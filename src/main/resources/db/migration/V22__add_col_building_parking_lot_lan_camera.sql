ALTER TABLE public.building
    ADD COLUMN IF NOT EXISTS description VARCHAR(255),
    ADD COLUMN IF NOT EXISTS status      VARCHAR(20);

