
-- ==============================================================
-- V7: Add lock fields to users table
-- For account locking after multiple failed PIN attempts
-- ==============================================================

-- Add columns (IF NOT EXISTS prevents errors on re-run)
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked BOOLEAN DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS pin_attempts INTEGER DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP;
ALTER TABLE users ADD COLUMN IF NOT EXISTS locked_by UUID;

-- Update existing users (set defaults for any NULL values)
UPDATE users SET locked = FALSE WHERE locked IS NULL;
UPDATE users SET pin_attempts = 0 WHERE pin_attempts IS NULL;

-- Make columns NOT NULL (safe after setting defaults)
ALTER TABLE users ALTER COLUMN locked SET NOT NULL;
ALTER TABLE users ALTER COLUMN pin_attempts SET NOT NULL;

-- Add index for faster queries on locked users
CREATE INDEX IF NOT EXISTS idx_users_locked ON users(locked);
CREATE INDEX IF NOT EXISTS idx_users_locked_at ON users(locked_at);

-- Add comment for documentation
COMMENT ON COLUMN users.locked IS 'Account locked due to multiple failed PIN attempts';
COMMENT ON COLUMN users.pin_attempts IS 'Number of consecutive failed PIN attempts';
COMMENT ON COLUMN users.locked_at IS 'When the account was locked';
COMMENT ON COLUMN users.locked_by IS 'Admin who locked the account (NULL if auto-locked)';