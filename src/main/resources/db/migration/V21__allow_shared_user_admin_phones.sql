ALTER TABLE users
DROP CONSTRAINT IF EXISTS users_phone_key;

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_user_phone
    ON users (phone)
    WHERE role = 'USER';

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_admin_phone
    ON users (phone)
    WHERE role IN ('ADMIN', 'SUPER_ADMIN');