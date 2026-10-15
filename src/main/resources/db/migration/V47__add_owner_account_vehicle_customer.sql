CREATE TABLE IF NOT EXISTS owner_account
(
    id              BIGSERIAL PRIMARY KEY,
    full_name       VARCHAR(255) NOT NULL,
    phone           VARCHAR(30)  NOT NULL,
    identity_number VARCHAR(30),
    address         TEXT,
    image_url       TEXT,
    zalo_link       TEXT,
    note            TEXT,
    status          VARCHAR(20)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE UNIQUE INDEX IF NOT EXISTS uk_owner_account_phone
    ON owner_account (phone);

CREATE UNIQUE INDEX IF NOT EXISTS uk_owner_account_identity_number
    ON owner_account (identity_number)
    WHERE identity_number IS NOT NULL;

ALTER TABLE vehicle
    ADD COLUMN IF NOT EXISTS owner_id BIGINT;

ALTER TABLE parking_customer
    ADD COLUMN IF NOT EXISTS owner_id BIGINT;

CREATE TABLE IF NOT EXISTS vehicle_customer
(
    id            BIGSERIAL PRIMARY KEY,
    vehicle_id    BIGINT      NOT NULL,
    customer_id   BIGINT      NOT NULL,
    relation_type VARCHAR(30) NOT NULL,
    status        VARCHAR(20) NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);


CREATE INDEX IF NOT EXISTS idx_vehicle_owner_id
    ON vehicle (owner_id);

CREATE INDEX IF NOT EXISTS idx_parking_customer_owner_id
    ON parking_customer (owner_id);

CREATE INDEX IF NOT EXISTS idx_vehicle_customer_vehicle_id
    ON vehicle_customer (vehicle_id);

CREATE INDEX IF NOT EXISTS idx_vehicle_customer_customer_id
    ON vehicle_customer (customer_id);

CREATE TABLE IF NOT EXISTS vehicle_subscription
(
    id                       SERIAL PRIMARY KEY,
    vehicle_id               INTEGER      NOT NULL,
    parking_lot_id           INTEGER      NOT NULL,
    package_id               INTEGER      NOT NULL,
    registration_type        VARCHAR(20)  NOT NULL,
    previous_subscription_id INTEGER,
    price_vnd                BIGINT       NOT NULL,
    starts_at                TIMESTAMPTZ  NOT NULL,
    ends_at                  TIMESTAMPTZ  NOT NULL,
    payment_deadline_at      TIMESTAMPTZ,
    status                   VARCHAR(30)  NOT NULL,
    paid_at                  TIMESTAMPTZ,
    payment_source           VARCHAR(50),
    payment_reference        VARCHAR(255),
    payment_receipt          JSONB,
    created_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at               TIMESTAMPTZ  NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX IF NOT EXISTS idx_vehicle_subscription_vehicle_id
    ON vehicle_subscription (vehicle_id);

CREATE INDEX IF NOT EXISTS idx_vehicle_subscription_parking_lot_id
    ON vehicle_subscription (parking_lot_id);

CREATE INDEX IF NOT EXISTS idx_vehicle_subscription_package_id
    ON vehicle_subscription (package_id);
