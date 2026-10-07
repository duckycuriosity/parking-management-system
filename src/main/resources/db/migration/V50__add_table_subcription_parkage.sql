-- ==============================================================================
-- BƯỚC 1: ĐỒNG BỘ KIỂU DỮ LIỆU CỦA BẢNG HIỆN TẠI VỀ BIGINT
-- ==============================================================================
ALTER TABLE vehicle_subscription
    ALTER COLUMN id TYPE BIGINT,
    ALTER COLUMN vehicle_id TYPE BIGINT,
    ALTER COLUMN parking_lot_id TYPE BIGINT,
    ALTER COLUMN package_id TYPE BIGINT,
    ALTER COLUMN previous_subscription_id TYPE BIGINT;

-- ==============================================================================
-- BƯỚC 2: TẠO BẢNG MỚI VÀ INDEX KÈM CÁC RÀNG BUỘC CHECK
-- ==============================================================================
CREATE TABLE IF NOT EXISTS subscription_package
(
    id             BIGSERIAL PRIMARY KEY,
    package_code   VARCHAR(50)  NOT NULL,
    version_no     INTEGER      NOT NULL,
    parking_lot_id BIGINT       NOT NULL,
    vehicle_type   VARCHAR(20)  NOT NULL,
    name           VARCHAR(255) NOT NULL,
    price_vnd      BIGINT       NOT NULL,
    duration_days  INTEGER      NOT NULL,
    coverage_mode  VARCHAR(20)  NOT NULL,
    effective_from TIMESTAMPTZ  NOT NULL,
    effective_to   TIMESTAMPTZ,
    status         VARCHAR(20)  NOT NULL DEFAULT 'DRAFT',
    sale_enabled   BOOLEAN      NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT uk_package_code_version UNIQUE (package_code, version_no),
    CONSTRAINT chk_package_price CHECK (price_vnd >= 0),
    CONSTRAINT chk_package_duration CHECK (duration_days > 0),
    CONSTRAINT chk_package_version CHECK (version_no > 0),
    CONSTRAINT chk_package_vehicle_type CHECK (vehicle_type IN ('CAR', 'MOTORBIKE')),
    CONSTRAINT chk_package_coverage CHECK (coverage_mode IN ('ALL_DAY', 'WEEKLY')),
    CONSTRAINT chk_package_status CHECK (status IN ('DRAFT', 'PUBLISHED')),
    CONSTRAINT chk_package_effective_dates CHECK (effective_to IS NULL OR effective_to > effective_from)
);

CREATE INDEX IF NOT EXISTS idx_sub_pkg_lot_vehicle_date
    ON subscription_package (parking_lot_id, vehicle_type, effective_from);

CREATE TABLE IF NOT EXISTS subscription_package_schedule
(
    id             BIGSERIAL PRIMARY KEY,
    package_id     BIGINT      NOT NULL,
    weekday        SMALLINT    NOT NULL,
    start_time     TIME        NOT NULL,
    end_time       TIME        NOT NULL,
    end_day_offset SMALLINT    NOT NULL DEFAULT 0,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_schedule_weekday CHECK (weekday BETWEEN 1 AND 7),
    CONSTRAINT chk_schedule_offset CHECK (end_day_offset IN (0, 1)),
    CONSTRAINT chk_schedule_time CHECK (
        (end_day_offset = 0 AND end_time > start_time) OR
        (end_day_offset = 1 AND end_time <= start_time)
        )
);

CREATE INDEX IF NOT EXISTS idx_sub_pkg_schedule_package_weekday
    ON subscription_package_schedule (package_id, weekday);

-- ==============================================================================
-- BƯỚC 3: DỌN RÁC (TRUNCATE) ĐỂ LÀM SẠCH DỮ LIỆU XUNG ĐỘT
-- Ghi chú: Thêm CASCADE để PostgreSQL tự động dọn các tham chiếu phụ thuộc (nếu có)
-- ==============================================================================
TRUNCATE TABLE vehicle_subscription CASCADE;

-- ==============================================================================
-- BƯỚC 4: GẮN KHÓA NGOẠI BẢO VỆ TOÀN VẸN DỮ LIỆU
-- Do bảng đã được TRUNCATE trống rỗng ở Bước 3, các lệnh này sẽ thành công 100%
-- ==============================================================================
ALTER TABLE subscription_package
    ADD CONSTRAINT fk_subscription_package_parking_lot
        FOREIGN KEY (parking_lot_id) REFERENCES parking_lot (id);

ALTER TABLE subscription_package_schedule
    ADD CONSTRAINT fk_sub_pkg_schedule_package
        FOREIGN KEY (package_id) REFERENCES subscription_package (id);

ALTER TABLE vehicle_subscription
    ADD CONSTRAINT fk_vehicle_subscription_vehicle
        FOREIGN KEY (vehicle_id) REFERENCES vehicle (id),
    ADD CONSTRAINT fk_vehicle_subscription_parking_lot
        FOREIGN KEY (parking_lot_id) REFERENCES parking_lot (id),
    ADD CONSTRAINT fk_vehicle_subscription_package
        FOREIGN KEY (package_id) REFERENCES subscription_package (id),
    ADD CONSTRAINT fk_vehicle_subscription_previous
        FOREIGN KEY (previous_subscription_id) REFERENCES vehicle_subscription (id);