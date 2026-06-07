#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Multi-scenario stress-test orchestrator for browser4-cli.

.DESCRIPTION
    Cleans and rebuilds the CLI binary (and optionally the Browser4 server),
    then runs the scenario suite in a loop.  Each iteration launches every
    scenario as an isolated sub-process so a single crash cannot poison the
    runner.  Results are tracked per-iteration and a summary is printed at
    the end.

    The server-side rebuild uses a fast source-tree fingerprint so Maven is
    only invoked when Java/Kotlin sources actually changed.

.PARAMETER Iterations
    Number of full suite iterations (default: 60).

.PARAMETER Scenarios
    Paths to scenario scripts relative to the script directory.  Defaults to the
    four core scenarios.

.PARAMETER Release
    Build with `--release`.  The default is a debug build (faster iteration).

.PARAMETER SkipBuild
    Skip CLI build.  Use when the binary is already fresh.

.PARAMETER SkipServerBuild
    Skip the Browser4 server (Maven) rebuild entirely.

.PARAMETER ForceServerBuild
    Always run Maven, even when the fingerprint says the server is up to date.

.EXAMPLE
    .\multi-scenarios.ps1 -Iterations 10

.EXAMPLE
    .\multi-scenarios.ps1 -Release -ForceServerBuild
#>

[CmdletBinding()]
param(
    [int] $Iterations = 60,
    [string[]] $Scenarios = @(
        'session-stress.ps1',
        'agent-run-page-visit.ps1',
        'agent-run-page-visit-interact.ps1',
        'swarm-agents.ps1'
    ),
    [switch] $Release,
    [switch] $SkipBuild,
    [switch] $SkipServerBuild,
    [switch] $ForceServerBuild,
    [string] $RuntimeBundleHome = ''
)

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectDir = Resolve-Path "$ScriptDir\..\.."

# Resolve the repo root (where pom.xml lives) by walking up from the CLI crate.
$RepoRoot = $ProjectDir
while ($RepoRoot -and -not (Test-Path (Join-Path $RepoRoot 'pom.xml'))) {
    $RepoRoot = Split-Path -Parent $RepoRoot
}
if (-not $RepoRoot) { throw 'Cannot find repo root (no pom.xml found up the tree)' }

$BinaryName = if ($IsWindows) { 'browser4-cli.exe' } else { 'browser4-cli' }
$Profile = if ($Release) { 'release' } else { 'debug' }
$BinaryPath = "$ProjectDir\target\$Profile\$BinaryName"
$ScenarioTimeoutSeconds = 300   # per-scenario timeout (5 min)
$ServerLogTailLines = 1000      # lines to tail from pulsar.log on failure

# Auto-detect RuntimeBundleHome when not explicitly provided.
if (-not $RuntimeBundleHome) {
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
if (-not $SkipBuild) {
    Write-Host '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━' -ForegroundColor Cyan
    Write-Host '  Build' -ForegroundColor Cyan
    Write-Host '━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━' -ForegroundColor Cyan

    # --- Server-side (Java) ---
    if (-not $SkipServerBuild) {
        Write-Host "`n[Server] Checking Browser4 bundle …" -ForegroundColor Yellow
        Invoke-ServerBuildIfNeeded
    }

    # --- CLI (Rust) ---
    Push-Location $ProjectDir
    Write-Host "`n[CLI] cargo clean …" -ForegroundColor Yellow
    cargo clean
    if ($LASTEXITCODE -ne 0) { throw 'cargo clean failed' }

    $buildArgs = @('build')
    if ($Release) { $buildArgs += '--release' }
    Write-Host "[CLI] cargo $($buildArgs -join ' ') …" -ForegroundColor Yellow
    & cargo $buildArgs
    if ($LASTEXITCODE -ne 0) { throw 'cargo build failed' }
    Pop-Location

    Write-Host "`n✅ Build complete`n" -ForegroundColor Green
}

if (-not (Test-Path $BinaryPath)) {
    throw "CLI binary not found: $BinaryPath"
}

Write-Host "Binary: $BinaryPath" -ForegroundColor DarkGray
Write-Host "Version: $(& $BinaryPath --version)" -ForegroundColor DarkGray

# Export so child scripts can use `$env:BROWSER4_CLI_BIN` as a faster
# alternative to `cargo run`.
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
        # Launch via .NET Process class: stdin pipe is closed immediately so
        # child reads return EOF.  stdout/stderr are captured for the log file.
        $logName = "iter{0:D3}_{1}" -f $iteration, [IO.Path]::GetFileNameWithoutExtension($scenario)
        $logFile = Join-Path $LogDir "$logName.log"
        $psi = [Diagnostics.ProcessStartInfo]@{
            FileName               = $pwshPath
            Arguments              = "-NoProfile -NonInteractive -File `"$scenarioPath`""
            UseShellExecute        = $false
            CreateNoWindow         = $true
            RedirectStandardInput  = $true
            RedirectStandardOutput = $true
            RedirectStandardError  = $true
        }
        $proc = [Diagnostics.Process]::Start($psi)
        $proc.StandardInput.Close()

        # Read streams asynchronously while the process runs.
        $stdoutTask = $proc.StandardOutput.ReadToEndAsync()
        $stderrTask = $proc.StandardError.ReadToEndAsync()

        # Wait with timeout.
        if (-not $proc.WaitForExit($ScenarioTimeoutSeconds * 1000)) {
            Write-Host ("⏱  TIMEOUT ({0}s) — killing process tree" -f $ScenarioTimeoutSeconds) -ForegroundColor Red
            $proc.Kill($true)
            $proc.WaitForExit(5000) | Out-Null
            $sw.Stop()
            try { $stdout = $stdoutTask.Result } catch { $stdout = '<timeout — no output captured>' }
            try { $stderr = $stderrTask.Result } catch { $stderr = '' }
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
        $stdout = $stdoutTask.Result
        $stderr = $stderrTask.Result

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

exit $(if ($TotalFailures -eq 0) { 0 } else { 1 })
