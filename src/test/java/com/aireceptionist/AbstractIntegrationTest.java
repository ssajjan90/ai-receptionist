package com.aireceptionist;

import com.aireceptionist.common.event.AiReceptionistEvent;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.tenant.port.out.OwnerNotificationPort;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@ActiveProfiles("test")
public abstract class AbstractIntegrationTest {

    // Deliberately NOT @Container: that annotation hands lifecycle to the JUnit5 Testcontainers
    // extension, whose beforeAll/afterAll bookkeeping is scoped per test class. With ~32
    // AbstractIntegrationTest subclasses each forcing a distinct Spring ApplicationContext (see
    // ci.yml's 2026-09-03 comment), that per-class bookkeeping raced @DynamicPropertySource's
    // context-build-time property resolution and caused these "singleton" containers to be
    // recreated (new Docker container, new mapped port) once per distinct context instead of once
    // per JVM — evidenced by CI logs showing 4 separate "Creating container for image" events in
    // a single `mvn test` run, each immediately following a new @TestConfiguration-bearing
    // subclass, with older contexts' listeners then failing to reconnect to the now-abandoned
    // port. Starting eagerly in the static block below, with no @Container/@Testcontainers
    // involvement for these two fields, is the pattern Testcontainers' own docs recommend for
    // singleton containers and removes that race entirely.
    @SuppressWarnings("resource")
    // W84: max_connections raised from the default 100 — one shared server backs every cached
    // test context's pool (see src/test/resources/application-test.yml). fsync=off is
    // Testcontainers' own default command, kept since withCommand replaces it.
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aireceptionist_test")
            .withUsername("test")
            .withPassword("test")
            .withCommand("postgres", "-c", "fsync=off", "-c", "max_connections=300");

    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    static {
        postgres.start();
        redis.start();
    }

    // W99: Testcontainers' bootstrap Postgres user is always a superuser regardless of the name
    // given to .withUsername(...) — that's how the official postgres image provisions its first
    // role — so pointing spring.datasource.* straight at it (as this class used to) makes every
    // RLS policy in V8__create_rls_policies.sql a silent no-op: superusers always bypass RLS,
    // FORCE ROW LEVEL SECURITY notwithstanding. app_runtime is a second, non-superuser role
    // created below purely for the app's runtime datasource; Flyway keeps using the bootstrap
    // superuser (via spring.flyway.*) so it can still own tables and run CREATE EXTENSION.
    private static final String APP_RUNTIME_USERNAME = "app_runtime";
    private static final String APP_RUNTIME_PASSWORD = "app_runtime_password";

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        provisionNonSuperuserRuntimeRole();

        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", () -> APP_RUNTIME_USERNAME);
        registry.add("spring.datasource.password", () -> APP_RUNTIME_PASSWORD);
        registry.add("spring.flyway.url", postgres::getJdbcUrl);
        registry.add("spring.flyway.user", postgres::getUsername);
        registry.add("spring.flyway.password", postgres::getPassword);
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.jwt.private-key", TestJwtKeys::privateKeyPem);
        registry.add("app.jwt.public-key", TestJwtKeys::publicKeyPem);
    }

    // Runs once per test class against the shared static container, before the Spring context
    // (and therefore Flyway) starts; idempotent so repeat invocations across test classes are
    // harmless. Must run before Flyway creates any tables — ALTER DEFAULT PRIVILEGES only affects
    // tables created after it, by the role named in FOR ROLE.
    private static void provisionNonSuperuserRuntimeRole() {
        try (Connection connection = DriverManager.getConnection(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
             Statement statement = connection.createStatement()) {
            statement.execute("""
                    DO $$ BEGIN
                        IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'app_runtime') THEN
                            CREATE ROLE app_runtime WITH LOGIN PASSWORD 'app_runtime_password'
                                NOSUPERUSER NOCREATEDB NOCREATEROLE NOBYPASSRLS;
                        END IF;
                    END $$;
                    """);
            statement.execute("GRANT CONNECT ON DATABASE " + postgres.getDatabaseName() + " TO app_runtime");
            statement.execute("GRANT USAGE ON SCHEMA public TO app_runtime");
            statement.execute("ALTER DEFAULT PRIVILEGES FOR ROLE " + postgres.getUsername()
                    + " IN SCHEMA public GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO app_runtime");
        } catch (SQLException ex) {
            throw new IllegalStateException("Failed to provision non-superuser app_runtime role for tests", ex);
        }
    }

    @Autowired private ApplicationEventPublisher eventPublisher;
    @Autowired private PlatformTransactionManager transactionManager;

    // Two compounding bugs made every @ApplicationModuleListener-driven integration test hang on
    // its awaitility assertion, independent of (and on top of) the container-restart bug fixed
    // above:
    //
    // 1. @ApplicationModuleListener expands to @Async @TransactionalEventListener with
    //    fallbackExecution left at its default (false, see the annotation's own source) — so if
    //    there is no active, synchronized transaction at the moment of publishEvent(), the listener
    //    is silently skipped: no exception, no log line, it just never runs. Every test previously
    //    called eventPublisher.publishEvent(...) directly from a plain @Test method with no
    //    transaction in scope. Wrapping the publish in a TransactionTemplate gives it a real
    //    transaction that actually commits (unlike Spring Test's @Transactional, which rolls back
    //    by default), so AFTER_COMMIT synchronization fires and the async listener is scheduled.
    //
    // 2. Once the listener does fire, it runs on AsyncConfig's "taskExecutor" thread — and because
    //    @Async dispatches to that thread BEFORE @Transactional (bundled into the same annotation)
    //    starts the listener's transaction, Hibernate resolves the tenant for that transaction's
    //    session on this new thread before the listener method body runs. TenantContext is a plain
    //    ThreadLocal, so it doesn't cross onto the new thread by itself (this is exactly the real
    //    production bug fixed in AsyncConfig's TaskDecorator, not just a test artifact — confirmed
    //    live via TenantConnectionProvider logging: every getConnection() call resolved
    //    tenantIdentifier=DEFAULT). Setting TenantContext here, before publishing — mirroring what
    //    WhatsAppWebhookController and friends already do on the real inbound-webhook path before
    //    they publish — lets AsyncConfig's decorator propagate it correctly.
    protected void publishAndCommit(Object event) {
        String tenantId = event instanceof AiReceptionistEvent aiEvent ? aiEvent.getTenantId() : null;
        String previousTenant = TenantContext.getCurrentTenant();
        if (tenantId != null) {
            TenantContext.setCurrentTenant(tenantId);
        }
        try {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> eventPublisher.publishEvent(event));
        } finally {
            if (previousTenant != null) {
                TenantContext.setCurrentTenant(previousTenant);
            } else {
                TenantContext.clear();
            }
        }
    }

    @TestConfiguration
    static class TestNotificationConfig {

        @Bean
        @Primary
        CapturingOwnerNotificationPort capturingOwnerNotificationPort() {
            return new CapturingOwnerNotificationPort();
        }
    }

    public static class CapturingOwnerNotificationPort implements OwnerNotificationPort {

        private final Map<String, String> otpByPhone = new ConcurrentHashMap<>();

        @Override
        public void sendOtp(String phone, String otp) {
            otpByPhone.put(phone, otp);
        }

        public String otpFor(String phone) {
            return otpByPhone.get(phone);
        }

        public void clear() {
            otpByPhone.clear();
        }
    }
}
