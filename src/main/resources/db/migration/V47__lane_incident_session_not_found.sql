ALTER TABLE lane_incident
    ADD COLUMN IF NOT EXISTS corrected_plate VARCHAR(50),
    ADD COLUMN IF NOT EXISTS attempt_count   INTEGER;

-- Mỗi làn chỉ có tối đa 1 sự cố không tìm thấy phiên đang chờ admin xử lý
CREATE UNIQUE INDEX IF NOT EXISTS uq_lane_incident_active_session_not_found
    ON lane_incident (lane_id) WHERE type = 'SESSION_NOT_FOUND' AND status IN ('PENDING', 'AWAITING_DECISION');

ALTER TABLE lane_incident_action
    ADD COLUMN IF NOT EXISTS old_value VARCHAR(255),
    ADD COLUMN IF NOT EXISTS new_value VARCHAR(255),
    ADD COLUMN IF NOT EXISTS result    VARCHAR(50);
