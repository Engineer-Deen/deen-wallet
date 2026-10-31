-- Recovery passcodes: an admin can keep up to 5 of them as a second way to get back in (besides the email code).
CREATE TABLE IF NOT EXISTS admin_passcodes (
    id            UUID         PRIMARY KEY,
    admin_id      UUID         NOT NULL,
    label         VARCHAR(40)  NOT NULL,
    passcode_hash VARCHAR(100) NOT NULL,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    last_used_at  TIMESTAMPTZ
);
CREATE INDEX IF NOT EXISTS idx_admin_passcodes_admin ON admin_passcodes (admin_id);
