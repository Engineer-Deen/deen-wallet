
-- V2__add_transaction_fees.sql
-- Adds the 3% Deen Wallet conversion fee, split into three 1% components
-- (Monime deposit fee, Deen Wallet's own revenue, Monime withdrawal fee)
-- for internal accounting and reconciliation. The frontend only ever
-- shows the combined fee_value as one line.

ALTER TABLE transactions
    ADD COLUMN fee_value                     BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN monime_deposit_fee_value      BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN deen_wallet_fee_value         BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN monime_withdrawal_fee_value   BIGINT NOT NULL DEFAULT 0,
    ADD COLUMN total_charged_value           BIGINT NOT NULL DEFAULT 0;
