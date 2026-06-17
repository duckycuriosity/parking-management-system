-- Extend address column length
ALTER TABLE branch ALTER COLUMN address TYPE VARCHAR(500);

-- Remove old CHECK constraint on status (not in new schema)
ALTER TABLE branch DROP CONSTRAINT IF EXISTS branch_status_check;

-- Add new columns
ALTER TABLE branch
    ADD COLUMN IF NOT EXISTS description TEXT,
    ADD COLUMN IF NOT EXISTS province VARCHAR(255),
    ADD COLUMN IF NOT EXISTS manager_name VARCHAR(100),
    ADD COLUMN IF NOT EXISTS branch_name VARCHAR(255),
    ADD COLUMN IF NOT EXISTS latitude NUMERIC,
    ADD COLUMN IF NOT EXISTS longitude NUMERIC,
    ADD COLUMN IF NOT EXISTS created_by VARCHAR(100),
    ADD COLUMN IF NOT EXISTS updated_by VARCHAR(100);

-- Add PostGIS geography column for spatial queries
ALTER TABLE branch
    ADD COLUMN IF NOT EXISTS location geography(Point, 4326);
