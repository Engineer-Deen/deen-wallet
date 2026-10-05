-- Remove the old global email uniqueness.
-- This allows a USER and an ADMIN/SUPER_ADMIN to share the same email address.
ALTER TABLE users
DROP CONSTRAINT IF EXISTS users_email_key;

-- A normal USER email must remain unique among USER accounts.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_user_email
    ON users (email)
    WHERE role = 'USER';

-- An administrator email must remain unique among ADMIN/SUPER_ADMIN accounts.
CREATE UNIQUE INDEX IF NOT EXISTS uq_users_admin_email
    ON users (email)
    WHERE role IN ('ADMIN', 'SUPER_ADMIN');