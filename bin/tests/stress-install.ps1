#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Stress-test the Browser4 install, server-start, and shutdown lifecycle.

.DESCRIPTION
    Exercises the full lifecycle: explicit `install`, auto-install on first use,
    server auto-start / stop / restart, kill-all recovery, close-all vs kill-all
    distinction, and state-file integrity across repeated cycles.

    Covers:
      - install (fresh, repeated, --force)
      - Server auto-start on first command
      - stop / kill-all / close-all and their behavioral differences
      - Server restart after each shutdown variant
      - Rapid stop->open->close and kill-all->open->close cycles
      - State file integrity (installation.json, cli-state.json, managed-processes.json)

    All CLI invocations are logged to a per-run directory under bin/tests/logs/.
    Failures are reported with log paths.  If `copilot` is on PATH, it is
    invoked automatically to analyse any failures.

.PARAMETER Iterations
    Number of full test cycles (default: 2).

.PARAMETER Seed
    RNG seed for reproducible runs (default: random).

.PARAMETER SkipInstall
    Skip the explicit `install` phases (A) -- useful when running inside a repo
    that already has a local runtime bundle built.

.NOTES
    This script WILL delete the versioned runtime install directory under the
    platform data directory to test fresh-install paths.  It will also kill and
    restart the Browser4 server multiple times.  Run it in an environment where
    this is acceptable.
#>
param(
    [int] $Iterations = 2,
    [int] $Seed = (Get-Random),
    [switch] $SkipInstall,
    [string] $Tag = '4.10.0'
)

$ErrorActionPreference = 'Continue'

# Switch the console code page to UTF-8 (65001) so box-drawing glyphs,
# emoji, and other Unicode characters render correctly.  On Chinese
# Windows the default is 936 (GBK), which mangles UTF-8 byte sequences
# into CJK gibberish.  `chcp` changes the *console's interpretation* of
# output bytes; OutputEncoding / InputEncoding alone only affect .NET
# stream encoding, not how the host renders them.
if ($IsWin) {
    $null = & chcp 65001
}
[Console]::OutputEncoding = [Text.Encoding]::UTF8
[Console]::InputEncoding  = [Text.Encoding]::UTF8

# -------------------------------------------------------------------
# Load shared test utilities (logging, status tracking, copilot)
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'stress-install'

Write-TestHeader -Name 'stress-install'

# -------------------------------------------------------------------
# CLI helper — uses global browser4-cli by default.
# Set BROWSER4_CLI_BIN to override (e.g. to a local dev binary).
# -------------------------------------------------------------------
$script:__CliBin = $env:BROWSER4_CLI_BIN
if (-not $script:__CliBin) {
    # Use @(...) to guard against npm returning both .cmd and extensionless shims on Windows
    $cmds = @(Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue)
    if ($cmds.Count -gt 0) {
        $script:__CliBin = $cmds[0].Source
    } else {
        # Fall back to where.exe on Windows, `which` on Unix.
        $whichCmd = if ($IsWindows) { 'where.exe' } else { 'which' }
        $raw = & $whichCmd 'browser4-cli' 2>$null | Select-Object -First 1
        if ($raw) { $script:__CliBin = $raw.Trim() }
    }
    if (-not $script:__CliBin) {
        throw 'browser4-cli not found on PATH. Install it with: npm i -g browser4-cli && browser4-cli install'
    }
    Write-Host "  (using global browser4-cli: $script:__CliBin)" -ForegroundColor DarkGray
}

$cli = { & $script:__CliBin @args 2>&1 }

# Wrapper that captures $LASTEXITCODE *before* the pipeline ends.
# Windows PowerShell 5.1 clears $LASTEXITCODE after piping to a
# cmdlet like ForEach-Object; PS 6+ preserves it.  Saving it inside
# the producer scriptblock works on both.
$script:__InvokeCliProducer = {
    $raw = & $cli @args
    $global:__InvokeCliExitCode = $LASTEXITCODE
    $raw
}

function Invoke-Cli {
    $desc = ($args -join ' ')
    Write-Host "       [$(Get-Date -Format 'HH:mm:ss')] cli $desc ..." -ForegroundColor DarkGray
    $sw = [Diagnostics.Stopwatch]::StartNew()
    # Stream output line-by-line so long-running commands (e.g. install
    # --force downloads) show progress in real time instead of appearing
    # hung.  Each line is both captured and written to the console.
    #
    # $LASTEXITCODE is captured inside the producer scriptblock so it
    # survives the pipeline on Windows PowerShell 5.1 (which clears it
    # after piping to a cmdlet).
    $out = @()
    $global:__InvokeCliExitCode = 0
    & $script:__InvokeCliProducer @args | ForEach-Object {
        $out += $_
        Write-Host $_
    }
    $exitCode = $global:__InvokeCliExitCode
    $sw.Stop()

    # Register with test-utils for structured logging and copilot analysis
    Register-CliResult -Label $desc -ExitCode $exitCode -OutputLines $out `
        -Elapsed $sw.Elapsed -ExpectedExitCode 0

    if ($exitCode -ne 0) {
        $msg = "CLI command failed (exit=$exitCode): $desc"
        Write-Host "       FAIL: $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("       FAIL: $msg`nOutput:`n$($out -join "`n")")
    }
    if ($sw.Elapsed.TotalSeconds -ge 5) {
        Write-Host "       took $([math]::Round($sw.Elapsed.TotalSeconds, 1))s" -ForegroundColor DarkGray
    }
    $out
}

