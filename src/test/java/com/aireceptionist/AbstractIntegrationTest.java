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
            .withPassword("test");

    @Container
    @SuppressWarnings("resource")
    static final GenericContainer<?> redis = new GenericContainer<>("redis:7-alpine")
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
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
