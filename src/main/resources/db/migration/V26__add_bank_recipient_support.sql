-- Add saved bank recipients without changing existing mobile-money recipients.

ALTER TABLE saved_recipients
    ADD COLUMN IF NOT EXISTS recipient_type VARCHAR(20) NOT NULL DEFAULT 'MOBILE_MONEY',
    ADD COLUMN IF NOT EXISTS bank_provider_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS bank_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_account_number VARCHAR(64),
    ADD COLUMN IF NOT EXISTS bank_holder_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS bank_kyc_verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE saved_recipients
    ALTER COLUMN phone_number DROP NOT NULL,
    ALTER COLUMN provider_id DROP NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_saved_bank_recipient
    ON saved_recipients (user_id, bank_provider_id, bank_account_number)
    WHERE recipient_type = 'BANK';

CREATE INDEX IF NOT EXISTS idx_saved_recipients_user_type
    ON saved_recipients (user_id, recipient_type);
