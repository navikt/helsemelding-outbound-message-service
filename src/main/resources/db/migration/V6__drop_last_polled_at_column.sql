DROP INDEX IF EXISTS idx_messages_polling_state_time;

ALTER TABLE messages DROP COLUMN last_polled_at;
