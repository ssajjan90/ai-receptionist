package com.aireceptionist;

import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModules;
import org.springframework.modulith.core.Violations;

/**
 * Shared base for every {@code *ModuleTest} class. Deliberately has no {@code @SpringBootTest}/
 * {@code @ApplicationModuleTest}/Testcontainers dependency: {@link ApplicationModules#of} performs
 * pure static bytecode analysis, no live bean graph needed. Using {@code @ApplicationModuleTest}
 * here used to force a real module-scoped Spring context to bootstrap for every subclass, which
 * broke as soon as any module's beans reached a cross-module dependency chain the restricted
 * bootstrap mode couldn't satisfy (root-level {@code config} package beans in particular) — see
 * story 5.2's fix for {@code AdminModuleTest} and deferred W83 for {@code WhatsAppModuleTest}.
 * None of these classes ever added a real module-scoped test needing that context anyway.
 */
public abstract class AbstractModuleTest {

    /**
     * Runs full Spring Modulith structural verification, tolerating exactly one known,
     * intentional exception: the leads <-> whatsapp module cycle. That cycle is a deliberate
     * bidirectional saga (WhatsApp captures a lead -> leads reacts; leads captures a lead ->
     * whatsapp notifies the owner) — Modulith flags any such pair as a cycle regardless of
     * whether the coupling is loose (event-driven) or tight, so this is a documented, accepted
     * exception rather than a defect. A genuinely new violation anywhere in the app still fails.
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
}
