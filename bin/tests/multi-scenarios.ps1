#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Multi-scenario stress-test orchestrator for browser4-cli.

.DESCRIPTION
    Runs the scenario suite in a loop.  Each iteration launches every scenario
    as an isolated sub-process so a single crash cannot poison the runner.
    Results are tracked per-iteration and a summary is printed at the end.

    By default this script uses the globally-installed browser4-cli and does
    NOT build the Browser4 server — it expects a server to already be running
    (or auto-started by browser4-cli).  Use -BuildCli to build from the local
    Rust source tree, and -BuildServer to rebuild the server JAR via Maven.

.PARAMETER Iterations
    Number of full suite iterations (default: 60).

.PARAMETER Scenarios
    Paths to scenario scripts relative to the script directory.  Defaults to the
    four core scenarios.

.PARAMETER Release
    Build with `--release` (only meaningful with -BuildCli).

.PARAMETER SkipBuild
    Skip CLI build.  This is the default behaviour; accepted for bw-compat.

.PARAMETER SkipServerBuild
    Skip the Browser4 server (Maven) rebuild.  This is the default behaviour;
    accepted for bw-compat.

.PARAMETER BuildServer
    Rebuild the Browser4 server JAR via Maven before running scenarios.

.PARAMETER BuildCli
    Build the CLI binary from the local Rust source tree instead of using the
    globally-installed browser4-cli.

.PARAMETER UseGlobalCli
    Use the globally-installed browser4-cli.  This is the default behaviour;
    accepted for bw-compat.  Override with -BuildCli.

.EXAMPLE
    .\multi-scenarios.ps1 -Iterations 10

.EXAMPLE
    .\multi-scenarios.ps1 -BuildCli -BuildServer -Iterations 5

.EXAMPLE
    .\multi-scenarios.ps1 -UseGlobalCli -SkipServerBuild
#>

[CmdletBinding()]
param(
    [int] $Iterations = 60,
    [string[]] $Scenarios = @(
        'stress-session.ps1',
        'agent-run-page-visit.ps1',
        'agent-run-page-visit-interact.ps1',
        'swarm-agents.ps1'
    ),
    [switch] $Release,
    [switch] $SkipBuild,
    [switch] $SkipServerBuild,
    [switch] $BuildServer,
    [switch] $BuildCli,
    [switch] $UseGlobalCli,
    [switch] $Help,
    [string] $RuntimeBundleHome = ''
)

if ($Help) {
    Get-Help -Full $MyInvocation.MyCommand.Path
    exit 0
}

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
# Resolve repo root only when inside a repository checkout (two levels up from
# bin/tests/).  When this script is run standalone (e.g. downloaded from OSS),
# repo-dependent features like -BuildCli / -BuildServer are unavailable.
$RepoRoot = if (Test-Path (Join-Path $ScriptDir '..\..\pom.xml')) {
    Resolve-Path (Join-Path $ScriptDir '..\..')
} else {
    $null
}

# -------------------------------------------------------------------
# Load shared test utilities (for copilot analysis on failure)
# -------------------------------------------------------------------
Import-Module "$ScriptDir\test-utils.psm1" -Force
Start-TestSession -Name 'multi-scenarios' -LogBaseDir (Join-Path $ScriptDir 'logs')

$BinaryName = if ($IsWindows) { 'browser4-cli.exe' } else { 'browser4-cli' }
$Profile = if ($Release) { 'release' } else { 'debug' }

# ---- Resolve the CLI binary ----
# Default: use globally-installed browser4-cli (overridable via $env:BROWSER4_CLI_BIN).
# When -BuildCli is given, build from the local Rust source and use the resulting binary.

