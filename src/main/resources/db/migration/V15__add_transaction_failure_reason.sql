ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS failure_reason VARCHAR(1000);

CREATE INDEX IF NOT EXISTS idx_transactions_status_created_at
    ON transactions(status, created_at DESC);
