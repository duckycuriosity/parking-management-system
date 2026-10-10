-- V52 is kept unchanged for installations that already applied it.
-- parking_lot.id is BIGINT in this repository; a populated UUID tariff scope
-- requires an explicit business mapping before this migration can proceed.
CREATE EXTENSION IF NOT EXISTS btree_gist;

DO $$
DECLARE
    scope_type REGTYPE;
    policy_sequence REGCLASS;
BEGIN
    SELECT atttypid::regtype INTO scope_type
    FROM pg_attribute
    WHERE attrelid = 'fee_policy'::regclass
      AND attname = 'parking_lot_id' AND NOT attisdropped;

    IF scope_type = 'uuid'::regtype THEN
        IF EXISTS (SELECT 1 FROM fee_policy) THEN
            RAISE EXCEPTION 'fee_policy.parking_lot_id contains UUID data. Map these scopes to existing parking_lot BIGINT IDs before applying V53; automatic conversion is not possible.';
        END IF;
        ALTER TABLE fee_policy ALTER COLUMN parking_lot_id TYPE BIGINT USING NULL::bigint;
    ELSIF scope_type IS DISTINCT FROM 'bigint'::regtype THEN
        RAISE EXCEPTION 'Unexpected fee_policy.parking_lot_id type: %', scope_type;
    END IF;

    IF EXISTS (SELECT 1 FROM fee_policy WHERE id NOT BETWEEN 1 AND 2147483647) THEN
        RAISE EXCEPTION 'fee_policy IDs exceed the positive INTEGER range; reconcile IDs before applying V53.';
    END IF;
    ALTER TABLE fee_policy ALTER COLUMN id TYPE INTEGER USING id::integer;
    policy_sequence := pg_get_serial_sequence('fee_policy', 'id')::regclass;
    IF policy_sequence IS NULL THEN
        RAISE EXCEPTION 'fee_policy.id must retain its auto-generated sequence from V52.';
    END IF;
    EXECUTE format('ALTER SEQUENCE %s AS INTEGER', policy_sequence);
END $$;

-- A name is a display label. Identity is the new integer row ID; versions are
-- allocated separately in each parking_lot_id + vehicle_type scope.
ALTER TABLE fee_policy DROP COLUMN policy_code;

ALTER TABLE fee_policy
    ADD CONSTRAINT fk_fee_policy_parking_lot
        FOREIGN KEY (parking_lot_id) REFERENCES parking_lot(id),
    ADD CONSTRAINT uq_fee_policy_scope_version
        UNIQUE (parking_lot_id, vehicle_type, version_no),
    ADD CONSTRAINT ck_fee_policy_vehicle_type CHECK (vehicle_type IN ('CAR', 'MOTORBIKE')),
    ADD CONSTRAINT ck_fee_policy_name CHECK (length(btrim(name)) > 0),
    ADD CONSTRAINT ck_fee_policy_version CHECK (version_no > 0),
    ADD CONSTRAINT ck_fee_policy_effective_range
        CHECK (effective_to IS NULL OR effective_to > effective_from),
    ADD CONSTRAINT ck_fee_policy_calculation_method
        CHECK (calculation_method IN ('TIME_BLOCK', 'DAILY_ENTRY')),
    ADD CONSTRAINT ck_fee_policy_daily_price CHECK (daily_price_vnd IS NULL OR daily_price_vnd >= 0),
    ADD CONSTRAINT ck_fee_policy_remainder_threshold
        CHECK (remainder_threshold_minutes IS NULL OR remainder_threshold_minutes >= 0),
    ADD CONSTRAINT ck_fee_policy_pricing_rules_object CHECK (jsonb_typeof(pricing_rules) = 'object'),
    ADD CONSTRAINT ck_fee_policy_method_config CHECK (
        (calculation_method = 'DAILY_ENTRY'
            AND daily_price_vnd IS NOT NULL
            AND remainder_threshold_minutes IS NULL)
        OR (calculation_method = 'TIME_BLOCK'
            AND daily_price_vnd IS NULL
            AND remainder_threshold_minutes IS NOT NULL)
    ),
    ADD CONSTRAINT ex_fee_policy_effective_overlap EXCLUDE USING gist (
        parking_lot_id WITH =,
        vehicle_type WITH =,
        tstzrange(effective_from, effective_to, '[)') WITH &&
    );

ALTER TABLE fee_time_band
    ADD CONSTRAINT fk_fee_time_band_policy FOREIGN KEY (fee_policy_id) REFERENCES fee_policy(id),
    ADD CONSTRAINT ck_fee_time_band_name CHECK (length(btrim(name)) > 0),
    ADD CONSTRAINT ck_fee_time_band_sort_order CHECK (sort_order >= 0),
    ADD CONSTRAINT ck_fee_time_band_first_minutes CHECK (first_block_minutes > 0),
    ADD CONSTRAINT ck_fee_time_band_next_minutes CHECK (next_block_minutes > 0),
    ADD CONSTRAINT ck_fee_time_band_first_price CHECK (first_block_price_vnd >= 0),
    ADD CONSTRAINT ck_fee_time_band_next_price CHECK (next_block_price_vnd >= 0);

COMMENT ON COLUMN fee_policy.pricing_rules IS
    'Reserved for future rule configuration; new published versions currently store {}.';
COMMENT ON COLUMN fee_time_band.end_time IS
    'Greater than start: same day; less than start: following day; equal: 24-hour window.';
