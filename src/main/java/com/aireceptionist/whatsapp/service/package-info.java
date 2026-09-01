// WhatsAppNotificationService is used cross-module by leads.service.DailySummaryJob. Exposing
// the whole package (matching this codebase's established coarse-grained NamedInterface
// convention) rather than extracting just that one class, for consistency with every other
// module here. See deferred W82 (2026-09-01 Spring Modulith cycle fix).
@org.springframework.modulith.NamedInterface("service")
package com.aireceptionist.whatsapp.service;
