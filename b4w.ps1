#!/usr/bin/env pwsh
<#
.SYNOPSIS
    b4w — browser4-cli wrapper with additional development tools.

.DESCRIPTION
    b4w (browser4-cli wrapper) is a development-only launcher that builds and
    runs the browser4-cli Rust binary directly from the current codebase.  It
    also bundles extra dev-tool subcommands (coworker, test, build) that are
    not part of the published browser4-cli release.

    This script is not shipped to end users — it lives in the repo root and
    expects the Cargo workspace at ../cli/browser4-cli relative to itself.
#>

# Use $args directly instead of PowerShell's param() block so that CLI flags
# like -o, -i, and -v are NOT intercepted by PowerShell's common parameter
# binder (where -o is ambiguous between -OutVariable/-OutBuffer, -i matches
# -InformationAction, and -v matches -Verbose).
#
# With manual args parsing, all tokens pass through to the $RemainingArgs
# array without PowerShell trying to bind them to script parameters.

# ── Global invocation bootstrap ────────────────────────────────────────────
# When b4w is invoked via PATH (global command), search upward from the
# calling directory to locate the correct b4w.ps1 for this repository.
# This ensures that if you have multiple Browser4 checkouts, typing "b4w"
# always uses the one corresponding to your current directory.
#
# If no b4w.ps1 is found in any parent directory, show an error — b4w is a
# development-only tool and must be called from within a Browser4 source
# code repository.

$B4wMyPath = $MyInvocation.MyCommand.Path
$B4wCallingDir = Get-Location
$B4wFoundScript = $null
$B4wSearchDir = $B4wCallingDir

while ($B4wSearchDir) {
    $B4wCandidate = Join-Path $B4wSearchDir 'b4w.ps1'
    if (Test-Path -Path $B4wCandidate -PathType Leaf) {
        $B4wFoundScript = (Resolve-Path $B4wCandidate).Path
        break
    }
    $B4wParent = Split-Path $B4wSearchDir -Parent
    if ($B4wParent -eq $B4wSearchDir) { break }
    $B4wSearchDir = $B4wParent
}

if (-not $B4wFoundScript) {
    Write-Host 'Error: b4w is a development-only tool.' -ForegroundColor Red
    Write-Host ''
    Write-Host 'b4w must be called from within a Browser4 source code repository.' -ForegroundColor Yellow
    Write-Host 'Navigate to a Browser4 checkout and try again.' -ForegroundColor Yellow
    exit 1
}

# If the found script is different from ourselves, delegate to it.
$B4wMyPathNormalized = if (Test-Path $B4wMyPath) { (Resolve-Path $B4wMyPath).Path } else { $B4wMyPath }
if ($B4wFoundScript -ne $B4wMyPathNormalized) {
    & $B4wFoundScript @args
    exit $LASTEXITCODE
}
# Otherwise, we ARE the correct b4w.ps1 — continue below.

$Rebuild = $false
$RemainingArgs = @()

for ($i = 0; $i -lt $args.Count; $i++) {
    if ($args[$i] -eq '-Rebuild') {
        $Rebuild = $true
    } else {
        $RemainingArgs += $args[$i]
    }
}

# Short flags (-o, -i, -v) are safe in b4w.ps1 because manual $args
# parsing prevents PowerShell from intercepting them.  No warning is
# emitted here — users on other shells (b4w.sh, plain pwsh) should
# use long-form equivalents (--output, --interactive, --viewport) or
# b4w.bat (cmd.exe) for full compatibility.

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
        $CoworkerCommand = $CoworkerArgs[0]
        $CoworkerRemaining = @()
        if ($CoworkerArgs.Count -gt 1) {
            $CoworkerRemaining = @($CoworkerArgs[1..($CoworkerArgs.Count - 1)])
        }
        & $CoworkerScript -Command $CoworkerCommand -Remaining $CoworkerRemaining
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
b4w — browser4-cli wrapper with additional development tools.

b4w is a development-only launcher that builds and runs browser4-cli
directly from the current codebase.  It also bundles extra dev-tool
subcommands (coworker, test, build) that are not part of the published
browser4-cli release.

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
  b4w.sh          Git Bash / Linux / macOS wrapper — preferred for bash
                  environments when PowerShell flag interception occurs.

