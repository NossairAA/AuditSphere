ALTER TABLE user_admin_log ADD COLUMN requested_changes JSONB NOT NULL DEFAULT '{}'::jsonb;
