-- Root tenant registry. No tenant_id FK (this IS the tenant). No RLS applied here.
CREATE TABLE tenants (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    business_name   VARCHAR(255) NOT NULL,
    phone_number    VARCHAR(20)  NOT NULL,
    owner_name      VARCHAR(255),
    tier            VARCHAR(20)  NOT NULL DEFAULT 'BASIC',
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_tenants_phone_number UNIQUE (phone_number),
    CONSTRAINT chk_tenants_tier   CHECK (tier   IN ('BASIC', 'PRO')),
    CONSTRAINT chk_tenants_status CHECK (status IN ('PENDING', 'ACTIVE', 'SUSPENDED', 'TERMINATED'))
);

CREATE INDEX idx_tenants_status ON tenants(status);
