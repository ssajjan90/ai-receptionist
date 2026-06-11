package com.aireceptionist.whatsapp.service;

import com.aireceptionist.knowledgebase.event.UnansweredQueryFlaggedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.Map;

@Service
public class UnansweredQueryListener {

    private static final Logger log = LoggerFactory.getLogger(UnansweredQueryListener.class);
    private final WhatsAppNotificationService notificationService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public UnansweredQueryListener(WhatsAppNotificationService notificationService,
                                   StringRedisTemplate redisTemplate,
                                   ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @ApplicationModuleListener
    public void onUnansweredQuery(UnansweredQueryFlaggedEvent event) {
        if (event.getOwnerPhone() == null) {
            log.info("Unanswered query flagged for tenant {} but no owner phone on record — skipping notification",
                    event.getTenantId());
            return;
        }
        log.info("Unanswered query flagged for tenant {} — confidence below threshold", event.getTenantId());

        if (!storeTrainingContext(event)) {
            log.warn("Skipping training notification for tenant={} — training context could not be stored",
                    event.getTenantId());
            return;
        }

        String message = "⚠️ Unanswered query from customer:\n\"" + event.getOriginalQuery()
                + "\"\n\nReply with the correct answer to teach the AI. Or reply SKIP to ignore.";
        try {
            notificationService.sendMessage(event.getTenantId(), event.getOwnerPhone(), message);
        } catch (Exception ex) {
            log.warn("Failed to notify owner for tenant={}: {}", event.getTenantId(), ex.getMessage());
        }
    }

    private boolean storeTrainingContext(UnansweredQueryFlaggedEvent event) {
        String key = TrainingRedisKeys.PREFIX + event.getTenantId() + ":" + event.getOwnerPhone();
        try {
            Map<String, String> context = Map.of(
                    "question", event.getOriginalQuery(),
                    "auditLogId", event.getAuditLogId() != null ? event.getAuditLogId().toString() : ""
            );
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(context), TrainingRedisKeys.TTL);
            log.debug("Stored training context for tenant={} key={}", event.getTenantId(), key);
            return true;
        } catch (Exception ex) {
            log.warn("Failed to store training context for tenant={}: {}", event.getTenantId(), ex.getMessage());
            return false;
        }
    }
}
