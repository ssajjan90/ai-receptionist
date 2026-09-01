package com.aireceptionist.audit;

import com.aireceptionist.common.ai.AiConfidenceGateAspect;
import com.aireceptionist.common.ai.AiResponse;
import com.aireceptionist.common.ai.AiResponseResult;
import com.aireceptionist.common.ai.TenantOwnerPhonePort;
import com.aireceptionist.common.audit.AuditEventType;
import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.common.resilience.FallbackMessageProvider;
import com.aireceptionist.common.event.UnansweredQueryFlaggedEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class AiConfidenceGateAspectTest {

    private final AuditLogRepository auditLogRepository = mock(AuditLogRepository.class);
    private final TenantOwnerPhonePort tenantOwnerPhonePort = mock(TenantOwnerPhonePort.class);
    private final ApplicationEventPublisher eventPublisher = mock(ApplicationEventPublisher.class);
    private final FallbackMessageProvider fallbackMessageProvider = mock(FallbackMessageProvider.class);
    private final Clock clock = Clock.fixed(Instant.parse("2026-05-21T12:00:00Z"), ZoneOffset.UTC);
    private final AiConfidenceGateAspect aspect = new AiConfidenceGateAspect(
            auditLogRepository,
            fallbackMessageProvider,
            tenantOwnerPhonePort,
            eventPublisher,
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
        assertThat(entry.id()).isNotNull();
        assertThat(entry.tenantId()).isEqualTo(tenantId);
        assertThat(entry.eventType()).isEqualTo(AuditEventType.AUDIT_HIGH_CONFIDENCE);
        assertThat(entry.confidence()).isEqualByComparingTo(BigDecimal.valueOf(0.91));
        assertThat(entry.messageHash()).hasSize(64);
        assertThat(entry.occurredAt()).isEqualTo(Instant.parse("2026-05-21T12:00:00Z"));
        verify(eventPublisher, never()).publishEvent(anyString());
    }

    @Test
    void mediumConfidenceReturnsResponseFlaggedAndPublishesEvent() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId.toString());
        when(tenantOwnerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.of("+911234567890"));
        ProceedingJoinPoint joinPoint = joinPointReturning(new AiResponseResult("answer", 0.62), tenantId, "hello");

        Object result = aspect.gateAiResponse(joinPoint, aiResponseAnnotation());

        assertThat(result).isEqualTo(new AiResponseResult("answer", 0.62, true));
        AuditLogEntry entry = savedAuditEntry();
        assertThat(entry.eventType()).isEqualTo(AuditEventType.AUDIT_MEDIUM_CONFIDENCE);
        assertThat(entry.confidence()).isEqualByComparingTo(BigDecimal.valueOf(0.62));

        ArgumentCaptor<UnansweredQueryFlaggedEvent> eventCaptor =
                ArgumentCaptor.forClass(UnansweredQueryFlaggedEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        assertThat(eventCaptor.getValue().getTenantId()).isEqualTo(tenantId.toString());
        assertThat(eventCaptor.getValue().getOwnerPhone()).isEqualTo("+911234567890");
    }

    @Test
    void lowConfidenceReturnsFallbackPublishesEventAndWritesLowConfidenceAudit() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId.toString());
        when(tenantOwnerPhonePort.getOwnerPhone(tenantId.toString())).thenReturn(Optional.empty());
        when(fallbackMessageProvider.getFallbackResponse(tenantId.toString(), null))
                .thenReturn("I'll have our team follow up with you shortly. Thank you for your patience.");
        ProceedingJoinPoint joinPoint = joinPointReturning(new AiResponseResult("risky answer", 0.24), tenantId, "hello");

        Object result = aspect.gateAiResponse(joinPoint, aiResponseAnnotation());

        assertThat(result).isEqualTo(new AiResponseResult(
                "I'll have our team follow up with you shortly. Thank you for your patience.",
                0.24,
                true
        ));
        AuditLogEntry entry = savedAuditEntry();
        assertThat(entry.eventType()).isEqualTo(AuditEventType.AUDIT_LOW_CONFIDENCE);
        assertThat(entry.confidence()).isEqualByComparingTo(BigDecimal.valueOf(0.24));
        verify(eventPublisher).publishEvent(org.mockito.ArgumentMatchers.any(UnansweredQueryFlaggedEvent.class));
    }

    @Test
    void highConfidenceDoesNotPublishEvent() throws Throwable {
        UUID tenantId = UUID.randomUUID();
        TenantContext.setCurrentTenant(tenantId.toString());
        ProceedingJoinPoint joinPoint = joinPointReturning(new AiResponseResult("answer", 0.90), tenantId, "hello");

        aspect.gateAiResponse(joinPoint, aiResponseAnnotation());

        verify(eventPublisher, never()).publishEvent(
                org.mockito.ArgumentMatchers.any(UnansweredQueryFlaggedEvent.class));
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
