CREATE TABLE assignment_outbox (
  event_id uuid PRIMARY KEY,
  mission_id uuid NOT NULL REFERENCES missions(id),
  recipient_subject varchar(255) NOT NULL,
  aggregate_version bigint NOT NULL,
  occurred_at timestamptz NOT NULL DEFAULT now(),
  published_at timestamptz,
  available_at timestamptz NOT NULL DEFAULT now(),
  lease_until timestamptz,
  lease_token uuid,
  attempts integer NOT NULL DEFAULT 0
);
CREATE INDEX assignment_outbox_pending ON assignment_outbox(available_at, occurred_at) WHERE published_at IS NULL;
