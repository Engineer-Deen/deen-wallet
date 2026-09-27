
-- Add role column to users table
ALTER TABLE users ADD COLUMN IF NOT EXISTS role VARCHAR(50) DEFAULT 'USER';

-- Set super admin role for the main admin user
UPDATE users SET role = 'SUPER_ADMIN' WHERE email = 'abduldeenkamara06@gmail.com';

-- Update admin user details (if needed)
UPDATE users SET
                 full_name = 'Abdul Deen Kamara',
                 username = 'ingr.deen',
                 phone = '+23274132162',
                 password_hash = '$2a$10$N.zmdr9k7uOCQb376NoUnuTJ8iAt6Z5EHsM8lE9lBOsl7iKTVKIiW',
                 account_number = 'ADMIN DEEN',
                 email_verified = true
WHERE email = 'abduldeenkamara06@gmail.com';