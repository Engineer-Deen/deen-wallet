# DeenWallet Transfer - admin alerts: top banner popup, "Turn on phone alerts" button and status
#
# Run from the project root (the folder that contains pom.xml, the SERVER project), after alerts2.ps1:
#     powershell -ExecutionPolicy Bypass -File .\alerts3.ps1
#
# What it does (admin.html only; the admin app loads it from the server, so no new APK is needed for this part):
#   - A new alert shows as a banner at the top of the screen (tap it to open the bell), like the customer app's popup.
#   - The bell sheet now says whether phone alerts are ON, blocked, or not asked yet, and has a
#     "Turn on phone alerts" button that makes Android show the "Allow notifications" question.
# Everything is checked first; nothing is written unless it fits. No backup files are created.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = (Get-Location).Path
if (-not (Test-Path (Join-Path $root 'pom.xml'))) { throw 'Run this from the folder that contains pom.xml.' }
$htmlRel = 'src\main\resources\static\admin.html'
$htmlPath = Join-Path $root $htmlRel
if (-not (Test-Path $htmlPath)) { throw "$htmlRel was not found. Nothing changed." }
$raw = [System.IO.File]::ReadAllText($htmlPath, $utf8)
$crlf = $raw.Contains("`r`n")
$html = $raw.Replace("`r`n", "`n")
if ($html.Contains('function notifPopup(')) { Write-Host 'Already applied - nothing to do.' -ForegroundColor Yellow; exit 0 }
if (-not $html.Contains('function adminPushInit()') -or -not $html.Contains('EMAIL_RECEIVED')) { throw 'Run mailbox3.ps1 and alerts2.ps1 first. Nothing changed.' }
if ($html.Contains('var ADMIN_PUSH_ENABLED = false;')) { throw 'Phone push is still switched off. Run alerts2.ps1 first. Nothing changed.' }
function Edit-Text([string]$old, [string]$new, [string]$what) {
  $old = $old.TrimEnd("`n"); $new = $new.TrimEnd("`n")
  $i = $script:html.IndexOf($old, [StringComparison]::Ordinal)
  if ($i -lt 0) { throw "admin.html: the '$what' part was not found (the file differs from the expected version). Nothing changed." }
  if ($script:html.IndexOf($old, $i + 1, [StringComparison]::Ordinal) -ge 0) { throw "admin.html: the '$what' part was found twice. Nothing changed." }
  $script:html = $script:html.Substring(0, $i) + $new + $script:html.Substring($i + $old.Length)
}
$o0 = @'
        .nt-time { margin-top: 4px; font-size: 11px; color: var(--muted); }
