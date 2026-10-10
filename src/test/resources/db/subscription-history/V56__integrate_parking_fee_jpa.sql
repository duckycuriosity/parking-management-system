-- Keep applied migrations unchanged. V52 used UUID session IDs, whereas this project's
-- parking_session key is BIGINT. Populated UUID rows require an explicit approved mapping.
DO $$
DECLARE session_type REGTYPE;
BEGIN
    SELECT atttypid::regtype INTO session_type FROM pg_attribute
    WHERE attrelid = 'fee_calculation'::regclass AND attname = 'session_id' AND NOT attisdropped;
    IF session_type = 'uuid'::regtype THEN
        IF EXISTS (SELECT 1 FROM fee_calculation) THEN
            RAISE EXCEPTION 'fee_calculation contains UUID session IDs. Map them to parking_session BIGINT IDs before V56; no automatic mapping or deletion is permitted.';
        END IF;
        ALTER TABLE fee_calculation ALTER COLUMN session_id TYPE BIGINT USING NULL::bigint;
    ELSIF session_type IS DISTINCT FROM 'bigint'::regtype THEN
        RAISE EXCEPTION 'Unexpected fee_calculation.session_id type: %', session_type;
    END IF;
END $$;

ALTER TABLE fee_calculation
    ADD COLUMN valid_until timestamptz,
    ADD CONSTRAINT fk_fee_calculation_session FOREIGN KEY (session_id) REFERENCES parking_session(id),
    ADD CONSTRAINT fk_fee_calculation_policy FOREIGN KEY (fee_policy_id) REFERENCES fee_policy(id);
COMMENT ON COLUMN fee_calculation.valid_until IS
    'Explicit operator-approved payment deadline; NULL legacy quotes are not payable. No implicit TTL.';

ALTER TABLE parking_fee_payment
    ALTER COLUMN calculation_id TYPE BIGINT USING calculation_id::bigint,
    ADD COLUMN payment_id BIGINT,
    ADD COLUMN application_status varchar(30) NOT NULL DEFAULT 'PENDING',
    ADD COLUMN application_reason varchar(100),
    ADD CONSTRAINT fk_parking_fee_payment_calculation FOREIGN KEY (calculation_id) REFERENCES fee_calculation(id),
    ADD CONSTRAINT fk_parking_fee_payment_payment FOREIGN KEY (payment_id) REFERENCES payment(id),
    ADD CONSTRAINT uq_parking_fee_payment_request_key UNIQUE (request_key),
    ADD CONSTRAINT uq_parking_fee_payment_payment UNIQUE (payment_id);
COMMENT ON COLUMN parking_fee_payment.receipt IS
    'Append-only verified transaction observations for this fee request, including additional transfers requiring review; not subscription receipts.';
COMMENT ON COLUMN parking_fee_payment.payment_id IS
    'Existing shared payment intent for this fee attempt; no separate gateway or parent-child payment model.';

-- JSON history alone cannot enforce source/reference uniqueness between different intents
-- racing on different vehicles. This registry supplies only global identity/FK protection;
-- actual money observations remain in parking_fee_payment.receipt, not in another ledger.
CREATE TABLE parking_fee_transaction_key (
    source varchar(50) NOT NULL,
    reference varchar(255) NOT NULL,
    attempt_id BIGINT NOT NULL REFERENCES parking_fee_payment(id),
    identity_fingerprint varchar(64) NOT NULL,
    created_at timestamptz NOT NULL,
    PRIMARY KEY (source, reference)
);
CREATE INDEX idx_parking_fee_transaction_attempt ON parking_fee_transaction_key(attempt_id);
COMMENT ON TABLE parking_fee_transaction_key IS
    'Minimal global fee-transaction identity registry. Prevents the same verified source/reference settling distinct intents concurrently; financial evidence lives in the existing receipt JSON.';
