ALTER TABLE ticket
DROP CONSTRAINT IF EXISTS chk_ticket_type;
ALTER TABLE ticket ADD CONSTRAINT chk_ticket_type CHECK (ticket_type IN ('DAY', 'MONTH', 'QUARTER'));

ALTER TABLE parking_session DROP CONSTRAINT IF EXISTS chk_session_type;

ALTER TABLE parking_session ADD CONSTRAINT chk_session_type CHECK (session_type IN ('DAY', 'MONTH', 'QUARTER'));