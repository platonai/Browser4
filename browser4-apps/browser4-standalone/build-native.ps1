<#
.SYNOPSIS
Build a GraalVM native image for browser4-standalone.

.DESCRIPTION
This script builds a native executable for the Browser4 standalone
application using GraalVM native-image.

Prerequisites:
  - JAVA_HOME must point to a GraalVM JDK (21+) with native-image installed.
    Install native-image via:  gu install native-image
  - The browser4-standalone module and its dependencies must already be
    installed in the local Maven repository:
      mvn install -Passet-standalone -DskipTests

The native image is written to:
  target\browser4-standalone.exe

The build may take 5-15 minutes and requires at least 8 GB of free RAM.

.PARAMETER ExtraArgs
Additional arguments to pass to Maven (e.g. "-DskipTests").
These are forwarded directly to the mvn command.

.EXAMPLE
.\build-native.ps1

.EXAMPLE
.\build-native.ps1 -ExtraArgs "-X"
#>

param(
    [string]$ExtraArgs = ""
)

$ErrorActionPreference = 'Stop'

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Resolve-Path (Join-Path $scriptDir '..\..')

# --- Validate JAVA_HOME ---------------------------------------------------
if (-not $env:JAVA_HOME) {
    Write-Error "JAVA_HOME is not set. JAVA_HOME must point to a GraalVM JDK (21+) with native-image."
    exit 1
}

$nativeImageExe = Join-Path $env:JAVA_HOME 'bin\native-image.cmd'
if (-not (Test-Path $nativeImageExe)) {
    Write-Error "native-image.cmd not found in JAVA_HOME\bin. Install it via: gu install native-image"
    exit 1
}

Write-Host "Using GraalVM JDK: $env:JAVA_HOME" -ForegroundColor Cyan
& $nativeImageExe --version

# --- Build ----------------------------------------------------------------
Set-Location $repoRoot

Write-Host ""
Write-Host "Building native image for browser4-standalone..." -ForegroundColor Cyan
Write-Host "This may take 5-15 minutes."
Write-Host ""

$mvnArgs = @('-Pnative', '-Passet-standalone', 'clean', 'package', '-DskipTests')
if ($ExtraArgs) {
    $mvnArgs += $ExtraArgs
}

& mvn @mvnArgs
if ($LASTEXITCODE -ne 0) {
    Write-Error "Native image build failed with exit code $LASTEXITCODE"
    exit $LASTEXITCODE
}

# --- Report ---------------------------------------------------------------
$exePath = Join-Path $scriptDir 'target\browser4-standalone.exe'
$linuxPath = Join-Path $scriptDir 'target\browser4-standalone'

Write-Host ""
Write-Host "======================================================================" -ForegroundColor Green
Write-Host "Native image built successfully." -ForegroundColor Green
Write-Host ""

if (Test-Path $exePath) {
    $size = (Get-Item $exePath).Length / 1MB
    Write-Host "  $exePath  ($([math]::Round($size, 1)) MB)"
    Write-Host ""
    Write-Host "Run it with:  $exePath"
} elseif (Test-Path $linuxPath) {
    $size = (Get-Item $linuxPath).Length / 1MB
    Write-Host "  $linuxPath  ($([math]::Round($size, 1)) MB)"
    Write-Host ""
    Write-Host "Run it with:  $linuxPath"
}
