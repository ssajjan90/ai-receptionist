package com.aireceptionist.audit;

import com.aireceptionist.common.audit.AuditLogCleanupJob;
import com.aireceptionist.common.audit.AuditLogRepository;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AuditLogCleanupJobTest {

    @Test
    void deletesAuditRowsOlderThanNinetyDays() {
        AuditLogRepository repository = mock(AuditLogRepository.class);
        Clock clock = Clock.fixed(Instant.parse("2026-05-21T12:00:00Z"), ZoneOffset.UTC);
        when(repository.deleteOlderThan(Instant.parse("2026-02-20T12:00:00Z"))).thenReturn(7);

        new AuditLogCleanupJob(repository, clock).deleteOldAuditLogs();

        verify(repository).deleteOlderThan(Instant.now(clock).minus(90, ChronoUnit.DAYS));
    }
}
