
$ErrorActionPreference = "Stop"
$base = "http://localhost:8082"
$file = Join-Path $env:TEMP "admin-expiry-check.apk"
$pass = $null
$token = $null
$headers = $null

try {
    $email = Read-Host "Admin email"
    $secure = Read-Host "Admin password" -AsSecureString
    $cred = [PSCredential]::new($email, $secure)
    $pass = $cred.GetNetworkCredential().Password

    $json = @{ email = $email; password = $pass } | ConvertTo-Json
    $auth = Invoke-RestMethod -Uri "$base/api/app/admin-android-update/authorize" -Method POST -ContentType "application/json" -Body $json
    $token = $auth.downloadToken

    if ([string]::IsNullOrWhiteSpace($token)) {
        throw "No download token received."
    }

    Write-Host "Token lifetime: $($auth.expiresInSeconds) seconds"

    $headers = @{ "X-Admin-Download-Token" = $token }

    Invoke-WebRequest -Uri "$base/api/app/admin-android-update/download" -Headers $headers -OutFile $file
    Write-Host "PASS: Initial download succeeded." -ForegroundColor Green

    Write-Host "Waiting 125 seconds..."
    Start-Sleep -Seconds 125

    $status = 0
    try {
        Invoke-WebRequest -Uri "$base/api/app/admin-android-update/download" -Headers $headers -OutFile $file
        Write-Host "FAIL: Token still accepted after 125 seconds." -ForegroundColor Red
    }
    catch {
        if ($_.Exception.Response) {
            $status = [int]$_.Exception.Response.StatusCode
        }

        if ($status -eq 401) {
            Write-Host "PASS: Expired token rejected with HTTP 401." -ForegroundColor Green
        }
        else {
            Write-Host "INCONCLUSIVE: HTTP $status. $($_.Exception.Message)" -ForegroundColor Yellow
        }
    }
}
catch {
    Write-Host "TEST ERROR: $($_.Exception.Message)" -ForegroundColor Red
}
finally {
    Remove-Item $file -Force -ErrorAction SilentlyContinue
    $pass = $null
    $token = $null
    $headers = $null
    $json = $null
    $cred = $null
    $secure = $null
}
