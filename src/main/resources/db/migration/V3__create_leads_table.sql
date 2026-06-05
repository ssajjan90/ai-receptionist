-- DPDP FR42: consent_timestamp + consent_channel mandatory at lead capture.
-- DPDP FR44: erased flag for right-to-erasure (soft erase, fields zeroed by TenantService.eraseTenantData).
CREATE TABLE leads (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id          UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    name               VARCHAR(255) NOT NULL,
    phone              VARCHAR(20)  NOT NULL,
    intent             TEXT,
    channel            VARCHAR(20)  NOT NULL,
    consent_timestamp  TIMESTAMPTZ  NOT NULL,
    consent_channel    VARCHAR(20)  NOT NULL,
    erased             BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_leads_channel         CHECK (channel         IN ('whatsapp', 'voice')),
    CONSTRAINT chk_leads_consent_channel CHECK (consent_channel IN ('whatsapp', 'voice'))
);

CREATE INDEX idx_leads_tenant_id  ON leads(tenant_id);
CREATE INDEX idx_leads_created_at ON leads(created_at);
