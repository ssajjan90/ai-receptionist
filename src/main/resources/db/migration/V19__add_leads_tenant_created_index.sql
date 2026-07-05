-- Story 4-1: composite index to keep GET /v1/tenants/{tenantId}/leads within the 500ms SLA at scale.
CREATE INDEX IF NOT EXISTS idx_leads_tenant_created ON leads(tenant_id, created_at DESC);
