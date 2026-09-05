#!/usr/bin/env pwsh
<#
.SYNOPSIS
    b4w — browser4-cli wrapper with additional development tools.

.DESCRIPTION
    b4w (browser4-cli wrapper) is a development-only launcher that builds and
    runs the browser4-cli Rust binary directly from the current codebase.  It
    also bundles extra dev-tool subcommands (coworker, sc, test, build) that are
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
$NoBuild = $false
$RemainingArgs = @()

for ($i = 0; $i -lt $args.Count; $i++) {
    if ($args[$i] -eq '-Rebuild') {
        $Rebuild = $true
    } elseif ($args[$i] -eq '-NoBuild' -or $args[$i] -eq '--no-build') {
        $NoBuild = $true
    } else {
        $RemainingArgs += $args[$i]
    }
}

# Short flags (-o, -i, -v) are safe in b4w.ps1 because manual $args
# parsing prevents PowerShell from intercepting them.  No warning is
# emitted here — users on other shells (b4w.sh, plain pwsh) should
# use long-form equivalents (--output, --interactive, --viewport) or
# b4w.bat (cmd.exe) for full compatibility.

# ── Git-Bash (MSYS) mangled-argument guard ──────────────────────────────
# Git Bash rewrites '/'-leading arguments into Windows paths rooted at the
# Git installation directory when spawning pwsh ('/ec/dp/' arrives here as
# 'C:/Program Files/Git/ec/dp/').  The original token is lost before this
# script runs — a mangled argument would be forwarded to the CLI and
# silently produce wrong results (e.g. a snapshot grep pattern reporting
# '0 matches found').  Detect the rewritten form and fail fast with
# guidance; the conversion-free route from Git Bash is ./b4w.sh, which
# exports MSYS2_ARG_CONV_EXCL='*'.  Legit '/d/...'-style conversions to
# other drives are never under the Git root, so they pass through.
if ($env:MSYSTEM) {
    $MsysRoot = $null
    $MsysGit = Get-Command git.exe -ErrorAction SilentlyContinue
    if ($MsysGit -and $MsysGit.Source) {
        # <root>\cmd\git.exe | <root>\bin\git.exe | <root>\usr\bin\git.exe | <root>\mingw64\bin\git.exe
        $MsysGitDir = Split-Path $MsysGit.Source -Parent
        $MsysGitLeaf = Split-Path $MsysGitDir -Leaf
        if ($MsysGitLeaf -eq 'cmd') {
            $MsysRoot = Split-Path $MsysGitDir -Parent
        } elseif ($MsysGitLeaf -eq 'bin') {
            $MsysGitParent = Split-Path $MsysGitDir -Parent
            if ((Split-Path $MsysGitParent -Leaf) -in @('usr', 'mingw64', 'msys64')) {
                $MsysRoot = Split-Path $MsysGitParent -Parent
            } else {
                $MsysRoot = $MsysGitParent
            }
        }
    }

    if ($MsysRoot) {
        $RootFwd = ($MsysRoot -replace '\\', '/').TrimEnd('/') + '/'
        foreach ($MangledArg in $RemainingArgs) {
            $ArgFwd = $MangledArg -replace '\\', '/'
            if ($ArgFwd.Length -gt $RootFwd.Length -and $ArgFwd.StartsWith($RootFwd, [StringComparison]::OrdinalIgnoreCase)) {
                $ProbableOriginal = '/' + $ArgFwd.Substring($RootFwd.Length).TrimStart('/')
                Write-Host "Error: argument '$MangledArg' (probably typed as '$ProbableOriginal') was rewritten by" -ForegroundColor Red
                Write-Host "Git Bash's MSYS path conversion before PowerShell started.  The original value" -ForegroundColor Red
                Write-Host 'is unrecoverable, and using the rewritten path would silently produce wrong' -ForegroundColor Red
                Write-Host "results (e.g. a snapshot grep pattern reporting '0 matches found')." -ForegroundColor Red
                Write-Host ''
                Write-Host "Run this command via ./b4w.sh instead (it disables the conversion)." -ForegroundColor Cyan
                Write-Host "Or export MSYS2_ARG_CONV_EXCL='*' and re-run:  MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 ..." -ForegroundColor Cyan
                exit 1
            }
        }
    }
}

# Save the original working directory so we can restore it on exit.
# Some operations (cargo build, cargo run) may change the process CWD,
# and restoring it prevents shell session CWD drift when b4w.ps1 is
# invoked from a wrapper that tracks CWD across invocations.
$OriginalCwd = Get-Location

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$Manifest = "$ScriptDir/cli/browser4-cli/Cargo.toml"
$ExeName = if ($IsWindows -or $env:OS -eq 'Windows_NT') { 'browser4-cli.exe' } else { 'browser4-cli' }
$Exe = Join-Path $ScriptDir "cli/browser4-cli/target/debug/$ExeName"

