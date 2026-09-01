-- Story 4-1 review follow-up: composite index covering the status-filtered lead list query
-- (findByTenantIdAndStatus) so it can also hit the 500ms SLA at scale, matching the
-- unfiltered index added in V19.
-- NOT CONCURRENTLY: see V19's comment — CONCURRENTLY deadlocks against Flyway's own
-- lock-holding connection (code review of story 5-1, 2026-09-01, deferred W72).
CREATE INDEX IF NOT EXISTS idx_leads_tenant_status_created ON leads(tenant_id, status, created_at DESC);
