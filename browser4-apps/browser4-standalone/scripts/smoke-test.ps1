# Smoke test for browser4-standalone.exe
$ErrorActionPreference = 'Continue'
$exe = Join-Path $PSScriptRoot '..\target\browser4-standalone.exe'

Write-Host "Starting $exe ..."
$p = Start-Process -FilePath $exe -PassThru -WindowStyle Hidden `
    -RedirectStandardError (Join-Path $PSScriptRoot '..\target\smoke-stderr.txt') `
    -RedirectStandardOutput (Join-Path $PSScriptRoot '..\target\smoke-stdout.txt')

Write-Host "PID: $($p.Id) — waiting 15s for startup ..."
Start-Sleep -Seconds 15

if ($p.HasExited) {
    Write-Host "Process exited with code: $($p.ExitCode)"
    Write-Host "--- STDERR ---"
    Get-Content (Join-Path $PSScriptRoot '..\target\smoke-stderr.txt') -ErrorAction SilentlyContinue
    Write-Host "--- STDOUT ---"
    Get-Content (Join-Path $PSScriptRoot '..\target\smoke-stdout.txt') -ErrorAction SilentlyContinue
} else {
    Write-Host "Process is still running — trying health endpoint ..."
    try {
        $r = Invoke-WebRequest 'http://localhost:8182/actuator/health' -TimeoutSec 5 -UseBasicParsing
        Write-Host "Health: $($r.StatusCode) — $($r.Content)"
    } catch {
        Write-Host "Health check failed: $($_.Exception.Message)"
    }
    Stop-Process -Id $p.Id -Force
}
