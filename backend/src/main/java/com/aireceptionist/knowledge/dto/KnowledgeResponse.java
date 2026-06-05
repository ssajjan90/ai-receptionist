package com.aireceptionist.knowledge.dto;

import com.aireceptionist.knowledge.entity.KnowledgeBase.KnowledgeType;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@Builder
public class KnowledgeResponse {

    private Long id;
    private Long tenantId;
    private KnowledgeType type;
    private String question;
    private String answer;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
