package com.aireceptionist.knowledge.dto;

import com.aireceptionist.knowledge.entity.KnowledgeBase.KnowledgeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class KnowledgeRequest {

    @NotNull(message = "Knowledge type is required")
    private KnowledgeType type;

    @NotBlank(message = "Question is required")
    private String question;

    @NotBlank(message = "Answer is required")
    private String answer;

    private boolean active = true;
}
