package com.aireceptionist.whatsapp.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "whatsapp_messages")
public class WhatsAppMessage {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "message_id")
    private String messageId;

    @Enumerated(EnumType.STRING)
    @Column(name = "sender_type", nullable = false)
    private SenderType senderType;

    @Enumerated(EnumType.STRING)
    @Column(name = "direction")
    private MessageDirection direction;

    @Column(name = "sender_phone", nullable = false)
    private String senderPhone;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "confidence_score")
    private BigDecimal confidenceScore;

    @Column(name = "language")
    private String language;

    @Enumerated(EnumType.STRING)
    @Column(name = "delivery_status")
    private DeliveryStatus deliveryStatus;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    protected WhatsAppMessage() {
    }

    public static WhatsAppMessage inboundCustomer(UUID tenantId, String messageId,
                                                  String senderPhone, String content) {
        WhatsAppMessage msg = new WhatsAppMessage();
        msg.tenantId = tenantId;
        msg.messageId = messageId;
        msg.senderType = SenderType.CUSTOMER;
        msg.direction = MessageDirection.INBOUND;
        msg.senderPhone = senderPhone;
        msg.content = content == null ? "" : content;
        return msg;
    }

    public static WhatsAppMessage outboundAi(UUID tenantId, String recipientPhone,
                                             String content, double confidence, String language) {
        WhatsAppMessage msg = new WhatsAppMessage();
        msg.tenantId = tenantId;
        msg.senderType = SenderType.AI;
        msg.direction = MessageDirection.OUTBOUND;
        msg.senderPhone = recipientPhone;
        msg.content = content == null ? "" : content;
        msg.confidenceScore = BigDecimal.valueOf(confidence);
        msg.language = language;
        msg.deliveryStatus = DeliveryStatus.PENDING;
        return msg;
    }

    public static WhatsAppMessage inboundOwner(UUID tenantId, String senderPhone, String content) {
        WhatsAppMessage msg = new WhatsAppMessage();
        msg.tenantId = tenantId;
        msg.senderType = SenderType.OWNER;
        msg.direction = MessageDirection.INBOUND;
        msg.senderPhone = senderPhone;
        msg.content = content == null ? "" : content;
        return msg;
    }

    @PrePersist
    void prePersist() {
        if (receivedAt == null) {
            receivedAt = Instant.now();
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getTenantId() {
        return tenantId;
    }

    public String getMessageId() {
        return messageId;
    }

    public SenderType getSenderType() {
        return senderType;
    }

    public MessageDirection getDirection() {
        return direction;
    }

    public String getSenderPhone() {
        return senderPhone;
    }

    public String getContent() {
        return content;
    }

    public BigDecimal getConfidenceScore() {
        return confidenceScore;
    }

    public String getLanguage() {
        return language;
    }

    public Instant getReceivedAt() {
        return receivedAt;
    }

    public void setConfidenceScore(BigDecimal confidenceScore) {
        this.confidenceScore = confidenceScore;
    }

    public void setLanguage(String language) {
        this.language = language;
    }

    public DeliveryStatus getDeliveryStatus() {
        return deliveryStatus;
    }

    public void markSent() {
        this.deliveryStatus = DeliveryStatus.SENT;
    }

    public void markFailed() {
        this.deliveryStatus = DeliveryStatus.FAILED;
    }
}
