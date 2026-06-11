package com.aireceptionist.knowledgebase.service;

import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.knowledgebase.event.KbTrainingCompletedEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;

@Service
public class KbTrainingEventListener {

    private static final Logger log = LoggerFactory.getLogger(KbTrainingEventListener.class);

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public KbTrainingEventListener(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    @ApplicationModuleListener
    public void onTrainingCompleted(KbTrainingCompletedEvent event) {
        if (event.getAuditLogId() == null) {
            return;
        }
        TenantContext.setCurrentTenant(event.getTenantId());
        try {
            auditLogRepository.markResolved(event.getAuditLogId(), Instant.now(clock));
            log.info("Audit log {} marked resolved after owner training, tenant={}",
                    event.getAuditLogId(), event.getTenantId());
        } finally {
            TenantContext.clear();
        }
    }
}
