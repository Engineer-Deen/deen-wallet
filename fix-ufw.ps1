# DeenWallet Transfer - fix the "Copy server firewall commands" lines (ufw: Invalid position 1)
#
# Run from the SERVER project root (the folder that contains pom.xml):
#     powershell -ExecutionPolicy Bypass -File .\fix-ufw.ps1
#
# The copied commands used "ufw insert 1", which ufw refuses when it has no rules yet. They now use "ufw prepend deny from ...",
# which works with or without existing rules and still goes before any "allow" rule (so the address really is blocked).
# Changes admin.html only. Run metrics3.ps1 first.
# Everything is checked first; nothing is written unless it all fits. No backup files are created.
$ErrorActionPreference = 'Stop'
$utf8 = New-Object System.Text.UTF8Encoding($false)
$root = (Get-Location).Path
if (-not (Test-Path (Join-Path $root 'pom.xml'))) { throw 'Run this from the folder that contains pom.xml.' }
if (-not (Test-Path (Join-Path $root 'src\main\resources\db\migration\V47__usage_monthly.sql'))) { throw 'Run metrics3.ps1 first (V47 is missing). Nothing changed.' }
if ((Get-Content -Raw -LiteralPath (Join-Path $root 'src\main\resources\static\admin.html')).Contains('ufw prepend deny from')) { Write-Host 'Already applied - nothing to do.' -ForegroundColor Yellow; exit 0 }
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
'<div class="mx-fix">sudo ufw insert 1 deny from ' + escapeHtml(x.ip) + '</div></div>';
'@
$n1 = @'
'<div class="mx-fix">sudo ufw prepend deny from ' + escapeHtml(x.ip) + '</div></div>';
'@
Edit-Text 'src\main\resources\static\admin.html' $o1 $n1 'ufw card'
$o2 = @'
return 'sudo ufw insert 1 deny from ' + ip;
'@
$n2 = @'
return 'sudo ufw prepend deny from ' + ip;
'@
Edit-Text 'src\main\resources\static\admin.html' $o2 $n2 'ufw copy'
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

