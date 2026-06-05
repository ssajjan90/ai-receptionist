package com.aireceptionist.whatsapp.service;

import com.aireceptionist.tenant.port.in.GetLiveTenantsUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

@Component
public class WhatsAppQueueProcessor {

    private static final Logger log = LoggerFactory.getLogger(WhatsAppQueueProcessor.class);
    private static final int BATCH_SIZE = 10;

    private final WhatsAppQueueService queueService;
    private final WhatsAppNotificationService notificationService;
    private final GetLiveTenantsUseCase getLiveTenantsUseCase;

    public WhatsAppQueueProcessor(WhatsAppQueueService queueService,
                                  WhatsAppNotificationService notificationService,
                                  GetLiveTenantsUseCase getLiveTenantsUseCase) {
        this.queueService = queueService;
        this.notificationService = notificationService;
        this.getLiveTenantsUseCase = getLiveTenantsUseCase;
    }

    @Scheduled(fixedDelay = 30_000)
    public void processQueues() {
        List<UUID> liveTenants = getLiveTenantsUseCase.getLiveTenantIds();
        for (UUID tenantId : liveTenants) {
            drainQueueForTenant(tenantId.toString());
        }
    }

    private void drainQueueForTenant(String tenantId) {
        List<String> messages = queueService.dequeueMessages(tenantId, BATCH_SIZE);
        if (messages.isEmpty()) {
            return;
        }
        log.debug("Processing {} queued WhatsApp messages for tenant={}", messages.size(), tenantId);
        for (String rawValue : messages) {
            WhatsAppQueueService.QueuedMessage queued = WhatsAppQueueService.parse(rawValue);
            try {
                notificationService.sendMessage(tenantId, queued.recipientPhone(), queued.message());
            } catch (Exception ex) {
                log.warn("Failed to send queued message for tenant={}, re-enqueuing", tenantId, ex);
                queueService.enqueueMessage(tenantId, queued.recipientPhone(), queued.message());
            }
        }
    }
}
