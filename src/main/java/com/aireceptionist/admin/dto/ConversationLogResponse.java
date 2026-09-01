package com.aireceptionist.admin.dto;

import java.math.BigDecimal;
import java.time.Instant;

/**
 * Story 5.3 (AC2, AC5). {@code content} is null when the sending customer's lead has since been
 * erased (see {@code AdminService.findConversations} — matched via {@code Lead.phoneHash}, not
 * the raw phone, since {@code Lead.erase()} nulls the phone itself).
 */
public record ConversationLogResponse(
        String messageId,
        String direction,
        String content,
        BigDecimal confidenceScore,
        String language,
        Instant createdAt
) {
}
