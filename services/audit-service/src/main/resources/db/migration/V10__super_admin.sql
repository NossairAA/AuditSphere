ALTER TABLE mission_action_log DROP CONSTRAINT mission_action_log_action_check;
ALTER TABLE mission_action_log ADD CONSTRAINT mission_action_log_action_check CHECK (action IN ('DRAFT_DELETED','REVIEW_WITHDRAWN','ADMIN_CHANGE','ADMIN_OVERRIDE'));
ALTER TABLE mission_action_log ADD COLUMN operation varchar(32);
ALTER TABLE mission_action_log ADD COLUMN previous_status varchar(32);
ALTER TABLE mission_action_log ADD COLUMN resulting_status varchar(32);
ALTER TABLE missions ADD COLUMN deleted_at timestamptz;
ALTER TABLE missions ADD COLUMN admin_override_id uuid REFERENCES mission_action_log(id);
ALTER TABLE missions ADD COLUMN admin_approval_id uuid REFERENCES mission_action_log(id);
ALTER TABLE missions ADD COLUMN admin_override_action varchar(32);
ALTER TABLE missions ADD COLUMN admin_override_reason varchar(2000);
ALTER TABLE missions ADD COLUMN admin_override_at timestamptz;
ALTER TABLE missions DROP CONSTRAINT missions_review_check;
ALTER TABLE missions ADD CONSTRAINT missions_review_check CHECK (
 (status <> 'PENDING_REVIEW' OR (validator_subject IS NOT NULL AND submitted_at IS NOT NULL AND reviewed_at IS NULL AND reviewed_by IS NULL AND review_decision IS NULL AND review_comment IS NULL)) AND
 (status <> 'APPROVED' OR (reviewed_at IS NOT NULL AND reviewed_by IS NOT NULL AND review_decision IS NOT NULL AND review_decision='APPROVED' AND review_comment IS NOT NULL AND length(trim(review_comment)) > 0 AND
 ((validator_subject IS NOT NULL AND submitted_at IS NOT NULL AND reviewed_by=validator_subject AND reviewed_by<>owner_subject) OR (admin_approval_id IS NOT NULL))))
);
CREATE OR REPLACE FUNCTION require_mission_approval() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.status='CLOSED' AND OLD.status<>'CLOSED' AND (OLD.status<>'APPROVED' OR OLD.review_decision IS DISTINCT FROM 'APPROVED') THEN
  IF NEW.admin_override_id IS NOT DISTINCT FROM OLD.admin_override_id OR NOT EXISTS (
   SELECT 1 FROM mission_action_log WHERE id=NEW.admin_override_id AND mission_id=OLD.id AND action='ADMIN_OVERRIDE' AND operation='CLOSE' AND mission_version=OLD.version AND previous_status=OLD.status AND resulting_status='CLOSED'
  ) THEN
   RAISE EXCEPTION 'Mission approval or explicit override required' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE FUNCTION validate_admin_override() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.admin_approval_id IS DISTINCT FROM OLD.admin_approval_id AND NEW.admin_approval_id IS NOT NULL AND NOT EXISTS (
  SELECT 1 FROM mission_action_log WHERE id=NEW.admin_approval_id AND mission_id=OLD.id AND action='ADMIN_OVERRIDE' AND operation='APPROVE' AND mission_version=OLD.version AND actor_subject=NEW.reviewed_by
 ) THEN
  RAISE EXCEPTION 'Administrative approval trace required' USING ERRCODE='23514';
 END IF;
 IF NEW.admin_override_id IS DISTINCT FROM OLD.admin_override_id AND NEW.admin_override_id IS NOT NULL THEN
  IF NOT EXISTS (SELECT 1 FROM mission_action_log WHERE id=NEW.admin_override_id AND mission_id=OLD.id AND action='ADMIN_OVERRIDE' AND operation=NEW.admin_override_action AND mission_version=OLD.version AND previous_status=OLD.status AND resulting_status=CASE WHEN NEW.deleted_at IS NOT NULL THEN 'DELETED' ELSE NEW.status END) THEN
   RAISE EXCEPTION 'Administrative audit record required' USING ERRCODE='23514';
  END IF;
 END IF;
 RETURN NEW;
END;
$$;
CREATE TRIGGER mission_override_trace BEFORE UPDATE ON missions FOR EACH ROW EXECUTE FUNCTION validate_admin_override();
