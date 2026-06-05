CREATE TABLE voice_calls (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    call_sid      VARCHAR(255),
    caller_phone  VARCHAR(20)  NOT NULL,
    status        VARCHAR(20)  NOT NULL DEFAULT 'RECEIVED',
    started_at    TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ended_at      TIMESTAMPTZ,
    CONSTRAINT chk_voice_calls_status CHECK (status IN ('RECEIVED', 'HANDLED', 'TRANSFERRED', 'MISSED'))
);

CREATE INDEX idx_voice_calls_tenant_id ON voice_calls(tenant_id);
CREATE UNIQUE INDEX uq_voice_calls_call_sid ON voice_calls(call_sid) WHERE call_sid IS NOT NULL;
