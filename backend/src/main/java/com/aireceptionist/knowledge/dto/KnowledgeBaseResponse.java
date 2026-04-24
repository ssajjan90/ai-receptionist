package com.aireceptionist.knowledge.dto;

import com.aireceptionist.knowledge.entity.IndustryType;
import com.aireceptionist.knowledge.entity.KnowledgeIntent;
import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
@Builder
public class KnowledgeBaseResponse {

    private Long id;
    private Long tenantId;
    private IndustryType industry;
    private String category;
    private KnowledgeIntent intent;
    private String question;
    private String answer;
    private String language;
    private List<String> altQuestions;
    private List<String> keywords;
    private Integer priority;
    private boolean active;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
