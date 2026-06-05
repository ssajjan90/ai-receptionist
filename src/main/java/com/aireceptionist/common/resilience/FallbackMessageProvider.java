package com.aireceptionist.common.resilience;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FallbackMessageProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackMessageProvider.class);

    public String getFallbackResponse(String tenantId, Throwable cause) {
        String reason = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "no cause";
        log.warn("Fallback triggered for tenant={}: {}", tenantId, reason);
        return "Our AI is temporarily unavailable, please try again shortly.";
    }
}
