// Story 6.2: exposed so the voice module can pre-generate a tenant's TTS greeting on
// TenantOnboardedEvent without reaching into tenant internals.
@org.springframework.modulith.NamedInterface("event")
package com.aireceptionist.tenant.event;