# -------------------------------------------------------------------
# Status reporter (fires every 30 s via background timer)
# -------------------------------------------------------------------
$script:StatusPhase   = 'init'
$script:StatusStep    = ''
$script:StatusPasses  = 0
$script:StatusIter    = 0
$script:StatusTotal   = 0
$script:StatusTimer   = $null
$script:StatusSuiteStart = [datetime]::MinValue

# Per-assertion failure tracking (soft asserts -- failures are recorded,
# not thrown, so the suite continues through the remaining test cases).
$global:TestPassed   = 0
$global:TestFailed   = 0
$global:FailureMessages = [System.Collections.ArrayList]::new()

function Start-StatusReporter {
    $script:StatusSuiteStart = Get-Date
    $script:StatusTimer = [System.Timers.Timer]::new(30000)
    $script:StatusTimer.AutoReset = $true
    $null = Register-ObjectEvent -InputObject $script:StatusTimer -EventName Elapsed -Action {
        $elapsed = [math]::Round(((Get-Date) - $script:StatusSuiteStart).TotalMinutes, 1)
        $msg = "--- STATUS [$($elapsed) min] | iter $($script:StatusIter)/$($script:StatusTotal) | $($script:StatusPhase) $($script:StatusStep) | passes: $($script:StatusPasses) ---"
        Write-Host "`n$msg" -ForegroundColor Magenta
    }
    $script:StatusTimer.Start()
    Write-Host "  (status reported every 30 s)" -ForegroundColor DarkGray
}

function Stop-StatusReporter {
    if ($script:StatusTimer) {
        $script:StatusTimer.Stop()
        $script:StatusTimer.Dispose()
        $script:StatusTimer = $null
    }
    Get-EventSubscriber | Where-Object { $_.SourceObject -is [System.Timers.Timer] } |
        Unregister-Event -Force -ErrorAction SilentlyContinue
}

function Set-Status {
    param([string]$Phase, [string]$Step, [int]$Passes)
    $script:StatusPhase  = $Phase
    $script:StatusStep   = $Step
    $script:StatusPasses = $Passes
}

function Set-StatusIter {
    param([int]$Iter)
    $script:StatusIter = $Iter
}

# -------------------------------------------------------------------
# Helpers
# -------------------------------------------------------------------
filter session-data-rows {
    $_ | Where-Object {
        $_ -match '\S' -and
        $_ -notmatch '^\s*-+\s*' -and
        $_ -notmatch '^Name\b' -and
        $_ -notmatch '^Note:' -and
        # Exclude rustc / cargo build output that may leak through when
        # using `cargo run` (warnings, errors, source locations, and
        # diagnostic annotations).
        $_ -notmatch '^\s*(warning|error)\b' -and
        $_ -notmatch '^\s*-->' -and
        $_ -notmatch '^\s*[|]' -and
        $_ -notmatch '^\s*=\s*(note|help):'
    }
}

$rng = [Random]::new($Seed)

# Resolve the Browser4 state directory.
$StateDir = if ($env:BROWSER4_CLI_STATE_DIR) { $env:BROWSER4_CLI_STATE_DIR }
            else { Join-Path $HOME '.browser4' }

# Resolve the Browser4 runtime data directory (mirrors Rust's resolve_runtime_data_dir()).
# On Linux:   $XDG_DATA_HOME/browser4 (typically ~/.local/share/browser4/)
# On macOS:   ~/Library/Application Support/browser4/
# On Windows: %APPDATA%/browser4/
# Honours BROWSER4_RUNTIME_DIR as an override.
if (-not (Get-Variable -Name 'IsLinux' -ErrorAction SilentlyContinue)) { $IsLinux = $false }
if (-not (Get-Variable -Name 'IsMacOS' -ErrorAction SilentlyContinue)) { $IsMacOS = $false }
$RuntimeDataDir = if ($env:BROWSER4_RUNTIME_DIR) { $env:BROWSER4_RUNTIME_DIR }
                  elseif ($IsLinux) { Join-Path $HOME '.local/share/browser4' }
                  elseif ($IsMacOS) { Join-Path $HOME 'Library/Application Support/browser4' }
                  else { Join-Path $env:APPDATA 'browser4' }

