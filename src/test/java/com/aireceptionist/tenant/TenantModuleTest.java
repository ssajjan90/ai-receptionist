package com.aireceptionist.tenant;

import com.aireceptionist.AbstractModuleTest;
import org.springframework.modulith.test.ApplicationModuleTest;

// verifyAutomatically = false: full-app structural verification runs via the inherited
// moduleBoundaryCompliance() test in AbstractModuleTest, which tolerates one documented
// exception (the leads<->whatsapp cycle). See deferred W82.
@ApplicationModuleTest(verifyAutomatically = false)
class TenantModuleTest extends AbstractModuleTest {
}
