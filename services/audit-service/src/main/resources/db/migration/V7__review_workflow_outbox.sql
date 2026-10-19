ALTER TABLE missions ADD COLUMN review_cycle_id uuid;
CREATE TABLE review_workflow_outbox (
 event_id uuid PRIMARY KEY,
 cycle_id uuid NOT NULL,
 mission_id uuid NOT NULL REFERENCES missions(id),
 event_type varchar(16) NOT NULL CHECK (event_type IN ('START','DECISION')),
 decision varchar(16),
 occurred_at timestamptz NOT NULL DEFAULT now(),
 published_at timestamptz,
 available_at timestamptz NOT NULL DEFAULT now(),
 lease_until timestamptz,
 lease_token uuid,
 attempts integer NOT NULL DEFAULT 0,
 UNIQUE(cycle_id,event_type),
 CHECK ((event_type='START' AND decision IS NULL) OR (event_type='DECISION' AND decision IS NOT NULL AND decision IN ('APPROVED','REJECTED')))
);
CREATE INDEX review_workflow_pending ON review_workflow_outbox(available_at,occurred_at) WHERE published_at IS NULL;
-- Adopt reviews already awaiting a decision. Do not invent workflows for past approvals.
UPDATE missions SET review_cycle_id=gen_random_uuid() WHERE status='PENDING_REVIEW';
INSERT INTO review_workflow_outbox(event_id,cycle_id,mission_id,event_type)
 SELECT gen_random_uuid(),review_cycle_id,id,'START' FROM missions WHERE status='PENDING_REVIEW';
