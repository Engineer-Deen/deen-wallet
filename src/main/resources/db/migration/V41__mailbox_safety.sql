-- Mailbox safety: one row per send attempt (no double sends) and a failure counter for the inbox check.
ALTER TABLE mail_messages ADD COLUMN IF NOT EXISTS request_id UUID;
CREATE UNIQUE INDEX IF NOT EXISTS uq_mail_request_id ON mail_messages (request_id);
ALTER TABLE mail_state ADD COLUMN IF NOT EXISTS fail_count INT NOT NULL DEFAULT 0;
