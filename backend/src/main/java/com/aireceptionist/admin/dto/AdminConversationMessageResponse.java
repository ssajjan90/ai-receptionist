package com.aireceptionist.admin.dto;

import com.aireceptionist.conversation.entity.ConversationMessage;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class AdminConversationMessageResponse {

    private Long id;
    private ConversationMessage.Direction direction;
    private String message;
    private LocalDateTime createdAt;
}
