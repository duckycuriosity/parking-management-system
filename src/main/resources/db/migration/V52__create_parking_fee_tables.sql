CREATE TABLE IF NOT EXISTS fee_policy (
    id bigserial primary key,
    policy_code varchar(50) NOT NULL,
    parking_lot_id uuid NOT NULL,
    vehicle_type varchar(20) NOT NULL,
    name varchar(255) NOT NULL,
    version_no integer NOT NULL,
    effective_from timestamptz NOT NULL,
    effective_to timestamptz,
    calculation_method varchar(30) NOT NULL,
    daily_price_vnd bigint,
    remainder_threshold_minutes integer,
    pricing_rules jsonb NOT NULL DEFAULT '{}'::jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS  idx_fee_policy_code_version ON fee_policy(policy_code, version_no);
CREATE INDEX IF NOT EXISTS  idx_fee_policy_lot_vehicle_version ON fee_policy(parking_lot_id, vehicle_type, version_no);
CREATE INDEX IF NOT EXISTS  idx_fee_policy_lot_vehicle_effective_from ON fee_policy(parking_lot_id, vehicle_type, effective_from);

CREATE TABLE IF NOT EXISTS  fee_time_band (
    id bigserial primary key,
    fee_policy_id integer NOT NULL,
    name varchar(100) NOT NULL,
    sort_order integer NOT NULL DEFAULT 0,
    start_time time NOT NULL,
    end_time time NOT NULL,
    first_block_minutes integer NOT NULL,
    first_block_price_vnd bigint NOT NULL,
    next_block_minutes integer NOT NULL,
    next_block_price_vnd bigint NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS  idx_fee_time_band_policy_id ON fee_time_band(fee_policy_id);

CREATE TABLE IF NOT EXISTS  fee_calculation (
    id bigserial primary key,
    session_id uuid NOT NULL,
    fee_policy_id integer,
    charge_context varchar(30) NOT NULL,
    calculated_at timestamptz NOT NULL,
    calculation_until timestamptz NOT NULL,
    engine_revision varchar(50) NOT NULL,
    input_fingerprint varchar(64) NOT NULL,
    subtotal_vnd bigint NOT NULL,
    rounding_adjustment_vnd bigint NOT NULL,
    total_vnd bigint NOT NULL,
    fee_detail jsonb NOT NULL,
    status varchar(20) NOT NULL DEFAULT 'QUOTED',
    settled_at timestamptz,
    created_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS  idx_fee_calc_session_created ON fee_calculation(session_id, created_at);
CREATE INDEX IF NOT EXISTS  idx_fee_calc_policy_id ON fee_calculation(fee_policy_id);

CREATE TABLE IF NOT EXISTS  parking_fee_payment (
    id bigserial primary key,
    calculation_id integer NOT NULL,
    request_key varchar(100) NOT NULL,
    amount_vnd bigint NOT NULL,
    currency varchar(3) NOT NULL DEFAULT 'VND',
    status varchar(20) NOT NULL DEFAULT 'PENDING',
    payment_source varchar(50),
    payment_reference varchar(255),
    requested_at timestamptz NOT NULL,
    confirmed_at timestamptz,
    receipt jsonb,
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS  idx_fee_payment_calculation_id ON parking_fee_payment(calculation_id);
CREATE INDEX IF NOT EXISTS  idx_fee_payment_source_ref ON parking_fee_payment(payment_source, payment_reference);
