ALTER TABLE public.parking_lot
    ADD COLUMN if NOT EXISTS description VARCHAR(255),
    ADD COLUMN if NOT EXISTS  status      VARCHAR(20);

ALTER TABLE public.lane
    ADD COLUMN if NOT EXISTS  description VARCHAR(255);

ALTER TABLE public.camera
    ADD COLUMN if NOT EXISTS  description VARCHAR(255);

ALTER TABLE public.barriers
    ADD COLUMN if NOT EXISTS  description VARCHAR(255);