-- Things worth a security person's attention: blocked-by-rate-limit, failed logins/PINs, scanners probing for known weak spots.
CREATE TABLE IF NOT EXISTS security_events (
    id          BIGSERIAL    PRIMARY KEY,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    ip          VARCHAR(64)  NOT NULL,
    kind        VARCHAR(20)  NOT NULL,   -- RATE_LIMIT, LOGIN_FAILED, PIN_FAILED, SCAN, ADMIN_PROBE
    method      VARCHAR(10),
    path        VARCHAR(200),
    status      INT,
    user_agent  VARCHAR(160)
);
CREATE INDEX IF NOT EXISTS idx_security_events_time ON security_events (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_security_events_ip ON security_events (ip, created_at DESC);
