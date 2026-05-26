-- =====================================================
-- Migrate cổng thanh toán: MegaPay -> SePay (Webhook ngân hàng)
-- =====================================================
-- Mô hình webhook: backend tự sinh VietQR, SePay báo về khi có biến động
-- số dư. Match theo invoiceNo parse từ trường `content`. Mỗi giao dịch
-- có `id` BIGINT duy nhất từ SePay -> dùng cho idempotency.

ALTER TABLE payment
    ADD COLUMN IF NOT EXISTS sepay_tx_id    BIGINT,
    ADD COLUMN IF NOT EXISTS gateway        VARCHAR(50),
    ADD COLUMN IF NOT EXISTS reference_code VARCHAR(100);

CREATE UNIQUE INDEX IF NOT EXISTS uq_payment_sepay_tx_id
    ON payment (sepay_tx_id)
    WHERE sepay_tx_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_reference_code
    ON payment (reference_code);

-- paid_at đã có; thêm index cho stats query
CREATE INDEX IF NOT EXISTS idx_payment_status_paid_at
    ON payment (status, paid_at);
