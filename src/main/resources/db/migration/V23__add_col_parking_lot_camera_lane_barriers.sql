ALTER TABLE public.parking_lot
    ADD COLUMN description VARCHAR(255),
    ADD COLUMN status      VARCHAR(20);

ALTER TABLE public.lane
    ADD COLUMN description VARCHAR(255);

ALTER TABLE public.camera
    ADD COLUMN description VARCHAR(255);

ALTER TABLE public.barriers
    ADD COLUMN description VARCHAR(255);