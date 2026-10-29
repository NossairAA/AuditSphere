-- A mission approved or closed by a SUPER_ADMIN override has no designated validator in audit-service.
ALTER TABLE mission_scopes ALTER COLUMN validator_subject DROP NOT NULL;
