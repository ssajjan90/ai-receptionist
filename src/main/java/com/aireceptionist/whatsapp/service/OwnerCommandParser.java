package com.aireceptionist.whatsapp.service;

import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.whatsapp.event.OwnerCommandReceivedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class OwnerCommandParser {

    private static final Logger log = LoggerFactory.getLogger(OwnerCommandParser.class);

    private static final Pattern ADD_CMD = Pattern.compile(
            "^ADD:\\s*(.+?)\\s*\\|\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern UPDATE_CMD = Pattern.compile(
            "^UPDATE:\\s*(.+?)\\s*\\|\\s*(.+)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DELETE_CMD = Pattern.compile(
            "^DELETE:\\s*(.+)$", Pattern.CASE_INSENSITIVE);

    private final WhatsAppNotificationService notificationService;

    public OwnerCommandParser(WhatsAppNotificationService notificationService) {
        this.notificationService = notificationService;
    }

    @ApplicationModuleListener
    void onOwnerCommand(OwnerCommandReceivedEvent event) {
        TenantContext.setCurrentTenant(event.getTenantId());
        try {
            String raw = event.getRawMessage();
            if (raw == null || raw.isBlank()) {
                return;
            }
            String trimmed = raw.trim();
            Matcher addMatcher = ADD_CMD.matcher(trimmed);
            Matcher updateMatcher = UPDATE_CMD.matcher(trimmed);
            Matcher deleteMatcher = DELETE_CMD.matcher(trimmed);

            if (addMatcher.matches()) {
                log.info("Owner ADD command — product: '{}', price: '{}' (tenant={})",
                        addMatcher.group(1).trim(), addMatcher.group(2).trim(), event.getTenantId());
            } else if (updateMatcher.matches()) {
                log.info("Owner UPDATE command — product: '{}', new price: '{}' (tenant={})",
                        updateMatcher.group(1).trim(), updateMatcher.group(2).trim(), event.getTenantId());
            } else if (deleteMatcher.matches()) {
                log.info("Owner DELETE command — product: '{}' (tenant={})",
                        deleteMatcher.group(1).trim(), event.getTenantId());
            } else {
                log.info("Unrecognised owner command — tenant={}, message='{}'", event.getTenantId(), trimmed);
                notificationService.sendMessage(event.getTenantId(), event.getSenderPhone(),
                        "Unrecognised command. Use ADD/UPDATE/DELETE format.");
            }
        } finally {
            TenantContext.clear();
        }
    }
}
