ALTER TABLE tenants
    ADD COLUMN location VARCHAR(255),
    ADD COLUMN business_hours VARCHAR(255),
    ADD COLUMN onboarded_at TIMESTAMPTZ;

ALTER TABLE knowledge_entries
    ALTER COLUMN question DROP NOT NULL,
    ALTER COLUMN answer DROP NOT NULL,
    ADD COLUMN product_name VARCHAR(255),
    ADD COLUMN price VARCHAR(255),
    ADD COLUMN source VARCHAR(32) NOT NULL DEFAULT 'WIZARD';

CREATE UNIQUE INDEX uq_knowledge_entries_onboarding_product
    ON knowledge_entries(tenant_id, entry_type, product_name)
    WHERE entry_type = 'PRODUCT' AND product_name IS NOT NULL AND source = 'WIZARD';

CREATE UNIQUE INDEX uq_knowledge_entries_onboarding_faq
    ON knowledge_entries(tenant_id, entry_type, question)
    WHERE entry_type = 'FAQ' AND question IS NOT NULL AND source = 'WIZARD';
