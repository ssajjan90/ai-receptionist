-- Platform admin only — no tenant_id, no RLS. Admin users see all tenants.
CREATE TABLE admin_users (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    email          VARCHAR(255) NOT NULL,
    password_hash  VARCHAR(255) NOT NULL,
    role           VARCHAR(20)  NOT NULL DEFAULT 'ADMIN',
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_admin_users_email UNIQUE (email),
    CONSTRAINT chk_admin_users_role CHECK (role IN ('ADMIN', 'SUPER_ADMIN'))
);
