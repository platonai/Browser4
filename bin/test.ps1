#!/usr/bin/env pwsh

param(
    [switch]$DryRun,
    [switch]$Show,
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ScriptArgs
)

$script:DryRun = $DryRun
$script:Show = $Show

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
    param([int]$ExitCode = 1)
    Write-Host "Usage: test.ps1 [-DryRun] [-Show] [test-types...] [additional-args...]"
    Write-Host ""
    Write-Host "Options:"
    Write-Host "  -DryRun     Compile only (test-compile), do not run tests"
    Write-Host "  -Show       Print the final Maven command, do not execute anything"
    Write-Host ""
    Write-Host "Test Types:"
    Write-Host "  fast        Run fast unit tests only"
    Write-Host "  it          Run integration tests"
    Write-Host "  e2e         Run end-to-end tests"
    Write-Host "  cli         Run Rust Browser4 CLI tests from cli\browser4-cli"
    Write-Host "  mock-site   Launch mock site from browser4-tests\browser4-rest-tests"
    Write-Host "  rest        Run REST module tests"
    Write-Host "  skills      Run skills-focused agentic tests"
    Write-Host "  mcp         Run MCP-focused agentic tests"
    Write-Host "  resume      Resume from the last failed module (-rf)"
    Write-Host "  browser4    Run all Browser4 main tests (fast, rest, it, e2e)"
    Write-Host ""
    Write-Host "Examples:"
    Write-Host "  test.ps1 fast                       # Run fast unit tests"
    Write-Host "  test.ps1 -DryRun fast               # Show the Maven command for fast tests"
    Write-Host "  test.ps1 -DryRun it -pl browser4-core  # Show the Maven command with extra args"
    Write-Host "  test.ps1 it                         # Run integration tests"
    Write-Host "  test.ps1 e2e                        # Run end-to-end tests"
    Write-Host "  test.ps1 cli                        # Show CLI test help (cargo test --test e2e -- --help)"
    Write-Host "  test.ps1 cli -- --nocapture         # Run CLI tests with extra cargo test args"
    Write-Host "  test.ps1 mock-site -Dmock.site.port=18080"
    Write-Host "  test.ps1 skills                     # Run skills-focused agentic tests"
    Write-Host "  test.ps1 mcp                        # Run MCP-focused agentic tests"
    Write-Host "  test.ps1 resume                     # Resume from the last failed module"
    Write-Host "  test.ps1 browser4                   # Run all Browser4 main tests"
    Write-Host '  test.ps1 it -pl browser4-core       # Pass additional Maven args through'
    exit $ExitCode
}

function Exit-UnknownTestType([string]$testType) {
    Write-Error "Unknown test type '$testType'. Valid test types: fast, it, e2e, cli, mock-site, rest, skills, mcp, resume, browser4. Aliases: mocksite, mocksiteboot."
    exit 1
}

