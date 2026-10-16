CREATE TABLE missions (
 id UUID PRIMARY KEY, title VARCHAR(200) NOT NULL, description VARCHAR(4000) NOT NULL,
 perimeter VARCHAR(2000) NOT NULL, owner_subject VARCHAR(255) NOT NULL,
 created_at TIMESTAMP WITH TIME ZONE NOT NULL, status VARCHAR(255) NOT NULL CHECK (status IN ('DRAFT'))
);
CREATE TABLE mission_assignments (
 mission_id UUID NOT NULL REFERENCES missions(id) ON DELETE CASCADE,
 subject VARCHAR(255) NOT NULL, PRIMARY KEY(mission_id,subject)
);
CREATE INDEX missions_owner_idx ON missions(owner_subject);
