-- Passkeys (screen lock / fingerprint / face) an admin can use to verify their account. Only the PUBLIC key is stored.
CREATE TABLE IF NOT EXISTS admin_passkeys (
    id            UUID          PRIMARY KEY,
    admin_id      UUID          NOT NULL,
    label         VARCHAR(40)   NOT NULL,
    credential_id VARCHAR(1400) NOT NULL UNIQUE,
    public_key    TEXT          NOT NULL,
    alg           INT           NOT NULL,
    sign_count    BIGINT        NOT NULL DEFAULT 0,
    created_at    TIMESTAMPTZ   NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_admin_passkeys_admin ON admin_passkeys (admin_id);
