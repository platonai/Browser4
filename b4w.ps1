#!/usr/bin/env pwsh

param(
    [switch]$Rebuild,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$RemainingArgs
)

# Save the original working directory so we can restore it on exit.
# Some operations (cargo build, cargo run) may change the process CWD,
# and restoring it prevents shell session CWD drift when b4w.ps1 is
# invoked from a wrapper that tracks CWD across invocations.
$OriginalCwd = Get-Location

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Manifest = "$ScriptDir/cli/browser4-cli/Cargo.toml"
$Exe = Join-Path $ScriptDir "cli/browser4-cli/target/debug/browser4-cli.exe"

if ($Rebuild) {
    Write-Host "Rebuilding browser4-cli..." -ForegroundColor Yellow
    cargo build --manifest-path $Manifest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# Auto-detect stale sources and rebuild if needed
if (!$Rebuild -and (Test-Path $Exe)) {
    $ExeTime = (Get-Item $Exe).LastWriteTime
    $CrateDir = Join-Path $ScriptDir "cli/browser4-cli"
    $SrcDir = Join-Path $CrateDir "src"
    $Stale = @(Get-ChildItem -Path $SrcDir -Recurse -File -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -gt $ExeTime })
    if (-not $Stale) {
        foreach ($f in @("$CrateDir/Cargo.toml", "$CrateDir/Cargo.lock")) {
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
if ($RemainingArgs -and ($RemainingArgs[0] -eq '--' -or $RemainingArgs[0] -eq '--%')) {
    # Strip the passthrough / stop-parsing token so it doesn't interfere
    # with subcommand routing below.  -- is the conventional passthrough
    # separator.  --% (PowerShell stop-parsing symbol) may arrive as a
    # literal argument when b4w.bat calls pwsh -File ... --% %* ¡ª
    # pwsh.exe's native command-line parser doesn't always consume it.
    $CliArgs = $RemainingArgs[1..($RemainingArgs.Count - 1)]
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
  b4w install      Install b4w as a global command (adds to PATH)
  b4w uninstall    Remove b4w from PATH and delete generated launcher

Examples:
  # cli (default — "cli" keyword is optional)
  b4w                                   show this help
  b4w -s my-session                     start a named session
  b4w -s my-session snapshot -i         snapshot — interactive elements only
  b4w --version                         print version
  b4w --help                            CLI help
  b4w -- -s my-session -i               passthrough via --

  # subcommands
  b4w coworker list                     list Coworker tasks
  b4w test --e2e                        run E2E tests
  b4w build                             build browser4-cli
  b4w b4w install                       install b4w globally (one-time setup)
  b4w b4w uninstall                     remove b4w from PATH and launcher

Wrapper:
  b4w.sh          Git Bash / Linux / macOS wrapper — quotes arguments
                  to prevent PowerShell parameter binding issues.

Tip: When running b4w.ps1 directly and short flags like -i or -v are
intercepted by PowerShell, either use b4w.sh, or pass flags after "--"
(e.g. ./b4w.ps1 -- snapshot -i).
'@

# ── Subcommand: b4w install ────────────────────────────────────────────────
# Installs the b4w command globally so you can type `b4w <subcommand>` from
# any directory without the .ps1 / .bat / .sh extension.
if ($CliArgs -and $CliArgs[0] -eq 'b4w' -and $CliArgs[1] -eq 'install') {
    Write-Host "Installing b4w command..." -ForegroundColor Cyan

    # 1. Refresh the user PATH: remove any stale b4w repo paths, then add
    #    the current $ScriptDir so the global `b4w` command always resolves
    #    to this local copy.
    $normalizedCurrent = $ScriptDir.TrimEnd('\', '/')
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = $userPath -split ';' -ne ''
    $cleaned = $entries | Where-Object {
        $entry = $_.TrimEnd('\', '/')
        # Remove entries that point to a different b4w repo (any path
        # whose leaf is a b4w.ps1 sibling) so stale installs don't
        # shadow the current one.
        -not (Test-Path (Join-Path $entry 'b4w.ps1')) -or $entry -eq $normalizedCurrent
    }
    $alreadyThere = $cleaned | Where-Object { $_.TrimEnd('\', '/') -eq $normalizedCurrent }
    if (-not $alreadyThere) {
        $cleaned += $ScriptDir
        Write-Host "  + Added to user PATH: $ScriptDir" -ForegroundColor Green
    } else {
        Write-Host "  - Already on PATH: $ScriptDir" -ForegroundColor DarkGray
    }
    $newPath = $cleaned -join ';'
    [Environment]::SetEnvironmentVariable("Path", $newPath, "User")

    # Also refresh the current process PATH (de-dup + add current).
    $procEntries = $env:Path -split ';' -ne ''
    $procCleaned = $procEntries | Where-Object {
        $entry = $_.TrimEnd('\', '/')
        -not (Test-Path (Join-Path $entry 'b4w.ps1')) -or $entry -eq $normalizedCurrent
    }
    if (-not ($procCleaned | Where-Object { $_.TrimEnd('\', '/') -eq $normalizedCurrent })) {
        $procCleaned += $ScriptDir
    }
    $env:Path = $procCleaned -join ';'

    # 2. On non-Windows platforms, create/update the bare `b4w` bash
    #    script (no extension) so the global launcher works from bash/zsh.
    #    On Windows, users invoke b4w via b4w.ps1 or b4w.bat — a bash
    #    launcher would be useless and pollute the repo root.
    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        $b4wBash = Join-Path $ScriptDir 'b4w'
        $b4wExisted = Test-Path $b4wBash
        @'
#!/bin/bash
# b4w — short-form launcher for browser4-cli (delegates to b4w.sh).
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
exec "$SCRIPT_DIR/b4w.sh" "$@"
'@ | Set-Content -Path $b4wBash -Encoding UTF8 -NoNewline
        # Append a final newline (Set-Content -NoNewline omits it for the heredoc).
        Add-Content -Path $b4wBash -Value ""
        if ($b4wExisted) {
            Write-Host "  + Updated bash launcher: $b4wBash" -ForegroundColor Green
        } else {
            Write-Host "  + Created bash launcher: $b4wBash" -ForegroundColor Green
        }

        # 3. Ensure the bash script is executable (git update-index --chmod=+x).
        Push-Location $ScriptDir
        try {
            git update-index --chmod=+x b4w 2>$null
            if ($LASTEXITCODE -eq 0) {
                Write-Host "  + Marked b4w as executable in git index" -ForegroundColor Green
            }
        } finally {
            Pop-Location
        }
    }

    Write-Host ""
    Write-Host "b4w installed successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Restart your shell (or run 'refreshenv' / reopen the terminal)"
    Write-Host "and then you can type just:  b4w <subcommand>" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Examples:" -ForegroundColor White
    Write-Host "  b4w --help"
    Write-Host "  b4w coworker list"
    Write-Host "  b4w test --e2e"
    Write-Host "  b4w build"
    Write-Host "  b4w -- snapshot -i"

    Set-Location $OriginalCwd
    exit 0
}

# ── Subcommand: b4w uninstall ──────────────────────────────────────────────
# Removes b4w from the user's PATH and deletes the generated launcher file.
if ($CliArgs -and $CliArgs[0] -eq 'b4w' -and $CliArgs[1] -eq 'uninstall') {
    Write-Host "Uninstalling b4w command..." -ForegroundColor Cyan

    # 1. Remove the repo root from the user's PATH environment variable.
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($userPath -like "*$ScriptDir*") {
        # Split on ';' and filter out the ScriptDir entry (handle trailing
        # backslash variations and the entry possibly being at start, middle,
        # or end of the PATH string).
        $entries = $userPath -split ';' -ne ''
        $cleaned = $entries | Where-Object {
            $normalized = $_.TrimEnd('\', '/')
            $normalized -ne $ScriptDir.TrimEnd('\', '/')
        }
        $newPath = $cleaned -join ';'
        [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
        Write-Host "  + Removed from user PATH: $ScriptDir" -ForegroundColor Green
    } else {
        Write-Host "  - Not on user PATH: $ScriptDir" -ForegroundColor DarkGray
    }

    # Also remove from the current process PATH.
    if ($env:Path -like "*$ScriptDir*") {
        $entries = $env:Path -split ';' -ne ''
        $cleaned = $entries | Where-Object {
            $normalized = $_.TrimEnd('\', '/')
            $normalized -ne $ScriptDir.TrimEnd('\', '/')
        }
        $env:Path = $cleaned -join ';'
        Write-Host "  + Removed from current session PATH" -ForegroundColor Green
    }

    # 2. Delete the `b4w` bash launcher (non-Windows only — matches install).
    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        $b4wBash = Join-Path $ScriptDir 'b4w'
        if (Test-Path $b4wBash) {
            Remove-Item $b4wBash -Force
            Write-Host "  + Deleted bash launcher: $b4wBash" -ForegroundColor Green
        } else {
            Write-Host "  - Bash launcher not found (already removed): $b4wBash" -ForegroundColor DarkGray
        }
    }

    Write-Host ""
    Write-Host "b4w uninstalled successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Restart your shell for the PATH change to take full effect." -ForegroundColor Yellow
    Write-Host "To reinstall later:  ./b4w.ps1 b4w install"

    Set-Location $OriginalCwd
    exit 0
}

# ── b4w subcommand help ────────────────────────────────────────────────────
$B4wHelp = @'
Usage: b4w b4w <subcommand>

Manage the b4w launcher itself.

Subcommands:
  b4w install      Install b4w as a global command (adds to PATH, creates bash launcher)
  b4w uninstall    Remove b4w from PATH and delete the generated launcher

Examples:
  ./b4w.ps1 b4w install       install b4w globally
  ./b4w.ps1 b4w uninstall     remove b4w from PATH
'@

# ── Subcommand: b4w (bare / unknown subcommand) ───────────────────────────
# Running `b4w b4w` (bare) or `b4w b4w <unknown>` prints b4w-specific help.
if ($CliArgs -and $CliArgs[0] -eq 'b4w') {
    Write-Host $B4wHelp
    Set-Location $OriginalCwd
    exit 0
}

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

# Restore the original working directory so the caller's shell session
# (e.g., Git Bash) sees a consistent CWD after b4w.ps1 exits.
Set-Location $OriginalCwd
