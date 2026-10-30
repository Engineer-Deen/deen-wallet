-- Addresses removed from the Server Metrics attack lists (so they stop showing up). Not the same as blocked.
CREATE TABLE IF NOT EXISTS dismissed_ips (
    ip          VARCHAR(64)  PRIMARY KEY,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);
