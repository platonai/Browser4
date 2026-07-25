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

# ── Subcommand: coworker ──────────────────────────────────────────────────
# Delegates to coworker/coworker.ps1, forwarding all remaining arguments.
if ($CliArgs -and $CliArgs[0] -eq 'coworker') {
    $CoworkerScript = Join-Path $ScriptDir 'coworker\coworker.ps1'
    $CoworkerArgs = $CliArgs[1..$CliArgs.Length]
    if ($CoworkerArgs) {
        & $CoworkerScript @CoworkerArgs
    } else {
        & $CoworkerScript
    }
    exit $LASTEXITCODE
}

# ── Subcommand: test ──────────────────────────────────────────────────────
# Delegates to bin/test.ps1, forwarding all remaining arguments.
if ($CliArgs -and $CliArgs[0] -eq 'test') {
    $TestScript = Join-Path $ScriptDir 'bin\test.ps1'
    $TestArgs = $CliArgs[1..$CliArgs.Length]
    if ($TestArgs) {
        & $TestScript @TestArgs
    } else {
        & $TestScript
    }
    exit $LASTEXITCODE
}

# ── Subcommand: build ─────────────────────────────────────────────────────
# Delegates to bin/build.ps1, forwarding all remaining arguments.
if ($CliArgs -and $CliArgs[0] -eq 'build') {
    $BuildScript = Join-Path $ScriptDir 'bin\build.ps1'
    $BuildArgs = $CliArgs[1..$CliArgs.Length]
    if ($BuildArgs) {
        & $BuildScript @BuildArgs
    } else {
        & $BuildScript
    }
    exit $LASTEXITCODE
}

# ── Top-level help ────────────────────────────────────────────────────────
$TopHelp = @'
Usage: b4w [command] [options]

Commands:
  cli [args]       Run browser4-cli (default — can be omitted)
  coworker <cmd>   Manage Coworker tasks (delegates to coworker/coworker.ps1)
  test [args]      Run tests (delegates to bin/test.ps1)
  build [args]     Build the project (delegates to bin/build.ps1)

Examples:
  b4w -s my-session          # runs: cli -s my-session
  b4w cli -s my-session      # same as above
  b4w coworker list          # runs: coworker/coworker.ps1 list
  b4w test --e2e             # runs: bin/test.ps1 --e2e
  b4w build                  # runs: bin/build.ps1
'@

# No arguments: show help
if (-not $CliArgs) {
    Write-Host $TopHelp
    exit 0
}

# ── Subcommand: cli ───────────────────────────────────────────────────────
# "cli" is explicit; when omitted, any unrecognized first token is treated
# as the start of CLI arguments (implicit cli).  Strip an explicit "cli"
# so the remaining args pass straight through to the binary.
if ($CliArgs[0] -eq 'cli') {
    $CliArgs = $CliArgs[1..$CliArgs.Length]
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
