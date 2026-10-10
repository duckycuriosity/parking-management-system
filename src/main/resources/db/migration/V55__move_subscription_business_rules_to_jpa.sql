-- V54 remains immutable. Its business rules now belong to the Java/JPA
-- package/order/receipt services and their entity lifecycle guards.
-- Structural primary/foreign/unique/not-null constraints remain in place.
-- Direct SQL bypasses the Java business layer after this migration.

DROP TRIGGER IF EXISTS trg_subscription_package_contract ON subscription_package;
DROP TRIGGER IF EXISTS trg_subscription_schedule_immutable ON subscription_package_schedule;
DROP TRIGGER IF EXISTS trg_vehicle_subscription_contract ON vehicle_subscription;
DROP TRIGGER IF EXISTS trg_subscription_receipt_immutable ON vehicle_subscription_receipt;
DROP TRIGGER IF EXISTS trg_subscription_receipt_applied_at_commit ON vehicle_subscription_receipt;

DROP FUNCTION IF EXISTS enforce_subscription_package_contract();
DROP FUNCTION IF EXISTS enforce_subscription_schedule_immutability();
DROP FUNCTION IF EXISTS enforce_vehicle_subscription_contract();
DROP FUNCTION IF EXISTS enforce_subscription_receipt_immutability();
DROP FUNCTION IF EXISTS ensure_applied_subscription_receipt_grants_period();

ALTER TABLE subscription_package
    DROP CONSTRAINT IF EXISTS ex_subscription_package_published_overlap,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_code,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_name,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_version,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_vehicle,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_price,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_duration,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_coverage,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_status,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_effective;

ALTER TABLE subscription_package_schedule
    DROP CONSTRAINT IF EXISTS ck_subscription_package_schedule_weekday,
    DROP CONSTRAINT IF EXISTS ck_subscription_package_schedule_clock;

ALTER TABLE vehicle_subscription
    DROP CONSTRAINT IF EXISTS ex_vehicle_subscription_paid_overlap,
    DROP CONSTRAINT IF EXISTS ck_vehicle_subscription_request,
    DROP CONSTRAINT IF EXISTS ck_vehicle_subscription_price,
    DROP CONSTRAINT IF EXISTS ck_vehicle_subscription_period,
    DROP CONSTRAINT IF EXISTS ck_vehicle_subscription_status,
    DROP CONSTRAINT IF EXISTS ck_vehicle_subscription_registration,
    DROP CONSTRAINT IF EXISTS ck_vehicle_subscription_paid;

ALTER TABLE vehicle_subscription_receipt
    DROP CONSTRAINT IF EXISTS ck_subscription_receipt_identifiers,
    DROP CONSTRAINT IF EXISTS ck_subscription_receipt_amount,
    DROP CONSTRAINT IF EXISTS ck_subscription_receipt_metadata,
    DROP CONSTRAINT IF EXISTS ck_subscription_receipt_outcome;

-- Callback lookup is by invoice, so it must identify one existing intent.
-- Do not rewrite or discard historical duplicates: an existing duplicate causes
-- this migration to fail, and must be reconciled before the operator retries it.
ALTER TABLE payment ADD CONSTRAINT uq_payment_invoice_no UNIQUE (invoice_no);

COMMENT ON TABLE vehicle_subscription_receipt IS
    'Verified actual transfers; Java/JPA enforces append-only data and atomic period application.';
