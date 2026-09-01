package com.aireceptionist.tenant.adapter.out.persistence;

import com.aireceptionist.tenant.domain.BusinessTenant;
import com.aireceptionist.tenant.domain.TenantStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "tenants")
public class BusinessTenantJpaEntity {

    @Id
    private UUID id;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(name = "owner_name", nullable = false)
    private String ownerName;

    @Column(name = "owner_phone", nullable = false)
    private String ownerPhone;

    @Column(name = "phone_number", nullable = false)
    private String businessPhone;

    @Column(nullable = false)
    private String email;

    @Column(name = "password_hash", nullable = false)
    private String passwordHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private TenantStatus status;

    @Column(nullable = false)
    private String tier;

    @Column(name = "preferred_language", nullable = false)
    private String preferredLanguage;

    @Column(name = "waba_id")
    private String wabaId;

    @Column(name = "phone_number_id")
    private String phoneNumberId;

    @Column(name = "google_review_url")
    private String googleReviewUrl;

    @Column
    private String location;

    @Column(name = "business_hours")
    private String businessHours;

    @Column(name = "onboarded_at")
    private Instant onboardedAt;

    @Column(name = "whatsapp_connected_at")
    private Instant whatsappConnectedAt;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Column(name = "termination_scheduled_at")
    private Instant terminationScheduledAt;

    protected BusinessTenantJpaEntity() {
    }

    static BusinessTenantJpaEntity fromDomain(BusinessTenant tenant) {
        BusinessTenantJpaEntity entity = new BusinessTenantJpaEntity();
        entity.id = tenant.getId();
        entity.businessName = tenant.getBusinessName();
        entity.ownerName = tenant.getOwnerName();
        entity.ownerPhone = tenant.getOwnerPhone();
        entity.businessPhone = tenant.getBusinessPhone();
        entity.email = tenant.getEmail();
        entity.passwordHash = tenant.getPasswordHash();
        entity.status = tenant.getStatus();
        entity.tier = tenant.getTier();
        entity.preferredLanguage = tenant.getPreferredLanguage();
        entity.wabaId = tenant.getWabaId();
        entity.phoneNumberId = tenant.getPhoneNumberId();
        entity.googleReviewUrl = tenant.getGoogleReviewUrl();
        entity.location = tenant.getLocation();
        entity.businessHours = tenant.getBusinessHours();
        entity.onboardedAt = tenant.getOnboardedAt();
        entity.whatsappConnectedAt = tenant.getWhatsappConnectedAt();
        entity.createdAt = tenant.getCreatedAt();
        entity.updatedAt = tenant.getUpdatedAt();
        entity.terminationScheduledAt = tenant.getTerminationScheduledAt();
        return entity;
    }

    BusinessTenant toDomain() {
        return BusinessTenant.restore(
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

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }
}
