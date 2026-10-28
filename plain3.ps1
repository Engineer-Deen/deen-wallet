# DeenWallet Transfer - fix: the new splash logo was blocked by security (follow-up to plain1/plain2)
# Run from the folder that contains pom.xml:  powershell -ExecutionPolicy Bypass -File .\plain3.ps1
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = (Get-Location).Path
if (-not (Test-Path (Join-Path $root 'pom.xml'))) { throw 'Run this from the folder that contains pom.xml.' }
$files = @{}
function Load-File([string]$rel) {
  $p = Join-Path $root $rel
  if (-not (Test-Path $p)) { throw "$rel was not found. Nothing changed." }
  $raw = [System.IO.File]::ReadAllText($p, $utf8)
  $script:files[$rel] = @{ path = $p; crlf = $raw.Contains("`r`n"); text = $raw.Replace("`r`n", "`n") }
}
$skipped = @()
function Edit-Text([string]$rel, [string]$old, [string]$new, [string]$what, [bool]$optional) {
  $f = $script:files[$rel]
  $i = $f.text.IndexOf($old, [StringComparison]::Ordinal)
  if ($i -lt 0 -or $f.text.IndexOf($old, $i + 1, [StringComparison]::Ordinal) -ge 0) {
    if ($optional) { $script:skipped += "${rel}: $what"; return }
    throw "${rel}: the '$what' part was not found (or found twice). Nothing changed."
  }
  $f.text = $f.text.Substring(0, $i) + $new + $f.text.Substring($i + $old.Length)
}
$sec = 'src\main\java\com\glr\deenwallet\config\SecurityConfig.java'
$adm = 'src\main\resources\static\admin.html'
$aut = 'src\main\resources\static\auth.html'
Load-File $sec; Load-File $adm; Load-File $aut
if ($files[$sec].text.Contains('"/deenwallet-logo-md.png"')) { Write-Host 'Already applied - nothing to do.' -ForegroundColor Yellow; exit 0 }
Edit-Text $sec '"/deenwallet-logo-sm.png",' ('"/deenwallet-logo-sm.png",' + "`n" + '                                "/deenwallet-logo-md.png",') 'allow new logo' $false
# if the lighter logo ever fails to load, fall back to the original one
$fb = ' onerror="this.onerror=null;this.src=''deenwallet-logo.png''"'
Edit-Text $adm '<img class="admin-splash-logo" src="deenwallet-logo-md.png" alt="DeenWallet Transfer" width="480" height="423" fetchpriority="high" />' ('<img class="admin-splash-logo" src="deenwallet-logo-md.png" alt="DeenWallet Transfer" width="480" height="423" fetchpriority="high"' + $fb + ' />') 'admin splash' $true
Edit-Text $adm '<img class="admin-login-logo" src="deenwallet-logo-md.png" alt="DeenWallet Transfer" />' ('<img class="admin-login-logo" src="deenwallet-logo-md.png" alt="DeenWallet Transfer"' + $fb + ' />') 'admin login' $true
Edit-Text $aut '<img class="splash-logo" src="deenwallet-logo-md.png?v=2" alt="DeenWallet Transfer" width="480" height="423" fetchpriority="high" />' ('<img class="splash-logo" src="deenwallet-logo-md.png?v=2" alt="DeenWallet Transfer" width="480" height="423" fetchpriority="high"' + $fb + ' />') 'app splash' $true
foreach ($rel in $files.Keys) {
  $f = $files[$rel]; $t = $f.text
  if ($f.crlf) { $t = $t.Replace("`n", "`r`n") }
  [System.IO.File]::WriteAllText($f.path, $t, $utf8)
  Write-Host ('changed   ' + $rel) -ForegroundColor Green
}
if ($skipped.Count -gt 0) { Write-Host 'Skipped (optional):' -ForegroundColor Yellow; $skipped | ForEach-Object { Write-Host ('  ' + $_) } }
Write-Host 'Done. Rebuild and restart the server: mvn clean package.' -ForegroundColor Cyan
