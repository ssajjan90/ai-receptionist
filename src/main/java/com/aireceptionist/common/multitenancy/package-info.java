// TenantContext is foundational infrastructure used across every module (whatsapp, leads,
// knowledgebase, admin, etc.) to propagate the current tenant id for RLS scoping.
// See deferred W82 (2026-09-01 Spring Modulith cycle fix).
@org.springframework.modulith.NamedInterface("multitenancy")
package com.aireceptionist.common.multitenancy;
