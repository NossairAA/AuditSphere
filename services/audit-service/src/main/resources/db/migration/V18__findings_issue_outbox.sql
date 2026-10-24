-- Incremented each time the mission enters APPROVED or CLOSED from another status.
-- Not mapped by JPA: only FindingsIssueOutbox writes it, inside the mission transaction.
-- Missions approved before this migration keep 0 and are not issued retroactively.
ALTER TABLE missions ADD COLUMN issue_cycle integer NOT NULL DEFAULT 0 CHECK (issue_cycle >= 0);

CREATE TABLE findings_issue_outbox (
  event_id uuid PRIMARY KEY,
  event_type varchar(80) NOT NULL CHECK (event_type IN ('audit.mission.findings-issued.v1','audit.mission.findings-withdrawn.v1')),
  mission_id uuid NOT NULL REFERENCES missions(id),
  issue_cycle integer NOT NULL CHECK (issue_cycle > 0),
  reason varchar(16) CHECK (reason IN ('REOPENED','DELETED')),
  aggregate_version bigint NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  lease_token uuid,
  attempts integer NOT NULL DEFAULT 0,
  CHECK ((event_type = 'audit.mission.findings-withdrawn.v1') = (reason IS NOT NULL))
);
CREATE INDEX findings_issue_outbox_pending ON findings_issue_outbox(available_at, occurred_at) WHERE published_at IS NULL;
