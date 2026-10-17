ALTER TABLE notifications ADD COLUMN read_at timestamptz;
CREATE INDEX notifications_unread_recipient ON notifications(recipient_subject) WHERE read_at IS NULL;
