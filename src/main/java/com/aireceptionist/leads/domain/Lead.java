package com.aireceptionist.leads.domain;

import com.aireceptionist.common.util.Sha256;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "leads")
public class Lead {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "name")
    private String customerName;

    @Column(name = "phone")
    private String phone;

    @Column(name = "intent")
    private String productIntent;

    @Enumerated(EnumType.STRING)
    @Column(name = "channel", nullable = false)
    private LeadChannel channel;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private LeadStatus status;

    @Column(name = "consent_timestamp", nullable = false)
    private Instant consentTimestamp;

    @Column(name = "consent_channel", nullable = false)
    private String consentChannel;

    @Column(name = "erased", nullable = false)
    private Boolean erased;

    // Story 5.3: SHA-256 of `phone`, deliberately NOT nulled by erase() — see its javadoc.
    @Column(name = "phone_hash")
    private String phoneHash;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    @Column(name = "version", nullable = false)
    private Long version;

    protected Lead() {}

    public static Lead create(UUID tenantId, String customerName, String phone,
                              String productIntent, LeadChannel channel, String consentChannel,
                              Instant consentedAt) {
        Lead lead = new Lead();
        lead.id = UUID.randomUUID();
        lead.tenantId = tenantId;
        lead.customerName = customerName;
        lead.phone = phone;
        lead.productIntent = productIntent;
        lead.channel = channel;
        lead.status = LeadStatus.NEW;
        lead.consentTimestamp = consentedAt != null ? consentedAt : Instant.now();
        lead.consentChannel = consentChannel;
        lead.erased = false;
        lead.phoneHash = phone == null ? null : Sha256.hex(phone);
        return lead;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (createdAt == null) createdAt = now;
        if (updatedAt == null) updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public void updateStatus(LeadStatus newStatus) {
        this.status = Objects.requireNonNull(newStatus, "newStatus must not be null");
    }

    /**
     * {@code phoneHash} is deliberately NOT nulled here. It's a one-way SHA-256 hash — not PII,
     * not reversible to the original phone — kept so the admin conversation viewer (story 5.3)
     * can still detect "this WhatsApp message's sender was an erased lead" and redact content,
     * without either retaining the raw phone or being unable to identify erased conversations at
     * all.
     */
    public void erase() {
        this.customerName = null;
        this.phone = null;
        this.erased = true;
    }

    public UUID getId() { return id; }
    public UUID getTenantId() { return tenantId; }
    public String getCustomerName() { return customerName; }
    public String getPhone() { return phone; }
    public String getProductIntent() { return productIntent; }
    public LeadChannel getChannel() { return channel; }
    public LeadStatus getStatus() { return status; }
    public Instant getConsentTimestamp() { return consentTimestamp; }
    public String getConsentChannel() { return consentChannel; }
    public Boolean getErased() { return erased; }
    public String getPhoneHash() { return phoneHash; }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
}
