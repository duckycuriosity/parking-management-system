

CREATE TABLE IF NOT EXISTS camera_event_log (
    id VARCHAR(55) PRIMARY KEY,
    event_id BIGINT,
    event_type VARCHAR(255),
    camera_uuid VARCHAR(255),
    event_time VARCHAR(255),
    model_name VARCHAR(255),
    metadata JSONB,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
