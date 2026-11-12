-- Super admin can withdraw from the separate WAEC pin Monime account and move money between the two accounts.
ALTER TABLE wallet_withdrawals ADD COLUMN IF NOT EXISTS account VARCHAR(8) NOT NULL DEFAULT 'MAIN';   -- MAIN or WAEC: which Monime account the payout comes from

CREATE TABLE IF NOT EXISTS wallet_account_transfers (
    id                  UUID         PRIMARY KEY,
    from_account        VARCHAR(8)   NOT NULL,      -- MAIN or WAEC
    to_account          VARCHAR(8)   NOT NULL,
    amount_value        BIGINT       NOT NULL CHECK (amount_value > 0),
    currency            VARCHAR(3)   NOT NULL DEFAULT 'SLE',
    status              VARCHAR(12)  NOT NULL DEFAULT 'PENDING',   -- PENDING, COMPLETED, FAILED, REVIEW
    monime_transfer_id  VARCHAR(100),
    failure_reason      VARCHAR(500),
    attempts            INTEGER      NOT NULL DEFAULT 0,
    created_by          UUID         NOT NULL,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at        TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_wallet_account_transfers_open ON wallet_account_transfers (status) WHERE status IN ('PENDING', 'REVIEW');
CREATE INDEX IF NOT EXISTS idx_wallet_account_transfers_created ON wallet_account_transfers (created_at DESC);
