-- =====================================================================
-- V18: (1) tag errors by which app sent them, for admin traceability
--      (2) archive table for old transactions
-- =====================================================================

-- ---- 1. Error traceability ---------------------------------------------
-- sourceApp: which frontend reported it ('user' = auth/index/transactions.html,
--   'admin' = admin.html). Defaults to 'user' for rows already in the table.
-- endpoint_path: the actual API path that failed (e.g. /api/transactions),
--   separate from `url` (the PAGE the person was on) - this is what makes a
--   failed API call traceable straight to the backend endpoint.
-- http_method: GET/POST/etc, so admins can tell "GET failing" from "POST failing"
--   on the same path.
ALTER TABLE error_logs ADD COLUMN IF NOT EXISTS source_app VARCHAR(10) NOT NULL DEFAULT 'user';
ALTER TABLE error_logs ADD COLUMN IF NOT EXISTS endpoint_path VARCHAR(300);
ALTER TABLE error_logs ADD COLUMN IF NOT EXISTS http_method VARCHAR(10);

-- Supports the admin "live errors since X" poll (WHERE created_at > :since ORDER BY created_at)
-- and filtering by which app. Composite because the poll always filters by recency first.
CREATE INDEX IF NOT EXISTS idx_error_logs_created_at_source ON error_logs (created_at DESC, source_app);

-- ---- 2. Transaction archive ---------------------------------------------
-- Same shape as `transactions`. Rows land here once they are old AND in a
-- final state (COMPLETED/FAILED/CANCELED) - see TransactionArchiveJob.
-- This is NOT deletion: nothing here is destroyed, it just moves out of the
-- hot table (and out of a user's "recent history" list) so that table stays
-- small and fast as the user base grows. See RUNBOOK for the retention policy
-- and why permanent deletion is a separate, off-by-default decision.
CREATE TABLE IF NOT EXISTS transactions_archive (
    id                            UUID PRIMARY KEY,
    transaction_code              VARCHAR(64) NOT NULL,
    user_id                       UUID NOT NULL,
    recipient_id                  UUID,
    amount_currency               VARCHAR(10) NOT NULL,
    amount_value                  BIGINT NOT NULL,
    fee_value                     BIGINT NOT NULL,
    monime_deposit_fee_value      BIGINT NOT NULL,
    deen_wallet_fee_value         BIGINT NOT NULL,
    monime_withdrawal_fee_value   BIGINT NOT NULL,
    total_charged_value           BIGINT NOT NULL,
    source_provider_id            VARCHAR(20) NOT NULL,
    source_phone                  VARCHAR(20) NOT NULL,
    destination_provider_id       VARCHAR(20) NOT NULL,
    destination_phone             VARCHAR(20) NOT NULL,
    destination_holder_name       VARCHAR(200),
    monime_payment_code_id        VARCHAR(100),
    monime_ussd_code              VARCHAR(50),
    monime_payout_id              VARCHAR(100),
    failure_reason                VARCHAR(1000),
    status                        VARCHAR(30) NOT NULL,
    sms_sent                      BOOLEAN NOT NULL,
    created_at                    TIMESTAMPTZ NOT NULL,
    updated_at                    TIMESTAMPTZ NOT NULL,
    archived_at                   TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- A user asking support "where did my old transaction go" - look up by user, newest first.
CREATE INDEX IF NOT EXISTS idx_tx_archive_user_id ON transactions_archive (user_id, created_at DESC);
-- Admin/support tracing a specific transaction code in the archive.
CREATE INDEX IF NOT EXISTS idx_tx_archive_code ON transactions_archive (transaction_code);
