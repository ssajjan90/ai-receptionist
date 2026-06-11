ALTER TABLE whatsapp_messages
    ADD COLUMN IF NOT EXISTS delivery_status VARCHAR(20);
