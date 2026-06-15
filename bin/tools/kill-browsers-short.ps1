#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

foreach ($proc in (Get-Process -Name "chrome")) {
    $cmdLine = (Get-CimInstance Win32_Process -Filter "ProcessId = $($proc.Id)").CommandLine
    if ($cmdLine -and $cmdLine -match "PULSAR_CHROME") {
        Write-Host "Killing process $($proc.Id) for browser $browser with command line: $cmdLine" -ForegroundColor Yellow
        Stop-Process -Id $proc.Id -Force
    }
}
