-- 1) New states. PAYOUT_FAILED = customer paid, payout failed, WAITING FOR AN ADMIN (resend or refund).
ALTER TABLE transactions DROP CONSTRAINT IF EXISTS chk_transactions_status;
ALTER TABLE transactions ADD CONSTRAINT chk_transactions_status CHECK (status IN
  ('AWAITING_PAYMENT','PAID_IN','PAYING_OUT','COMPLETED','FAILED','CANCELED',
   'PAYOUT_FAILED','REFUND_PENDING','REFUNDED','NEEDS_REVIEW'));

-- 2) Audit / recovery columns.
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS paid_in_at TIMESTAMPTZ;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS payout_failed_at TIMESTAMPTZ;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS payout_attempt INTEGER NOT NULL DEFAULT 0;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS refund_payout_id VARCHAR(100);

-- 3) Every recovery event is recorded: who did what, when, with what result.
CREATE TABLE IF NOT EXISTS payout_recovery_actions (
    id              UUID PRIMARY KEY,
    transaction_id  UUID NOT NULL REFERENCES transactions(id),
    action          VARCHAR(30)  NOT NULL,   -- PAYOUT_FAILED, RESEND, RESEND_FAILED, REFUND, REFUND_SENT, REFUND_FAILED
    actor_id        UUID,                    -- admin user id; NULL = system
    actor_email     VARCHAR(150),
    detail          VARCHAR(1000),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_pra_transaction ON payout_recovery_actions (transaction_id, created_at);

-- 4) Provider ids map to exactly one transaction (webhook lookups use Optional).
--    If this fails you already have duplicates: resolve them first.
CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_payment_code_id
    ON transactions (monime_payment_code_id) WHERE monime_payment_code_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_payout_id
    ON transactions (monime_payout_id) WHERE monime_payout_id IS NOT NULL;
CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_refund_payout_id
    ON transactions (refund_payout_id) WHERE refund_payout_id IS NOT NULL;

-- 5) Fast lookups for reconciliation, the failed-payout queue, and per-user limits.
CREATE INDEX IF NOT EXISTS idx_transactions_status_created ON transactions (status, created_at);
CREATE INDEX IF NOT EXISTS idx_transactions_user_status ON transactions (user_id, status);
