-- Sessions written before token encryption cannot be read safely by the new BFF.
-- Run this during rollout before starting the upgraded web process.
DELETE FROM web_sessions WHERE kind='session' AND NOT (data ? 'tokens');
