-- Payment links: a customer shares a link, anyone can pay it with mobile money, and the money is sent to the
-- account the link owner chose. Each payment is a normal row in "transactions" (so it gets the same safety checks,
-- payout handling, refunds and admin tools); these two columns tie it back to the link and remember the payer's name.
CREATE TABLE IF NOT EXISTS payment_requests (
    id               UUID         PRIMARY KEY,
    owner_user_id    UUID         NOT NULL,
    slug             VARCHAR(16)  NOT NULL UNIQUE,
    title            VARCHAR(80)  NOT NULL,
    note             VARCHAR(300),
    amount_value     BIGINT,
    reusable         BOOLEAN      NOT NULL DEFAULT FALSE,
    dest_type        VARCHAR(10)  NOT NULL,
    dest_provider_id VARCHAR(64)  NOT NULL,
    dest_account     VARCHAR(64)  NOT NULL,
    dest_holder_name VARCHAR(200) NOT NULL,
    dest_bank_name   VARCHAR(200),
    status           VARCHAR(12)  NOT NULL DEFAULT 'ACTIVE',
    expires_at       TIMESTAMPTZ,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    closed_at        TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_payment_requests_owner ON payment_requests (owner_user_id, created_at DESC);

ALTER TABLE transactions ADD COLUMN IF NOT EXISTS payment_request_id UUID;
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS payer_name VARCHAR(80);
CREATE INDEX IF NOT EXISTS idx_transactions_payment_request ON transactions (payment_request_id) WHERE payment_request_id IS NOT NULL;
