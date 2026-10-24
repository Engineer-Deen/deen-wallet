# DeenWallet Transfer - new admin Home page
#
# Run from the project root (the folder that contains pom.xml):
#     powershell -ExecutionPolicy Bypass -File .\admin-home.ps1
#
# What changes
#   - Home shows: Total Users, Verified Users (with "ID verified" count), Failed payouts (red when above 0),
#     Transactions today, Volume today, Fees this month, plus tap-to-open alerts for failed payouts,
#     KYC requests waiting and low / empty WAEC stock (alerts only appear when something needs you).
#   - New table daily_stats (migration V34) + DailyStatsJob: keeps per-day totals (no customer details) so the
#     month figures survive the 30-day record wipe. It runs at 03:30 and once at start-up.
#   - New endpoint /api/admin/home (admins only, like every /api/admin/** endpoint).
#   - admin.html is PATCHED (three small parts), not replaced.
# Everything is checked first; nothing is written unless all parts fit. A .bak copy is saved of admin.html.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = (Get-Location).Path
$em = [string][char]0x2014

$ctl = 'src\main\java\com\glr\deenwallet\admin\AdminHomeController.java'
if (Test-Path (Join-Path $root $ctl)) {
  Write-Host 'The new admin Home is already installed - nothing to do.' -ForegroundColor Yellow
  exit 0
}
foreach ($must in @('pom.xml', 'src\main\resources\static\admin.html', 'src\main\resources\db\migration', 'src\main\java\com\glr\deenwallet\maintenance', 'src\main\java\com\glr\deenwallet\admin')) {
  if (-not (Test-Path (Join-Path $root $must))) { throw "MISSING $must - run this from the folder that contains pom.xml" }
}
if (Test-Path (Join-Path $root 'src\main\resources\db\migration\V34__daily_stats.sql')) { throw 'V34__daily_stats.sql already exists - nothing changed.' }
$others = Get-ChildItem (Join-Path $root 'src\main\resources\db\migration') -Filter 'V34__*' -ErrorAction SilentlyContinue
if ($others) { throw ('Another V34 migration already exists (' + $others[0].Name + '). Tell me and I will renumber this one. Nothing changed.') }

$adminRel = 'src\main\resources\static\admin.html'
$raw = [System.IO.File]::ReadAllText((Join-Path $root $adminRel), $utf8)
$crlf = $raw.Contains("`r`n")
$t = $raw.Replace("`r`n", "`n")

function Edit([string]$old, [string]$new, [string]$what) {
  $old = $old.Replace('{{EM}}', $em).Replace("`r`n", "`n").TrimEnd("`n")
  $new = $new.Replace('{{EM}}', $em).Replace("`r`n", "`n").TrimEnd("`n")
  $i = $script:t.IndexOf($old, [StringComparison]::Ordinal)
  if ($i -lt 0) { throw "admin.html: the $what part was not found (your admin.html differs from the expected version). Nothing changed." }
  if ($script:t.IndexOf($old, $i + 1, [StringComparison]::Ordinal) -ge 0) { throw "admin.html: the $what part was found twice. Nothing changed." }
  $script:t = $script:t.Substring(0, $i) + $new + $script:t.Substring($i + $old.Length)
}

$cssOld = @'
        .stat-card.warning .number {
            color: var(--warning-text);
        }
'@
$cssNew = @'
        .stat-card.warning .number {
            color: var(--warning-text);
        }

        .stat-card.danger .number {
            color: var(--error);
        }

        .stat-card .sub {
            font-size: 11px;
            color: var(--muted);
            margin-top: 3px;
        }

        .stat-card.clickable {
            cursor: pointer;
        }

        .home-alerts {
            display: flex;
            flex-direction: column;
            gap: 8px;
            margin-bottom: 12px;
        }

        .home-alerts:empty {
            display: none;
        }

        .home-alert {
            display: flex;
            align-items: center;
            justify-content: space-between;
            gap: 10px;
            width: 100%;
            text-align: left;
            padding: 12px 14px;
            border-radius: 12px;
            border: 1px solid var(--border);
            background: var(--warning-bg);
            color: var(--warning-text);
            font-size: 13px;
            font-weight: 700;
            cursor: pointer;
        }

        .home-alert.danger {
            background: #FDECEA;
            border-color: #F3C6C1;
            color: var(--error);
        }

        .home-alert .go {
            font-size: 18px;
            line-height: 1;
        }
'@
$htmlOld = @'
            <div class="stats-grid">
                <div class="stat-card primary">
                    <div class="number" id="stat-users">{{EM}}</div>
                    <div class="label">Total Users</div>
                </div>
                <div class="stat-card success">
                    <div class="number" id="stat-verified">{{EM}}</div>
                    <div class="label">Verified Users</div>
                </div>
                <div class="stat-card primary">
                    <div class="number" id="stat-transactions">{{EM}}</div>
                    <div class="label">Transactions</div>
                </div>
                <div class="stat-card warning">
                    <div class="number" id="stat-volume">{{EM}}</div>
                    <div class="label">Volume (SLE)</div>
                </div>
            </div>
