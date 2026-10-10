-- dangpn/subscription already created these three tables in V47/V50.
-- Preserve those immutable migrations and their data instead of importing the
-- incompatible CREATE TABLE baseline V54 from dangpn/fee_policy. This bridge
-- installs the same structural Java/JPA contract used after canonical V55.
-- It intentionally does not invent receipts, deadlines or financial states.

DO $$
BEGIN
    IF EXISTS (
        SELECT 1 FROM vehicle_subscription
        WHERE status NOT IN ('PENDING_PAYMENT', 'CANCELLED', 'PAYMENT_EXCEPTION')
           OR payment_deadline_at IS NULL
           OR paid_at IS NOT NULL
           OR payment_source IS NOT NULL
           OR payment_reference IS NOT NULL
           OR payment_receipt IS NOT NULL
           OR registration_type NOT IN ('NEW', 'RENEW')
           OR (registration_type = 'NEW' AND previous_subscription_id IS NOT NULL)
           OR (registration_type = 'RENEW' AND previous_subscription_id IS NULL)
           -- PAID predecessors need verified receipt mapping and are rejected
           -- above. An unmapped renewal must not become payable after upgrade.
           OR (status = 'PENDING_PAYMENT' AND registration_type = 'RENEW')
           OR ends_at <= starts_at OR price_vnd < 0
    ) THEN
        RAISE EXCEPTION 'Legacy subscriptions require explicit reconciliation before V51: unsupported states, financial evidence, missing deadlines, an unmapped renewal or invalid frozen terms. No rows were deleted or paid receipts fabricated.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM subscription_package_schedule
        WHERE weekday NOT BETWEEN 1 AND 7
           OR start_time >= TIME '24:00:00' OR end_time >= TIME '24:00:00'
           OR end_day_offset <> CASE WHEN end_time > start_time THEN 0 ELSE 1 END
    ) THEN
        RAISE EXCEPTION 'Legacy subscription schedules require explicit reconciliation before V51: end_day_offset must match the agreed same-day/cross-midnight clock semantics. No schedule was rewritten.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM subscription_package
        WHERE length(btrim(package_code)) = 0 OR length(btrim(name)) = 0
           OR version_no <= 0 OR vehicle_type NOT IN ('CAR', 'MOTORBIKE')
           OR price_vnd < 0 OR duration_days <= 0
           OR coverage_mode NOT IN ('ALL_DAY', 'WEEKLY')
           OR status NOT IN ('DRAFT', 'PUBLISHED')
           OR (effective_to IS NOT NULL AND effective_to <= effective_from)
    ) THEN
        RAISE EXCEPTION 'Legacy subscription packages have invalid terms; reconcile explicitly before V51.';
    END IF;
    IF EXISTS (
        SELECT 1 FROM vehicle_subscription s
        JOIN subscription_package p ON p.id = s.package_id
        WHERE p.status <> 'PUBLISHED' OR s.price_vnd <> p.price_vnd
           OR extract(epoch FROM (s.ends_at - s.starts_at)) <> p.duration_days::bigint * 86400
    ) THEN
        RAISE EXCEPTION 'Legacy subscription frozen price/duration differs from its published package; reconcile explicitly before V51.';
    END IF;
END $$;

ALTER TABLE subscription_package
    ALTER COLUMN package_code TYPE VARCHAR(100),
    ADD CONSTRAINT subscription_package_parking_lot_id_fkey
        FOREIGN KEY (parking_lot_id) REFERENCES parking_lot(id),
    ADD CONSTRAINT uq_subscription_package_code_version UNIQUE (package_code, version_no),
    ADD CONSTRAINT uq_subscription_package_id_scope UNIQUE (id, parking_lot_id);

ALTER TABLE subscription_package_schedule
    ALTER COLUMN weekday TYPE INTEGER USING weekday::integer,
    ADD CONSTRAINT subscription_package_schedule_package_id_fkey
        FOREIGN KEY (package_id) REFERENCES subscription_package(id),
    ADD CONSTRAINT uq_subscription_package_schedule_window
        UNIQUE (package_id, weekday, start_time, end_time);
COMMENT ON COLUMN subscription_package_schedule.end_day_offset IS
    'Legacy V50 field retained for audit only. Java/JPA infers wrapping from end_time <= start_time and does not read or write this field.';

ALTER TABLE vehicle_subscription
    ADD COLUMN request_key VARCHAR(100),
    ADD COLUMN paid_receipt_id BIGINT;
