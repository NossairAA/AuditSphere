ALTER TABLE mission_controls ADD COLUMN result_data jsonb;
ALTER TABLE program_change_log DROP CONSTRAINT program_change_log_resource_check;
ALTER TABLE program_change_log ADD CONSTRAINT program_change_log_resource_check CHECK (resource IN ('RISK','CONTROL','RESULT'));
