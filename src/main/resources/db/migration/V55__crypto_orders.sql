-- Send to a crypto wallet: the customer pays with mobile money and the provider (HoneyCoin) sends USDT to their wallet.
-- One row per request. DeenWallet never holds the money or the crypto, so there is no balance table.
CREATE TABLE IF NOT EXISTS crypto_orders (
    id              UUID         PRIMARY KEY,
    user_id         UUID         NOT NULL,
    reference       VARCHAR(24)  NOT NULL UNIQUE,
    amount_minor    BIGINT       NOT NULL,
    operator        VARCHAR(12)  NOT NULL,
    phone           VARCHAR(20)  NOT NULL,
    asset           VARCHAR(8)   NOT NULL,
    chain           VARCHAR(12)  NOT NULL,
    address         VARCHAR(120) NOT NULL,
    provider_tx_id  VARCHAR(100),
    status          VARCHAR(12)  NOT NULL DEFAULT 'CREATED',
    crypto_amount   VARCHAR(40),
    chain_tx_id     VARCHAR(140),
    failure_reason  VARCHAR(300),
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_crypto_orders_user ON crypto_orders (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_crypto_orders_provider ON crypto_orders (provider_tx_id) WHERE provider_tx_id IS NOT NULL;
CREATE INDEX IF NOT EXISTS idx_crypto_orders_open ON crypto_orders (status, created_at) WHERE status IN ('CREATED', 'PENDING');
