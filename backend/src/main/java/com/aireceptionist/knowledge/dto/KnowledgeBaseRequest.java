package com.aireceptionist.knowledge.dto;

import com.aireceptionist.knowledge.entity.IndustryType;
import com.aireceptionist.knowledge.entity.KnowledgeIntent;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.util.List;

@Data
public class KnowledgeBaseRequest {

    private Long tenantId;

    @NotNull(message = "Industry is required")
    private IndustryType industry;

    private String category;

    @NotNull(message = "Intent is required")
    private KnowledgeIntent intent;

    @NotBlank(message = "Question is required")
    @Size(max = 500, message = "Question must be at most 500 characters")
    private String question;

    @NotBlank(message = "Answer is required")
    @Size(max = 5000, message = "Answer must be at most 5000 characters")
    private String answer;

    @NotBlank(message = "Language is required")
    private String language;

    private List<String> altQuestions;
    private List<String> keywords;

    private Integer priority = 1;
    private boolean active = true;
}