Tip: Use long-form flags (--output, --interactive, --viewport) for
cross-shell compatibility.  Short flags (-o, -i, -v) are now safe with
b4w.ps1 thanks to manual argument parsing that avoids PowerShell's
parameter binder.
'@

# ── Subcommand: b4w install ────────────────────────────────────────────────
# Installs the b4w command globally so you can type `b4w <subcommand>` from
# any directory without the .ps1 / .bat / .sh extension.
#
# On non-Windows platforms, installation places a bash launcher script at
#   $HOME/.local/bin/b4w
# and ensures that directory is on the user PATH.  The launcher delegates
# to the repo's b4w.sh via an absolute path, so the repo can be moved or
# deleted independently — just re-run install from the new location.
#
# On Windows, installation adds the repo directory ($ScriptDir) to the user
# PATH so b4w.ps1 / b4w.bat are discoverable from any shell.
if ($CliArgs -and $CliArgs[0] -eq 'b4w' -and $CliArgs[1] -eq 'install') {
    Write-Host "Installing b4w command..." -ForegroundColor Cyan

    # ── Non-Windows: install to ~/.local/bin ─────────────────────────────
    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        $globalBin = Join-Path $HOME '.local/bin'
        $globalLauncher = Join-Path $globalBin 'b4w'

        # 1. Ensure ~/.local/bin exists.
        if (-not (Test-Path $globalBin)) {
            New-Item -ItemType Directory -Path $globalBin -Force | Out-Null
            Write-Host "  + Created directory: $globalBin" -ForegroundColor Green
        }

        # 2. Create/update the global `b4w` bash launcher that delegates
        #    to this repo's b4w.sh via an absolute path.
        #    We build the content with explicit Unix line endings ("`n")
        #    to avoid CRLF from the .ps1 file leaking into the bash script.
        #    "$ScriptDir" is interpolated; "`$@" produces the literal
        #    bash $@ so arguments are forwarded correctly.
        $launcherExisted = Test-Path $globalLauncher
        $launcherContent = "#!/bin/bash`n" +
            "# b4w — global launcher for browser4-cli.`n" +
            "# Installed by: $ScriptDir/b4w.ps1`n" +
            "# Delegates to: $ScriptDir/b4w.sh`n" +
            "exec `"$ScriptDir/b4w.sh`" `"`$@`"`n"
        # Use ASCII encoding (no BOM) — WriteAllText writes LF on Linux.
        [System.IO.File]::WriteAllText($globalLauncher, $launcherContent, [System.Text.Encoding]::ASCII)

        # Make it executable.
        chmod +x $globalLauncher 2>$null
        if ($launcherExisted) {
            Write-Host "  + Updated global launcher: $globalLauncher" -ForegroundColor Green
        } else {
            Write-Host "  + Created global launcher: $globalLauncher" -ForegroundColor Green
        }

        # 3. Ensure ~/.local/bin is on the user PATH.
        $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
        $entries = @($userPath -split ';' -ne '')
        $alreadyOnPath = $entries | Where-Object {
            $_.TrimEnd('\', '/') -eq $globalBin.TrimEnd('\', '/')
        }
        if (-not $alreadyOnPath) {
            $entries += $globalBin
            $newPath = $entries -join ';'
            [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
            Write-Host "  + Added to user PATH: $globalBin" -ForegroundColor Green
        } else {
            Write-Host "  - Already on PATH: $globalBin" -ForegroundColor DarkGray
        }

        # Also refresh the current process PATH.
        $procEntries = @($env:Path -split ';' -ne '')
        $alreadyInProc = $procEntries | Where-Object {
            $_.TrimEnd('\', '/') -eq $globalBin.TrimEnd('\', '/')
        }
        if (-not $alreadyInProc) {
            $procEntries += $globalBin
            $env:Path = $procEntries -join ';'
        }

        Write-Host ""
        Write-Host "b4w installed successfully!" -ForegroundColor Green
        Write-Host "  bash launcher: $globalLauncher"
        Write-Host "  Repo     : $ScriptDir"
        Write-Host ""
        Write-Host "Restart your shell (or run 'hash -r' / reopen the terminal)"
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

    # ── Windows: add the repo directory to PATH ──────────────────────────
    $normalizedCurrent = $ScriptDir.TrimEnd('\', '/')
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    $entries = $userPath -split ';' -ne ''
    $cleaned = $entries | Where-Object {
        $entry = $_.TrimEnd('\', '/')
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
# Removes the globally-installed b4w launcher and cleans up PATH entries.
#
# On non-Windows: removes ~/.local/bin/b4w (the global launcher).
# On Windows: removes the repo directory from the user PATH.
if ($CliArgs -and $CliArgs[0] -eq 'b4w' -and $CliArgs[1] -eq 'uninstall') {
    Write-Host "Uninstalling b4w command..." -ForegroundColor Cyan

    # ── Non-Windows: remove the global launcher ──────────────────────────
    if (-not ($IsWindows -or $env:OS -eq 'Windows_NT')) {
        $globalBin = Join-Path $HOME '.local/bin'
        $globalLauncher = Join-Path $globalBin 'b4w'

        # 1. Remove the global launcher file.
        if (Test-Path $globalLauncher) {
            Remove-Item $globalLauncher -Force
            Write-Host "  + Removed global launcher: $globalLauncher" -ForegroundColor Green
        } else {
            Write-Host "  - Global launcher not found: $globalLauncher" -ForegroundColor DarkGray
        }

        # 2. Also clean up the legacy repo-local bash launcher if it exists
        #    (from b4w installs prior to the global-install change).
        $legacyLauncher = Join-Path $ScriptDir 'b4w'
        if (Test-Path $legacyLauncher) {
            # Only remove if it looks like a generated launcher (not a
            # hand-maintained script).
            $content = Get-Content $legacyLauncher -Raw -ErrorAction SilentlyContinue
            if ($content -match 'b4w — short-form launcher') {
                Remove-Item $legacyLauncher -Force
                Write-Host "  + Removed legacy repo launcher: $legacyLauncher" -ForegroundColor Green
            }
        }

        # 3. Clean any b4w-repo PATH entries from the user PATH (legacy).
        $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
        $normalizedRepo = $ScriptDir.TrimEnd('\', '/')
        $entries = @($userPath -split ';' -ne '')
        $cleaned = $entries | Where-Object {
            $entry = $_.TrimEnd('\', '/')
            # Remove entries that point to this repo (legacy install style).
            -not (Test-Path (Join-Path $entry 'b4w.ps1') -and $entry -eq $normalizedRepo)
        }
        if ($cleaned.Count -ne $entries.Count) {
            $newPath = $cleaned -join ';'
            [Environment]::SetEnvironmentVariable("Path", $newPath, "User")
            Write-Host "  + Removed repo from user PATH: $ScriptDir" -ForegroundColor Green

            # Also refresh current process PATH.
            $procEntries = @($env:Path -split ';' -ne '')
            $procCleaned = $procEntries | Where-Object {
                $e = $_.TrimEnd('\', '/')
                -not (Test-Path (Join-Path $e 'b4w.ps1') -and $e -eq $normalizedRepo)
            }
            if ($procCleaned.Count -ne $procEntries.Count) {
                $env:Path = $procCleaned -join ';'
            }
        } else {
            Write-Host "  - Repo not on user PATH (nothing to clean)" -ForegroundColor DarkGray
        }

        Write-Host ""
        Write-Host "b4w uninstalled successfully!" -ForegroundColor Green
        Write-Host ""
        Write-Host "Note: ~/.local/bin remains on your PATH (used by other tools)." -ForegroundColor DarkGray
        Write-Host "To reinstall later:  ./b4w.ps1 b4w install"

        Set-Location $OriginalCwd
        exit 0
    }

    # ── Windows: remove the repo directory from PATH ─────────────────────
    $userPath = [Environment]::GetEnvironmentVariable("Path", "User")
    if ($userPath -like "*$ScriptDir*") {
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
