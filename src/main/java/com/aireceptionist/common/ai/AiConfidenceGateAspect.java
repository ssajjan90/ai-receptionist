package com.aireceptionist.common.ai;

import com.aireceptionist.common.audit.AuditLogEntry;
import com.aireceptionist.common.audit.AuditLogRepository;
import com.aireceptionist.common.multitenancy.TenantContext;
import com.aireceptionist.common.resilience.FallbackMessageProvider;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

@Aspect
@Component
public class AiConfidenceGateAspect {

    private static final Logger log = LoggerFactory.getLogger(AiConfidenceGateAspect.class);
    private static final double FLAG_THRESHOLD = 0.75;
    private static final double FALLBACK_THRESHOLD = 0.50;

    private final AuditLogRepository auditLogRepository;
    private final FallbackMessageProvider fallbackMessageProvider;
    private final Clock clock;

    public AiConfidenceGateAspect(
            AuditLogRepository auditLogRepository,
            FallbackMessageProvider fallbackMessageProvider,
            Clock clock
    ) {
        this.auditLogRepository = auditLogRepository;
        this.fallbackMessageProvider = fallbackMessageProvider;
        this.clock = clock;
    }

    @Around("@annotation(aiResponse)")
    public Object gateAiResponse(ProceedingJoinPoint joinPoint, AiResponse aiResponse) throws Throwable {
        Object result = joinPoint.proceed();
        if (!(result instanceof AiResponseResult aiResult)) {
            return result;
        }

        String tenantId = resolveTenantId(joinPoint.getArgs());
        if (tenantId == null) {
            log.warn("AI response skipped audit because no tenant context was available");
            return routeResult(aiResult, null);
        }

        auditLogRepository.save(new AuditLogEntry(
                UUID.fromString(tenantId),
                aiResponse.eventType(),
                BigDecimal.valueOf(aiResult.confidence()).setScale(2, RoundingMode.HALF_UP),
                hashQuery(joinPoint.getArgs()),
                Instant.now(clock)
        ));

        return routeResult(aiResult, tenantId);
    }

    private Object routeResult(AiResponseResult aiResult, String tenantId) {
        if (aiResult.confidence() < FALLBACK_THRESHOLD) {
            log.warn("Low AI confidence {} for tenant {}; returning fallback", aiResult.confidence(), tenantId);
            return new AiResponseResult(
                    fallbackMessageProvider.getFallbackResponse(tenantId, null),
                    aiResult.confidence(),
                    true
            );
        }
        if (aiResult.confidence() < FLAG_THRESHOLD) {
            log.warn("Medium AI confidence {} for tenant {}; flagging for review", aiResult.confidence(), tenantId);
            return aiResult.flagged();
        }
        return aiResult;
    }

    private String resolveTenantId(Object[] args) {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId != null && !tenantId.isBlank()) {
            return tenantId;
        }
        for (Object arg : args) {
            if (arg instanceof UUID uuid) {
                return uuid.toString();
            }
            if (arg instanceof String value && isUuid(value)) {
                return value;
            }
        }
        return null;
    }

    private boolean isUuid(String value) {
        try {
            UUID.fromString(value);
            return true;
        } catch (IllegalArgumentException ignored) {
            return false;
        }
    }

    private String hashQuery(Object[] args) {
        String query = "";
        for (Object arg : args) {
            if (arg instanceof String value && !isUuid(value)) {
                query = value;
            }
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(query.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 digest unavailable", ex);
        }
    }
}
