package com.aireceptionist.whatsapp;

import com.aireceptionist.AbstractModuleTest;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;

@ApplicationModuleTest
class WhatsAppModuleTest extends AbstractModuleTest {

    @Test
    void moduleBoundaryCompliance() {
        // Passes when Spring Modulith detects no cross-module boundary violations
    }
}
