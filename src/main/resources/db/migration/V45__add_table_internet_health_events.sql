CREATE TABLE IF NOT EXISTS internet_health_events (
    id BIGSERIAL PRIMARY KEY,
    event_type VARCHAR(50) NOT NULL,
    previous_status VARCHAR(20) NOT NULL,
    current_status VARCHAR(20) NOT NULL,
    reason VARCHAR(100),
    downtime_seconds BIGINT,
    changed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE INDEX IF NOT EXISTS idx_changed_at ON internet_health_events (changed_at DESC);
