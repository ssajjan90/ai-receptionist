-- Story 4-1 review follow-up: composite index covering the status-filtered lead list query
-- (findByTenantIdAndStatus) so it can also hit the 500ms SLA at scale, matching the
-- unfiltered index added in V19.
-- CONCURRENTLY avoids a full-table write lock while building (requires spring.flyway.mixed=true).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_leads_tenant_status_created ON leads(tenant_id, status, created_at DESC);
