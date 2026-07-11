-- Story 4-1 review follow-up: optimistic locking to prevent lost updates on concurrent lead status changes.
ALTER TABLE leads ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
