CREATE TABLE IF NOT EXISTS messages
(
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    message_type          VARCHAR(100)             NOT NULL,
    external_reference_id UUID                     NOT NULL UNIQUE,
    external_message_url  TEXT                     NOT NULL UNIQUE,
    current_state         VARCHAR(100)             NOT NULL,
    last_state_change     TIMESTAMPTZ              NOT NULL,
    last_polled_at        TIMESTAMPTZ              NULL,
    created_at            TIMESTAMPTZ              NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ              NOT NULL DEFAULT now()
);

CREATE INDEX idx_messages_state ON messages (current_state);
CREATE INDEX idx_messages_last_state_change ON messages (last_state_change);
CREATE INDEX idx_messages_polling_state_time ON messages (current_state, last_polled_at);