package com.aireceptionist.leads;

import com.aireceptionist.AbstractModuleTest;
import org.junit.jupiter.api.Test;
import org.springframework.modulith.test.ApplicationModuleTest;

@ApplicationModuleTest
class LeadsModuleTest extends AbstractModuleTest {

    @Test
    void moduleBoundaryCompliance() {
        // Passes when Spring Modulith detects no cross-module boundary violations
    }
}
