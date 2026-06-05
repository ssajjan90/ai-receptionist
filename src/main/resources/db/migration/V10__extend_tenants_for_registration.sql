ALTER TABLE tenants
    ADD COLUMN owner_phone VARCHAR(20),
    ADD COLUMN email VARCHAR(255),
    ADD COLUMN password_hash VARCHAR(255),
    ADD COLUMN preferred_language VARCHAR(10) NOT NULL DEFAULT 'en',
    ADD COLUMN waba_id VARCHAR(255),
    ADD COLUMN phone_number_id VARCHAR(255),
    ADD COLUMN google_review_url TEXT;

ALTER TABLE tenants DROP CONSTRAINT chk_tenants_status;

UPDATE tenants
SET status = 'PENDING_VERIFICATION'
WHERE status = 'PENDING';

ALTER TABLE tenants
    ALTER COLUMN status TYPE VARCHAR(32),
    ALTER COLUMN status SET DEFAULT 'PENDING_VERIFICATION',
    ADD CONSTRAINT chk_tenants_status CHECK (status IN (
        'PENDING_VERIFICATION',
        'ACTIVE',
        'ONBOARDING_COMPLETE',
        'LIVE',
        'SUSPENDED',
        'PAYMENT_SUSPENDED',
        'TERMINATED',
        'ERASED'
    ));

CREATE UNIQUE INDEX uq_tenants_owner_phone ON tenants(owner_phone) WHERE owner_phone IS NOT NULL;
CREATE UNIQUE INDEX uq_tenants_email ON tenants(email) WHERE email IS NOT NULL;
