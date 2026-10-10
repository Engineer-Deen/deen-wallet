-- V27: protect transaction creation retries and prevent one Monime resource
-- from being attached to more than one DeenWallet transaction.

ALTER TABLE transactions
    ADD COLUMN IF NOT EXISTS client_request_key VARCHAR(64);

CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_user_client_request_key
    ON transactions (user_id, client_request_key)
    WHERE client_request_key IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_monime_payment_code_id
    ON transactions (monime_payment_code_id)
    WHERE monime_payment_code_id IS NOT NULL;

CREATE UNIQUE INDEX IF NOT EXISTS uq_transactions_monime_payout_id
    ON transactions (monime_payout_id)
    WHERE monime_payout_id IS NOT NULL;
