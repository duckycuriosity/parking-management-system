-- ============================================================================
-- COMBINED MIGRATION FILE: Tất cả các migration từ V1 đến V20
-- Khởi tạo toàn bộ database schema cho barie-core-service
-- ============================================================================

-- ============================================================================
-- V1__init.sql
-- ============================================================================
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

-- ============================================================================
-- V2__second_init.sql (EMPTY - skipped)
-- ============================================================================

-- ============================================================================
-- V3__add_ai_identity_columns.sql
-- ============================================================================
ALTER TABLE device
    ADD COLUMN IF NOT EXISTS ai_identity_plate_id VARCHAR(100);

ALTER TABLE parking_customer
    ADD COLUMN IF NOT EXISTS ai_identity_id VARCHAR(100);

-- ============================================================================
-- V4__vms_integration.sql
-- ============================================================================
CREATE TABLE IF NOT EXISTS vms_token (
    id BIGSERIAL PRIMARY KEY,
    token_name VARCHAR(100) NOT NULL UNIQUE,
    access_token TEXT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- V5__create_vms_event_table.sql
-- ============================================================================
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

-- ============================================================================
-- V6__create_parking_message.sql
-- ============================================================================
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

-- ============================================================================
-- V7__add_colums.sql
-- ============================================================================
-- Xử lý bảng device -> vehicle
ALTER TABLE device RENAME TO vehicle;

ALTER TABLE vehicle
    ADD COLUMN IF NOT EXISTS ai_identity_plate_id VARCHAR(100);

ALTER TABLE vehicle
    ALTER COLUMN plate_number TYPE VARCHAR(50);

-- Xử lý bảng parking_customer
ALTER TABLE parking_customer
    ADD COLUMN IF NOT EXISTS username VARCHAR(100) NOT NULL DEFAULT 'unknown_user';

-- Xử lý bảng user_device_permission
ALTER TABLE user_device_permission RENAME TO vehicle_customer_permission;

ALTER TABLE vehicle_customer_permission
    RENAME COLUMN device_id TO vehicle_id;

ALTER TABLE vehicle_customer_permission DROP COLUMN IF EXISTS assigned_at;

ALTER TABLE vehicle_customer_permission
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

ALTER TABLE vehicle_customer_permission
    RENAME CONSTRAINT fk_udp_customer TO fk_vcp_customer;

ALTER TABLE vehicle_customer_permission
    RENAME CONSTRAINT fk_udp_device TO fk_vcp_vehicle;

ALTER TABLE vehicle_customer_permission
    RENAME CONSTRAINT unique_user_device TO unique_customer_vehicle;

-- Xử lý bảng parking_session
DROP TABLE IF EXISTS parking_session;

CREATE TABLE parking_session (
    id BIGSERIAL PRIMARY KEY,
    parking_lot_id BIGINT NOT NULL,
    vehicle_id BIGINT NOT NULL,
    entry_time TIMESTAMP NOT NULL,
    exit_time TIMESTAMP,
    entry_lane_id BIGINT,
    exit_lane_id BIGINT,
    entry_customer_id BIGINT NOT NULL DEFAULT 1,
    exit_customer_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_session_parking_lot
        FOREIGN KEY (parking_lot_id) REFERENCES parking_lot(id),
    CONSTRAINT fk_session_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    CONSTRAINT fk_session_entry_lane
        FOREIGN KEY (entry_lane_id) REFERENCES lane(id),
    CONSTRAINT fk_session_exit_lane
        FOREIGN KEY (exit_lane_id) REFERENCES lane(id),
    CONSTRAINT fk_parking_session_entry_customer
        FOREIGN KEY (entry_customer_id) REFERENCES parking_customer(id),
    CONSTRAINT fk_parking_session_exit_customer
        FOREIGN KEY (exit_customer_id) REFERENCES parking_customer(id)
);

-- Tạo các bảng mới
CREATE TABLE organize (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255) NOT NULL,
    branch_code VARCHAR(255) NOT NULL,
    address VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE branch (
    id BIGSERIAL PRIMARY KEY,
    organize_id BIGINT NOT NULL,
    branch_code VARCHAR(255) NOT NULL,
    address VARCHAR(255) NOT NULL,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_branch_organize FOREIGN KEY (organize_id) REFERENCES organize(id),
    CONSTRAINT uk_branch_organize_code UNIQUE (organize_id, branch_code),
    CONSTRAINT uk_branch_id_organize UNIQUE (id, organize_id)
);

CREATE TABLE sys_user (
    id BIGSERIAL PRIMARY KEY,
    organize_id BIGINT NOT NULL,
    branch_id BIGINT,
    username VARCHAR(255) NOT NULL UNIQUE,
    password_hash VARCHAR(255) NOT NULL,
    email VARCHAR(255) NOT NULL UNIQUE,
    status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED')),
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_users_organize FOREIGN KEY (organize_id) REFERENCES organize(id),
    CONSTRAINT fk_users_branch FOREIGN KEY (branch_id) REFERENCES branch(id),
    CONSTRAINT fk_users_branch_organize FOREIGN KEY (branch_id, organize_id) REFERENCES branch(id, organize_id)
);

CREATE TABLE role (
    id BIGSERIAL PRIMARY KEY,
    organize_id BIGINT NOT NULL,
    role_name VARCHAR(255) NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_role_organize FOREIGN KEY (organize_id) REFERENCES organize(id),
    CONSTRAINT uk_role_organize_name UNIQUE (organize_id, role_name)
);

CREATE TABLE permission (
    id BIGSERIAL PRIMARY KEY,
    permission_name VARCHAR(255) NOT NULL UNIQUE,
    resource VARCHAR(255) NOT NULL,
    description TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE role_permission (
    id BIGSERIAL PRIMARY KEY,
    role_id BIGINT NOT NULL,
    permission_id BIGINT NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role(id),
    CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission(id),
    CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id)
);

CREATE TABLE payment (
    id BIGSERIAL PRIMARY KEY,
    invoice_no VARCHAR(255),
    amount NUMERIC(15,2),
    currency VARCHAR(10),
    payment_method VARCHAR(50),
    paid_at TIMESTAMP,
    status VARCHAR(50),
    parking_customer_id BIGINT,
    sys_user_id BIGINT,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_payment_customer
        FOREIGN KEY (parking_customer_id) REFERENCES parking_customer(id),
    CONSTRAINT fk_payment_sys_user
        FOREIGN KEY (sys_user_id) REFERENCES sys_user(id)
);

CREATE TABLE ticket (
    id BIGSERIAL PRIMARY KEY,
    vehicle_id BIGINT,
    register_at TIMESTAMP NOT NULL,
    expire_at TIMESTAMP NOT NULL,
    status VARCHAR(50),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ticket_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle(id)
);

CREATE TABLE ticket_payment (
    id BIGSERIAL PRIMARY KEY,
    ticket_id BIGINT NOT NULL,
    payment_id BIGINT NOT NULL,
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT fk_ticket
        FOREIGN KEY (ticket_id) REFERENCES ticket(id),
    CONSTRAINT fk_payment_tp
        FOREIGN KEY (payment_id) REFERENCES payment(id)
);

CREATE TABLE parking_rule (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pricing_rule (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(255),
    price NUMERIC(15,2),
    currency VARCHAR(10),
    created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

-- ============================================================================
-- V8__pricing_seed_and_payment_session.sql
-- ============================================================================
ALTER TABLE pricing_rule
    ADD COLUMN IF NOT EXISTS type VARCHAR(20);

ALTER TABLE pricing_rule
    ADD CONSTRAINT uk_pricing_rule_type UNIQUE (type);

INSERT INTO pricing_rule (type, name, price, currency, created_at)
VALUES ('DAY',   'Daily Rate',   5000,   'VND', CURRENT_TIMESTAMP),
       ('MONTH', 'Monthly Rate', 150000, 'VND', CURRENT_TIMESTAMP)
ON CONFLICT (type) DO NOTHING;

-- ============================================================================
-- V9__add_lane_id_to_parking_devices.sql
-- ============================================================================
ALTER TABLE parking_devices
    ADD COLUMN IF NOT EXISTS lane_id bigint;

CREATE INDEX IF NOT EXISTS idx_parking_devices_lane_id ON parking_devices(lane_id);

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'fk_parking_device_lane'
    ) THEN
        ALTER TABLE parking_devices
            ADD CONSTRAINT fk_parking_device_lane
            FOREIGN KEY (lane_id) REFERENCES lane(id);
    END IF;
END$$;

-- ============================================================================
-- V10_remove_contraint.sql
-- ============================================================================
ALTER TABLE public.parking_customer ALTER COLUMN zalo_id DROP NOT NULL;

-- ============================================================================
-- V10__add_columns_to_camera.sql
-- ============================================================================
ALTER TABLE camera
    ADD COLUMN camera_uuid VARCHAR(255),
    ADD COLUMN name        VARCHAR(255),
    ADD COLUMN url         TEXT,
    ADD COLUMN type        VARCHAR(50),
    ADD COLUMN latitude    DOUBLE PRECISION,
    ADD COLUMN longitude   DOUBLE PRECISION;

-- ============================================================================
-- V11__add_ai_camera_id.sql
-- ============================================================================
ALTER TABLE camera ADD COLUMN IF NOT EXISTS ai_camera_id bigint;

-- ============================================================================
-- V12__make_camera_lane_id_nullable.sql
-- ============================================================================
ALTER TABLE camera
    ALTER COLUMN lane_id DROP NOT NULL;

-- ============================================================================
-- V13__create_barriers.sql
-- ============================================================================
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

-- ============================================================================
-- V14__add_more_info_in_parking_customer_table.sql
-- ============================================================================
ALTER TABLE parking_customer ADD COLUMN IF NOT EXISTS ai_profile_id bigint;

-- ============================================================================
-- V15__add_ai_plate_to_vehicle_table.sql
-- ============================================================================
ALTER TABLE vehicle
    ADD COLUMN IF NOT EXISTS vehicle_type VARCHAR(20) NOT NULL,
    ADD CONSTRAINT chk_vehicle_type CHECK (vehicle_type IN ('CAR', 'MOTOBIKE', 'OTHER'));

-- ============================================================================
-- V16__add_camera_barier_table.sql
-- ============================================================================
CREATE TABLE IF NOT EXISTS barrier_camera (
    id BIGSERIAL PRIMARY KEY,
    barrier_id VARCHAR(100) NOT NULL,
    camera_id BIGINT NOT NULL,
    role VARCHAR(16) NOT NULL,
    priority INT DEFAULT 0,
    created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now(),
    UNIQUE (barrier_id, camera_id)
);

ALTER TABLE barrier_camera
    ADD CONSTRAINT fk_barrier_camera_barrier
    FOREIGN KEY (barrier_id) REFERENCES barriers(barrier_id) ON DELETE CASCADE;

CREATE INDEX idx_barrier_camera_camera_id ON barrier_camera(camera_id);

-- ============================================================================
-- V17__modify_table_ticket.sql
-- ============================================================================
ALTER TABLE ticket 
    ADD COLUMN IF NOT EXISTS customer_id BIGINT,
    ADD CONSTRAINT fk_ticket_customer FOREIGN KEY (customer_id) REFERENCES parking_customer(id) ON DELETE SET NULL;

ALTER TABLE ticket 
    ADD COLUMN IF NOT EXISTS ticket_type VARCHAR(20) NOT NULL,
    ADD CONSTRAINT chk_ticket_type CHECK (ticket_type IN ('MONTH', 'QUARTER', 'YEAR'));

-- ============================================================================
-- V18__add_more_fields_in_parking_session.sql
-- ============================================================================
ALTER TABLE parking_session 
    ADD COLUMN IF NOT EXISTS entry_barrier VARCHAR(50),
    ADD COLUMN IF NOT EXISTS exit_barrier VARCHAR(50),
    ADD COLUMN IF NOT EXISTS ticket_id BIGINT,
    ADD COLUMN IF NOT EXISTS session_type VARCHAR(20) NOT NULL;

ALTER TABLE parking_session 
    ADD CONSTRAINT chk_session_type CHECK (session_type IN ('MONTHLY', 'QUARTERLY', 'YEARLY', 'DAILY'));

-- ============================================================================
-- V20__add_payment_context.sql
-- ============================================================================
ALTER TABLE payment ADD COLUMN IF NOT EXISTS callback_type VARCHAR(50);

ALTER TABLE payment ADD COLUMN IF NOT EXISTS callback_context JSONB;

CREATE TABLE IF NOT EXISTS session_payment (
    id          BIGSERIAL PRIMARY KEY,
    session_id  BIGINT NOT NULL REFERENCES parking_session(id),
    payment_id  BIGINT NOT NULL REFERENCES payment(id),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_session_payment UNIQUE (session_id, payment_id)
);

CREATE INDEX IF NOT EXISTS idx_session_payment_session_id ON session_payment(session_id);
CREATE INDEX IF NOT EXISTS idx_session_payment_payment_id ON session_payment(payment_id);

-- ============================================================================
-- END OF COMBINED MIGRATIONS
-- ============================================================================
