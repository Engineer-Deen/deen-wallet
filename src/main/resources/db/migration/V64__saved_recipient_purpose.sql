-- Each service keeps its own saved recipients: Send Money (purpose NULL), Withdraw from Bank (BANK_WITHDRAW)
-- and Withdraw from Card (CARD_WITHDRAW). A number saved in one place no longer shows up in the others.
ALTER TABLE saved_recipients ADD COLUMN IF NOT EXISTS purpose VARCHAR(20);

-- The same number may now be saved once per service, so drop the old "one per number" rule
-- (whatever its generated name is) and replace it with one that includes the service.
DO $$
DECLARE
    c text;
BEGIN
    FOR c IN
        SELECT con.conname
        FROM pg_constraint con
        JOIN pg_class rel ON rel.oid = con.conrelid
        WHERE rel.relname = 'saved_recipients'
          AND con.contype = 'u'
          AND (SELECT array_agg(att.attname::text ORDER BY att.attname)
               FROM unnest(con.conkey) AS k
               JOIN pg_attribute att ON att.attrelid = con.conrelid AND att.attnum = k)
              = ARRAY['phone_number', 'provider_id', 'user_id']
    LOOP
        EXECUTE format('ALTER TABLE saved_recipients DROP CONSTRAINT %I', c);
    END LOOP;
END $$;

CREATE UNIQUE INDEX IF NOT EXISTS uq_saved_mobile_recipient
    ON saved_recipients (user_id, phone_number, provider_id, COALESCE(purpose, 'SEND'))
    WHERE phone_number IS NOT NULL;
