package com.aireceptionist.tenant.adapter.in.web.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;

import java.util.List;

public record OnboardingRequest(
        @NotBlank String shopName,
        @NotBlank String location,
        @NotBlank String businessHours,
        String preferredLanguage,
        @Valid @NotEmpty List<ProductEntry> topProducts,
        @Valid List<FaqEntry> commonFaqs
) {
    public String resolvedPreferredLanguage() {
        return preferredLanguage == null || preferredLanguage.isBlank() ? "en" : preferredLanguage;
    }
}
