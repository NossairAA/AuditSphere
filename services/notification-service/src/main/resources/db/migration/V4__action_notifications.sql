-- Corrective action assigned by remediation-service (F-006 lot 6). The notification opens the action.
ALTER TABLE notifications ADD COLUMN action_id uuid;
ALTER TABLE notifications DROP CONSTRAINT notifications_type_check;
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (event_type IN ('audit.mission.assigned.v1','audit.mission.review-requested.v1','audit.action.assigned.v1'));
-- NULL in a CHECK passes, so the action is tested with IS NOT NULL.
ALTER TABLE notifications ADD CONSTRAINT notifications_action_check CHECK ((event_type = 'audit.action.assigned.v1') = (action_id IS NOT NULL));
