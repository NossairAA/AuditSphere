ALTER TABLE missions ADD COLUMN planned_start_date date;
ALTER TABLE missions ADD COLUMN planned_end_date date;
ALTER TABLE missions ADD COLUMN started_at timestamptz;
ALTER TABLE missions ADD COLUMN closed_at timestamptz;
ALTER TABLE missions DROP CONSTRAINT missions_status_check;
ALTER TABLE missions ADD CONSTRAINT missions_status_check CHECK (status IN ('DRAFT','IN_PROGRESS','CLOSED'));
ALTER TABLE missions ADD CONSTRAINT missions_dates_check CHECK (
  (planned_start_date IS NULL AND planned_end_date IS NULL) OR
  (planned_start_date IS NOT NULL AND planned_end_date IS NOT NULL AND planned_end_date >= planned_start_date)
);
ALTER TABLE missions ADD CONSTRAINT missions_lifecycle_check CHECK (
  (status='DRAFT' AND started_at IS NULL AND closed_at IS NULL) OR
  (status='IN_PROGRESS' AND planned_start_date IS NOT NULL AND started_at IS NOT NULL AND closed_at IS NULL) OR
  (status='CLOSED' AND planned_start_date IS NOT NULL AND started_at IS NOT NULL AND closed_at IS NOT NULL AND closed_at >= started_at)
);
