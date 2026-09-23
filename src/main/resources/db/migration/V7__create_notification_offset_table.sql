CREATE TABLE notification_offset
(
    her_id                INTEGER PRIMARY KEY,
    last_processed_offset BIGINT      NOT NULL CHECK (last_processed_offset >= 0),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
