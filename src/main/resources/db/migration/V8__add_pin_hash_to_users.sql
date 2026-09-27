
-- ==============================================================
-- V8: Add pin_hash column to users table
-- ==============================================================

ALTER TABLE users ADD COLUMN IF NOT EXISTS pin_hash VARCHAR(255);

COMMENT ON COLUMN users.pin_hash IS 'Hashed PIN for session security';