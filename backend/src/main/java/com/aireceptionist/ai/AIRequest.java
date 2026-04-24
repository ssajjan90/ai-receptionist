package com.aireceptionist.ai;

import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AIRequest {
    String tenantName;
    String industry;
    String workingHours;
    String knowledgeBase;
    String customerMessage;
}
