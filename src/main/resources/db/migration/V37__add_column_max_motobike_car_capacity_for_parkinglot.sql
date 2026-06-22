ALTER TABLE public.parking_lot
    ADD COLUMN IF NOT EXISTS max_motorcycle_capacity INTEGER,
    ADD COLUMN IF NOT EXISTS max_car_capacity        INTEGER;