if ($BuildCli) {
    if (-not $RepoRoot) {
        throw '-BuildCli requires the source repository (no pom.xml found). Run without -BuildCli to use the globally-installed browser4-cli, or run this script from within a repository checkout.'
    }
    # Build from local source tree
    $CliProjectDir = Join-Path $RepoRoot 'cli\browser4-cli'
    if (-not (Test-Path (Join-Path $CliProjectDir 'Cargo.toml'))) {
        throw "CLI project not found at: $CliProjectDir"
    }
    $BinaryPath = Join-Path $CliProjectDir "target\$Profile\$BinaryName"
} else {
    # Use global CLI — resolve from PATH (or $env:BROWSER4_CLI_BIN override)
    if ($env:BROWSER4_CLI_BIN) {
        $BinaryPath = $env:BROWSER4_CLI_BIN
    } else {
        $globalCmd = Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
        if (-not $globalCmd) {
            $whichCmd = if ($IsWindows) { 'where.exe' } else { 'which' }
            $raw = & $whichCmd 'browser4-cli' 2>$null | Select-Object -First 1
            if ($raw) { $globalCmd = $raw.Trim() }
        }
        if (-not $globalCmd -or -not (Test-Path ($globalCmd.Source ?? $globalCmd))) {
            Write-Host 'browser4-cli not found on PATH — installing globally …' -ForegroundColor Yellow
            npm i -g browser4-cli
            if ($LASTEXITCODE -ne 0) { throw 'npm i -g browser4-cli failed' }
            browser4-cli install
            if ($LASTEXITCODE -ne 0) { throw 'browser4-cli install failed' }
            $globalCmd = Get-Command 'browser4-cli' -CommandType Application -ErrorAction Stop | Select-Object -First 1
        }
        $BinaryPath = if ($globalCmd -is [string]) { $globalCmd } else { $globalCmd.Source }
    }
    Write-Host "Using browser4-cli: $BinaryPath" -ForegroundColor DarkGray
}

$ScenarioTimeoutSeconds = 600   # per-scenario timeout (10 min — agent polling can take 5+ min)
$ServerLogTailLines = 1000      # lines to tail from pulsar.log on failure

# Auto-detect RuntimeBundleHome when not explicitly provided.
# Only scans the local repo build output; graceful when run outside a repo.
if (-not $RuntimeBundleHome -and $RepoRoot) {
    $bundleTargetDir = Join-Path $RepoRoot 'browser4-apps\browser4-bundle\target\runtime-bundle'
    if (Test-Path $bundleTargetDir) {
        $candidate = Get-ChildItem -Path $bundleTargetDir -Recurse -Directory -Filter 'logs' -ErrorAction SilentlyContinue `
            | Where-Object { Test-Path (Join-Path $_.FullName 'pulsar.log') } `
            | Select-Object -First 1
        if ($candidate) {
            $RuntimeBundleHome = Split-Path -Parent $candidate.FullName
        }
    }
}

# -------------------------------------------------------------------
# Helpers
# -------------------------------------------------------------------

<#
.SYNOPSIS
    Return the last $TailLines lines from the server-side pulsar.log.
    Returns $null when the log file cannot be found.
#>
function Get-ServerLogTail {
    param([string]$BundleHome, [int]$TailLines = 1000)
    if (-not $BundleHome) { return $null }
    $logPath = Join-Path $BundleHome 'logs\pulsar.log'
    if (-not (Test-Path $logPath)) { return $null }
    try {
        return Get-Content -Path $logPath -Tail $TailLines -ErrorAction Stop | Out-String
    } catch {
        return "!!! Could not read server log: $($_.Exception.Message)"
    }
}

<#
.SYNOPSIS
    Compute a fast fingerprint for a set of source directories.
    Uses file paths + LastWriteTime + Length so it completes in < 100 ms even
    on large trees; we don't need cryptographic strength, just change detection.