# Ensure the CLI binary uses the same runtime directory we are checking.
if (-not $env:BROWSER4_RUNTIME_DIR) {
    $env:BROWSER4_RUNTIME_DIR = $RuntimeDataDir
}

# Versioned install directory for the tag under test.
# Mirror Rust's normalize_release_tag(): if the tag doesn't start with 'v',
# prepend one — the CLI normalises "4.10.0" to "v4.10.0".
$NormalizedTag = if ($Tag.StartsWith('v')) { $Tag } else { "v$Tag" }
$RuntimeVersionsDir = Join-Path $RuntimeDataDir 'runtime'
$VersionedInstallDir = Join-Path $RuntimeVersionsDir $NormalizedTag
$InstallMetaFile = Join-Path $VersionedInstallDir 'browser4-installation.json'

$CliStateFile = Join-Path $StateDir 'cli-state.json'
$ManagedProcsFile = Join-Path $StateDir 'cli-managed-processes.json'

# -------------------------------------------------------------------
# Resolution for platform-dependent paths inside the runtime bundle.
# -------------------------------------------------------------------
$RuntimeBinDir = Join-Path $VersionedInstallDir 'runtime' | Join-Path -ChildPath 'bin'
$IsWin = $env:OS -eq 'Windows_NT'  # works on PS 5.1 and 6+
$JavaExe = if ($IsWin) { 'java.exe' } else { 'java' }
$JavaBin = Join-Path $RuntimeBinDir $JavaExe

# Stress-test page (lightweight, stable).
$TestUrl = 'https://news.ycombinator.com/'
$TestKeyword = 'ycombinator'

# -------------------------------------------------------------------
# Assertion helpers
# -------------------------------------------------------------------
function Assert-True {
    param([string]$Label, [scriptblock]$Condition)
    try {
        $result = & $Condition
        if (-not $result) {
            $msg = "FAIL: $Label`n      Condition returned: $result"
            Write-Host "    $msg" -ForegroundColor Red
            $null = $global:FailureMessages.Add("    $msg")
            $global:TestFailed++
            return
        }
    } catch {
        $msg = "FAIL: $Label`n      Exception: $($_.Exception.Message)"
        Write-Host "    $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("    $msg")
        $global:TestFailed++
        return
    }
    Write-Host "    ok $Label" -ForegroundColor Green
    $global:TestPassed++
}

function Assert-FileExists {
    param([string]$Path, [string]$Label)
    if (-not (Test-Path $Path)) {
        $msg = "FAIL: $Label`n      File not found: $Path"
        Write-Host "    $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("    $msg")
        $global:TestFailed++
        return
    }
    Write-Host "    ok $Label" -ForegroundColor Green
    $global:TestPassed++
}

function Assert-JsonValid {
    param([string]$Path, [string]$Label)
    try {
        $null = Get-Content -Raw $Path | ConvertFrom-Json
    } catch {
        $msg = "FAIL: $Label`n      Invalid JSON in $Path : $($_.Exception.Message)"
        Write-Host "    $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("    $msg")
        $global:TestFailed++
        return
    }
    Write-Host "    ok $Label" -ForegroundColor Green
    $global:TestPassed++
}

function Get-SessionDataRows {
    $out = Invoke-Cli list
    return ($out | session-data-rows)
}

