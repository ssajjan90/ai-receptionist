package com.aireceptionist.leads.service;

import com.aireceptionist.common.audit.AuditEventType;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.ai.TenantNamePort;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.leads.domain.Lead;
import com.aireceptionist.leads.repository.LeadRepository;
import com.aireceptionist.tenant.port.in.GetLiveTenantsUseCase;
import com.aireceptionist.whatsapp.domain.SenderType;
import com.aireceptionist.whatsapp.repository.WhatsAppMessageRepository;
import com.aireceptionist.whatsapp.service.WhatsAppNotificationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

@Component
public class DailySummaryJob {

    private static final Logger log = LoggerFactory.getLogger(DailySummaryJob.class);

    private final GetLiveTenantsUseCase liveTenantsUseCase;
    private final TenantNamePort tenantNamePort;
    private final TenantOwnerPhonePort tenantOwnerPhonePort;
    private final WhatsAppMessageRepository whatsAppMessageRepository;
    private final LeadRepository leadRepository;
    private final AuditLogRepository auditLogRepository;
    private final WhatsAppNotificationService notificationService;
    private final Clock clock;
    private final boolean dailySummaryEnabled;
    private final String dashboardUrl;

    public DailySummaryJob(GetLiveTenantsUseCase liveTenantsUseCase,
                           TenantNamePort tenantNamePort,
                           TenantOwnerPhonePort tenantOwnerPhonePort,
                           WhatsAppMessageRepository whatsAppMessageRepository,
                           LeadRepository leadRepository,
                           AuditLogRepository auditLogRepository,
                           WhatsAppNotificationService notificationService,
                           Clock clock,
                           @Value("${features.daily-summary.enabled:false}") boolean dailySummaryEnabled,
                           @Value("${app.dashboard.url}") String dashboardUrl) {
        this.liveTenantsUseCase = liveTenantsUseCase;
        this.tenantNamePort = tenantNamePort;
        this.tenantOwnerPhonePort = tenantOwnerPhonePort;
        this.whatsAppMessageRepository = whatsAppMessageRepository;
        this.leadRepository = leadRepository;
        this.auditLogRepository = auditLogRepository;
        this.notificationService = notificationService;
        this.clock = clock;
        this.dailySummaryEnabled = dailySummaryEnabled;
        this.dashboardUrl = dashboardUrl;
    }

    @Scheduled(cron = "0 0 8 * * *")
    public void sendDailySummaries() {
        if (!dailySummaryEnabled) {
            log.debug("Daily summary job disabled via feature flag; skipping run");
            return;
        }

        Instant since = Instant.now(clock).minus(24, ChronoUnit.HOURS);
        List<UUID> liveTenantIds = liveTenantsUseCase.getLiveTenantIds();
        log.info("Running daily summary job for {} live tenant(s)", liveTenantIds.size());
        for (UUID tenantId : liveTenantIds) {
            try {
                sendSummaryForTenant(tenantId, since);
            } catch (Exception e) {
                log.error("Daily summary failed for tenant {}", tenantId, e);
            }
        }
    }

    private void sendSummaryForTenant(UUID tenantId, Instant since) {
        TenantContext.setCurrentTenant(tenantId.toString());
        try {
            long totalMessages = whatsAppMessageRepository.countByTenantIdAndSenderTypeAndReceivedAtAfter(
                    tenantId, SenderType.CUSTOMER, since);
            if (totalMessages == 0) {
                return;
            }

            long leadCount = leadRepository.countByTenantIdAndCreatedAtAfter(tenantId, since);
            List<Lead> newLeads = leadRepository.findTop5ByTenantIdAndErasedFalseAndCreatedAtAfterOrderByCreatedAtDesc(tenantId, since);
            long unansweredCount = auditLogRepository.countByTenantIdAndEventTypeAndOccurredAtAfter(
                    tenantId, AuditEventType.AUDIT_LOW_CONFIDENCE, since);

            String tenantIdStr = tenantId.toString();
            String ownerPhone = tenantOwnerPhonePort.getOwnerPhone(tenantIdStr).orElse(null);
            if (ownerPhone == null) {
                log.warn("Skipping daily summary for tenant {} — no owner phone on file", tenantId);
                return;
            }
            String shopName = tenantNamePort.getBusinessName(tenantIdStr).orElse("your business");

            String message = buildSummaryMessage(shopName, totalMessages, leadCount, unansweredCount, newLeads);
            notificationService.sendMessage(tenantIdStr, ownerPhone, message);
            log.info("Daily summary sent for tenant={} messages={} leads={} unanswered={}",
                    tenantId, totalMessages, leadCount, unansweredCount);
        } finally {
            TenantContext.clear();
        }
    }

    private String buildSummaryMessage(String shopName, long totalMessages, long leadCount,
                                       long unansweredCount, List<Lead> newLeads) {
        StringBuilder sb = new StringBuilder();
        sb.append("📊 *Daily Summary — ").append(sanitize(shopName)).append("*\n\n");
        sb.append("Yesterday's activity:\n");
        sb.append("• Messages handled: ").append(totalMessages).append('\n');
        sb.append("• New leads captured: ").append(leadCount).append('\n');
        sb.append("• Unanswered queries: ").append(unansweredCount).append('\n');

        if (!newLeads.isEmpty()) {
            sb.append("\nNew leads:\n");
            for (Lead lead : newLeads) {
                String name = lead.getCustomerName() != null ? lead.getCustomerName() : "Unknown";
                String product = lead.getProductIntent() != null ? lead.getProductIntent() : "general enquiry";
                sb.append("• ").append(sanitize(name)).append(" — interested in ").append(sanitize(product)).append('\n');
            }
            if (leadCount > newLeads.size()) {
                sb.append("...and ").append(leadCount - newLeads.size()).append(" more\n");
            }
        }

        sb.append("\nView full dashboard: ").append(dashboardUrl);
        return sb.toString();
    }

    private static String sanitize(String text) {
        return text.replaceAll("[*_~`]", "").replaceAll("[\\r\\n]+", " ").trim();
    }
}
