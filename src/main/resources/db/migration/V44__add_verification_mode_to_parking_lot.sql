ALTER TABLE public.parking_lot
    ADD COLUMN IF NOT EXISTS verification_mode VARCHAR(20) NOT NULL DEFAULT 'FACE_PLATE';

ALTER TABLE public.parking_lot
    ADD CONSTRAINT chk_parking_lot_verification_mode
    CHECK (verification_mode IN ('FACE_PLATE','PLATE_ONLY','PLATE_RFID'));
