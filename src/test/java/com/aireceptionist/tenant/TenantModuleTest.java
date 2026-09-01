package com.aireceptionist.tenant;

import com.aireceptionist.AbstractModuleTest;

// Full structural verification (with one documented exception: the leads<->whatsapp cycle) runs
// via the inherited moduleBoundaryCompliance() test in AbstractModuleTest -- pure static analysis,
// no Spring context needed. See deferred W82; see AbstractModuleTest for why this is no longer
// @ApplicationModuleTest (story 5.2's AdminModuleTest bean-wiring break under cross-module deps).
class TenantModuleTest extends AbstractModuleTest {
}
