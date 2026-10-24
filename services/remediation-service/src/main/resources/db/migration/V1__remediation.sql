-- Projection of an approved mission, refreshed from audit-service at each issue cycle.
CREATE TABLE mission_scopes (
  mission_id uuid PRIMARY KEY,
  title varchar(200) NOT NULL,
  owner_subject varchar(255) NOT NULL,
  validator_subject varchar(255) NOT NULL,
  issue_cycle integer NOT NULL CHECK (issue_cycle > 0),
  state varchar(16) NOT NULL CHECK (state IN ('ACTIVE','SUSPENDED','WITHDRAWN')),
  synced_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE mission_scope_auditors (
  mission_id uuid NOT NULL REFERENCES mission_scopes(mission_id) ON DELETE CASCADE,
  subject varchar(255) NOT NULL,
  PRIMARY KEY (mission_id, subject)
);

-- The id is the finding id in audit-service. Texts are a copy taken at issue time.
CREATE TABLE finding_follow_ups (
  id uuid PRIMARY KEY,
  mission_id uuid NOT NULL REFERENCES mission_scopes(mission_id),
  reference integer NOT NULL CHECK (reference > 0),
  finding_version bigint NOT NULL,
  title varchar(200) NOT NULL,
  severity varchar(16) NOT NULL CHECK (severity IN ('LOW','MODERATE','HIGH','CRITICAL')),
  condition_text varchar(4000) NOT NULL,
  cause varchar(4000) NOT NULL,
  recommendation varchar(4000) NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('TO_PLAN','ACTION_PLAN','VERIFICATION','CLOSED','SUSPENDED','WITHDRAWN')),
  status_before_suspension varchar(16) CHECK (status_before_suspension IN ('TO_PLAN','ACTION_PLAN','VERIFICATION','CLOSED')),
  version bigint NOT NULL DEFAULT 0,
  closure_requested_by varchar(255),
  closure_requested_at timestamptz,
  closed_by varchar(255),
  closed_at timestamptz,
  return_comment varchar(2000),
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (mission_id, reference),
  UNIQUE (mission_id, id),
  CHECK ((status = 'SUSPENDED') = (status_before_suspension IS NOT NULL)),
  -- NULL in a CHECK passes, so required values are tested with IS NOT NULL.
  CHECK (status <> 'VERIFICATION' OR (closure_requested_by IS NOT NULL AND closure_requested_at IS NOT NULL)),
  CHECK (status <> 'CLOSED' OR (closed_by IS NOT NULL AND closed_at IS NOT NULL AND closure_requested_by IS NOT NULL AND closed_by <> closure_requested_by))
);

-- Last reference given per follow-up, so a number is never reused.
CREATE TABLE action_sequences (
  follow_up_id uuid PRIMARY KEY REFERENCES finding_follow_ups(id),
  last_reference integer NOT NULL CHECK (last_reference > 0)
);

CREATE TABLE corrective_actions (
  id uuid PRIMARY KEY,
  follow_up_id uuid NOT NULL,
  mission_id uuid NOT NULL,
  reference integer NOT NULL CHECK (reference > 0),
  description varchar(2000) NOT NULL CHECK (length(btrim(description)) > 0),
  owner_subject varchar(255) NOT NULL,
  due_date date NOT NULL,
  status varchar(16) NOT NULL CHECK (status IN ('TODO','IN_PROGRESS','DONE','VERIFIED','CANCELLED')),
  version bigint NOT NULL DEFAULT 0,
  created_by varchar(255) NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now(),
  updated_by varchar(255) NOT NULL,
  updated_at timestamptz NOT NULL DEFAULT now(),
  completion_note varchar(2000),
  completed_at timestamptz,
  verified_by varchar(255),
  verified_at timestamptz,
  verification_comment varchar(2000),
  cancel_reason varchar(2000),
  due_date_changes integer NOT NULL DEFAULT 0 CHECK (due_date_changes >= 0),
  FOREIGN KEY (mission_id, follow_up_id) REFERENCES finding_follow_ups(mission_id, id),
  UNIQUE (follow_up_id, reference),
  CHECK (status NOT IN ('DONE','VERIFIED') OR (completion_note IS NOT NULL AND length(btrim(completion_note)) > 0 AND completed_at IS NOT NULL)),
  CHECK (status <> 'VERIFIED' OR (verified_by IS NOT NULL AND verified_at IS NOT NULL)),
  CHECK (verified_by IS NULL OR verified_by <> owner_subject),
  CHECK (status <> 'CANCELLED' OR (cancel_reason IS NOT NULL AND length(btrim(cancel_reason)) > 0))
);
CREATE INDEX corrective_actions_owner ON corrective_actions(owner_subject, due_date);
CREATE INDEX corrective_actions_follow_up ON corrective_actions(follow_up_id, reference);

