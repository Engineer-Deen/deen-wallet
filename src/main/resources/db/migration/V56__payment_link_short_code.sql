-- A 6-digit number for every payment link, so a payer can type it on a USSD keypad (letters are hard to type there).
ALTER TABLE payment_requests ADD COLUMN IF NOT EXISTS short_code VARCHAR(6);

DO $$
DECLARE r RECORD;
DECLARE c TEXT;
BEGIN
    FOR r IN SELECT id FROM payment_requests WHERE short_code IS NULL LOOP
        LOOP
            c := (100000 + floor(random() * 900000))::int::text;
            EXIT WHEN NOT EXISTS (SELECT 1 FROM payment_requests WHERE short_code = c);
        END LOOP;
        UPDATE payment_requests SET short_code = c WHERE id = r.id;
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS ux_payment_requests_short_code ON payment_requests (short_code) WHERE short_code IS NOT NULL;
