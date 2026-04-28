CREATE INDEX IF NOT EXISTS idx_leads_tenant_id ON leads(tenant_id);
CREATE INDEX IF NOT EXISTS idx_leads_status ON leads(status);
CREATE INDEX IF NOT EXISTS idx_appointments_tenant_id ON appointments(tenant_id);
CREATE INDEX IF NOT EXISTS idx_appointments_time ON appointments(appointment_time);
CREATE INDEX IF NOT EXISTS idx_chat_history_tenant_phone ON chat_history(tenant_id, customer_phone);
CREATE INDEX IF NOT EXISTS idx_knowledge_base_tenant_active ON knowledge_base(tenant_id, active);