function Assert-SessionCount {
    param([int]$Expected, [string]$Context)
    $rows = Get-SessionDataRows
    $label = "session count == $Expected  ($Context)"
    if ($rows.Count -ne $Expected) {
        $all = Invoke-Cli list
        $msg = "FAIL: $label`n      found $($rows.Count) row(s):`n$($all -join "`n")"
        Write-Host "    $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("    $msg")
        $global:TestFailed++
        return
    }
    Write-Host "    ok $label" -ForegroundColor Green
    $global:TestPassed++
}

function Assert-SnapshotContains {
    param([string]$Keyword, [string]$Context)
    # A post-command snapshot inside `open` may briefly yield about:blank
    # if the session is being recycled.  Retry once after a short wait.
    $maxRetries = 2
    for ($retry = 0; $retry -le $maxRetries; $retry++) {
        if ($retry -gt 0) {
            Write-Host "       (snapshot retry $retry / $maxRetries)" -ForegroundColor DarkGray
            Wait-WithStatus -Seconds 3
        }
        $snap = Invoke-Cli snapshot
        $text = $snap -join ' '
        if ($text -like "*$Keyword*") {
            Write-Host "    ok snapshot contains '$Keyword'  ($Context)" -ForegroundColor Green
            $global:TestPassed++
            return
        }
        if ($text -like '*about:blank*') {
            continue  # transient -- retry
        }
        # Not blank but also missing keyword -- fail immediately.
        break
    }
    $msg = "FAIL: snapshot contains '$Keyword'  ($Context)`n      snapshot output:`n$($snap -join "`n")"
    Write-Host "    $msg" -ForegroundColor Red
    $null = $global:FailureMessages.Add("    $msg")
    $global:TestFailed++
}

# -------------------------------------------------------------------
# Timed wait helper -- sleeps in 10 s chunks, printing status via
# the background timer, plus an explicit heartbeat line every 30 s.
# -------------------------------------------------------------------
function Wait-WithStatus {
    param([int]$Seconds)
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $nextHeartbeat = 30
    while ($sw.Elapsed.TotalSeconds -lt $Seconds) {
        $remaining = $Seconds - $sw.Elapsed.TotalSeconds
        $chunk = [math]::Min(10, [math]::Max(1, $remaining))
        Start-Sleep -Seconds $chunk
        if ($sw.Elapsed.TotalSeconds -ge $nextHeartbeat) {
            Write-Host "       [$(Get-Date -Format 'HH:mm:ss')] waiting ... ($([math]::Round($sw.Elapsed.TotalSeconds, 0))s elapsed, $([math]::Round($remaining, 0))s remaining)" -ForegroundColor DarkGray
            $nextHeartbeat += 30
        }
    }
    $sw.Stop()
}

# -------------------------------------------------------------------
# Clean start
# -------------------------------------------------------------------
Write-Host "`n=== INSTALL STRESS TEST ===" -ForegroundColor Cyan
Write-Host "  Iterations  : $Iterations" -ForegroundColor Cyan
Write-Host "  Seed        : $Seed" -ForegroundColor Cyan
Write-Host "  Tag         : $Tag" -ForegroundColor Cyan
Write-Host "  State dir   : $StateDir" -ForegroundColor Cyan
Write-Host "  SkipInstall : $SkipInstall" -ForegroundColor Cyan
Write-Host "  Started     : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Cyan
Write-Host ""

Write-Host "-- Ensuring clean slate (close current session if any) --" -ForegroundColor DarkYellow
# Only close the current CLI session -- `close-all` would tear down the
# Chrome browser underneath a running server, causing the next `open`
# to navigate to about:blank.
try { $null = Invoke-Cli close } catch { Write-Host "       (no session to close)" -ForegroundColor DarkGray }
Wait-WithStatus -Seconds 2

if (-not $SkipInstall) {
    Write-Host "-- Removing versioned install for fresh-install test --" -ForegroundColor DarkYellow
    # Remove the versioned install directory so the next install starts fresh.
    if (Test-Path $VersionedInstallDir) {
        Remove-Item -Recurse -Force $VersionedInstallDir -ErrorAction Stop
        Write-Host "       Removed $VersionedInstallDir"
    }
    # Also remove current.tag so the runtime is treated as not installed.
    $currentTagFile = Join-Path $RuntimeVersionsDir 'current.tag'
    if (Test-Path $currentTagFile) {
        Remove-Item -Force $currentTagFile -ErrorAction Stop
        Write-Host "       Removed $currentTagFile"
    }
} else {
    Write-Host "-- SkipInstall: keeping existing runtime install --" -ForegroundColor DarkGray
}

# -------------------------------------------------------------------
# Main test loop
# -------------------------------------------------------------------
$suiteTimer = [Diagnostics.Stopwatch]::StartNew()

Start-StatusReporter
Set-StatusIter -Iter 1
$script:StatusTotal = $Iterations

for ($iter = 1; $iter -le $Iterations; $iter++) {
    Write-Host "`n============================================================" -ForegroundColor Cyan
    Write-Host "  ITERATION $iter / $Iterations  (seed=$Seed)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor Cyan
    Write-Host "============================================================" -ForegroundColor Cyan

    Set-StatusIter -Iter $iter
    $prevTotal  = $global:TestPassed + $global:TestFailed
    $prevPassed = $global:TestPassed

    # Catch unexpected terminating errors within this iteration so one
    # rogue throw doesn't kill the whole suite.
    trap {
        $msg = "UNEXPECTED ERROR in iteration $($iter): $_"
        Write-Host "    $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("    $msg")
        $global:TestFailed++
        continue
    }

    # ==============================================================
    # Phase A: Explicit install lifecycle
    # ==============================================================
    if (-not $SkipInstall) {
        Write-Host "`n  -- Phase A: explicit install  [$(Get-Date -Format 'HH:mm:ss')] --" -ForegroundColor DarkYellow
        Set-Status -Phase 'A (install)' -Step 'A1' -Passes $global:TestPassed

        # A1: status before install
        Write-Host "`n  A1. status before install  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
        $statusOut = Invoke-Cli status
        Assert-True "status reports 'not installed' or no runtime" {
            ($statusOut -join ' ') -notmatch 'Runtime version' -or
            ($statusOut -join ' ') -match '(not installed|no runtime|not found)'
        }

        # A2: install
        Write-Host "`n  A2. install  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
        Set-Status -Phase 'A (install)' -Step 'A2 install' -Passes $global:TestPassed
        Invoke-Cli install --tag $Tag
        Assert-FileExists $InstallMetaFile "installation.json created"
        Assert-JsonValid $InstallMetaFile "installation.json is valid JSON"
        try { $meta = Get-Content -Raw $InstallMetaFile | ConvertFrom-Json } catch { $meta = $null }
        Assert-True "installation.json has tag" { -not [string]::IsNullOrWhiteSpace($meta.tag) }
        Assert-True "installation.json has asset_name" { -not [string]::IsNullOrWhiteSpace($meta.asset_name) }
        Assert-True "installation.json has installed_at" { -not [string]::IsNullOrWhiteSpace($meta.installed_at) }
        $firstInstalledAt = $meta.installed_at

        # A3: install again (no --force) -- should be no-op / reuse
        Write-Host "`n  A3. install (repeat, no --force)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
        Set-Status -Phase 'A (install)' -Step 'A3 install repeat' -Passes $global:TestPassed
        $install2Out = Invoke-Cli install --tag $Tag
        Assert-True "second install reuses or says already installed" {
            ($install2Out -join ' ') -match '(already|reus|up to date|no update)'
        }

        # A4: install --force
        Write-Host "`n  A4. install --force  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
        Set-Status -Phase 'A (install)' -Step 'A4 install --force' -Passes $global:TestPassed
        Invoke-Cli install --force --tag $Tag
        try { $meta2 = Get-Content -Raw $InstallMetaFile | ConvertFrom-Json } catch { $meta2 = $null }
        Assert-True "installation.json readable after --force" { $null -ne $meta2 }
        Assert-True "install --force updates installed_at timestamp" {
            $null -ne $meta2 -and $meta2.installed_at -ne $firstInstalledAt
        }

        # A5: status after install
        Write-Host "`n  A5. status after install  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
        $statusOut2 = Invoke-Cli status
        Assert-True "status shows runtime version" {
            ($statusOut2 -join ' ') -match 'Runtime|version|tag|installed'
        }

    } else {
        Write-Host "`n  -- Phase A: SKIPPED (--SkipInstall)  [$(Get-Date -Format 'HH:mm:ss')] --" -ForegroundColor DarkGray
    }

    # ==============================================================
    # Phase B: Server auto-start lifecycle
    # ==============================================================
    Write-Host "`n  -- Phase B: server auto-start / stop / restart  [$(Get-Date -Format 'HH:mm:ss')] --" -ForegroundColor DarkYellow

    # B1: open page (server pre-started by harness or previous phase)
    Write-Host "`n  B1. open page  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'B (server)' -Step 'B1 open' -Passes $global:TestPassed
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    Assert-SnapshotContains $TestKeyword "after open"
    Assert-SessionCount 1 "after open"

    # B2: status shows healthy
    Write-Host "`n  B2. status (server should be healthy)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'B (server)' -Step 'B2 status' -Passes $global:TestPassed
    $statusOut = Invoke-Cli status
    Assert-True "status reports server running/healthy" {
        ($statusOut -join ' ') -match '(running|healthy|UP|ready|active)'
    }

    # B3: close session (server survives)
    Write-Host "`n  B3. close (server survives)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'B (server)' -Step 'B3 close' -Passes $global:TestPassed
    Invoke-Cli close
    Assert-SessionCount 0 "after close"
    $statusAfterClose = Invoke-Cli status
    Assert-True "server still running after close" {
        ($statusAfterClose -join ' ') -match '(running|healthy|UP|ready|active)'
    }

    # Give the server a moment to fully tear down the session before
    # creating a new one with the same name.
    Wait-WithStatus -Seconds 2

    # B4: open again (reuses server, faster)
    Write-Host "`n  B4. open again (reuses running server)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'B (server)' -Step 'B4 open reuse' -Passes $global:TestPassed
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Cli open $TestUrl
    $sw.Stop()
    Assert-SnapshotContains $TestKeyword "after second open"
    Assert-SessionCount 1 "after second open"
    Write-Host "       open took $([math]::Round($sw.Elapsed.TotalSeconds, 1))s (server already running)" -ForegroundColor DarkGray

    # B5: stop gracefully
    Write-Host "`n  B5. stop (graceful shutdown)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'B (server)' -Step 'B5 stop' -Passes $global:TestPassed
    Invoke-Cli stop
    Wait-WithStatus -Seconds 3
    $statusStopped = Invoke-Cli status
    Assert-True "status reports server stopped after stop" {
        ($statusStopped -join ' ') -notmatch '(running|healthy|UP|ready|active)' -or
        ($statusStopped -join ' ') -match '(stopped|not running|down|unreachable)'
    }

    # B6: double stop (no server)
    Write-Host "`n  B6. stop, stop (double stop)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'B (server)' -Step 'B6 double stop' -Passes $global:TestPassed
    $stop2Out = Invoke-Cli stop
    Assert-True "double stop is graceful" {
        ($stop2Out -join ' ') -match '(not running|no.*server|nothing|already|stopped)'
    }

    # Clean up the managed-processes registry so the next open starts
    # from a blank slate rather than trying to manage a dead PID.
    if (Test-Path $ManagedProcsFile) {
        Remove-Item $ManagedProcsFile -Force -ErrorAction SilentlyContinue
        Write-Host "       (cleared stale managed-processes.json)" -ForegroundColor DarkGray
    }

    # B7: open after stop (server auto-restarts)
    Write-Host "`n  B7. open after stop (server should auto-restart)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'B (server)' -Step 'B7 open after stop' -Passes $global:TestPassed
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    Assert-SnapshotContains $TestKeyword "after stop + open"
    Assert-SessionCount 1 "after stop + open"
    Invoke-Cli close
    Assert-SessionCount 0 "after close (B7)"

    # ==============================================================
    # Phase C: kill-all / recovery cycles
    # ==============================================================
    Write-Host "`n  -- Phase C: kill-all / recovery  [$(Get-Date -Format 'HH:mm:ss')] --" -ForegroundColor DarkYellow

    # C1: open, kill-all, verify total stop
    Write-Host "`n  C1. open, kill-all, verify  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'C (kill-all)' -Step 'C1 kill-all' -Passes $global:TestPassed
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    Assert-SnapshotContains $TestKeyword "before kill-all"
    Assert-SessionCount 1 "before kill-all"
    $null = Invoke-Cli kill-all
    Wait-WithStatus -Seconds 5
    Assert-SessionCount 0 "after kill-all"

    # C2: open after kill-all (fresh server)
    Write-Host "`n  C2. open after kill-all (fresh server)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'C (kill-all)' -Step 'C2 open after kill-all' -Passes $global:TestPassed
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    Assert-SnapshotContains $TestKeyword "after kill-all + open"
    Assert-SessionCount 1 "after kill-all + open"

    # C3: double kill-all
    Write-Host "`n  C3. kill-all, kill-all (double)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'C (kill-all)' -Step 'C3 double kill-all' -Passes $global:TestPassed
    $null = Invoke-Cli kill-all
    Wait-WithStatus -Seconds 3
    $null = Invoke-Cli kill-all
    Wait-WithStatus -Seconds 3
    Assert-SessionCount 0 "after double kill-all"

    # C4: rapid kill-all, open, close x3
    Write-Host "`n  C4. rapid kill-all, open, close (x3)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    1..3 | ForEach-Object {
        Write-Host "       cycle $_/3  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor DarkGray
        Set-Status -Phase 'C (kill-all)' -Step "C4 rapid cycle $_/3" -Passes $global:TestPassed
        $null = Invoke-Cli kill-all
        Wait-WithStatus -Seconds 4
        Invoke-Cli open $TestUrl
        Wait-WithStatus -Seconds 2
        Assert-SnapshotContains $TestKeyword "rapid cycle $_"
        Invoke-Cli close
        Assert-SessionCount 0 "rapid cycle $_ close"
    }

    # ==============================================================
    # Phase D: close-all vs kill-all distinction
    # ==============================================================
    Write-Host "`n  -- Phase D: close-all vs kill-all  [$(Get-Date -Format 'HH:mm:ss')] --" -ForegroundColor DarkYellow

    # D1: open, close, server still running
    Write-Host "`n  D1. open, close (server survives)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'D (close vs kill)' -Step 'D1 close' -Passes $global:TestPassed
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    Invoke-Cli close
    Assert-SessionCount 0 "after close"
    $statusD1 = Invoke-Cli status
    Assert-True "server still running after close" {
        ($statusD1 -join ' ') -match '(running|healthy|UP|ready|active)'
    }

    # D2: open, close-all, server still running
    Write-Host "`n  D2. open, close-all (server survives)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'D (close vs kill)' -Step 'D2 close-all' -Passes $global:TestPassed
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    Invoke-Cli close-all
    Wait-WithStatus -Seconds 1
    Assert-SessionCount 0 "after close-all"
    $statusD2 = Invoke-Cli status
    Assert-True "server still running after close-all" {
        ($statusD2 -join ' ') -match '(running|healthy|UP|ready|active)'
    }

    # D3: open, kill-all, both gone
    Write-Host "`n  D3. open, kill-all (server stops)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'D (close vs kill)' -Step 'D3 kill-all' -Passes $global:TestPassed
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    $null = Invoke-Cli kill-all
    Wait-WithStatus -Seconds 5
    Assert-SessionCount 0 "after kill-all (D3)"
    $statusD3 = Invoke-Cli status
    Assert-True "server stopped after kill-all" {
        ($statusD3 -join ' ') -notmatch '(running|healthy|UP|ready|active)' -or
        ($statusD3 -join ' ') -match '(stopped|not running|down|unreachable)'
    }

    # D4: after close-all, open reuses running server (faster)
    Write-Host "`n  D4. close-all, open reuses server (no restart)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'D (close vs kill)' -Step 'D4 close-all reuse' -Passes $global:TestPassed
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    Assert-SnapshotContains $TestKeyword "D4 before close-all"
    Invoke-Cli close-all
    Wait-WithStatus -Seconds 1
    $sw = [Diagnostics.Stopwatch]::StartNew()
    Invoke-Cli open $TestUrl
    $sw.Stop()
    Assert-SnapshotContains $TestKeyword "D4 after close-all + open"
    Write-Host "       open after close-all took $([math]::Round($sw.Elapsed.TotalSeconds, 1))s (should be fast)" -ForegroundColor DarkGray
    Assert-True "open after close-all is fast (< 30s, server already running)" {
        $sw.Elapsed.TotalSeconds -lt 30
    }
    Invoke-Cli close

    # ==============================================================
    # Phase E: Rapid cycles
    # ==============================================================
    Write-Host "`n  -- Phase E: rapid stop/kill, open, close cycles  [$(Get-Date -Format 'HH:mm:ss')] --" -ForegroundColor DarkYellow

    Write-Host "`n  E1. stop, open, close (x5)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    1..5 | ForEach-Object {
        Write-Host "       cycle $_/5  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor DarkGray
        Set-Status -Phase 'E (rapid)' -Step "E1 stop cycle $_/5" -Passes $global:TestPassed
        Invoke-Cli stop
        Wait-WithStatus -Seconds 2
        Invoke-Cli open $TestUrl
        Wait-WithStatus -Seconds 2
        Assert-SnapshotContains $TestKeyword "E1 cycle $_"
        Invoke-Cli close
        Assert-SessionCount 0 "E1 cycle $_ close"
    }

    Write-Host "`n  E2. kill-all, open, close (x3)  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    1..3 | ForEach-Object {
        Write-Host "       cycle $_/3  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor DarkGray
        Set-Status -Phase 'E (rapid)' -Step "E2 kill-all cycle $_/3" -Passes $global:TestPassed
        $null = Invoke-Cli kill-all
        Wait-WithStatus -Seconds 4
        Invoke-Cli open $TestUrl
        Wait-WithStatus -Seconds 2
        Assert-SnapshotContains $TestKeyword "E2 cycle $_"
        Invoke-Cli close
        Assert-SessionCount 0 "E2 cycle $_ close"
    }

    # ==============================================================
    # Phase F: State file integrity
    # ==============================================================
    Write-Host "`n  -- Phase F: state file integrity  [$(Get-Date -Format 'HH:mm:ss')] --" -ForegroundColor DarkYellow

    # F1: open creates cli-state.json
    Write-Host "`n  F1. open creates cli-state.json  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'F (state)' -Step 'F1 cli-state' -Passes $global:TestPassed
    if (Test-Path $CliStateFile) { Remove-Item $CliStateFile -Force }
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    Assert-FileExists $CliStateFile "cli-state.json exists after open"
    Assert-JsonValid $CliStateFile "cli-state.json is valid JSON"
    try { $cliState = Get-Content -Raw $CliStateFile | ConvertFrom-Json } catch { $cliState = $null }
    Assert-True "cli-state.json has sessionId" { -not [string]::IsNullOrWhiteSpace($cliState.sessionId) }
    Assert-True "cli-state.json has baseUrl" { -not [string]::IsNullOrWhiteSpace($cliState.baseUrl) }

    # F2: close clears sessionId
    Write-Host "`n  F2. close clears sessionId  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'F (state)' -Step 'F2 close clears' -Passes $global:TestPassed
    Invoke-Cli close
    try { $cliState2 = Get-Content -Raw $CliStateFile -ErrorAction SilentlyContinue | ConvertFrom-Json } catch { $cliState2 = $null }
    # clear_state (called by `close`) removes the file entirely via
    # fs::remove_file.  Either outcome — file gone, or sessionId blank
    # — means the session is deactivated.
    Assert-True "session deactivated after close" {
        $null -eq $cliState2 -or [string]::IsNullOrWhiteSpace($cliState2.sessionId)
    }

    # F3: installation.json + runtime files
    if (-not $SkipInstall -and (Test-Path $InstallMetaFile)) {
        Write-Host "`n  F3. installation.json integrity  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
        Set-Status -Phase 'F (state)' -Step 'F3 install integrity' -Passes $global:TestPassed
        Assert-JsonValid $InstallMetaFile "installation.json is valid JSON"
        try { $instMeta = Get-Content -Raw $InstallMetaFile | ConvertFrom-Json } catch { $instMeta = $null }
        Assert-True "installation.json has required fields" {
            $instMeta.tag -and $instMeta.asset_name -and $instMeta.installed_at
        }
        Assert-FileExists $JavaBin "bundled java exists"
        $libJars = Get-ChildItem -Path (Join-Path $VersionedInstallDir 'lib') -Filter '*.jar' -ErrorAction SilentlyContinue
        Assert-True "lib/*.jar files exist" { $libJars.Count -gt 0 }

    }

    # F4: managed-processes.json after server start
    Write-Host "`n  F4. managed-processes.json after server start  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'F (state)' -Step 'F4 managed-procs' -Passes $global:TestPassed
    Invoke-Cli open $TestUrl
    Wait-WithStatus -Seconds 3
    Assert-FileExists $ManagedProcsFile "managed-processes.json exists"
    Assert-JsonValid $ManagedProcsFile "managed-processes.json is valid JSON"

    # F5: kill-all clears managed processes
    Write-Host "`n  F5. kill-all clears managed processes  [$(Get-Date -Format 'HH:mm:ss')]" -ForegroundColor White
    Set-Status -Phase 'F (state)' -Step 'F5 kill-all clear' -Passes $global:TestPassed
    $null = Invoke-Cli kill-all
    Wait-WithStatus -Seconds 5
    try { $procData = Get-Content -Raw $ManagedProcsFile -ErrorAction SilentlyContinue | ConvertFrom-Json } catch { $procData = $null }
    # write_managed_server_processes removes the registry file when the
    # list is empty, so `kill-all` may delete managed-processes.json
    # entirely.  Either outcome — file gone, or empty PID list — is a
    # clean state.
    $activePids = if ($procData) {
        @($procData.PSObject.Properties | Where-Object { -not [string]::IsNullOrWhiteSpace($_.Value) })
    } else { @() }
    Assert-True "no active PIDs after kill-all" {
        $null -eq $procData -or $activePids.Count -eq 0
    }

    # ==============================================================
    # Iteration summary
    # ==============================================================
    $iterChecks = ($global:TestPassed + $global:TestFailed) - $prevTotal
    $iterOk     = $global:TestPassed - $prevPassed
    Write-Host "`n  -- Iteration $iter summary  [$(Get-Date -Format 'HH:mm:ss')] --" -ForegroundColor Cyan
    Write-Host "  Checks this iter: $iterChecks (passed: $iterOk, failed: $($iterChecks - $iterOk))" -ForegroundColor Cyan
}

