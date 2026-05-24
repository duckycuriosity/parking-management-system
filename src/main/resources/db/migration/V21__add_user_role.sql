-- ==========================================
-- 1. Thêm các cột mới vào bảng sys_user
-- ==========================================
ALTER TABLE sys_user
    ADD COLUMN IF NOT EXISTS keycloak_user_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS phone            VARCHAR(20),
    ADD COLUMN IF NOT EXISTS fullname         VARCHAR(255),
    ADD COLUMN IF NOT EXISTS created_by       VARCHAR(100),
    ADD COLUMN IF NOT EXISTS updated_by       VARCHAR(100);

-- Cho phép password_hash NULL (user đăng nhập qua Keycloak có thể không cần password local)
ALTER TABLE sys_user
    ALTER COLUMN password_hash DROP NOT NULL;

-- Unique constraint cho keycloak_user_id (chỉ thêm nếu chưa có)
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint WHERE conname = 'sys_user_keycloak_user_id_key'
    ) THEN
ALTER TABLE sys_user
    ADD CONSTRAINT sys_user_keycloak_user_id_key UNIQUE (keycloak_user_id);
END IF;
END$$;

-- ==========================================
-- 2. Tạo bảng sys_user_role
-- ==========================================
CREATE TABLE IF NOT EXISTS sys_user_role
(
    id         BIGSERIAL PRIMARY KEY,
    user_id    BIGINT    NOT NULL,
    role_id    BIGINT    NOT NULL,
    created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT fk_sys_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_sys_user_role_role FOREIGN KEY (role_id) REFERENCES role (id),
    CONSTRAINT uk_sys_user_role UNIQUE (user_id, role_id)
    );

CREATE INDEX IF NOT EXISTS idx_sys_user_role_user_id ON sys_user_role (user_id);
CREATE INDEX IF NOT EXISTS idx_sys_user_role_role_id ON sys_user_role (role_id);