function Normalize-ArgumentTokens([string[]]$tokens) {
    $normalized = @()
    for ($i = 0; $i -lt $tokens.Count; $i++) {
        $token = $tokens[$i]
        while ($token.StartsWith('-D') -and ($i + 1) -lt $tokens.Count -and $tokens[$i + 1].StartsWith('.')) {
            $i++
            $token += $tokens[$i]
        }

        $normalized += $token
    }

    return $normalized
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

    $goal = if ($script:DryRun -and -not $script:Show) { 'test-compile' } else { 'test' }
    $mvnTestArgs = @($goal, '-P=-examples')

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

    if ($hasFast -or $hasRest) {
        $modules = @()
    }

    if ($modules.Count -gt 0) {
        $mvnTestArgs += '-pl'
        $mvnTestArgs += ($modules -join ',')
        $mvnTestArgs += '-am'
    }

    $mvnTestArgs += $additionalMvnArgs

    if ($script:Show) {
        Write-Host ""
        Write-Host "=========================================="
        Write-Host "[SHOW] Would execute:"
        Write-Host "  $mvnCmd $($mvnTestArgs -join ' ')"
        Write-Host "=========================================="
        return
    }

    if ($script:DryRun) {
        Write-Host ""
        Write-Host "=========================================="
        Write-Host "[DRY RUN] Executing:"
        Write-Host "  $mvnCmd $($mvnTestArgs -join ' ')"
        Write-Host "=========================================="
    }

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
    $browser4CliDir = Join-Path $repoRoot 'cli\browser4-cli'

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

        if ($script:Show) {
            $cargoArgs = @('test', '--test', 'e2e') + $additionalArgs
            Write-Host ""
            Write-Host "=========================================="
            Write-Host "[SHOW] Would execute in ${browser4CliDir}:"
            Write-Host "  cargo $($cargoArgs -join ' ')"
            Write-Host "=========================================="
            return
        }

        if ($script:DryRun) {
            $cargoArgs = @('test', '--test', 'e2e', '--no-run') + $additionalArgs
        } elseif ($additionalArgs.Count -eq 0) {
            $cargoArgs = @('test', '--test', 'e2e', '--', '--help')
        } else {
            $cargoArgs = @('test', '--test', 'e2e') + $additionalArgs
        }

        if ($script:DryRun) {
            Write-Host ""
            Write-Host "=========================================="
            Write-Host "[DRY RUN] Executing in ${browser4CliDir}:"
            Write-Host "  cargo $($cargoArgs -join ' ')"
            Write-Host "=========================================="
        }

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

function Invoke-MockSiteBoot([string[]]$additionalArgs) {
    $mvnCmd = Join-Path $repoRoot 'mvnw.cmd'
    $mockSiteModuleDir = Join-Path $repoRoot 'browser4-tests\browser4-rest-tests'
    $passThroughArgs = @()
    $mockSiteJvmArgs = @()
    if (-not (Test-Path $mvnCmd)) {
        Write-Error "Maven wrapper not found at $mvnCmd"
        exit 1
    }
    if (-not (Test-Path $mockSiteModuleDir)) {
        Write-Error "Mock site module not found at $mockSiteModuleDir"
        exit 1
    }

    Write-Host "=========================================="
    Write-Host "Launching MockSiteBoot..."
    Write-Host "=========================================="

    foreach ($arg in $additionalArgs) {
        if ($arg -like '-Dmock.site.*') {
            $mockSiteJvmArgs += $arg
        }
        else {
            $passThroughArgs += $arg
        }
    }

    $mvnArgs = @(
        '-DskipTests',
        '-P=-examples'
    ) + $passThroughArgs

    if ($mockSiteJvmArgs.Count -gt 0 -and -not ($passThroughArgs | Where-Object { $_ -like '-Dspring-boot.run.jvmArguments=*' })) {
        $mvnArgs += "-Dspring-boot.run.jvmArguments=$($mockSiteJvmArgs -join ' ')"
    }

    if ($script:Show) {
        $mvnArgs += @('package', 'spring-boot:run')
    } elseif ($script:DryRun) {
        $mvnArgs += @('compile')
    } else {
        $mvnArgs += @(
            'package',
            'spring-boot:run'
        )
    }

    try {
        Push-Location $mockSiteModuleDir

        if ($script:Show) {
            Write-Host ""
            Write-Host "=========================================="
            Write-Host "[SHOW] Would execute in ${mockSiteModuleDir}:"
            Write-Host "  $mvnCmd $($mvnArgs -join ' ')"
            Write-Host "=========================================="
            Pop-Location
            return
        }

        if ($script:DryRun) {
            Write-Host ""
            Write-Host "=========================================="
            Write-Host "[DRY RUN] Executing in ${mockSiteModuleDir}:"
            Write-Host "  $mvnCmd $($mvnArgs -join ' ')"
            Write-Host "=========================================="
        }

        & $mvnCmd @mvnArgs
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            Write-Host ""
            Write-Host "=========================================="
            Write-Host "❌ MockSiteBoot failed with exit code $exitCode"
            Write-Host "=========================================="
            exit $exitCode
        }
    }
    catch {
        Write-Error "Failed to launch MockSiteBoot: $_"
        exit 1
    }
    finally {
        Pop-Location
    }
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
    Write-Host "=========================================="
    Write-Host "Searching for failed modules to resume from..."
    Write-Host "=========================================="

    # Collect all module directories that have failing test reports.
    $failedModuleDirs = @()
    $reportsDirs = Get-ChildItem -Path $repoRoot -Recurse -Directory -Filter 'surefire-reports' -ErrorAction SilentlyContinue |
        Where-Object { $_.FullName -match '\\target\\surefire-reports$' }

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
    $mvnTestArgs = @($goal, '-P=-examples', '-rf', ":$resumeFrom") + $additionalArgs

    $mvnCmd = Join-Path $repoRoot 'mvnw.cmd'

    if ($script:Show) {
        Write-Host ""
        Write-Host "=========================================="
        Write-Host "[SHOW] Would execute:"
        Write-Host "  $mvnCmd $($mvnTestArgs -join ' ')"
        Write-Host "=========================================="
        return
    }

    if ($script:DryRun) {
        Write-Host ""
        Write-Host "=========================================="
        Write-Host "[DRY RUN] Executing:"
        Write-Host "  $mvnCmd $($mvnTestArgs -join ' ')"
        Write-Host "=========================================="
    }

    try {
        & $mvnCmd @mvnTestArgs
        $exitCode = $LASTEXITCODE
        if ($exitCode -ne 0) {
            Write-Host ""
            Write-Host "=========================================="
            Write-Host "❌ Tests failed with exit code $exitCode"
            Write-Host "=========================================="
            exit $exitCode
        }

        Write-Host ""
        Write-Host "=========================================="
        Write-Host "✅ Resume tests completed successfully"
        Write-Host "=========================================="
    }
    catch {
        Write-Error "Failed to execute resume tests: $_"
        exit 1
    }
}

