-- Login password-attempt protection and richer error diagnostics.
ALTER TABLE users ADD COLUMN IF NOT EXISTS login_failed_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS login_locked_until TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_failed_at TIMESTAMPTZ;

CREATE INDEX IF NOT EXISTS idx_users_login_locked_until ON users(login_locked_until);

ALTER TABLE error_logs ADD COLUMN IF NOT EXISTS stack TEXT;
ALTER TABLE error_logs ADD COLUMN IF NOT EXISTS line INTEGER;
ALTER TABLE error_logs ADD COLUMN IF NOT EXISTS col INTEGER;
CREATE INDEX IF NOT EXISTS idx_error_logs_created_at ON error_logs(created_at DESC);
CREATE INDEX IF NOT EXISTS idx_error_logs_error_type ON error_logs(error_type);
