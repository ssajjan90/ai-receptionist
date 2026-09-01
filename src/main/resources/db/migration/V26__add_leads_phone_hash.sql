-- Story 5.3: a stable, non-PII link from whatsapp_messages.sender_phone back to an erased lead.
-- Lead.erase() nulls `phone` (by design, for genuine DPDP erasure — see V3's original comment),
-- which destroys the only key that could match a message to its lead. phone_hash is a one-way
-- SHA-256 hash: it lets the admin conversation viewer detect "this message's sender was an
-- erased lead" and redact content, without storing or being able to reconstruct the raw phone.
ALTER TABLE leads ADD COLUMN phone_hash VARCHAR(64);
CREATE INDEX idx_leads_tenant_phone_hash ON leads(tenant_id, phone_hash) WHERE phone_hash IS NOT NULL;

-- Backfill currently non-erased leads (their phone is still present). Leads already erased
-- before this migration already have phone = NULL — their phone_hash cannot be backfilled; this
-- is an inherent, unavoidable limitation for pre-existing erasures (see story 5.3 Debug Log).
CREATE EXTENSION IF NOT EXISTS pgcrypto;
UPDATE leads SET phone_hash = encode(digest(phone, 'sha256'), 'hex') WHERE phone IS NOT NULL;
