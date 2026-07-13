#!/usr/bin/env pwsh

param(
    [switch]$Rebuild,
    [string[]]$ScriptArgs
)

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Manifest = "$ScriptDir\cli\browser4-cli\Cargo.toml"
$Exe = Join-Path $ScriptDir "cli\browser4-cli\target\debug\browser4-cli.exe"

if ($Rebuild) {
    cargo build --manifest-path $Manifest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

if (Test-Path $Exe) {
    & $Exe @ScriptArgs
} else {
    cargo run --manifest-path $Manifest -- @ScriptArgs
}
