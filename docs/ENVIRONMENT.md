# Settings (environment variables)

Set these in `/var/www/deenwallet/deenwallet.env` on the server, then restart: `sudo systemctl restart deenwallet`.
A line looks like `NAME=value`. If a setting is not in the file, the default below is used.

Values marked "(secret)" are never listed here. Keep them private.

Some limits are set with names like `APP_WALLET_MAX_SINGLE_MINOR` and `APP_WALLET_MAX_DAILY_MINOR` (see the `safety` folder).
Amounts ending in `_MINOR` are in the smallest unit: 100 = SLE 1.00.

| Name | Default | Used in |
|---|---|---|
| ADMIN_ANDROID_UPDATE_APK_PATH | /var/www/deenwallet/admin-downloads/DeenWallet-Admin.apk | application.yml |
| ADMIN_ANDROID_UPDATE_DOWNLOAD_URL | https://api.deenwallapp.com/api/app/admin-android-update/download | application.yml |
| ADMIN_ANDROID_UPDATE_FORCE | false | application.yml |
| ADMIN_ANDROID_UPDATE_METADATA_PATH | /var/www/deenwallet/admin-downloads/admin-android-update.json | application.yml |
| ADMIN_ANDROID_UPDATE_RELEASE_NOTES | Added faster responsiveness and performance | application.yml |
| ADMIN_ANDROID_UPDATE_VERSION_CODE | 6 | application.yml |
| ADMIN_ANDROID_UPDATE_VERSION_NAME | 1.6 | application.yml |
| ADMIN_EMAIL | (empty) | application.yml |
| ADMIN_FORCE_RESET | false | application.yml |
| ADMIN_PASSWORD | (secret - set on the server only) | application.yml |
| ADMIN_PHONE | (empty) | application.yml |
| APP_ANDROID_UPDATE_APK_PATH | downloads/DeenWallet.apk | application.yml |
| APP_ANDROID_UPDATE_DOWNLOAD_URL | https://deenwallapp.com/downloads/DeenWallet.apk | application.yml |
| APP_ANDROID_UPDATE_FORCE | false | application.yml |
| APP_ANDROID_UPDATE_RELEASE_NOTES | Added faster responsiveness and improve the usage of our services | application.yml |
| APP_ANDROID_UPDATE_VERSION_CODE | 5 | application.yml |
| APP_ANDROID_UPDATE_VERSION_NAME | 1.4 | application.yml |
| APP_ERRORS_TRUST_FORWARDED_FOR | false | application.yml |
| APP_RELEASE_ID | ${random.uuid} | application.yml |
| CARD_PAYMENTS_ENABLED | false | PaymentRequestService |
| CORS_ALLOWED_ORIGINS | http://localhost:8081,http://127.0.0.1:8081,https://deenwallapp.com,https://api.deenwallapp.com | application.yml |
| CRYPTO_DAILY_LIMIT_MINOR | 500000 | CryptoOrderService |
| CRYPTO_ENABLED | false | CryptoOrderService |
| CRYPTO_MAX_MINOR | 200000 | CryptoOrderService |
| CRYPTO_MIN_MINOR | 500 | CryptoOrderService |
| CRYPTO_REQUIRE_KYC | true | CryptoOrderService |
| DATA_RETENTION_DAYS | 30 | application.yml |
| DATA_RETENTION_ENABLED | true | application.yml |
| DB_PASSWORD | (secret - set on the server only) | application.yml |
| DB_URL | jdbc:postgresql://localhost:5432/deenwallet_db | application.yml |
| DB_USERNAME | (empty) | application.yml |
| FIREBASE_SERVICE_ACCOUNT_PATH | (empty) | application.yml |
| FIREBASE_WEB_CONFIG_PATH | ./secrets/firebase-web-config.json | application.yml |
| FRONTEND_BASE_URL | https://deenwallapp.com | application.yml |
| HONEYCOIN_API_BASE | https://api-v2.honeycoin.app/api | HoneyCoinClient |
| HONEYCOIN_API_KEY | (secret - set on the server only) | HoneyCoinClient |
| HONEYCOIN_AUTH_URL | (empty) | HoneyCoinClient |
| HONEYCOIN_CRYPTO_BASE | https://crypto.honeycoin.app/api | HoneyCoinClient |
| HONEYCOIN_PUBLIC_KEY | (secret - set on the server only) | HoneyCoinClient |
| HONEYCOIN_SANDBOX | true | HoneyCoinClient |
| HONEYCOIN_WEBHOOK_SECRET | (secret - set on the server only) | CryptoOrderService |
| INBOX_IMAP_HOST | imap.gmail.com | MailboxImap |
| INBOX_IMAP_PASSWORD | (secret - set on the server only) | MailboxImap, MailboxService |
| INBOX_IMAP_PORT | 993 | MailboxImap |
| INBOX_IMAP_USER | (empty) | MailboxImap, MailboxService |
| INBOX_SMTP_DAILY_LIMIT | 400 | MailboxService |
| INBOX_SMTP_ENABLED | true | MailboxSmtp |
| INBOX_SMTP_FROM | (empty) | MailboxSmtp |
| INBOX_SMTP_HOST | smtp.gmail.com | MailboxSmtp |
| INBOX_SMTP_PASSWORD | (secret - set on the server only) | MailboxSmtp |
| INBOX_SMTP_PORT | 587 | MailboxSmtp |
| INBOX_SMTP_USER | ${INBOX_IMAP_USER:} | MailboxSmtp |
| JWT_SECRET | (secret - set on the server only) | application.yml |
| KYC_BASIC_BANK_DAILY_MINOR | 50000 | KycLimitService |
| KYC_BASIC_MOBILE_MONEY_DAILY_MINOR | 100000 | KycLimitService |
| KYC_ENCRYPTION_KEY | (secret - set on the server only) | KycCrypto |
| KYC_VERIFIED_DAILY_MINOR | 10000000 | KycLimitService |
| MAIL_FROM_EMAIL | no-reply@deenwallapp.com | application.yml |
| MAIL_FROM_NAME | DeenWallet Transfer | application.yml |
| MAIL_HOST | smtp.queensmtp.com | application.yml |
| MAIL_PASSWORD | (secret - set on the server only) | application.yml |
| MAIL_PORT | 587 | application.yml |
| MAIL_USERNAME | (empty) | application.yml |
| MONIME_ACCESS_TOKEN | (secret - set on the server only) | application.yml |
| MONIME_DEFAULT_FINANCIAL_ACCOUNT_ID | (empty) | application.yml |
| MONIME_SPACE_ID | (empty) | application.yml |
| MONIME_WEBHOOK_SECRET | (secret - set on the server only) | application.yml |
| MONITOR_AGENT_TOKEN | (secret - set on the server only) | AgentReportService |
| ORACLE_FREE_OUTBOUND_GB | 10240 | UsageService |
| ORACLE_FREE_STORAGE_GB | 200 | UsageService |
| ORACLE_PAYG_START | (empty) | UsageService |
| ORACLE_PLAN | free | UsageService |
| ORACLE_PRICE_PER_GB_OUTBOUND | 0.0085 | UsageService |
| ORACLE_PRICE_PER_GB_STORAGE_MONTH | 0.0255 | UsageService |
| PAY_LINK_BASE_URL | https://pay.deenwallapp.com | PaymentRequestService |
| QUEENSMTP_API_KEY | (secret - set on the server only) | QueenSmtpClient |
| QUEENSMTP_BASE_URL | https://queensmtp.com/v1 | QueenSmtpClient |
| RATE_LIMIT_TRUST_FORWARDED_FOR | false | application.yml |
| SERVER_PORT | 8082 | application.yml |
| SUPPORT_WHATSAPP | +23280613600 | application.yml |
| TRANSACTION_ARCHIVE_AFTER_DAYS | 180 | application.yml |
| TRANSACTION_ARCHIVE_ENABLED | true | application.yml |
| WAEC_MAX_QUANTITY | 5 | application.yml |
| WAEC_PIN_ENCRYPTION_KEY | (secret - set on the server only) | application.yml |
| WAEC_PIN_FEE_RATE | 0.02 | application.yml |
| WAEC_PIN_PRICE_MINOR | 3500 | application.yml |