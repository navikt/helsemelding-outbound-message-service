ALTER TABLE messages
    ADD COLUMN transport VARCHAR(100) NOT NULL DEFAULT 'EDI_2.0',
    ADD COLUMN published_at TIMESTAMPTZ;

ALTER TABLE messages ADD CONSTRAINT messages_transport_check
    CHECK (transport IN ('EDI_1.0', 'EDI_2.0'));
