ALTER TABLE parking_session
    ADD COLUMN IF NOT EXISTS entry_image_info JSONB;

ALTER TABLE parking_session
    ADD COLUMN IF NOT EXISTS exit_image_info JSONB;