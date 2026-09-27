ALTER TABLE camera
    ADD COLUMN IF NOT EXISTS roi_x1 double precision,
    ADD COLUMN IF NOT EXISTS roi_y1 double precision,
    ADD COLUMN IF NOT EXISTS roi_x2 double precision,
    ADD COLUMN IF NOT EXISTS roi_y2 double precision,
    ADD COLUMN IF NOT EXISTS roi_x3 double precision,
    ADD COLUMN IF NOT EXISTS roi_y3 double precision,
    ADD COLUMN IF NOT EXISTS roi_x4 double precision,
    ADD COLUMN IF NOT EXISTS roi_y4 double precision;
