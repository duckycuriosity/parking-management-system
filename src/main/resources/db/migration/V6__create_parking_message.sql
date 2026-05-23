CREATE TABLE parking_devices
(
    id                BIGSERIAL PRIMARY KEY,
    device_id         VARCHAR(100) UNIQUE NOT NULL,
    status            VARCHAR(20)         NOT NULL,
    firmware_version  VARCHAR(50),
    has_cash_reader   BOOLEAN,
    has_display       BOOLEAN,
    last_seen         TIMESTAMP,
    cpu_temp_c        NUMERIC(10, 2),
    free_mem_mb       INTEGER,
    uptime_seconds    BIGINT,
    disconnect_reason VARCHAR(100),
    created_at        TIMESTAMP DEFAULT NOW(),
    updated_at        TIMESTAMP DEFAULT NOW()
);

CREATE TABLE parking_messages
(
    id               BIGSERIAL PRIMARY KEY,
    device_id        VARCHAR(100) NOT NULL,
    message_type     VARCHAR(30)  NOT NULL,
    topic            VARCHAR(255),
    payload          JSONB        NOT NULL,
    firmware_version VARCHAR(50),
    reason           VARCHAR(100),
    uptime_seconds   BIGINT,
    cpu_temp_c       NUMERIC(10, 2),
    free_mem_mb      INTEGER,
    created_at       TIMESTAMP DEFAULT NOW()
);

CREATE INDEX idx_pm_device_id ON parking_messages (device_id);
CREATE INDEX idx_pm_created_at ON parking_messages (created_at DESC);