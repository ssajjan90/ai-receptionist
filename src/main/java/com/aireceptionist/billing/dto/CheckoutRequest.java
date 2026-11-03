package com.aireceptionist.billing.dto;

import jakarta.validation.constraints.NotBlank;

/** Story 7.2 (AC1). */
public record CheckoutRequest(@NotBlank String planId) {
}
