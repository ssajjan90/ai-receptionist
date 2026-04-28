CREATE TABLE IF NOT EXISTS tenants (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    industry VARCHAR(100),
    phone VARCHAR(50),
    email VARCHAR(255) UNIQUE,
    address VARCHAR(500),
    working_hours VARCHAR(255),
    default_language VARCHAR(100),
    supported_languages VARCHAR(255),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS users (
    id BIGSERIAL PRIMARY KEY,
    email VARCHAR(255) NOT NULL UNIQUE,
    password VARCHAR(255) NOT NULL,
    name VARCHAR(255) NOT NULL,
    role VARCHAR(50) NOT NULL DEFAULT 'TENANT_ADMIN',
    tenant_id BIGINT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_users_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE IF NOT EXISTS leads (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    customer_name VARCHAR(255),
    phone VARCHAR(50),
    email VARCHAR(255),
    requirement TEXT,
    status VARCHAR(50) NOT NULL DEFAULT 'NEW',
    source VARCHAR(50),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_leads_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE IF NOT EXISTS appointments (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    customer_name VARCHAR(255),
    phone VARCHAR(50),
    service_name VARCHAR(255),
    appointment_time TIMESTAMPTZ,
    status VARCHAR(50) NOT NULL DEFAULT 'REQUESTED',
    notes TEXT,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_appointments_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE IF NOT EXISTS chat_history (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT NOT NULL,
    customer_phone VARCHAR(50),
    channel VARCHAR(50),
    user_message TEXT,
    ai_response TEXT,
    intent VARCHAR(100),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_chat_history_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGSERIAL PRIMARY KEY,
    tenant_id BIGINT,
    industry VARCHAR(255) NOT NULL DEFAULT 'CLINIC',
    category VARCHAR(255),
    intent VARCHAR(255) NOT NULL DEFAULT 'SERVICES',
    question TEXT NOT NULL,
    answer TEXT NOT NULL,
    language VARCHAR(100) NOT NULL DEFAULT 'English',
    type VARCHAR(100) NOT NULL DEFAULT 'SERVICE',
    alt_questions TEXT,
    keywords TEXT,
    priority INTEGER NOT NULL DEFAULT 1,
    active BOOLEAN NOT NULL DEFAULT TRUE,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    CONSTRAINT fk_knowledge_base_tenant FOREIGN KEY (tenant_id) REFERENCES tenants (id)
);

CREATE INDEX IF NOT EXISTS idx_leads_tenant_id ON leads (tenant_id);
CREATE INDEX IF NOT EXISTS idx_leads_status ON leads (status);
CREATE INDEX IF NOT EXISTS idx_appointments_tenant_id ON appointments (tenant_id);
CREATE INDEX IF NOT EXISTS idx_appointments_time ON appointments (appointment_time);
CREATE INDEX IF NOT EXISTS idx_chat_history_tenant_phone ON chat_history (tenant_id, customer_phone);
CREATE INDEX IF NOT EXISTS idx_knowledge_base_tenant_active ON knowledge_base (tenant_id, active);
