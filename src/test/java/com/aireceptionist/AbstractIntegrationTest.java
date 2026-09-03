package com.aireceptionist;

import com.aireceptionist.tenant.port.out.OwnerNotificationPort;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
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

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("aireceptionist_test")
            .withUsername("test")
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

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
