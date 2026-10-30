# DeenWallet Transfer - to-do list with commands, extra machines (your 2nd VM), and Oracle usage and cost
#
# Run from the SERVER project root (the folder that contains pom.xml):
#     powershell -ExecutionPolicy Bypass -File .\metrics3.ps1
#
# What it does (all inside Server Metrics):
#   * "To do on your servers (needs SSH)": one list of everything only you can do over SSH, grouped by machine, each with
#     the exact command and a Copy button: waiting security updates, restart needed, automatic updates off, SSH using
#     passwords, no fail2ban, risky open ports, Ubuntu reaching end of support, full disk and old logs to clean.
#   * Extra machines: a small agent (dw-agent.py, served by your server) runs on your second VM, reports every minute,
#     and that VM gets its own card (CPU, RAM, storage, load, updates with commands, SSH guessing, last seen). If it stops
#     reporting, the verdict at the top turns red. Setup steps with Copy buttons are in the tab ("Add another machine").
#   * Oracle usage and cost: data sent out and received, storage used and busiest moments are added up every minute and kept
#     month by month (it works now, while you are on the free tier). It compares with Oracle's free allowance and estimates
#     the extra cost; switch the plan to Pay As You Go later with two lines in the environment file. The exact bill stays in
#     the Oracle console.
# Database: migration V47 (a small monthly usage table).
# Receiving from other machines is OFF until you add MONITOR_AGENT_TOKEN (a long secret) to the server's environment file.
# Run metrics2.ps1 first.
# Everything is checked first; nothing is written unless it all fits. No backup files are created.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = (Get-Location).Path
if (-not (Test-Path (Join-Path $root 'pom.xml'))) { throw 'Run this from the folder that contains pom.xml.' }
$mig = 'src\main\resources\db\migration\V47__usage_monthly.sql'
if (Test-Path (Join-Path $root $mig)) { Write-Host 'Already applied - nothing to do.' -ForegroundColor Yellow; exit 0 }
if (-not (Test-Path (Join-Path $root 'src\main\resources\db\migration\V46__blocked_ips.sql'))) { throw 'Run metrics2.ps1 first (V46 is missing). Nothing changed.' }
$files = @{}
function Load-File([string]$rel) {
  $p = Join-Path $root $rel
  if (-not (Test-Path $p)) { throw "$rel was not found. Nothing changed." }
  $raw = [System.IO.File]::ReadAllText($p, $utf8)
  $script:files[$rel] = @{ path = $p; crlf = $raw.Contains("`r`n"); text = $raw.Replace("`r`n", "`n") }
}
function Edit-Text([string]$rel, [string]$old, [string]$new, [string]$what) {
  $old = $old.Replace("`r`n", "`n").TrimEnd("`n"); $new = $new.Replace("`r`n", "`n").TrimEnd("`n")
  $f = $script:files[$rel]
  $i = $f.text.IndexOf($old, [StringComparison]::Ordinal)
  if ($i -lt 0) { throw "${rel}: the '$what' part was not found (the file differs from the expected version). Nothing changed." }
  if ($f.text.IndexOf($old, $i + 1, [StringComparison]::Ordinal) -ge 0) { throw "${rel}: the '$what' part was found twice. Nothing changed." }
  $f.text = $f.text.Substring(0, $i) + $new + $f.text.Substring($i + $old.Length)
}
$newFiles = @{}
function Add-New([string]$rel, [string]$text) {
  if (Test-Path (Join-Path $root $rel)) { throw "$rel already exists. Nothing changed." }
  $script:newFiles[$rel] = $text.Replace("`r`n", "`n").TrimEnd("`n") + "`n"
}

Load-File 'src\main\java\com\glr\deenwallet\monitoring\SecurityScanService.java'
Load-File 'src\main\java\com\glr\deenwallet\config\SecurityConfig.java'
Load-File 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java'
Load-File 'src\main\resources\static\admin.html'
Load-File 'src\main\java\com\glr\deenwallet\monitoring\ServerMetricsService.java'
$o1 = @'
        items.sort(Comparator.comparingInt(m -> rank(String.valueOf(m.get("level")))));
'@
$n1 = @'
        extraChecks(items);

        items.sort(Comparator.comparingInt(m -> rank(String.valueOf(m.get("level")))));
'@
Edit-Text 'src\main\java\com\glr\deenwallet\monitoring\SecurityScanService.java' $o1 $n1 'extra call'
$o2 = @'
    private static int rank(String level) {
'@
$n2 = @'
    /** Housekeeping a person would otherwise have to remember: end of support, disk cleanup, old logs, leftover packages. */
    private void extraChecks(List<Map<String, Object>> items) {
        try {
            String id = "", ver = "";
            for (String l : SecurityScanSupport.readAll("/etc/os-release")) {
                if (l.startsWith("ID=")) id = l.substring(3).replace("\"", "").trim();
                if (l.startsWith("VERSION_ID=")) ver = l.substring(11).replace("\"", "").trim();
            }
            if ("ubuntu".equals(id)) {
                Map<String, LocalDate> eol = Map.of("18.04", LocalDate.of(2023, 5, 31), "20.04", LocalDate.of(2025, 5, 31),
                        "22.04", LocalDate.of(2027, 6, 1), "24.04", LocalDate.of(2029, 6, 1));
                LocalDate end = eol.get(ver);
                if (end != null) {
                    long days = java.time.temporal.ChronoUnit.DAYS.between(LocalDate.now(), end);
                    if (days < 0) items.add(item("bad", "Ubuntu " + ver + " is no longer supported", "It stopped getting free security fixes on " + end + ".",
                            "Back up first, then: sudo do-release-upgrade   (or move to a new machine with a current Ubuntu)"));
                    else if (days < 180) items.add(item("warn", "Ubuntu " + ver + " support ends in " + days + " days", "Free security fixes stop on " + end + ".",
                            "Plan the upgrade: sudo do-release-upgrade   (back up first)"));
                }
            }
        } catch (RuntimeException ignored) { }

        double diskPct = 0;
        try {
            File root = new File("/");
            if (root.getTotalSpace() > 0) diskPct = 100.0 * (root.getTotalSpace() - root.getUsableSpace()) / root.getTotalSpace();
        } catch (RuntimeException ignored) { }
        if (diskPct >= 75) {
            items.add(item(diskPct >= 90 ? "bad" : "warn", "Storage is " + Math.round(diskPct) + "% full", "The app and the database can stop working when the disk fills.",
                    "sudo apt autoremove -y && sudo apt clean && sudo journalctl --vacuum-size=200M\ndu -xh / --max-depth=2 2>/dev/null | sort -h | tail -15   # shows what is big"));
        }
        try {
            Path j = Path.of("/var/log/journal");
            if (Files.isReadable(j)) {
                try (var walk = Files.walk(j, 3)) {
                    long size = walk.filter(Files::isRegularFile).mapToLong(p -> p.toFile().length()).sum();
                    if (size > 1_000_000_000L) items.add(item("warn", "System logs take " + Math.round(size / 1_000_000_000.0 * 10) / 10.0 + " GB", "Old logs can be trimmed safely.",
                            "sudo journalctl --vacuum-size=200M"));
                }
            }
        } catch (Exception ignored) { }
        if (diskPct >= 70) {
            try {
                Process pr = new ProcessBuilder("apt-get", "-s", "autoremove").redirectError(ProcessBuilder.Redirect.DISCARD).start();
                if (!pr.waitFor(15, TimeUnit.SECONDS)) { pr.destroyForcibly(); } else {
                    int n = 0;
                    try (var br = new java.io.BufferedReader(new java.io.InputStreamReader(pr.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = br.readLine()) != null) if (line.startsWith("Remv ")) n++;
                    }
                    if (n > 0) items.add(item("info", n + " old package(s) can be removed", "Frees a little space.", "sudo apt autoremove -y && sudo apt clean"));
                }
            } catch (Exception ignored) { }
        }
    }

    private static int rank(String level) {
'@
Edit-Text 'src\main\java\com\glr\deenwallet\monitoring\SecurityScanService.java' $o2 $n2 'extra method'
$o3 = @'
import java.time.Duration;
'@
$n3 = @'
import java.time.Duration;
import java.time.LocalDate;
'@
Edit-Text 'src\main\java\com\glr\deenwallet\monitoring\SecurityScanService.java' $o3 $n3 'imports'
$o4 = @'
                                "/icon-192.png"
                        ).permitAll()
'@
$n4 = @'
                                "/icon-192.png",
                                "/dw-agent.py",
                                "/api/monitor/agent/report"
                        ).permitAll()
