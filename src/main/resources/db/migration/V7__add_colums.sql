-- ========================================== --
-- 1. XỬ LÝ BẢNG DEVICE -> VEHICLE           --
-- ========================================== --
ALTER TABLE device RENAME TO vehicle;

-- Thêm cột (V2 quên cái này, thêm ở đây cho chắc)
ALTER TABLE vehicle
    ADD COLUMN IF NOT EXISTS ai_identity_plate_id VARCHAR(100);

-- Sửa độ dài cột plate_number
ALTER TABLE vehicle
ALTER COLUMN plate_number TYPE VARCHAR(50);


-- ========================================== --
-- 2. XỬ LÝ BẢNG PARKING_CUSTOMER             --
-- ========================================== --
-- Thêm cột username. LUÔN thêm DEFAULT để tránh lỗi NOT NULL với data cũ
ALTER TABLE parking_customer
    ADD COLUMN IF NOT EXISTS username VARCHAR(100) NOT NULL DEFAULT 'unknown_user';


-- ========================================== --
-- 3. XỬ LÝ BẢNG USER_DEVICE_PERMISSION       --
-- ========================================== --
-- Đổi tên bảng
ALTER TABLE user_device_permission RENAME TO vehicle_customer_permission;

-- Đổi tên cột
ALTER TABLE vehicle_customer_permission
    RENAME COLUMN device_id TO vehicle_id;

-- Xóa cột thừa
ALTER TABLE vehicle_customer_permission DROP COLUMN IF EXISTS assigned_at;

-- Thêm cột created_at nếu V1 chưa có
ALTER TABLE vehicle_customer_permission
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP;

-- Đổi tên constraints
ALTER TABLE vehicle_customer_permission
    RENAME CONSTRAINT fk_udp_customer TO fk_vcp_customer;

ALTER TABLE vehicle_customer_permission
    RENAME CONSTRAINT fk_udp_device TO fk_vcp_vehicle;

ALTER TABLE vehicle_customer_permission
    RENAME CONSTRAINT unique_user_device TO unique_customer_vehicle;


-- ========================================== --
-- 4. XỬ LÝ BẢNG PARKING_SESSION              --
-- ========================================== --
-- Xóa bảng cũ của V1 để tạo lại bản hoàn chỉnh hơn (Chấp nhận mất data lịch sử)
DROP TABLE IF EXISTS parking_session;

-- Tạo lại bảng parking_session với cấu trúc mới nhất
CREATE TABLE parking_session (
                                 id BIGSERIAL PRIMARY KEY,
                                 parking_lot_id BIGINT NOT NULL,
                                 vehicle_id BIGINT NOT NULL,
                                 entry_time TIMESTAMP NOT NULL,
                                 exit_time TIMESTAMP,
                                 entry_lane_id BIGINT,
                                 exit_lane_id BIGINT,
                                 entry_customer_id BIGINT NOT NULL DEFAULT 1, -- Thêm DEFAULT để tránh lỗi NOT NULL, sau này có thể xóa DEFAULT đi
                                 exit_customer_id BIGINT,
                                 created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                 CONSTRAINT fk_session_parking_lot
                                     FOREIGN KEY (parking_lot_id) REFERENCES parking_lot(id),
                                 CONSTRAINT fk_session_vehicle
                                     FOREIGN KEY (vehicle_id) REFERENCES vehicle(id),
                                 CONSTRAINT fk_session_entry_lane
                                     FOREIGN KEY (entry_lane_id) REFERENCES lane(id),
                                 CONSTRAINT fk_session_exit_lane
                                     FOREIGN KEY (exit_lane_id) REFERENCES lane(id),
                                 CONSTRAINT fk_parking_session_entry_customer
                                     FOREIGN KEY (entry_customer_id) REFERENCES parking_customer(id),
                                 CONSTRAINT fk_parking_session_exit_customer
                                     FOREIGN KEY (exit_customer_id) REFERENCES parking_customer(id)
);


-- ========================================== --
-- 5. TẠO CÁC BẢNG MỚI                        --
-- ========================================== --

