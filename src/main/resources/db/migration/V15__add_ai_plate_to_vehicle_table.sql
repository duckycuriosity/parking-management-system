ALTER TABLE vehicle
ADD COLUMN IF NOT EXISTS vehicle_type VARCHAR(20) NOT NULL,
ADD CONSTRAINT chk_vehicle_type CHECK (vehicle_type IN ('CAR', 'MOTOBIKE', 'OTHER'));