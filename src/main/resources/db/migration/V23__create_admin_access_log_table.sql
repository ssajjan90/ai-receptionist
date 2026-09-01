-- Story 5.1 (AC5): platform-level admin access trail. Deliberately separate from the
-- tenant-scoped, RLS-protected `audit_log` table (Story 1.7) — admin access events are not
-- tenant data, and several admin actions (e.g. listing all tenants) have no single target
-- tenant, which the FORCE RLS + NOT NULL tenant_id design on `audit_log` cannot represent.
CREATE TABLE admin_access_log (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    admin_user_id     UUID         NOT NULL,
    target_tenant_id  UUID,
    event_type        VARCHAR(64)  NOT NULL,
    action            VARCHAR(128) NOT NULL,
    occurred_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_admin_access_log_admin_user_id    ON admin_access_log(admin_user_id);
CREATE INDEX idx_admin_access_log_target_tenant_id ON admin_access_log(target_tenant_id);
CREATE INDEX idx_admin_access_log_occurred_at      ON admin_access_log(occurred_at);
