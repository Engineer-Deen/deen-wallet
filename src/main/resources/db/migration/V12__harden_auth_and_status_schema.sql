
-- Harden existing schema without rewriting earlier Flyway migrations.
ALTER TABLE users ALTER COLUMN locked_at TYPE TIMESTAMPTZ USING locked_at AT TIME ZONE 'UTC';
UPDATE users SET pin_hash = '' WHERE pin_hash IS NULL;
ALTER TABLE users ALTER COLUMN pin_hash SET NOT NULL;
UPDATE users SET role = 'USER' WHERE role IS NULL;
ALTER TABLE users ALTER COLUMN role SET DEFAULT 'USER';
ALTER TABLE email_otps ALTER COLUMN code TYPE VARCHAR(255);
ALTER TABLE email_otps ADD COLUMN IF NOT EXISTS attempts INTEGER NOT NULL DEFAULT 0;
UPDATE transactions SET status = UPPER(status);
ALTER TABLE transactions ALTER COLUMN status SET DEFAULT 'AWAITING_PAYMENT';
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_transactions_status;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_status CHECK (status IN ('AWAITING_PAYMENT','PAID_IN','PAYING_OUT','COMPLETED','FAILED','CANCELED'));
CREATE TABLE IF NOT EXISTS refresh_tokens (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 jti VARCHAR(100) NOT NULL UNIQUE,
 user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
 expires_at TIMESTAMPTZ NOT NULL,
 revoked BOOLEAN NOT NULL DEFAULT FALSE,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user_id ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_expires_at ON refresh_tokens(expires_at);
CREATE TABLE IF NOT EXISTS webhook_events (
 id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
 event_key VARCHAR(128) NOT NULL UNIQUE,
 event_name VARCHAR(100) NOT NULL,
 resource_id VARCHAR(128) NOT NULL,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_webhook_events_created_at ON webhook_events(created_at);
