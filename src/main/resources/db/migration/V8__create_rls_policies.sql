-- FORCE ROW LEVEL SECURITY ensures RLS applies even to superusers / table owners.
-- Required so Testcontainers' postgres superuser is also subject to RLS in integration tests.
--
-- Policy: tenant_id::text = current_setting('app.current_tenant', true)
--   missing_ok=true → returns NULL when setting is absent → zero rows visible (secure default).
--   USING clause also acts as WITH CHECK: INSERT/UPDATE only allowed for the active tenant.

-- knowledge_entries
ALTER TABLE knowledge_entries ENABLE ROW LEVEL SECURITY;
ALTER TABLE knowledge_entries FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON knowledge_entries
    USING (tenant_id::text = current_setting('app.current_tenant', true));

-- leads
ALTER TABLE leads ENABLE ROW LEVEL SECURITY;
ALTER TABLE leads FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON leads
    USING (tenant_id::text = current_setting('app.current_tenant', true));

-- subscriptions
ALTER TABLE subscriptions ENABLE ROW LEVEL SECURITY;
ALTER TABLE subscriptions FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON subscriptions
    USING (tenant_id::text = current_setting('app.current_tenant', true));

-- whatsapp_messages
ALTER TABLE whatsapp_messages ENABLE ROW LEVEL SECURITY;
ALTER TABLE whatsapp_messages FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON whatsapp_messages
    USING (tenant_id::text = current_setting('app.current_tenant', true));

-- voice_calls
ALTER TABLE voice_calls ENABLE ROW LEVEL SECURITY;
ALTER TABLE voice_calls FORCE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON voice_calls
    USING (tenant_id::text = current_setting('app.current_tenant', true));
