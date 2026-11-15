-- Cross-border transfers: the price list the admin controls (rate, fees, limits per country + mobile money provider),
-- the sender's saved details (encrypted), and a request id so a double tap can never start two transfers.
CREATE TABLE IF NOT EXISTS cross_border_corridors (
    id               BIGSERIAL     PRIMARY KEY,
    country          VARCHAR(3)    NOT NULL,                 -- destination, ISO3 (GHA)
    country_name     VARCHAR(60)   NOT NULL,
    dial_code        VARCHAR(5)    NOT NULL,                 -- 233
    provider         VARCHAR(40)   NOT NULL,                 -- pawaPay provider code, e.g. MTN_MOMO_GHA
    provider_name    VARCHAR(60)   NOT NULL,
    currency         VARCHAR(3)    NOT NULL,                 -- the recipient's currency
    receive_decimals SMALLINT      NOT NULL DEFAULT 2,
    rate             NUMERIC(24,8) NOT NULL DEFAULT 0,       -- 1 SLE = rate units of the recipient's currency
    fee_percent      NUMERIC(6,3)  NOT NULL DEFAULT 0,
    fee_flat         NUMERIC(20,2) NOT NULL DEFAULT 0,
    min_send         NUMERIC(20,2) NOT NULL DEFAULT 10,
    max_send         NUMERIC(20,2) NOT NULL DEFAULT 5000,
    enabled          BOOLEAN       NOT NULL DEFAULT FALSE,
    sort_order       INT           NOT NULL DEFAULT 0,
    updated_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_by       VARCHAR(254),
    CONSTRAINT uq_cbc_country_provider UNIQUE (country, provider)
);

CREATE TABLE IF NOT EXISTS cross_border_sender_profiles (
    user_id    UUID        PRIMARY KEY,
    data_enc   TEXT        NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

ALTER TABLE cross_border_transfers ADD COLUMN IF NOT EXISTS client_request_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS uq_cbt_client_request ON cross_border_transfers (user_id, client_request_id) WHERE client_request_id IS NOT NULL;
