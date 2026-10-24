CREATE TABLE mission_findings (
 id uuid PRIMARY KEY,
 mission_id uuid NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
 reference integer NOT NULL CHECK (reference > 0),
 title varchar(200) NOT NULL CHECK (length(trim(title)) > 0),
 condition_text varchar(4000) NOT NULL CHECK (length(trim(condition_text)) > 0),
 criterion varchar(4000),
 cause varchar(4000),
 consequence varchar(4000),
 recommendation varchar(4000),
 severity varchar(16) NOT NULL CHECK (severity IN ('LOW','MODERATE','HIGH','CRITICAL')),
 status varchar(16) NOT NULL CHECK (status IN ('DRAFT','SUBMITTED','VALIDATED','REJECTED')),
 version bigint NOT NULL DEFAULT 0,
 ever_submitted boolean NOT NULL DEFAULT false,
 created_by varchar(255) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 updated_by varchar(255) NOT NULL,
 updated_at timestamptz NOT NULL DEFAULT now(),
 submitted_by varchar(255),
 submitted_at timestamptz,
 decided_by varchar(255),
 decided_at timestamptz,
 decision_comment varchar(2000),
 UNIQUE(mission_id,reference),
 UNIQUE(mission_id,id),
 CHECK (status = 'DRAFT' OR (length(trim(cause)) > 0 AND length(trim(recommendation)) > 0)),
 CHECK (status = 'DRAFT' OR (ever_submitted AND submitted_by IS NOT NULL)),
 CHECK (status NOT IN ('VALIDATED','REJECTED') OR decided_by IS NOT NULL),
 CHECK (status <> 'REJECTED' OR length(trim(decision_comment)) > 0)
);
CREATE TABLE mission_finding_sequences (
 mission_id uuid PRIMARY KEY REFERENCES missions(id) ON DELETE CASCADE,
 last_reference integer NOT NULL CHECK (last_reference > 0)
);
CREATE TABLE mission_finding_controls (
 mission_id uuid NOT NULL,
 finding_id uuid NOT NULL,
 control_id uuid NOT NULL,
 PRIMARY KEY(finding_id,control_id),
 FOREIGN KEY(mission_id,finding_id) REFERENCES mission_findings(mission_id,id) ON DELETE CASCADE,
 FOREIGN KEY(mission_id,control_id) REFERENCES mission_controls(mission_id,id)
);
CREATE INDEX mission_finding_controls_control ON mission_finding_controls(control_id);
CREATE TABLE finding_change_log (
 id uuid PRIMARY KEY,
 mission_id uuid NOT NULL,
 finding_id uuid NOT NULL,
 actor_subject varchar(255) NOT NULL,
 action varchar(16) NOT NULL CHECK (action IN ('CREATED','UPDATED','SUBMITTED','WITHDRAWN','VALIDATED','RETURNED','REJECTED','REOPENED','DELETED')),
 finding_version bigint NOT NULL,
 reason varchar(2000),
 override boolean NOT NULL DEFAULT false,
 previous_data jsonb,
 resulting_data jsonb,
 occurred_at timestamptz NOT NULL DEFAULT now(),
 CHECK (action NOT IN ('RETURNED','REJECTED','REOPENED') OR length(trim(reason)) > 0)
);
CREATE INDEX finding_change_log_finding ON finding_change_log(finding_id,occurred_at);
