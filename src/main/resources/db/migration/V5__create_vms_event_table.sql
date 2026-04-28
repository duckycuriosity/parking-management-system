CREATE TABLE IF NOT EXISTS vms_event
(
    id                  BIGSERIAL PRIMARY KEY,
    vms_event_id        BIGINT           NOT NULL UNIQUE,
    service_id          INTEGER,
    origin_id           BIGINT,
    event_type          VARCHAR(120)     NOT NULL,
    is_confirmed        BOOLEAN,
    event_time          TIMESTAMP        NOT NULL,
    event_epoch_seconds DOUBLE PRECISION NOT NULL,
    camera_uuid VARCHAR(120),
    monitor_type VARCHAR(120),
    profile_uuid VARCHAR(120),
    profile_name VARCHAR(255),
    payload JSONB NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);