[CmdletBinding()]
param(
    [string]$ProjectRoot = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path,
    [string]$ImageName = "browser4-builder",
    [switch]$SkipDockerBuild,
    [switch]$DryRun
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

$OutputDir = Join-Path $ProjectRoot "bin"
$CliDir = Join-Path $ProjectRoot "browser4-cli"
$DockerfilePath = Join-Path $ProjectRoot "docker/Dockerfile.build"

$Targets = @(
    @{ Target = "x86_64-unknown-linux-gnu"; Output = "browser4-linux-x64" },
    @{ Target = "aarch64-unknown-linux-gnu"; Output = "browser4-linux-arm64" },
    @{ Target = "x86_64-pc-windows-gnu"; Output = "browser4-win32-x64.exe" },
    @{ Target = "x86_64-apple-darwin"; Output = "browser4-darwin-x64" },
    @{ Target = "aarch64-apple-darwin"; Output = "browser4-darwin-arm64" },
    @{ Target = "x86_64-unknown-linux-musl"; Output = "browser4-linux-musl-x64" },
    @{ Target = "aarch64-unknown-linux-musl"; Output = "browser4-linux-musl-arm64" }
)

function Invoke-DockerCommand {
    param([string[]]$Args)

    if ($DryRun) {
        Write-Host ("[DRY-RUN] docker " + ($Args -join " ")) -ForegroundColor DarkYellow
        return
    }

    & docker @Args
    if ($LASTEXITCODE -ne 0) {
        throw "Docker command failed with exit code $LASTEXITCODE"
    }
}

function Build-Target {
    param(
        [string]$Target,
        [string]$OutputName
    )

    Write-Host "Building for $Target..." -ForegroundColor Yellow

    $containerCmd = "cargo zigbuild --release --target $Target && cp /build/target/$Target/release/browser4* /output/$OutputName && chmod +x /output/$OutputName 2>/dev/null || true"

    Invoke-DockerCommand -Args @(
        "run", "--rm",
        "-v", "${CliDir}:/build",
        "-v", "${OutputDir}:/output",
        $ImageName,
        "-c", $containerCmd
    )

    $artifactPath = Join-Path $OutputDir $OutputName
    if (-not (Test-Path -Path $artifactPath -PathType Leaf)) {
        throw "Failed to build $OutputName"
    }

    $artifactSize = (Get-Item -Path $artifactPath).Length
    if ($artifactSize -le 0) {
        throw "Built artifact is empty: $OutputName"
    }

    Write-Host "Built $OutputName" -ForegroundColor Green
}

if (-not (Get-Command docker -ErrorAction SilentlyContinue)) {
    throw "Docker CLI not found in PATH. Install Docker Desktop and retry."
}

Write-Host "Building browser4 for all platforms..." -ForegroundColor Yellow
Write-Host ""

New-Item -ItemType Directory -Force -Path $OutputDir | Out-Null

if (-not $SkipDockerBuild) {
    Write-Host "Building Docker cross-compilation image..." -ForegroundColor Yellow
    Invoke-DockerCommand -Args @(
        "build",
        "-t", $ImageName,
        "-f", $DockerfilePath,
        $ProjectRoot
    )
    Write-Host ""
}

foreach ($entry in $Targets) {
    Build-Target -Target $entry.Target -OutputName $entry.Output
}

Write-Host ""
Write-Host "Build complete" -ForegroundColor Green
Write-Host "Binaries are in: $OutputDir"

if ($DryRun) {
    Write-Host "[DRY-RUN] Skipping artifact listing." -ForegroundColor DarkYellow
}
else {
    Get-ChildItem -Path $OutputDir -Filter "browser4-*" | Select-Object Name, Length, LastWriteTime
}
