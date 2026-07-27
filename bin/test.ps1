#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$DryRun,
    [switch]$Show,
    [switch]$NoSession,
    [string]$SessionPath = '',
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ScriptArgs
)

$script:DryRun = $DryRun
$script:Show = $Show
$script:NoSession = $NoSession
$script:SessionPath = $SessionPath

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = (git rev-parse --show-toplevel 2>$null)

if ([string]::IsNullOrWhiteSpace($repoRoot)) {
    $repoRoot = $scriptDir
    while (-not (Test-Path (Join-Path $repoRoot 'VERSION'))) {
        $parent = Split-Path -Parent $repoRoot
        if ($parent -eq $repoRoot) {
            break
        }

        $repoRoot = $parent
    }
}

if (-not (Test-Path (Join-Path $repoRoot 'VERSION'))) {
    Write-Error "Could not locate the repository root from $scriptDir"
    exit 1
}

Set-Location $repoRoot

$mvnwScript = if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    Join-Path $repoRoot 'mvnw.cmd'
} else {
    Join-Path $repoRoot 'mvnw'
}

# ═══════════════════════════════════════════════════════════════════
# Internal helper functions
# ═══════════════════════════════════════════════════════════════════

function Write-Rule {
    <#
    .SYNOPSIS
        Print a horizontal rule of '=' characters.
    #>
    param([int]$Width = 46)
    Write-Host ('=' * $Width)
}

function Write-CommandBanner {
    <#
    .SYNOPSIS
        Print a standardized banner block for test command status.
    .PARAMETER Label
        Primary message line.
    .PARAMETER Subtitle
        Optional detail line (e.g. the full command being run).
    .PARAMETER Icon
        Optional status icon (e.g. '✅', '❌', '[SHOW]').
    #>
    param(
        [string]$Label,
        [string]$Subtitle = '',
        [string]$Icon = ''
    )
    Write-Host ''
    Write-Rule
    if ($Icon) { Write-Host "$Icon $Label" } else { Write-Host $Label }
    if ($Subtitle) { Write-Host $Subtitle }
    Write-Rule
}

function Invoke-CommandAndReport {
    <#
    .SYNOPSIS
        Execute a command (as a scriptblock), push/pop a working
        directory if needed, and print success/failure banners.

    .PARAMETER ScriptBlock
        The command to execute.
    .PARAMETER Label
        Human-readable label for banner messages.
    .PARAMETER PreExecPath
        Optional directory to Push-Location into before execution.
        Pop-Location is guaranteed via finally.
    .PARAMETER NoExit
        If set, return the exit code instead of exiting on failure.
    #>
    param(
        [Parameter(Mandatory)]
        [scriptblock]$ScriptBlock,
        [Parameter(Mandatory)]
        [string]$Label,
        [string]$PreExecPath = '',
        [switch]$NoExit
    )
    try {
        if ($PreExecPath) { Push-Location $PreExecPath }
        $global:LASTEXITCODE = 0
        if ($NoExit) {
            & $ScriptBlock *>&1 | Out-Host
        } else {
            & $ScriptBlock
        }
        $exitCode = $LASTEXITCODE
    }
    catch {
        Write-Error "Failed to execute $Label`: $_"
        exit 1
    }
    finally {
        if ($PreExecPath) { Pop-Location }
    }

    if ($exitCode -ne 0) {
        Write-CommandBanner -Label "$Label failed with exit code $exitCode" -Icon '❌'
        if (-not $NoExit) { exit $exitCode }
        return $exitCode
    }

    Write-CommandBanner -Label "$Label completed successfully" -Icon '✅'
    return $exitCode
}

# ═══════════════════════════════════════════════════════════════════
# Usage
# ═══════════════════════════════════════════════════════════════════

function Print-Usage {
    param([int]$ExitCode = 1)
    Write-Host "Usage: test.ps1 [-DryRun] [-Show] [-NoSession] [-SessionPath <path>] [test-types...] [additional-args...]"
    Write-Host ""
    Write-Host "Options:"
    Write-Host "  -DryRun      Compile only (test-compile), do not run tests"
    Write-Host "  -Show        Print the final command, do not execute anything"
    Write-Host "  -NoSession     Skip persisting test results to .test-sessions/<session-id>/test-session.json"
    Write-Host "  -SessionPath   Custom path for the test-session JSON file"
    Write-Host "               (default: <repo-root>/.test-sessions/<timestamp>/test-session.json)"
    Write-Host ""
    Write-Host "Test Types:"
    Write-Host "  fast        Run fast unit tests only"
    Write-Host "  it          Run integration tests"
    Write-Host "  e2e         Run end-to-end tests"
    Write-Host "  cli         Run Rust Browser4 CLI tests from cli\browser4-cli"
    Write-Host "  mock-site  Launch mock site from browser4-tests\browser4-rest-tests"
    Write-Host "              (aliases: server, mocksite, mocksiteboot)"
    Write-Host "  rest        Run REST module tests"
    Write-Host "  skills      Run skills-focused agentic tests"
    Write-Host "  mcp         Run MCP-focused agentic tests"
    Write-Host "  ps          Run all PowerShell *.tests.ps1 files in the project"
    Write-Host "  rws         Run real-world scenario tests (requires a mode)"
    Write-Host "              sc, scenarios <names...>  run named tasks via run-tests.ps1"
    Write-Host "              dir, directory <path>     run all .md tasks in a directory"
    Write-Host "              task <file>               run a single task via run-task.ps1"
    Write-Host "  session     List or view persisted test sessions (list, view)"
    Write-Host "              list --all | --count N   Paginate session listing (default: 15)"
    Write-Host ""
    Write-Host "  RWS options (accepted after the mode):"
    Write-Host "    --production                Use installed browser4-cli instead of cargo run"
    Write-Host "    --fail-fast                 Stop after the first failing scenario"
    Write-Host "    --list                      List discovered scenarios, don't run"
    Write-Host "    --silent                    Suppress agent output"
    Write-Host "    --skip-version-check        Skip browser4-cli version check"
    Write-Host "    --timeout <minutes>         Kill each scenario task after N minutes (default: no timeout)"
    Write-Host "    --agent <name>              Use a specific agent CLI (claude, kimi, or opencode)"
    Write-Host "  resume      Resume from the last failed module (-rf)"
    Write-Host "  main    Run all Browser4 main tests (fast, rest, it, e2e)"
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  test.ps1 fast                       # Run fast unit tests"
    Write-Host "  test.ps1 -NoSession fast              # Run fast tests without persisting session"
    Write-Host "  test.ps1 -SessionPath out/session.json ps  # Write session to a custom path"
    Write-Host "  test.ps1 -DryRun fast               # Show the Maven command for fast tests"
    Write-Host "  test.ps1 -DryRun it -pl browser4-core  # Show the Maven command with extra args"
    Write-Host "  test.ps1 it                         # Run integration tests"
    Write-Host "  test.ps1 e2e                        # Run end-to-end tests"
    Write-Host "  test.ps1 cli                        # Run CLI tests (cargo test --test e2e -- --nocapture)"
    Write-Host "  test.ps1 cli --help                 # Run CLI tests with extra cargo test args"
    Write-Host "  test.ps1 mock-site -Dmock.site.port=18080"
    Write-Host "  test.ps1 mock-site --force              # Auto-kill process on port 18080"
    Write-Host "  test.ps1 skills                     # Run skills-focused agentic tests"
    Write-Host "  test.ps1 mcp                        # Run MCP-focused agentic tests"
    Write-Host "  test.ps1 ps                         # Run all PowerShell *.tests.ps1 files"
    Write-Host "  test.ps1 ps -Quiet                  # Run PowerShell tests with -Quiet flag"
    Write-Host "  test.ps1 resume                     # Resume from the last failed module"
    Write-Host "  test.ps1 rws                        # Show RWS help"
    Write-Host "  test.ps1 rws sc amazon              # Run a specific scenario task"
    Write-Host "  test.ps1 rws sc amazon hn           # Run multiple scenarios"
    Write-Host "  test.ps1 rws scenarios --list       # List available scenarios"
    Write-Host "  test.ps1 rws dir tasks/real-world/generic  # Run all .md tasks in a directory"
    Write-Host "  test.ps1 rws sc amazon --timeout 30 # Run with 30-minute per-task timeout"
    Write-Host "  test.ps1 rws sc amazon --production # Run against installed CLI"
    Write-Host "  test.ps1 rws task tasks/real-world/generic/amazon.md  # Run a single task file"
    Write-Host "  test.ps1 main                       # Run all Browser4 main tests"
    Write-Host "  test.ps1 session list               # List all past test sessions"
    Write-Host "  test.ps1 session view 20260724T1917 # View a specific session (prefix match)"
    Write-Host '  test.ps1 it -pl browser4-core       # Pass additional Maven args through'
    exit $ExitCode
}

