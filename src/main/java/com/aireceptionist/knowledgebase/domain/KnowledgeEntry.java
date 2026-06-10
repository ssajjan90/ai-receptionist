package com.aireceptionist.knowledgebase.domain;

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
@Table(name = "knowledge_entries")
public class KnowledgeEntry {

    @Id
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "entry_type", nullable = false)
    private EntryType type;

    @Column
    private String question;

    @Column(name = "product_name")
    private String productName;

    @Column
    private String answer;

    @Column
    private String price;

    @Column(nullable = false)
    private String source;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    protected KnowledgeEntry() {
    }

    private KnowledgeEntry(UUID tenantId, EntryType type, String source) {
        this.id = UUID.randomUUID();
        this.tenantId = tenantId;
        this.type = type;
        this.source = source;
    }

    public static KnowledgeEntry onboardingProduct(UUID tenantId, String productName, String price) {
        return product(tenantId, productName, price, "WIZARD");
    }

    public static KnowledgeEntry product(UUID tenantId, String productName, String price, String source) {
        KnowledgeEntry entry = new KnowledgeEntry(tenantId, EntryType.PRODUCT, source);
        entry.productName = productName;
        entry.question = productName;
        entry.answer = price;
        entry.price = price;
        return entry;
    }

    public static KnowledgeEntry onboardingFaq(UUID tenantId, String question, String answer) {
        KnowledgeEntry entry = new KnowledgeEntry(tenantId, EntryType.FAQ, "WIZARD");
        entry.question = question;
        entry.answer = answer;
        return entry;
    }

    public void updateProduct(String price) {
        updateProduct(price, "WIZARD");
    }

    public void updateProduct(String price, String source) {
        this.price = price;
        this.answer = price;
        this.source = source;
        this.updatedAt = Instant.now();
    }

    public void updateFaq(String answer) {
        this.answer = answer;
        this.source = "WIZARD";
        this.updatedAt = Instant.now();
    }

    public void updateFaq(String answer, String source) {
        this.answer = answer;
        this.source = source;
        this.updatedAt = Instant.now();
    }

    public static KnowledgeEntry faq(UUID tenantId, String question, String answer, String source) {
        KnowledgeEntry entry = new KnowledgeEntry(tenantId, EntryType.FAQ, source);
        entry.question = question;
        entry.answer = answer;
        return entry;
    }

    @PrePersist
    void prePersist() {
        Instant now = Instant.now();
        if (id == null) {
            id = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void preUpdate() {
        updatedAt = Instant.now();
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public EntryType getType() {
        return type;
    }

    public String getQuestion() {
        return question;
    }

    public String getProductName() {
        return productName;
    }

    public String getAnswer() {
        return answer;
    }

    public String getPrice() {
        return price;
    }

    public String getSource() {
        return source;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
