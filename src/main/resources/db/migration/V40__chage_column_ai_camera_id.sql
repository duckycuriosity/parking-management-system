DO
$$
    BEGIN
        IF EXISTS (SELECT 1
                   FROM information_schema.columns
                   WHERE table_schema = 'public'
                     AND table_name = 'camera'
                     AND column_name = 'ai_camera_id'
                     AND data_type = 'bigint') THEN
            ALTER TABLE public.camera
                ALTER COLUMN ai_camera_id TYPE VARCHAR(255) USING ai_camera_id::VARCHAR;
        END IF;
    END
$$;