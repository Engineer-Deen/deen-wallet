-- When a customer / admin was last seen using the app (powers "online now" on the admin Home).
ALTER TABLE users ADD COLUMN IF NOT EXISTS last_seen_at TIMESTAMPTZ;
CREATE INDEX IF NOT EXISTS idx_users_last_seen ON users (last_seen_at) WHERE last_seen_at IS NOT NULL;
