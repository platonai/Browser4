#!/usr/bin/env pwsh
# Build Windows installer for Browser4 Agents
# This script creates both a portable app-image and Windows EXE installer

param(
    [switch]$Installer = $false,
    [switch]$WithTests = $false,
    [string]$AppVersion = "0.0.1"
)

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Browser4 Agents - Windows Installer Builder" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

# Check if Java is available
try {
    $javaVersion = & java -version 2>&1 | Select-Object -First 1
    Write-Host "[INFO] Java found: $javaVersion" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] Java not found. Please install JDK 17 or later." -ForegroundColor Red
    exit 1
}

# Check if jpackage is available
try {
    $jpackageVersion = & jpackage --version 2>&1
    Write-Host "[INFO] jpackage found: version $jpackageVersion" -ForegroundColor Green
} catch {
    Write-Host "[ERROR] jpackage not found. Please ensure you're using JDK 17 or later (not JRE)." -ForegroundColor Red
    exit 1
}

Write-Host ""

# Display build configuration
Write-Host "Build Configuration:" -ForegroundColor Yellow
Write-Host "  - Portable App-Image: YES" -ForegroundColor White
if ($Installer) {
    Write-Host "  - Windows Installer: YES" -ForegroundColor White
    Write-Host "  - [WARN] Building installer requires WiX Toolset v3.x" -ForegroundColor Yellow

    # Check for WiX
    try {
        $null = & candle.exe '-?' 2>&1
        Write-Host "  - WiX Toolset: Found" -ForegroundColor Green
    } catch {
        Write-Host "  - [ERROR] WiX Toolset not found in PATH" -ForegroundColor Red
        Write-Host "  - Please install from: https://github.com/wixtoolset/wix3/releases" -ForegroundColor Red
        exit 1
    }
} else {
    Write-Host "  - Windows Installer: NO (use -Installer flag to enable)" -ForegroundColor White
}
Write-Host "  - Skip Tests: $(!$WithTests)" -ForegroundColor White
Write-Host "  - App Version: $AppVersion" -ForegroundColor White
Write-Host ""

# Determine Maven wrapper location
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$mvnWrapper = Join-Path $scriptDir "..\..\mvnw.cmd"

if (-not (Test-Path $mvnWrapper)) {
    Write-Host "[ERROR] Maven wrapper not found at: $mvnWrapper" -ForegroundColor Red
    exit 1
}

# Build Maven command
$mvnArgs = @(
    "clean",
    "package",
    "-Pwin-jpackage",
    "-Djpackage.appVersion=$AppVersion"
)

if (-not $WithTests) {
    $mvnArgs += "-DskipTests"
}

if ($Installer) {
    $mvnArgs += "-Djpackage.installer.skip=false"
}

Write-Host "[INFO] Building Browser4 Agents..." -ForegroundColor Cyan
Write-Host "[INFO] Command: $mvnWrapper $($mvnArgs -join ' ')" -ForegroundColor Gray
Write-Host ""

# Execute build
$startTime = Get-Date
& cmd /c $mvnWrapper @mvnArgs

if ($LASTEXITCODE -ne 0) {
    Write-Host ""
    Write-Host "[ERROR] Build failed with exit code: $LASTEXITCODE" -ForegroundColor Red
    exit $LASTEXITCODE
}

$duration = (Get-Date) - $startTime
Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "Build completed successfully!" -ForegroundColor Green
Write-Host "Duration: $($duration.ToString('mm\:ss'))" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

# Display output files
Write-Host "Output files:" -ForegroundColor Yellow
$jarPath = Join-Path $scriptDir "target\Browser4.jar"
$appImagePath = Join-Path $scriptDir "target\jpackage\app-image\Browser4\Browser4.exe"
$installerPath = Join-Path $scriptDir "target\jpackage\dist\Browser4-$AppVersion.exe"

if (Test-Path $jarPath) {
    $jarSize = (Get-Item $jarPath).Length / 1MB
    Write-Host "  - JAR: target\Browser4.jar ($([math]::Round($jarSize, 2)) MB)" -ForegroundColor White
}

if (Test-Path $appImagePath) {
    Write-Host "  - App-Image: target\jpackage\app-image\Browser4\Browser4.exe" -ForegroundColor White
}

if ($Installer -and (Test-Path $installerPath)) {
    $installerSize = (Get-Item $installerPath).Length / 1MB
    Write-Host "  - Installer: target\jpackage\dist\Browser4-$AppVersion.exe ($([math]::Round($installerSize, 2)) MB)" -ForegroundColor White
}

Write-Host ""
Write-Host "To run the portable version:" -ForegroundColor Cyan
Write-Host "  target\jpackage\app-image\Browser4\Browser4.exe" -ForegroundColor White
Write-Host ""

if ($Installer) {
    Write-Host "To install:" -ForegroundColor Cyan
    Write-Host "  target\jpackage\dist\Browser4-$AppVersion.exe" -ForegroundColor White
    Write-Host ""
}

Write-Host "The application will start on port 8182 by default." -ForegroundColor Gray
Write-Host "Access the WebUI at: http://localhost:8182/command.html" -ForegroundColor Gray
