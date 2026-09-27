
-- Speeds up TransactionExpiryScheduler's query
-- (findByStatusAndCreatedAtBefore), which runs every minute. Without
-- this index, that query does a full table scan on every run — fine
-- with a handful of transactions, but becomes a real bottleneck once
-- the table has thousands of rows and many concurrent users.
CREATE INDEX IF NOT EXISTS idx_transactions_status_created_at
    ON transactions (status, created_at);

-- Speeds up the recipient-history and duplicate-recipient-check
-- queries (findByUserIdOrderByCreatedAtDesc,
-- existsByUserIdAndPhoneNumberAndProviderId), which get hit on every
-- page load and every recipient save.
CREATE INDEX IF NOT EXISTS idx_transactions_user_id_created_at
    ON transactions (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_saved_recipients_user_id
    ON saved_recipients (user_id);

CREATE INDEX IF NOT EXISTS idx_saved_recipients_user_phone_provider
    ON saved_recipients (user_id, phone_number, provider_id);
