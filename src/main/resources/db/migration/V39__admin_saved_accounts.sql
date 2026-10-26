-- Accounts an admin saved for withdrawals (each admin sees only their own).
CREATE TABLE IF NOT EXISTS admin_saved_accounts (
    id            UUID PRIMARY KEY,
    owner_user_id UUID         NOT NULL,
    method        VARCHAR(10)  NOT NULL,
    provider_id   VARCHAR(60)  NOT NULL,
    provider_name VARCHAR(120) NOT NULL DEFAULT '',
    destination   VARCHAR(40)  NOT NULL,
    holder_name   VARCHAR(200) NOT NULL DEFAULT '',
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_admin_saved_account UNIQUE (owner_user_id, method, provider_id, destination)
);
CREATE INDEX IF NOT EXISTS idx_admin_saved_owner ON admin_saved_accounts (owner_user_id, created_at DESC);
