CREATE TABLE knowledge_entries (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL REFERENCES tenants(id) ON DELETE CASCADE,
    question    TEXT         NOT NULL,
    answer      TEXT         NOT NULL,
    entry_type  VARCHAR(20)  NOT NULL DEFAULT 'FAQ',
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT chk_knowledge_entries_type CHECK (entry_type IN ('FAQ', 'PRODUCT', 'SERVICE'))
);

CREATE INDEX idx_knowledge_entries_tenant_id ON knowledge_entries(tenant_id);
