-- Which payment-service reference belongs to which payout, so the payouts list can show the DeenWallet reference.
CREATE TABLE IF NOT EXISTS wallet_payout_refs (
    payout_id     TEXT PRIMARY KEY,
    txn_reference TEXT,
    attempts      INT NOT NULL DEFAULT 0,
    last_try      TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS idx_wallet_payout_refs_ref ON wallet_payout_refs (txn_reference) WHERE txn_reference IS NOT NULL;
