package com.aireceptionist.knowledgebase.event;

import com.aireceptionist.common.event.AiReceptionistEvent;

import java.time.Instant;
import java.util.UUID;

public class KbTrainingCompletedEvent extends AiReceptionistEvent {

    private final UUID auditLogId;
    private final String question;
    private final String answer;
    private final Instant trainedAt;

    public KbTrainingCompletedEvent(String tenantId, UUID auditLogId, String question, String answer) {
        super(tenantId);
        this.auditLogId = auditLogId;
        this.question = question;
        this.answer = answer;
        this.trainedAt = Instant.now();
    }

    public UUID getAuditLogId() {
        return auditLogId;
    }

    public String getQuestion() {
        return question;
    }

    public String getAnswer() {
        return answer;
    }

    public Instant getTrainedAt() {
        return trainedAt;
    }
}
