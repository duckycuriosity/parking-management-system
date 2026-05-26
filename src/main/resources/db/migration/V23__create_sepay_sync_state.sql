-- Cursor cho reconciliation job SePay (chỉ 1 dòng, id=1).
CREATE TABLE IF NOT EXISTS sepay_sync_state (
    id              BIGSERIAL PRIMARY KEY,
    last_seen_tx_id BIGINT,
    last_sync_at    TIMESTAMP,
    last_sync_count INTEGER,
    last_sync_error VARCHAR(500),
    created_at      TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Seed dòng cursor mặc định
INSERT INTO sepay_sync_state (id, last_seen_tx_id, last_sync_count)
VALUES (1, 0, 0)
ON CONFLICT (id) DO NOTHING;
