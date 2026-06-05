package com.aireceptionist.audit;

import com.aireceptionist.common.ai.AiConfidenceGateAspect;
import com.aireceptionist.common.ai.AiResponse;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.common.resilience.FallbackMessageProvider;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConfidenceGateAspectTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final FallbackMessageProvider fallbackMessageProvider = new FallbackMessageProvider();
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-21T12:00:00Z"), ZoneOffset.UTC);
    private final AiConfidenceGateAspect aspect = new AiConfidenceGateAspect(
            auditLogRepository,
            fallbackMessageProvider,
            clock
    );

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    @Test
    void highConfidenceReturnsResponseAndWritesAuditLog() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId.toString());
        ProceedingJoinPoint joinPoint = joinPointReturning(new AiResponseResult("answer", 0.91), tenantId, "hello");

        Object result = aspect.gateAiResponse(joinPoint, aiResponseAnnotation());

        assertThat(result).isEqualTo(new AiResponseResult("answer", 0.91));
        AuditLogEntry entry = savedAuditEntry();
        assertThat(entry.tenantId()).isEqualTo(tenantId);
        assertThat(entry.eventType()).isEqualTo("QUERY_RESPONSE");
        assertThat(entry.confidence()).isEqualByComparingTo(BigDecimal.valueOf(0.91));
        assertThat(entry.messageHash()).hasSize(64);
        assertThat(entry.occurredAt()).isEqualTo(Instant.parse("2026-05-21T12:00:00Z"));
    }

    @Test
    void mediumConfidenceReturnsResponseFlaggedForReview() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId.toString());
        ProceedingJoinPoint joinPoint = joinPointReturning(new AiResponseResult("answer", 0.62), tenantId, "hello");

        Object result = aspect.gateAiResponse(joinPoint, aiResponseAnnotation());

        assertThat(result).isEqualTo(new AiResponseResult("answer", 0.62, true));
        assertThat(savedAuditEntry().confidence()).isEqualByComparingTo(BigDecimal.valueOf(0.62));
    }

    @Test
    void lowConfidenceReturnsFallbackAndWritesAuditLog() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId.toString());
        ProceedingJoinPoint joinPoint = joinPointReturning(new AiResponseResult("risky answer", 0.24), tenantId, "hello");

        Object result = aspect.gateAiResponse(joinPoint, aiResponseAnnotation());

        assertThat(result).isEqualTo(new AiResponseResult(
                "Our AI is temporarily unavailable, please try again shortly.",
                0.24,
                true
        ));
        assertThat(savedAuditEntry().confidence()).isEqualByComparingTo(BigDecimal.valueOf(0.24));
    }

    private ProceedingJoinPoint joinPointReturning(AiResponseResult result, UUID tenantId, String query) throws Throwable {
        ProceedingJoinPoint joinPoint = mock(ProceedingJoinPoint.class);
        when(joinPoint.proceed()).thenReturn(result);
        when(joinPoint.getArgs()).thenReturn(new Object[]{tenantId.toString(), query});
        return joinPoint;
    }

    private AuditLogEntry savedAuditEntry() {
        ArgumentCaptor<AuditLogEntry> captor = ArgumentCaptor.forClass(AuditLogEntry.class);
        verify(auditLogRepository).save(captor.capture());
        return captor.getValue();
    }

    private AiResponse aiResponseAnnotation() throws NoSuchMethodException {
        return AnnotatedMethod.class
                .getDeclaredMethod("generate")
                .getAnnotation(AiResponse.class);
    }

    private static class AnnotatedMethod {
        @AiResponse(eventType = "QUERY_RESPONSE")
        void generate() {
        }
    }
}
