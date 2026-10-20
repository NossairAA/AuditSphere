ALTER TABLE missions ADD COLUMN program_revision uuid;
CREATE TABLE mission_risks (
 id uuid PRIMARY KEY,
 mission_id uuid NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
 title varchar(200) NOT NULL,
 description varchar(4000) NOT NULL,
 probability integer NOT NULL CHECK (probability BETWEEN 1 AND 5),
 impact integer NOT NULL CHECK (impact BETWEEN 1 AND 5),
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(mission_id,id)
);
CREATE TABLE mission_controls (
 id uuid PRIMARY KEY,
 mission_id uuid NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
 objective varchar(1000) NOT NULL,
 procedure varchar(4000) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 UNIQUE(mission_id,id)
);
CREATE TABLE mission_control_risks (
 mission_id uuid NOT NULL,
 control_id uuid NOT NULL,
 risk_id uuid NOT NULL,
 PRIMARY KEY(control_id,risk_id),
 FOREIGN KEY(mission_id,control_id) REFERENCES mission_controls(mission_id,id) ON DELETE CASCADE,
 FOREIGN KEY(mission_id,risk_id) REFERENCES mission_risks(mission_id,id)
);
CREATE TABLE program_change_log (
 id uuid PRIMARY KEY,
 mission_id uuid NOT NULL,
 target_id uuid NOT NULL,
 actor_subject varchar(255) NOT NULL,
 resource varchar(16) NOT NULL CHECK (resource IN ('RISK','CONTROL')),
 mission_version bigint NOT NULL,
 previous_data jsonb,
 resulting_data jsonb NOT NULL,
 occurred_at timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX program_change_log_mission ON program_change_log(mission_id,occurred_at);