function Exit-UnknownTestType([string]$testType) {
    Write-Error "Unknown test type '$testType'. Valid test types: fast, it, e2e, cli, browser4-cli, main, mock-site, server, rest, skills, mcp, ps, rws, resume, session."
    exit 1
}

# ═══════════════════════════════════════════════════════════════════
# Execution functions
# ═══════════════════════════════════════════════════════════════════

function Invoke-MavenTests([string[]]$testTypes, [string[]]$additionalMvnArgs) {
    if (-not (Test-Path $mvnwScript)) {
        Write-Error "Maven wrapper not found at $mvnwScript"
        exit 1
    }

    Write-CommandBanner -Label "Running Maven tests: $($testTypes -join ', ')"

    $goal = if ($script:DryRun -and -not $script:Show) { 'test-compile' } else { 'test' }
    $mvnTestArgs = @($goal)

    $hasFast = $testTypes -contains 'fast'
    $hasIT = $testTypes -contains 'it'
    $hasE2E = $testTypes -contains 'e2e'
    $hasRest = $testTypes -contains 'rest'
    $hasSkills = $testTypes -contains 'skills'
    $hasMcp = $testTypes -contains 'mcp'

    if ($hasIT) { $mvnTestArgs += '-DrunITs=true' }
    if ($hasE2E) { $mvnTestArgs += '-DrunE2ETests=true' }
    if ($hasRest) { $mvnTestArgs += '-DrunRestTests=true' }

    $modules = @()
    if ($hasSkills -or $hasMcp) {
        $modules += 'browser4-agentic'

        if (-not ($hasFast -or $hasIT -or $hasE2E -or $hasRest)) {
            $patterns = @()
            if ($hasSkills) { $patterns += '*Skill*' }
            if ($hasMcp) { $patterns += '*MCP*' }

            if ($patterns.Count -gt 0) {
                $mvnTestArgs += "-Dtest=$($patterns -join ',')"
                $mvnTestArgs += '-Dsurefire.failIfNoSpecifiedTests=false'
            }
        }
    }

    if ($modules.Count -gt 0) {
        $mvnTestArgs += '-pl'
        $mvnTestArgs += ($modules -join ',')
        $mvnTestArgs += '-am'
    }

    $mvnTestArgs += $additionalMvnArgs

    if ($script:Show) {
        Write-CommandBanner -Label '[SHOW] Would execute:' -Subtitle "  $mvnwScript $($mvnTestArgs -join ' ')"
        return
    }

    if ($script:DryRun) {
        Write-CommandBanner -Label '[DRY RUN] Executing:' -Subtitle "  $mvnwScript $($mvnTestArgs -join ' ')"
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $exitCode = Invoke-CommandAndReport -ScriptBlock { & $mvnwScript @mvnTestArgs } `
        -Label "Maven tests: $($testTypes -join ', ')" -NoExit
    $sw.Stop()

    # ── Persist session ──────────────────────────────────────────────────
    if ($script:SessionAvailable) {
        Update-TestSessionSystem -RepoRoot $repoRoot -SessionPath $script:SessionPath
        $status = if ($exitCode -eq 0) { 'pass' } else { 'fail' }
        $dur = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        $logDir = $script:TestLogDir
        foreach ($type in $testTypes) {
            Update-TestSessionResult -RepoRoot $repoRoot -TestKey "maven:$type" `
                -Status $status -ExitCode $exitCode -DurationSec $dur `
                -LogDir $logDir `
                -SessionPath $script:SessionPath
        }
    }

    if ($exitCode -ne 0) { exit $exitCode }
}

function Invoke-Browser4CliTests([string[]]$additionalArgs) {
    $browser4CliDir = Join-Path $repoRoot 'cli' 'browser4-cli'

    Write-CommandBanner -Label 'Running Browser4 CLI tests...'

    if (-not (Test-Path $browser4CliDir)) {
        Write-Error "Browser4 CLI directory not found at $browser4CliDir"
        exit 1
    }

    $cargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
    if (-not $cargoCmd) {
        Write-Error "cargo is not installed or not in PATH"
        exit 1
    }

    $cargoTomlPath = Join-Path $browser4CliDir 'Cargo.toml'
    if (-not (Test-Path $cargoTomlPath)) {
        Write-Error "Cargo.toml not found at $cargoTomlPath"
        exit 1
    }

    if ($script:Show) {
        $cargoArgs = @('test', '--test', 'e2e', '--color', 'always', '--', '--nocapture') + $additionalArgs
        Write-CommandBanner -Label '[SHOW] Would execute in browser4-cli:' -Subtitle "  cargo $($cargoArgs -join ' ')"
        return
    }

    if ($script:DryRun) {
        $cargoArgs = @('test', '--test', 'e2e', '--color', 'always', '--no-run') + $additionalArgs
    } else {
        $cargoArgs = @('test', '--test', 'e2e', '--color', 'always', '--', '--nocapture') + $additionalArgs
    }

    if ($script:DryRun) {
        Write-CommandBanner -Label '[DRY RUN] Executing in browser4-cli:' -Subtitle "  cargo $($cargoArgs -join ' ')"
    }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    Push-Location $browser4CliDir
    try {
        Write-Host ''
        Write-Host '── Rust test output (println! etc.) ──' -ForegroundColor DarkCyan
        $global:LASTEXITCODE = 0
        & cargo @cargoArgs
        $exitCode = $LASTEXITCODE
        Write-Host '── End Rust output ──' -ForegroundColor DarkCyan
    } catch {
        Write-Error "Failed to execute Browser4 CLI tests: $_"
        exit 1
    } finally {
        Pop-Location
    }
    $sw.Stop()

    if ($exitCode -ne 0) {
        Write-CommandBanner -Label "Browser4 CLI tests failed with exit code $exitCode" -Icon '❌'
    } else {
        Write-CommandBanner -Label 'Browser4 CLI tests completed successfully' -Icon '✅'
    }

    # ── Persist session ──────────────────────────────────────────────────
    if ($script:SessionAvailable) {
        Update-TestSessionSystem -RepoRoot $repoRoot -SessionPath $script:SessionPath
        $status = if ($exitCode -eq 0) { 'pass' } else { 'fail' }
        $dur = [math]::Round($sw.Elapsed.TotalSeconds, 1)

        # Attach the structured Rust test report if cargo wrote one.
        # The E2E harness writes test-report.json next to last-failed-scenarios.json
        # in the e2e temp directory (%TEMP%/browser4/browser4-cli/e2e/).
        # Still check the cross-platform temp path since the Rust side writes there.
        $e2eTemp = if ($IsWindows -or $env:OS -eq 'Windows_NT') {
            Join-Path $env:TEMP 'browser4' 'browser4-cli' 'e2e'
        } else {
            Join-Path ([System.IO.Path]::GetTempPath()) 'browser4' 'browser4-cli' 'e2e'
        }
        $reportPath = Join-Path $e2eTemp 'test-report.json'
        $failureReport = if (Test-Path -LiteralPath $reportPath) { $reportPath } else { '' }

        Update-TestSessionResult -RepoRoot $repoRoot -TestKey 'cli' `
            -Status $status -ExitCode $exitCode -DurationSec $dur `
            -LogDir $script:TestLogDir `
            -FailureReport $failureReport `
            -SessionPath $script:SessionPath
    }

    if ($exitCode -ne 0) { exit $exitCode }
}

