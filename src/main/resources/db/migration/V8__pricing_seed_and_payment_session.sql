-- ========================================== --
-- 1. PRICING RULE: thêm type + giá mặc định  --
-- ========================================== --
ALTER TABLE pricing_rule
    ADD COLUMN IF NOT EXISTS type VARCHAR(20);

ALTER TABLE pricing_rule
    ADD CONSTRAINT uk_pricing_rule_type UNIQUE (type);

INSERT INTO pricing_rule (type, name, price, currency, created_at)
VALUES ('DAY',   'Daily Rate',   5000,   'VND', CURRENT_TIMESTAMP),
       ('MONTH', 'Monthly Rate', 150000, 'VND', CURRENT_TIMESTAMP)
ON CONFLICT (type) DO NOTHING;
