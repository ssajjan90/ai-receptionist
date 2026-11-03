package com.aireceptionist.common.multitenancy;

import com.aireceptionist.common.event.AiReceptionistEvent;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Runs every {@code @ApplicationModuleListener} with {@link TenantContext} set to the event's own
 * tenant — on the async worker thread and <em>before</em> the listener's {@code @Transactional}
 * opens its Hibernate session (this advice is ordered ahead of the transaction interceptor; the
 * {@code @Async} interceptor is always outermost). Hibernate resolves the session's tenant, and so
 * Postgres' RLS scope, at that moment.
 *
 * <p>Why it's needed: {@code AsyncConfig}'s TaskDecorator copies the <em>submitting</em> thread's
 * TenantContext, but an event published inside a listener is dispatched at that listener's commit
 * — after its {@code finally { TenantContext.clear(); }} already ran. The next listener (e.g.
 * {@code LeadCaptureEventListener} after a WhatsApp reply) then started with no tenant, Hibernate
 * bound the DEFAULT tenant, and every insert was rejected by RLS: captured leads were silently
 * never saved. Taking the tenant from the event makes each listener self-sufficient.
 */
@Aspect
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TenantEventListenerAspect {

    @Around("@annotation(org.springframework.modulith.events.ApplicationModuleListener)")
    public Object withEventTenant(ProceedingJoinPoint joinPoint) throws Throwable {
        Object[] args = joinPoint.getArgs();
        Object event = args.length > 0 ? args[0] : null;
        if (!(event instanceof AiReceptionistEvent aiEvent) || aiEvent.getTenantId() == null) {
            return joinPoint.proceed();
        }
        String previousTenant = TenantContext.getCurrentTenant();
        String previousMdc = MDC.get("tenantId");
        TenantContext.setCurrentTenant(aiEvent.getTenantId());
        MDC.put("tenantId", aiEvent.getTenantId());
        try {
            return joinPoint.proceed();
        } finally {
            if (previousTenant == null) {
                TenantContext.clear();
            } else {
                TenantContext.setCurrentTenant(previousTenant);
            }
            if (previousMdc == null) {
                MDC.remove("tenantId");
            } else {
                MDC.put("tenantId", previousMdc);
            }
        }
    }
}
