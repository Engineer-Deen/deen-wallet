-- Support mailbox: customer emails read from the support inbox (IMAP) and replies sent through QueenSMTP.
CREATE TABLE IF NOT EXISTS mail_messages (
    id                  UUID PRIMARY KEY,
    thread_id           UUID          NOT NULL,
    direction           VARCHAR(3)    NOT NULL,            -- IN (from a customer) or OUT (sent by an admin)
    rfc_message_id      VARCHAR(300),                      -- the email's own Message-ID (IN only); unique so nothing is stored twice
    imap_uid            BIGINT,
    counterparty_email  VARCHAR(254)  NOT NULL,            -- the customer: sender of IN, recipient of OUT
    counterparty_name   VARCHAR(200)  NOT NULL DEFAULT '',
    subject             VARCHAR(300)  NOT NULL DEFAULT '',
    body_text           TEXT          NOT NULL DEFAULT '',
    attachments         INT           NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now(),
    is_read             BOOLEAN       NOT NULL DEFAULT FALSE,
    sent_by_id          UUID,
    sent_by_name        VARCHAR(200)  NOT NULL DEFAULT '',
    provider_message_id VARCHAR(80),
    status              VARCHAR(12)   NOT NULL DEFAULT 'RECEIVED',   -- RECEIVED, QUEUED, SENT, FAILED
    error               VARCHAR(300)  NOT NULL DEFAULT '',
    CONSTRAINT uq_mail_rfc_message_id UNIQUE (rfc_message_id)
);
CREATE INDEX IF NOT EXISTS idx_mail_thread  ON mail_messages (thread_id, created_at);
CREATE INDEX IF NOT EXISTS idx_mail_created ON mail_messages (created_at DESC);
CREATE INDEX IF NOT EXISTS idx_mail_unread  ON mail_messages (direction, is_read) WHERE direction = 'IN' AND is_read = FALSE;

CREATE TABLE IF NOT EXISTS mail_state (
    id            INT PRIMARY KEY,
    imap_validity BIGINT       NOT NULL DEFAULT 0,
    last_uid      BIGINT       NOT NULL DEFAULT 0,
    last_poll     TIMESTAMPTZ,
    last_error    VARCHAR(300) NOT NULL DEFAULT ''
);
INSERT INTO mail_state (id) VALUES (1) ON CONFLICT (id) DO NOTHING;
