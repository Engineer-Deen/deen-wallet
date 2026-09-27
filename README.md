# Deen Wallet backend

Payment conversion backend for Good Luck Rahman Enterprise. Users convert between Orange Money, AfriMoney, and QMoney through the Monime API. This service does not hold customer funds; it orchestrates a Monime Payment Code (collect from sender) followed by a Monime Payout (send to recipient).

## Package layout

- `auth` — registration, login, JWT issuing
- `otp` — OTP generation, storage, verification (email now, SMS later)
- `user` — user entity, repository, profile lookups
- `recipient` — saved recipients per user
- `transaction` — conversion requests, status tracking
- `monime` — Monime API client (Payment Code, Payout, Provider KYC, webhook handling)
- `config` — security config, JWT filter, bean wiring
- `common` — shared exceptions, response wrappers

## Running locally

1. Create a PostgreSQL database named `deen_wallet`.
2. Set environment variables (or edit `application.yml` defaults directly for local dev):
   - `DB_URL`, `DB_USERNAME`, `DB_PASSWORD`
   - `MAIL_USERNAME`, `MAIL_PASSWORD` (for sending OTP emails)
   - `JWT_SECRET`
   - `MONIME_ACCESS_TOKEN`, `MONIME_SPACE_ID`, `MONIME_WEBHOOK_SECRET`
3. Run: `mvn spring-boot:run`

## Next steps

- Add Flyway migration scripts under `src/main/resources/db/migration` for the users, email_otps, saved_recipients, and transactions tables.
- Implement the entities and repositories in each package.
- Wire up the Monime client using the confirmed endpoints: Create Payment Code, Create Payout, Get Provider KYC, and the signed webhook receiver.
