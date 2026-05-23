ALTER TABLE ticket ADD COLUMN IF NOT EXISTS customer_id BIGINT,
ADD CONSTRAINT fk_ticket_customer FOREIGN KEY (customer_id) REFERENCES parking_customer(id) ON DELETE SET NULL;

ALTER TABLE ticket ADD COLUMN IF NOT EXISTS ticket_type VARCHAR(20) NOT NULL,
ADD CONSTRAINT chk_ticket_type CHECK (ticket_type IN ('MONTH', 'QUARTER', 'YEAR'));