# DeenWallet Transfer - project map

Spring Boot 4 / Java 21 / PostgreSQL. The customer app and the admin app are Android apps (Capacitor) that load the web
pages in `src/main/resources/static` from the server, so most screens are changed here, not in the Android projects.

## Top level

| Path | What it is |
|---|---|
| `pom.xml` | The build (Maven). |
| `src/main/java/com/glr/deenwallet/` | The backend code, one folder per feature (below). |
| `src/main/resources/application.yml` | All settings. Each one reads an environment variable, see ENVIRONMENT.md. |
| `src/main/resources/db/migration/` | Database changes, numbered `V1__...sql`, `V2__...`. They run once, in order, when the app starts. Never edit one that has already run: add a new one with the next number. |
| `src/main/resources/static/` | The web pages and scripts (below). |
| `src/main/resources/email/` | The logo used in emails. |
| `docs/` | These notes. |
| `_old-backups/` | Old `*.bak` leftovers moved out of the code. Safe to delete when you are sure you do not need them. |

## Backend: one folder per feature

Inside a folder the names tell you the job: `*Controller` = web endpoints, `*Service` = the logic, `*Repository` = database
access, plain nouns (`User`, `WaecOrder`...) = database tables, `*Job` = tasks that run on a schedule.

| Folder | What it does |
|---|---|
| `account` | Look up an account by number or phone. |
| `admin` | Admin app: login, passcode and passkeys, users, stats and metrics, payout actions, and which sections each admin may use. |
| `audit` | The admin audit log (who did what, and when). |
| `auth` | Customer login, devices and sessions, biometric login, protection against repeated wrong logins. |
| `config` | Security rules, CORS, rate limits, app update endpoints, Firebase, creating the first admin, error handling. |
| `crypto` | "Send to a crypto wallet" through HoneyCoin. Switched off unless CRYPTO_ENABLED=true. |
| `email` | Sending emails and their templates. |
| `kyc` | ID verification, encrypted documents, and the daily limits that depend on verification. |
| `mailbox` | The support inbox: reading and sending customer emails from the admin app. |
| `maintenance` | Scheduled clean-up: daily stats, data retention, archiving old transactions. |
| `monime` | The Monime connection: collecting payments, payouts, the bank list, and card checkout. |
| `monitoring` | Error logs, security scanning and blocked IPs, reports from the server agent, database metrics. |
| `notification` | In-app notifications, phone push, and the admin alerts (pop-ups). |
| `otp` | Email one-time codes. |
| `paylink` | Payment links: the owner side, the public pay page, and the admin view. |
| `photo` | Profile photos. |
| `provider` | Mobile money providers (Orange Money, Africell Money). |
| `recipient` | Saved recipients. |
| `safety` | Safety rails around money: the emergency freeze and withdrawal limits. |
| `settings` | Settings kept in the database (for example the support WhatsApp number). |
| `support` | Support staff view of a customer transaction. |
| `transaction` | The core: transfers, bank transfers, fees, payouts, refunds and the payment confirmation flow. |
| `user` | Users, profiles, who is online now, account deletion. |
| `waec` | WAEC result pins: stock, orders, email delivery, admin tools. |
| `wallet` | The admin Wallet screen: Monime balance, withdrawals, transfers to admin wallets, reconciliation. |

## Web pages (`src/main/resources/static`)

| File | What it is |
|---|---|
| `index.html` | The customer app (all customer screens). |
| `admin.html` | The admin app (all admin screens). |
| `pay.html` | The public page someone sees when they open a payment link. |
| `auth.html`, `welcome.html` | Sign-in and first-open pages. |
| `transactions.html` | Receipts and history. |
| `terms.html`, `privacy.html` | Legal pages. |
| `reset-password.html`, `reset-pin.html`, `admin-reset-password.html`, `admin-reset-pin.html` | Reset pages opened from emails. |
| `deenwallet-client.js` | Shared script: talking to the server, login tokens, push. |
| `qr.js` | QR code generator for payment links. |
| `firebase-messaging-sw.js` | Push service worker. It must stay at the top of this folder. |
| `config.js`, `config.example.js` | Which server the pages talk to. |
| `dw-agent.py` | The small monitoring agent that other servers download from `/dw-agent.py`. |
| `assets/` | Logos and images. |

Important: every page is reachable at the top of the website address (for example `https://api.deenwallapp.com/index.html`).
The Android apps, emails and nginx use those exact addresses, so **do not move or rename these files** without changing all of
them together.

## How to change things safely

- A new database table or column: add a new migration file with the next `V` number. Never edit an old one.
- A new setting: read it with `@Value("${NAME:default}")` and add it to ENVIRONMENT.md.
- A new screen: add it to the matching page (`index.html` for customers, `admin.html` for admins).
- After any change that touches Java files, rebuild and test before putting it on the server (see OPERATIONS.md).