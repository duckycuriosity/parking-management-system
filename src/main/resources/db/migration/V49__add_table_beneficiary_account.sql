CREATE TABLE IF NOT EXISTS beneficiary_account (
id BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
bank_code VARCHAR(50) NOT NULL,
bank_name VARCHAR(255) NOT NULL,
account_number VARCHAR(100) NOT NULL,
account_holder_name VARCHAR(255) NOT NULL,
status VARCHAR(30) NOT NULL,
note TEXT,

building_id BIGINT,
parking_lot_id BIGINT,

created_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,
updated_at TIMESTAMP DEFAULT CURRENT_TIMESTAMP,


CONSTRAINT fk_beneficiary_building FOREIGN KEY (building_id) REFERENCES building(id),
CONSTRAINT fk_beneficiary_parking_lot FOREIGN KEY (parking_lot_id) REFERENCES parking_lot(id)
);

CREATE INDEX IF NOT EXISTS idx_beneficiary_location ON beneficiary_account(building_id, parking_lot_id);