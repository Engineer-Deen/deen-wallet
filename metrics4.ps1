# DeenWallet Transfer - Server Metrics as tidy cards (tap a card to open its details)
#
# Run from the SERVER project root (the folder that contains pom.xml):
#     powershell -ExecutionPolicy Bypass -File .\metrics4.ps1
#
# Server Metrics now opens as a grid of cards, each with a status dot and a one-line summary:
#   To do (needs SSH), Server, App, Database, Attacks (with SQL injection and blocked addresses inside), Server break-ins (SSH),
#   Weak spots, Other machines, Oracle usage & cost.
# Tap a card to open its full details (still live); "All metrics" or the phone's Back button returns to the grid.
# The overall verdict stays at the top. Changes admin.html only. Run fix-ufw.ps1 (or metrics3.ps1) first.
# Everything is checked first; nothing is written unless it all fits. No backup files are created.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = (Get-Location).Path
if (-not (Test-Path (Join-Path $root 'pom.xml'))) { throw 'Run this from the folder that contains pom.xml.' }
if (-not (Test-Path (Join-Path $root 'src\main\resources\db\migration\V47__usage_monthly.sql'))) { throw 'Run metrics3.ps1 first (V47 is missing). Nothing changed.' }
if ((Get-Content -Raw -LiteralPath (Join-Path $root 'src\main\resources\static\admin.html')).Contains('mx-hub')) { Write-Host 'Already applied - nothing to do.' -ForegroundColor Yellow; exit 0 }
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