if ($Rebuild) {
    Write-Host "Rebuilding browser4-cli..." -ForegroundColor Yellow
    cargo build --manifest-path $Manifest
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
}

# Auto-detect stale sources and rebuild if needed.
# Uses a source-content hash cache to avoid redundant cargo build
# invocations when running multiple b4w.ps1 commands in parallel.
# Without the cache, each concurrent invocation detects stale sources
# independently and all try to acquire the cargo build lock, causing
# "Blocking waiting for file lock on build directory" delays.
if (!$Rebuild -and !$NoBuild -and (Test-Path $Exe)) {
    $ExeTime = (Get-Item $Exe).LastWriteTime
    $CrateDir = Join-Path $ScriptDir "cli/browser4-cli"
    $SrcDir = Join-Path $CrateDir "src"
    $HashFile = Join-Path $CrateDir "target/.source-hash"

    # Compute a content hash of all source files to detect real changes.
    # Timestamp comparison alone triggers rebuilds when multiple instances
    # start before any one finishes — the exe is still old so all instances
    # see "stale" sources.  A content hash cached after successful build
    # lets subsequent instances skip the build when nothing actually changed.
    $CurrentHash = $null
    try {
        $Hasher = [System.Security.Cryptography.SHA256]::Create()
        $Files = @(Get-ChildItem -Path $SrcDir -Recurse -File -Filter "*.rs" -ErrorAction SilentlyContinue | Sort-Object FullName)
        $Files += @(Get-Item "$CrateDir/Cargo.toml", "$CrateDir/Cargo.lock" -ErrorAction SilentlyContinue | Where-Object { $_ })
        foreach ($f in $Files) {
            $Content = [System.IO.File]::ReadAllBytes($f.FullName)
            $Hasher.TransformBlock($Content, 0, $Content.Length, $Content, 0) > $null
        }
        $Hasher.TransformFinalBlock(@(), 0, 0) > $null
        $CurrentHash = [BitConverter]::ToString($Hasher.Hash) -replace '-'
        $Hasher.Dispose()
    } catch {
        # If hashing fails (e.g. permission), fall back to timestamp check.
    }

    $CachedHash = $null
    if ($CurrentHash -and (Test-Path $HashFile)) {
        $CachedHash = (Get-Content $HashFile -ErrorAction SilentlyContinue).Trim()
    }

    if ($CachedHash -and $CurrentHash -and $CachedHash -eq $CurrentHash) {
        # Sources haven't changed since last build — skip rebuild.
    } else {
        # Fall back to timestamp-based detection when hash cache is
        # unavailable or sources have genuinely changed.
        $Stale = @(Get-ChildItem -Path $SrcDir -Recurse -File -Filter "*.rs" -ErrorAction SilentlyContinue | Where-Object { $_.LastWriteTime -gt $ExeTime })
        if (-not $Stale) {
            foreach ($f in @("$CrateDir/Cargo.toml", "$CrateDir/Cargo.lock")) {
                if ((Test-Path $f) -and ((Get-Item $f).LastWriteTime -gt $ExeTime)) { $Stale = @($true); break }
            }
        }
        if ($Stale) {
            Write-Host "Rust sources changed, rebuilding browser4-cli..." -ForegroundColor Yellow
            cargo build --manifest-path $Manifest
            if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
            # Persist the new source hash so the next invocation can skip
            # the build.  Always write after a successful build so parallel
            # invocations benefit from the cache.
            if ($CurrentHash) {
                try {
                    New-Item -Path (Split-Path $HashFile -Parent) -ItemType Directory -Force -ErrorAction SilentlyContinue > $null
                    $CurrentHash | Out-File -FilePath $HashFile -Encoding ascii -NoNewline
                } catch { }
            }
        }
    }
}