$knownTestTypes = @('fast', 'it', 'e2e', 'cli', 'browser4-cli', 'mock-site', 'mocksite', 'mocksiteboot', 'rest', 'skills', 'mcp', 'resume', 'browser4')
$testTypes = @()
$additionalArgs = @()
$parsingTestTypes = $true

$normalizedScriptArgs = Normalize-ArgumentTokens $ScriptArgs

if ($normalizedScriptArgs.Count -eq 0) {
    Print-Usage
}

foreach ($arg in $normalizedScriptArgs) {
    if ($arg -in '-h', '-help', '--help') {
        Print-Usage -ExitCode 0
    }

    if ($arg -eq '--dry-run') {
        $script:DryRun = $true
        continue
    }

    if ($arg -in '--show', '-Show') {
        $script:Show = $true
        continue
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

# Handle 'resume' test type: find last failed module and resume with -rf
if ($testTypes -contains 'resume') {
    if ($testTypes.Count -gt 1) {
        Write-Error "'resume' must be the only test type. It resumes from the last failed module."
        exit 1
    }
    Invoke-ResumeTests -additionalArgs $additionalArgs
    exit 0
}

$mavenTests = @()
$cliTests = @()
$launchTargets = @()

foreach ($type in $testTypes) {
    if ($type -eq 'browser4') {
        $mavenTests += 'fast', 'it', 'e2e', 'rest'
        continue
    }

    if ($type -in @('cli', 'browser4-cli')) {
        $cliTests += $type
        continue
    }

    if ($type -in @('mock-site', 'mocksite', 'mocksiteboot')) {
        $launchTargets += 'mock-site'
        continue
    }

    $mavenTests += $type
}

$mavenTests = $mavenTests | Select-Object -Unique
$cliTests = $cliTests | Select-Object -Unique
$launchTargets = $launchTargets | Select-Object -Unique

if ($launchTargets.Count -gt 0 -and (($mavenTests.Count -gt 0) -or ($cliTests.Count -gt 0) -or ($launchTargets.Count -gt 1))) {
    Write-Error "mock-site must be run by itself. Pass any Maven properties after it, for example: test.ps1 mock-site -Dmock.site.port=18080"
    exit 1
}

if ($mavenTests.Count -gt 0) {
    Invoke-MavenTests -testTypes $mavenTests -additionalMvnArgs $additionalArgs
}

if (($cliTests | Where-Object { $_ -in @('cli', 'browser4-cli') }).Count -gt 0) {
    Invoke-Browser4CliTests -additionalArgs $additionalArgs
}

if ($launchTargets -contains 'mock-site') {
    Invoke-MockSiteBoot -additionalArgs $additionalArgs
}

exit 0
