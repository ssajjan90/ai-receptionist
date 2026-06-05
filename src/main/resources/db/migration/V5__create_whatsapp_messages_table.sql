-- content stored for AI processing; DPDP compliance at logging level (log hash, not raw content).
CREATE TABLE whatsapp_messages (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id     UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    message_id    VARCHAR(255),
    sender_type   VARCHAR(20)  NOT NULL,
    sender_phone  VARCHAR(20)  NOT NULL,
    content       TEXT         NOT NULL,
    received_at   TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_whatsapp_sender_type CHECK (sender_type IN ('CUSTOMER', 'OWNER'))
);

CREATE INDEX idx_whatsapp_messages_tenant_id   ON whatsapp_messages(tenant_id);
CREATE INDEX idx_whatsapp_messages_received_at ON whatsapp_messages(received_at);
CREATE UNIQUE INDEX uq_whatsapp_messages_message_id ON whatsapp_messages(message_id) WHERE message_id IS NOT NULL;
