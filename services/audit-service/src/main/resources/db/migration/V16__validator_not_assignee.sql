-- Stop migration until legacy conflicts are reviewed and corrected explicitly.
LOCK TABLE missions, mission_assignments IN SHARE ROW EXCLUSIVE MODE;
DO $$
BEGIN
  IF EXISTS (
    SELECT 1 FROM missions m JOIN mission_assignments a ON a.mission_id = m.id
    WHERE a.subject = m.validator_subject
  ) THEN
    RAISE EXCEPTION 'Existing missions have a validator assigned as auditor. Resolve these assignments before V16.';
  END IF;
END $$;

CREATE FUNCTION reject_validator_assignment() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE chosen_validator varchar(255);
BEGIN
  -- Serialize assignment changes with validator changes for the same mission.
  SELECT validator_subject INTO chosen_validator FROM missions WHERE id = NEW.mission_id FOR UPDATE;
  IF NEW.subject = chosen_validator THEN
    RAISE EXCEPTION 'Mission validator cannot be an assigned auditor' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER mission_assignment_separation
  BEFORE INSERT OR UPDATE OF mission_id, subject ON mission_assignments
  FOR EACH ROW EXECUTE FUNCTION reject_validator_assignment();

CREATE FUNCTION reject_assigned_validator() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
  IF NEW.validator_subject IS NOT NULL AND EXISTS (
    SELECT 1 FROM mission_assignments
    WHERE mission_id = NEW.id AND subject = NEW.validator_subject
  ) THEN
    RAISE EXCEPTION 'Mission validator cannot be an assigned auditor' USING ERRCODE = '23514';
  END IF;
  RETURN NEW;
END $$;

CREATE TRIGGER mission_validator_separation
  BEFORE INSERT OR UPDATE OF validator_subject ON missions
  FOR EACH ROW EXECUTE FUNCTION reject_assigned_validator();
