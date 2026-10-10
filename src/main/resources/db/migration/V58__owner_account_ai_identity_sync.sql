-- V14 uses BIGINT. Keep existing values and the external profileId contract.
ALTER TABLE owner_account
    ADD COLUMN ai_profile_id bigint,
    ADD COLUMN ai_identity_id varchar(100),
    ADD COLUMN driver_customer_id bigint,
    ADD COLUMN ai_sync_state varchar(40) NOT NULL DEFAULT 'NOT_SYNCED',
    ADD COLUMN ai_sync_token varchar(36),
    ADD COLUMN ai_sync_started_at timestamptz,
    ADD COLUMN ai_sync_profile_id bigint,
    ADD COLUMN row_version bigint NOT NULL DEFAULT 0;

ALTER TABLE parking_customer
    ADD COLUMN ai_sync_state varchar(40) NOT NULL DEFAULT 'NOT_SYNCED',
    ADD COLUMN ai_sync_token varchar(36),
    ADD COLUMN ai_sync_started_at timestamptz,
    ADD COLUMN ai_sync_profile_id bigint,
    ADD COLUMN row_version bigint NOT NULL DEFAULT 0;

-- A locally saved person may not yet have a successfully synchronized face.
ALTER TABLE parking_customer ALTER COLUMN ai_profile_id DROP NOT NULL;
ALTER TABLE parking_customer ALTER COLUMN ai_identity_id DROP NOT NULL;

UPDATE parking_customer SET ai_sync_state = 'SYNCED' WHERE ai_profile_id IS NOT NULL;

ALTER TABLE owner_account
    ADD CONSTRAINT fk_owner_account_driver_customer FOREIGN KEY (driver_customer_id)
        REFERENCES parking_customer(id),
    ADD CONSTRAINT uq_owner_account_driver_customer UNIQUE (driver_customer_id),
    ADD CONSTRAINT ck_owner_account_ai_sync_state CHECK
        (ai_sync_state IN ('NOT_SYNCED', 'IN_PROGRESS', 'SYNCED', 'RECONCILIATION_REQUIRED'));

ALTER TABLE parking_customer
    ADD CONSTRAINT ck_parking_customer_ai_sync_state CHECK
        (ai_sync_state IN ('NOT_SYNCED', 'IN_PROGRESS', 'SYNCED', 'RECONCILIATION_REQUIRED'));

-- findByAiProfileId returns one person. Do not silently merge historical data.
DO $$
BEGIN
    IF EXISTS (
        SELECT ai_profile_id FROM parking_customer
        WHERE ai_profile_id IS NOT NULL
        GROUP BY ai_profile_id HAVING COUNT(*) > 1
    ) THEN
        RAISE EXCEPTION 'V58: duplicate parking_customer.ai_profile_id values exist; reconcile the people and their vehicle/session references before rerunning migration. No data was automatically deduplicated.';
    END IF;
END $$;

CREATE UNIQUE INDEX uq_parking_customer_ai_profile_id ON parking_customer(ai_profile_id)
    WHERE ai_profile_id IS NOT NULL;

CREATE UNIQUE INDEX uq_owner_account_ai_profile_id ON owner_account(ai_profile_id)
    WHERE ai_profile_id IS NOT NULL;

COMMENT ON COLUMN owner_account.ai_profile_id IS 'Backend-confirmed Core AI profileId; not the PMS person primary key';
COMMENT ON COLUMN owner_account.driver_customer_id IS 'Explicit owner-as-driver person link; service verifies matching owner_id';
COMMENT ON COLUMN owner_account.ai_sync_profile_id IS 'Known provider ID retained for safe retry when a response became stale';
