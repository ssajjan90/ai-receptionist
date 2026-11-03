-- Epic 6 code review: Exotel's From can exceed 20 chars (SIP URIs, extensions); VARCHAR(20)
-- silently lost the voice_calls row and failed every turn of such calls.
ALTER TABLE voice_calls ALTER COLUMN caller_phone TYPE VARCHAR(64);
