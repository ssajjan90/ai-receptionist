package com.aireceptionist.common.audit;

public interface AuditLogWriter {

    void save(AuditLogEntry entry);
}
