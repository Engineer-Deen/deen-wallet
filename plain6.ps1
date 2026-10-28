# DeenWallet Transfer - user app opens like the admin app: logo page, then straight into the app
#
# Run from the SERVER project root (the folder that contains pom.xml):
#     powershell -ExecutionPolicy Bypass -File .\plain6.ps1
#
#   - User app: after the phone's own logo, the same kind of logo page as the admin app shows briefly (once each time the app is started), then the app appears. No welcome / login page.
#   - Admin app: not touched.
# After it: mvn clean package, restart the server. Close and reopen both apps. No new APK is needed.
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
function Edit-Text([string]$rel, [string]$old, [string]$new, [string]$what) {
  $old = $old.Replace("`r`n", "`n").TrimEnd("`n"); $new = $new.Replace("`r`n", "`n").TrimEnd("`n")
  $f = $script:files[$rel]
  $i = $f.text.IndexOf($old, [StringComparison]::Ordinal)
  if ($i -lt 0) { throw "${rel}: the '$what' part was not found (the file differs from the expected version). Nothing changed." }
  if ($f.text.IndexOf($old, $i + 1, [StringComparison]::Ordinal) -ge 0) { throw "${rel}: the '$what' part was found twice. Nothing changed." }
  $f.text = $f.text.Substring(0, $i) + $new + $f.text.Substring($i + $old.Length)
}
Load-File 'src\main\resources\static\index.html'
if ($files['src\main\resources\static\index.html'].text.Contains('id="dw-launch"')) { Write-Host 'Already applied - nothing to do.' -ForegroundColor Yellow; exit 0 }

$o1 = @'
</head>
'@
$n1 = @'
    <link rel="preload" as="image" href="deenwallet-logo-md.png" />
    <style>
        /* opening logo page - same look as the admin app */
        #dw-launch { position: fixed; inset: 0; z-index: 99999; display: flex; align-items: center; justify-content: center; background: linear-gradient(145deg, #FFFFFF 0%, #F1F8FA 100%); transition: opacity .35s ease, visibility .35s ease; }
        #dw-launch.hide { opacity: 0; visibility: hidden; pointer-events: none; }
        #dw-launch .dw-l-inner { text-align: center; animation: dwLaunchIn .55s ease-out both; }
        #dw-launch .dw-l-logo { width: min(260px, 78vw); height: auto; border-radius: 18px; box-shadow: 0 18px 40px rgba(0, 0, 0, .18); }
        #dw-launch .dw-l-name { margin-top: 14px; font-size: 29px; font-weight: 850; color: #1C7A91; }
        #dw-launch .dw-l-sub { margin-top: 6px; font-size: 13px; color: #6E7C82; }
        @keyframes dwLaunchIn { from { opacity: 0; transform: translateY(8px) scale(.97); } to { opacity: 1; transform: none; } }
    </style>
</head>
'@
Edit-Text 'src\main\resources\static\index.html' $o1 $n1 'splash css'
$o2 = @'
<body>
'@
$n2 = @'
<body>
<div id="dw-launch" aria-label="DeenWallet Transfer loading"><div class="dw-l-inner"><img class="dw-l-logo" src="deenwallet-logo-md.png" alt="DeenWallet Transfer" width="480" height="423" onerror="this.onerror=null;this.src='deenwallet-logo-sm.png'" /><div class="dw-l-name">DeenWallet Transfer</div><div class="dw-l-sub">Secure. Simple. Yours.</div></div></div>
<script>
    // Opening logo page, like the admin app: shows when the app is started, then the app appears.
    (function () {
        var s = document.getElementById('dw-launch');
        if (!s) return;
        try { if (sessionStorage.getItem('dw_launch_done')) { s.parentNode.removeChild(s); return; } sessionStorage.setItem('dw_launch_done', '1'); } catch (e) { }
        var t0 = Date.now(), done = false;
        function hide() {
            if (done) return; done = true;
            setTimeout(function () {
                s.classList.add('hide');
                setTimeout(function () { if (s.parentNode) s.parentNode.removeChild(s); }, 450);
            }, Math.max(0, 900 - (Date.now() - t0)));
        }
        if (document.readyState === 'complete') hide(); else window.addEventListener('load', hide);
        setTimeout(hide, 4000);
    })();
</script>
'@
Edit-Text 'src\main\resources\static\index.html' $o2 $n2 'splash html'
foreach ($rel in $files.Keys) {
  $f = $files[$rel]; $t = $f.text
  if ($f.crlf) { $t = $t.Replace("`n", "`r`n") }
  [System.IO.File]::WriteAllText($f.path, $t, $utf8)
  Write-Host ('changed   ' + $rel) -ForegroundColor Green
}
Write-Host 'Done. Rebuild and restart the server: mvn clean package. Then fully close the user app and open it again.' -ForegroundColor Cyan

