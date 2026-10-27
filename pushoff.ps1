# DeenWallet Transfer - emergency fix: stop the admin app closing ("DeenWallet app has stopped")
#
# Run from the project root (the folder that contains pom.xml), then rebuild and restart the server:
#     powershell -ExecutionPolicy Bypass -File .\pushoff.ps1
#
# The admin app loads this page from the server, so the fix reaches the phone as soon as the server is updated; no
# new APK is needed. It switches phone-push registration off in admin.html (the bell, in-app alerts and vibration
# keep working). Push is switched on again later, once the admin APK is confirmed to contain its Firebase file.
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
$text = $raw.Replace("`r`n", "`n")
if ($text.Contains('ADMIN_PUSH_ENABLED')) { Write-Host 'Already applied - nothing to do.' -ForegroundColor Yellow; exit 0 }
if (-not $text.Contains('function adminPushInit()')) { throw 'admin.html does not have the admin notifications yet (run mailbox3.ps1 first). Nothing changed.' }
function Edit-Text([string]$old, [string]$new, [string]$what) {
  $old = $old.TrimEnd("`n"); $new = $new.TrimEnd("`n")
  $i = $script:text.IndexOf($old, [StringComparison]::Ordinal)
  if ($i -lt 0) { throw "admin.html: the '$what' part was not found (the file differs from the expected version). Nothing changed." }
  if ($script:text.IndexOf($old, $i + 1, [StringComparison]::Ordinal) -ge 0) { throw "admin.html: the '$what' part was found twice. Nothing changed." }
  $script:text = $script:text.Substring(0, $i) + $new + $script:text.Substring($i + $old.Length)
}
$o0 = @'
            var ADMIN_FCM_KEY = 'dw_admin_fcm_token';
'@
$n0 = @'
            var ADMIN_FCM_KEY = 'dw_admin_fcm_token';
            // Phone push is switched off until the admin APK is confirmed to include its Firebase file (set to true then).
            var ADMIN_PUSH_ENABLED = false;
'@
Edit-Text $o0 $n0 'push switch 0'

$o1 = @'
            function adminPushInit() {
                if (adminNotif.pushBusy || adminNotif.pushDone) return;
'@
$n1 = @'
            function adminPushInit() {
                if (!ADMIN_PUSH_ENABLED) return;
                if (adminNotif.pushBusy || adminNotif.pushDone) return;
'@
Edit-Text $o1 $n1 'push switch 1'

if ($crlf) { $text = $text.Replace("`n", "`r`n") }
[System.IO.File]::WriteAllText($htmlPath, $text, $utf8)
Write-Host ('changed   ' + $htmlRel) -ForegroundColor Green
Write-Host ''
Write-Host 'Done. Rebuild and restart the server: mvn clean package. Then fully close the admin app and open it again.' -ForegroundColor Cyan
