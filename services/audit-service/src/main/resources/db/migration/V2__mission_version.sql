ALTER TABLE missions ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
CREATE INDEX assignments_subject_idx ON mission_assignments(subject, mission_id);
ALTER TABLE missions ADD COLUMN assignment_revision VARCHAR(255);