-- A deterministic technical key identifies old requests; it does not change
-- the financial quote or claim that an old browser request can be replayed.
UPDATE vehicle_subscription SET request_key = 'legacy-subscription-' || id;

ALTER TABLE vehicle_subscription
    ALTER COLUMN request_key SET NOT NULL,
    ALTER COLUMN payment_deadline_at SET NOT NULL,
    ALTER COLUMN status SET DEFAULT 'PENDING_PAYMENT',
    ADD CONSTRAINT vehicle_subscription_vehicle_id_fkey
        FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
    ADD CONSTRAINT vehicle_subscription_parking_lot_id_fkey
        FOREIGN KEY (parking_lot_id) REFERENCES parking_lot(id),
    ADD CONSTRAINT uq_vehicle_subscription_request_key UNIQUE (request_key),
    ADD CONSTRAINT uq_vehicle_subscription_paid_receipt UNIQUE (paid_receipt_id),
    ADD CONSTRAINT uq_vehicle_subscription_id_scope UNIQUE (id, vehicle_id, parking_lot_id),
    ADD CONSTRAINT fk_vehicle_subscription_package_scope
        FOREIGN KEY (package_id, parking_lot_id) REFERENCES subscription_package(id, parking_lot_id),
    ADD CONSTRAINT fk_vehicle_subscription_previous_scope
        FOREIGN KEY (previous_subscription_id, vehicle_id, parking_lot_id)
        REFERENCES vehicle_subscription(id, vehicle_id, parking_lot_id);

-- V47's SERIAL sequence still had the INTEGER upper bound after V50 converted
-- its column to BIGINT. Keep the sequence and values; only widen its capacity.
DO $$
DECLARE subscription_sequence REGCLASS;
BEGIN
    subscription_sequence := pg_get_serial_sequence('vehicle_subscription', 'id')::regclass;
    IF subscription_sequence IS NOT NULL THEN
        EXECUTE format('ALTER SEQUENCE %s AS BIGINT', subscription_sequence);
    END IF;
END $$;

-- Reuse V50's equivalent catalog and package-leading schedule indexes, plus
-- V47's package_id index. Do not create a duplicate index just to rename it.
CREATE INDEX idx_vehicle_subscription_vehicle_period
    ON vehicle_subscription(vehicle_id, parking_lot_id, starts_at);
CREATE INDEX idx_vehicle_subscription_previous ON vehicle_subscription(previous_subscription_id);

CREATE TABLE vehicle_subscription_receipt (
    id BIGINT GENERATED BY DEFAULT AS IDENTITY PRIMARY KEY,
    subscription_id BIGINT NOT NULL REFERENCES vehicle_subscription(id),
    payment_id BIGINT NOT NULL REFERENCES payment(id),
    source VARCHAR(50) NOT NULL,
    reference VARCHAR(100) NOT NULL,
    invoice_no VARCHAR(100) NOT NULL,
    currency VARCHAR(10) NOT NULL,
    amount_vnd BIGINT NOT NULL,
    paid_at TIMESTAMPTZ,
    received_at TIMESTAMPTZ NOT NULL,
    outcome VARCHAR(30) NOT NULL,
    reason_code VARCHAR(100),
    metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
    created_at TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_subscription_receipt_source_reference UNIQUE (source, reference),
    CONSTRAINT uq_subscription_receipt_id_parent UNIQUE (id, subscription_id)
);
-- No UNIQUE(payment_id): multiple physical bank transfers may target one intent.
CREATE INDEX idx_subscription_receipt_payment ON vehicle_subscription_receipt(payment_id);
CREATE INDEX idx_subscription_receipt_parent_received
    ON vehicle_subscription_receipt(subscription_id, received_at, id);
ALTER TABLE vehicle_subscription
    ADD CONSTRAINT fk_vehicle_subscription_paid_receipt_parent
        FOREIGN KEY (paid_receipt_id, id)
        REFERENCES vehicle_subscription_receipt(id, subscription_id);

COMMENT ON COLUMN vehicle_subscription.request_key IS
    'Idempotent order identity. legacy-subscription-* identifies retained pre-V51 rows only.';
COMMENT ON COLUMN vehicle_subscription.payment_receipt IS
    'Legacy inline data retained for audit; new verified transfers are stored in vehicle_subscription_receipt.';
COMMENT ON TABLE vehicle_subscription_receipt IS
    'Verified actual transfers; Java/JPA enforces append-only evidence and atomic period application.';
