ALTER TABLE message_state_history DROP COLUMN old_state;
ALTER TABLE message_state_history DROP COLUMN new_state;

ALTER TABLE message_state_history ADD COLUMN old_delivery_state VARCHAR(100);
ALTER TABLE message_state_history ADD COLUMN new_delivery_state VARCHAR(100);
ALTER TABLE message_state_history ADD COLUMN old_app_rec_status VARCHAR(100);
ALTER TABLE message_state_history ADD COLUMN new_app_rec_status VARCHAR(100);