# -------------------------------------------------------------------
# Final report
# -------------------------------------------------------------------
Stop-StatusReporter
$suiteTimer.Stop()

$totalChecks = $global:TestPassed + $global:TestFailed
$exitCode = if ($global:TestFailed -gt 0) { 1 } else { 0 }

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  INSTALL STRESS TEST RESULTS" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Seed        : $Seed"
Write-Host "  Iterations  : $Iterations"
Write-Host "  Total checks: $totalChecks"
if ($global:TestPassed -gt 0) {
    Write-Host "  Passed      : $($global:TestPassed)" -ForegroundColor Green
}
if ($global:TestFailed -gt 0) {
    Write-Host "  Failed      : $($global:TestFailed)" -ForegroundColor Red
}
Write-Host "  Finished    : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host ("  Elapsed     : {0:mm\:ss}" -f $suiteTimer.Elapsed)
Write-Host "  Log dir     : $(Get-LogDir)" -ForegroundColor DarkGray
Write-Host "============================================================" -ForegroundColor Cyan

if ($global:TestFailed -gt 0) {
    Write-Host "`n  --- FAILURES ($($global:TestFailed) of $totalChecks checks failed) ---" -ForegroundColor Red
    foreach ($f in $global:FailureMessages) {
        Write-Host $f -ForegroundColor Red
    }
    Write-Host ""
    Write-Host "  $($global:TestPassed) PASSED, $($global:TestFailed) FAILED" -ForegroundColor Red
}

# --- Copilot analysis via test-utils ---
# Finish-TestSession prints the per-command log report and invokes copilot
# if any CLI commands failed.
$cliCode = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI install stress test. Iterations: $Iterations. Seed: $Seed. Tag: $Tag."
if ($exitCode -eq 0 -and $cliCode -ne 0) { $exitCode = $cliCode }

exit $exitCode
