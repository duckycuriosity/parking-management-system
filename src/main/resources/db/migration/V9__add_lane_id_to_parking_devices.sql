-- V9__add_lane_id_to_parking_devices.sql
ALTER TABLE parking_devices
  ADD COLUMN IF NOT EXISTS lane_id bigint;

CREATE INDEX IF NOT EXISTS idx_parking_devices_lane_id ON parking_devices(lane_id);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1 FROM pg_constraint WHERE conname = 'fk_parking_device_lane'
  ) THEN
    ALTER TABLE parking_devices
      ADD CONSTRAINT fk_parking_device_lane
      FOREIGN KEY (lane_id) REFERENCES lane(id);
  END IF;
END$$;