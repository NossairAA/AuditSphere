CREATE TABLE IF NOT EXISTS web_sessions (
 id_hash TEXT PRIMARY KEY,
 kind TEXT NOT NULL CHECK (kind IN ('oauth','session')),
 data JSONB NOT NULL,
 expires_at TIMESTAMPTZ NOT NULL
);
CREATE INDEX IF NOT EXISTS web_sessions_expiry ON web_sessions(expires_at);
