-- Main super admin (cannot be controlled), super admins created by the main one, and per-admin section access.
ALTER TABLE users ADD COLUMN IF NOT EXISTS main_admin BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN IF NOT EXISTS denied_sections VARCHAR(300) NOT NULL DEFAULT '';
ALTER TABLE users ADD COLUMN IF NOT EXISTS created_by UUID;
-- every super admin that exists today is a main super admin
UPDATE users SET main_admin = TRUE WHERE role = 'SUPER_ADMIN';
