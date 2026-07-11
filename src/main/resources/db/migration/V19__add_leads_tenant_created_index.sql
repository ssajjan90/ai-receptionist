-- Story 4-1: composite index to keep GET /v1/tenants/{tenantId}/leads within the 500ms SLA at scale.
-- CONCURRENTLY avoids a full-table write lock while building on a leads table that may already
-- have production rows (requires spring.flyway.mixed=true, see application.yml).
CREATE INDEX CONCURRENTLY IF NOT EXISTS idx_leads_tenant_created ON leads(tenant_id, created_at DESC);
