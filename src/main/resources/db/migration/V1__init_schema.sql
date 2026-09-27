
-- V1__init_schema.sql
-- Core schema for Deen Wallet: users, email OTPs, saved recipients, transactions.

CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    full_name       VARCHAR(150)    NOT NULL,
    username        VARCHAR(50)     NOT NULL UNIQUE,
    phone           VARCHAR(20)     NOT NULL UNIQUE,
    email           VARCHAR(150)    NOT NULL UNIQUE,
    password_hash   VARCHAR(255)    NOT NULL,
    account_number  VARCHAR(20)     NOT NULL UNIQUE,
    email_verified  BOOLEAN         NOT NULL DEFAULT FALSE,
    phone_verified  BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE TABLE email_otps (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    email       VARCHAR(150)    NOT NULL,
    code        VARCHAR(10)     NOT NULL,
    expires_at  TIMESTAMPTZ     NOT NULL,
    used        BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_email_otps_email ON email_otps (email);

CREATE TABLE saved_recipients (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id         UUID            NOT NULL REFERENCES users (id) ON DELETE CASCADE,
    phone_number    VARCHAR(20)     NOT NULL,
    provider_id     VARCHAR(10)     NOT NULL,
    holder_name     VARCHAR(150),
    label           VARCHAR(50),
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    UNIQUE (user_id, phone_number, provider_id)
);

CREATE INDEX idx_saved_recipients_user_id ON saved_recipients (user_id);

CREATE TABLE transactions (
    id                          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id                     UUID            NOT NULL REFERENCES users (id),
    recipient_id                UUID            REFERENCES saved_recipients (id),
    amount_currency             VARCHAR(3)      NOT NULL DEFAULT 'SLE',
    amount_value                BIGINT          NOT NULL,
    source_provider_id          VARCHAR(10)     NOT NULL,
    source_phone                VARCHAR(20)     NOT NULL,
    destination_provider_id     VARCHAR(10)     NOT NULL,
    destination_phone           VARCHAR(20)     NOT NULL,
    destination_holder_name     VARCHAR(150),
    monime_payment_code_id      VARCHAR(64),
    monime_ussd_code            VARCHAR(64),
    monime_payout_id            VARCHAR(64),
    status                      VARCHAR(30)     NOT NULL DEFAULT 'awaiting_payment',
    sms_sent                    BOOLEAN         NOT NULL DEFAULT FALSE,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now()
);

CREATE INDEX idx_transactions_user_id ON transactions (user_id);
CREATE INDEX idx_transactions_status ON transactions (status);
CREATE INDEX idx_transactions_payment_code_id ON transactions (monime_payment_code_id);
CREATE INDEX idx_transactions_payout_id ON transactions (monime_payout_id);
