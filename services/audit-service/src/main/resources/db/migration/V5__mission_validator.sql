ALTER TABLE missions ADD COLUMN validator_subject varchar(255);
ALTER TABLE missions ADD CONSTRAINT missions_distinct_validator CHECK (validator_subject IS NULL OR validator_subject <> owner_subject);
CREATE INDEX missions_validator_idx ON missions (validator_subject) WHERE validator_subject IS NOT NULL;
