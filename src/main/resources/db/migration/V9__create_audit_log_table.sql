-- Written by AiConfidenceGateAspect (Story 1.7). RLS enabled — admin reads handled in Story 5.6.
-- 90-day retention enforced by @Scheduled cleanup job (Story 1.7).
CREATE TABLE audit_log (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL,
    event_type    VARCHAR(64)  NOT NULL,
    confidence    NUMERIC(5,2),
    message_hash  VARCHAR(64),
    occurred_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_audit_log_tenant_id  ON audit_log(tenant_id);
CREATE INDEX idx_audit_log_occurred_at ON audit_log(occurred_at);

ALTER TABLE audit_log ENABLE ROW LEVEL SECURITY;
ALTER TABLE audit_log FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON audit_log
    USING (tenant_id::text = current_setting('app.current_tenant', true));
