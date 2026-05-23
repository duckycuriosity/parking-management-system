CREATE TABLE IF NOT EXISTS barrier_camera (
  id BIGSERIAL PRIMARY KEY,
  barrier_id VARCHAR(100) NOT NULL,
  camera_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL, -- 'face'|'plate'|'both'
  priority INT DEFAULT 0,
  created_at TIMESTAMP WITHOUT TIME ZONE DEFAULT now(),
  UNIQUE (barrier_id, camera_id)
);
ALTER TABLE barrier_camera
  ADD CONSTRAINT fk_barrier_camera_barrier
  FOREIGN KEY (barrier_id) REFERENCES barriers(barrier_id) ON DELETE CASCADE;

-- optional index for fast lookup by camera
CREATE INDEX idx_barrier_camera_camera_id ON barrier_camera(camera_id);