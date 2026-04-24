package com.aireceptionist.admin.dto;

import com.aireceptionist.channel.CommunicationChannel;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class AdminConversationResponse {

    private Long id;
    private Long tenantId;
    private CommunicationChannel channel;
    private String customerPhone;
    private LocalDateTime createdAt;
    private List<AdminConversationMessageResponse> messages;
}
