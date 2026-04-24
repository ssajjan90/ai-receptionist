package com.aireceptionist.channel.entity;

import com.aireceptionist.channel.CommunicationChannel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import java.time.LocalDateTime;

@Entity
@Table(
        name = "tenant_channel_config",
        indexes = {
                @Index(name = "idx_tenant_channel_config_tenant_id", columnList = "tenant_id"),
                @Index(name = "idx_tenant_channel_config_channel_phone", columnList = "channel, external_phone_number_id", unique = true)
        }
)
@EntityListeners(AuditingEntityListener.class)
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TenantChannelConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "tenant_id", nullable = false)
    private Long tenantId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private CommunicationChannel channel;

    @Column(name = "provider_name", nullable = false)
    private String providerName;

    @Column(name = "external_phone_number_id", nullable = false)
    private String externalPhoneNumberId;

    @Column(name = "access_token")
    private String accessToken;

    @Column(nullable = false)
    private Boolean enabled;

    @CreatedDate
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