Load-File 'src\main\resources\static\admin.html'
$o1 = @'
        .mx-err { background:#FDECEC;
'@
$n1 = @'
        .mx-hub { display:grid; grid-template-columns:repeat(2,minmax(0,1fr)); gap:10px; margin-top:12px; }
        .mx-tile { position:relative; text-align:left; background:var(--card); border:1px solid var(--border); border-radius:16px; padding:14px 12px 12px; cursor:pointer; min-width:0; font:inherit; color:var(--text); }
        .mx-tile:active { transform:scale(.98); }
        .mx-tile .t-ic { font-size:20px; line-height:1; }
        .mx-tile .t-ti { font-size:14px; font-weight:800; margin-top:8px; }
        .mx-tile .t-v { font-size:12.5px; color:var(--muted); margin-top:3px; line-height:1.4; }
        .mx-tile .t-dot { position:absolute; top:12px; right:12px; width:10px; height:10px; border-radius:50%; background:#22a06b; }
        .mx-tile.warn .t-dot { background:#e0a100; } .mx-tile.bad .t-dot { background:#d64545; } .mx-tile.info .t-dot { background:#9aa7ad; }
        .mx-tile.warn { border-color:#F5DDA6; } .mx-tile.bad { border-color:#F3BDBD; }
        .mx-wide { grid-column:1 / -1; }
        .mx-back { display:flex; align-items:center; gap:6px; margin-top:12px; background:none; border:0; color:var(--primary); font:700 14px/1 inherit; padding:6px 0; cursor:pointer; }
        .mx-err { background:#FDECEC;
'@
Edit-Text 'src\main\resources\static\admin.html' $o1 $n1 'css'
$o2 = @'
                var blockedMap = mxBlockedMap(m);
                var html = '';
'@
$n2 = @'
                var blockedMap = mxBlockedMap(m);
                var html = '', sec = {};
'@
Edit-Text 'src\main\resources\static\admin.html' $o2 $n2 'sec init'
$o3 = @'
                html += '<div class="mx-health ' + escapeHtml(hl.level || 'ok')
'@
$n3 = @'
                sec.verdict = '<div class="mx-health ' + escapeHtml(hl.level || 'ok')
'@
Edit-Text 'src\main\resources\static\admin.html' $o3 $n3 'sec verdict'
$o4 = @'
                html += mxTodoCard(m);
'@
$n4 = @'
                sec.todo = mxTodoCard(m);
'@
Edit-Text 'src\main\resources\static\admin.html' $o4 $n4 'sec todo'
$o5 = @'
                html += mxSection('Server (your Oracle machine)'
'@
$n5 = @'
                sec.server = mxSection('Server (your Oracle machine)'
'@
Edit-Text 'src\main\resources\static\admin.html' $o5 $n5 'sec server'
$o6 = @'
                html += mxSection('DeenWallet app'
'@
$n6 = @'
                sec.app = mxSection('DeenWallet app'
'@
Edit-Text 'src\main\resources\static\admin.html' $o6 $n6 'sec app'
$o7 = @'
                html += mxSection('Database (PostgreSQL)'
'@
$n7 = @'
                sec.db = mxSection('Database (PostgreSQL)'
'@
Edit-Text 'src\main\resources\static\admin.html' $o7 $n7 'sec db'
$o8 = @'
                html += mxMachines(m);
'@
$n8 = @'
                sec.machines = mxMachines(m);
'@
Edit-Text 'src\main\resources\static\admin.html' $o8 $n8 'sec mach'
$o9 = @'
                html += mxUsageCard(m.usage);
'@
$n9 = @'
                sec.usage = mxUsageCard(m.usage);
'@
Edit-Text 'src\main\resources\static\admin.html' $o9 $n9 'sec usage'
$o10 = @'
                html += mxSection('Attacks (last 24 hours)'
'@
$n10 = @'
                sec.attacks = mxSection('Attacks (last 24 hours)'
'@
Edit-Text 'src\main\resources\static\admin.html' $o10 $n10 'sec attacks'
$o11 = @'
                html += mxSqliCard(at);
                html += mxBlockedCard(m);
'@
$n11 = @'
                sec.attacks += mxSqliCard(at);
                sec.attacks += mxBlockedCard(m);
'@
Edit-Text 'src\main\resources\static\admin.html' $o11 $n11 'sec sqli'
$o12 = @'
                html += mxSection('Attempts to break into the server (SSH)'
'@
$n12 = @'
                sec.ssh = mxSection('Attempts to break into the server (SSH)'
'@
Edit-Text 'src\main\resources\static\admin.html' $o12 $n12 'sec ssh'
$o13 = @'
                html += mxSection('Weak spots and updates (this server)'
'@
$n13 = @'
                sec.weak = mxSection('Weak spots and updates (this server)'
'@
Edit-Text 'src\main\resources\static\admin.html' $o13 $n13 'sec weak'
$o14 = @'
            function mxRender(m) {
'@
$n14 = @'
            var mxView = null;
            var MX_TITLES = { todo: 'To do (needs SSH)', server: 'Server', app: 'App', db: 'Database', attacks: 'Attacks', ssh: 'Server break-ins (SSH)', weak: 'Weak spots', machines: 'Other machines', usage: 'Oracle usage & cost' };
            function mxBack() {
                var tab = tabContents.metrics;
                if (mxView && tab && !tab.classList.contains('hidden')) { mxView = null; if (mxLast) mxRender(mxLast); tab.scrollTop = 0; return true; }
                return false;
            }
            function mxTiles(m, sec) {
                var s = m.server || {}, a = m.app || {}, d = m.database || {}, at = m.attacks || {}, ssh = m.ssh || {}, v = m.vulnerabilities || {}, u = m.usage || {}, rq = (a.requests || {});
                var todo = 0, todoBad = 0;
                ((v.items || []).filter(function (i) { return i.level !== 'ok' && i.fix; })).forEach(function (i) { todo++; if (i.level === 'bad') todoBad++; });
                (m.machines || []).forEach(function (mc) { (mc.tasks || []).forEach(function (i) { if (i.level !== 'ok' && i.fix) { todo++; if (i.level === 'bad') todoBad++; } }); });
                var sl = Math.max(mxLevel(s.cpuPercent, 75, 90) === 'bad' ? 2 : mxLevel(s.cpuPercent, 75, 90) === 'warn' ? 1 : 0, mxLevel(s.ramPercent, 80, 92) === 'bad' ? 2 : mxLevel(s.ramPercent, 80, 92) === 'warn' ? 1 : 0, mxLevel(s.diskPercent, 80, 90) === 'bad' ? 2 : mxLevel(s.diskPercent, 80, 90) === 'warn' ? 1 : 0);
                var L = ['ok', 'warn', 'bad'];
                var sqli = (at.byKind || {}).SQLI || 0, blockedN = (m.blockedIps || []).length;
                var highIps = (at.topIps || []).filter(function (t) { return t.level === 'high'; }).length;
                var machines = m.machines || [], down = machines.filter(function (x) { return !x.online; }).length;
                var cur = (u.months || [])[0];
                var tiles = [
                    ['todo', '✔', todo ? todo + ' thing(s) to do' : 'All clear', todoBad ? 'bad' : todo ? 'warn' : 'ok'],
                    ['server', '▣', 'CPU ' + Math.round(s.cpuPercent || 0) + '% · RAM ' + Math.round(s.ramPercent || 0) + '%<br>Storage ' + Math.round(s.diskPercent || 0) + '%', L[sl]],
                    ['app', '◈', (rq.lastMinute || 0) + ' requests/min<br>' + (rq.serverErrors5Min || 0) + ' errors (5 min)', (rq.serverErrors5Min || 0) >= 5 ? 'warn' : 'ok'],
                    ['db', '▤', d.up ? 'Online · ' + d.latencyMs + ' ms<br>' + (d.connections || 0) + '/' + (d.maxConnections || '?') + ' connections' : 'Not answering', d.up ? (d.latencyMs >= 200 ? 'warn' : 'ok') : 'bad'],
                    ['attacks', '⚑', (at.total24h || 0) + ' attacks (24 h)<br>' + sqli + ' SQL tricks · ' + blockedN + ' blocked', highIps || sqli ? 'warn' : 'ok'],
                    ['ssh', '⚿', ssh.available ? (ssh.attempts || 0) + ' password guesses<br>' + (ssh.uniqueIps || 0) + ' address(es)' : 'Log not readable', ssh.available ? ((ssh.topIps || []).some(function (t) { return t.level === 'high'; }) ? 'warn' : 'ok') : 'info'],
                    ['weak', '⚠', (v.bad || 0) + ' serious · ' + (v.warn || 0) + ' to watch', v.bad ? 'bad' : v.warn ? 'warn' : 'ok'],
                    ['machines', '▦', machines.length ? machines.length + ' reporting' + (down ? ' · ' + down + ' not answering' : '') : 'None added yet', down ? 'bad' : machines.length ? 'ok' : 'info'],
                    ['usage', '$', cur ? mxGb(cur.txBytes).toFixed(1) + ' GB out this month<br>≈ $' + Number(cur.estimatedCost || 0).toFixed(2) + ' extra' : 'Collecting…', cur && cur.estimatedCost > 0 ? 'warn' : 'ok']
                ];
                return '<div class="mx-hub">' + tiles.map(function (t, i) {
                    return '<button type="button" class="mx-tile ' + t[3] + (i === 0 || i === 8 ? ' mx-wide' : '') + '" data-mxv="' + t[0] + '"><span class="t-dot"></span><div class="t-ic">' + t[1] + '</div><div class="t-ti">' + escapeHtml(MX_TITLES[t[0]]) + '</div><div class="t-v">' + t[2] + '</div></button>';
                }).join('') + '</div>';
            }
            function mxShow(m, sec) {
                var box = document.getElementById('mx-body');
                if (mxView && sec[mxView]) {
                    box.innerHTML = '<button type="button" class="mx-back" data-mxv="">← All metrics</button>' + sec[mxView];
                } else {
                    mxView = null;
                    box.innerHTML = sec.verdict + mxTiles(m, sec);
                }
            }
            (function () {
                var box = document.getElementById('mx-body');
                if (!box) return;
                box.addEventListener('click', function (e) {
                    var t = e.target.closest ? e.target.closest('[data-mxv]') : null;
                    if (!t || !mxLast) return;
                    mxView = t.getAttribute('data-mxv') || null;
                    mxRender(mxLast);
                    var tab = tabContents.metrics; if (tab) tab.scrollTop = 0;
                });
            })();
            function mxRender(m) {
'@
Edit-Text 'src\main\resources\static\admin.html' $o14 $n14 'tiles js'
$o15 = @'
                document.getElementById('mx-body').innerHTML = html;
'@
$n15 = @'
                mxShow(m, sec);
'@
Edit-Text 'src\main\resources\static\admin.html' $o15 $n15 'final'
$o16 = @'
if (section === 'metrics') { loadMetrics(); startMetricsRefresh(); }
'@
$n16 = @'
if (section === 'metrics') { mxView = null; if (mxLast) mxRender(mxLast); loadMetrics(); startMetricsRefresh(); }
'@
Edit-Text 'src\main\resources\static\admin.html' $o16 $n16 'reset view'
$o17 = @'
                    if (pinModalOverlay && pinModalOverlay.classList.contains('active')) return; // never bypass the PIN lock
'@
$n17 = @'
                    if (pinModalOverlay && pinModalOverlay.classList.contains('active')) return; // never bypass the PIN lock
                    if (typeof mxBack === 'function' && mxBack()) return;
'@
Edit-Text 'src\main\resources\static\admin.html' $o17 $n17 'android back'
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
Write-Host 'Done. Rebuild and restart the server: mvn clean package. Then fully close and reopen the admin app.' -ForegroundColor Cyan

