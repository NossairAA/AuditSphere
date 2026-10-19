ALTER TABLE notifications ADD COLUMN event_type varchar(80) NOT NULL DEFAULT 'audit.mission.assigned.v1';
ALTER TABLE notifications ADD CONSTRAINT notifications_type_check CHECK (event_type IN ('audit.mission.assigned.v1','audit.mission.review-requested.v1'));