# ── Backend staleness check ──────────────────────────────────────────────
# The CLI binary is rebuilt from source above, but the Browser4 backend runs
# from a runtime bundle (browser4-apps/browser4-bundle/target/runtime-bundle)
# whose jars are only refreshed on demand via
# BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1. Detect changed Kotlin sources and
# warn instead of silently talking to a stale backend. `-Rebuild` also
# forces the bundle rebuild and caches the new hash afterwards.
$ForcedBundleRebuild = $false
$BackendHashToCache = $null
if (!$NoBuild -and (Test-Path (Join-Path $ScriptDir 'browser4-apps\browser4-bundle'))) {
    $BackendHashFile = Join-Path $ScriptDir 'cli\browser4-cli\target\.backend-source-hash'
    $BackendCurrentHash = $null
    try {
        $Hasher2 = [System.Security.Cryptography.SHA256]::Create()
        $BackendDirs = @(
            'browser4-core', 'browser4-agentic', 'browser4-coding',
            'browser4-rest', 'browser4-boot', 'browser4-agent-tools'
        )
        $BackendFiles = foreach ($d in $BackendDirs) {
            @(Get-ChildItem -Path (Join-Path $ScriptDir $d) -Recurse -File -Filter '*.kt' -ErrorAction SilentlyContinue)
        }
        $BackendFiles += @(Get-Item (Join-Path $ScriptDir 'pom.xml'), (Join-Path $ScriptDir 'VERSION') -ErrorAction SilentlyContinue | Where-Object { $_ })
        $BackendFiles = @($BackendFiles | Sort-Object FullName -Unique)
        foreach ($f in $BackendFiles) {
            $Content = [System.IO.File]::ReadAllBytes($f.FullName)
            $Hasher2.TransformBlock($Content, 0, $Content.Length, $Content, 0) > $null
        }
        $Hasher2.TransformFinalBlock(@(), 0, 0) > $null
        $BackendCurrentHash = [BitConverter]::ToString($Hasher2.Hash) -replace '-'
        $Hasher2.Dispose()
    } catch {
        # If hashing fails (e.g. permission), skip the staleness check.
    }
    if ($BackendCurrentHash) {
        $BackendCachedHash = if (Test-Path $BackendHashFile) { (Get-Content $BackendHashFile -ErrorAction SilentlyContinue).Trim() } else { $null }
        if ($BackendCachedHash -and $BackendCachedHash -eq $BackendCurrentHash) {
            # Backend sources unchanged since the last bundle build — skip.
        } elseif ($BackendFiles) {
            # Hash cache differs — but that may just mean the bundle was rebuilt
            # manually (e.g. via build-runtime-bundle.ps1) and the cache wasn't
            # refreshed. Verify against the bundle's own build stamp: when the
            # bundle artifact is NEWER than every backend source file, the
            # running code matches the sources — refresh the cache silently
            # instead of warning.
            $BackendNewestSource = ($BackendFiles | ForEach-Object { $_.LastWriteTime } | Measure-Object -Maximum).Maximum
            $BundleStamp = Join-Path $ScriptDir 'browser4-apps\browser4-bundle\target\runtime-bundle\_work\browser4-bundle-runtime-windows-x64\browser4-bundle-runtime-windows-x64\runtime-bundle.json'
            $BundleFresh = $false
            if (Test-Path $BundleStamp) {
                $BundleMtime = (Get-Item $BundleStamp).LastWriteTime
                if ($BundleMtime -gt $BackendNewestSource) { $BundleFresh = $true }
            }
            if ($BundleFresh) {
                try {
                    New-Item -Path (Split-Path $BackendHashFile -Parent) -ItemType Directory -Force -ErrorAction SilentlyContinue > $null
                    $BackendCurrentHash | Out-File -FilePath $BackendHashFile -Encoding ascii -NoNewline
                } catch { }
            } else {
                Write-Host 'Backend sources changed since the runtime bundle was last built.' -ForegroundColor Yellow
                Write-Host 'Rebuild with:  $env:BROWSER4_CLI_FORCE_REBUILD_BUNDLE = "1"; b4w <command>' -ForegroundColor Yellow
                Write-Host '  (or pass -Rebuild to b4w to force the rebuild automatically)' -ForegroundColor DarkGray
                if ($Rebuild) {
                    $env:BROWSER4_CLI_FORCE_REBUILD_BUNDLE = '1'
                    $ForcedBundleRebuild = $true
                    $BackendHashToCache = $BackendCurrentHash
                }
            }
        }
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
    # separator.  --% (PowerShell stop-parsing symbol) may still arrive as
    # a literal argument when callers pass it explicitly.
    $CliArgs = $RemainingArgs[1..($RemainingArgs.Count - 1)]
}

# ── Subcommand: coworker ──────────────────────────────────────────────────
# Delegates to coworker/coworker.ps1, forwarding all remaining arguments.
# Special cases:
#   coworker start   — run coworker/start.ps1 (both by default) in the background
#   coworker stop    — stop the background scheduler/GUI
#   coworker restart — stop and restart the background scheduler/GUI
if ($CliArgs -and $CliArgs[0] -eq 'coworker') {
    $CoworkerArgs = if ($CliArgs.Count -gt 1) { ,$CliArgs[1..($CliArgs.Count - 1)] } else { @() }
    $CoworkerPidFile = Join-Path $ScriptDir '.coworker\scheduler.pid'

    # ── coworker start ───────────────────────────────────────────────────
    # Thin wrapper around coworker/start.ps1 so both entry points share the
    # same behavior.  Arguments are forwarded verbatim; b4w only supplies a
    # default when none are given:
    #   b4w coworker start                    → start.ps1 both -Background
    #   b4w coworker start sched|gui|both …   → start.ps1 sched|gui|both …
    # Legacy b4w-only aliases still map onto start.ps1 subcommands (with
    # -Background and PID tracking so stop/restart can manage them):
    #   --sched-only → sched   --gui-only → gui
    if ($CoworkerArgs -and $CoworkerArgs[0] -eq 'start') {
        $StartScript = Join-Path $ScriptDir 'coworker\start.ps1'
        $StartArgs = @()
        if ($CoworkerArgs.Count -gt 1) { $StartArgs = @($CoworkerArgs[1..($CoworkerArgs.Count - 1)]) }

        $legacySchedOnly = $StartArgs -contains '--sched-only'
        $legacyGuiOnly = $StartArgs -contains '--gui-only'

        if ($legacySchedOnly -or $legacyGuiOnly) {
            $LegacySub = if ($legacySchedOnly) { 'sched' } else { 'gui' }
            $LegacyArgs = @($StartArgs | Where-Object { $_ -ne '--sched-only' -and $_ -ne '--gui-only' })
            $ForwardArgs = @($LegacySub) + $LegacyArgs
            if ($ForwardArgs -notcontains '-Background') { $ForwardArgs += '-Background' }
            if ($ForwardArgs -notcontains '-PidFile') { $ForwardArgs += '-PidFile'; $ForwardArgs += $CoworkerPidFile }
            & $StartScript @ForwardArgs
        } elseif ($StartArgs.Count -eq 0) {
            # Default: scheduler + GUI, both detached, PID(s) recorded so
            # `b4w coworker stop` / `restart` can manage them.
            & $StartScript both -Background -PidFile $CoworkerPidFile
        } else {
            # Explicit start.ps1 subcommand and options — behave exactly like
            # running coworker/start.ps1 directly.
            & $StartScript @StartArgs
        }
        exit $LASTEXITCODE
    }

    # ── coworker stop ────────────────────────────────────────────────────
    if ($CoworkerArgs -and $CoworkerArgs[0] -eq 'stop') {
        if (-not (Test-Path $CoworkerPidFile)) {
            Write-Host 'No running Coworker scheduler found.' -ForegroundColor Yellow
            exit 0
        }
        $pidRaw = Get-Content $CoworkerPidFile -Raw
        $pidsToStop = @()
        $pidJson = $null
        try {
            # Newer format: JSON { "scheduler": <pid>, "gui": <pid> } when both
            # scheduler and GUI were started together.
            $pidJson = $pidRaw | ConvertFrom-Json -ErrorAction Stop
        } catch {
            $pidJson = $null
        }
        if ($pidJson -is [System.Management.Automation.PSCustomObject]) {
            if ($pidJson.scheduler) { $pidsToStop += [int]$pidJson.scheduler }
            if ($pidJson.gui) { $pidsToStop += [int]$pidJson.gui }
        } elseif ($pidRaw.Trim() -match '^\d+$') {
            # Legacy format: a single plain PID (note: a bare number is also
            # valid JSON, so only treat PSCustomObject payloads as JSON records)
            $pidsToStop += [int]$pidRaw.Trim()
        }
        if ($pidsToStop.Count -eq 0) {
            Write-Host "No running Coworker process found in PID file: $CoworkerPidFile" -ForegroundColor Yellow
            Remove-Item $CoworkerPidFile -Force -ErrorAction SilentlyContinue
            exit 0
        }
        $stoppedAny = $false
        foreach ($savedPid in $pidsToStop) {
            $proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
            if ($proc) {
                Write-Host "Stopping Coworker process (PID $savedPid)..." -ForegroundColor Cyan
                $proc.Kill()
                $stoppedAny = $true
            } else {
                Write-Host "Coworker process (PID $savedPid) is no longer running." -ForegroundColor Yellow
            }
        }
        if ($stoppedAny) {
            Write-Host 'Coworker stopped.' -ForegroundColor Green
        }
        Remove-Item $CoworkerPidFile -Force -ErrorAction SilentlyContinue
        exit 0
    }

    # ── coworker restart ─────────────────────────────────────────────────
    if ($CoworkerArgs -and $CoworkerArgs[0] -eq 'restart') {
        # Stop any recorded background processes first
        if (Test-Path $CoworkerPidFile) {
            $pidRaw = Get-Content $CoworkerPidFile -Raw
            $pidsToStop = @()
            $pidJson = $null
            try {
                $pidJson = $pidRaw | ConvertFrom-Json -ErrorAction Stop
            } catch {
                $pidJson = $null
            }
            if ($pidJson -is [System.Management.Automation.PSCustomObject]) {
                if ($pidJson.scheduler) { $pidsToStop += [int]$pidJson.scheduler }
                if ($pidJson.gui) { $pidsToStop += [int]$pidJson.gui }
            } elseif ($pidRaw.Trim() -match '^\d+$') {
                $pidsToStop += [int]$pidRaw.Trim()
            }
            foreach ($savedPid in $pidsToStop) {
                $proc = Get-Process -Id $savedPid -ErrorAction SilentlyContinue
                if ($proc) {
                    Write-Host "Stopping Coworker process (PID $savedPid)..." -ForegroundColor Cyan
                    $proc.Kill()
                }
            }
            Remove-Item $CoworkerPidFile -Force -ErrorAction SilentlyContinue
        }
        # Start fresh (same default as `b4w coworker start`)
        $StartScript = Join-Path $ScriptDir 'coworker\start.ps1'
        & $StartScript both -Background -PidFile $CoworkerPidFile
        exit $LASTEXITCODE
    }

    $CoworkerScript = Join-Path $ScriptDir 'coworker\coworker.ps1'
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

# ── Subcommand: sc ─────────────────────────────────────────────────────────
# Shortcut for the real-world scenario interactive picker.
# `b4w sc`          → test.ps1 rws sc --interactive (launch picker)
# `b4w sc <args>`   → test.ps1 rws sc <args>        (passthrough)
if ($CliArgs -and $CliArgs[0] -eq 'sc') {
    $TestScript = Join-Path $ScriptDir 'bin\test.ps1'
    $ScArgs = if ($CliArgs.Count -gt 1) { @($CliArgs[1..($CliArgs.Count - 1)]) } else { @() }
    if ($ScArgs) {
        $safeArgs = ($ScArgs | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ' '
        Invoke-Expression "& '$TestScript' -- rws sc $safeArgs"
    } else {
        # No args → launch the interactive scenario picker
        & $TestScript rws sc --interactive
    }
    exit $LASTEXITCODE
}

# ── Subcommand: test ──────────────────────────────────────────────────────
# Delegates to bin/test.ps1, forwarding all remaining arguments.
# Uses Invoke-Expression with "--" instead of splatting (@TestArgs) so that
# PowerShell's parser consumes the "--" as an end-of-parameters marker.
# This prevents short flags like -i (ambiguous between -InformationAction
# and -InformationVariable) from triggering common-parameter binding in the
# target script's CmdletBinding block.
if ($CliArgs -and $CliArgs[0] -eq 'test') {
    $TestScript = Join-Path $ScriptDir 'bin\test.ps1'
    $TestArgs = if ($CliArgs.Count -gt 1) { ,$CliArgs[1..($CliArgs.Count - 1)] } else { @() }
    if ($TestArgs) {
        $safeArgs = ($TestArgs | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ' '
        Invoke-Expression "& '$TestScript' -- $safeArgs"
    } else {
        & $TestScript
    }
    exit $LASTEXITCODE
}

# ── Subcommand: build ─────────────────────────────────────────────────────
# Delegates to bin/build.ps1, forwarding all remaining arguments.
if ($CliArgs -and $CliArgs[0] -eq 'build') {
    $BuildScript = Join-Path $ScriptDir 'bin\build.ps1'
    $BuildArgs = if ($CliArgs.Count -gt 1) { ,$CliArgs[1..($CliArgs.Count - 1)] } else { @() }
    if ($BuildArgs) {
        $safeArgs = ($BuildArgs | ForEach-Object { "'" + $_.Replace("'", "''") + "'" }) -join ' '
        Invoke-Expression "& '$BuildScript' -- $safeArgs"
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
subcommands (coworker, sc, test, build) that are not part of the published
browser4-cli release.

Usage: b4w [command] [options]

Commands:
  cli [args]       Run browser4-cli (default — can be omitted)
  coworker <cmd>   Manage Coworker tasks (delegates to coworker/coworker.ps1)
  sc [args]        Real-world scenario picker (delegates to bin/test.ps1 rws sc)
  test [args]      Run tests (delegates to bin/test.ps1)
  build [args]     Build the project (delegates to bin/build.ps1)
  b4w install [-WithLaunchers]  Install b4w as a global command (adds to PATH)
  b4w uninstall                Remove b4w from PATH and delete generated launcher

Pass -WithLaunchers to b4w install to also create shortcut launchers so
you can type subcommands directly:
  b4w-coworker       → b4w coworker
  b4w-sc             → b4w sc
  b4w-coworker-fix   → b4w coworker fix
  b4w-test           → b4w test
  b4w-build          → b4w build
  (and all coworker & sc sub-subcommands: b4w-coworker-draft, b4w-sc-add, etc.)

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
  b4w coworker start                    start the Coworker scheduler + GUI (background)
  b4w coworker start --sched-only       start only the scheduler (background)
  b4w coworker start --gui-only         start only the GUI server (background)
  b4w coworker start sched              run only the scheduler (foreground — Ctrl+C to stop)
  b4w coworker start gui -OpenBrowser   run only the GUI server (foreground — Ctrl+C to stop)
  b4w coworker start both -Port 8091    scheduler + GUI, custom GUI port (background)
  b4w coworker stop                     stop the background scheduler/GUI
  b4w coworker restart                  restart the background scheduler/GUI
  b4w test --e2e                        run E2E tests
  b4w sc                                interactive scenario picker
  b4w sc add my-test https://example.com  create a new scenario
  b4w build                             build browser4-cli
  b4w b4w install                       install b4w globally (one-time setup)
  b4w b4w install -WithLaunchers         install with shortcut launchers
  b4w b4w uninstall                     remove b4w from PATH and launcher

  # subcommand launchers (after b4w install -WithLaunchers)
  b4w-coworker list                     b4w coworker list
  b4w-coworker-fix                      pick and fix a task from 1ready/
  b4w-test fast                         run fast tests
  b4w-build                             build browser4-cli

Wrapper:
  b4w.sh          Git Bash / Linux / macOS wrapper — preferred for bash
                  environments when PowerShell flag interception occurs.

Tip: Use long-form flags (--output, --interactive, --viewport) for
cross-shell compatibility.  Short flags (-o, -i, -v) are now safe with
b4w.ps1 thanks to manual argument parsing that avoids PowerShell's
parameter binder.
'@

# ── Known subcommand launchers ──────────────────────────────────────────────
# When b4w install -WithLaunchers runs, it creates global wrapper scripts so
# subcommands can be invoked directly from the shell without typing "b4w" first:
#   b4w-coworker              → b4w coworker
#   b4w-sc                    → b4w sc
#   b4w-coworker-fix          → b4w coworker fix
#   b4w-test                  → b4w test
#   b4w-build                 → b4w build
#   (and all sc/coworker sub-subcommands: b4w-sc-add, b4w-coworker-draft, etc.)
#
# Each entry maps the launcher script name (without extension) to the
# b4w argument string it delegates to.
# These are NOT created by default — pass -WithLaunchers to b4w install.
$LauncherSubcommands = @(
    @{ Name = 'b4w-coworker';          Args = 'coworker' },
    @{ Name = 'b4w-coworker-draft';    Args = 'coworker draft' },
    @{ Name = 'b4w-coworker-refine';   Args = 'coworker refine' },
    @{ Name = 'b4w-coworker-assign';   Args = 'coworker assign' },
    @{ Name = 'b4w-coworker-list';     Args = 'coworker list' },
    @{ Name = 'b4w-coworker-view';     Args = 'coworker view' },
    @{ Name = 'b4w-coworker-cancel';   Args = 'coworker cancel' },
    @{ Name = 'b4w-coworker-commit';   Args = 'coworker commit' },
    @{ Name = 'b4w-coworker-push';     Args = 'coworker push' },
    @{ Name = 'b4w-coworker-fix';      Args = 'coworker fix' },
    @{ Name = 'b4w-coworker-review';   Args = 'coworker review' },
    @{ Name = 'b4w-coworker-start';    Args = 'coworker start' },
    @{ Name = 'b4w-sc';              Args = 'sc' },
    @{ Name = 'b4w-sc-add';         Args = 'sc add' },
    @{ Name = 'b4w-test';              Args = 'test' },
    @{ Name = 'b4w-build';             Args = 'build' }
)

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
#
# Subcommand shortcut launchers (b4w-coworker, b4w-test, etc.) are only
# created when the -WithLaunchers flag is passed.  These are optional
# convenience wrappers; without them, subcommands are invoked as
# `b4w coworker`, `b4w test`, etc.
if ($CliArgs -and $CliArgs[0] -eq 'b4w' -and $CliArgs[1] -eq 'install') {
    $WithLaunchers = ($CliArgs.Count -gt 2 -and $CliArgs[2] -eq '-WithLaunchers')
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

        # ── Create subcommand launchers (only with -WithLaunchers) ──────────
        # Creates b4w-coworker, b4w-test, b4w-coworker-fix, etc. in ~/.local/bin/
        # so subcommands can be invoked directly without typing "b4w" first.
        if ($WithLaunchers) {
            Write-Host ""
            $launcherCount = 0
            foreach ($entry in $LauncherSubcommands) {
                $subLauncherPath = Join-Path $globalBin $entry.Name
                $subExisted = Test-Path $subLauncherPath
                $subContent = "#!/bin/bash`n" +
                    "# $($entry.Name) — global launcher for browser4-cli.`n" +
                    "# Installed by: $ScriptDir/b4w.ps1`n" +
                    "# Delegates to: $ScriptDir/b4w.sh $($entry.Args)`n" +
                    "exec `"$ScriptDir/b4w.sh`" $($entry.Args) `"`$@`"`n"
                [System.IO.File]::WriteAllText($subLauncherPath, $subContent, [System.Text.Encoding]::ASCII)
                chmod +x $subLauncherPath 2>$null
                if ($subExisted) { Write-Host "  + Updated: $subLauncherPath" -ForegroundColor Green }
                else             { Write-Host "  + Created: $subLauncherPath" -ForegroundColor Green }
                $launcherCount++
            }
            Write-Host "  + $launcherCount subcommand launchers installed." -ForegroundColor Green
        }

        Write-Host ""
        Write-Host "Restart your shell (or run 'hash -r' / reopen the terminal)"
        Write-Host "and then you can type just:  b4w <subcommand>" -ForegroundColor Yellow
        Write-Host ""
        Write-Host "Examples:" -ForegroundColor White
        Write-Host "  b4w --help"
        if ($WithLaunchers) {
            Write-Host "  b4w-coworker list"
            Write-Host "  b4w-test --e2e"
            Write-Host "  b4w-build"
        } else {
            Write-Host "  b4w coworker list"
            Write-Host "  b4w test --e2e"
            Write-Host "  b4w build"
        }
        Write-Host "  b4w -- snapshot -i"
        if ($WithLaunchers) {
            Write-Host ""
            Write-Host "Or use subcommand launchers directly:" -ForegroundColor DarkGray
            Write-Host "  b4w-coworker-fix        # → b4w coworker fix" -ForegroundColor DarkGray
            Write-Host "  b4w-coworker-review     # → b4w coworker review" -ForegroundColor DarkGray
            Write-Host "  b4w-test fast           # → b4w test fast" -ForegroundColor DarkGray
        } else {
            Write-Host ""
            Write-Host "Tip: re-run with -WithLaunchers to create b4w-coworker, b4w-test, etc." -ForegroundColor DarkGray
        }

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

    # ── Create subcommand .bat launchers in the repo root ─────────────────
    # Only created when -WithLaunchers is passed. These are optional
    # convenience wrappers; without them, subcommands are invoked as
    # `b4w coworker`, `b4w test`, etc.
    if ($WithLaunchers) {
        $launcherCount = 0
        foreach ($entry in $LauncherSubcommands) {
            $batPath = Join-Path $ScriptDir "$($entry.Name).bat"
            $batExisted = Test-Path $batPath
            $batContent = "@echo off`r`n" +
                "REM $($entry.Name).bat — Delegates to b4w $($entry.Args)`r`n" +
                "REM Generated by b4w install. Do not edit.`r`n" +
                "setlocal`r`n" +
                "set `"SCRIPT_DIR=%~dp0`"`r`n" +
                "call `"%SCRIPT_DIR%b4w.bat`" $($entry.Args) %*`r`n" +
                "exit /b %ERRORLEVEL%`r`n"
            [System.IO.File]::WriteAllText($batPath, $batContent, [System.Text.Encoding]::ASCII)
            if ($batExisted) { Write-Host "  + Updated: $batPath" -ForegroundColor Green }
            else             { Write-Host "  + Created: $batPath" -ForegroundColor Green }
            $launcherCount++
        }
        Write-Host "  + $launcherCount subcommand launchers installed." -ForegroundColor Green
    }

    Write-Host ""
    Write-Host "b4w installed successfully!" -ForegroundColor Green
    Write-Host ""
    Write-Host "Restart your shell (or run 'refreshenv' / reopen the terminal)"
    Write-Host "and then you can type just:  b4w <subcommand>" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Examples:" -ForegroundColor White
    Write-Host "  b4w --help"
    if ($WithLaunchers) {
        Write-Host "  b4w-coworker list"
        Write-Host "  b4w-test --e2e"
        Write-Host "  b4w-build"
    } else {
        Write-Host "  b4w coworker list"
        Write-Host "  b4w test --e2e"
        Write-Host "  b4w build"
    }
    Write-Host "  b4w -- snapshot -i"
    if ($WithLaunchers) {
        Write-Host ""
        Write-Host "Or use subcommand launchers directly:" -ForegroundColor DarkGray
        Write-Host "  b4w-coworker-fix        # → b4w coworker fix" -ForegroundColor DarkGray
        Write-Host "  b4w-coworker-review     # → b4w coworker review" -ForegroundColor DarkGray
        Write-Host "  b4w-test fast           # → b4w test fast" -ForegroundColor DarkGray
    } else {
        Write-Host ""
        Write-Host "Tip: re-run with -WithLaunchers to create b4w-coworker, b4w-test, etc." -ForegroundColor DarkGray
    }

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

        # 2. Remove subcommand launchers (b4w-coworker, b4w-test, etc.)
        #    Only remove files that look like generated launchers (contain
        #    the "global launcher for browser4-cli" marker).
        $subLaunchers = Get-ChildItem -Path $globalBin -Filter 'b4w-*' -File -ErrorAction SilentlyContinue
        $removedCount = 0
        foreach ($s in $subLaunchers) {
            $content = Get-Content $s.FullName -Raw -ErrorAction SilentlyContinue
            if ($content -match 'global launcher for browser4-cli') {
                Remove-Item $s.FullName -Force
                $removedCount++
            }
        }
        if ($removedCount -gt 0) {
            Write-Host "  + Removed $removedCount subcommand launcher(s) from $globalBin" -ForegroundColor Green
        } else {
            Write-Host "  - No subcommand launchers to remove" -ForegroundColor DarkGray
        }

        # 3. Also clean up the legacy repo-local bash launcher if it exists
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

        # 4. Clean any b4w-repo PATH entries from the user PATH (legacy).
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

    # ── Remove subcommand .bat launchers from the repo root ─────────────
    $batLaunchers = Get-ChildItem -Path $ScriptDir -Filter 'b4w-*.bat' -File -ErrorAction SilentlyContinue
    $batCount = 0
    foreach ($b in $batLaunchers) {
        $c = Get-Content $b.FullName -Raw -ErrorAction SilentlyContinue
        if ($c -match 'Generated by b4w install') {
            Remove-Item $b.FullName -Force
            $batCount++
        }
    }
    if ($batCount -gt 0) {
        Write-Host "  + Removed $batCount subcommand .bat launcher(s) from $ScriptDir" -ForegroundColor Green
    } else {
        Write-Host "  - No subcommand .bat launchers to remove" -ForegroundColor DarkGray
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
  b4w install [-WithLaunchers]  Install b4w as a global command (adds to PATH)
  b4w uninstall                 Remove b4w from PATH and delete all generated launchers

With -WithLaunchers, subcommand shortcut launchers are created so you can type:
  b4w-coworker       → b4w coworker
  b4w-sc             → b4w sc
  b4w-coworker-fix   → b4w coworker fix
  b4w-test           → b4w test
  b4w-build          → b4w build
  (and all coworker & sc sub-subcommands: b4w-coworker-draft, b4w-sc-add, etc.)

Examples:
  ./b4w.ps1 b4w install                 install b4w globally
  ./b4w.ps1 b4w install -WithLaunchers   install with shortcut launchers
  ./b4w.ps1 b4w uninstall               remove b4w from PATH
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

# After a -Rebuild-forced bundle rebuild, persist the backend source hash so
# subsequent invocations skip the staleness warning.
if ($ForcedBundleRebuild -and $BackendHashToCache) {
    try {
        New-Item -Path (Split-Path $BackendHashFile -Parent) -ItemType Directory -Force -ErrorAction SilentlyContinue > $null
        $BackendHashToCache | Out-File -FilePath $BackendHashFile -Encoding ascii -NoNewline
    } catch { }
}

# Capture the CLI's exit status BEFORE restoring the working directory.
# All four invocation branches above (direct exe / cargo run fallback, with
# or without args) run a native command whose status lands in $LASTEXITCODE.
$CliExitCode = $LASTEXITCODE

# Restore the original working directory so the caller's shell session
# (e.g., Git Bash) sees a consistent CWD after b4w.ps1 exits.
Set-Location $OriginalCwd

# Propagate the CLI's exit code so scripts, CI, and &&-chains can detect
# failure.  Without this, pwsh -File / Git Bash / b4w.bat invocations of
# this script always report success even when the CLI printed an error
# (usage errors exit 2, tool failures exit 1, ...).  cmdlets like
# Set-Location above do not modify $LASTEXITCODE, so it still holds the
# CLI's status here.
exit $CliExitCode
