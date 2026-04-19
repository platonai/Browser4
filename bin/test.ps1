#!/usr/bin/env pwsh

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

function Print-Usage {
    Write-Host "Usage: test.ps1 [test-types...] [additional-args...]"
    Write-Host ""
    Write-Host "Test Types:"
    Write-Host "  fast        Run fast unit tests only"
    Write-Host "  it          Run integration tests"
    Write-Host "  e2e         Run end-to-end tests"
    Write-Host "  cli         Run Rust Browser4 CLI tests from sdks\browser4-cli"
    Write-Host "  rest        Run REST module tests"
    Write-Host "  skills      Run skills-focused agentic tests"
    Write-Host "  mcp         Run MCP-focused agentic tests"
    Write-Host "  browser4    Run all Browser4 main tests (fast, rest, it, e2e)"
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  test.ps1 fast                       # Run fast unit tests"
    Write-Host "  test.ps1 it                         # Run integration tests"
    Write-Host "  test.ps1 e2e                        # Run end-to-end tests"
    Write-Host "  test.ps1 cli                        # Run Browser4 CLI tests"
    Write-Host "  test.ps1 cli -- --nocapture         # Pass extra cargo test args"
    Write-Host "  test.ps1 skills                     # Run skills-focused agentic tests"
    Write-Host "  test.ps1 mcp                        # Run MCP-focused agentic tests"
    Write-Host "  test.ps1 browser4                   # Run all Browser4 main tests"
    Write-Host '  test.ps1 it -pl browser4-core       # Pass additional Maven args through'
    exit 1
}

function Exit-UnknownTestType([string]$testType) {
    Write-Error "Unknown test type '$testType'. Valid test types: fast, it, e2e, cli, rest, skills, mcp, browser4."
    exit 1
}

function Invoke-MavenTests([string[]]$testTypes, [string[]]$additionalMvnArgs) {
    $mvnCmd = Join-Path $repoRoot 'mvnw.cmd'
    if (-not (Test-Path $mvnCmd)) {
        Write-Error "Maven wrapper not found at $mvnCmd"
        exit 1
    }

    Write-Host "=========================================="
    Write-Host "Running Maven tests: $($testTypes -join ', ')"
    Write-Host "=========================================="

    $mvnTestArgs = @('test', '-P=-examples')

    $hasFast = $testTypes -contains 'fast'
    $hasIT = $testTypes -contains 'it'
    $hasE2E = $testTypes -contains 'e2e'
    $hasRest = $testTypes -contains 'rest'
    $hasSkills = $testTypes -contains 'skills'
    $hasMcp = $testTypes -contains 'mcp'

    if ($hasIT) { $mvnTestArgs += '-DrunITs=true' }
    if ($hasE2E) { $mvnTestArgs += '-DrunE2ETests=true' }

    $modules = @()
    if ($hasSkills -or $hasMcp) {
        $modules += 'pulsar-agentic'

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

    if ($hasFast -or $hasRest) {
        $modules = @()
    }

    if ($modules.Count -gt 0) {
        $mvnTestArgs += '-pl'
        $mvnTestArgs += ($modules -join ',')
        $mvnTestArgs += '-am'
    }

    $mvnTestArgs += $additionalMvnArgs

    try {
        & $mvnCmd @mvnTestArgs
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            Write-Host ""
            Write-Host "=========================================="
            Write-Host "❌ Maven tests failed with exit code $exitCode"
            Write-Host "=========================================="
            exit $exitCode
        }

        Write-Host ""
        Write-Host "=========================================="
        Write-Host "✅ Maven tests completed successfully"
        Write-Host "=========================================="
    }
    catch {
        Write-Error "Failed to execute Maven tests: $_"
        exit 1
    }
}

function Invoke-Browser4CliTests([string[]]$additionalArgs) {
    $browser4CliDir = Join-Path $repoRoot 'sdks\browser4-cli'

    Write-Host "=========================================="
    Write-Host "Running Browser4 CLI tests..."
    Write-Host "=========================================="

    if (-not (Test-Path $browser4CliDir)) {
        Write-Error "Browser4 CLI directory not found at $browser4CliDir"
        exit 1
    }

    $cargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
    if (-not $cargoCmd) {
        Write-Error "cargo is not installed or not in PATH"
        exit 1
    }

    Push-Location $browser4CliDir
    try {
        Write-Host "Working directory: $(Get-Location)"

        if (-not (Test-Path "$browser4CliDir\Cargo.toml")) {
            Write-Error "Cargo.toml not found in $browser4CliDir"
            exit 1
        }

        $cargoArgs = @('test') + $additionalArgs
        & cargo @cargoArgs
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            Write-Host ""
            Write-Host "=========================================="
            Write-Host "❌ Browser4 CLI tests failed with exit code $exitCode"
            Write-Host "=========================================="
            exit $exitCode
        }

        Write-Host ""
        Write-Host "=========================================="
        Write-Host "✅ Browser4 CLI tests completed successfully"
        Write-Host "=========================================="
    }
    catch {
        Write-Error "Failed to execute Browser4 CLI tests: $_"
        exit 1
    }
    finally {
        Pop-Location
    }
}

$knownTestTypes = @('fast', 'it', 'e2e', 'cli', 'browser4-cli', 'rest', 'skills', 'mcp', 'browser4')
$testTypes = @()
$additionalArgs = @()
$parsingTestTypes = $true

if ($args.Count -eq 0) {
    Print-Usage
}

foreach ($arg in $args) {
    if ($arg -in '-h', '-help', '--help') {
        Print-Usage
    }

    if ($parsingTestTypes -and ($arg -in $knownTestTypes)) {
        $testTypes += $arg
    }
    else {
        if ($parsingTestTypes -and -not ($arg.StartsWith('-'))) {
            Exit-UnknownTestType $arg
        }

        $parsingTestTypes = $false
        $additionalArgs += $arg
    }
}

if ($testTypes.Count -eq 0) {
    $testTypes += 'fast'
}

$mavenTests = @()
$cliTests = @()

foreach ($type in $testTypes) {
    if ($type -eq 'browser4') {
        $mavenTests += 'fast', 'it', 'e2e', 'rest'
        continue
    }

    if ($type -in @('cli', 'browser4-cli')) {
        $cliTests += $type
        continue
    }

    $mavenTests += $type
}

$mavenTests = $mavenTests | Select-Object -Unique
$cliTests = $cliTests | Select-Object -Unique

if ($mavenTests.Count -gt 0) {
    Invoke-MavenTests -testTypes $mavenTests -additionalMvnArgs $additionalArgs
}

if (($cliTests | Where-Object { $_ -in @('cli', 'browser4-cli') }).Count -gt 0) {
    Invoke-Browser4CliTests -additionalArgs $additionalArgs
}

exit 0
