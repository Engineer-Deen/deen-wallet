-- Recipients a customer has sent money abroad to (or saved to send to later). One row per customer + provider + number.
CREATE TABLE IF NOT EXISTS cross_border_recipients (
    id         UUID         PRIMARY KEY,
    user_id    UUID         NOT NULL,
    country    VARCHAR(3)   NOT NULL,
    provider   VARCHAR(40)  NOT NULL,
    phone      VARCHAR(20)  NOT NULL,
    first_name VARCHAR(64)  NOT NULL,
    last_name  VARCHAR(64)  NOT NULL,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_cbr_user_provider_phone UNIQUE (user_id, provider, phone)
);
CREATE INDEX IF NOT EXISTS idx_cbr_user ON cross_border_recipients (user_id);
