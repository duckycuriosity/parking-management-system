CREATE TABLE IF NOT EXISTS lane_incident
(
    id                BIGSERIAL PRIMARY KEY,
    type              VARCHAR(50) NOT NULL,
    status            VARCHAR(50) NOT NULL,
    lane_id           BIGINT,
    barrier_id        VARCHAR(255),
    session_id        BIGINT REFERENCES parking_session (id),
    detected_plate    VARCHAR(50),
    entry_profile_id  INTEGER,
    exit_profile_id   INTEGER,
    exit_image_info   JSONB,
    detection_payload JSONB,
    resolved_at       TIMESTAMP,
    resolved_by       BIGINT,
    created_at        TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_lane_incident_pending_session
    ON lane_incident (session_id, type) WHERE status = 'PENDING';

CREATE INDEX IF NOT EXISTS idx_lane_incident_status_lane ON lane_incident (status, lane_id);

CREATE TABLE IF NOT EXISTS lane_incident_action
(
    id             BIGSERIAL PRIMARY KEY,
    incident_id    BIGINT      NOT NULL REFERENCES lane_incident (id),
    action_type    VARCHAR(50) NOT NULL,
    actor_user_id  BIGINT,
    actor_username VARCHAR(255),
    note           TEXT,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_lane_incident_action_incident ON lane_incident_action (incident_id);
