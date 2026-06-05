package com.aireceptionist.tenant.domain;

import java.time.Instant;
import java.util.UUID;

public class BusinessTenant {

    private UUID id;
    private String businessName;
    private String ownerName;
    private String ownerPhone;
    private String businessPhone;
    private String email;
    private String passwordHash;
    private TenantStatus status;
    private String tier;
    private String preferredLanguage;
    private String wabaId;
    private String phoneNumberId;
    private String googleReviewUrl;
    private String location;
    private String businessHours;
    private Instant onboardedAt;
    private Instant whatsappConnectedAt;
    private Instant createdAt;
    private Instant updatedAt;

    private BusinessTenant(UUID id, String businessName, String ownerName, String ownerPhone, String businessPhone,
                           String email, String passwordHash, TenantStatus status, String tier,
                           String preferredLanguage, String wabaId, String phoneNumberId,
                           String googleReviewUrl, String location, String businessHours, Instant onboardedAt,
                           Instant whatsappConnectedAt, Instant createdAt, Instant updatedAt) {
        this.id = id;
        this.businessName = businessName;
        this.ownerName = ownerName;
        this.ownerPhone = ownerPhone;
        this.businessPhone = businessPhone;
        this.email = email;
        this.passwordHash = passwordHash;
        this.status = status;
        this.tier = tier;
        this.preferredLanguage = preferredLanguage;
        this.wabaId = wabaId;
        this.phoneNumberId = phoneNumberId;
        this.googleReviewUrl = googleReviewUrl;
        this.location = location;
        this.businessHours = businessHours;
        this.onboardedAt = onboardedAt;
        this.whatsappConnectedAt = whatsappConnectedAt;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public static BusinessTenant register(String businessName, String ownerName, String ownerPhone,
                                          String businessPhone, String email, String passwordHash) {
        Instant now = Instant.now();
        return new BusinessTenant(
                UUID.randomUUID(),
                businessName,
                ownerName,
                ownerPhone,
                businessPhone,
                email,
                passwordHash,
                TenantStatus.PENDING_VERIFICATION,
                "BASIC",
                "en",
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                now,
                now
        );
    }

    public static BusinessTenant restore(UUID id, String businessName, String ownerName, String ownerPhone,
                                         String businessPhone, String email, String passwordHash,
                                         TenantStatus status, String tier, String preferredLanguage,
                                         String wabaId, String phoneNumberId, String googleReviewUrl,
                                         String location, String businessHours, Instant onboardedAt,
                                         Instant whatsappConnectedAt, Instant createdAt, Instant updatedAt) {
        return new BusinessTenant(
                id,
                businessName,
                ownerName,
                ownerPhone,
                businessPhone,
                email,
                passwordHash,
                status,
                tier,
                preferredLanguage,
                wabaId,
                phoneNumberId,
                googleReviewUrl,
                location,
                businessHours,
                onboardedAt,
                whatsappConnectedAt,
                createdAt,
                updatedAt
        );
    }

    public void activate() {
        this.status = TenantStatus.ACTIVE;
        this.updatedAt = Instant.now();
    }

    public void completeOnboarding(String shopName, String location, String businessHours, String preferredLanguage) {
        Instant now = Instant.now();
        this.businessName = shopName;
        this.location = location;
        this.businessHours = businessHours;
        this.preferredLanguage = preferredLanguage == null || preferredLanguage.isBlank() ? "en" : preferredLanguage;
        this.status = TenantStatus.ONBOARDING_COMPLETE;
        this.onboardedAt = now;
        this.updatedAt = now;
    }

    public void connectWhatsApp(String wabaId, String phoneNumberId) {
        Instant now = Instant.now();
        this.wabaId = wabaId;
        this.phoneNumberId = phoneNumberId;
        this.whatsappConnectedAt = now;
        this.status = TenantStatus.LIVE;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getBusinessName() {
        return businessName;
    }

    public String getOwnerName() {
        return ownerName;
    }

    public String getOwnerPhone() {
        return ownerPhone;
    }

    public String getBusinessPhone() {
        return businessPhone;
    }

    public String getEmail() {
        return email;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public TenantStatus getStatus() {
        return status;
    }

    public String getTier() {
        return tier;
    }

    public String getPreferredLanguage() {
        return preferredLanguage;
    }

    public String getWabaId() {
        return wabaId;
    }

    public String getPhoneNumberId() {
        return phoneNumberId;
    }

    public String getGoogleReviewUrl() {
        return googleReviewUrl;
    }

    public String getLocation() {
        return location;
    }

    public String getBusinessHours() {
        return businessHours;
    }

    public Instant getOnboardedAt() {
        return onboardedAt;
    }

    public Instant getWhatsappConnectedAt() {
        return whatsappConnectedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
