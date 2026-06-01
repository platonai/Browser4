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
        'sessions.ps1',
        'agent-run-page-visit.ps1',
        'agent-run-page-visit-interact.ps1',
        'swarm-agents.ps1'
    ),
    [switch] $Release,
    [switch] $SkipBuild,
    [switch] $SkipServerBuild,
    [switch] $ForceServerBuild
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

# -------------------------------------------------------------------
# Helpers
# -------------------------------------------------------------------

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
        '-Dmaven.javadoc.skip=true',
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

    Write-Host "  ✅ Server build complete  ($($sw.Elapsed.TotalSeconds.ToString('F1'))s)" -ForegroundColor Green
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
        $proc = Start-Process -FilePath $pwshPath `
            -ArgumentList '-NoProfile', '-NonInteractive', '-File', $scenarioPath `
            -PassThru -Wait -NoNewWindow

        $sw.Stop()

        if ($proc.ExitCode -eq 0) {
            Write-Host "✅  $($sw.Elapsed.TotalSeconds.ToString('F1'))s" -ForegroundColor Green
            $iterPasses++
        } else {
            Write-Host "❌  exit=$($proc.ExitCode)  $($sw.Elapsed.TotalSeconds.ToString('F1'))s" -ForegroundColor Red
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
    Write-Host "  Passes: $iterPasses  Failures: $iterFailures  Elapsed: $($iterElapsed.ToString('mm\:ss'))" -ForegroundColor $(if ($iterFailures -eq 0) { 'Green' } else { 'Red' })
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
Write-Host "  Started    : $($SuiteStartedAt.ToString('yyyy-MM-dd HH:mm:ss'))"
Write-Host "  Finished   : $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')"
Write-Host "  Elapsed    : $($SuiteElapsed.ToString('hh\:mm\:ss'))"

$failedIters = $IterationTimings | Where-Object { $_.Failures -gt 0 }
if ($failedIters) {
    Write-Host "`n  Failed iterations:" -ForegroundColor Red
    foreach ($f in $failedIters) {
        Write-Host "    Iter $($f.Iteration): $($f.Failures) failures in $($f.Elapsed.ToString('mm\:ss'))" -ForegroundColor Red
    }
}

exit $(if ($TotalFailures -eq 0) { 0 } else { 1 })
