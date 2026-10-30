-- Addresses an admin chose to block. The app answers them with "Access denied". expires_at NULL = until unblocked.
CREATE TABLE IF NOT EXISTS blocked_ips (
    ip          VARCHAR(64)  PRIMARY KEY,
    reason      VARCHAR(200),
    blocked_by  VARCHAR(150),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    expires_at  TIMESTAMPTZ
);
-- What a suspicious request tried (never the request's contents, which could hold passwords).
ALTER TABLE security_events ADD COLUMN IF NOT EXISTS detail VARCHAR(120);
