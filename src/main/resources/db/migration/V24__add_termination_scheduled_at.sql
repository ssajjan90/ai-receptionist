-- Story 5.2: tracks the 30-day retention window before ScheduledDataRetentionJob erases a
-- TERMINATED tenant's data (AC3, NFR28). Numbered V24, not the story's suggested V14 — V14 was
-- already taken (whatsapp_messages direction/AI fields) by the time this story landed.
ALTER TABLE tenants ADD COLUMN termination_scheduled_at TIMESTAMPTZ;
