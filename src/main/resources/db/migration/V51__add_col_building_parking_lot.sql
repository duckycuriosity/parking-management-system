ALTER TABLE public.building
    ADD COLUMN IF NOT EXISTS phone_number varchar(20),
    ADD COLUMN IF NOT EXISTS management_unit VARCHAR(255),
    ADD COLUMN IF NOT EXISTS building_code VARCHAR(50);

ALTER TABLE public.parking_lot
    ADD COLUMN IF NOT EXISTS operating_mode VARCHAR(50),
    ADD COLUMN IF NOT EXISTS parking_lot_code VARCHAR(50),
    ADD COLUMN IF NOT EXISTS address VARCHAR(100);


ALTER TABLE  public.barriers
    ADD COLUMN IF NOT EXISTS ip_barrier VARCHAR(50),
    ADD COLUMN IF NOT EXISTS reason_open_cmd VARCHAR(50),
    DROP COLUMN IF EXISTS reason,
    DROP COLUMN IF EXISTS reason_openCmd;