function Invoke-MockSiteBoot([string[]]$additionalArgs) {
    $mockSiteModuleDir = Join-Path $repoRoot 'browser4-tests' 'browser4-rest-tests'
    $passThroughArgs = @()
    $mockSiteJvmArgs = @()
    if (-not (Test-Path $mvnwScript)) {
        Write-Error "Maven wrapper not found at $mvnwScript"
        exit 1
    }
    if (-not (Test-Path $mockSiteModuleDir)) {
        Write-Error "Mock site module not found at $mockSiteModuleDir"
        exit 1
    }

    Write-CommandBanner -Label 'Launching MockSiteBoot...'

    foreach ($arg in $additionalArgs) {
        if ($arg -like '-Dmock.site.*') {
            $mockSiteJvmArgs += $arg
        }
        elseif ($arg -eq '--force') {
            $script:_MockSiteForceKill = $true
        }
        else {
            $passThroughArgs += $arg
        }
    }

    # ── Pre-check port availability ──────────────────────────────────────
    $mockSitePort = 18080
    foreach ($jvmArg in $mockSiteJvmArgs) {
        if ($jvmArg -match '^-Dmock\.site\.port[= ](\d+)$') {
            $mockSitePort = [int]$Matches[1]
            break
        }
    }

    $portInUse = $false
    $occupyingPid = $null

    if ($IsWindows -or $env:OS -eq 'Windows_NT') {
        $conn = Get-NetTCPConnection -LocalPort $mockSitePort -ErrorAction SilentlyContinue | Select-Object -First 1
        if ($conn) {
            $portInUse = $true
            $occupyingPid = $conn.OwningProcess
        }
    } else {
        # Linux/macOS: use ss (modern) or netstat (fallback)
        $ss = Get-Command ss -ErrorAction SilentlyContinue
        if ($ss) {
            $line = & ss -tlnp "sport = :$mockSitePort" 2>$null | Select-Object -Last 1
            if ($line -match 'pid=(\d+)') {
                $portInUse = $true
                $occupyingPid = $Matches[1]
            }
        } else {
            $line = & netstat -tlnp 2>$null | Select-String ":$mockSitePort "
            if ($line -match '(\d+)/') {
                $portInUse = $true
                $occupyingPid = $Matches[1]
            }
        }
    }

    if ($portInUse) {
        $killHint = if ($IsWindows -or $env:OS -eq 'Windows_NT') {
            "taskkill //PID $occupyingPid //F"
        } else {
            "kill $occupyingPid"
        }

        if ($script:_MockSiteForceKill) {
            Write-Host "Port $mockSitePort is in use by PID $occupyingPid. --force specified, killing..." -ForegroundColor Yellow
            if ($IsWindows -or $env:OS -eq 'Windows_NT') {
                & taskkill //PID $occupyingPid //F 2>$null
            } else {
                & kill $occupyingPid 2>$null
            }
            Start-Sleep -Seconds 1
            Write-Host "Process killed. Proceeding with launch." -ForegroundColor Green
        } else {
            Write-Error @"

Port $mockSitePort is already in use by PID $occupyingPid.

To free the port, kill the process:
  $killHint

Or re-run with --force to auto-kill:
  test.ps1 mock-site --force

To use a different port:
  test.ps1 mock-site -Dmock.site.port=18081
"@
            exit 1
        }
    }

    $mvnArgs = @(
        '-DskipTests'
    ) + $passThroughArgs

    if ($mockSiteJvmArgs.Count -gt 0 -and -not ($passThroughArgs | Where-Object { $_ -like '-Dspring-boot.run.jvmArguments=*' })) {
        $mvnArgs += "-Dspring-boot.run.jvmArguments=$($mockSiteJvmArgs -join ' ')"
    }

    if ($script:Show) {
        $mvnArgs += @('package', 'spring-boot:run')
        Write-CommandBanner -Label '[SHOW] Would execute in rest-tests:' -Subtitle "  $mvnwScript $($mvnArgs -join ' ')"
        return
    }
    elseif ($script:DryRun) {
        $mvnArgs += @('compile')
    }
    else {
        $mvnArgs += @(
            'package',
            'spring-boot:run'
        )
    }

    if ($script:DryRun) {
        Write-CommandBanner -Label '[DRY RUN] Executing in rest-tests:' -Subtitle "  $mvnwScript $($mvnArgs -join ' ')"
    }

    Invoke-CommandAndReport -ScriptBlock { & $mvnwScript @mvnArgs } -Label 'MockSiteBoot' -PreExecPath $mockSiteModuleDir
}

