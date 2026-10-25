-- Admin wallets (a ledger inside the one Monime account) and every withdrawal made from the Wallet screen.
-- The Monime account itself is the money; these tables only record who is owed what.
-- No foreign keys on purpose: the history must survive even if an admin account is removed.

CREATE TABLE IF NOT EXISTS admin_wallets (
    user_id        UUID        PRIMARY KEY,
    balance_value  BIGINT      NOT NULL DEFAULT 0 CHECK (balance_value >= 0),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS wallet_entries (
    id                 UUID         PRIMARY KEY,
    user_id            UUID         NOT NULL,
    kind               VARCHAR(24)  NOT NULL,     -- TRANSFER_IN, WITHDRAWAL, WITHDRAWAL_REVERSAL
    amount_value       BIGINT       NOT NULL CHECK (amount_value > 0),
    balance_after      BIGINT       NOT NULL,
    note               VARCHAR(300),
    counterparty_id    UUID,
    counterparty_name  VARCHAR(150),
    withdrawal_id      UUID,
    created_by         UUID,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wallet_entries_user ON wallet_entries (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_entries_created ON wallet_entries (created_at DESC);

CREATE TABLE IF NOT EXISTS wallet_withdrawals (
    id                UUID         PRIMARY KEY,
    owner_user_id     UUID         NOT NULL,
    scope             VARCHAR(10)  NOT NULL,      -- PLATFORM (super admin, from the Monime account) or ADMIN (from an admin wallet)
    amount_value      BIGINT       NOT NULL CHECK (amount_value > 0),
    currency          VARCHAR(3)   NOT NULL DEFAULT 'SLE',
    method            VARCHAR(8)   NOT NULL,      -- momo or bank
    provider_id       VARCHAR(40)  NOT NULL,
    destination       VARCHAR(40)  NOT NULL,
    holder_name       VARCHAR(150),
    status            VARCHAR(12)  NOT NULL DEFAULT 'PENDING',   -- PENDING, COMPLETED, FAILED, REVIEW
    monime_payout_id  VARCHAR(100),
    failure_reason    VARCHAR(500),
    attempts          INTEGER      NOT NULL DEFAULT 0,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ
);
CREATE UNIQUE INDEX IF NOT EXISTS uq_wallet_withdrawals_payout ON wallet_withdrawals (monime_payout_id) WHERE monime_payout_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_wallet_withdrawals_owner ON wallet_withdrawals (owner_user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_wallet_withdrawals_open ON wallet_withdrawals (status) WHERE status IN ('PENDING', 'REVIEW');