'@
$n0 = @'
        .nt-time { margin-top: 4px; font-size: 11px; color: var(--muted); }
        .nt-pop { position: fixed; left: 12px; right: 12px; top: calc(10px + env(safe-area-inset-top, 0px)); z-index: 400; max-width: 520px; margin: 0 auto; padding: 12px 14px; border-radius: 14px; background: #12343D; color: #fff; box-shadow: 0 10px 30px rgba(0, 0, 0, .35); cursor: pointer; transform: translateY(-160%); transition: transform .25s ease; }
        .nt-pop.show { transform: translateY(0); }
        .nt-pop strong { display: block; font-size: 14px; }
        .nt-pop span { display: block; margin-top: 2px; font-size: 13px; opacity: .92; word-break: break-word; }
'@
Edit-Text $o0 $n0 'popup css'

$o1 = @'
<div id="notif-error" class="wallet-note bad hidden"></div>
'@
$n1 = @'
<div id="notif-error" class="wallet-note bad hidden"></div>
                <div class="wallet-note" id="notif-push-status" style="margin-top:8px;"></div>
                <button type="button" class="btn-outline hidden" id="notif-push-btn" style="width:100%;margin-top:6px;">Turn on phone alerts</button>
'@
Edit-Text $o1 $n1 'status html'

$o2 = @'
            function notifSetBadge(n) {
'@
$n2 = @'
            // a banner at the top of the screen (tap it to open the bell)
            function notifPopup(title, body) {
                try {
                    var old = document.getElementById('nt-pop');
                    if (old && old.parentNode) old.parentNode.removeChild(old);
                    var d = document.createElement('div');
                    d.id = 'nt-pop';
                    d.className = 'nt-pop';
                    d.setAttribute('role', 'alert');
                    var inner = document.createElement('div');
                    var s = document.createElement('strong');
                    s.textContent = title || 'Notification';
                    var sp = document.createElement('span');
                    sp.textContent = body || '';
                    inner.appendChild(s);
                    inner.appendChild(sp);
                    d.appendChild(inner);
                    document.body.appendChild(d);
                    requestAnimationFrame(function () { d.classList.add('show'); });
                    var gone = function () {
                        d.classList.remove('show');
                        setTimeout(function () { if (d.parentNode) d.parentNode.removeChild(d); }, 300);
                    };
                    d.addEventListener('click', function () { gone(); notifSheet(true); notifLoad(); notifPushStatus(); });
                    setTimeout(gone, 7000);
                } catch (e) { showToast((title || 'Notification') + ': ' + (body || '')); }
            }
            function notifSetBadge(n) {
'@
Edit-Text $o2 $n2 'popup fn'

$o3 = @'
if (fresh) showToast(fresh.title + ': ' + fresh.message);
'@
$n3 = @'
if (fresh) notifPopup(fresh.title, fresh.message);
'@
Edit-Text $o3 $n3 'announce popup'

$o4 = @'
showToast(((n && n.title) || 'Notification') + ((n && n.body) ? ': ' + n.body : ''));
'@
$n4 = @'
notifPopup((n && n.title) || 'Notification', (n && n.body) || '');
'@
Edit-Text $o4 $n4 'push popup'

$o5 = @'
            // called on logout / session end: this phone stops getting this admin's alerts
'@
$n5 = @'
            // what the bell sheet says about phone alerts, and the button that asks Android for permission
            async function notifPushStatus() {
                var el = document.getElementById('notif-push-status');
                var btn = document.getElementById('notif-push-btn');
                if (!el) return;
                var C = window.Capacitor;
                var P = C && C.Plugins && C.Plugins.PushNotifications;
                var msg = '';
                var showBtn = false;
                if (!C || typeof C.isNativePlatform !== 'function' || !C.isNativePlatform()) {
                    msg = 'Phone alerts work in the installed admin app. In a browser you only get the bell.';
                } else if (!P) {
                    msg = 'This app version cannot receive phone alerts. Install admin app 1.8 or newer.';
                } else {
                    try {
                        var perm = await P.checkPermissions();
                        var r = perm && perm.receive;
                        var registered = false;
                        try { registered = !!localStorage.getItem(ADMIN_FCM_KEY); } catch (e) { }
                        if (r === 'granted' && registered) msg = 'Phone alerts are ON for this phone.';
                        else if (r === 'granted') { msg = 'Notifications are allowed, but this phone is not registered yet. Tap the button to register it.'; showBtn = true; }
                        else if (r === 'denied') msg = 'Notifications are blocked. Open Android Settings > Apps > DeenWallet Admin > Notifications, allow them, then come back here.';
                        else { msg = 'Phone alerts are off. Tap the button to allow notifications.'; showBtn = true; }
                    } catch (e) { msg = 'Could not read the notification setting: ' + (e && e.message ? e.message : e); }
                }
                el.textContent = msg;
                if (btn) btn.classList.toggle('hidden', !showBtn);
            }
            function notifPushEnable(btn) {
                btn.disabled = true;
                btn.textContent = 'Working...';
                adminNotif.pushDone = false;
                adminNotif.pushBusy = false;
                adminPushInit();
                setTimeout(notifPushStatus, 2500);
                setTimeout(function () { notifPushStatus().then(function () { btn.disabled = false; btn.textContent = 'Turn on phone alerts'; }); }, 6000);
            }
            // called on logout / session end: this phone stops getting this admin's alerts
'@
Edit-Text $o5 $n5 'status fns'

$o6 = @'
                bell.addEventListener('click', function () { notifSheet(true); notifLoad(); });
'@
$n6 = @'
                bell.addEventListener('click', function () { notifSheet(true); notifLoad(); notifPushStatus(); });
                var pushBtn = document.getElementById('notif-push-btn');
                if (pushBtn) pushBtn.addEventListener('click', function () { notifPushEnable(pushBtn); });
'@
Edit-Text $o6 $n6 'bell click'

$o7 = @'
                if (adminNotif.sentToken === token) return;
                adminNotif.sentToken = token;
'@
$n7 = @'
                var stored = null;
                try { stored = localStorage.getItem(ADMIN_FCM_KEY); } catch (e) { }
                if (adminNotif.sentToken === token && stored === token) return;
                adminNotif.sentToken = token;
'@
Edit-Text $o7 $n7 'send token'

if ($crlf) { $html = $html.Replace("`n", "`r`n") }
[System.IO.File]::WriteAllText($htmlPath, $html, $utf8)
Write-Host ('changed   ' + $htmlRel) -ForegroundColor Green
Write-Host ''
Write-Host 'Done. Rebuild and restart the server: mvn clean package. Then fully close the admin app and open it again.' -ForegroundColor Cyan
