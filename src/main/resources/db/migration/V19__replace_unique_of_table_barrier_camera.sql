-- Bước 1: Tìm tên của constraint hiện tại
SELECT constraint_name 
FROM information_schema.table_constraints 
WHERE table_name = 'barrier_camera' 
  AND constraint_type = 'UNIQUE';

-- Giả sử tên constraint là barrier_camera_barrier_id_camera_id_key
-- Bước 2: Drop constraint cũ
ALTER TABLE barrier_camera 
DROP CONSTRAINT barrier_camera_barrier_id_camera_id_key;

-- Bước 3: Tạo constraint mới với 3 trường
ALTER TABLE barrier_camera 
ADD CONSTRAINT barrier_camera_barrier_id_camera_id_role_key 
UNIQUE (barrier_id, camera_id, role);