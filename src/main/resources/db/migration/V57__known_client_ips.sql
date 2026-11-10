-- Addresses a real signed-in customer or admin used recently. The automatic ban never blocks these
-- (many customers share one mobile-network address). Rows older than 60 days are deleted every night.
CREATE TABLE IF NOT EXISTS known_client_ips (
    ip        varchar(64) PRIMARY KEY,
    last_seen timestamptz NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_known_client_ips_last_seen ON known_client_ips (last_seen);
