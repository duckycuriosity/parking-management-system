ALTER TABLE vehicle DROP CONSTRAINT chk_vehicle_type;

UPDATE vehicle v SET vehicle_type = 'MOTORBIKE' WHERE vehicle_type != 'CAR';

UPDATE vehicle
SET info = jsonb_set(info, '{type}', '"MOTORBIKE"', false)
WHERE info->>'type' = 'MOTOBIKE';

ALTER TABLE vehicle ADD CONSTRAINT chk_vehicle_type
    CHECK (vehicle_type IN ('CAR','MOTORBIKE','OTHER'));