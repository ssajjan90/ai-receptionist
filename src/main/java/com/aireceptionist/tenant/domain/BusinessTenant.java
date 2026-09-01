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
    private Instant terminationScheduledAt;

    private BusinessTenant(UUID id, String businessName, String ownerName, String ownerPhone, String businessPhone,
                           String email, String passwordHash, TenantStatus status, String tier,
                           String preferredLanguage, String wabaId, String phoneNumberId,
                           String googleReviewUrl, String location, String businessHours, Instant onboardedAt,
                           Instant whatsappConnectedAt, Instant createdAt, Instant updatedAt,
                           Instant terminationScheduledAt) {
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
        this.terminationScheduledAt = terminationScheduledAt;
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
                now,
                null
        );
    }

    public static BusinessTenant restore(UUID id, String businessName, String ownerName, String ownerPhone,
                                         String businessPhone, String email, String passwordHash,
                                         TenantStatus status, String tier, String preferredLanguage,
                                         String wabaId, String phoneNumberId, String googleReviewUrl,
                                         String location, String businessHours, Instant onboardedAt,
                                         Instant whatsappConnectedAt, Instant createdAt, Instant updatedAt,
                                         Instant terminationScheduledAt) {
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
                updatedAt,
                terminationScheduledAt
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

    /**
     * Story 5.2 (AC1): admin action — inbound WhatsApp traffic is rejected while SUSPENDED (see
     * {@code WhatsAppMessageService.onInboundMessage}). AC1 also names voice traffic, but the
     * {@code voice} module is still an empty placeholder (code review, 2026-09-01) — no voice
     * inbound path exists yet to guard, so that half of AC1 is not implementable in this story.
     * See deferred W97: apply the same guard once voice inbound handling is built.
     */
    public void suspend() {
        this.status = TenantStatus.SUSPENDED;
        this.updatedAt = Instant.now();
    }

    /**
     * Story 5.2 (AC2): admin action — restores normal processing. Also clears any pending
     * termination schedule, since resuming service should cancel a scheduled erasure.
     */
    public void reactivate() {
        this.status = TenantStatus.LIVE;
        this.terminationScheduledAt = null;
        this.updatedAt = Instant.now();
    }

    /** Story 5.2 (AC3, NFR28): admin action — schedules data erasure after a 30-day retention window. */
    public void terminate() {
        Instant now = Instant.now();
        this.status = TenantStatus.TERMINATED;
        this.terminationScheduledAt = now.plus(30, java.time.temporal.ChronoUnit.DAYS);
        this.updatedAt = now;
    }

    /**
     * Story 5.2 (Task 4): set once ScheduledDataRetentionJob has erased this tenant's data.
     * Story 5.5 code review (decision: scrub now): child-table erasure alone (knowledge entries,
     * leads, messages — {@code TenantDataRightsUseCase.eraseTenantData}) leaves this row's own PII
     * untouched, which is not real DPDP erasure. Scrubbed fields get a unique-but-non-identifying
     * placeholder rather than {@code null}: the JPA entity maps owner_name/owner_phone/email/
     * password_hash as {@code nullable = false} (even though the DB migration itself allows null),
     * and phone_number/owner_phone/email are DB-unique — a shared literal like "" or "ERASED" would
     * collide across more than one erased tenant. Deriving the placeholder from this tenant's own
     * id keeps every erased row's placeholder unique while freeing the real phone/email for a
     * future, unrelated registration (uq_tenants_owner_phone / uq_tenants_email are partial indexes
     * that stop applying once the real value is gone).
     */
    public void markErased() {
        this.status = TenantStatus.ERASED;
        String tag = id.toString().replace("-", "");
        this.businessName = "Erased Business";
        this.ownerName = "Erased Owner";
        this.ownerPhone = ("EROWN-" + tag).substring(0, 20);
        this.businessPhone = ("ERBIZ-" + tag).substring(0, 20);
        this.email = "erased-" + id + "@erased.invalid";
        this.passwordHash = "erased-no-login-possible";
        this.updatedAt = Instant.now();
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

    public Instant getTerminationScheduledAt() {
        return terminationScheduledAt;
    }
}
