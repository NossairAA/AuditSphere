CREATE TABLE user_admin_log (
 id UUID PRIMARY KEY,
 actor_subject VARCHAR(255) NOT NULL,
 target_subject VARCHAR(255) NOT NULL,
 action VARCHAR(40) NOT NULL,
 reason VARCHAR(2000) NOT NULL,
 outcome VARCHAR(30) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 completed_at TIMESTAMPTZ
);
CREATE INDEX user_admin_log_target ON user_admin_log(target_subject, created_at DESC);
CREATE UNIQUE INDEX user_admin_pending ON user_admin_log(target_subject) WHERE outcome = 'PENDING';
CREATE TABLE account_revocations (
 subject VARCHAR(255) PRIMARY KEY,
 revoked_before BIGINT NOT NULL
);
CREATE INDEX missions_status_end ON missions(status, planned_end_date) WHERE deleted_at IS NULL;