#>
function Get-SourceFingerprint {
    param([string[]] $Paths)
    $sb = [System.Text.StringBuilder]::new()
    foreach ($dir in $Paths) {
        if (-not (Test-Path $dir)) { continue }
        Get-ChildItem -Path $dir -Recurse -File -ErrorAction SilentlyContinue `
            | Where-Object { $_.DirectoryName -notmatch '(^|[\\/])target([\\/]|$)' } `
            | Sort-Object FullName `
            | ForEach-Object {
                $null = $sb.AppendLine("$($_.FullName)|$($_.LastWriteTimeUtc.Ticks)|$($_.Length)")
            }
    }
    # Use SHA256 for a compact, collision-resistant digest.
    $bytes = [Text.Encoding]::UTF8.GetBytes($sb.ToString())
    $hash = [Security.Cryptography.SHA256]::Create().ComputeHash($bytes)
    return [BitConverter]::ToString($hash) -replace '-', ''
}

<#
.SYNOPSIS
    Build the Browser4 server (bundle) JAR if sources are newer than the
    cached fingerprint.  Falls back to a full rebuild when the fingerprint file
    is missing or --ForceServerBuild is set.
#>
function Invoke-ServerBuildIfNeeded {
    $bundleModule = 'browser4-apps/browser4-bundle'
    $jarPath = Join-Path $RepoRoot "$bundleModule/target/Browser4Bundle.jar"
    $fingerprintFile = Join-Path $RepoRoot "$bundleModule/target/.source-fingerprint"

    # Directories whose sources feed into the bundle JAR.
    # We fingerprint the full source tree under these modules.
    $sourceDirs = @(
        "$RepoRoot/browser4-core",
        "$RepoRoot/browser4-rest",
        "$RepoRoot/browser4-agentic",
        "$RepoRoot/browser4-agent-tools",
        "$RepoRoot/browser4-boot",
        "$RepoRoot/browser4-apps/browser4-bundle",
        "$RepoRoot/browser4-dependencies"
    )

    $needBuild = $false

    if ($ForceServerBuild) {
        Write-Host '  --ForceServerBuild: skipping fingerprint check' -ForegroundColor DarkGray
        $needBuild = $true
    }
    elseif (-not (Test-Path $jarPath)) {
        Write-Host '  Server JAR missing — build required' -ForegroundColor Yellow
        $needBuild = $true
    }
    else {
        $currentFingerprint = Get-SourceFingerprint -Paths $sourceDirs
        $storedFingerprint = if (Test-Path $fingerprintFile) {
            (Get-Content $fingerprintFile -Raw).Trim()
        } else { '' }

        if ($currentFingerprint -ne $storedFingerprint) {
            Write-Host '  Sources changed — build required' -ForegroundColor Yellow
            $needBuild = $true
        } else {
            Write-Host '  Server fingerprint matches — skipping Maven build' -ForegroundColor Green
        }
    }

    if (-not $needBuild) { return }

    Write-Host "  Running Maven package (browser4-bundle) …" -ForegroundColor Yellow
    Push-Location $RepoRoot

    $mvnArgs = @(
        'package',
        '-Passet-bundle',
        '-pl', $bundleModule,
        '-am',
        '-DskipTests',
        '-q'
    )

    $sw = [Diagnostics.Stopwatch]::StartNew()
    & mvn @mvnArgs
    $sw.Stop()

    if ($LASTEXITCODE -ne 0) {
        Pop-Location
        throw "Maven build failed (exit=$LASTEXITCODE)"
    }
    Pop-Location

    # Persist the new fingerprint.
    $newFingerprint = Get-SourceFingerprint -Paths $sourceDirs
    $null = New-Item -Path (Split-Path $fingerprintFile) -ItemType Directory -Force -ErrorAction SilentlyContinue
    Set-Content -Path $fingerprintFile -Value $newFingerprint -NoNewline

    Write-Host ("  ✅ Server build complete  ({0:F1}s)" -f $sw.Elapsed.TotalSeconds) -ForegroundColor Green
}

# -------------------------------------------------------------------
# Build
# -------------------------------------------------------------------
$needBuild = $BuildCli -or $BuildServer

if ($needBuild) {
    Write-Host '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━' -ForegroundColor Cyan
    Write-Host '  Build' -ForegroundColor Cyan
    Write-Host '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━' -ForegroundColor Cyan

    # --- Server-side (Java) ---
    if ($BuildServer) {
        if (-not $RepoRoot) {
            throw '-BuildServer requires the source repository (no pom.xml found). Run without -BuildServer to use the globally-installed browser4-cli, or run this script from within a repository checkout.'
        }
        Write-Host "`n[Server] Checking Browser4 bundle …" -ForegroundColor Yellow
        Invoke-ServerBuildIfNeeded
    }

    # --- CLI (Rust) ---
    if ($BuildCli) {
        if (-not $RepoRoot) {
            throw '-BuildCli requires the source repository (no pom.xml found). Run without -BuildCli to use the globally-installed browser4-cli, or run this script from within a repository checkout.'
        }
        Push-Location $CliProjectDir
        Write-Host "`n[CLI] cargo clean …" -ForegroundColor Yellow
        cargo clean
        if ($LASTEXITCODE -ne 0) { throw 'cargo clean failed' }

        $buildArgs = @('build')
        if ($Release) { $buildArgs += '--release' }
        Write-Host "[CLI] cargo $($buildArgs -join ' ') …" -ForegroundColor Yellow
        & cargo $buildArgs
        if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' }
        Pop-Location
    }

    Write-Host "`n✅ Build complete`n" -ForegroundColor Green
}

