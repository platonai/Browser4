#!/usr/bin/env pwsh

param(
    [switch]$Rebuild,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
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

# Support explicit passthrough: everything after '--' bypasses the
# script's own param() block and goes directly to the CLI binary.
# This allows flags like -s <session> to work without PowerShell
# interpreting them as parameter names (e.g., -s → -ScriptArgs).
$CliArgs = $RemainingArgs
if ($RemainingArgs -and $RemainingArgs[0] -eq '--') {
    $CliArgs = $RemainingArgs[1..$RemainingArgs.Length]
}

# Build a quoted argument list to prevent PowerShell from interpreting
# dash-prefixed CLI flags (-v, -i, --sql, -Ex*, etc.) as its own
# parameters.  Without this, PowerShell resolves -v to -Verbose (common
# parameter), -i clashes with -InformationAction, and other flag-like
# tokens can be consumed before reaching the CLI binary.
# See also: b4w.sh for bash → pwsh passthrough handling.
$SafeArgs = foreach ($a in $CliArgs) {
    # Double-quote each argument and escape internal double quotes.
    '"' + ($a -replace '"', '""') + '"'
}
$SafeArgsStr = $SafeArgs -join ' '

if (Test-Path $Exe) {
    if ($SafeArgs) {
        Invoke-Expression "& `"$Exe`" $SafeArgsStr"
    } else {
        & $Exe
    }
} else {
    if ($SafeArgs) {
        Invoke-Expression "cargo run --manifest-path `"$Manifest`" -- $SafeArgsStr"
    } else {
        cargo run --manifest-path $Manifest
    }
}
