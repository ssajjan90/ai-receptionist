CREATE TABLE subscriptions (
    id                       UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id                UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    tier                     VARCHAR(20)  NOT NULL DEFAULT 'BASIC',
    status                   VARCHAR(20)  NOT NULL DEFAULT 'ACTIVE',
    razorpay_subscription_id VARCHAR(255),
    starts_at                TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    ends_at                  TIMESTAMPTZ,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_subscriptions_tenant_id UNIQUE (tenant_id),
    CONSTRAINT chk_subscriptions_tier   CHECK (tier   IN ('BASIC', 'PRO')),
    CONSTRAINT chk_subscriptions_status CHECK (status IN ('ACTIVE', 'PAST_DUE', 'SUSPENDED', 'CANCELLED'))
);
