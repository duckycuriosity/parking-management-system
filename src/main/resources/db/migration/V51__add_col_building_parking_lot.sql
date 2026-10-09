ALTER TABLE public.building
    ADD COLUMN IF NOT EXISTS phone_number varchar(20),
    ADD COLUMN IF NOT EXISTS management_unit VARCHAR(255),
    ADD COLUMN IF NOT EXISTS building_code VARCHAR(50);

ALTER TABLE public.parking_lot
    ADD COLUMN IF NOT EXISTS operating_mode VARCHAR(50),
    ADD COLUMN IF NOT EXISTS parking_lot_code VARCHAR(50);