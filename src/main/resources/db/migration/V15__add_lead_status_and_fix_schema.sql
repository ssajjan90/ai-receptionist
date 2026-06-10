-- Story 3-5: align leads table with Java entity (status, updated_at, nullable name/phone, uppercase enum values)

ALTER TABLE leads ALTER COLUMN name DROP NOT NULL;
ALTER TABLE leads ALTER COLUMN phone DROP NOT NULL;

-- Re-create channel constraint using uppercase JPA enum values and add WEB channel
ALTER TABLE leads DROP CONSTRAINT IF EXISTS chk_leads_channel;
UPDATE leads SET channel = UPPER(channel) WHERE channel NOT IN ('WHATSAPP', 'VOICE', 'WEB');
ALTER TABLE leads ADD CONSTRAINT chk_leads_channel
    CHECK (channel IN ('WHATSAPP', 'VOICE', 'WEB'));

-- Re-create consent_channel constraint using uppercase values
ALTER TABLE leads DROP CONSTRAINT IF EXISTS chk_leads_consent_channel;
UPDATE leads SET consent_channel = UPPER(consent_channel) WHERE consent_channel NOT IN ('WHATSAPP', 'VOICE');
ALTER TABLE leads ADD CONSTRAINT chk_leads_consent_channel
    CHECK (consent_channel IN ('WHATSAPP', 'VOICE'));

-- Add lead status
ALTER TABLE leads ADD COLUMN IF NOT EXISTS status VARCHAR(20) NOT NULL DEFAULT 'NEW';
ALTER TABLE leads ADD CONSTRAINT chk_leads_status
    CHECK (status IN ('NEW', 'CONTACTED', 'CONVERTED', 'DISMISSED'));

-- Add updated_at
ALTER TABLE leads ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE INDEX IF NOT EXISTS idx_leads_tenant_status ON leads(tenant_id, status);
