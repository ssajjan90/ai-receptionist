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
            .withPassword("test")
            // CI investigation (2026-09-03): a full `mvn verify` batch run creates one distinct
            // Spring ApplicationContext per test class (~32 AbstractIntegrationTest subclasses,
            // many with different @MockBean/@TestConfiguration combos so Spring's context cache
            // can't reuse them), and Spring's default test-context cache holds up to 32 contexts
            // alive at once. Each context's default HikariCP pool (max 10) against Postgres's
            // default max_connections=100 means the full suite can demand 300+ connections against
            // a 100-connection ceiling — the exact `CannotGetJdbcConnection` cascade seen in CI.
            // Raised as headroom alongside the Hikari pool-size cap below (see configureProperties).
            .withCommand("postgres", "-c", "max_connections=300");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        // CI investigation (2026-09-03, see the postgres container's max_connections comment
        // above): capping each test ApplicationContext's own Hikari pool keeps the full-suite
        // batch run's peak concurrent connection demand (~32 contexts x this pool size) well under
        // Postgres's max_connections, instead of the Spring Boot default (max 10 per context).
        registry.add("spring.datasource.hikari.maximum-pool-size", () -> "3");
        registry.add("spring.datasource.hikari.minimum-idle", () -> "1");
        registry.add("spring.data.redis.host", redis::getHost);
        registry.add("spring.data.redis.port", () -> redis.getMappedPort(6379));
        registry.add("app.jwt.private-key", TestJwtKeys::privateKeyPem);
        registry.add("app.jwt.public-key", TestJwtKeys::publicKeyPem);
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