'@
Edit-Text 'src\main\java\com\glr\deenwallet\config\SecurityConfig.java' $o4 $n4 'permit agent'
$o5 = @'
import com.glr.deenwallet.monitoring.BlockedIpService;
'@
$n5 = @'
import com.glr.deenwallet.monitoring.AgentReportService;
import com.glr.deenwallet.monitoring.BlockedIpService;
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o5 $n5 'imp agents'
$o6 = @'
    private final BlockedIpService blockedIps;
'@
$n6 = @'
    private final BlockedIpService blockedIps;
    private final AgentReportService agents;
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o6 $n6 'field agents'
$o7 = @'
                                  BlockedIpService blockedIps) {
        this.blockedIps = blockedIps;
'@
$n7 = @'
                                  BlockedIpService blockedIps, AgentReportService agents) {
        this.blockedIps = blockedIps;
        this.agents = agents;
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o7 $n7 'ctor agents'
$o8 = @'
        out.put("blockedIps", blockedIps.list());
'@
$n8 = @'
        out.put("blockedIps", blockedIps.list());
        List<Map<String, Object>> machines = agents.list();
        out.put("machines", machines);
        out.put("agentEnabled", agents.enabled());
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o8 $n8 'build machines'
$o9 = @'
out.put("health", health(server, app, db, attacks, vulns));
'@
$n9 = @'
out.put("health", health(server, app, db, attacks, vulns, machines));
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o9 $n9 'health call'
$o10 = @'
Map<String, Object> attacks, Map<String, Object> vulns) {
'@
$n10 = @'
Map<String, Object> attacks, Map<String, Object> vulns,
                                              List<Map<String, Object>> machines) {
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o10 $n10 'health sig'
$o11 = @'
        Object kinds = attacks.get("byKind");
'@
$n11 = @'
        for (Map<String, Object> mc : machines) {
            String who = String.valueOf(mc.get("name"));
            if (!Boolean.TRUE.equals(mc.get("online"))) {
                long mins = Math.round(n(mc.get("ageSeconds")) / 60.0);
                issue(issues, n(mc.get("ageSeconds")) >= 300 ? "bad" : "warn", who + " has not reported for " + mins + " minute(s). It may be down or off the network.");
                continue;
            }
            if (mc.get("server") instanceof Map<?, ?> sv) {
                if (n(sv.get("diskPercent")) >= 90) issue(issues, "bad", who + ": storage is almost full (" + Math.round(n(sv.get("diskPercent"))) + "%).");
                else if (n(sv.get("diskPercent")) >= 80) issue(issues, "warn", who + ": storage is filling up (" + Math.round(n(sv.get("diskPercent"))) + "%).");
                if (n(sv.get("ramPercent")) >= 92) issue(issues, "bad", who + ": memory (RAM) is almost full.");
                else if (n(sv.get("ramPercent")) >= 80) issue(issues, "warn", who + ": memory (RAM) is getting full.");
                if (n(sv.get("cpuPercent")) >= 90) issue(issues, "warn", who + ": the processor is almost fully used.");
            }
            if (mc.get("tasks") instanceof List<?> tl) {
                long bad = tl.stream().filter(o -> o instanceof Map<?, ?> t && "bad".equals(t.get("level"))).count();
                long warn = tl.stream().filter(o -> o instanceof Map<?, ?> t && "warn".equals(t.get("level"))).count();
                if (bad > 0) issue(issues, "bad", who + ": " + bad + " serious item(s) to fix on that machine.");
                else if (warn > 0) issue(issues, "warn", who + ": " + warn + " item(s) need attention on that machine.");
            }
        }
        Object kinds = attacks.get("byKind");
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o11 $n11 'health machines'
$o12 = @'
import com.glr.deenwallet.monitoring.SecurityScanService;
'@
$n12 = @'
import com.glr.deenwallet.monitoring.SecurityScanService;
import com.glr.deenwallet.monitoring.UsageService;
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o12 $n12 'u imp'
$o13 = @'
    private final AgentReportService agents;
'@
$n13 = @'
    private final AgentReportService agents;
    private final UsageService usage;
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o13 $n13 'u field'
$o14 = @'
BlockedIpService blockedIps, AgentReportService agents) {
'@
$n14 = @'
BlockedIpService blockedIps, AgentReportService agents, UsageService usage) {
        this.usage = usage;
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o14 $n14 'u ctor'
$o15 = @'
        out.put("agentEnabled", agents.enabled());
'@
$n15 = @'
        out.put("agentEnabled", agents.enabled());
        out.put("usage", usage.overview());
'@
Edit-Text 'src\main\java\com\glr\deenwallet\admin\AdminMetricsController.java' $o15 $n15 'u build'
$o16 = @'
            function mxCopy(text) {
'@
$n16 = @'
            function mxFixRow(it) {
                var lbl = it.level === 'bad' ? 'Fix now' : it.level === 'warn' ? 'Needs attention' : it.level === 'ok' ? 'Good' : 'Note';
                return '<div class="mx-row"><span class="mx-pill ' + escapeHtml(it.level) + '">' + lbl + '</span> <b>' + escapeHtml(it.title) + '</b>' +
                    (it.detail ? '<br><span class="mx-muted">' + escapeHtml(it.detail) + '</span>' : '') +
                    (it.fix ? '<div class="mx-fix">' + escapeHtml(it.fix) + '</div><div style="margin-top:6px;"><button type="button" class="btn-sm refresh" data-mx="copy-text" data-text="' + escapeHtml(it.fix) + '">Copy command</button></div>' : '') + '</div>';
            }
            // Everything that needs you on an SSH session, grouped by machine, each with the command to run.
            function mxTodoCard(m) {
                var s = m.server || {}, groups = [];
                var local = ((m.vulnerabilities || {}).items || []).filter(function (i) { return i.level !== 'ok' && i.fix; });
                if (local.length) groups.push({ name: 'This server (' + (s.host || 'main') + ')', items: local });
                (m.machines || []).forEach(function (mc) {
                    var t = (mc.tasks || []).filter(function (i) { return i.level !== 'ok' && i.fix; });
                    if (t.length) groups.push({ name: mc.name, items: t });
                });
                var total = groups.reduce(function (n, g) { return n + g.items.length; }, 0);
                var inner = '';
                if (!total) inner = '<div class="mx-row">✔ Nothing needs you on any server right now.</div>';
                groups.forEach(function (g) {
                    inner += '<p class="mx-sub">' + escapeHtml(g.name) + ' · ' + g.items.length + ' to do</p>' + g.items.map(mxFixRow).join('');
                });
                return mxSection('To do on your servers (needs SSH)', total ? 'Open an SSH session to the machine named, paste the command, done. Updates and fixes that only you can do.' : 'Updates, restarts and clean-ups that need an SSH session will appear here with the command to run.', inner);
            }
            function mxMachineCard(mc) {
                var s = mc.server || {}, t = mc.tasks || [], ssh = mc.ssh || {};
                var perCore = (s.cores ? (s.load1 / s.cores) : 0);
                var inner = '<div class="mx-health ' + (mc.online ? 'ok' : 'bad') + '" style="margin-top:10px;padding:10px 12px;"><b>' + (mc.online ? '● Online' : '⚠ Not reporting') + '</b> · last report ' + escapeHtml(mxAgo(mc.lastReport)) +
                    '<div class="mx-s-h" style="margin:2px 0 0;">' + escapeHtml((s.os || '') + (s.kernel ? ' · kernel ' + s.kernel : '')) + '</div></div>';
                if (s.cores) {
                    inner += '<div class="mx-grid">' +
                        mxGauge('Processor (CPU)', s.cpuPercent, Math.round(s.cpuPercent || 0) + '%', s.cores + ' core(s)', mxLevel(s.cpuPercent, 75, 90)) +
                        mxGauge('Memory (RAM)', s.ramPercent, Math.round(s.ramPercent || 0) + '%', mxBytes(s.ramUsed) + ' of ' + mxBytes(s.ramTotal), mxLevel(s.ramPercent, 80, 92)) +
                        mxGauge('Storage (disk)', s.diskPercent, Math.round(s.diskPercent || 0) + '%', mxBytes(s.diskUsed) + ' of ' + mxBytes(s.diskTotal), mxLevel(s.diskPercent, 80, 90)) +
                        mxGauge('Load (work queue)', Math.min(100, perCore * 50), String(s.load1), '5 min ' + s.load5 + ' · 15 min ' + s.load15, perCore >= 2 ? 'bad' : perCore >= 1 ? 'warn' : 'ok') +
                        mxStat('Running for', mxDur(s.uptimeSeconds), s.host || '') +
                        mxStat('Swap', s.swapTotal ? mxBytes(s.swapUsed) + ' / ' + mxBytes(s.swapTotal) : 'None', '') + '</div>';
                }
                if (t.length) inner += '<p class="mx-sub">Updates and weak spots</p>' + t.map(mxFixRow).join('');
                if (ssh.available) {
                    inner += '<p class="mx-sub">SSH guessing on this machine · ' + escapeHtml(String(ssh.attempts || 0)) + ' tries from ' + escapeHtml(String(ssh.uniqueIps || 0)) + ' address(es)</p>';
                    (ssh.topIps || []).slice(0, 5).forEach(function (x) {
                        inner += '<div class="mx-row"><b class="ip">' + escapeHtml(x.ip) + '</b> <span class="mx-pill ' + escapeHtml(x.level) + '">' + mxRiskLabel(x.level) + '</span> · <b>' + escapeHtml(String(x.attempts)) + '</b> tries · ' + escapeHtml((x.usernames || []).join(', ')) +
                            '<div class="mx-fix">sudo ufw insert 1 deny from ' + escapeHtml(x.ip) + '</div></div>';
                    });
                    if ((ssh.recentLogins || []).length) {
                        inner += ssh.recentLogins.map(function (e) {
                            return '<div class="mx-row">Login: <b>' + escapeHtml(e.user) + '</b> from <b class="ip">' + escapeHtml(e.ip) + '</b> · ' + escapeHtml(e.how) + '<br><span class="mx-muted">' + escapeHtml(e.time) + '</span></div>';
                        }).join('');
                    }
                } else if (ssh.hint) {
                    inner += '<div class="mx-row">' + escapeHtml(ssh.hint) + '</div>';
                }
                return mxSection(mc.name, 'An extra machine reporting to this tab every minute.', inner);
            }
            function mxMachines(m) {
                var html = '', list = m.machines || [];
                list.forEach(function (mc) { html += mxMachineCard(mc); });
                var api = String(API_BASE_URL || '').replace(/\/$/, '');
                var step1 = 'openssl rand -hex 24\nsudo nano /var/www/deenwallet/deenwallet.env     # add the line:  MONITOR_AGENT_TOKEN=<the value printed above>\nsudo systemctl restart deenwallet';
                var step2 = 'curl -fsSL ' + api + '/dw-agent.py -o dw-agent.py\nsudo python3 dw-agent.py --install --url ' + api + ' --token <the same value> --name "Database VM"';
                var inner = '<div class="mx-row"><span class="mx-pill ' + (m.agentEnabled ? 'ok' : 'warn') + '">' + (m.agentEnabled ? 'Receiving is on' : 'Not switched on yet') + '</span> ' +
                    (list.length ? list.length + ' machine(s) reporting.' : 'No extra machine is reporting yet.') + '</div>' +
                    '<p class="mx-sub">Step 1 · on this main server (once)</p><div class="mx-fix">' + escapeHtml(step1) + '</div><div style="margin-top:6px;"><button type="button" class="btn-sm refresh" data-mx="copy-text" data-text="' + escapeHtml(step1) + '">Copy</button></div>' +
                    '<p class="mx-sub">Step 2 · on the other machine</p><div class="mx-fix">' + escapeHtml(step2) + '</div><div style="margin-top:6px;"><button type="button" class="btn-sm refresh" data-mx="copy-text" data-text="' + escapeHtml(step2) + '">Copy</button></div>' +
                    '<div class="mx-s-h">The token is a password only your machines know: keep it in the server\'s environment file, never in chat. The agent only reads the machine and sends a small report. Remove it any time with: sudo python3 /usr/local/bin/dw-agent.py --uninstall</div>';
                html += mxSection('Add another machine', 'Monitor your second virtual machine (or more) the same way.', inner);
                return html;
            }
            function mxCopy(text) {
'@
Edit-Text 'src\main\resources\static\admin.html' $o16 $n16 'mx new helpers'
$o17 = @'
                    if (act === 'copy-ufw') {
'@
$n17 = @'
                    if (act === 'copy-text') {
                        mxCopy(b.getAttribute('data-text') || '').then(function () { showToast('Copied. Paste it into your SSH session.'); }).catch(function () { showToast('Could not copy. Long-press the command to copy it.'); });
                        return;
                    }
                    if (act === 'copy-ufw') {
'@
Edit-Text 'src\main\resources\static\admin.html' $o17 $n17 'mx copy-text'
$o18 = @'
                // 2) server
'@
$n18 = @'
                html += mxTodoCard(m);

                // 2) server
'@
Edit-Text 'src\main\resources\static\admin.html' $o18 $n18 'mx todo'
$o19 = @'
                // 5) attacks
'@
$n19 = @'
                html += mxMachines(m);

                // 5) attacks
'@
Edit-Text 'src\main\resources\static\admin.html' $o19 $n19 'mx machines'
$o20 = @'
                items.forEach(function (it) {
                    var lbl = it.level === 'bad' ? 'Fix now' : it.level === 'warn' ? 'Needs attention' : it.level === 'ok' ? 'Good' : 'Note';
                    inner += '<div class="mx-row"><span class="mx-pill ' + escapeHtml(it.level) + '">' + lbl + '</span> <b>' + escapeHtml(it.title) + '</b>' +
                        (it.detail ? '<br><span class="mx-muted">' + escapeHtml(it.detail) + '</span>' : '') +
                        (it.fix ? '<div class="mx-fix">' + escapeHtml(it.fix) + '</div>' : '') + '</div>';
                });
'@
$n20 = @'
                items.forEach(function (it) { inner += mxFixRow(it); });
'@
Edit-Text 'src\main\resources\static\admin.html' $o20 $n20 'mx weak rows'
$o21 = @'
html += mxSection('Weak spots', v.scannedAt ? 'Checked ' + mxAgo(v.scannedAt) + ' · re-checked every 5 minutes. Fixes are copy-and-paste for the server.' : 'Things an attacker could use, with the fix.', inner);
'@
$n21 = @'
html += mxSection('Weak spots and updates (this server)', v.scannedAt ? 'Checked ' + mxAgo(v.scannedAt) + ' · re-checked every 5 minutes. Fixes are copy-and-paste for the server.' : 'Things an attacker could use, with the fix.', inner);
'@
Edit-Text 'src\main\resources\static\admin.html' $o21 $n21 'mx weak title'
$o22 = @'
            function mxCopy(text) {
'@
$n22 = @'
            function mxGb(b) { return (Number(b || 0) / 1073741824); }
            function mxMonthName(ym) {
                var p = String(ym || '').split('-');
                return p.length === 2 ? ['Jan', 'Feb', 'Mar', 'Apr', 'May', 'Jun', 'Jul', 'Aug', 'Sep', 'Oct', 'Nov', 'Dec'][Number(p[1]) - 1] + ' ' + p[0] : ym;
            }
            function mxUsageCard(u) {
                u = u || {};
                var months = u.months || [];
                var payg = u.plan === 'payg';
                var inner = '<div class="mx-row"><span class="mx-pill ' + (payg ? 'warn' : 'ok') + '">' + (payg ? 'Pay As You Go' : 'Free tier') + '</span> ' +
                    (payg ? (u.paygStart ? 'Paying since ' + escapeHtml(u.paygStart) + '.' : 'Pay As You Go is on.') : 'Not paying yet. Usage is recorded now, so the history is already here when you start paying.') + '</div>';
                if (u.available === false || !months.length) {
                    inner += '<div class="mx-row">Collecting the first numbers. Come back in a few minutes.</div>';
                    return mxSection('Oracle usage and cost', 'What this month is using, against Oracle\'s free allowance.', inner);
                }
                var cur = months[0];
                var outGb = mxGb(cur.txBytes), diskGb = mxGb(cur.diskUsed);
                var outPct = u.freeOutboundGb ? outGb / u.freeOutboundGb * 100 : 0, diskPct = u.freeStorageGb ? diskGb / u.freeStorageGb * 100 : 0;
                inner += '<div class="mx-grid">' +
                    mxGauge('Data sent out · ' + mxMonthName(cur.month), outPct, outGb < 10 ? outGb.toFixed(2) + ' GB' : Math.round(outGb) + ' GB', 'free allowance ' + Math.round(u.freeOutboundGb) + ' GB / month', mxLevel(outPct, 70, 90)) +
                    mxGauge('Storage used', diskPct, Math.round(diskGb) + ' GB', 'free allowance ' + Math.round(u.freeStorageGb) + ' GB', mxLevel(diskPct, 80, 95)) +
                    mxStat('Data received', (mxGb(cur.rxBytes) < 10 ? mxGb(cur.rxBytes).toFixed(2) : Math.round(mxGb(cur.rxBytes))) + ' GB', 'Coming in is always free') +
                    mxStat('Busiest moment', 'CPU ' + Math.round(cur.cpuPeak) + '% · RAM ' + Math.round(cur.ramPeak) + '%', 'Highest this month') + '</div>';
                inner += '<div class="mx-row"><b>Estimated extra cost this month: $' + Number(cur.estimatedCost || 0).toFixed(2) + '</b><br><span class="mx-muted">' +
                    (cur.estimatedCost > 0 ? 'Only the part above the free allowance is charged.' : 'Everything is inside the free allowance, so nothing to pay for data or storage.') +
                    ' This is an estimate from the numbers above (about $' + u.priceOutbound + ' per GB out, $' + u.priceStorage + ' per GB of storage per month). The exact bill is in the Oracle console: Billing &amp; Cost Management &gt; Cost Analysis. Machines and databases you create yourself are billed there too.</span></div>';
                if (months.length > 1) {
                    inner += '<p class="mx-sub">Earlier months</p>' + months.slice(1, 7).map(function (m) {
                        return '<div class="mx-row"><b>' + escapeHtml(mxMonthName(m.month)) + '</b> · out ' + escapeHtml(mxGb(m.txBytes).toFixed(1)) + ' GB · storage ' + escapeHtml(Math.round(mxGb(m.diskUsed))) + ' GB · estimated $' + Number(m.estimatedCost || 0).toFixed(2) + '</div>';
                    }).join('');
                }
                inner += '<div class="mx-s-h">When you subscribe, add ORACLE_PLAN=payg (and ORACLE_PAYG_START=the date) to the server\'s environment file and restart. Prices can change: set ORACLE_PRICE_PER_GB_OUTBOUND and ORACLE_PRICE_PER_GB_STORAGE_MONTH if Oracle\'s differ.</div>';
                return mxSection('Oracle usage and cost', 'Recorded every minute and kept month by month, even while you are on the free tier.', inner);
            }
            function mxCopy(text) {
'@
Edit-Text 'src\main\resources\static\admin.html' $o22 $n22 'u js'
$o23 = @'
                html += mxMachines(m);
'@
$n23 = @'
                html += mxMachines(m);
                html += mxUsageCard(m.usage);
'@
Edit-Text 'src\main\resources\static\admin.html' $o23 $n23 'u use'
$o24 = @'
    private final ArrayDeque<Map<String, Object>> history = new ArrayDeque<>();
'@
$n24 = @'
    private final ArrayDeque<Map<String, Object>> history = new ArrayDeque<>();
    private final UsageService usage;

    public ServerMetricsService(UsageService usage) {
        this.usage = usage;
    }
'@
Edit-Text 'src\main\java\com\glr\deenwallet\monitoring\ServerMetricsService.java' $o24 $n24 'usage dep'
$o25 = @'
            readNet();
            Map<String, Object> p
'@
$n25 = @'
            readNet();
            usage.notePeaks(cpuPercent, mem.getOrDefault("MemTotal", 0L) > 0 ? 100.0 * (mem.get("MemTotal") - mem.getOrDefault("MemAvailable", 0L)) / mem.get("MemTotal") : 0);
            Map<String, Object> p
'@
Edit-Text 'src\main\java\com\glr\deenwallet\monitoring\ServerMetricsService.java' $o25 $n25 'usage peaks'
$f1 = @'
#!/usr/bin/env python3
"""DeenWallet monitoring agent.

Runs on an extra server (for example your database machine) and reports to the DeenWallet Metrics tab every minute:
processor, memory, storage, load, what needs updating (with the command to run), SSH guessing and weak spots.
It only READS the machine and sends a small report. It never changes anything except its own two small systemd files.

Install (as root, on the extra server):
    curl -fsSL https://YOUR-API/dw-agent.py -o dw-agent.py
    sudo python3 dw-agent.py --install --url https://YOUR-API --token YOUR_TOKEN --name "Database VM"
Remove:
    sudo python3 dw-agent.py --uninstall
Test without sending anything:
    sudo python3 dw-agent.py --print
"""
import argparse
import json
import os
import platform
import re
import shutil
import socket
import subprocess
import sys
import time
import urllib.error
import urllib.request

VERSION = "1"
CONF = "/etc/dw-agent.json"
BIN = "/usr/local/bin/dw-agent.py"
CACHE = "/var/tmp/dw-agent-apt.json"
SERVICE = "/etc/systemd/system/dw-agent.service"
TIMER = "/etc/systemd/system/dw-agent.timer"


def read(path, limit=None):
    try:
        with open(path, "r", errors="replace") as f:
            return f.read() if limit is None else f.read(limit)
    except Exception:
        return ""


def run(cmd, timeout=20):
    try:
        p = subprocess.run(cmd, stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, timeout=timeout, text=True)
        return p.stdout or ""
    except Exception:
        return None


def cpu_sample():
    line = read("/proc/stat", 400).split("\n")[0].split()
    if len(line) < 5 or line[0] != "cpu":
        return None
    vals = [int(x) for x in line[1:9] if x.isdigit()]
    return sum(vals), int(line[4]) + (int(line[5]) if len(line) > 5 else 0)


def cpu_percent():
    a = cpu_sample()
    time.sleep(1)
    b = cpu_sample()
    if not a or not b or b[0] <= a[0]:
        return 0.0
    return round(max(0.0, min(100.0, 100.0 * ((b[0] - a[0]) - (b[1] - a[1])) / (b[0] - a[0]))), 1)


def meminfo():
    out = {}
    for line in read("/proc/meminfo").split("\n"):
        if ":" in line:
            k, v = line.split(":", 1)
            try:
                out[k] = int(v.strip().split()[0]) * 1024
            except Exception:
                pass
    return out


def net_totals():
    rx = tx = 0
    for line in read("/proc/net/dev").split("\n")[2:]:
        if ":" not in line:
            continue
        name, data = line.split(":", 1)
        if name.strip() == "lo":
            continue
        f = data.split()
        if len(f) >= 9:
            rx += int(f[0])
            tx += int(f[8])
    return rx, tx


def item(level, title, detail="", fix=""):
    return {"level": level, "title": title, "detail": detail, "fix": fix}


def public_ports():
    ports = set()
    for fn, v6 in (("/proc/net/tcp", False), ("/proc/net/tcp6", True)):
        for line in read(fn).split("\n")[1:]:
            f = line.split()
            if len(f) < 4 or f[3] != "0A":
                continue
            addr, _, port = f[1].partition(":")
            if (addr == "0" * 32) if v6 else (addr == "00000000"):
                try:
                    ports.add(int(port, 16))
                except ValueError:
                    pass
    return sorted(ports)


EOL = {"18.04": "2023-05-31", "20.04": "2025-05-31", "22.04": "2027-06-01", "24.04": "2029-06-01"}


def apt_state():
    """Waiting updates are checked at most every 30 minutes (apt is slowish)."""
    try:
        c = json.loads(read(CACHE) or "{}")
        if time.time() - c.get("at", 0) < 1800:
            return c
    except Exception:
        pass
    c = {"at": time.time(), "total": None, "security": 0, "autoremove": 0}
    out = run(["apt", "list", "--upgradable"], 25)
    if out is not None:
        lines = [l for l in out.split("\n") if "/" in l and not l.startswith("Listing")]
        c["total"] = len(lines)
        c["security"] = len([l for l in lines if "-security" in l])
    out = run(["apt-get", "-s", "autoremove"], 20)
    if out is not None:
        c["autoremove"] = len([l for l in out.split("\n") if l.startswith("Remv ")])
    try:
        with open(CACHE, "w") as f:
            json.dump(c, f)
    except Exception:
        pass
    return c


def tasks(disk_pct):
    t = []
    c = apt_state()
    if c["total"] is None:
        t.append(item("info", "Waiting updates could not be checked", "This machine does not use apt.", "Check by hand: sudo apt update && apt list --upgradable"))
    elif c["security"] > 0:
        t.append(item("bad", "%d security update(s) are waiting" % c["security"], "%d package update(s) waiting in total. Security updates close holes attackers already know about." % c["total"],
                      "sudo apt update && sudo apt upgrade -y"))
    elif c["total"] > 0:
        t.append(item("warn", "%d system update(s) are waiting" % c["total"], "None is marked as a security update.", "sudo apt update && sudo apt upgrade -y"))
    else:
        t.append(item("ok", "System packages are up to date", "No updates are waiting (based on the last package list refresh)."))
    if os.path.exists("/var/run/reboot-required") or os.path.exists("/run/reboot-required"):
        t.append(item("warn", "This machine needs a restart", "Some updates only take effect after a restart.", "sudo reboot   (do it when few people are using the app)"))
    auto = read("/etc/apt/apt.conf.d/20auto-upgrades")
    if 'Unattended-Upgrade "1"' in auto:
        t.append(item("ok", "Automatic security updates are on"))
    elif os.path.isdir("/etc/apt/apt.conf.d"):
        t.append(item("warn", "Automatic security updates are not on", "Nobody installs security fixes unless you do it by hand.",
                      "sudo apt install unattended-upgrades && sudo dpkg-reconfigure -plow unattended-upgrades"))
    # ssh settings (first value found wins, files in sshd_config.d come first)
    files = []
    d = "/etc/ssh/sshd_config.d"
    if os.path.isdir(d):
        files += [os.path.join(d, x) for x in sorted(os.listdir(d)) if x.endswith(".conf")]
    files.append("/etc/ssh/sshd_config")
    pw = root = None
    seen = False
    for fn in files:
        txt = read(fn)
        if txt:
            seen = True
        for line in txt.split("\n"):
            s = line.strip()
            if not s or s.startswith("#"):
                continue
            p = s.split()
            if len(p) < 2:
                continue
            if pw is None and p[0].lower() == "passwordauthentication":
                pw = p[1].lower()
            if root is None and p[0].lower() == "permitrootlogin":
                root = p[1].lower()
    if seen:
        if pw == "yes":
            t.append(item("bad", "SSH accepts passwords", "Anyone on the internet can keep guessing the password. Keys cannot be guessed.",
                          "Set PasswordAuthentication no in /etc/ssh/sshd_config, then: sudo systemctl restart ssh   (keep your key login working first)"))
        elif pw is None:
            t.append(item("warn", "SSH password setting is not set", "The default allows password logins unless a setting turns them off.",
                          "Add PasswordAuthentication no to /etc/ssh/sshd_config, then: sudo systemctl restart ssh   (keep your key login working first)"))
        else:
            t.append(item("ok", "SSH uses keys only"))
        if root == "yes":
            t.append(item("bad", "SSH allows logging in as root", "Attackers always try root first.", "Set PermitRootLogin no in /etc/ssh/sshd_config, then: sudo systemctl restart ssh"))
    if os.path.exists("/etc/fail2ban") or os.path.exists("/run/fail2ban/fail2ban.sock"):
        t.append(item("ok", "fail2ban is installed"))
    else:
        t.append(item("warn", "No automatic blocking of attackers (fail2ban)", "People who keep guessing SSH passwords are never blocked.", "sudo apt install fail2ban -y"))
    ufw = read("/etc/ufw/ufw.conf")
    if ufw:
        if "ENABLED=yes" in ufw.replace(" ", ""):
            t.append(item("ok", "Firewall (ufw) is on"))
        else:
            t.append(item("info", "Firewall (ufw) is off", "On Oracle Cloud the cloud firewall (Security List) may already protect this machine.", "sudo ufw allow 22/tcp && sudo ufw enable"))
    ports = public_ports()
    risky = [p for p in ports if p in (5432, 3306, 6379, 27017, 9200, 11211, 2375, 5984)]
    other = [p for p in ports if p not in (22, 80, 443) and p not in risky]
    if risky:
        t.append(item("bad", "Database / internal port open to everyone: %s" % risky, "These ports should only be reachable from your own servers.",
                      "Make the service listen only on your private address (PostgreSQL: listen_addresses in postgresql.conf) and allow only your app server in the firewall / Oracle Security List."))
    if other:
        t.append(item("info", "Other ports listening on all addresses: %s" % other, "Make sure you recognise each of these.",
                      "If a service does not need the internet, make it listen on 127.0.0.1 or your private address."))
    if not risky and not other:
        t.append(item("ok", "Only the normal ports are open", "Listening on all addresses: %s" % ports))
    # end of support
    ver = ""
    ident = ""
    for line in read("/etc/os-release").split("\n"):
        if line.startswith("ID="):
            ident = line[3:].strip('"')
        if line.startswith("VERSION_ID="):
            ver = line[11:].strip('"')
    if ident == "ubuntu" and ver in EOL:
        days = int((time.mktime(time.strptime(EOL[ver], "%Y-%m-%d")) - time.time()) // 86400)
        if days < 0:
            t.append(item("bad", "Ubuntu %s is no longer supported" % ver, "It stopped getting free security fixes on %s." % EOL[ver],
                          "Back up first, then: sudo do-release-upgrade   (or move to a new machine with a current Ubuntu)"))
        elif days < 180:
            t.append(item("warn", "Ubuntu %s support ends in %d days" % (ver, days), "Free security fixes stop on %s." % EOL[ver], "Plan the upgrade: sudo do-release-upgrade   (back up first)"))
    # disk and cleanup
    if disk_pct >= 75:
        lvl = "bad" if disk_pct >= 90 else "warn"
        t.append(item(lvl, "Storage is %d%% full" % round(disk_pct), "The machine and database can stop working when the disk fills.",
                      "sudo apt autoremove -y && sudo apt clean && sudo journalctl --vacuum-size=200M\ndu -xh / --max-depth=2 2>/dev/null | sort -h | tail -15   # shows what is big"))
    elif c.get("autoremove", 0) > 0 and disk_pct >= 70:
        t.append(item("info", "%d old package(s) can be removed" % c["autoremove"], "Frees a little space.", "sudo apt autoremove -y && sudo apt clean"))
    return t


FAILED = re.compile(r"Failed password for (?:invalid user )?(\S+) from (\S+) port")
ACCEPTED = re.compile(r"Accepted (\S+) for (\S+) from (\S+) port")


def ssh_attempts():
    fn = "/var/log/auth.log" if os.path.exists("/var/log/auth.log") else "/var/log/secure"
    if not os.path.exists(fn):
        return {"available": False, "hint": "No SSH log file (/var/log/auth.log) on this machine."}
    try:
        with open(fn, "rb") as f:
            f.seek(0, 2)
            size = f.tell()
            f.seek(max(0, size - 4000000))
            text = f.read().decode("utf-8", "replace")
    except Exception as e:
        return {"available": False, "hint": "The SSH log could not be read: %s" % type(e).__name__}
    per, users, acc, attempts = {}, {}, [], 0
    for line in text.split("\n"):
        if "sshd" not in line:
            continue
        m = FAILED.search(line)
        if m:
            attempts += 1
            per[m.group(2)] = per.get(m.group(2), 0) + 1
            users.setdefault(m.group(2), [])
            if m.group(1) not in users[m.group(2)]:
                users[m.group(2)].append(m.group(1))
            continue
        a = ACCEPTED.search(line)
        if a:
            ts = line.split()[0] if re.match(r"^\d{4}-", line) else line[:15]
            acc.append({"time": ts, "how": a.group(1), "user": a.group(2), "ip": a.group(3)})
            acc = acc[-50:]
    top = []
    for ip, n in sorted(per.items(), key=lambda x: -x[1])[:8]:
        top.append({"ip": ip, "attempts": n, "usernames": users.get(ip, [])[:4], "level": "high" if n >= 50 else "medium" if n >= 10 else "low"})
    return {"available": True, "attempts": attempts, "uniqueIps": len(per), "topIps": top, "recentLogins": list(reversed(acc[-5:]))}


def collect(name):
    mem = meminfo()
    total = mem.get("MemTotal", 0)
    avail = mem.get("MemAvailable", 0)
    st = os.statvfs("/")
    dtotal = st.f_blocks * st.f_frsize
    dfree = st.f_bavail * st.f_frsize
    dpct = round(100.0 * (dtotal - dfree) / dtotal, 1) if dtotal else 0.0
    la = read("/proc/loadavg").split()
    up = read("/proc/uptime").split()
    rx, tx = net_totals()
    osname = ""
    for line in read("/etc/os-release").split("\n"):
        if line.startswith("PRETTY_NAME="):
            osname = line[12:].strip('"')
    return {
        "name": name,
        "agentVersion": VERSION,
        "server": {
            "cores": os.cpu_count() or 1,
            "cpuPercent": cpu_percent(),
            "load1": float(la[0]) if len(la) > 2 else -1, "load5": float(la[1]) if len(la) > 2 else -1, "load15": float(la[2]) if len(la) > 2 else -1,
            "ramTotal": total, "ramUsed": max(0, total - avail), "ramPercent": round(100.0 * (total - avail) / total, 1) if total else 0.0,
            "swapTotal": mem.get("SwapTotal", 0), "swapUsed": max(0, mem.get("SwapTotal", 0) - mem.get("SwapFree", 0)),
            "diskTotal": dtotal, "diskUsed": dtotal - dfree, "diskPercent": dpct,
            "uptimeSeconds": int(float(up[0])) if up else 0,
            "rxTotal": rx, "txTotal": tx,
            "os": osname or platform.platform(), "kernel": platform.release(), "host": socket.gethostname(),
        },
        "tasks": tasks(dpct),
        "ssh": ssh_attempts(),
    }


def send(url, token, payload):
    req = urllib.request.Request(url.rstrip("/") + "/api/monitor/agent/report", data=json.dumps(payload).encode("utf-8"), method="POST",
                                 headers={"Content-Type": "application/json", "X-Agent-Token": token, "User-Agent": "dw-agent/" + VERSION})
    try:
        with urllib.request.urlopen(req, timeout=20) as r:
            return r.status, ""
    except urllib.error.HTTPError as e:
        return e.code, ""
    except Exception as e:
        return 0, "%s: %s" % (type(e).__name__, e)


def install(url, token, name):
    if os.geteuid() != 0:
        sys.exit("Run this with sudo.")
    if not re.match(r"^[A-Za-z0-9 ._-]{1,40}$", name):
        sys.exit("The name may only use letters, numbers, spaces, dots, dashes and underscores (max 40).")
    print("Testing the connection to %s ..." % url)
    code, err = send(url, token, collect(name))
    if code not in (200, 204):
        reason = {401: "The token is wrong.", 404: "The server has no MONITOR_AGENT_TOKEN set yet (or the address is wrong).", 0: err}.get(code, "HTTP %s" % code)
        sys.exit("Could not report: %s Nothing was installed." % reason)
    shutil.copy2(os.path.abspath(__file__), BIN)
    os.chmod(BIN, 0o755)
    with open(CONF, "w") as f:
        json.dump({"url": url, "token": token, "name": name}, f)
    os.chmod(CONF, 0o600)
    with open(SERVICE, "w") as f:
        f.write("[Unit]\nDescription=DeenWallet monitoring agent\nAfter=network-online.target\n\n[Service]\nType=oneshot\nExecStart=/usr/bin/python3 %s\n" % BIN)
    with open(TIMER, "w") as f:
        f.write("[Unit]\nDescription=Run the DeenWallet monitoring agent every minute\n\n[Timer]\nOnBootSec=30\nOnUnitActiveSec=60\nAccuracySec=5\n\n[Install]\nWantedBy=timers.target\n")
    run(["systemctl", "daemon-reload"], 30)
    run(["systemctl", "enable", "--now", "dw-agent.timer"], 30)
    print("Installed. '%s' will appear in Server Metrics within a minute." % name)


def uninstall():
    if os.geteuid() != 0:
        sys.exit("Run this with sudo.")
    run(["systemctl", "disable", "--now", "dw-agent.timer"], 30)
    for p in (SERVICE, TIMER, BIN, CONF, CACHE):
        try:
            os.remove(p)
        except FileNotFoundError:
            pass
    run(["systemctl", "daemon-reload"], 30)
    print("Removed.")


def main():
    ap = argparse.ArgumentParser(description="DeenWallet monitoring agent")
    ap.add_argument("--install", action="store_true")
    ap.add_argument("--uninstall", action="store_true")
    ap.add_argument("--print", action="store_true", help="show the report instead of sending it")
    ap.add_argument("--url")
    ap.add_argument("--token")
    ap.add_argument("--name")
    a = ap.parse_args()
    if a.uninstall:
        return uninstall()
    if a.install:
        if not (a.url and a.token and a.name):
            sys.exit("Use: sudo python3 dw-agent.py --install --url https://YOUR-API --token YOUR_TOKEN --name \"Database VM\"")
        return install(a.url, a.token, a.name)
    conf = {}
    try:
        conf = json.loads(read(CONF) or "{}")
    except Exception:
        pass
    name = a.name or conf.get("name") or socket.gethostname()
    payload = collect(name)
    if a.print:
        print(json.dumps(payload, indent=2))
        return
    url, token = a.url or conf.get("url"), a.token or conf.get("token")
    if not url or not token:
        sys.exit("Not installed yet. See the instructions at the top of this file.")
    code, err = send(url, token, payload)
    if code not in (200, 204):
        sys.exit("Report failed: %s" % (err or "HTTP %s" % code))


if __name__ == "__main__":
    main()
'@
Add-New 'src\main\resources\static\dw-agent.py' $f1
$f2 = @'
package com.glr.deenwallet.monitoring;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

/** Keeps the latest report from each extra machine that runs the DeenWallet monitoring agent (dw-agent.py). */
@Service
public class AgentReportService {

    private static final Pattern NAME = Pattern.compile("^[A-Za-z0-9 ._-]{1,40}$");
    private static final int MAX_MACHINES = 10;

    @Value("${MONITOR_AGENT_TOKEN:}")
    private String token;

    private static final class Entry {
        Map<String, Object> report;
        long at;
        String ip;
    }

    private final ConcurrentHashMap<String, Entry> machines = new ConcurrentHashMap<>();

    /** Receiving is only on when a long secret token is set in the server's environment file. */
    public boolean enabled() {
        return token != null && token.trim().length() >= 16;
    }

    public boolean tokenOk(String given) {
        if (!enabled() || given == null) return false;
        return MessageDigest.isEqual(token.trim().getBytes(StandardCharsets.UTF_8), given.trim().getBytes(StandardCharsets.UTF_8));
    }

    /** Returns null when accepted, or the reason it was refused. */
    public String accept(String ip, Map<String, Object> report) {
        Object n = report.get("name");
        if (!(n instanceof String name) || !NAME.matcher(name).matches()) return "Invalid machine name";
        if (!machines.containsKey(name) && machines.size() >= MAX_MACHINES) return "Too many machines";
        Entry e = new Entry();
        e.report = report;
        e.at = System.currentTimeMillis();
        e.ip = ip;
        machines.put(name, e);
        long cutoff = System.currentTimeMillis() - 30L * 24 * 3600 * 1000;
        machines.values().removeIf(x -> x.at < cutoff);
        return null;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> out = new ArrayList<>();
        long now = System.currentTimeMillis();
        for (Map.Entry<String, Entry> en : machines.entrySet()) {
            Entry e = en.getValue();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", en.getKey());
            long age = Math.max(0, (now - e.at) / 1000L);
            m.put("ageSeconds", age);
            m.put("online", age < 150);
            m.put("lastReport", Instant.ofEpochMilli(e.at).toString());
            m.put("fromIp", e.ip);
            m.put("server", e.report.get("server"));
            m.put("tasks", e.report.get("tasks"));
            m.put("ssh", e.report.get("ssh"));
            m.put("agentVersion", e.report.get("agentVersion"));
            out.add(m);
        }
        out.sort(Comparator.comparing(m -> String.valueOf(m.get("name"))));
        return out;
    }
}
'@
Add-New 'src\main\java\com\glr\deenwallet\monitoring\AgentReportService.java' $f2
$f3 = @'
package com.glr.deenwallet.monitoring;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.io.IOException;
import java.util.Map;

/** Receives the once-a-minute report from an extra machine. Open to the internet, so the secret token is checked first. */
@RestController
@RequestMapping("/api/monitor/agent")
public class AgentReportController {

    private static final int MAX_BYTES = 200_000;

    private final AgentReportService service;
    private final SecurityEventService events;
    private final ObjectMapper mapper;

    public AgentReportController(AgentReportService service, SecurityEventService events, ObjectMapper mapper) {
        this.service = service;
        this.events = events;
        this.mapper = mapper;
    }

    @PostMapping("/report")
    public ResponseEntity<Void> report(@RequestHeader(value = "X-Agent-Token", required = false) String token, HttpServletRequest http) throws IOException {
        if (!service.enabled()) return ResponseEntity.notFound().build();
        if (!service.tokenOk(token)) {
            events.record(http.getRemoteAddr(), "ADMIN_PROBE", "POST", "/api/monitor/agent/report", 401, http.getHeader("User-Agent"), "Wrong monitoring token");
            return ResponseEntity.status(401).build();
        }
        byte[] raw = http.getInputStream().readNBytes(MAX_BYTES + 1);
        if (raw.length > MAX_BYTES) return ResponseEntity.status(413).build();
        Map<String, Object> body;
        try {
            body = mapper.readValue(raw, new TypeReference<Map<String, Object>>() { });
        } catch (IOException e) {
            return ResponseEntity.badRequest().build();
        }
        String refused = service.accept(http.getRemoteAddr(), body);
        if (refused != null) return ResponseEntity.badRequest().build();
        return ResponseEntity.noContent().build();
    }
}
'@
Add-New 'src\main\java\com\glr\deenwallet\monitoring\AgentReportController.java' $f3
$f4 = @'
-- Month-by-month usage of this server (kept so it is still there after restarts and when you start paying).
CREATE TABLE IF NOT EXISTS usage_monthly (
    month          CHAR(7)      PRIMARY KEY,   -- e.g. 2026-10
    tx_bytes       BIGINT       NOT NULL DEFAULT 0,   -- data sent out to the internet
    rx_bytes       BIGINT       NOT NULL DEFAULT 0,   -- data received
    disk_used      BIGINT       NOT NULL DEFAULT 0,   -- highest storage used this month
    disk_total     BIGINT       NOT NULL DEFAULT 0,
    ram_peak_pct   NUMERIC(5,1) NOT NULL DEFAULT 0,
    cpu_peak_pct   NUMERIC(5,1) NOT NULL DEFAULT 0,
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now()
);
'@
Add-New 'src\main\resources\db\migration\V47__usage_monthly.sql' $f4
$f5 = @'
package com.glr.deenwallet.monitoring;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.File;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds up what this server uses each month (data out, data in, storage, peaks) and stores it, so it survives restarts.
 * It also estimates what the usage would cost above Oracle's free allowance. The estimate is a guide only:
 * Oracle's own Cost Analysis page is the real bill.
 */
@Service
public class UsageService {

    @Value("${ORACLE_PLAN:free}")
    private String plan;

    @Value("${ORACLE_FREE_OUTBOUND_GB:10240}")
    private double freeOutboundGb;

    @Value("${ORACLE_FREE_STORAGE_GB:200}")
    private double freeStorageGb;

    @Value("${ORACLE_PRICE_PER_GB_OUTBOUND:0.0085}")
    private double priceOutbound;

    @Value("${ORACLE_PRICE_PER_GB_STORAGE_MONTH:0.0255}")
    private double priceStorage;

    @Value("${ORACLE_PAYG_START:}")
    private String paygStart;

    @PersistenceContext
    private EntityManager em;

    private long prevRx = -1, prevTx = -1;
    private double cpuPeak = 0, ramPeak = 0;

    /** Called by the 5-second sampler so peaks are not missed. */
    public synchronized void notePeaks(double cpu, double ram) {
        if (cpu > cpuPeak) cpuPeak = cpu;
        if (ram > ramPeak) ramPeak = ram;
    }

    @Scheduled(fixedDelay = 60_000, initialDelay = 20_000)
    @Transactional
    public void collect() {
        long rx = 0, tx = 0;
        boolean any = false;
        for (String line : ServerMetricsService.readLines("/proc/net/dev", 40)) {
            int c = line.indexOf(':');
            if (c < 0 || line.substring(0, c).trim().equals("lo")) continue;
            String[] f = line.substring(c + 1).trim().split("\\s+");
            if (f.length < 9) continue;
            rx += ServerMetricsService.parse(f[0]);
            tx += ServerMetricsService.parse(f[8]);
            any = true;
        }
        long dRx = 0, dTx = 0;
        if (any) {
            if (prevRx >= 0 && rx >= prevRx && tx >= prevTx) { dRx = rx - prevRx; dTx = tx - prevTx; } // after a reboot the counters restart: skip that one step
            prevRx = rx; prevTx = tx;
        }
        File root = new File("/");
        long total = root.getTotalSpace(), used = Math.max(0, total - root.getUsableSpace());
        double cpu, ram;
        synchronized (this) { cpu = cpuPeak; ram = ramPeak; cpuPeak = 0; ramPeak = 0; }
        String month = YearMonth.now(ZoneOffset.UTC).toString();
        em.createNativeQuery("INSERT INTO usage_monthly (month, tx_bytes, rx_bytes, disk_used, disk_total, ram_peak_pct, cpu_peak_pct, updated_at) "
                        + "VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, now()) ON CONFLICT (month) DO UPDATE SET "
                        + "tx_bytes = usage_monthly.tx_bytes + EXCLUDED.tx_bytes, rx_bytes = usage_monthly.rx_bytes + EXCLUDED.rx_bytes, "
                        + "disk_used = GREATEST(usage_monthly.disk_used, EXCLUDED.disk_used), disk_total = EXCLUDED.disk_total, "
                        + "ram_peak_pct = GREATEST(usage_monthly.ram_peak_pct, EXCLUDED.ram_peak_pct), cpu_peak_pct = GREATEST(usage_monthly.cpu_peak_pct, EXCLUDED.cpu_peak_pct), updated_at = now()")
                .setParameter(1, month).setParameter(2, dTx).setParameter(3, dRx).setParameter(4, used).setParameter(5, total)
                .setParameter(6, Math.round(ram * 10) / 10.0).setParameter(7, Math.round(cpu * 10) / 10.0).executeUpdate();
    }

    private double cost(long tx, long diskUsed) {
        double outGb = tx / 1_073_741_824.0, diskGb = diskUsed / 1_073_741_824.0;
        double c = Math.max(0, outGb - freeOutboundGb) * priceOutbound + Math.max(0, diskGb - freeStorageGb) * priceStorage;
        return Math.round(c * 100) / 100.0;
    }

    @SuppressWarnings("unchecked")
    @Transactional(readOnly = true)
    public Map<String, Object> overview() {
        Map<String, Object> out = new LinkedHashMap<>();
        boolean payg = "payg".equalsIgnoreCase(plan) || "paid".equalsIgnoreCase(plan);
        out.put("plan", payg ? "payg" : "free");
        out.put("paygStart", paygStart == null || paygStart.isBlank() ? null : paygStart);
        out.put("freeOutboundGb", freeOutboundGb);
        out.put("freeStorageGb", freeStorageGb);
        out.put("priceOutbound", priceOutbound);
        out.put("priceStorage", priceStorage);
        try {
            List<Map<String, Object>> months = new ArrayList<>();
            for (Object[] r : (List<Object[]>) em.createNativeQuery(
                    "SELECT month, tx_bytes, rx_bytes, disk_used, disk_total, ram_peak_pct, cpu_peak_pct FROM usage_monthly ORDER BY month DESC LIMIT 12").getResultList()) {
                long tx = ((Number) r[1]).longValue(), du = ((Number) r[3]).longValue();
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("month", r[0]);
                m.put("txBytes", tx);
                m.put("rxBytes", ((Number) r[2]).longValue());
                m.put("diskUsed", du);
                m.put("diskTotal", ((Number) r[4]).longValue());
                m.put("ramPeak", ((Number) r[5]).doubleValue());
                m.put("cpuPeak", ((Number) r[6]).doubleValue());
                m.put("estimatedCost", cost(tx, du));
                months.add(m);
            }
            out.put("months", months);
            out.put("available", true);
        } catch (RuntimeException e) {
            out.put("available", false);
        }
        return out;
    }
}
'@
Add-New 'src\main\java\com\glr\deenwallet\monitoring\UsageService.java' $f5
$crlfProject = $files['src\main\resources\static\admin.html'].crlf
foreach ($rel in $files.Keys) {
  $f = $files[$rel]; $t = $f.text
  if ($f.crlf) { $t = $t.Replace("`n", "`r`n") }
  [System.IO.File]::WriteAllText($f.path, $t, $utf8)
  Write-Host ('changed   ' + $rel) -ForegroundColor Green
}
foreach ($rel in $newFiles.Keys) {
  $p = Join-Path $root $rel
  New-Item -ItemType Directory -Force -Path (Split-Path $p) | Out-Null
  $t = $newFiles[$rel]
  if ($crlfProject) { $t = $t.Replace("`n", "`r`n") }
  [System.IO.File]::WriteAllText($p, $t, $utf8)
  Write-Host ('added     ' + $rel) -ForegroundColor Green
}
Write-Host ''
Write-Host 'Done. Rebuild and restart the server: mvn clean package. Then fully close and reopen the admin app (no new APK needed).' -ForegroundColor Cyan

