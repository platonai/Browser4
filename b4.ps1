#!/usr/bin/env pwsh

param(
    [switch]$Rebuild,
    [string[]]$ScriptArgs
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Manifest = "$ScriptDir\cli\browser4-cli\Cargo.toml"
$Exe = Join-Path $ScriptDir "cli\browser4-cli\target\debug\browser4-cli.exe"

if ($Rebuild) {
    Write-Host "Rebuilding browser4-cli..." -ForegroundColor Yellow
    cargo build --manifest-path $Manifest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# Auto-detect stale sources and rebuild if needed
if (!$Rebuild -and (Test-Path $Exe)) {
    $ExeTime = (Get-Item $Exe).LastWriteTime
    $CrateDir = Join-Path $ScriptDir "cli\browser4-cli"
    $SrcDir = Join-Path $CrateDir "src"
    $Stale = @(Get-ChildItem -Path $SrcDir -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -gt $ExeTime })
    if (-not $Stale) {
        foreach ($f in @("$CrateDir\Cargo.toml", "$CrateDir\Cargo.lock")) {
            if ((Test-Path $f) -and ((Get-Item $f).LastWriteTime -gt $ExeTime)) { $Stale = @($true); break }
        }
    }
    if ($Stale) {
        Write-Host "Rust sources changed, rebuilding browser4-cli..." -ForegroundColor Yellow
        cargo build --manifest-path $Manifest
        if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    }
}

if (Test-Path $Exe) {
    & $Exe @ScriptArgs
} else {
    cargo run --manifest-path $Manifest -- @ScriptArgs
}
