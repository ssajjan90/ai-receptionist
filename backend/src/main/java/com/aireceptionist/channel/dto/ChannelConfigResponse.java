package com.aireceptionist.channel.dto;

import com.aireceptionist.channel.entity.ChannelConfig.ReceptionChannel;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class ChannelConfigResponse {

    private Long id;
    private Long tenantId;
    private ReceptionChannel channel;
    private Boolean enabled;
    private String botDisplayName;
    private String welcomeMessage;
    private String webhookUrl;
    private String providerConfig;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
