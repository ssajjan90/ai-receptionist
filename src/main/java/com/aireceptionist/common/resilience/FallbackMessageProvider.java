package com.aireceptionist.common.resilience;

import com.aireceptionist.common.ai.TenantNamePort;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

@Component
public class FallbackMessageProvider {

    private static final Logger log = LoggerFactory.getLogger(FallbackMessageProvider.class);
    private static final String GENERIC_FALLBACK =
            "I'll have our team follow up with you shortly. Thank you for your patience.";

    private final TenantNamePort tenantNamePort;

    public FallbackMessageProvider(TenantNamePort tenantNamePort) {
        this.tenantNamePort = tenantNamePort;
    }

    public String getFallbackResponse(String tenantId, Throwable cause) {
        String reason = (cause != null && cause.getMessage() != null) ? cause.getMessage() : "no cause";
        log.warn("Fallback triggered for tenant={}: {}", tenantId, reason);

        if (tenantId != null) {
            return tenantNamePort.getBusinessName(tenantId)
                    .filter(name -> !name.isBlank())
                    .map(name -> "Our " + name + " team will follow up with you shortly.")
                    .orElse(GENERIC_FALLBACK);
        }
        return GENERIC_FALLBACK;
    }
}
