-- Trusted devices: every login is a "session" that stays the same while its refresh token rotates.
-- The session is what the customer sees (and can remove) under "Trusted Devices".
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS session_id      UUID;
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS device_key      VARCHAR(64);
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS device_name     VARCHAR(120);
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS device_platform VARCHAR(30);
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS last_ip         VARCHAR(64);
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS last_used_at    TIMESTAMPTZ;
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS first_login_at  TIMESTAMPTZ;

-- Logins that already exist each become their own session.
UPDATE refresh_tokens
   SET session_id     = id,
       first_login_at = created_at,
       last_used_at   = created_at
 WHERE session_id IS NULL;

CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_session ON refresh_tokens(user_id, session_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_device  ON refresh_tokens(user_id, device_key);
