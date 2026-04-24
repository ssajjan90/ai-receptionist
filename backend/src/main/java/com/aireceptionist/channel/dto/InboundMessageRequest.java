package com.aireceptionist.channel.dto;

import com.aireceptionist.channel.CommunicationChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class InboundMessageRequest {

    @NotNull(message = "Tenant ID is required")
    private Long tenantId;

    @NotNull(message = "Channel is required")
    private CommunicationChannel channel;

    private String customerName;

    private String customerPhone;

    private String customerEmail;

    @NotBlank(message = "Message is required")
    private String message;

    private String externalMessageId;

    private Map<String, Object> metadata;
}
