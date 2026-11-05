# Running the server

## Where things are

| What | Where |
|---|---|
| App service | `deenwallet` (systemd). Listens on port 8082. |
| Settings file | `/var/www/deenwallet/deenwallet.env` (read when the service starts) |
| Web server | nginx. It sends api.deenwallapp.com, deenwallapp.com and pay.deenwallapp.com to the app. |
| Database | PostgreSQL, on its own server. The address is in `DB_URL` in the settings file. |
| Database backups | `/var/backups/deenwallet` on the app server (see below) |

## Putting a change on the server

1. Build on your computer: `mvn clean package`.
2. On the server keep the working jar: `sudo cp /var/www/deenwallet/deenwallet.jar /var/www/deenwallet/deenwallet.jar.prev`
3. Copy the new jar over, then restart: `sudo systemctl restart deenwallet`
4. Look at the log for errors: `sudo journalctl -u deenwallet -n 80`
5. If something is wrong, put the `.prev` jar back and restart.

A change that only touches files in `static/` also needs the rebuild (the pages are inside the jar).
Before restarting during the day, pause the alerts: `sudo dw-ops maint 15`.

## Monitoring and backups (`dw-ops`)

Installed by `deenwallet-ops.sh` (run `sudo bash deenwallet-ops.sh install`; running it again updates it and keeps your settings).

| Command | What it does |
|---|---|
| `sudo dw-ops status` | Backups, timers and any problem right now. |
| `sudo dw-ops backup-now` | Take a database backup now. |
| `sudo dw-ops restore-test` | Check the newest backup file. |
| `sudo dw-ops test-alert` | Send a test alert. |
| `sudo dw-ops maint 15` / `maint off` | Pause alerts for 15 minutes / turn them back on. |
| `sudo dw-ops logs` | Recent monitor and backup messages. |

- The monitor runs every minute and emails you when something breaks and when it is fixed.
- A backup runs every night at 03:15 (server time, UTC). It keeps 14 daily, 8 weekly and 6 monthly copies.
- Settings for both are in `/etc/deenwallet-ops.conf`.
- Backups are on the app server only until you set `OFFSITE_REMOTE` (a copy to another place with rclone).

## Restoring a backup (database loss)

The files are PostgreSQL custom-format dumps. To restore into an empty database:
`pg_restore -h DBHOST -U DBUSER -d DBNAME --no-owner /var/backups/deenwallet/FILE.dump`
Do this with the app stopped, and try it on a spare database first.

## Secrets

Keys, passwords and tokens belong in the settings file on the server only. Never put them in the code, in chat or in screenshots.