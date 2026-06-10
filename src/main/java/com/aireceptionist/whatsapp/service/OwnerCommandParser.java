package com.aireceptionist.whatsapp.service;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.knowledgebase.dto.ConflictInfo;
import com.aireceptionist.knowledgebase.service.ConflictDetectionService;
import com.aireceptionist.knowledgebase.service.KnowledgeBaseService;
import com.aireceptionist.whatsapp.event.OwnerCommandReceivedEvent;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OwnerCommandParser {

    private static final Logger log = LoggerFactory.getLogger(OwnerCommandParser.class);

    private static final Pattern ADD_FAQ_CMD = Pattern.compile(
            "^ADD FAQ:\\s*(.+?)\\s*\\|\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern ADD_CMD = Pattern.compile(
            "^ADD:\\s*(.+?)\\s*\\|\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_CMD = Pattern.compile(
            "^UPDATE:\\s*(.+?)\\s*\\|\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_CMD = Pattern.compile(
            "^DELETE:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private static final String HELP_TEXT =
            "Command not recognised. Use:\n" +
            "ADD: Product | Price\n" +
            "UPDATE: Product | New Price\n" +
            "DELETE: Product\n" +
            "ADD FAQ: Question | Answer";

    private static final String PENDING_CMD_KEY_PREFIX = "pending_cmd:";
    private static final Duration PENDING_CMD_TTL = Duration.ofSeconds(600);

    private final WhatsAppNotificationService notificationService;
    private final KnowledgeBaseService knowledgeBaseService;
    private final ConflictDetectionService conflictDetectionService;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public OwnerCommandParser(WhatsAppNotificationService notificationService,
                               KnowledgeBaseService knowledgeBaseService,
                               ConflictDetectionService conflictDetectionService,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper) {
        this.notificationService = notificationService;
        this.knowledgeBaseService = knowledgeBaseService;
        this.conflictDetectionService = conflictDetectionService;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    @ApplicationModuleListener
    public void onOwnerCommand(OwnerCommandReceivedEvent event) {
        TenantContext.setCurrentTenant(event.getTenantId());
        try {
            String raw = event.getRawMessage();
            if (raw == null || raw.isBlank()) {
                return;
            }
            String trimmed = raw.trim();
            String tenantId = event.getTenantId();
            String ownerPhone = event.getSenderPhone();

            // 1. Check for pending conflict resolution (CONFIRM/CANCEL)
            if (handlePendingConfirmation(trimmed, tenantId, ownerPhone)) {
                return;
            }

            // 2. Match known command patterns
            Matcher addFaqMatcher = ADD_FAQ_CMD.matcher(trimmed);
            Matcher addMatcher = ADD_CMD.matcher(trimmed);
            Matcher updateMatcher = UPDATE_CMD.matcher(trimmed);
            Matcher deleteMatcher = DELETE_CMD.matcher(trimmed);

            if (addFaqMatcher.matches()) {
                handleAddFaq(tenantId, ownerPhone,
                        addFaqMatcher.group(1).trim(), addFaqMatcher.group(2).trim());
            } else if (addMatcher.matches()) {
                handleAddOrUpdate(tenantId, ownerPhone,
                        addMatcher.group(1).trim(), addMatcher.group(2).trim());
            } else if (updateMatcher.matches()) {
                handleAddOrUpdate(tenantId, ownerPhone,
                        updateMatcher.group(1).trim(), updateMatcher.group(2).trim());
            } else if (deleteMatcher.matches()) {
                handleDelete(tenantId, ownerPhone, deleteMatcher.group(1).trim());
            } else {
                log.info("Unrecognised owner command — tenant={}, message='{}'", tenantId, trimmed);
                notificationService.sendMessage(tenantId, ownerPhone, HELP_TEXT);
            }
        } finally {
            TenantContext.clear();
        }
    }

    private void handleAddOrUpdate(String tenantId, String ownerPhone, String product, String price) {
        UUID tenantUuid = UUID.fromString(tenantId);
        conflictDetectionService.checkConflict(tenantUuid, product, price).ifPresentOrElse(
                conflict -> {
                    storePendingCommand(tenantId, ownerPhone, product, price, conflict.existingPrice());
                    String warning = String.format(
                            "⚠️ Conflict detected: %s is currently ₹%s. " +
                            "Reply CONFIRM to update to ₹%s or CANCEL to keep current price.",
                            conflict.productName(), conflict.existingPrice(), conflict.newPrice());
                    notificationService.sendMessage(tenantId, ownerPhone, warning);
                    log.info("Conflict detected for product='{}' tenant={}", product, tenantId);
                },
                () -> {
                    knowledgeBaseService.addOrUpdateProduct(tenantUuid, product, price);
                    notificationService.sendMessage(tenantId, ownerPhone,
                            "Updated: " + product + " → ₹" + price + " ✓");
                }
        );
    }

    private void handleAddFaq(String tenantId, String ownerPhone, String question, String answer) {
        knowledgeBaseService.addOrUpdateFaq(UUID.fromString(tenantId), question, answer);
        notificationService.sendMessage(tenantId, ownerPhone,
                "FAQ added: " + question + " ✓");
    }

    private void handleDelete(String tenantId, String ownerPhone, String product) {
        boolean deleted = knowledgeBaseService.deleteEntry(UUID.fromString(tenantId), product);
        if (deleted) {
            notificationService.sendMessage(tenantId, ownerPhone, "Deleted: " + product + " ✓");
        } else {
            notificationService.sendMessage(tenantId, ownerPhone,
                    "Not found: " + product + ". No changes made.");
        }
    }

    private boolean handlePendingConfirmation(String message, String tenantId, String ownerPhone) {
        String key = pendingCmdKey(tenantId, ownerPhone);
        String pendingJson = redisTemplate.opsForValue().get(key);
        if (pendingJson == null) {
            return false;
        }

        String upper = message.toUpperCase().trim();
        if ("CONFIRM".equals(upper)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> cmd = objectMapper.readValue(pendingJson, Map.class);
                String product = cmd.get("product");
                String price = cmd.get("price");
                redisTemplate.delete(key);
                knowledgeBaseService.addOrUpdateProduct(UUID.fromString(tenantId), product, price);
                notificationService.sendMessage(tenantId, ownerPhone,
                        "Updated: " + product + " → ₹" + price + " ✓");
                log.info("Pending conflict confirmed for product='{}' tenant={}", product, tenantId);
            } catch (Exception ex) {
                log.warn("Failed to process CONFIRM for tenant={}: {}", tenantId, ex.getMessage());
                redisTemplate.delete(key);
            }
            return true;
        } else if ("CANCEL".equals(upper)) {
            try {
                @SuppressWarnings("unchecked")
                Map<String, String> cmd = objectMapper.readValue(pendingJson, Map.class);
                String product = cmd.get("product");
                String existingPrice = cmd.get("existingPrice");
                redisTemplate.delete(key);
                notificationService.sendMessage(tenantId, ownerPhone,
                        "Keeping current price: " + product + " → ₹" + existingPrice);
                log.info("Pending conflict cancelled for product='{}' tenant={}", product, tenantId);
            } catch (Exception ex) {
                log.warn("Failed to process CANCEL for tenant={}: {}", tenantId, ex.getMessage());
                redisTemplate.delete(key);
            }
            return true;
        }
        return false;
    }

    private void storePendingCommand(String tenantId, String ownerPhone, String product, String price,
                                      String existingPrice) {
        String key = pendingCmdKey(tenantId, ownerPhone);
        try {
            Map<String, String> cmd = Map.of("product", product, "price", price, "existingPrice", existingPrice);
            redisTemplate.opsForValue().set(key, objectMapper.writeValueAsString(cmd), PENDING_CMD_TTL);
        } catch (Exception ex) {
            log.warn("Failed to store pending command for tenant={}: {}", tenantId, ex.getMessage());
        }
    }

    private String pendingCmdKey(String tenantId, String ownerPhone) {
        return PENDING_CMD_KEY_PREFIX + tenantId + ":" + ownerPhone;
    }
}
