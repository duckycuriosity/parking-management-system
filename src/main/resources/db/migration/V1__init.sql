CREATE TABLE building
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255) NOT NULL,
    address    TEXT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE parking_lot
(
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255),
    building_id BIGINT NOT NULL,
    created_at  TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_parking_lot_building
        FOREIGN KEY (building_id) REFERENCES building (id)
);
CREATE TABLE lane
(
    id             BIGSERIAL PRIMARY KEY,
    name           VARCHAR(255),
    type           VARCHAR(50),
    status         VARCHAR(50),
    parking_lot_id BIGINT NOT NULL,
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_lane_parking_lot
        FOREIGN KEY (parking_lot_id) REFERENCES parking_lot (id)
);
CREATE TABLE camera
(
    id         BIGSERIAL PRIMARY KEY,
    code       VARCHAR(255),
    ip_address VARCHAR(255),
    status     VARCHAR(50),
    lane_id    BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_camera_lane
        FOREIGN KEY (lane_id) REFERENCES lane (id)
);
CREATE TABLE device
(
    id           BIGSERIAL PRIMARY KEY,
    plate_number VARCHAR(255) NOT NULL UNIQUE,
    info         JSONB,
    created_at   TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE parking_customer
(
    id             BIGSERIAL PRIMARY KEY,
    ai_identity_id VARCHAR(100) NOT NULL,
    zalo_id        VARCHAR(100) NOT NULL,
    phone_number   VARCHAR(20),
    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE user_device_permission
(
    id                  BIGSERIAL PRIMARY KEY,
    parking_customer_id BIGINT NOT NULL,
    device_id           BIGINT NOT NULL,

    role                VARCHAR(50),
    assigned_at         TIMESTAMP,

    CONSTRAINT fk_udp_customer
        FOREIGN KEY (parking_customer_id) REFERENCES parking_customer (id),

    CONSTRAINT fk_udp_device
        FOREIGN KEY (device_id) REFERENCES device (id),

    CONSTRAINT unique_user_device
        UNIQUE (parking_customer_id, device_id)
);
CREATE TABLE parking_session
(
    id             BIGSERIAL PRIMARY KEY,

    parking_lot_id BIGINT    NOT NULL,
    device_id      BIGINT    NOT NULL,

    entry_time     TIMESTAMP NOT NULL,
    exit_time      TIMESTAMP
);