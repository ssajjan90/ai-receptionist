package com.aireceptionist.chat.dto;

import com.aireceptionist.chat.entity.ChatHistory.ChatChannel;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class ChatRequest {

    @NotNull(message = "Tenant ID is required")
    private Long tenantId;

    @NotBlank(message = "Customer phone is required")
    @Pattern(regexp = "^\\+?[0-9\\s\\-().]{7,20}$", message = "Invalid phone number")
    private String customerPhone;

    @NotBlank(message = "Message cannot be empty")
    @Size(max = 2000, message = "Message must be at most 2000 characters")
    private String message;

    @NotNull(message = "Channel is required")
    private ChatChannel channel;
}
