-- =====================================================================
-- V17: indexes for scale (2,000+ users) - verified with EXPLAIN ANALYZE
-- on 2,000 users / 200,000 transactions.
-- Plain CREATE INDEX is fine at current table sizes (milliseconds).
-- =====================================================================

-- 1) MISSING indexes ---------------------------------------------------

-- FK column with no index: every recipient delete / hasTransactions()
-- did a full scan of transactions (35 ms at 200k rows, grows linearly).
CREATE INDEX IF NOT EXISTS idx_transactions_recipient_id
    ON transactions (recipient_id) WHERE recipient_id IS NOT NULL;

-- Admin "all transactions" is ORDER BY created_at DESC with no supporting index.
CREATE INDEX IF NOT EXISTS idx_transactions_created_at
    ON transactions (created_at DESC);

-- Admin user list: WHERE role = 'USER' ORDER BY created_at DESC.
CREATE INDEX IF NOT EXISTS idx_users_role_created_at
    ON users (role, created_at DESC);

-- OTP lookups: WHERE email = ? [AND used = false] ORDER BY created_at DESC
-- and the resend rate-limit count (email = ? AND created_at > ?).
CREATE INDEX IF NOT EXISTS idx_email_otps_email_created_at
    ON email_otps (email, created_at DESC);


DROP INDEX IF EXISTS idx_transactions_transaction_code;        -- duplicate of UNIQUE uk_transaction_code
DROP INDEX IF EXISTS idx_transactions_user_id;                  -- prefix of (user_id, created_at DESC)
DROP INDEX IF EXISTS idx_transactions_status;                   -- prefix of (status, created_at)
DROP INDEX IF EXISTS idx_saved_recipients_user_phone_provider;  -- duplicate of UNIQUE (user_id, phone_number, provider_id)
DROP INDEX IF EXISTS idx_saved_recipients_user_id;              -- prefix of that UNIQUE constraint's index
DROP INDEX IF EXISTS idx_email_otps_email;                      -- prefix of (email, created_at DESC)

-- 3) HOT-row tuning -----------------------------------------------------
-- users rows are updated on every login/failed-login (counters). Leaving
-- free space in each page lets Postgres do cheap in-page (HOT) updates.
ALTER TABLE users SET (fillfactor = 85);

ANALYZE users;
ANALYZE transactions;
ANALYZE email_otps;
