package com.aireceptionist.chat.dto;

import com.aireceptionist.chat.entity.ChatHistory.ChatChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class ChatRequest {

    @NotNull(message = "Tenant ID is required")
    private Long tenantId;

    @NotBlank(message = "Customer phone is required")
    private String customerPhone;

    @NotBlank(message = "Message cannot be empty")
    private String message;

    @NotNull(message = "Channel is required")
    private ChatChannel channel;
}
