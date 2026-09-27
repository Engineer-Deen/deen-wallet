
-- ==============================================================
-- V9: Add transaction_code column
-- Format: DW-YYYYMMDD-XXXXX (e.g., DW-20260905-A7B3C)
-- ==============================================================

-- Add transaction_code column
ALTER TABLE transactions ADD COLUMN IF NOT EXISTS transaction_code VARCHAR(50);

-- Update existing transactions with generated codes
UPDATE transactions
SET transaction_code = 'DW-' || TO_CHAR(created_at, 'YYYYMMDD') || '-' ||
                       UPPER(SUBSTRING(MD5(RANDOM()::TEXT) FROM 1 FOR 5))
WHERE transaction_code IS NULL;

-- Make it NOT NULL
ALTER TABLE transactions ALTER COLUMN transaction_code SET NOT NULL;

-- Add unique constraint
ALTER TABLE transactions ADD CONSTRAINT uk_transaction_code UNIQUE (transaction_code);

-- Add index for faster lookups
CREATE INDEX IF NOT EXISTS idx_transactions_transaction_code ON transactions(transaction_code);

-- Add comment for documentation
COMMENT ON COLUMN transactions.transaction_code IS 'Human-readable transaction ID (e.g., DW-20260905-A7B3C)';