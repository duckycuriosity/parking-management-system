ALTER TABLE vehicle_subscription
    ALTER COLUMN id TYPE BIGINT,
    ALTER COLUMN vehicle_id TYPE BIGINT,
    ALTER COLUMN parking_lot_id TYPE BIGINT,
    ALTER COLUMN package_id TYPE BIGINT,
    ALTER COLUMN previous_subscription_id TYPE BIGINT;

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
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
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
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_sub_pkg_schedule_package_weekday
    ON subscription_package_schedule (package_id, weekday);

TRUNCATE TABLE vehicle_subscription CASCADE;

-- Foreign keys, unique constraints, and check constraints are intentionally not added.
-- Primary keys above are kept.
