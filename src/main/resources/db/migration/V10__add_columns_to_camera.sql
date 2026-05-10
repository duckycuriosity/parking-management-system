ALTER TABLE camera
    ADD COLUMN camera_uuid VARCHAR(255),
    ADD COLUMN name        VARCHAR(255),
    ADD COLUMN url         TEXT,
    ADD COLUMN type        VARCHAR(50),
    ADD COLUMN latitude    DOUBLE PRECISION,
    ADD COLUMN longitude   DOUBLE PRECISION;