'@
$htmlNew = @'
            <div class="home-alerts" id="home-alerts"></div>
            <div class="stats-grid">
                <div class="stat-card primary">
                    <div class="number" id="stat-users">{{EM}}</div>
                    <div class="label">Total Users</div>
                </div>
                <div class="stat-card success">
                    <div class="number" id="stat-verified">{{EM}}</div>
                    <div class="label">Verified Users</div>
                    <div class="sub" id="stat-verified-sub">&nbsp;</div>
                </div>
                <div class="stat-card clickable" id="card-failed-payouts">
                    <div class="number" id="stat-failed">{{EM}}</div>
                    <div class="label">Failed payouts</div>
                    <div class="sub" id="stat-failed-sub">waiting for you</div>
                </div>
                <div class="stat-card primary">
                    <div class="number" id="stat-today-tx">{{EM}}</div>
                    <div class="label">Transactions today</div>
                </div>
                <div class="stat-card warning">
                    <div class="number" id="stat-today-volume">{{EM}}</div>
                    <div class="label">Volume today (SLE)</div>
                </div>
                <div class="stat-card success">
                    <div class="number" id="stat-month-fees">{{EM}}</div>
                    <div class="label">Fees this month (SLE)</div>
                    <div class="sub" id="stat-month-sub">&nbsp;</div>
                </div>
            </div>
'@
$jsOld = @'
                adminApiRequest('/api/admin/stats')
                    .then(function (stats) {
                        document.getElementById('stat-users').textContent = stats.totalUsers || 0;
                        document.getElementById('stat-verified').textContent = stats.verifiedUsers || 0;
                        document.getElementById('stat-transactions').textContent = stats.totalTransactions || 0;
                        document.getElementById('stat-volume').textContent = (stats.totalVolumeSle || 0).toFixed(2);
                        hide(loading);
                    })
'@
$jsNew = @'
                adminApiRequest('/api/admin/home')
                    .then(function (h) {
                        function money(v) { return Number(v || 0).toLocaleString(undefined, { minimumFractionDigits: 2, maximumFractionDigits: 2 }); }
                        function num(v) { return Number(v || 0).toLocaleString(); }
                        document.getElementById('stat-users').textContent = num(h.totalUsers);
                        document.getElementById('stat-verified').textContent = num(h.emailVerifiedUsers);
                        document.getElementById('stat-verified-sub').textContent = num(h.kycVerifiedUsers) + ' ID verified';
                        document.getElementById('stat-today-tx').textContent = num(h.todayTransactions);
                        document.getElementById('stat-today-volume').textContent = money(h.todayVolumeSle);
                        document.getElementById('stat-month-fees').textContent = money(h.monthFeesSle);
                        document.getElementById('stat-month-sub').textContent = num(h.monthTransactions) + ' transactions, ' + money(h.monthVolumeSle) + ' volume';

                        var failedCard = document.getElementById('card-failed-payouts');
                        var failedNum = document.getElementById('stat-failed');
                        failedNum.textContent = num(h.failedPayouts);
                        failedCard.classList.toggle('danger', (h.failedPayouts || 0) > 0);
                        document.getElementById('stat-failed-sub').textContent = (h.failedPayouts || 0) > 0 ? 'tap to open' : 'none waiting';
                        failedCard.onclick = function () { drawerNavigate('failed-payouts'); };

                        var alerts = [];
                        if ((h.failedPayouts || 0) > 0) alerts.push({ cls: 'danger', text: num(h.failedPayouts) + ' failed payout' + (h.failedPayouts === 1 ? '' : 's') + ' waiting for you', go: 'failed-payouts' });
                        if ((h.kycPending || 0) > 0) alerts.push({ cls: '', text: num(h.kycPending) + ' KYC request' + (h.kycPending === 1 ? '' : 's') + ' waiting for review', go: 'kyc' });
                        if (!h.waecOperational) alerts.push({ cls: 'danger', text: 'WAEC pins cannot be sold: encryption key is not set', go: 'waec-pins' });
                        else if ((h.waecAvailable || 0) === 0) alerts.push({ cls: 'danger', text: 'Out of stock: no WAEC pins left to sell', go: 'waec-pins' });
                        else if (h.waecAvailable <= 10) alerts.push({ cls: '', text: 'Low stock: only ' + num(h.waecAvailable) + ' WAEC pin' + (h.waecAvailable === 1 ? '' : 's') + ' left', go: 'waec-pins' });
                        var box = document.getElementById('home-alerts');
                        box.innerHTML = '';
                        alerts.forEach(function (a) {
                            var b = document.createElement('button');
                            b.type = 'button';
                            b.className = 'home-alert' + (a.cls ? ' ' + a.cls : '');
                            var t = document.createElement('span');
                            t.textContent = a.text;
                            var g = document.createElement('span');
                            g.className = 'go';
                            g.textContent = '\u203A';
                            b.appendChild(t); b.appendChild(g);
                            b.onclick = function () { drawerNavigate(a.go); };
                            box.appendChild(b);
                        });
                        hide(loading);
                    })