function Invoke-RealWorldScenarioTests([string[]]$additionalArgs) {
    $rwsScriptsDir = Join-Path $repoRoot 'browser4-tests' 'real-world-scenarios' 'scripts'
    $scenarioRunner = Join-Path $rwsScriptsDir 'run-tests.ps1'
    $taskRunner = Join-Path $rwsScriptsDir 'run-task.ps1'

    # ── Determine mode from additional args ──────────────────────────────────
    $mode = ''
    $modeLabel = ''
    $taskFile = $null
    $taskDir = $null
    $setProduction = $false
    $passThroughArgs = @()

    $i = 0
    while ($i -lt $additionalArgs.Count) {
        $arg = $additionalArgs[$i]
        if ($arg -in 'scenarios', 'sc', '--scenarios', '-sc') {
            $mode = 'scenarios'
            $i++
            # Collect required scenario names (bare words until a --flag or end)
            $scenarioNames = @()
            while ($i -lt $additionalArgs.Count -and -not $additionalArgs[$i].StartsWith('-')) {
                $scenarioNames += $additionalArgs[$i]
                $i++
            }
            if ($scenarioNames.Count -eq 0) {
                # --list is the only flag that makes sense without names
                if ($i -lt $additionalArgs.Count -and $additionalArgs[$i] -in '--list', '-List') {
                    $passThroughArgs += '-List'
                    $i++
                    $modeLabel = 'real-world scenarios (list)'
                }
                else {
                    Write-Host ''
                    Write-Host 'sc requires at least one scenario name.' -ForegroundColor Yellow
                    Write-Host ''
                    Write-Host 'Usage:  test.ps1 rws sc <names...> [options]'
                    Write-Host ''
                    Write-Host 'Discover available scenarios:'
                    Write-Host '  test.ps1 rws sc --list'
                    Write-Host ''
                    Write-Host 'Examples:'
                    Write-Host '  test.ps1 rws sc amazon'
                    Write-Host '  test.ps1 rws sc amazon hn search-summary'
                    Write-Host ''
                    exit 1
                }
            }
            $modeLabel = "real-world scenarios: $($scenarioNames -join ', ')"
            $passThroughArgs += $scenarioNames
        }
        elseif ($arg -in 'dir', 'directory', '--dir', '--directory' -and ($i + 1) -lt $additionalArgs.Count) {
            $mode = 'dir'
            $taskDir = $additionalArgs[$i + 1]
            $modeLabel = "real-world scenario dir: $taskDir"
            $i += 2
        }
        elseif ($arg -in 'task', '--task' -and ($i + 1) -lt $additionalArgs.Count) {
            $mode = 'task'
            $taskFile = $additionalArgs[$i + 1]
            $modeLabel = "real-world scenario: $taskFile"
            $i += 2
        }
        elseif ($arg -in '--production', '-Production') {
            $setProduction = $true
            $i++
        }
        elseif ($arg -in '--fail-fast', '-FailFast') {
            $passThroughArgs += '-FailFast'
            $i++
        }
        elseif ($arg -in '--list', '-List') {
            $passThroughArgs += '-List'
            $i++
        }
        elseif ($arg -in '--silent', '-Silent') {
            $passThroughArgs += '-Silent'
            $i++
        }
        elseif ($arg -in '--skip-version-check', '-SkipVersionCheck') {
            $passThroughArgs += '-SkipVersionCheck'
            $i++
        }
        elseif ($arg -eq '--timeout' -and ($i + 1) -lt $additionalArgs.Count) {
            $timeoutVal = $additionalArgs[$i + 1]
            $passThroughArgs += '-TimeoutMinutes', $timeoutVal
            $i += 2
        }
        elseif ($arg -eq '--agent' -and ($i + 1) -lt $additionalArgs.Count) {
            $agentVal = $additionalArgs[$i + 1]
            $passThroughArgs += '-Agent', $agentVal
            $i += 2
        }
        else {
            $passThroughArgs += $arg
            $i++
        }
    }

    # ── No mode flag provided: show RWS help ──────────────────────────────────
    if ($mode -eq '') {
        Write-Host ''
        Write-Host 'Usage: test.ps1 rws <mode> [options]'
        Write-Host ''
        Write-Host 'Modes (required, pick one):'
        Write-Host '  sc, scenarios <names...>  Run named agent-scenario tasks via run-tests.ps1'
        Write-Host '  dir, directory <path>     Run all .md task files in a directory'
        Write-Host '  task <file>               Run a single task file via run-task.ps1'
        Write-Host ''
        Write-Host 'Options:'
        Write-Host '  --production              Use installed browser4-cli instead of cargo run'
        Write-Host '  --fail-fast               Stop after the first failing scenario'
        Write-Host '  --list                    List discovered scenarios, don''t run'
        Write-Host '  --silent                  Suppress agent output'
        Write-Host '  --skip-version-check      Skip browser4-cli version check'
        Write-Host '  --timeout <minutes>       Kill each scenario task after N minutes'
        Write-Host '  --agent <name>            Use a specific agent CLI (claude, kimi, opencode)'
        Write-Host ''
        Write-Host 'Examples:'
        Write-Host '  test.ps1 rws sc amazon                    # Run a specific scenario'
        Write-Host '  test.ps1 rws sc amazon hn                 # Run multiple scenarios'
        Write-Host '  test.ps1 rws scenarios --list             # List discovered tasks'
        Write-Host '  test.ps1 rws dir tasks/real-world/generic # Run all .md tasks in a directory'
        Write-Host '  test.ps1 rws task tasks/amazon.md         # Run a single task file'
        Write-Host '  test.ps1 rws sc amazon --production       # Run against installed CLI'
        exit 0
    }

    # ── Resolve dir path relative to repo root ──────────────────────────────
    if ($mode -eq 'dir' -and $taskDir) {
        if (-not [System.IO.Path]::IsPathRooted($taskDir)) {
            $taskDir = Join-Path $repoRoot $taskDir
        }
        $taskDir = [System.IO.Path]::GetFullPath($taskDir)
        $modeLabel = "real-world scenario dir: $taskDir"
    }

    Write-CommandBanner -Label "Running $modeLabel..."

    # ── Resolve the script path for the chosen mode ──────────────────────────
    if ($mode -eq 'scenarios') {
        $runner = $scenarioRunner
        $runnerKind = 'Scenario runner'
    }
    elseif ($mode -eq 'dir') {
        $runner = $scenarioRunner
        $runnerKind = 'Scenario runner (custom dir)'
    }
    elseif ($mode -eq 'task') {
        $runner = $taskRunner
        $runnerKind = 'Task runner'
    }
    else {
        Write-Error "Unknown RWS mode '$mode'. Valid modes: sc (scenarios), dir (directory), task"
        exit 1
    }

    if (-not (Test-Path $runner)) {
        Write-Error "$runnerKind not found at $runner"
        exit 1
    }

    # ── Build pwsh invocation ────────────────────────────────────────────────
    $pwshArgs = @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-File', $runner)

    if ($mode -eq 'task') {
        $pwshArgs += '-TaskFile', $taskFile
    }
    if ($mode -eq 'dir') {
        $pwshArgs += '-TasksDir', $taskDir
    }

    $pwshArgs += $passThroughArgs

    # ── Show / DryRun ────────────────────────────────────────────────────────
    if ($script:Show) {
        $envHint = if ($setProduction) { '$env:BROWSER4CLI_MODE=production ' } else { '' }
        Write-CommandBanner -Label '[SHOW] Would execute:' -Subtitle "  ${envHint}pwsh $($pwshArgs -join ' ')"
        return
    }

    if ($script:DryRun) {
        $envHint = if ($setProduction) { '$env:BROWSER4CLI_MODE=production ' } else { '' }
        Write-CommandBanner -Label '[DRY RUN] Would execute:' -Subtitle "  ${envHint}pwsh $($pwshArgs -join ' ')"
        return
    }

    # ── Execute with real-time agent output monitoring ────────────────────
    $sw = [System.Diagnostics.Stopwatch]::StartNew()

    # Clean up stale marker from previous runs
    $markerFile = Join-Path $repoRoot 'target' '.current-capture-path'
    Remove-Item -LiteralPath $markerFile -ErrorAction SilentlyContinue

    try {
        if ($repoRoot) { Push-Location $repoRoot }
        $global:LASTEXITCODE = 0

        if ($setProduction) { $env:BROWSER4CLI_MODE = 'production' }

        # ── Start child pwsh process ──────────────────────────────────────
        $proc = Start-Process -FilePath 'pwsh' -ArgumentList $pwshArgs `
            -NoNewWindow -PassThru

        # ── Monitor capture file in real-time ─────────────────────────────
        $capturePath = $null
        $lastSize = 0
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        $markerTimeoutSec = 120
        $monitorStart = Get-Date
        $shownHeader = $false

        while (-not $proc.HasExited) {
            # Check for marker file (written by run-task.ps1 before agent starts)
            if (-not $capturePath -and (Test-Path -LiteralPath $markerFile)) {
                try {
                    $capturePath = (Get-Content -LiteralPath $markerFile -TotalCount 1).Trim()
                    if ($capturePath) {
                        Write-Host ''
                        Write-Rule
                        Write-Host "Agent output (live): $capturePath" -ForegroundColor DarkCyan
                        Write-Rule
                        $shownHeader = $true
                    }
                } catch { }
            }

            # Display new content from the capture file
            if ($capturePath -and (Test-Path -LiteralPath $capturePath)) {
                try {
                    $currentSize = (Get-Item -LiteralPath $capturePath).Length
                    if ($currentSize -gt $lastSize) {
                        $content = [System.IO.File]::ReadAllText($capturePath, $utf8NoBom)
                        if ($content.Length -gt $lastSize) {
                            $newContent = $content.Substring($lastSize)
                            Write-Host $newContent -NoNewline
                            $lastSize = $content.Length
                        }
                    }
                } catch { }
            }

            # Timeout waiting for marker
            if (-not $capturePath -and ((Get-Date) - $monitorStart).TotalSeconds -gt $markerTimeoutSec) {
                Write-Host 'WARNING: Timed out waiting for agent output marker.' -ForegroundColor Yellow
                break
            }

            $proc.Refresh()
            Start-Sleep -Seconds 2
        }

        # ── Final drain of any remaining output ───────────────────────────
        if ($capturePath -and (Test-Path -LiteralPath $capturePath)) {
            try {
                $content = [System.IO.File]::ReadAllText($capturePath, $utf8NoBom)
                if ($content.Length -gt $lastSize) {
                    $newContent = $content.Substring($lastSize)
                    Write-Host $newContent -NoNewline
                }
            } catch { }
        }

        if ($shownHeader) {
            Write-Rule
            Write-Host 'End of agent output' -ForegroundColor DarkGray
            Write-Rule
            Write-Host ''
        }

        $proc.WaitForExit()
        $global:LASTEXITCODE = $proc.ExitCode
        $proc.Dispose()

    } catch {
        Write-Error "Failed to execute $modeLabel`: $_"
        exit 1
    } finally {
        if ($repoRoot) { Pop-Location }
    }

    $exitCode = $LASTEXITCODE
    $sw.Stop()

    # ── Report exit status ────────────────────────────────────────────────
    if ($exitCode -ne 0) {
        Write-CommandBanner -Label "$modeLabel failed with exit code $exitCode" -Icon '❌'
        if ($exitCode -eq 124) {
            Write-Host '  Task timed out.' -ForegroundColor Yellow
        }
    } else {
        Write-CommandBanner -Label "$modeLabel completed successfully" -Icon '✅'
    }

    # ── Clean up marker ────────────────────────────────────────────────────
    Remove-Item -LiteralPath $markerFile -ErrorAction SilentlyContinue

    # ── Persist session ──────────────────────────────────────────────────
    if ($script:SessionAvailable) {
        Update-TestSessionSystem -RepoRoot $repoRoot -SessionPath $script:SessionPath
        $status = if ($exitCode -eq 0) { 'pass' } else { 'fail' }
        $dur = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        $sessionKey = if ($mode -eq 'scenarios') { 'rws:scenarios' } elseif ($mode -eq 'dir') { 'rws:dir' } else { 'rws:task' }
        Update-TestSessionResult -RepoRoot $repoRoot -TestKey $sessionKey `
            -Status $status -ExitCode $exitCode -DurationSec $dur `
            -LogDir $script:TestLogDir `
            -SessionPath $script:SessionPath
    }

    if ($exitCode -ne 0) { exit $exitCode }
}

function Invoke-PowerShellTests([string[]]$additionalArgs) {
    <#
    .SYNOPSIS
        Discover and run all *.tests.ps1 files in the repository.

    .DESCRIPTION
        Recursively searches the repo root for *.tests.ps1 files, excluding
        node_modules, target, .git, and .claude directories.  Each file is
        executed with `pwsh -NoProfile -ExecutionPolicy Bypass -File`.

        In DryRun/Show mode, lists the discovered files without executing.
        Additional arguments are forwarded to every test script (e.g. -Quiet).
    #>
    Write-CommandBanner -Label 'Discovering PowerShell tests...'

    $testFiles = @(Get-ChildItem -Path $repoRoot -Recurse -Filter '*.tests.ps1' -File |
        Where-Object {
            $_.FullName -notmatch '[\\/](node_modules|target|\.git|\.claude)[\\/]'
        } |
        Sort-Object FullName)

    if ($testFiles.Count -eq 0) {
        Write-Host 'No *.tests.ps1 files found.'
        return
    }

    # ── Show / DryRun ────────────────────────────────────────────────────────
    if ($script:Show -or $script:DryRun) {
        $label = if ($script:Show) { '[SHOW] Would execute' } else { '[DRY RUN] Would execute' }
        Write-CommandBanner -Label $label
        Write-Host "Found $($testFiles.Count) PowerShell test file(s):"
        foreach ($file in $testFiles) {
            $relativePath = $file.FullName.Substring($repoRoot.Length + 1)
            $usesPester = Select-String -Path $file.FullName -Pattern '\b(Describe|Context)\s+[''"'']' -Quiet -ErrorAction SilentlyContinue
            $runner = if ($usesPester) { 'Invoke-Pester' } else { 'pwsh -File' }
            Write-Host "  - $relativePath [$runner]"
        }
        return
    }

    Write-CommandBanner -Label "Running $($testFiles.Count) PowerShell test file(s)..."

    # ── Ensure UTF-8 encoding when capturing child pwsh output ────────────
    # Child test processes output Unicode (emoji, box-drawing chars) via
    # Write-Host.  On Windows the default console encoding is the system
    # OEM code page, which cannot decode UTF-8 bytes and produces garbled
    # text like "馃Ч" instead of "🧹".  Align with what the children write.
    $originalOutputEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8

    $pesterAvailable = $null
    try {
        $pesterAvailable = Get-Module -ListAvailable -Name Pester -ErrorAction SilentlyContinue |
            Where-Object Version -ge '5.0' | Select-Object -First 1
    } catch { }

    $failed = [System.Collections.ArrayList]::new()
    $passed = 0
    $perFileResults = [System.Collections.ArrayList]::new()
    $swTotal = [System.Diagnostics.Stopwatch]::StartNew()

    foreach ($file in $testFiles) {
        $relativePath = $file.FullName.Substring($repoRoot.Length + 1)
        Write-Host ''
        Write-Host "  ▶ $relativePath" -ForegroundColor Cyan

        # Detect if this file uses Pester (contains Describe/Context blocks)
        $usesPester = Select-String -Path $file.FullName -Pattern '\b(Describe|Context)\s+[''"'']' -Quiet -ErrorAction SilentlyContinue

        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        $exitCode = 0

        if ($usesPester) {
            # This is a Pester test — run via Invoke-Pester
            if (-not $pesterAvailable) {
                Write-Host "    ⚠ SKIP: Pester 5+ not found. Install with: Install-Module -Name Pester -Force -Scope CurrentUser -SkipPublisherCheck" -ForegroundColor Yellow
                [void]$perFileResults.Add(@{
                    path        = $relativePath
                    status      = 'skip'
                    exitCode    = -1
                    durationSec = 0
                })
                continue
            }

            $pesterArgs = @('-NoProfile', '-Command')
            $pesterCmd = "Import-Module Pester -Force; `$result = Invoke-Pester -Path '$($file.FullName)' -PassThru -ErrorAction SilentlyContinue"
            if ($additionalArgs -contains '-Quiet') {
                $pesterCmd += " -Show None"
                # Remove -Quiet from additional args since Pester uses -Show instead
            }
            $pesterCmd += "; if (`$result.FailedCount -gt 0) { exit `$result.FailedCount } else { exit 0 }"
            $pesterArgs += $pesterCmd

            & pwsh @pesterArgs
            $exitCode = $LASTEXITCODE
        }
        else {
            # Plain PowerShell test — run directly, capture output for diagnostics
            $rawOutput = & pwsh -NoProfile -ExecutionPolicy Bypass -File $file.FullName @additionalArgs *>&1
            $exitCode = $LASTEXITCODE
            # Replay captured output so the console still shows test progress
            if ($rawOutput) {
                $rawOutput | ForEach-Object { Write-Host $_ }
            }
        }

        $sw.Stop()

        $fileSec = [math]::Round($sw.Elapsed.TotalSeconds, 1)
        $elapsed = "$($fileSec)s"
        $fileStatus = if ($exitCode -eq 0) { 'pass' } else { 'fail' }

        [void]$perFileResults.Add(@{
            path        = $relativePath
            status      = $fileStatus
            exitCode    = $exitCode
            durationSec = $fileSec
        })

        if ($exitCode -eq 0) {
            Write-Host "    ✅ PASS ($elapsed)" -ForegroundColor Green
            $passed++
        }
        else {
            Write-Host "    ❌ FAIL (exit $exitCode, $elapsed)" -ForegroundColor Red
            [void]$failed.Add(@{ Path = $relativePath; ExitCode = $exitCode })
            # ── Extract failure details from captured output ──────────────
            # Match assertion-failure lines (indented "FAIL  Label …") and the
            # test file's own summary ("=== FAILURE DETAILS ===") but exclude
            # log lines like "[ERROR] [docker …]" or "[ERROR] [fix-links.py …]".
            $failLines = @($rawOutput | Where-Object {
                $_ -is [string] -and (
                    $_ -match '^\s+FAIL\s+\S' -or
                    $_ -match '=== FAILURE DETAILS ===' -or
                    $_ -match '^\s+❌\s'
                )
            })
            if ($failLines.Count -gt 0) {
                Write-Host "    ── Failure details ──" -ForegroundColor DarkYellow
                $maxShow = 30
                $failLines | Select-Object -First $maxShow | ForEach-Object {
                    Write-Host "      $_" -ForegroundColor DarkYellow
                }
                if ($failLines.Count -gt $maxShow) {
                    Write-Host "      ... and $($failLines.Count - $maxShow) more failure line(s)" -ForegroundColor DarkGray
                }
            } else {
                # No explicit assertion-failure lines found.  Look for PowerShell
                # terminating-error markers (trap output, exception messages,
                # position pointers) before falling back to the output tail.
                $errorLines = @($rawOutput | Where-Object {
                    $_ -is [string] -and (
                        $_ -match 'FATAL ERROR' -or
                        $_ -match '^\s*At\s+\S+:\d+\s+char:\d+' -or
                        $_ -match 'Exception\s*:' -or
                        $_ -match '^\s*\+\s+CategoryInfo' -or
                        $_ -match 'FullyQualifiedErrorId'
                    )
                })
                if ($errorLines.Count -gt 0) {
                    Write-Host "    ── Error details ──" -ForegroundColor DarkYellow
                    $errorLines | ForEach-Object {
                        Write-Host "      $_" -ForegroundColor DarkYellow
                    }
                } else {
                    # No structured error found — show last 50 lines of output
                    # (upped from 15: long test files like common.tests.ps1 need
                    # more context to surface the cause of a non-zero exit).
                    $tail = @($rawOutput | Where-Object { $_ -is [string] } | Select-Object -Last 50)
                    if ($tail.Count -gt 0) {
                        Write-Host "    ── Last output lines (no FAIL/error markers found) ──" -ForegroundColor DarkYellow
                        $tail | ForEach-Object { Write-Host "      $_" -ForegroundColor DarkGray }
                    }
                }
            }
        }
    }

    $swTotal.Stop()
    $total = $passed + $failed.Count
    $totalSec = [math]::Round($swTotal.Elapsed.TotalSeconds, 1)
    $overallStatus = if ($failed.Count -eq 0) { 'pass' } else { 'fail' }
    $overallExit   = if ($failed.Count -gt 0) { 1 } else { 0 }

    Write-Host ''
    Write-Rule
    if ($failed.Count -eq 0) {
        Write-Host "✅ PowerShell tests: $passed passed, 0 failed ($total total)" -ForegroundColor Green
    }
    else {
        Write-Host "❌ PowerShell tests: $passed passed, $($failed.Count) failed ($total total)" -ForegroundColor Red
        Write-Host ''
        Write-Host 'Failed files (see failure details above):' -ForegroundColor Red
        foreach ($f in $failed) {
            Write-Host "  ❌ $($f.Path) (exit $($f.ExitCode))" -ForegroundColor Red
        }
    }
    Write-Rule

    # ── Persist session ──────────────────────────────────────────────────
    if ($script:SessionAvailable) {
        Update-TestSessionSystem -RepoRoot $repoRoot -SessionPath $script:SessionPath
        Update-TestSessionResult -RepoRoot $repoRoot -TestKey 'ps' `
            -Status $overallStatus -ExitCode $overallExit -DurationSec $totalSec `
            -PerFileResults $perFileResults.ToArray() `
            -SessionPath $script:SessionPath
    }

    # Restore the console encoding we saved before running child processes.
    [Console]::OutputEncoding = $originalOutputEncoding

    if ($failed.Count -gt 0) { exit 1 }
}

# Read the parent POM's <modules> section to get the reactor build order.
function Get-ReactorModuleOrder {
    $parentPom = Join-Path $repoRoot 'pom.xml'
    $order = @()
    $inModules = $false

    foreach ($line in (Get-Content $parentPom)) {
        if ($line -match '<modules>') {
            $inModules = $true
            continue
        }
        if ($line -match '</modules>') {
            break
        }
        if ($inModules -and ($line -match '<module>(.+)</module>')) {
            $order += $Matches[1]
        }
    }

    return $order
}

# Find the artifactId for a module directory by reading its pom.xml.
# Skips the <parent> block to return the module's own artifactId.
function Get-ArtifactIdForDir([string]$moduleDir) {
    $pom = Join-Path $moduleDir 'pom.xml'
    if (Test-Path $pom) {
        $inParent = $false
        foreach ($line in (Get-Content $pom)) {
            if ($line -match '<parent>') {
                $inParent = $true
            }
            elseif ($line -match '</parent>') {
                $inParent = $false
            }
            elseif (-not $inParent -and ($line -match '<artifactId>([^<]+)</artifactId>')) {
                return $Matches[1]
            }
        }
    }
    return $null
}

function Invoke-ResumeTests([string[]]$additionalArgs) {
    Write-CommandBanner -Label 'Searching for failed modules to resume from...'

    # Collect all module directories that have failing test reports.
    $failedModuleDirs = @()
    $reportsDirs = Get-ChildItem -Path $repoRoot -Recurse -Directory -Filter 'surefire-reports' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '[\\/]target[\\/]surefire-reports$' }

    foreach ($dir in $reportsDirs) {
        $hasFailure = Get-ChildItem -Path $dir.FullName -Filter '*.xml' -ErrorAction SilentlyContinue |
            Where-Object { (Select-String -Path $_.FullName -Pattern '<failure|<error' -Quiet) } |
            Select-Object -First 1

        if ($hasFailure) {
            # dir is <repo>\module\target\surefire-reports, parent of parent is module dir
            $moduleDir = Split-Path -Parent (Split-Path -Parent $dir.FullName)
            $failedModuleDirs += $moduleDir
        }
    }

    if ($failedModuleDirs.Count -eq 0) {
        Write-Host "No previous test failures found to resume from."
        exit 0
    }

    # Build a hashtable: artifactId -> module directory
    $artifactToDir = @{}
    foreach ($dir in $failedModuleDirs) {
        $aid = Get-ArtifactIdForDir $dir
        if ($aid) {
            $artifactToDir[$aid] = $dir
        }
    }

    # Walk the reactor order from the parent POM to find the first failed module.
    $resumeFrom = $null
    $reactorOrder = Get-ReactorModuleOrder

    foreach ($modulePath in $reactorOrder) {
        $moduleDir = Join-Path $repoRoot $modulePath
        if (Test-Path (Join-Path $moduleDir 'pom.xml')) {
            $aid = Get-ArtifactIdForDir $moduleDir
            if ($aid -and $artifactToDir.ContainsKey($aid)) {
                $resumeFrom = $aid
                break
            }
        }
    }

    if (-not $resumeFrom) {
        Write-Host "Could not match any failed module to the reactor order."
        exit 1
    }

    Write-Host "Resuming from module: $resumeFrom"
    Write-Host ""

    $goal = if ($script:DryRun -and -not $script:Show) { 'test-compile' } else { 'test' }
    $mvnTestArgs = @($goal, '-rf', ":$resumeFrom") + $additionalArgs

    if ($script:Show) {
        Write-CommandBanner -Label '[SHOW] Would execute:' -Subtitle "  $mvnwScript $($mvnTestArgs -join ' ')"
        return
    }

    if ($script:DryRun) {
        Write-CommandBanner -Label '[DRY RUN] Executing:' -Subtitle "  $mvnwScript $($mvnTestArgs -join ' ')"
    }

    Invoke-CommandAndReport -ScriptBlock { & $mvnwScript @mvnTestArgs } -Label 'Resume tests'
}

function Invoke-SessionCommand([string[]]$additionalArgs) {
    <#
    .SYNOPSIS
        List or view persisted test sessions from .test-sessions/.

    .DESCRIPTION
        Operates on the .test-sessions/ directory in the repo root.
        Subcommands:
          list              List all past test sessions in a summary table.
          view <sessionId>  Pretty-print a single session's JSON.
                            Supports prefix matching on the timestamp ID.

        Without a subcommand, shows session-specific usage.
    #>
    $sessionsDir = Join-Path $repoRoot '.test-sessions'

    $subcommand = if ($additionalArgs.Count -gt 0 -and -not $additionalArgs[0].StartsWith('-')) {
        $additionalArgs[0]
    } else { '' }

    # Select-Object -Skip keeps result as array (avoids PowerShell if-statement unwrap)
    $subArgs = @($additionalArgs | Select-Object -Skip 1)

    # ── Help (no subcommand or unknown) ───────────────────────────────
    if ($subcommand -eq '' -or $subcommand -notin @('list', 'view')) {
        if ($subcommand -ne '' -and $subcommand -notin @('list', 'view')) {
            Write-Error "Unknown session subcommand '$subcommand'. Valid subcommands: list, view"
        }
        Write-Host ''
        Write-Host 'Usage: test.ps1 session <subcommand> [options]'
        Write-Host ''
        Write-Host 'Subcommands:'
        Write-Host '  list              List past test sessions (default: last 15)'
        Write-Host '                      --all        Show all sessions'
        Write-Host '                      --count N    Show last N sessions'
        Write-Host '  view <sessionId>  Show the full JSON for a session'
        Write-Host ''
        Write-Host 'Options:'
        Write-Host '  -Show             Print the command, do not execute'
        Write-Host '  -DryRun           Same as -Show for session commands'
        Write-Host ''
        Write-Host 'Examples:'
        Write-Host '  test.ps1 session list'
        Write-Host '  test.ps1 session view 20260724T1917'
        Write-Host '  test.ps1 session view 20260724T1917366034791Z'
        exit 0
    }

    # ── Guard: .test-sessions directory must exist ────────────────────
    if (-not (Test-Path -LiteralPath $sessionsDir -PathType Container)) {
        Write-Host 'No .test-sessions directory found. Run some tests first.' -ForegroundColor Yellow
        exit 0
    }

    # ═══════════════════════════════════════════════════════════════════
    # session list [--all] [--count N]
    # ═══════════════════════════════════════════════════════════════════
    if ($subcommand -eq 'list') {
        if ($script:Show -or $script:DryRun) {
            Write-Host "[$(if ($script:Show) { 'SHOW' } else { 'DRY RUN' })] Would list sessions in: $sessionsDir"
            return
        }

        # ── Parse list-specific flags ──────────────────────────────────
        $listAll = $false
        $listCount = 0  # 0 = use default

        $i = 0
        while ($i -lt $subArgs.Count) {
            $a = $subArgs[$i]
            if ($a -eq '--all') {
                $listAll = $true
                $i++
            } elseif ($a -in @('--count', '-Count', '-c') -and ($i + 1) -lt $subArgs.Count) {
                $val = $subArgs[$i + 1]
                if ($val -match '^\d+$' -and [int]$val -gt 0) {
                    $listCount = [int]$val
                    $i += 2
                } else {
                    Write-Error "session list --count requires a positive integer, got: $val"
                    exit 1
                }
            } else {
                Write-Error "Unknown session list flag: $a. Valid flags: --all, --count N"
                exit 1
            }
        }

        $sessionDirs = @(Get-ChildItem -Path $sessionsDir -Directory |
            Where-Object { Test-Path -LiteralPath (Join-Path $_.FullName 'test-session.json') -PathType Leaf } |
            Sort-Object Name -Descending)

        if ($sessionDirs.Count -eq 0) {
            Write-Host 'No test sessions found in .test-sessions/.' -ForegroundColor Yellow
            exit 0
        }

        # ── Paginate ───────────────────────────────────────────────────
        $defaultLimit = 15
        $limit = if ($listAll) { $sessionDirs.Count }
                 elseif ($listCount -gt 0) { [math]::Min($listCount, $sessionDirs.Count) }
                 else { [math]::Min($defaultLimit, $sessionDirs.Count) }

        $shown = $sessionDirs | Select-Object -First $limit
        $remaining = $sessionDirs.Count - $limit

        # ── Heading ────────────────────────────────────────────────────
        Write-Host ''
        if ($listAll) {
            Write-Host "Test sessions ($($sessionDirs.Count) total, newest first):" -ForegroundColor Cyan
        } elseif ($remaining -gt 0) {
            Write-Host "Test sessions (showing $limit of $($sessionDirs.Count), newest first):" -ForegroundColor Cyan
        } else {
            Write-Host "Test sessions ($($sessionDirs.Count) total, newest first):" -ForegroundColor Cyan
        }
        Write-Host ''

        # ── Print rows ─────────────────────────────────────────────────
        foreach ($dir in $shown) {
            $sessionId = $dir.Name
            $sessionPath = Join-Path $dir.FullName 'test-session.json'

            try {
                $json = Get-Content -LiteralPath $sessionPath -Raw -Encoding UTF8 -ErrorAction Stop
                $obj = $json | ConvertFrom-Json -ErrorAction Stop
            } catch {
                Write-Host "  $(('▶' + ' ' + $sessionId).PadRight(46)) ⚠ unreadable" -ForegroundColor DarkYellow
                continue
            }

            # Friendly timestamp from the directory name (yyyyMMddTHHmmssfffffffZ)
            $friendlyDate = ''
            try {
                $ts = $sessionId -replace '^(\d{8})T(\d{2})(\d{2})(\d{2}).*$', '$1 $2:$3:$4'
                $friendlyDate = "$ts UTC"
            } catch { $friendlyDate = $sessionId }

            # Branch / commit
            $branch = if ($obj.repository -and $obj.repository.branch) { $obj.repository.branch } else { '?' }
            $commit = if ($obj.repository -and $obj.repository.commit) { $obj.repository.commit } else { '?' }

            # Test overview
            $testSummaries = @()
            if ($obj.tests) {
                foreach ($prop in $obj.tests.PSObject.Properties) {
                    $key = $prop.Name
                    $val = $prop.Value
                    if (-not $val) { continue }
                    $status = if ($val.lastStatus -eq 'pass') { '✅' }
                    elseif ($val.lastStatus -eq 'fail') { '❌' }
                    else { '·' }
                    $testSummaries += "$status$key"
                }
            }

            $testLine = if ($testSummaries.Count -gt 0) { ($testSummaries -join '  ') } else { '(no test results)' }

            # ── Print session row ──────────────────────────────────────
            Write-Host "  ▶ $sessionId" -ForegroundColor White
            Write-Host "    Date:   $friendlyDate"
            Write-Host "    Branch: $branch  Commit: $commit"
            Write-Host "    Tests:  $testLine"
            Write-Host ''
        }

        # ── Footer ─────────────────────────────────────────────────────
        if ($remaining -gt 0) {
            Write-Host "  … $remaining more session(s). Use " -NoNewline
            Write-Host '--all' -ForegroundColor Yellow -NoNewline
            Write-Host ' to list all, or ' -NoNewline
            Write-Host '--count N' -ForegroundColor Yellow -NoNewline
            Write-Host ' to show N entries.' -NoNewline
            Write-Host ''
            Write-Host ''
        }

        exit 0
    }

    # ═══════════════════════════════════════════════════════════════════
    # session view <sessionId>
    # ═══════════════════════════════════════════════════════════════════
    if ($subcommand -eq 'view') {
        if ($subArgs.Count -eq 0) {
            Write-Error "session view requires a <sessionId>. Use 'session list' to see available sessions."
            exit 1
        }

        $sessionIdPattern = $subArgs[0]

        if ($script:Show -or $script:DryRun) {
            Write-Host "[$(if ($script:Show) { 'SHOW' } else { 'DRY RUN' })] Would view session: $sessionIdPattern"
            return
        }

        # Find matching session directories (support prefix matching)
        $matches = @(Get-ChildItem -Path $sessionsDir -Directory |
            Where-Object {
                $_.Name -like "$sessionIdPattern*" -and
                (Test-Path -LiteralPath (Join-Path $_.FullName 'test-session.json') -PathType Leaf)
            } |
            Sort-Object Name)

        if ($matches.Count -eq 0) {
            Write-Error "No session found matching '$sessionIdPattern'. Use 'session list' to see available sessions."
            exit 1
        }

        if ($matches.Count -gt 1) {
            Write-Host "Multiple sessions match '$sessionIdPattern':" -ForegroundColor Yellow
            foreach ($m in $matches) {
                Write-Host "  $($m.Name)"
            }
            Write-Host ''
            Write-Host 'Please provide a more specific session ID.' -ForegroundColor Yellow
            exit 1
        }

        $sessionDir = $matches[0]
        $sessionFile = Join-Path $sessionDir.FullName 'test-session.json'

        try {
            $json = Get-Content -LiteralPath $sessionFile -Raw -Encoding UTF8 -ErrorAction Stop
            Write-Host ''
            Write-Rule
            Write-Host "Session: $($sessionDir.Name)" -ForegroundColor Cyan
            Write-Rule
            Write-Host ($json | ConvertFrom-Json | ConvertTo-Json -Depth 6)
        } catch {
            Write-Error "Failed to read session file: $_"
            exit 1
        }

        exit 0
    }
}

# ═══════════════════════════════════════════════════════════════════
# Argument parsing
# ═══════════════════════════════════════════════════════════════════

# Dispatch-category lookup table.  Maps every known test-type name
# (and its aliases) to a category used later in the dispatch block.
$testTypeMap = @{
    'fast'          = 'maven'
    'it'            = 'maven'
    'e2e'           = 'maven'
    'rest'          = 'maven'
    'skills'        = 'maven'
    'mcp'           = 'maven'
    'main'          = 'maven-expand'
    'cli'           = 'cli'
    'browser4-cli'  = 'cli'
    'server'        = 'server'
    'mock-site'     = 'server'
    'mocksite'      = 'server'
    'mocksiteboot'  = 'server'
    'rws'           = 'rws'
    'ps'            = 'ps'
    'resume'        = 'resume'
    'session'       = 'session'
}

$testTypes = @()
$additionalArgs = @()

if ($ScriptArgs.Count -eq 0) {
    Print-Usage
}

# Parsing runs in two phases: test-type mode and pass-through mode.
# The first non-test-type flag (e.g. -pl, -Dfoo=bar) switches from test-type
# to pass-through mode.  After the switch, ALL tokens (including bare words
# like 'browser4-core') are forwarded as additional args.
$parsingTestTypes = $true
foreach ($arg in $ScriptArgs) {
    # Handle --help / -h / -help (only before any test type has been collected,
    # matching the original behaviour where -h given as the first argument
    # shows usage instead of being treated as a test type).
    if ($arg -in '-h', '-help', '--help' -and $testTypes.Count -eq 0) {
        Print-Usage -ExitCode 0
    }

    # Explicit DryRun / Show flags passed as remaining args
    # (PowerShell binds -DryRun/-Show to the switch params automatically,
    # but --dry-run / --show land in $ScriptArgs and need manual handling.)
    if ($arg -eq '--dry-run') {
        $script:DryRun = $true
        continue
    }

    if ($arg -in '--show', '-Show') {
        $script:Show = $true
        continue
    }

    if ($arg -eq '--no-session') {
        $script:NoSession = $true
        continue
    }

    if ($arg -eq '--session-path') {
        $script:_NextIsSessionPath = $true
        continue
    }

    if ($script:_NextIsSessionPath) {
        $script:SessionPath = $arg
        $script:_NextIsSessionPath = $false
        continue
    }

    # Known test type (only in test-type mode)
    if ($parsingTestTypes -and $testTypeMap.ContainsKey($arg)) {
        $testTypes += $arg
        # Session and resume: subsequent tokens are subcommands / args, not test types
        if ($arg -in @('session', 'resume', 'rws')) { $parsingTestTypes = $false }
        continue
    }

    # Not a known test type.  If we're still in test-type mode and this
    # isn't a flag, it's an error (e.g. a typo like 'fasst').
    if ($parsingTestTypes -and -not $arg.StartsWith('-')) {
        Exit-UnknownTestType $arg
    }

    # First non-test-type arg switches us to pass-through mode.
    # After this point, all tokens — flags and bare words — are forwarded.
    $parsingTestTypes = $false
    $additionalArgs += $arg
}

# Default to 'fast' when no test type was given and no flags consumed everything
if ($testTypes.Count -eq 0) {
    $testTypes += 'fast'
}

# ═══════════════════════════════════════════════════════════════════
# Load test-session module (soft dependency, skipped when -NoSession)
# ═══════════════════════════════════════════════════════════════════
$script:SessionAvailable = $false
$script:_NextIsSessionPath = $false
if (-not $script:NoSession) {
    $sessionModulePath = Join-Path $scriptDir 'common' 'test-session.psm1'
    if (Test-Path $sessionModulePath) {
        Import-Module $sessionModulePath -Force -ErrorAction SilentlyContinue
        $script:SessionAvailable = $true
    }
}

# Timestamped log directory for this test run. Lives in .test/ so it
# survives `mvn clean` (unlike target/).  One directory per invocation.
$script:TestLogDir = Join-Path $repoRoot '.test' (
    (Get-Date).ToUniversalTime().ToString('yyyy-MM-dd-HHmmss')
)

# ═══════════════════════════════════════════════════════════════════
# Dispatch
# ═══════════════════════════════════════════════════════════════════

# Handle 'resume' test type: find last failed module and resume with -rf
if ($testTypes -contains 'resume') {
    if ($testTypes.Count -gt 1) {
        Write-Error "'resume' must be the only test type. It resumes from the last failed module."
        exit 1
    }
    Invoke-ResumeTests -additionalArgs $additionalArgs
    exit 0
}

# Handle 'session' test type: list or view persisted test sessions
if ($testTypes -contains 'session') {
    if ($testTypes.Count -gt 1) {
        Write-Error "'session' must be the only test type. It operates on the .test-sessions/ directory."
        exit 1
    }
    Invoke-SessionCommand -additionalArgs $additionalArgs
    exit 0
}

# Bucket test types by dispatch category
$mavenTests  = @($testTypes | Where-Object { $testTypeMap[$_] -in 'maven', 'maven-expand' })
$cliTests    = @($testTypes | Where-Object { $testTypeMap[$_] -eq 'cli' })
$rwsTests    = @($testTypes | Where-Object { $testTypeMap[$_] -eq 'rws' })
$psTests     = @($testTypes | Where-Object { $testTypeMap[$_] -eq 'ps' })
$launchTargets = @($testTypes | Where-Object { $testTypeMap[$_] -eq 'server' })

# Expand 'main' into its constituent test types (only for maven bucket)
$expandedMaven = @()
foreach ($type in $mavenTests) {
    if ($type -eq 'main') {
        $expandedMaven += 'fast', 'it', 'e2e', 'rest'
    } else {
        $expandedMaven += $type
    }
}
$mavenTests = $expandedMaven | Select-Object -Unique

$cliTests = $cliTests | Select-Object -Unique
$rwsTests = $rwsTests | Select-Object -Unique
$psTests  = $psTests | Select-Object -Unique
$launchTargets = $launchTargets | Select-Object -Unique

# Validate: server must be run by itself
if ($launchTargets.Count -gt 0 -and (($mavenTests.Count -gt 0) -or ($cliTests.Count -gt 0) -or ($rwsTests.Count -gt 0) -or ($psTests.Count -gt 0) -or ($launchTargets.Count -gt 1))) {
    Write-Error "mock-site (or server) must be run by itself. Pass any Maven properties after it, for example: test.ps1 mock-site -Dmock.site.port=18080"
    exit 1
}

# ── Execute ──────────────────────────────────────────────────────────

if ($mavenTests.Count -gt 0) {
    Invoke-MavenTests -testTypes $mavenTests -additionalMvnArgs $additionalArgs
}

if ($cliTests.Count -gt 0) {
    Invoke-Browser4CliTests -additionalArgs $additionalArgs
}

if ($rwsTests.Count -gt 0) {
    Invoke-RealWorldScenarioTests -additionalArgs $additionalArgs
}

if ($psTests.Count -gt 0) {
    Invoke-PowerShellTests -additionalArgs $additionalArgs
}

if ($launchTargets.Count -gt 0) {
    Invoke-MockSiteBoot -additionalArgs $additionalArgs
}

exit 0
