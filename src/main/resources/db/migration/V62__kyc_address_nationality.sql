-- The sender's nationality and address are needed for sending money abroad (pawaPay). They are now collected once, in the KYC form.
ALTER TABLE kyc_submissions ADD COLUMN IF NOT EXISTS nationality VARCHAR(3);
ALTER TABLE kyc_submissions ADD COLUMN IF NOT EXISTS address_line VARCHAR(120);
ALTER TABLE kyc_submissions ADD COLUMN IF NOT EXISTS city VARCHAR(80);
ALTER TABLE kyc_submissions ADD COLUMN IF NOT EXISTS postal_code VARCHAR(12);
