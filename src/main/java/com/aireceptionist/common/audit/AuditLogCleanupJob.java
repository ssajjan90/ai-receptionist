package com.aireceptionist.common.audit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

@Component
public class AuditLogCleanupJob {

    private static final Logger log = LoggerFactory.getLogger(AuditLogCleanupJob.class);

    private final AuditLogRepository auditLogRepository;
    private final Clock clock;

    public AuditLogCleanupJob(AuditLogRepository auditLogRepository, Clock clock) {
        this.auditLogRepository = auditLogRepository;
        this.clock = clock;
    }

    @Scheduled(cron = "0 0 2 * * *")
    public void deleteOldAuditLogs() {
        Instant cutoff = Instant.now(clock).minus(90, ChronoUnit.DAYS);
        int deletedRows = auditLogRepository.deleteOlderThan(cutoff);
        log.info("Deleted {} audit_log rows older than {}", deletedRows, cutoff);
    }
}
