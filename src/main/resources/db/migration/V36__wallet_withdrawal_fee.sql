-- Estimated Monime payout fee (1%) for each wallet withdrawal, in minor units. Older rows keep 0.
ALTER TABLE wallet_withdrawals ADD COLUMN IF NOT EXISTS fee_value BIGINT NOT NULL DEFAULT 0;
