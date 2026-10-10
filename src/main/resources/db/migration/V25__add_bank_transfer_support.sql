-- V25: add Bank Transfer as a second customer service while preserving
-- the existing Mobile Money transaction shape.

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS service_type VARCHAR(30) NOT NULL DEFAULT 'MOBILE_MONEY',
    ADD COLUMN IF NOT EXISTS destination_bank_provider_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS destination_bank_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS destination_bank_account_number VARCHAR(64),
    ADD COLUMN IF NOT EXISTS destination_bank_holder_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS destination_bank_kyc_verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE transactions
    ALTER COLUMN destination_provider_id DROP NOT NULL,
    ALTER COLUMN destination_phone DROP NOT NULL;

ALTER TABLE transactions_archive
    ADD COLUMN IF NOT EXISTS service_type VARCHAR(30) NOT NULL DEFAULT 'MOBILE_MONEY',
    ADD COLUMN IF NOT EXISTS destination_bank_provider_id VARCHAR(64),
    ADD COLUMN IF NOT EXISTS destination_bank_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS destination_bank_account_number VARCHAR(64),
    ADD COLUMN IF NOT EXISTS destination_bank_holder_name VARCHAR(200),
    ADD COLUMN IF NOT EXISTS destination_bank_kyc_verified BOOLEAN NOT NULL DEFAULT FALSE;

ALTER TABLE transactions_archive
    ALTER COLUMN destination_provider_id DROP NOT NULL,
    ALTER COLUMN destination_phone DROP NOT NULL;

CREATE INDEX IF NOT EXISTS idx_transactions_service_type
    ON transactions (service_type);