if (-not (Test-Path $BinaryPath)) {
    throw "CLI binary not found: $BinaryPath"
}

Write-Host "Binary: $BinaryPath" -ForegroundColor DarkGray
Write-Host "Version: $(& $BinaryPath --version)" -ForegroundColor DarkGray

# Export so child scripts can use `$env:BROWSER4_CLI_BIN` as a faster
# alternative to the global CLI.
$env:BROWSER4_CLI_BIN = $BinaryPath

# -------------------------------------------------------------------
# Run
# -------------------------------------------------------------------
$SuiteStartedAt = Get-Date
$TotalPasses = 0
$TotalFailures = 0
$IterationTimings = [System.Collections.ArrayList]::new()

# Per-scenario log directory.
$LogDir = Join-Path $ScriptDir 'logs'
$null = New-Item -Path $LogDir -ItemType Directory -Force -ErrorAction SilentlyContinue

for ($iteration = 1; $iteration -le $Iterations; $iteration++) {
    Write-Host "`n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
    Write-Host "  Iteration $iteration / $Iterations" -ForegroundColor Cyan
    Write-Host "  Started: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

    $iterStarted = Get-Date
    $iterPasses = 0
    $iterFailures = 0

    foreach ($scenario in $Scenarios) {
        $scenarioPath = Join-Path $ScriptDir $scenario
        if (-not (Test-Path $scenarioPath)) {
            Write-Host "  ⚠ Skipping missing scenario: $scenario" -ForegroundColor Yellow
            continue
        }

        $sw = [Diagnostics.Stopwatch]::StartNew()
        Write-Host "  ▶ $scenario … " -NoNewline

        # Launch each scenario in its own powershell process so an unhandled
        # failure in one script does not take down the whole suite.
        # Use the same pwsh that is running this script (cross-platform safe).
        $pwshPath = if ($PSVersionTable.PSEdition -eq 'Core') {
            (Get-Process -Id $PID).Path
        } else {
            'powershell.exe'
        }
        # Use Start-Process with file redirection instead of .NET Process
        # with redirected pipes.  On Windows, ReadToEndAsync().Result blocks
        # when a grandchild process (Java server spawned by the scenario)
        # inherits the write end of the anonymous pipe via CreateProcess.
        # File redirection avoids this entirely: temp-file reads never block,
        # and WaitForExit is alertable (Ctrl+C works).
        $logName = "iter{0:D3}_{1}" -f $iteration, [IO.Path]::GetFileNameWithoutExtension($scenario)
        $logFile = Join-Path $LogDir "$logName.log"
        $tempDir = if ($env:TEMP) { $env:TEMP } elseif ($env:TMPDIR) { $env:TMPDIR } else { [System.IO.Path]::GetTempPath() }
        $tmpOut = Join-Path $tempDir "b4_multi_stdout_${pid}_$(Get-Random).txt"
        $tmpErr = Join-Path $tempDir "b4_multi_stderr_${pid}_$(Get-Random).txt"
        Remove-Item $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue

        $proc = Start-Process `
            -FilePath $pwshPath `
            -ArgumentList "-NoProfile -NonInteractive -File `"$scenarioPath`"" `
            -NoNewWindow `
            -PassThru `
            -RedirectStandardOutput $tmpOut `
            -RedirectStandardError $tmpErr

        # Wait with timeout, printing progress periodically.
        $pollIntervalMs = 10000   # log "still waiting" every 10 s
        $deadline = [DateTime]::UtcNow.AddSeconds($ScenarioTimeoutSeconds)
        $completed = $false
        while (-not $completed -and ([DateTime]::UtcNow -lt $deadline)) {
            $completed = $proc.WaitForExit($pollIntervalMs)
            if (-not $completed -and ([DateTime]::UtcNow -lt $deadline)) {
                $elapsed = [Math]::Floor($sw.Elapsed.TotalSeconds)
                Write-Host ("`r  ⏳ {0} — {1}s / {2}s … " -f $scenario, $elapsed, $ScenarioTimeoutSeconds) -NoNewline -ForegroundColor DarkGray
            }
        }
        # Clear the progress tick line so the next output starts fresh.
        Write-Host ''
        if (-not $completed) {
            Write-Host ("⏱  TIMEOUT ({0}s) — killing process tree" -f $ScenarioTimeoutSeconds) -ForegroundColor Red
            $proc.Kill($true)
            $proc.WaitForExit(5000) | Out-Null
            $sw.Stop()
            $stdout = if (Test-Path $tmpOut) { Get-Content -Path $tmpOut -Raw -ErrorAction SilentlyContinue } else { '<timeout — no output captured>' }
            $stderr = if (Test-Path $tmpErr) { Get-Content -Path $tmpErr -Raw -ErrorAction SilentlyContinue } else { '' }
            Remove-Item $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue
            $serverLog = Get-ServerLogTail -BundleHome $RuntimeBundleHome -TailLines $ServerLogTailLines
            @"
=== Scenario : $scenario
=== Iteration: $iteration
=== Started  : $($iterStarted.ToString('yyyy-MM-dd HH:mm:ss'))
=== Status   : TIMEOUT ($ScenarioTimeoutSeconds s)
=== Elapsed  : $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s
=== ExitCode : (killed)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
STDOUT:
$stdout
STDERR:
$stderr
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SERVER LOG (last $ServerLogTailLines lines – $RuntimeBundleHome\logs\pulsar.log):
$serverLog
"@ | Set-Content -Path $logFile
            # Print server log tail to console on failure.
            if ($serverLog) { Write-Host "`n  ── SERVER LOG TAIL ──" -ForegroundColor Red; Write-Host $serverLog -ForegroundColor DarkYellow }
            Write-Host "  📄 $logName.log" -ForegroundColor DarkGray
            $iterFailures++
            continue
        }
        $sw.Stop()
        $stdout = if (Test-Path $tmpOut) { Get-Content -Path $tmpOut -Raw -ErrorAction SilentlyContinue } else { '' }
        $stderr = if (Test-Path $tmpErr) { Get-Content -Path $tmpErr -Raw -ErrorAction SilentlyContinue } else { '' }
        Remove-Item $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue

        # Echo captured output to console (non-real-time, but captured in full).
        if ($stdout) { Write-Host $stdout }
        if ($stderr) { Write-Host $stderr -ForegroundColor DarkYellow }

        if ($proc.ExitCode -eq 0) {
            # Write log file (success – no server log needed).
            @"
=== Scenario : $scenario
=== Iteration: $iteration
=== Started  : $($iterStarted.ToString('yyyy-MM-dd HH:mm:ss'))
=== Elapsed  : $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s
=== ExitCode : $($proc.ExitCode)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
STDOUT:
$stdout
STDERR:
$stderr
"@ | Set-Content -Path $logFile
            Write-Host ("✅  {0:F1}s  📄 {1}.log" -f $sw.Elapsed.TotalSeconds, $logName) -ForegroundColor Green
            $iterPasses++
        } else {
            $serverLog = Get-ServerLogTail -BundleHome $RuntimeBundleHome -TailLines $ServerLogTailLines
            @"
=== Scenario : $scenario
=== Iteration: $iteration
=== Started  : $($iterStarted.ToString('yyyy-MM-dd HH:mm:ss'))
=== Elapsed  : $('{0:F1}' -f $sw.Elapsed.TotalSeconds)s
=== ExitCode : $($proc.ExitCode)
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
STDOUT:
$stdout
STDERR:
$stderr
━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
SERVER LOG (last $ServerLogTailLines lines – $RuntimeBundleHome\logs\pulsar.log):
$serverLog
"@ | Set-Content -Path $logFile
            if ($serverLog) { Write-Host "`n  ── SERVER LOG TAIL ──" -ForegroundColor Red; Write-Host $serverLog -ForegroundColor DarkYellow }
            Write-Host ("❌  exit=$($proc.ExitCode)  {0:F1}s  📄 {1}.log" -f $sw.Elapsed.TotalSeconds, $logName) -ForegroundColor Red
            $iterFailures++
        }
    }

    $iterElapsed = (Get-Date) - $iterStarted
    $null = $IterationTimings.Add([PSCustomObject]@{
        Iteration = $iteration
        Passes    = $iterPasses
        Failures  = $iterFailures
        Elapsed   = $iterElapsed
    })

    $TotalPasses += $iterPasses
    $TotalFailures += $iterFailures

    Write-Host "  ──────────────────────────────────────────────" -ForegroundColor DarkGray
    $elapsedStr = $iterElapsed.ToString('mm\:ss')
    $color = if ($iterFailures -eq 0) { 'Green' } else { 'Red' }
    Write-Host "  Passes: $iterPasses  Failures: $iterFailures  Elapsed: $elapsedStr" -ForegroundColor $color
}