CREATE TABLE organize (
                          id BIGSERIAL PRIMARY KEY, -- Nên dùng BIGSERIAL cho đồng nhất
                          name VARCHAR(255) NOT NULL,
                          branch_code VARCHAR(255) NOT NULL,
                          address VARCHAR(255) NOT NULL,
                          status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE branch (
                        id BIGSERIAL PRIMARY KEY,
                        organize_id BIGINT NOT NULL,
                        branch_code VARCHAR(255) NOT NULL,
                        address VARCHAR(255) NOT NULL,
                        status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE')),
                        created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                        CONSTRAINT fk_branch_organize FOREIGN KEY (organize_id) REFERENCES organize(id),
                        CONSTRAINT uk_branch_organize_code UNIQUE (organize_id, branch_code),
                        CONSTRAINT uk_branch_id_organize UNIQUE (id, organize_id)
);

CREATE TABLE sys_user (
                          id BIGSERIAL PRIMARY KEY,
                          organize_id BIGINT NOT NULL,
                          branch_id BIGINT,
                          username VARCHAR(255) NOT NULL UNIQUE,
                          password_hash VARCHAR(255) NOT NULL,
                          email VARCHAR(255) NOT NULL UNIQUE,
                          status VARCHAR(20) NOT NULL CHECK (status IN ('ACTIVE', 'INACTIVE', 'LOCKED')),
                          created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                          CONSTRAINT fk_users_organize FOREIGN KEY (organize_id) REFERENCES organize(id),
                          CONSTRAINT fk_users_branch FOREIGN KEY (branch_id) REFERENCES branch(id),
                          CONSTRAINT fk_users_branch_organize FOREIGN KEY (branch_id, organize_id) REFERENCES branch(id, organize_id)
);

CREATE TABLE role (
                      id BIGSERIAL PRIMARY KEY,
                      organize_id BIGINT NOT NULL,
                      role_name VARCHAR(255) NOT NULL,
                      created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      updated_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                      CONSTRAINT fk_role_organize FOREIGN KEY (organize_id) REFERENCES organize(id),
                      CONSTRAINT uk_role_organize_name UNIQUE (organize_id, role_name)
);

CREATE TABLE permission (
                            id BIGSERIAL PRIMARY KEY,
                            permission_name VARCHAR(255) NOT NULL UNIQUE,
                            resource VARCHAR(255) NOT NULL,
                            description TEXT,
                            created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE role_permission (
                                 id BIGSERIAL PRIMARY KEY,
                                 role_id BIGINT NOT NULL,
                                 permission_id BIGINT NOT NULL,
                                 created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
                                 CONSTRAINT fk_role_permission_role FOREIGN KEY (role_id) REFERENCES role(id),
                                 CONSTRAINT fk_role_permission_permission FOREIGN KEY (permission_id) REFERENCES permission(id),
                                 CONSTRAINT uk_role_permission UNIQUE (role_id, permission_id)
);

CREATE TABLE payment (
                         id BIGSERIAL PRIMARY KEY,
                         invoice_no VARCHAR(255),
                         amount NUMERIC(15,2),
                         currency VARCHAR(10),
                         payment_method VARCHAR(50),
                         paid_at TIMESTAMP,
                         status VARCHAR(50),
                         parking_customer_id BIGINT,
                         sys_user_id BIGINT,
                         created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                         CONSTRAINT fk_payment_customer
                             FOREIGN KEY (parking_customer_id) REFERENCES parking_customer(id),
                         CONSTRAINT fk_payment_sys_user
                             FOREIGN KEY (sys_user_id) REFERENCES sys_user(id)
    -- Lưu ý: Bảng sys_user tạo bên dưới, nếu Postgres báo lỗi tham chiếu chưa tồn tại, hãy move khối CREATE sys_user lên trên khối CREATE payment này.
);

-- Tạo bảng ticket đã loại bỏ các cột thừa ngay từ đầu
CREATE TABLE ticket (
                        id BIGSERIAL PRIMARY KEY,
                        vehicle_id BIGINT,
                        register_at TIMESTAMP NOT NULL,
                        expire_at TIMESTAMP NOT NULL,
                        status VARCHAR(50),
                        created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                        CONSTRAINT fk_ticket_vehicle
                            FOREIGN KEY (vehicle_id) REFERENCES vehicle(id)
);

CREATE TABLE ticket_payment (
                                id BIGSERIAL PRIMARY KEY,
                                ticket_id BIGINT NOT NULL,
                                payment_id BIGINT NOT NULL,
                                created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,

                                CONSTRAINT fk_ticket
                                    FOREIGN KEY (ticket_id) REFERENCES ticket(id),
                                CONSTRAINT fk_payment_tp
                                    FOREIGN KEY (payment_id) REFERENCES payment(id)
);

CREATE TABLE parking_rule (
                              id BIGSERIAL PRIMARY KEY,
                              name VARCHAR(255),
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);

CREATE TABLE pricing_rule (
                              id BIGSERIAL PRIMARY KEY,
                              name VARCHAR(255),
                              price NUMERIC(15,2),
                              currency VARCHAR(10),
                              created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP
);