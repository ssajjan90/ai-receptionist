package com.aireceptionist.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

/**
 * Code review (2026-09-01): {@code tenantIds} capped at 100 — an unbounded list let one call
 * trigger unlimited WhatsApp sends, since {@code AdminController.broadcast} only ever consumed
 * one rate-limit token per call, not one per tenant. {@code @NotNull} on the element type (not
 * just {@code @NotEmpty} on the list) rejects a null entry before it can NPE deeper in
 * {@code AdminService.broadcast}'s per-tenant failure handling.
 */
public record BroadcastRequest(
        @NotEmpty @Size(max = 100) List<@NotNull UUID> tenantIds,
        @NotBlank @Size(max = 1000) String message
) {
}