# -------------------------------------------------------------------
# Summary
# -------------------------------------------------------------------
$SuiteElapsed = (Get-Date) - $SuiteStartedAt

Write-Host "`n`n"
Write-Host '╔══════════════════════════════════════════════════╗' -ForegroundColor Cyan
Write-Host '║              MULTI-SCENARIO RESULTS              ║' -ForegroundColor Cyan
Write-Host '╚══════════════════════════════════════════════════╝' -ForegroundColor Cyan
Write-Host "  Iterations : $Iterations"
Write-Host "  Scenarios  : $($Scenarios.Count) ($($Scenarios -join ', '))"
Write-Host "  Total runs : $($TotalPasses + $TotalFailures)"
Write-Host "  Passes     : $TotalPasses" -ForegroundColor $(if ($TotalPasses -gt 0) { 'Green' } else { 'Red' })
Write-Host "  Failures   : $TotalFailures" -ForegroundColor $(if ($TotalFailures -eq 0) { 'Green' } else { 'Red' })
Write-Host ("  Started    : {0:yyyy-MM-dd HH:mm:ss}" -f $SuiteStartedAt)
Write-Host ("  Finished   : {0:yyyy-MM-dd HH:mm:ss}" -f (Get-Date))
Write-Host ("  Elapsed    : {0:hh\:mm\:ss}" -f $SuiteElapsed)

