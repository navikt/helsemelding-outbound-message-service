ALTER TABLE messages ADD COLUMN external_delivery_state VARCHAR(100);
ALTER TABLE messages ADD COLUMN app_rec_status VARCHAR(100);

ALTER TABLE messages DROP COLUMN current_state;

DROP INDEX IF EXISTS idx_messages_state;
DROP INDEX IF EXISTS idx_messages_last_state_change;
DROP INDEX IF EXISTS idx_messages_polling_state_time;

CREATE INDEX idx_messages_delivery_state ON messages (external_delivery_state);
CREATE INDEX idx_messages_app_rec_status ON messages (app_rec_status);
CREATE INDEX idx_messages_polling_state_time ON messages (external_delivery_state, last_polled_at);
