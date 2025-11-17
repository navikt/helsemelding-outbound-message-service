CREATE TABLE IF NOT EXISTS message_state_history
(
    id         UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_id UUID                     NOT NULL REFERENCES messages (external_reference_id),
    old_state  VARCHAR(100),
    new_state  VARCHAR(100)             NOT NULL,
    changed_at TIMESTAMPTZ              NOT NULL
);

CREATE INDEX idx_message_state_history_message_id ON message_state_history (message_id);
