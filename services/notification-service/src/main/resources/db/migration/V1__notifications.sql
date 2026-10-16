CREATE TABLE notifications (
  event_id uuid PRIMARY KEY,
  mission_id uuid NOT NULL,
  recipient_subject varchar(255) NOT NULL,
  aggregate_version bigint NOT NULL,
  occurred_at timestamptz NOT NULL,
  received_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX notifications_recipient ON notifications(recipient_subject, received_at DESC, event_id DESC);
