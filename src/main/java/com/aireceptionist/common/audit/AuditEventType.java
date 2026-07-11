package com.aireceptionist.common.audit;

public final class AuditEventType {

    public static final String AUDIT_HIGH_CONFIDENCE = "AUDIT_HIGH_CONFIDENCE";
    public static final String AUDIT_MEDIUM_CONFIDENCE = "AUDIT_MEDIUM_CONFIDENCE";
    public static final String AUDIT_LOW_CONFIDENCE = "AUDIT_LOW_CONFIDENCE";
    public static final String DATA_ERASED = "DATA_ERASED";
    public static final String DATA_ERASED_BULK = "DATA_ERASED_BULK";

    private AuditEventType() {
    }
}
