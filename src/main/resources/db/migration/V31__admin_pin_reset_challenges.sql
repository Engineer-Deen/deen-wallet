-- Admin "Forgot PIN" recovery (email code -> short-lived one-time authorization -> new PIN).
-- Kept in its own table, keyed by the ADMIN's user id, so it can never mix with the customer
-- pin_reset_challenges flow (customers and admins may share the same email address).
-- The code and the one-time authorization are stored hashed; nothing here can be used directly.
CREATE TABLE IF NOT EXISTS admin_pin_reset_challenges (
    id                      UUID PRIMARY KEY,
    admin_id                UUID         NOT NULL,
    otp_hash                VARCHAR(100) NOT NULL,
    otp_expires_at          TIMESTAMPTZ  NOT NULL,
    otp_attempts            INTEGER      NOT NULL DEFAULT 0,
    otp_used                BOOLEAN      NOT NULL DEFAULT FALSE,
    reset_token_hash        VARCHAR(64),
    reset_token_expires_at  TIMESTAMPTZ,
    reset_token_used_at     TIMESTAMPTZ,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_admin_pin_reset_admin ON admin_pin_reset_challenges (admin_id, created_at DESC);
CREATE UNIQUE INDEX IF NOT EXISTS uq_admin_pin_reset_token ON admin_pin_reset_challenges (reset_token_hash) WHERE reset_token_hash IS NOT NULL;
