-- =====================================================
-- Seed Data for Testing: Building → ParkingLot → Lane → ParkingDevices → Barrier → Camera → BarrierCamera
-- =====================================================

-- =====================================================
-- 1. INSERT BUILDING
-- =====================================================
INSERT INTO building (id, name, address, city, phone, email, created_at, updated_at)
VALUES (1, 'Tòa nhà A1', '123 Nguyễn Hữu Cảnh, Quy Nhơn', 'Quy Nhơn', '0292123456', 'building@test.com', NOW(), NOW());

-- =====================================================
-- 2. INSERT PARKING_LOT (1 building → many parking_lots)
-- =====================================================
INSERT INTO parking_lot (id, building_id, name, description, total_slots, available_slots, created_at, updated_at)
VALUES 
    (1, 1, 'Parking Lot 1 - Tầng 1', 'Bãi giữ xe tầng 1, khu A', 50, 50, NOW(), NOW()),
    (2, 1, 'Parking Lot 2 - Tầng 2', 'Bãi giữ xe tầng 2, khu B', 40, 40, NOW(), NOW());

-- =====================================================
-- 3. INSERT LANE (1 parking_lot → many lanes)
-- =====================================================
INSERT INTO lane (id, parking_lot_id, name, description, created_at, updated_at)
VALUES 
    (1, 1, 'Lane 1A - Entry', 'Làn vào, khu A tầng 1', NOW(), NOW()),
    (2, 1, 'Lane 1B - Exit', 'Làn ra, khu A tầng 1', NOW(), NOW()),
    (3, 2, 'Lane 2A - Entry', 'Làn vào, khu B tầng 2', NOW(), NOW()),
    (4, 2, 'Lane 2B - Exit', 'Làn ra, khu B tầng 2', NOW(), NOW());

-- =====================================================
-- 4. INSERT PARKING_DEVICE (1 lane → 1 parking_device)
-- =====================================================
INSERT INTO parking_device (id, lane_id, device_id, status, firmware_version, has_cash_reader, has_display, 
                            free_mem_mb, cpu_temp_c, uptime_seconds, last_seen, created_at, updated_at)
VALUES 
    (1, 1, 'PD_ENTRY_1A', 'Online', '1.0.2', true, true, 256, 45.5, 864000, NOW(), NOW(), NOW()),
    (2, 2, 'PD_EXIT_1B', 'Online', '1.0.2', true, true, 512, 42.3, 864000, NOW(), NOW(), NOW()),
    (3, 3, 'PD_ENTRY_2A', 'Online', '1.0.2', true, true, 256, 44.1, 864000, NOW(), NOW(), NOW()),
    (4, 4, 'PD_EXIT_2B', 'Online', '1.0.2', true, true, 512, 41.8, 864000, NOW(), NOW(), NOW());

-- =====================================================
-- 5. INSERT BARRIER (1 parking_device → many barriers)
-- =====================================================
INSERT INTO barrier (id, parking_device_id, barrier_id, direction, description, modbus_addr, created_at, updated_at)
VALUES 
    -- Lane 1A (Entry) - Barriers
    (1, 1, 'BARRIER_ENTRY_1A_FACE', 'ENTRY', 'Chắn cổng vào làn 1A - Face Recognition', 1, NOW(), NOW()),
    (2, 1, 'BARRIER_ENTRY_1A_PLATE', 'ENTRY', 'Chắn cổng vào làn 1A - Plate Recognition', 2, NOW(), NOW()),
    
    -- Lane 1B (Exit) - Barriers
    (3, 2, 'BARRIER_EXIT_1B_FACE', 'EXIT', 'Chắn cổng ra làn 1B - Face Recognition', 1, NOW(), NOW()),
    (4, 2, 'BARRIER_EXIT_1B_PLATE', 'EXIT', 'Chắn cổng ra làn 1B - Plate Recognition', 2, NOW(), NOW()),
    
    -- Lane 2A (Entry) - Barriers
    (5, 3, 'BARRIER_ENTRY_2A_FACE', 'ENTRY', 'Chắn cổng vào làn 2A - Face Recognition', 1, NOW(), NOW()),
    (6, 3, 'BARRIER_ENTRY_2A_PLATE', 'ENTRY', 'Chắn cổng vào làn 2A - Plate Recognition', 2, NOW(), NOW()),
    
    -- Lane 2B (Exit) - Barriers
    (7, 4, 'BARRIER_EXIT_2B_FACE', 'EXIT', 'Chắn cổng ra làn 2B - Face Recognition', 1, NOW(), NOW()),
    (8, 4, 'BARRIER_EXIT_2B_PLATE', 'EXIT', 'Chắn cổng ra làn 2B - Plate Recognition', 2, NOW(), NOW());

