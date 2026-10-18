ALTER TABLE missions ADD COLUMN submitted_at timestamptz;
ALTER TABLE missions ADD COLUMN reviewed_at timestamptz;
ALTER TABLE missions ADD COLUMN reviewed_by varchar(255);
ALTER TABLE missions ADD COLUMN review_decision varchar(32);
ALTER TABLE missions ADD COLUMN review_comment varchar(2000);
ALTER TABLE missions DROP CONSTRAINT missions_status_check;
ALTER TABLE missions ADD CONSTRAINT missions_status_check CHECK (status IN ('DRAFT','IN_PROGRESS','PENDING_REVIEW','APPROVED','CLOSED'));
ALTER TABLE missions DROP CONSTRAINT missions_lifecycle_check;
ALTER TABLE missions ADD CONSTRAINT missions_lifecycle_check CHECK (
 (status='DRAFT' AND started_at IS NULL AND closed_at IS NULL) OR
 (status IN ('IN_PROGRESS','PENDING_REVIEW','APPROVED') AND planned_start_date IS NOT NULL AND started_at IS NOT NULL AND closed_at IS NULL) OR
 (status='CLOSED' AND planned_start_date IS NOT NULL AND started_at IS NOT NULL AND closed_at IS NOT NULL AND closed_at >= started_at)
);
ALTER TABLE missions ADD CONSTRAINT missions_review_check CHECK (
 (status NOT IN ('PENDING_REVIEW','APPROVED') OR (validator_subject IS NOT NULL AND submitted_at IS NOT NULL)) AND
 (status <> 'PENDING_REVIEW' OR (reviewed_at IS NULL AND reviewed_by IS NULL AND review_decision IS NULL AND review_comment IS NULL)) AND
 (status <> 'APPROVED' OR (reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL AND reviewed_by = validator_subject AND reviewed_by <> owner_subject AND review_decision IS NOT NULL AND review_decision='APPROVED' AND review_comment IS NOT NULL AND length(trim(review_comment)) > 0))
);
-- Preserve existing closures, but enforce approval for every new transition to CLOSED.
CREATE FUNCTION require_mission_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='CLOSED' AND OLD.status<>'CLOSED' AND (OLD.status<>'APPROVED' OR OLD.review_decision IS DISTINCT FROM 'APPROVED') THEN
  RAISE EXCEPTION 'Mission approval required before closure' USING ERRCODE='23514';
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER mission_approval_before_closure BEFORE UPDATE OF status ON missions FOR EACH ROW EXECUTE FUNCTION require_mission_approval();
