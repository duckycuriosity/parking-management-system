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
    exit_time      TIMESTAMP,

    entry_lane_id  BIGINT,
    exit_lane_id   BIGINT,

    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ps_parking_lot
        FOREIGN KEY (parking_lot_id) REFERENCES parking_lot (id),

    CONSTRAINT fk_ps_device
        FOREIGN KEY (device_id) REFERENCES user_device_permission (id),

    CONSTRAINT fk_ps_entry_lane
        FOREIGN KEY (entry_lane_id) REFERENCES lane (id),

    CONSTRAINT fk_ps_exit_lane
        FOREIGN KEY (exit_lane_id) REFERENCES lane (id)
);
CREATE TABLE payment
(
    id                  BIGSERIAL PRIMARY KEY,

    invoice_no          VARCHAR(255),
    amount              NUMERIC(15, 2),
    currency            VARCHAR(10),

    payment_method      VARCHAR(50),
    paid_at             TIMESTAMP,
    status              VARCHAR(50),

    parking_customer_id BIGINT UNIQUE,

    created_at          TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payment_customer
        FOREIGN KEY (parking_customer_id) REFERENCES parking_customer (id)
);
CREATE TABLE ticket
(
    id             BIGSERIAL PRIMARY KEY,

    device_id      BIGINT,
    payment_id     BIGINT,

    register_at    TIMESTAMP NOT NULL,
    expire_at      TIMESTAMP NOT NULL,

    duration       VARCHAR(50),
    type           VARCHAR(50),
    status         VARCHAR(50),

    payment_method VARCHAR(50),

    created_at     TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ticket_device
        FOREIGN KEY (device_id) REFERENCES device (id),

    CONSTRAINT fk_ticket_payment
        FOREIGN KEY (payment_id) REFERENCES payment (id)
);
CREATE TABLE pricing_rule
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255),
    price      NUMERIC(15, 2),
    currency   VARCHAR(10),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE parking_rule
(
    id         BIGSERIAL PRIMARY KEY,
    name       VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);
CREATE TABLE sys_user
(
    id         BIGSERIAL PRIMARY KEY,
    username   VARCHAR(255) NOT NULL UNIQUE,
    password   VARCHAR(255) NOT NULL,
    role       VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);