-- =====================================================
-- 6. INSERT CAMERA
-- =====================================================
INSERT INTO camera (id, lane_id, name, camera_uuid, ip_address, port, status, location_description, created_at, updated_at)
VALUES 
    -- Lane 1A (Entry)
    (1, 1, 'Camera Face Entry 1A', 'uuid-face-entry-1a-001', '192.168.1.101', 8080, 'Active', 'Face detection camera - Entry Lane 1A', NOW(), NOW()),
    (2, 1, 'Camera Plate Entry 1A', 'uuid-plate-entry-1a-001', '192.168.1.102', 8080, 'Active', 'Plate detection camera - Entry Lane 1A', NOW(), NOW()),
    
    -- Lane 1B (Exit)
    (3, 2, 'Camera Face Exit 1B', 'uuid-face-exit-1b-001', '192.168.1.103', 8080, 'Active', 'Face detection camera - Exit Lane 1B', NOW(), NOW()),
    (4, 2, 'Camera Plate Exit 1B', 'uuid-plate-exit-1b-001', '192.168.1.104', 8080, 'Active', 'Plate detection camera - Exit Lane 1B', NOW(), NOW()),
    
    -- Lane 2A (Entry)
    (5, 3, 'Camera Face Entry 2A', 'uuid-face-entry-2a-001', '192.168.1.105', 8080, 'Active', 'Face detection camera - Entry Lane 2A', NOW(), NOW()),
    (6, 3, 'Camera Plate Entry 2A', 'uuid-plate-entry-2a-001', '192.168.1.106', 8080, 'Active', 'Plate detection camera - Entry Lane 2A', NOW(), NOW()),
    
    -- Lane 2B (Exit)
    (7, 4, 'Camera Face Exit 2B', 'uuid-face-exit-2b-001', '192.168.1.107', 8080, 'Active', 'Face detection camera - Exit Lane 2B', NOW(), NOW()),
    (8, 4, 'Camera Plate Exit 2B', 'uuid-plate-exit-2b-001', '192.168.1.108', 8080, 'Active', 'Plate detection camera - Exit Lane 2B', NOW(), NOW());

-- =====================================================
-- 7. INSERT BARRIER_CAMERA (Mapping barriers to cameras with roles)
-- Each barrier is mapped to either FACE or PLATE camera
-- Note: A camera can be mapped to multiple barriers with different roles if needed
-- =====================================================
INSERT INTO barrier_camera (barrier_id, camera_id, role, priority, created_at, updated_at)
VALUES 
    -- Lane 1A (Entry)
    ('BARRIER_ENTRY_1A_FACE', 1, 'face', 1, NOW(), NOW()),           -- Face barrier → Face camera
    ('BARRIER_ENTRY_1A_PLATE', 2, 'plate', 1, NOW(), NOW()),         -- Plate barrier → Plate camera
    
    -- Lane 1B (Exit)
    ('BARRIER_EXIT_1B_FACE', 3, 'face', 1, NOW(), NOW()),            -- Face barrier → Face camera
    ('BARRIER_EXIT_1B_PLATE', 4, 'plate', 1, NOW(), NOW()),          -- Plate barrier → Plate camera
    
    -- Lane 2A (Entry)
    ('BARRIER_ENTRY_2A_FACE', 5, 'face', 1, NOW(), NOW()),           -- Face barrier → Face camera
    ('BARRIER_ENTRY_2A_PLATE', 6, 'plate', 1, NOW(), NOW()),         -- Plate barrier → Plate camera
    
    -- Lane 2B (Exit)
    ('BARRIER_EXIT_2B_FACE', 7, 'face', 1, NOW(), NOW()),            -- Face barrier → Face camera
    ('BARRIER_EXIT_2B_PLATE', 8, 'plate', 1, NOW(), NOW());          -- Plate barrier → Plate camera

