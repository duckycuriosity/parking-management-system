ALTER TABLE device
    ADD COLUMN IF NOT EXISTS ai_identity_plate_id VARCHAR(100);

ALTER TABLE parking_customer
    ADD COLUMN IF NOT EXISTS ai_identity_id VARCHAR(100)
