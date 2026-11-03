package com.aireceptionist.config;

import com.aireceptionist.common.multitenancy.TenantContext;
import org.slf4j.MDC;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.Map;
import java.util.concurrent.Executor;

@Configuration
@EnableAsync
public class AsyncConfig {

    @Bean(name = "taskExecutor")
    public Executor taskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(10);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("async-");
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.setTaskDecorator(runnable -> {
            Map<String, String> mdcContext = MDC.getCopyOfContextMap();
            // TenantContext is a plain ThreadLocal, so — unlike MDC, which Slf4j/Logback know how
            // to bridge across threads via context maps — it does NOT cross onto this executor's
            // thread on its own. @ApplicationModuleListener methods (see WhatsAppMessageService.
            // onInboundMessage and friends) bundle @Transactional, and Hibernate's
            // MultiTenantConnectionProvider resolves the tenant for that transaction's session at
            // transaction-begin time, which — because @Async dispatches to this executor BEFORE
            // @Transactional starts the transaction — happens on THIS thread before the listener
            // method body gets a chance to run. Any TenantContext.setCurrentTenant(...) call inside
            // the listener method itself is therefore always too late: Hibernate has already
            // resolved "DEFAULT" (see TenantIdentifierResolver's null-fallback) and bound the
            // session's Postgres connection with no app.current_tenant set, so every RLS-protected
            // write in that transaction is silently rejected. Capturing and restoring TenantContext
            // here — the same pattern already used for MDC — makes sure it's set before the
            // decorated Runnable (and therefore the @Transactional advice inside it) ever runs.
            String tenantContext = TenantContext.getCurrentTenant();
            org.slf4j.LoggerFactory.getLogger(AsyncConfig.class).error(
                    "DEBUG_PROBE_DECORATE capturedTenant={} submittingThread={}", tenantContext, Thread.currentThread().getName());
            return () -> {
                try {
                    if (mdcContext != null) {
                        MDC.setContextMap(mdcContext);
                    }
                    if (tenantContext != null) {
                        TenantContext.setCurrentTenant(tenantContext);
                    }
                    org.slf4j.LoggerFactory.getLogger(AsyncConfig.class).error(
                            "DEBUG_PROBE_RUN aboutToRun tenantNow={} thread={}", TenantContext.getCurrentTenant(), Thread.currentThread().getName());
                    runnable.run();
                } finally {
                    MDC.clear();
                    TenantContext.clear();
                }
            };
        });
        return executor;
    }
}
