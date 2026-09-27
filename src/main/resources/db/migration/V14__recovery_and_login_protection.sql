-- Admin-specific login protection and recovery state.
-- Safe for databases that already contain the generic login-failure columns.
ALTER TABLE users ADD COLUMN IF NOT EXISTS login_failed_attempts INTEGER NOT NULL DEFAULT 0;
ALTER TABLE users ADD COLUMN IF NOT EXISTS login_locked_until TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_login_failed_at TIMESTAMPTZ;
ALTER TABLE users ADD COLUMN IF NOT EXISTS admin_recovery_required BOOLEAN NOT NULL DEFAULT FALSE;

CREATE INDEX IF NOT EXISTS idx_users_role_locked ON users (role, locked);
CREATE INDEX IF NOT EXISTS idx_users_admin_recovery ON users (admin_recovery_required) WHERE admin_recovery_required = TRUE;
CREATE INDEX IF NOT EXISTS idx_users_login_locked_until ON users (login_locked_until);