$failedIters = $IterationTimings | Where-Object { $_.Failures -gt 0 }
if ($failedIters) {
    Write-Host "`n  Failed iterations:" -ForegroundColor Red
    foreach ($f in $failedIters) {
        Write-Host ("    Iter $($f.Iteration): $($f.Failures) failures in {0:mm\:ss}" -f $f.Elapsed) -ForegroundColor Red
    }
}

# --- Collect failure log paths and run copilot analysis ---
if ($TotalFailures -gt 0) {
    Write-Host "`n  Logs directory: $LogDir" -ForegroundColor DarkGray
    Write-Host "  -- FAILURE LOGS --" -ForegroundColor Red
    $failureLogs = @(Get-ChildItem -Path $LogDir -Filter "*.log" -ErrorAction SilentlyContinue | ForEach-Object { $_.FullName })
    foreach ($log in $failureLogs) {
        Write-Host "    📄 $log" -ForegroundColor DarkGray
    }
    $analysisPrompt = "Browser4 CLI multi-scenario stress test failures. $TotalFailures failures across $Iterations iterations. Scenarios: $($Scenarios -join ', ')."
    $analysisResult = Invoke-CopilotAnalysis -LogPaths $failureLogs -ExtraPrompt $analysisPrompt
}

# Clean up the test-utils session
$null = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI multi-scenario stress test."

exit $(if ($TotalFailures -eq 0) { 0 } else { 1 })
