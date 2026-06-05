-- Add AI processing fields to whatsapp_messages for future stories (3.2+)
ALTER TABLE whatsapp_messages
    ADD COLUMN IF NOT EXISTS direction       VARCHAR(20),
    ADD COLUMN IF NOT EXISTS confidence_score DECIMAL(5, 2),
    ADD COLUMN IF NOT EXISTS language        VARCHAR(20);
