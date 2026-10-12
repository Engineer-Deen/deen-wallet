-- WAEC Result Pin service.
-- Pins are stored ENCRYPTED (AES-256-GCM, key in WAEC_PIN_ENCRYPTION_KEY). pin_hash / serial_hash are
-- keyed HMACs used only to reject duplicates and to search; they cannot be reversed to the pin.
-- No foreign key to transactions on purpose: finished transactions are moved to transactions_archive
-- and deleted by TransactionArchiveJob, and the pin trail must survive that.

CREATE TABLE IF NOT EXISTS waec_pins (
                                         id               UUID PRIMARY KEY,
                                         pin_encrypted    VARCHAR(600) NOT NULL,
    serial_encrypted VARCHAR(600) NOT NULL,
    pin_hash         VARCHAR(64)  NOT NULL,
    serial_hash      VARCHAR(64)  NOT NULL,
    pin_last4        VARCHAR(4)   NOT NULL,
    serial_last4     VARCHAR(4)   NOT NULL,
    status           VARCHAR(12)  NOT NULL DEFAULT 'AVAILABLE',
    order_id         UUID,
    reserved_at      TIMESTAMPTZ,
    sold_at          TIMESTAMPTZ,
    added_by         UUID,
    added_by_email   VARCHAR(254),
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_waec_pins_pin_hash UNIQUE (pin_hash),
    CONSTRAINT uq_waec_pins_serial_hash UNIQUE (serial_hash),
    CONSTRAINT ck_waec_pins_status CHECK (status IN ('AVAILABLE', 'RESERVED', 'SOLD'))
    );

CREATE INDEX IF NOT EXISTS idx_waec_pins_available ON waec_pins (created_at, id) WHERE status = 'AVAILABLE';
CREATE INDEX IF NOT EXISTS idx_waec_pins_order ON waec_pins (order_id);
CREATE INDEX IF NOT EXISTS idx_waec_pins_status ON waec_pins (status);

CREATE TABLE IF NOT EXISTS waec_orders (
                                           id                  UUID PRIMARY KEY,
                                           transaction_id      UUID         NOT NULL,
                                           transaction_code    VARCHAR(60)  NOT NULL,
    user_id             UUID         NOT NULL,
    quantity            INTEGER      NOT NULL,
    unit_price_value    BIGINT       NOT NULL,
    amount_value        BIGINT       NOT NULL,
    fee_value           BIGINT       NOT NULL,
    total_charged_value BIGINT       NOT NULL,
    delivery_channel    VARCHAR(10)  NOT NULL DEFAULT 'EMAIL',
    delivery_email      VARCHAR(254),
    delivery_phone      VARCHAR(32),
    delivery_status     VARCHAR(12)  NOT NULL DEFAULT 'PENDING',
    delivery_attempts   INTEGER      NOT NULL DEFAULT 0,
    last_delivery_error VARCHAR(500),
    fulfilled_at        TIMESTAMPTZ,
    delivered_at        TIMESTAMPTZ,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_waec_orders_transaction UNIQUE (transaction_id),
    CONSTRAINT ck_waec_orders_delivery_status CHECK (delivery_status IN ('PENDING', 'SENT', 'FAILED'))
    );

CREATE INDEX IF NOT EXISTS idx_waec_orders_user ON waec_orders (user_id);
CREATE INDEX IF NOT EXISTS idx_waec_orders_fulfilled ON waec_orders (fulfilled_at DESC);
CREATE INDEX IF NOT EXISTS idx_waec_orders_code ON waec_orders (transaction_code);
CREATE INDEX IF NOT EXISTS idx_waec_orders_email ON waec_orders (delivery_email);

-- Who revealed, resent or added pins (admin accountability).
CREATE TABLE IF NOT EXISTS waec_audit (
                                          id          UUID PRIMARY KEY,
                                          order_id    UUID,
                                          action      VARCHAR(30)  NOT NULL,
    actor_id    UUID,
    actor_email VARCHAR(254),
    detail      VARCHAR(500),
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
    );

CREATE INDEX IF NOT EXISTS idx_waec_audit_order ON waec_audit (order_id, created_at DESC);
