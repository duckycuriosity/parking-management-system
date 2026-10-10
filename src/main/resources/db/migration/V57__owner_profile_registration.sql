CREATE TABLE IF NOT EXISTS owner_profile_registration (
    request_key VARCHAR(100) PRIMARY KEY,
    request_hash VARCHAR(64) NOT NULL,
    owner_id BIGINT REFERENCES owner_account(id) ON DELETE RESTRICT,
    result_ids JSONB,
    row_version BIGINT NOT NULL DEFAULT 0
);
CREATE INDEX IF NOT EXISTS idx_owner_profile_registration_owner ON owner_profile_registration(owner_id);

-- Preserve all existing rows; orphan/duplicate relations require explicit reconciliation.
DO $$
BEGIN
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_profile_vehicle_owner' AND conrelid = 'vehicle'::regclass) THEN
        ALTER TABLE vehicle ADD CONSTRAINT fk_profile_vehicle_owner FOREIGN KEY (owner_id) REFERENCES owner_account(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_profile_customer_owner' AND conrelid = 'parking_customer'::regclass) THEN
        ALTER TABLE parking_customer ADD CONSTRAINT fk_profile_customer_owner FOREIGN KEY (owner_id) REFERENCES owner_account(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_profile_relation_vehicle' AND conrelid = 'vehicle_customer'::regclass) THEN
        ALTER TABLE vehicle_customer ADD CONSTRAINT fk_profile_relation_vehicle FOREIGN KEY (vehicle_id) REFERENCES vehicle(id);
    END IF;
    IF NOT EXISTS (SELECT 1 FROM pg_constraint WHERE conname = 'fk_profile_relation_customer' AND conrelid = 'vehicle_customer'::regclass) THEN
        ALTER TABLE vehicle_customer ADD CONSTRAINT fk_profile_relation_customer FOREIGN KEY (customer_id) REFERENCES parking_customer(id);
    END IF;
END $$;
CREATE UNIQUE INDEX IF NOT EXISTS uq_profile_vehicle_customer ON vehicle_customer(vehicle_id, customer_id);
