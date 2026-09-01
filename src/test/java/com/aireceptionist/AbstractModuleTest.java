package com.aireceptionist;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.lifecycle.Startables;

@Testcontainers(disabledWithoutDocker = true)
@ActiveProfiles("test")
public abstract class AbstractModuleTest {

    /**
     * Runs full Spring Modulith structural verification, tolerating exactly one known,
     * intentional exception: the leads <-> whatsapp module cycle. That cycle is a deliberate
     * bidirectional saga (WhatsApp captures a lead -> leads reacts; leads captures a lead ->
     * whatsapp notifies the owner) — Modulith flags any such pair as a cycle regardless of
     * whether the coupling is loose (event-driven) or tight, so this is a documented, accepted
     * exception rather than a defect. Every {@code *ModuleTest} subclass sets
     * {@code @ApplicationModuleTest(verifyAutomatically = false)} and inherits this test so a
     * genuinely new violation anywhere in the app still fails the build.
     * See deferred W82 (2026-09-01 Spring Modulith cycle fix, code review of story 5-1).
     */
    @Test
    void moduleBoundaryCompliance() {
        Violations violations = ApplicationModules.of(AiReceptionistApplication.class).detectViolations();
        Violations unexpected = violations.filter(v -> !isAcceptedLeadsWhatsappCycle(v.getMessage()));
        unexpected.throwIfPresent();
    }

    private static boolean isAcceptedLeadsWhatsappCycle(String message) {
        return message.contains("Cycle detected")
                && message.contains("Slice leads")
                && message.contains("Slice whatsapp");
    }

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

    static {
        // Explicit eager start: @DynamicPropertySource below reads postgres.getJdbcUrl()/
        // redis.getMappedPort() during context bootstrap, which requires the containers already
        // running. @Testcontainers normally guarantees start-before-bootstrap ordering, but that
        // ordering has proven unreliable for this class specifically (empty @ApplicationModuleTest
        // subclasses with everything inherited from here) — start explicitly as a defensive fix.
        Startables.deepStart(postgres, redis).join();
    }

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
}
