-- pawaPay cross-border transfers (sender pays in Sierra Leone -> money is sent to a mobile money wallet in another country).
CREATE TABLE IF NOT EXISTS cross_border_transfers (
    id                         UUID          PRIMARY KEY,
    user_id                    UUID          NOT NULL,
    status                     VARCHAR(16)   NOT NULL,   -- CREATED, COLLECTING, COLLECTED, REMITTING, COMPLETED, REFUNDING, REFUNDED, FAILED, REVIEW
    -- what the sender pays (collected from the sender's mobile money)
    send_amount                NUMERIC(20,2) NOT NULL CHECK (send_amount > 0),
    send_currency              VARCHAR(3)    NOT NULL,
    fee_amount                 NUMERIC(20,2) NOT NULL DEFAULT 0,
    -- what the recipient receives
    receive_amount             NUMERIC(20,3) NOT NULL CHECK (receive_amount > 0),
    receive_currency           VARCHAR(3)    NOT NULL,
    fx_rate                    NUMERIC(24,8) NOT NULL,
    payer_provider             VARCHAR(40)   NOT NULL,   -- e.g. ORANGE_SLE
    payer_phone                VARCHAR(20)   NOT NULL,
    dest_country               VARCHAR(3)    NOT NULL,
    dest_provider              VARCHAR(40)   NOT NULL,   -- e.g. MTN_MOMO_UGA
    recipient_phone            VARCHAR(20)   NOT NULL,
    recipient_first_name       VARCHAR(64)   NOT NULL,
    recipient_last_name        VARCHAR(64)   NOT NULL,
    -- ids are created and saved BEFORE any call, so a retry can never charge or pay twice
    deposit_id                 UUID          NOT NULL UNIQUE,
    remittance_id              UUID          NOT NULL UNIQUE,
    refund_id                  UUID          NOT NULL UNIQUE,
    deposit_status             VARCHAR(24),
    remittance_status          VARCHAR(24),
    refund_status              VARCHAR(24),
    deposit_provider_txn_id    VARCHAR(100),
    remittance_provider_txn_id VARCHAR(100),
    sender_enc                 TEXT          NOT NULL,   -- sender KYC details (ID number etc.), encrypted
    failure_code               VARCHAR(64),
    failure_message            VARCHAR(500),
    attempts                   INTEGER       NOT NULL DEFAULT 0,
    created_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at                 TIMESTAMPTZ   NOT NULL DEFAULT now(),
    completed_at               TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_cbt_user_created ON cross_border_transfers (user_id, created_at DESC);
CREATE INDEX IF NOT EXISTS idx_cbt_open ON cross_border_transfers (updated_at)
    WHERE status IN ('CREATED', 'COLLECTING', 'COLLECTED', 'REMITTING', 'REFUNDING');

CREATE TABLE IF NOT EXISTS pawapay_events (
    id           UUID         PRIMARY KEY,
    event_key    VARCHAR(128) NOT NULL UNIQUE,
    kind         VARCHAR(16)  NOT NULL,       -- deposits, remittances, refunds, payouts
    resource_id  VARCHAR(64)  NOT NULL,
    claimed      VARCHAR(24),                 -- the status the callback claimed (never trusted; the status is re-read from pawaPay)
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_pawapay_events_resource ON pawapay_events (resource_id);
