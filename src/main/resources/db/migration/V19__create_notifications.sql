CREATE TABLE IF NOT EXISTS notifications (
                                             id                    UUID PRIMARY KEY,
                                             user_id               UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    type                  VARCHAR(40) NOT NULL,
    title                 VARCHAR(200) NOT NULL,
    message               VARCHAR(2000) NOT NULL,
    transaction_id        UUID,
    transaction_reference VARCHAR(100),
    is_read               BOOLEAN NOT NULL DEFAULT FALSE,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now()
    );

CREATE INDEX IF NOT EXISTS idx_notifications_user_created
    ON notifications (user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_notifications_user_unread
    ON notifications (user_id, is_read)
    WHERE is_read = FALSE;