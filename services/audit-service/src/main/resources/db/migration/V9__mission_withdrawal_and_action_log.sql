ALTER TABLE missions ADD COLUMN withdrawn_at timestamptz;
ALTER TABLE missions ADD COLUMN withdrawal_reason varchar(2000);
CREATE TABLE mission_action_log (
 id uuid PRIMARY KEY,
 mission_id uuid NOT NULL,
 actor_subject varchar(255) NOT NULL,
 action varchar(32) NOT NULL CHECK (action IN ('DRAFT_DELETED','REVIEW_WITHDRAWN')),
 reason varchar(2000) NOT NULL CHECK (length(trim(reason)) > 0),
 mission_version bigint NOT NULL,
 review_cycle_id uuid,
 occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX mission_action_log_mission ON mission_action_log(mission_id, occurred_at);
ALTER TABLE review_workflow_outbox DROP CONSTRAINT review_workflow_outbox_check;
ALTER TABLE review_workflow_outbox ADD CONSTRAINT review_workflow_outbox_check CHECK (
 (event_type='START' AND decision IS NULL) OR
 (event_type='DECISION' AND decision IS NOT NULL AND decision IN ('APPROVED','REJECTED','WITHDRAWN'))
);
