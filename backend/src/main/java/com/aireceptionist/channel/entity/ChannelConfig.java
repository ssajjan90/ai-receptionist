package com.aireceptionist.channel.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "channel_configs",
        uniqueConstraints = {
                @UniqueConstraint(name = "uk_channel_configs_tenant_channel", columnNames = {"tenant_id", "channel"})
        }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ChannelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ReceptionChannel channel;

    @Builder.Default
    @Column(nullable = false)
    private Boolean enabled = true;

    @Column(name = "bot_display_name")
    private String botDisplayName;

    @Column(name = "welcome_message", columnDefinition = "TEXT")
    private String welcomeMessage;

    @Column(name = "webhook_url")
    private String webhookUrl;

    @Column(name = "provider_config", columnDefinition = "TEXT")
    private String providerConfig;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum ReceptionChannel {
        CHAT,
        WHATSAPP,
        VOICE,
        INSTAGRAM,
        FACEBOOK_MESSENGER
    }
}
