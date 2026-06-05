package com.aireceptionist.tenant.port.out;

public interface TokenIssuerPort {

    String issueToken(String tenantId, String userId, String role, String tier);
}
