-- Lets a refresh token that was just rotated be accepted once more for a few minutes.
-- Protects people from being logged out when the response of a refresh is lost
-- (weak mobile network, app closed mid-refresh). Existing rows simply have no value.
ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS revoked_at TIMESTAMPTZ;