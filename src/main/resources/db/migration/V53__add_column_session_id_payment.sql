ALTER TABLE public.payment
ADD COLUMN IF NOT EXISTS session_id BIGINT,
-- lấy tt tài khoản nh để làm snapshot
ADD COLUMN IF NOT EXISTS beneficiary_account_id BIGINT,
ADD COLUMN IF NOT EXISTS bank_name VARCHAR(255),
ADD COLUMN IF NOT EXISTS account_number VARCHAR(100),
ADD COLUMN IF NOT EXISTS account_holder_name VARCHAR(255);

-- cập nhật session_id cho các bản ghi có callback_type = 'SESSION'
UPDATE public.payment
SET session_id = CAST(callback_context ->> 'sessionId' AS BIGINT)
WHERE session_id IS NULL
  AND callback_context IS NOT NULL
  AND (callback_context ->> 'sessionId') ~ '^[0-9]+$';
-- cập nhật tt tk nh cho các giao dịch đang ở method QR
UPDATE public.payment p
SET beneficiary_account_id = ba.id,
    bank_name = ba.bank_name,
    account_number = ba.account_number,
    account_holder_name = ba.account_holder_name
FROM public.parking_session ps
         JOIN public.beneficiary_account ba
              ON ba.parking_lot_id = ps.parking_lot_id
                  AND ba.status = 'ACTIVE'
WHERE p.session_id = ps.id
  AND p.payment_method = 'QR'
  AND p.bank_name IS NULL;

CREATE INDEX IF NOT EXISTS idx_payment_session_id_updated_at
    ON public.payment (session_id, updated_at DESC, id DESC)
    WHERE session_id IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_payment_beneficiary_account_id
    ON public.payment (beneficiary_account_id)
    WHERE beneficiary_account_id IS NOT NULL;