'@
Edit $cssOld $cssNew 'style'
Edit $htmlOld $htmlNew 'Home cards'
Edit $jsOld $jsNew 'Home loading code'

$f1 = @'
-- One row per day with only totals (no customer details). The nightly retention job deletes old
-- transactions, so this small table keeps the business history (count, volume, fees earned) that the
-- admin Home page shows for the month. Filled by DailyStatsJob before the wipe runs.
CREATE TABLE IF NOT EXISTS daily_stats (
    day           DATE        PRIMARY KEY,
    tx_count      BIGINT      NOT NULL DEFAULT 0,
    volume_value  BIGINT      NOT NULL DEFAULT 0,
    fees_value    BIGINT      NOT NULL DEFAULT 0,
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
'@
$f2 = @'
package com.glr.deenwallet.maintenance;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Keeps the daily_stats table (completed transactions, volume and DeenWallet fees per day, UTC) up to date,
 * so the admin Home numbers survive RecordRetentionJob deleting old transactions.
 *
 * Runs at 03:30 every night (before the 03:45 wipe) and once when the app starts.
 * It recomputes the last 7 days each time; when the table is empty it backfills everything that still exists.
 */
@Component
public class DailyStatsJob {

    private static final Logger log = LoggerFactory.getLogger(DailyStatsJob.class);
    private static final int RECOMPUTE_DAYS = 7;

    @PersistenceContext
    private EntityManager em;

    private final TransactionTemplate tx;

    public DailyStatsJob(TransactionTemplate tx) {
        this.tx = tx;
    }

    @EventListener(ApplicationReadyEvent.class)
    public void onStart() {
        snapshot();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void snapshot() {
        try {
            Integer rows = tx.execute(status -> {
                Number existing = (Number) em.createNativeQuery("SELECT COUNT(*) FROM daily_stats").getSingleResult();
                Instant from = existing.longValue() == 0
                        ? Instant.EPOCH
                        : LocalDate.now(ZoneOffset.UTC).minusDays(RECOMPUTE_DAYS).atStartOfDay().toInstant(ZoneOffset.UTC);
                return em.createNativeQuery(
                                "INSERT INTO daily_stats (day, tx_count, volume_value, fees_value, updated_at) "
                                        + "SELECT (x.created_at AT TIME ZONE 'UTC')::date, COUNT(*), "
                                        + "COALESCE(SUM(x.amount_value), 0), COALESCE(SUM(x.deen_wallet_fee_value), 0), now() "
                                        + "FROM (SELECT created_at, amount_value, deen_wallet_fee_value FROM transactions "
                                        + "      WHERE status = 'COMPLETED' AND created_at >= :from "
                                        + "      UNION ALL "
                                        + "      SELECT created_at, amount_value, deen_wallet_fee_value FROM transactions_archive "
                                        + "      WHERE status = 'COMPLETED' AND created_at >= :from) x "
                                        + "GROUP BY 1 "
                                        + "ON CONFLICT (day) DO UPDATE SET tx_count = EXCLUDED.tx_count, "
                                        + "volume_value = EXCLUDED.volume_value, fees_value = EXCLUDED.fees_value, updated_at = now()")
                        .setParameter("from", from)
                        .executeUpdate();
            });
            log.info("Daily stats: {} day rows written", rows);
        } catch (Exception e) {
            log.error("Daily stats snapshot failed", e);
        }
    }
}
'@
$f3 = @'
package com.glr.deenwallet.admin;

import com.glr.deenwallet.kyc.KycStatus;
import com.glr.deenwallet.kyc.KycSubmissionRepository;
import com.glr.deenwallet.user.UserRepository;
import com.glr.deenwallet.waec.WaecPinService;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Numbers for the admin Home page. Access: /api/admin/** is already restricted to ADMIN / SUPER_ADMIN.
 *
 * Today = completed transactions since 00:00 UTC. "This month" fees/volume = daily_stats for older days
 * (kept after old transactions are wiped) plus live figures for yesterday and today, which the nightly
 * snapshot may not have finished yet.
 */
@RestController
@RequestMapping("/api/admin/home")
public class AdminHomeController {

    private static final long CACHE_MS = 20_000;

    public record Home(long totalUsers, long emailVerifiedUsers, long kycVerifiedUsers,
                       long failedPayouts, long kycPending,
                       long waecAvailable, boolean waecOperational,
                       long todayTransactions, double todayVolumeSle,
                       long monthTransactions, double monthVolumeSle, double monthFeesSle) { }

    @PersistenceContext
    private EntityManager em;

    private final UserRepository users;
    private final KycSubmissionRepository kycSubmissions;
    private final WaecPinService waecPins;

    private volatile Home cached;
    private volatile long cachedAt;

    public AdminHomeController(UserRepository users, KycSubmissionRepository kycSubmissions, WaecPinService waecPins) {
        this.users = users;
        this.kycSubmissions = kycSubmissions;
        this.waecPins = waecPins;
    }

    @GetMapping
    public ResponseEntity<Home> home() {
        long now = System.currentTimeMillis();
        Home c = cached;
        if (c != null && now - cachedAt < CACHE_MS) return ResponseEntity.ok(c);
        Home fresh = compute();
        cached = fresh;
        cachedAt = now;
        return ResponseEntity.ok(fresh);
    }

    private Home compute() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        LocalDate monthStart = today.withDayOfMonth(1);
        LocalDate liveFromDay = today.minusDays(1);               // yesterday and today are read live
        Instant todayStart = today.atStartOfDay().toInstant(ZoneOffset.UTC);
        Instant liveFrom = (liveFromDay.isBefore(monthStart) ? monthStart : liveFromDay)
                .atStartOfDay().toInstant(ZoneOffset.UTC);

        long failedPayouts = ((Number) em.createNativeQuery(
                "SELECT COUNT(*) FROM transactions WHERE status IN ('PAYOUT_FAILED','REFUND_PENDING','NEEDS_REVIEW')")
                .getSingleResult()).longValue();

        Object[] t = (Object[]) em.createNativeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(amount_value), 0) FROM transactions "
                                + "WHERE status = 'COMPLETED' AND created_at >= :from")
                .setParameter("from", todayStart).getSingleResult();

        Object[] live = (Object[]) em.createNativeQuery(
                        "SELECT COUNT(*), COALESCE(SUM(amount_value), 0), COALESCE(SUM(deen_wallet_fee_value), 0) "
                                + "FROM transactions WHERE status = 'COMPLETED' AND created_at >= :from")
                .setParameter("from", liveFrom).getSingleResult();

        Object[] old = (Object[]) em.createNativeQuery(
                        "SELECT COALESCE(SUM(tx_count), 0), COALESCE(SUM(volume_value), 0), COALESCE(SUM(fees_value), 0) "
                                + "FROM daily_stats WHERE day >= :monthStart AND day < :liveDay")
                .setParameter("monthStart", monthStart)
                .setParameter("liveDay", liveFromDay.isBefore(monthStart) ? monthStart : liveFromDay)
                .getSingleResult();

        long monthTx = n(live[0]) + n(old[0]);
        long monthVol = n(live[1]) + n(old[1]);
        long monthFees = n(live[2]) + n(old[2]);

        return new Home(
                users.count(),
                users.countByEmailVerifiedTrue(),
                kycSubmissions.countByStatus(KycStatus.APPROVED),
                failedPayouts,
                kycSubmissions.countByStatus(KycStatus.PENDING),
                waecPins.summary().available(),
                waecPins.isOperational(),
                n(t[0]), n(t[1]) / 100.0,
                monthTx, monthVol / 100.0, monthFees / 100.0);
    }

    private static long n(Object o) {
        return o == null ? 0L : ((Number) o).longValue();
    }
}
'@
$newFiles = [ordered]@{
  'src\main\resources\db\migration\V34__daily_stats.sql' = $f1
  'src\main\java\com\glr\deenwallet\maintenance\DailyStatsJob.java' = $f2
  'src\main\java\com\glr\deenwallet\admin\AdminHomeController.java' = $f3
}

# ---------------------------------------------------------------- everything fits: write it
$adminPath = Join-Path $root $adminRel
Copy-Item $adminPath ($adminPath + '.bak') -Force
$out = $t
if ($crlf) { $out = $out.Replace("`n", "`r`n") }
[System.IO.File]::WriteAllText($adminPath, $out, $utf8)
Write-Host ('changed   ' + $adminRel) -ForegroundColor Green
foreach ($rel in $newFiles.Keys) {
  $p = Join-Path $root $rel
  [System.IO.File]::WriteAllText($p, ($newFiles[$rel].Replace("`r`n", "`n").TrimEnd("`n") + "`n"), $utf8)
  Write-Host ('new file  ' + $rel) -ForegroundColor Green
}
Write-Host ''
Write-Host 'Done. Rebuild: mvn clean package   (migration V34 runs on the next start)' -ForegroundColor Cyan
Write-Host 'Delete the admin.html.bak file before you commit it to git.' -ForegroundColor Cyan
