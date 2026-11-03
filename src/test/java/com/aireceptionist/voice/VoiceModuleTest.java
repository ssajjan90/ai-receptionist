package com.aireceptionist.voice;

import com.aireceptionist.AbstractModuleTest;
import com.aireceptionist.AiReceptionistApplication;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.core.ApplicationModule;
import org.springframework.modulith.core.ApplicationModuleDependencies;
import org.springframework.modulith.core.ApplicationModules;

import static org.assertj.core.api.Assertions.assertThat;

// Full structural verification (with one documented exception: the leads<->whatsapp cycle) runs
// via the inherited moduleBoundaryCompliance() test in AbstractModuleTest -- pure static analysis,
// no Spring context needed. See deferred W82; see AbstractModuleTest for why this is no longer
// @ApplicationModuleTest (story 5.2's AdminModuleTest bean-wiring break under cross-module deps).
class VoiceModuleTest extends AbstractModuleTest {

    /**
     * Story 6.4 (AC4): voice reaches the shared AI pipeline (ai, knowledgebase via ai, common) but
     * never the whatsapp module — owner alerts go out as common.event events that whatsapp listens
     * to. The story named {@code @ApplicationModuleTest(mode = ALL_DEPENDENCIES)}; this codebase
     * moved every module test to static analysis (see AbstractModuleTest), which checks the same
     * dependency graph without bootstrapping a context.
     */
    @Test
    void voiceDoesNotDependOnWhatsApp() {
        ApplicationModules modules = ApplicationModules.of(AiReceptionistApplication.class);
        ApplicationModule voice = modules.getModuleByName("voice").orElseThrow();
        ApplicationModuleDependencies direct = voice.getDirectDependencies(modules);
        ApplicationModuleDependencies all = voice.getAllDependencies(modules);

        assertThat(direct.containsModuleNamed("whatsapp")).isFalse();
        assertThat(all.containsModuleNamed("whatsapp")).isFalse();
        assertThat(direct.containsModuleNamed("ai")).isTrue();
    }
}
