package com.aireceptionist.channel.dto;

import com.aireceptionist.channel.entity.ChannelConfig.ReceptionChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChannelConfigUpsertRequest {

    @NotNull(message = "Channel is required")
    private ReceptionChannel channel;

    @NotNull(message = "Enabled flag is required")
    private Boolean enabled;

    @NotBlank(message = "Bot display name is required")
    private String botDisplayName;

    @NotBlank(message = "Welcome message is required")
    private String welcomeMessage;

    private String webhookUrl;

    private String providerConfig;
}