-- Append only for remediation_runtime, like the change log.
CREATE TABLE action_progress_notes (
  id uuid PRIMARY KEY,
  action_id uuid NOT NULL REFERENCES corrective_actions(id),
  author_subject varchar(255) NOT NULL,
  note varchar(2000) NOT NULL CHECK (length(btrim(note)) > 0),
  created_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX action_progress_notes_action ON action_progress_notes(action_id, created_at);

CREATE TABLE action_attachments (
  id uuid PRIMARY KEY,
  action_id uuid NOT NULL REFERENCES corrective_actions(id),
  mission_id uuid NOT NULL REFERENCES mission_scopes(mission_id),
  object_key varchar(512) NOT NULL UNIQUE,
  file_name varchar(255) NOT NULL,
  content_type varchar(32) NOT NULL CHECK (content_type IN ('application/pdf','image/jpeg','image/png')),
  size_bytes bigint NOT NULL CHECK (size_bytes BETWEEN 1 AND 10485760),
  sha256 char(64) NOT NULL CHECK (sha256 ~ '^[0-9a-f]{64}$'),
  uploaded_by varchar(255) NOT NULL,
  uploaded_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX action_attachments_action ON action_attachments(action_id);

-- No foreign key: the trace outlives any removed attachment.
CREATE TABLE remediation_change_log (
  id uuid PRIMARY KEY,
  mission_id uuid NOT NULL,
  follow_up_id uuid,
  action_id uuid,
  actor_subject varchar(255) NOT NULL,
  action varchar(24) NOT NULL CHECK (action IN (
    'ISSUED','SUSPENDED','WITHDRAWN',
    'CREATED','UPDATED','REASSIGNED','DUE_DATE_CHANGED','PROGRESS','ATTACHMENT_ADDED','ATTACHMENT_REMOVED',
    'COMPLETED','VERIFIED','RETURNED','CANCELLED','REOPENED',
    'CLOSURE_REQUESTED','CLOSED','CLOSURE_RETURNED')),
  entity_version bigint NOT NULL,
  reason varchar(2000),
  override boolean NOT NULL DEFAULT false,
  previous_data jsonb,
  resulting_data jsonb,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  CHECK (action NOT IN ('DUE_DATE_CHANGED','RETURNED','CANCELLED','REOPENED','CLOSURE_RETURNED') OR (reason IS NOT NULL AND length(btrim(reason)) > 0))
);
CREATE INDEX remediation_change_log_follow_up ON remediation_change_log(follow_up_id, occurred_at);
CREATE INDEX remediation_change_log_action ON remediation_change_log(action_id, occurred_at);

CREATE TABLE processed_events (
  event_id uuid PRIMARY KEY,
  event_type varchar(64) NOT NULL,
  processed_at timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE remediation_outbox (
  event_id uuid PRIMARY KEY,
  event_type varchar(64) NOT NULL CHECK (event_type IN ('audit.action.assigned.v1')),
  action_id uuid NOT NULL REFERENCES corrective_actions(id),
  follow_up_id uuid NOT NULL,
  mission_id uuid NOT NULL,
  recipient_subject varchar(255) NOT NULL,
  aggregate_version bigint NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  lease_token uuid,
  attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX remediation_outbox_pending ON remediation_outbox(available_at, occurred_at) WHERE published_at IS NULL;
