-- chk_whatsapp_sender_type (V5) was never updated when SenderType.AI was added to the domain
-- (story 3.2+) — every WhatsAppMessage.outboundAi() save has been silently violating this
-- constraint ever since. Because WhatsAppMessageService.onInboundMessage runs as an async
-- @ApplicationModuleListener, the resulting exception is logged and swallowed, not surfaced —
-- discovered 2026-09-01 while building story 5.3's conversation-log query.
--
-- NOT VALID + separate VALIDATE CONSTRAINT (code review, 2026-09-01): a plain DROP + ADD leaves
-- a brief window with no constraint at all, and ADD CONSTRAINT ... CHECK on an existing table
-- forces a full-table validation scan under an ACCESS EXCLUSIVE lock. NOT VALID adds the new
-- constraint immediately (enforced for all NEW rows right away, closing the "no constraint"
-- window) without scanning existing rows; VALIDATE CONSTRAINT then scans under a much lighter
-- SHARE UPDATE EXCLUSIVE lock that doesn't block concurrent reads/writes.
ALTER TABLE whatsapp_messages DROP CONSTRAINT chk_whatsapp_sender_type;
ALTER TABLE whatsapp_messages ADD CONSTRAINT chk_whatsapp_sender_type
    CHECK (sender_type IN ('CUSTOMER', 'OWNER', 'AI')) NOT VALID;
ALTER TABLE whatsapp_messages VALIDATE CONSTRAINT chk_whatsapp_sender_type;
