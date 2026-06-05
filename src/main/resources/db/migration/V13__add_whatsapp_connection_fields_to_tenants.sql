ALTER TABLE tenants
    ADD COLUMN whatsapp_connected_at TIMESTAMPTZ;

CREATE UNIQUE INDEX uq_tenants_phone_number_id
    ON tenants(phone_number_id)
    WHERE phone_number_id IS NOT NULL;
