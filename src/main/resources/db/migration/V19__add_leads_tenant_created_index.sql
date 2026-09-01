-- Story 4-1: composite index to keep GET /v1/tenants/{tenantId}/leads within the 500ms SLA at scale.
-- NOT CONCURRENTLY: a CONCURRENTLY build here deadlocks against Flyway's own lock-holding
-- connection, which stays open in a transaction for the duration of the whole migration run —
-- CONCURRENTLY must wait for every pre-existing open transaction (including that one) to finish,
-- so the migration hangs forever (see code review of story 5-1, 2026-09-01, and deferred W72).
-- This app has never run these migrations against a real production leads table, so the brief
-- write lock from a plain CREATE INDEX is a non-issue; if a genuinely large table needs this index
-- added without downtime later, run CONCURRENTLY manually outside of Flyway at that time.
CREATE INDEX IF NOT EXISTS idx_leads_tenant_created ON leads(tenant_id, created_at DESC);
