CREATE TABLE barriers
(
    id                 BIGSERIAL PRIMARY KEY,
    barrier_id         VARCHAR(100) UNIQUE NOT NULL,
    parking_device_id   BIGINT NOT NULL,
    description        VARCHAR(255),
    direction          VARCHAR(20),
    modbus_addr        INTEGER,
    created_at         TIMESTAMP DEFAULT NOW(),
    updated_at         TIMESTAMP DEFAULT NOW(),

    CONSTRAINT fk_barriers_parking_device
        FOREIGN KEY (parking_device_id) REFERENCES parking_devices (id)
        ON DELETE CASCADE
);

CREATE INDEX idx_barriers_parking_device_id ON barriers (parking_device_id);
CREATE INDEX idx_barriers_barrier_id ON barriers (barrier_id);
CREATE INDEX idx_barriers_created_at ON barriers (created_at DESC);