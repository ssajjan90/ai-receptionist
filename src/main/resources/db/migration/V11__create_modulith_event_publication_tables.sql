CREATE TABLE event_publication (
    id UUID PRIMARY KEY,
    publication_date TIMESTAMPTZ NOT NULL,
    listener_id VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    completion_date TIMESTAMPTZ,
    last_resubmission_date TIMESTAMPTZ,
    completion_attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32),
    CONSTRAINT chk_event_publication_status CHECK (
        status IS NULL OR status IN ('PUBLISHED', 'PROCESSING', 'COMPLETED', 'FAILED', 'RESUBMITTED')
    )
);

CREATE INDEX idx_event_publication_incomplete
    ON event_publication(publication_date)
    WHERE completion_date IS NULL;

CREATE INDEX idx_event_publication_event_listener
    ON event_publication(listener_id)
    WHERE completion_date IS NULL;

CREATE TABLE event_publication_archive (
    id UUID PRIMARY KEY,
    publication_date TIMESTAMPTZ NOT NULL,
    listener_id VARCHAR(512) NOT NULL,
    serialized_event TEXT NOT NULL,
    event_type VARCHAR(512) NOT NULL,
    completion_date TIMESTAMPTZ,
    last_resubmission_date TIMESTAMPTZ,
    completion_attempts INTEGER NOT NULL DEFAULT 0,
    status VARCHAR(32),
    CONSTRAINT chk_event_publication_archive_status CHECK (
        status IS NULL OR status IN ('PUBLISHED', 'PROCESSING', 'COMPLETED', 'FAILED', 'RESUBMITTED')
    )
);

CREATE INDEX idx_event_publication_archive_completion
    ON event_publication_archive(completion_date)
    WHERE completion_date IS NOT NULL;
