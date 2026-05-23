ALTER TABLE payment ADD COLUMN IF NOT EXISTS callback_type VARCHAR(50);

-- 3. Thêm callback_context: hướng dẫn thao tác khi payment hoàn thành
--    SESSION example: {"sessionId": 123, "barrierId": "barrier_001", "plateNumber": "51A-12345"}
--    TICKET  example: {"ticketId": 456, "customerId": 789}
ALTER TABLE payment ADD COLUMN IF NOT EXISTS callback_context JSONB;

-- =====================================================
-- 4. Bảng session_payment: liên kết session ↔ payment
--    Đảm bảo ràng buộc quan hệ & query dễ hơn so với
--    chỉ dùng callback_context jsonb
-- =====================================================
CREATE TABLE IF NOT EXISTS session_payment (
                                               id          BIGSERIAL PRIMARY KEY,
                                               session_id  BIGINT NOT NULL REFERENCES parking_session(id),
    payment_id  BIGINT NOT NULL REFERENCES payment(id),
    created_at  TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT uq_session_payment UNIQUE (session_id, payment_id)
    );

CREATE INDEX IF NOT EXISTS idx_session_payment_session_id ON session_payment(session_id);
CREATE INDEX IF NOT EXISTS idx_session_payment_payment_id ON session_payment(payment_id);