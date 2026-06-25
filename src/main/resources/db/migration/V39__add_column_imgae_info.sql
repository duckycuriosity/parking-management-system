ALTER TABLE parking_session
    ADD COLUMN IF NOT EXISTS image_info JSONB;