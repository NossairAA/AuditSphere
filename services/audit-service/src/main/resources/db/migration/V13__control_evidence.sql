CREATE TABLE control_evidence (
 id uuid PRIMARY KEY,
 mission_id uuid NOT NULL,
 control_id uuid NOT NULL,
 object_key varchar(100) NOT NULL UNIQUE,
 original_name varchar(255) NOT NULL,
 media_type varchar(32) NOT NULL CHECK (media_type IN ('application/pdf','image/jpeg','image/png')),
 byte_size bigint NOT NULL CHECK (byte_size BETWEEN 1 AND 10485760),
 sha256 char(64) NOT NULL,
 author_subject varchar(255) NOT NULL,
 author_name varchar(255) NOT NULL,
 created_at timestamptz NOT NULL DEFAULT now(),
 FOREIGN KEY (mission_id,control_id) REFERENCES mission_controls(mission_id,id) ON DELETE CASCADE
);
CREATE INDEX control_evidence_control ON control_evidence(mission_id,control_id,created_at,id);
