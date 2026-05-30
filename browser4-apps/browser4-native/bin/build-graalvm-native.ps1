#!/usr/bin/env pwsh
# Build Browser4 Native as a GraalVM native executable on Windows.

param(
    [switch]$WithTests = $false,
    [switch]$CheckOnly = $false,
    [string]$JavaHome = "D:\Program Files\Java\graalvm-jdk-25.0.3+9.1"
)

$env:JAVA_HOME = "D:\Program Files\Java\graalvm-jdk-25.0.3+9.1"

$ErrorActionPreference = "Stop"

function Write-Info([string]$Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Green
}

function Write-Warn([string]$Message) {
    Write-Host "[WARN] $Message" -ForegroundColor Yellow
}

function Write-Err([string]$Message) {
    Write-Host "[ERROR] $Message" -ForegroundColor Red
}

function Ensure-Command([string]$CommandName, [string]$Hint) {
    if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        Write-Err "$CommandName not found. $Hint"
        exit 1
    }
}

function Import-VsBuildToolsEnvironment {
    $vswhere = Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"
    if (-not (Test-Path $vswhere)) {
        Write-Err "vswhere.exe not found. Please install Visual Studio 2022 Build Tools (C++ workload)."
        exit 1
    }

    $installationPath = & $vswhere -latest -products * -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 -property installationPath
    if (-not $installationPath) {
        Write-Err "Visual Studio Build Tools with VC++ toolchain not found."
        exit 1
    }

    # Prefer vcvars64 when available to guarantee x64 toolchain.
    $batCandidates = @(
        (Join-Path $installationPath "VC\Auxiliary\Build\vcvars64.bat"),
        (Join-Path $installationPath "Common7\Tools\VsDevCmd.bat")
    )

    $batPath = $batCandidates | Where-Object { Test-Path $_ } | Select-Object -First 1
    if (-not $batPath) {
        Write-Err "Neither vcvars64.bat nor VsDevCmd.bat was found under $installationPath"
        exit 1
    }

    Write-Info "Loading MSVC environment via $batPath"
    $batName = [System.IO.Path]::GetFileName($batPath)
    if ($batName -ieq "VsDevCmd.bat") {
        $cmdOutput = & cmd.exe /c "`"$batPath`" -arch=x64 -host_arch=x64 >nul && set"
    } else {
        $cmdOutput = & cmd.exe /c "`"$batPath`" >nul && set"
    }

    foreach ($line in $cmdOutput) {
        if ([string]::IsNullOrWhiteSpace($line) -or $line.IndexOf("=") -lt 1) {
            continue
        }
        $pair = $line -split "=", 2
        if ($pair.Count -eq 2) {
            [System.Environment]::SetEnvironmentVariable($pair[0], $pair[1], "Process")
        }
    }

    if (-not (Get-Command cl.exe -ErrorAction SilentlyContinue)) {
        Write-Err "MSVC compiler cl.exe is still unavailable after loading VS environment."
        exit 1
    }

    $clVersionLine = (& cmd.exe /c "cl 2>&1" | Select-Object -First 1)
    Write-Info "MSVC: $clVersionLine"
}

Write-Host "========================================" -ForegroundColor Cyan
Write-Host "Browser4 Native - GraalVM Native Builder" -ForegroundColor Cyan
Write-Host "========================================" -ForegroundColor Cyan
Write-Host ""

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Join-Path $scriptDir "..\..\.."
$mvnWrapper = Join-Path $repoRoot "mvnw.cmd"
$pomPath = Join-Path $scriptDir "..\pom.xml"

if (-not (Test-Path $mvnWrapper)) {
    Write-Err "Maven wrapper not found at: $mvnWrapper"
    exit 1
}

if (-not (Test-Path $pomPath)) {
    Write-Err "pom.xml not found at: $pomPath"
    exit 1
}

if ($JavaHome) {
    if (-not (Test-Path $JavaHome)) {
        Write-Err "Provided -JavaHome path does not exist: $JavaHome"
        exit 1
    }
    $env:JAVA_HOME = $JavaHome
}

if ($env:JAVA_HOME) {
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Write-Info "JAVA_HOME = $env:JAVA_HOME"
} else {
    Write-Warn "JAVA_HOME is not set; current java/native-image from PATH will be used."
}

Ensure-Command -CommandName java -Hint "Install GraalVM JDK and/or set -JavaHome."
Ensure-Command -CommandName native-image -Hint "Install GraalVM Native Image component."

$javaVersion = (& cmd.exe /c "java -version 2>&1" | Select-Object -First 1).Trim()
$nativeVersion = (& cmd.exe /c "native-image --version 2>&1" | Select-Object -First 1).Trim()
Write-Info "Java: $javaVersion"
Write-Info "native-image: $nativeVersion"

if (-not (Select-String -Path $pomPath -Pattern "<id>graalvm-native</id>" -SimpleMatch -Quiet)) {
    Write-Err "Maven profile 'graalvm-native' is missing in $pomPath"
    exit 1
}

Import-VsBuildToolsEnvironment

Write-Host ""
Write-Host "Build Configuration:" -ForegroundColor Yellow
Write-Host "  - Module: browser4-app/browser4-native" -ForegroundColor White
Write-Host "  - Profile: graalvm-native" -ForegroundColor White
Write-Host "  - Skip Tests: $(!$WithTests)" -ForegroundColor White
Write-Host "  - Check Only: $CheckOnly" -ForegroundColor White
Write-Host ""

if ($CheckOnly) {
    Write-Info "Environment checks completed. Build not started because -CheckOnly was specified."
    exit 0
}

$mvnArgs = @(
    "clean",
    "package",
    "-pl", "browser4-app/browser4-native",
    "-am",
    "-Pgraalvm-native"
)
if (-not $WithTests) {
    $mvnArgs += "-DskipTests"
}

Write-Info "Running: $mvnWrapper $($mvnArgs -join ' ')"
Write-Host ""

$startTime = Get-Date
Push-Location $repoRoot
try {
    & $mvnWrapper @mvnArgs
    if ($LASTEXITCODE -ne 0) {
        Write-Err "Build failed with exit code: $LASTEXITCODE"
        exit $LASTEXITCODE
    }
} finally {
    Pop-Location
}

$duration = (Get-Date) - $startTime
$targetDir = Join-Path $scriptDir "..\target"
$exeCandidates = Get-ChildItem -Path $targetDir -Filter "*.exe" -ErrorAction SilentlyContinue

Write-Host ""
Write-Host "========================================" -ForegroundColor Green
Write-Host "Build completed successfully" -ForegroundColor Green
Write-Host "Duration: $($duration.ToString('mm\:ss'))" -ForegroundColor Green
Write-Host "========================================" -ForegroundColor Green
Write-Host ""

if ($exeCandidates) {
    Write-Host "Native executables:" -ForegroundColor Yellow
    foreach ($exe in $exeCandidates) {
        $sizeMb = [math]::Round($exe.Length / 1MB, 2)
        Write-Host "  - $($exe.FullName) ($sizeMb MB)" -ForegroundColor White
    }
} else {
    Write-Warn "No .exe found in $targetDir. If fallback was used, inspect Maven output for artifact location."
}
