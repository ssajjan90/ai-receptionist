ALTER TABLE leads ADD CONSTRAINT uk_leads_tenant_phone UNIQUE (tenant_id, phone);
