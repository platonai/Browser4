#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

$ErrorActionPreference = "Stop"

# This script installs Google Chrome on Windows only.
if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) {
    Write-Host "This script is Windows-only (installs Google Chrome). Use your package manager instead."
    exit 0
}

$chromeExe = "$Env:ProgramFiles\Google\Chrome\Application\chrome.exe"

if (Test-Path $chromeExe) {
    Write-Host "Google Chrome is already installed. Skipping installation."
    exit 0
}

$installerUrl = "https://dl.google.com/chrome/install/latest/chrome_installer.exe"
$tempInstaller = "$env:TEMP\chrome_installer.exe"

Write-Host "Downloading Google Chrome installer..."
Invoke-WebRequest -Uri $installerUrl -OutFile $tempInstaller

Write-Host "Installing Google Chrome silently..."
Start-Process -FilePath $tempInstaller -ArgumentList "/silent /install" -Wait

Remove-Item $tempInstaller -Force

if (Test-Path $chromeExe) {
    Write-Host "Google Chrome installation completed successfully."
    exit 0
} else {
    Write-Error "Google Chrome installation failed."
    exit 1
}
