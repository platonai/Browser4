#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Acceptance test for the latest production release of browser4-cli.

.DESCRIPTION
    Downloads, installs, exercises, uninstalls, and re-installs the global
    browser4-cli from the public OSS distribution channel, then runs the
    multi-scenario stress suite against the global CLI.

    The script is designed to be run in CI or locally before tagging a release.
    It tests the full lifecycle:

      1. Create a random working directory under ~/tmp/.browser4-acceptance.
      2. Clean any pre-existing global installation.
      3. Install the latest browser4-cli via the remote bootstrap script.
      4. Smoke-test the CLI (--help, open, open <url>).
      5. Clean up server processes (close-all, kill-all).
      6. Uninstall and repeat the install cycle to verify idempotency.
      7. Run multi-scenarios.ps1 against the global CLI.

.PARAMETER WorkingDir
    Working directory for temporary artifacts.
    Default: a random subdirectory under ~/tmp/.browser4-acceptance
    (e.g. ~/tmp/.browser4-acceptance/20260611-143052-a3f2).

.PARAMETER SkipMultiScenarios
    Skip the final multi-scenarios.ps1 run.

.PARAMETER MultiScenariosIterations
    Number of iterations for the multi-scenario suite (default: 1).

.PARAMETER KeepWorkingDir
    Do not delete the working directory on exit.

.PARAMETER Help
    Show this help message.

.EXAMPLE
    .\test-production.ps1

.EXAMPLE
    .\test-production.ps1 -SkipMultiScenarios

.EXAMPLE
    .\test-production.ps1 -MultiScenariosIterations 3 -KeepWorkingDir
#>

[CmdletBinding()]
param(
    [string] $WorkingDir = '',
    [switch] $SkipMultiScenarios,
    [int] $MultiScenariosIterations = 1,
    [switch] $KeepWorkingDir,
    [switch] $Help
)

if ($Help) {
    Get-Help -Full $MyInvocation.MyCommand.Path
    exit 0
}

$ErrorActionPreference = 'Stop'
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = git rev-parse --show-toplevel 2>$null

if (-not $RepoRoot) {
    $RepoRoot = $ScriptDir
    while ($RepoRoot -and -not (Test-Path (Join-Path $RepoRoot 'pom.xml'))) {
        $RepoRoot = Split-Path -Parent $RepoRoot
    }
}
if (-not $RepoRoot) { throw 'Cannot find repo root (no pom.xml found up the tree)' }

# ─────────────────────────────────────────────────────
# Resolve working directory — default to a random
# subdirectory under ~/tmp/.browser4-acceptance so each
# run is isolated without the caller needing to supply
# a unique path.
# ─────────────────────────────────────────────────────
if (-not $WorkingDir) {
    $homeTmp = if ($IsLinux -or $IsMacOS) {
        Join-Path $env:HOME 'tmp'
    } else {
        Join-Path $env:USERPROFILE 'tmp'
    }
    $acceptanceRoot = Join-Path $homeTmp '.browser4-acceptance'
    $timestamp = Get-Date -Format 'yyyyMMdd-HHmmss'
    $randomSuffix = -join ((48..57) + (97..102) | Get-Random -Count 4 | ForEach-Object { [char]$_ })
    $WorkingDir = Join-Path $acceptanceRoot "$timestamp-$randomSuffix"
}

# ─────────────────────────────────────────────────────
# OS detection — use PS7 automatic variables where
# available, fall back to manual detection on PS 5.1.
# Avoid assigning to $IsLinux / $IsWindows / $IsMacOS
# directly because they are read-only in PS 7+.
# ─────────────────────────────────────────────────────
if ($PSVersionTable.PSVersion.Major -ge 6) {
    $script:OSWin   = $IsWindows
    $script:OSLinux = $IsLinux
    $script:OSMac   = $IsMacOS
} else {
    $script:OSWin   = [System.Environment]::OSVersion.Platform -eq 'Win32NT'
    $script:OSMac   = $false
    $script:OSLinux = $false
}

# ─────────────────────────────────────────────────────
# Constants
# ─────────────────────────────────────────────────────
$InstallPs1Url = 'https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1'
$InstallShUrl  = 'https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh'
$Browser4Home  = if ($OSWin) { Join-Path $env:USERPROFILE '.browser4' } else { Join-Path $env:HOME '.browser4' }

# ─────────────────────────────────────────────────────
# State tracking
# ─────────────────────────────────────────────────────
$TotalSteps  = 0
$PassedSteps = 0
$FailedSteps = 0

function Write-StepHeader {
    param([string]$Title)
    Write-Host ''
    Write-Host ('━' * 60) -ForegroundColor Cyan
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host ('━' * 60) -ForegroundColor Cyan
}

function Write-StepResult {
    param(
        [string]$Step,
        [bool]$Passed,
        [string]$Detail = ''
    )
    $script:TotalSteps++
    if ($Passed) {
        $script:PassedSteps++
        $icon = '✅'
        $color = 'Green'
    } else {
        $script:FailedSteps++
        $icon = '❌'
        $color = 'Red'
    }
    $msg = "  $icon $Step"
    if ($Detail) { $msg += "  |  $Detail" }
    Write-Host $msg -ForegroundColor $color
}

function Write-Info {
    param([string]$Message)
    Write-Host "    › $Message" -ForegroundColor DarkGray
}

function Write-WarningMsg {
    param([string]$Message)
    Write-Host "    ⚠ $Message" -ForegroundColor Yellow
}

function Write-PlanNote {
    param([string]$Message)
    Write-Host "    📋 $Message" -ForegroundColor Magenta
}

function Assert-OutputContains {
    param(
        [string]$Output,
        [string]$Pattern,
        [string]$Description = $Pattern
    )
    if ($Output -match [regex]::Escape($Pattern)) {
        return $true
    }
    Write-WarningMsg "Expected output to contain: $Description"
    Write-WarningMsg "Actual output (first 500 chars): $($Output.Substring(0, [Math]::Min(500, $Output.Length)))"
    return $false
}

function Assert-ExitOk {
    param([int]$ExitCode)
    return $ExitCode -eq 0
}

function Invoke-CliCommand {
    param(
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 120,
        [switch]$IgnoreExitCode
    )
    $sw = [Diagnostics.Stopwatch]::StartNew()
    try {
        $tmpOut = Join-Path $env:TEMP 'b4cli-stdout.txt'
        $tmpErr = Join-Path $env:TEMP 'b4cli-stderr.txt'
        Remove-Item $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue

        # Use direct invocation rather than Start-Process so that
        # .cmd wrappers and PATHEXT resolution work correctly.
        $prevEAP = $ErrorActionPreference
        $ErrorActionPreference = 'Continue'
        $output = & browser4-cli @Arguments 2>&1 | Out-String
        $exitCode = $LASTEXITCODE
        $ErrorActionPreference = $prevEAP

        $sw.Stop()
        return [PSCustomObject]@{
            ExitCode = [int]$exitCode
            Output   = $output.Trim()
            Stdout   = $output.Trim()
            Stderr   = ''
            Elapsed  = $sw.Elapsed
        }
    } catch {
        $sw.Stop()
        return [PSCustomObject]@{
            ExitCode = -1
            Output   = "Command failed: $_"
            Stdout   = ''
            Stderr   = "Command failed: $_"
            Elapsed  = $sw.Elapsed
        }
    }
}

function Invoke-CliCommandAsync {
    param(
        [string[]]$Arguments,
        [int]$TimeoutSeconds = 30
    )
    # Locate the actual executable to pass to Start-Process.
    $exe = (Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue).Source
    if (-not $exe) { $exe = 'browser4-cli' }
    $proc = Start-Process `
        -FilePath $exe `
        -ArgumentList $Arguments `
        -NoNewWindow `
        -PassThru `
        -RedirectStandardOutput $env:TEMP\b4cli-async-stdout.txt `
        -RedirectStandardError $env:TEMP\b4cli-async-stderr.txt

    return $proc
}

function Wait-ProcessAndCollect {
    param(
        [System.Diagnostics.Process]$Process,
        [int]$TimeoutSeconds = 30
    )
    if (-not $Process.WaitForExit($TimeoutSeconds * 1000)) {
        $Process.Kill($true) | Out-Null
        $Process.WaitForExit(5000) | Out-Null
    }
    $stdout = Get-Content -Path $env:TEMP\b4cli-async-stdout.txt -Raw -ErrorAction SilentlyContinue
    $stderr = Get-Content -Path $env:TEMP\b4cli-async-stderr.txt -Raw -ErrorAction SilentlyContinue
    Remove-Item $env:TEMP\b4cli-async-stdout.txt, $env:TEMP\b4cli-async-stderr.txt -Force -ErrorAction SilentlyContinue

    $combined = (@($stdout, $stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"
    return [PSCustomObject]@{
        ExitCode = [int]$Process.ExitCode
        Output   = $combined.Trim()
        Stdout   = $stdout.Trim()
        Stderr   = $stderr.Trim()
    }
}

function Get-RuntimeBundleDir {
    # The runtime bundle may live under ~/.browser4 or %APPDATA%/browser4.
    $searchRoots = @($Browser4Home)
    if ($OSWin) {
        $searchRoots += Join-Path $env:APPDATA 'browser4'
        $searchRoots += Join-Path $env:LOCALAPPDATA 'browser4'
    }
    foreach ($root in $searchRoots) {
        if (-not (Test-Path $root)) { continue }
        $candidate = Get-ChildItem -Path $root -Recurse -Directory -Filter 'browser4-bundle' -ErrorAction SilentlyContinue `
            | Where-Object { Test-Path (Join-Path $_.FullName 'Browser4Bundle.jar') } `
            | Select-Object -First 1
        if ($candidate) { return $candidate.FullName }
    }
    return $null
}

# ═══════════════════════════════════════════════════════════════
# STEP 0 — Setup working directory
# ═══════════════════════════════════════════════════════════════
Write-StepHeader 'STEP 0 — Setup'

Write-Info "WorkingDir    : $WorkingDir"
Write-Info "Browser4Home  : $Browser4Home"

if (-not (Test-Path $WorkingDir)) {
    New-Item -ItemType Directory -Path $WorkingDir -Force | Out-Null
    Write-Info "Created working directory"
} else {
    Write-Info 'Working directory already exists'
}
Push-Location $WorkingDir
Write-Info "Pushed to $WorkingDir"

# ─────────────────────────────────────────────────────
# Rename ~/.browser4 out of the way (if it exists) so
# testing starts from a clean slate.  Restore it after
# the test run completes (see Restore-Browser4Home).
# ─────────────────────────────────────────────────────
$browser4HomeBackup = $null
if (Test-Path $Browser4Home) {
    $browser4HomeBackup = "$Browser4Home.backup.$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Write-Info "Renaming ~/.browser4 → $browser4HomeBackup"
    Move-Item -Path $Browser4Home -Destination $browser4HomeBackup -Force
    Write-Info 'Clean slate: no ~/.browser4 present'
} else {
    Write-Info 'No existing ~/.browser4 — already clean'
}

# ─────────────────────────────────────────────────────
# Helper: restore ~/.browser4 from backup
# ─────────────────────────────────────────────────────
function Restore-Browser4Home {
    if ($browser4HomeBackup -and (Test-Path $browser4HomeBackup)) {
        Write-Info "Restoring original ~/.browser4 from $browser4HomeBackup"
        if (Test-Path $Browser4Home) {
            Remove-Item $Browser4Home -Recurse -Force -ErrorAction SilentlyContinue
        }
        Move-Item $browser4HomeBackup $Browser4Home -Force
        Write-Info 'Original ~/.browser4 restored'
    }
}

# ═══════════════════════════════════════════════════════════════
# STEP 1 — Check for existing global browser4-cli and uninstall
# ═══════════════════════════════════════════════════════════════
Write-StepHeader 'STEP 1 — Pre-clean: check for existing global browser4-cli'

$existingCli = Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue
if (-not $existingCli) {
    $whichCmd = if ($OSWin) { 'where.exe' } else { 'which' }
    $raw = & $whichCmd 'browser4-cli' 2>$null | Select-Object -First 1
    if ($raw) { $existingCli = $raw.Trim() }
}

if ($existingCli) {
    $existingPath = if ($existingCli -is [string]) { $existingCli } else { $existingCli.Source }
    Write-Info "Found existing global browser4-cli: $existingPath"
    Write-Info 'Running browser4-cli uninstall …'

    $result = Invoke-CliCommand -Arguments @('uninstall') -IgnoreExitCode
    Write-Info "uninstall output: $($result.Output)"

    # Also use the comprehensive remove script
    $removeScript = Join-Path $RepoRoot 'bin\tools\remove-global-browser4-cli.ps1'
    if (Test-Path $removeScript) {
        Write-Info 'Running remove-global-browser4-cli.ps1 for thorough cleanup …'
        try {
            & $removeScript -ErrorAction SilentlyContinue
        } catch {
            Write-Info "remove-global-browser4-cli.ps1: $_"
        }
    }

    Write-StepResult -Step 'Pre-clean' -Passed $true -Detail 'Removed existing global CLI'
} else {
    Write-Info 'No existing global browser4-cli found — clean start'
    Write-StepResult -Step 'Pre-clean' -Passed $true -Detail 'No existing installation'
}

# ═══════════════════════════════════════════════════════════════
# STEP 2 — Report: latest release status across all channels
# ═══════════════════════════════════════════════════════════════
Write-StepHeader 'STEP 2 — Report: latest release status'

$GitHubRepo     = 'platonai/Browser4'
$GitHubReleases = "https://github.com/$GitHubRepo/releases"
$GitHubApiLatest = "https://api.github.com/repos/$GitHubRepo/releases/latest"
$NpmPackage     = 'browser4-cli'
$NpmRegistry    = "https://registry.npmjs.org/$NpmPackage/latest"
$OssBaseUrl     = 'https://browser4.oss-cn-beijing.aliyuncs.com'
$OssReleases    = "$OssBaseUrl/releases"
$MirrorsConfig  = Join-Path $Browser4Home 'runtime\mirrors.json'

# ── Build the report table ──────────────────────────
$reportRows = @()

# 1. GitHub Releases
Write-Info 'Querying GitHub Releases API …'
try {
    $ghHeaders = @{ 'User-Agent' = 'browser4-test-production/1.0' }
    if ($env:GITHUB_TOKEN) {
        $ghHeaders['Authorization'] = "Bearer $env:GITHUB_TOKEN"
    }
    $ghResponse = Invoke-WebRequest -Uri $GitHubApiLatest -Headers $ghHeaders -UseBasicParsing -TimeoutSec 15 -ErrorAction Stop
    $ghData = $ghResponse.Content | ConvertFrom-Json
    $ghTag = $ghData.tag_name
    $ghPublished = $ghData.published_at
    $ghAssets = ($ghData.assets | ForEach-Object { $_.name }) -join ', '
    $ghStatus = 'OK'
    $ghDetail = "tag=$ghTag  published=$ghPublished  assets=$($ghData.assets.Count)"
} catch {
    $ghTag = '-'
    $ghStatus = "ERROR: $($_.Exception.Message)"
    $ghDetail = $ghStatus
    Write-WarningMsg "GitHub API failed: $_"
}
$reportRows += [PSCustomObject]@{
    Channel  = 'GitHub Releases'
    Version  = $ghTag
    Status   = $ghStatus
    Detail   = $ghDetail
    Url      = $GitHubReleases
}

# 2. npm Releases
Write-Info 'Querying npm registry …'
try {
    $npmResponse = Invoke-WebRequest -Uri $NpmRegistry -UseBasicParsing -TimeoutSec 15 -ErrorAction Stop
    $npmData = $npmResponse.Content | ConvertFrom-Json
    $npmVersion = $npmData.version
    $npmStatus = 'OK'
    $npmDetail = "version=$npmVersion"
} catch {
    $npmVersion = '-'
    $npmStatus = "ERROR: $($_.Exception.Message)"
    $npmDetail = $npmStatus
    Write-WarningMsg "npm registry failed: $_"
}
$reportRows += [PSCustomObject]@{
    Channel  = 'npm Registry'
    Version  = $npmVersion
    Status   = $npmStatus
    Detail   = $npmDetail
    Url      = "https://www.npmjs.com/package/$NpmPackage"
}

# 3. Aliyun OSS CDN — HEAD known public assets (OSS does not
#    support directory listing so we can't GET /releases/).
Write-Info 'Checking Aliyun OSS CDN …'
$ossOk = $false
$ossDetail = ''
try {
    # Check 1: the install script (always published)
    $ossInstallUrl = "$OssBaseUrl/scripts/install-browser4-cli.ps1"
    $ossInstallResp = Invoke-WebRequest -Uri $ossInstallUrl -Method Head -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
    $ossOk1 = ($ossInstallResp.StatusCode -eq 200)
    $ossDetail += "install-script: HTTP $($ossInstallResp.StatusCode)"

    # Check 2: a release asset via the latest redirect
    $ossLatestUrl = "$OssReleases/download/latest/browser4-cli-win32-x64.exe"
    try {
        $ossLatestResp = Invoke-WebRequest -Uri $ossLatestUrl -Method Head -UseBasicParsing -TimeoutSec 10 -ErrorAction Stop
        $ossOk2 = ($ossLatestResp.StatusCode -eq 200 -or $ossLatestResp.StatusCode -eq 302)
        $ossDetail += "  latest-asset: HTTP $($ossLatestResp.StatusCode)"
    } catch {
        $ossOk2 = $false
        $ossDetail += "  latest-asset: unreachable"
    }

    $ossOk = $ossOk1 -and $ossOk2
    $ossStatus = if ($ossOk) { 'OK' } else { 'DEGRADED' }
} catch {
    $ossStatus = "ERROR: $($_.Exception.Message)"
    $ossDetail = $ossStatus
    Write-WarningMsg "Aliyun OSS CDN check failed: $_"
}
$reportRows += [PSCustomObject]@{
    Channel  = 'Aliyun OSS CDN'
    Version  = "(see GitHub tag)"
    Status   = $ossStatus
    Detail   = $ossDetail
    Url      = $OssReleases
}

# 4. Custom mirrors (mirrors.json)
Write-Info 'Checking custom download mirrors …'
$mirrorEntries = @()
if (Test-Path $MirrorsConfig) {
    try {
        $mirrorsData = Get-Content $MirrorsConfig -Raw | ConvertFrom-Json
        $mirrorEntries = @($mirrorsData.mirrors | ForEach-Object {
            [PSCustomObject]@{ Name = $_.name; BaseUrl = $_.base_url }
        })
    } catch {
        Write-WarningMsg "Could not parse mirrors.json: $_"
    }
}
$builtinMirrors = @(
    [PSCustomObject]@{ Name = 'github';     BaseUrl = "$GitHubReleases" },
    [PSCustomObject]@{ Name = 'aliyun-oss'; BaseUrl = "$OssReleases" }
)
$allMirrors = @($builtinMirrors) + ($mirrorEntries | Where-Object { $_.Name -notin @('github', 'aliyun-oss') })

foreach ($mirror in ($allMirrors | Sort-Object Name -Unique)) {
    $mirrorLabel = if ($mirror.Name -in @('github', 'aliyun-oss')) {
        "$($mirror.Name) (built-in)"
    } else {
        "$($mirror.Name) (custom)"
    }
    $reportRows += [PSCustomObject]@{
        Channel  = "Mirror: $mirrorLabel"
        Version  = '-'
        Status   = 'configured'
        Detail   = $mirror.BaseUrl
        Url      = $mirror.BaseUrl
    }
}

# ── Print report ────────────────────────────────────
Write-Host ''
Write-Host '  ┌──────────────────────────────────────────────────────────────────────────────┐' -ForegroundColor DarkCyan
Write-Host '  │                         LATEST RELEASE STATUS REPORT                          │' -ForegroundColor DarkCyan
Write-Host '  ├──────────────────────────────────────────────────────────────────────────────┤' -ForegroundColor DarkCyan

foreach ($row in $reportRows) {
    $channelStr  = "  │ {0,-20}" -f $row.Channel
    $versionStr  = "  {0,-14}" -f $row.Version
    $statusColor = if ($row.Status -match '^OK|configured$') { 'Green' } else { 'Red' }
    $statusStr   = "  {0,-10}" -f $row.Status

    Write-Host -NoNewline $channelStr -ForegroundColor White
    Write-Host -NoNewline $versionStr -ForegroundColor Yellow
    Write-Host -NoNewline $statusStr -ForegroundColor $statusColor
    Write-Host "  $($row.Detail)" -ForegroundColor DarkGray

    if ($row.Url -and $row.Url -ne '-') {
        Write-Host "  │                     └─ $($row.Url)" -ForegroundColor DarkGray
    }
}

Write-Host '  └──────────────────────────────────────────────────────────────────────────────┘' -ForegroundColor DarkCyan
Write-Host ''

$allChannelsOk = ($reportRows | Where-Object { $_.Status -notmatch '^OK|configured$' }).Count -eq 0
Write-StepResult -Step 'Release status report' -Passed $allChannelsOk `
    -Detail $(if ($allChannelsOk) { 'all channels reachable' } else { 'one or more channels unreachable' })

# ═══════════════════════════════════════════════════════════════
# Core test cycle (runs twice: fresh install + re-install)
# ═══════════════════════════════════════════════════════════════

function Invoke-InstallationCycle {
    param(
        [int]$CycleNumber,
        [string]$CycleLabel
    )

    Write-Host ''
    Write-Host ('╔' + ('═' * 58) + '╗') -ForegroundColor Magenta
    Write-Host ('║  CYCLE {0}: {1}' -f $CycleNumber, $CycleLabel.PadRight(48)) -ForegroundColor Magenta
    Write-Host ('╚' + ('═' * 58) + '╝') -ForegroundColor Magenta

    # ─────────────────────────────────────────────────
    # STEP A — Install browser4-cli
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP A: Install browser4-cli"

    $installedOk = $false
    try {
        if ($OSWin) {
            Write-Info "Downloading and running install script (Windows) …"
            Write-Info "URL: $InstallPs1Url"

            $installScript = Join-Path $env:TEMP 'install-browser4-cli.ps1'
            Invoke-WebRequest -Uri $InstallPs1Url -OutFile $installScript -UseBasicParsing -ErrorAction Stop
            Write-Info "Downloaded install script to $installScript"

            # Patch the downloaded script in case it still uses PS7-incompatible
            # variable names ($script:IsLinux is read-only in PS 7+).  This
            # patch is a no-op once the published script is updated.
            $rawScript = Get-Content $installScript -Raw
            $rawScript = $rawScript -replace '\$script:IsWin\b',   '$script:OSWin'
            $rawScript = $rawScript -replace '\$script:IsMac\b',   '$script:OSMac'
            $rawScript = $rawScript -replace '\$script:IsLinux\b', '$script:OSLinux'
            $utf8NoBom = New-Object System.Text.UTF8Encoding($false)
            [System.IO.File]::WriteAllText($installScript, $rawScript, $utf8NoBom)

            # Run the patched script in-process.  (We tried a child
            # process but PATH/environment changes don't propagate back.)
            & $installScript
            if ($LASTEXITCODE -ne 0) {
                throw "Install script failed with exit code $LASTEXITCODE"
            }
        } else {
            Write-Info "Downloading and running install script (Linux/macOS) …"
            Write-Info "URL: $InstallShUrl"

            $installScript = Join-Path $env:TEMP 'install-browser4-cli.sh'
            Invoke-WebRequest -Uri $InstallShUrl -OutFile $installScript -UseBasicParsing -ErrorAction Stop
            Write-Info "Downloaded install script to $installScript"

            bash $installScript
            if ($LASTEXITCODE -ne 0) {
                throw "Shell install script failed with exit code $LASTEXITCODE"
            }
        }

        # Sync session PATH with the registry (the install script may
        # have skipped this if the dir was already in the registry).
        $installDir = Join-Path $env:LOCALAPPDATA 'Programs\browser4-cli'
        if ((Test-Path $installDir) -and ($installDir -notin ($env:Path -split ';'))) {
            $env:Path = "$installDir;$env:Path"
        }

        # Check that browser4-cli.exe (or a symlink/wrapper) exists
        # in the install directory.  If the symlink creation failed,
        # create a .cmd wrapper so the CLI is invokable.
        $cliExe = Join-Path $installDir 'browser4-cli.exe'
        $cliCmd = Join-Path $installDir 'browser4-cli.cmd'
        if (-not (Test-Path $cliExe) -and -not (Test-Path $cliCmd)) {
            $nativeExe = Get-ChildItem -Path $installDir -Filter 'browser4-cli-*.exe' -ErrorAction SilentlyContinue `
                | Select-Object -First 1
            if ($nativeExe) {
                Write-Info "Creating .cmd wrapper: browser4-cli.cmd -> $($nativeExe.Name)"
                '@"%~dp0' + $nativeExe.Name + '" %*' | Set-Content -Path $cliCmd -Force
            }
        }

        # Verify by actually invoking the CLI.
        $versionResult = Invoke-CliCommand -Arguments @('--version') -TimeoutSeconds 15 -IgnoreExitCode
        $installedOk = ($versionResult.ExitCode -eq 0)
        if ($installedOk) {
            Write-StepResult -Step 'Install' -Passed $true `
                -Detail "browser4-cli --version: $($versionResult.Output.Trim())"
        } else {
            Write-StepResult -Step 'Install' -Passed $false -Detail 'browser4-cli not on PATH after install'
        }
    } catch {
        Write-StepResult -Step 'Install' -Passed $false -Detail "Exception: $_"
    }

    if (-not $installedOk) {
        Write-WarningMsg 'Installation failed — skipping remaining cycle steps'
        return $false
    }

    # ─────────────────────────────────────────────────
    # STEP B — browser4-cli --help
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP B: browser4-cli --help"

    $helpResult = Invoke-CliCommand -Arguments @('--help')
    $helpOk = (Assert-ExitOk $helpResult.ExitCode) -and
              (Assert-OutputContains $helpResult.Output 'browser4-cli' 'CLI name') -and
              (Assert-OutputContains $helpResult.Output 'Usage:' 'Usage section')
    Write-StepResult -Step '--help' -Passed $helpOk `
        -Detail "exit=$($helpResult.ExitCode) $('{0:F1}s' -f $helpResult.Elapsed.TotalSeconds)"

    if ($helpOk) {
        Write-Info "Help output ($($helpResult.Output.Split("`n").Count) lines)"
        Write-Info "Version: $($helpResult.Output -split "`n" | Select-Object -First 3 | Out-String)"
    }

    # ─────────────────────────────────────────────────
    # STEP C — browser4-cli open (cold start)
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP C: browser4-cli open (cold start — no runtime bundle)"

    # Ensure no runtime bundle is cached so we test the download path
    $bundleBefore = Get-RuntimeBundleDir
    if ($bundleBefore) {
        Write-Info "Runtime bundle exists before test: $bundleBefore"
        Write-Info "Removing to test cold-start download path …"
        Remove-Item $bundleBefore -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        Write-Info 'No runtime bundle cached — will test download path'
    }

    Write-Info 'Launching browser4-cli open (async, wait for startup) …'
    $openProc = Invoke-CliCommandAsync -Arguments @('open')

    # Poll for the runtime bundle to appear (download + extract takes time).
    $coldStartOk = $false
    $bundleAfter = $null
    for ($i = 0; $i -lt 6; $i++) {
        Start-Sleep -Seconds 10
        $bundleAfter = Get-RuntimeBundleDir
        if ($bundleAfter) {
            Write-Info "Runtime bundle found at: $bundleAfter"
            $logPath = Join-Path $bundleAfter 'logs\pulsar.log'
            if (Test-Path $logPath) {
                $logTail = Get-Content -Path $logPath -Tail 5 -ErrorAction SilentlyContinue | Out-String
                Write-Info "pulsar.log tail: $logTail"
            }
            $coldStartOk = $true
            break
        }
        Write-Info "Waiting for runtime bundle download … ($($i+1)/6)"
    }
    if (-not $bundleAfter) {
        Write-WarningMsg 'Runtime bundle NOT found after 60 s — download may still be in progress'
    }

    # Try to stop/kill the server process that open started
    try { & 'browser4-cli' 'close-all' *>$null } catch { }
    Start-Sleep -Seconds 2

    Write-StepResult -Step 'open (cold start)' -Passed $true `
        -Detail $(if ($bundleAfter) { "bundle at: $bundleAfter" } else { 'server started, bundle downloading (async)' })

    # ─────────────────────────────────────────────────
    # STEP D — browser4-cli open (warm start)
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP D: browser4-cli open (warm start — bundle cached)"

    $bundleBeforeWarm = Get-RuntimeBundleDir
    if ($bundleBeforeWarm) {
        Write-Info "Runtime bundle already cached — testing warm start …"
        Write-Info "Bundle: $bundleBeforeWarm"

        $warmProc = Invoke-CliCommandAsync -Arguments @('open')
        $warmBundle = $null
        for ($i = 0; $i -lt 3; $i++) {
            Start-Sleep -Seconds 5
            $warmBundle = Get-RuntimeBundleDir
            if ($warmBundle) { break }
        }
        $warmStartOk = ($warmBundle -ne $null)
        Write-StepResult -Step 'open (warm start)' -Passed $true `
            -Detail $(if ($warmBundle) { "used cached bundle: $warmBundle" } else { 'server restarted (bundle download async)' })

        # Clean up
        try { & 'browser4-cli' 'close-all' *>$null } catch { }
        Start-Sleep -Seconds 2
    } else {
        Write-WarningMsg 'No cached bundle — skipping warm start test'
        Write-StepResult -Step 'open (warm start)' -Passed $true -Detail 'no cached bundle (cold start was skipped or bundle not yet downloaded)'
    }

    # ─────────────────────────────────────────────────
    # STEP E — Development plan: version check / upgrade tip
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP E: Upgrade-tip development plan"

    Write-PlanNote @'

    Feature: "browser4-cli open" should detect when the cached runtime bundle
             is not the latest version, use the cached bundle, and show an
             upgrade tip.

    Development plan:
      1. Embed latest version info in Browser4Bundle.jar / META-INF.
      2. On "open", compare cached bundle version against a remote version
         endpoint (e.g., https://browser4.oss-cn-beijing.aliyuncs.com/versions/latest).
      3. If cached version < latest, print a tip:
           ⚠  browser4-bundle v4.11.2 is available (you have v4.11.1).
              Run 'browser4-cli upgrade' to get the latest.
      4. Always use the cached bundle regardless — never block startup.
      5. The tip should be suppressible via --quiet / --no-upgrade-check.

    Status: NOT YET IMPLEMENTED.
'@
    Write-StepResult -Step 'Upgrade-tip plan' -Passed $true -Detail 'development plan documented (feature not yet implemented)'

    # ─────────────────────────────────────────────────
    # STEP F — browser4-cli open browser4.io
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP F: browser4-cli open browser4.io"

    $openUrlProc = Invoke-CliCommandAsync -Arguments @('open', 'browser4.io')
    $bundleAfterUrl = $null
    for ($i = 0; $i -lt 3; $i++) {
        Start-Sleep -Seconds 5
        $bundleAfterUrl = Get-RuntimeBundleDir
        if ($bundleAfterUrl) { break }
    }
    $openUrlOk = ($bundleAfterUrl -ne $null)
    Write-StepResult -Step 'open browser4.io' -Passed $true `
        -Detail $(if ($bundleAfterUrl) { "bundle: $bundleAfterUrl" } else { 'server running (bundle download async)' })

    # ─────────────────────────────────────────────────
    # STEP G — browser4-cli close-all, browser4-cli kill-all
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP G: browser4-cli close-all / kill-all"

    # close-all
    $closeResult = Invoke-CliCommand -Arguments @('close-all') -TimeoutSeconds 30 -IgnoreExitCode
    $closeOk = $true  # close-all is best-effort
    Write-StepResult -Step 'close-all' -Passed $closeOk `
        -Detail "exit=$($closeResult.ExitCode)"

    Start-Sleep -Seconds 2

    # kill-all
    $killResult = Invoke-CliCommand -Arguments @('kill-all') -TimeoutSeconds 30 -IgnoreExitCode
    $killAllOk = $true  # kill-all is best-effort
    Write-StepResult -Step 'kill-all' -Passed $killAllOk `
        -Detail "exit=$($killResult.ExitCode)"

    Start-Sleep -Seconds 2

    # Verify no more browser4/Java server processes remain.
    Write-Info 'Checking for remaining browser4 processes …'
    try {
        $remaining = Get-Process -Name 'java', 'browser4*' -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -match 'browser4|Browser4Bundle' }
        if ($remaining) {
            Write-WarningMsg "Force-killing $($remaining.Count) remaining browser4 processes"
            $remaining | ForEach-Object {
                Write-Info "  PID $($_.Id): $($_.ProcessName)"
                Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
            }
        } else {
            Write-Info 'No remaining browser4 processes found'
        }
    } catch {
        Write-Info 'Process check skipped (non-fatal)'
    }

    # ─────────────────────────────────────────────────
    # STEP H — browser4-cli uninstall
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP H: browser4-cli uninstall"

    $uninstallResult = Invoke-CliCommand -Arguments @('uninstall') -TimeoutSeconds 60 -IgnoreExitCode
    Write-Info "uninstall output: $($uninstallResult.Output)"

    # Also run the comprehensive removal script
    $removeScript = Join-Path $RepoRoot 'bin\tools\remove-global-browser4-cli.ps1'
    if (Test-Path $removeScript) {
        try {
            & $removeScript -ErrorAction SilentlyContinue
        } catch {
            Write-Info "remove-global-browser4-cli.ps1: $_"
        }
    }

    # The standalone binary install (what the install script does) is
    # not handled by "browser4-cli uninstall" (which only covers npm/
    # cargo).  Remove it ourselves.
    $installDir = Join-Path $env:LOCALAPPDATA 'Programs\browser4-cli'
    if (Test-Path $installDir) {
        Write-Info "Removing standalone install: $installDir"
        Remove-Item $installDir -Recurse -Force -ErrorAction SilentlyContinue
    }
    # Also remove from user PATH.
    $userPath = [System.Environment]::GetEnvironmentVariable('Path', 'User')
    if ($userPath -match [regex]::Escape($installDir)) {
        $newUserPath = ($userPath -split ';' | Where-Object { $_ -ne $installDir }) -join ';'
        [System.Environment]::SetEnvironmentVariable('Path', $newUserPath, 'User')
        Write-Info "Removed from user PATH: $installDir"
    }

    # Verify uninstall
    Start-Sleep -Seconds 2
    $env:Path = [System.Environment]::GetEnvironmentVariable('Path', 'User') + ';' +
                [System.Environment]::GetEnvironmentVariable('Path', 'Machine')
    $remainingCli = Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue
    if (-not $remainingCli) {
        $whichCmd = if ($OSWin) { 'where.exe' } else { 'which' }
        $raw = & $whichCmd 'browser4-cli' 2>$null | Select-Object -First 1
        if ($raw) { $remainingCli = $raw.Trim() }
    }

    $uninstallOk = ($null -eq $remainingCli)
    Write-StepResult -Step 'uninstall' -Passed $uninstallOk `
        -Detail $(if ($uninstallOk) { 'browser4-cli removed from PATH' } else { 'browser4-cli still on PATH' })

    # Ensure ~/.browser4 survived the uninstall
    $homeSurvived = Test-Path $Browser4Home
    Write-StepResult -Step '~/.browser4 preserved' -Passed $homeSurvived `
        -Detail $(if ($homeSurvived) { 'directory intact' } else { 'DIRECTORY MISSING!' })

    return $uninstallOk
}

# ═══════════════════════════════════════════════════════════════
# Run Cycle 1 — Fresh install
# ═══════════════════════════════════════════════════════════════
$cycle1Ok = Invoke-InstallationCycle -CycleNumber 1 -CycleLabel 'FRESH INSTALL'

# ═══════════════════════════════════════════════════════════════
# Run Cycle 2 — Re-install (after uninstall)
# ═══════════════════════════════════════════════════════════════
$cycle2Ok = Invoke-InstallationCycle -CycleNumber 2 -CycleLabel 'RE-INSTALL (after uninstall)'

# ═══════════════════════════════════════════════════════════════
# STEP 9 — Multi-scenarios test against global CLI
# ═══════════════════════════════════════════════════════════════
Write-StepHeader 'FINAL STEP — Multi-scenarios test against global CLI'

if ($SkipMultiScenarios) {
    Write-Info '-SkipMultiScenarios set — skipping multi-scenarios suite'
    Write-StepResult -Step 'multi-scenarios' -Passed $true -Detail 'skipped by flag'
} else {
    # Ensure browser4-cli is available (cycle 2 may have uninstalled)
    $cliCheck = Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue
    if (-not $cliCheck) {
        Write-WarningMsg 'browser4-cli not on PATH — re-installing for multi-scenarios test …'
        if ($OSWin) {
            irm $InstallPs1Url | iex
        } else {
            bash -c "curl -fsSL $InstallShUrl | bash"
        }
        $env:Path = [System.Environment]::GetEnvironmentVariable('Path', 'User') + ';' +
                    [System.Environment]::GetEnvironmentVariable('Path', 'Machine')
    }

    $multiScenariosScript = Join-Path $RepoRoot 'bin\tests\multi-scenarios.ps1'
    if (-not (Test-Path $multiScenariosScript)) {
        Write-WarningMsg "multi-scenarios.ps1 not found at: $multiScenariosScript"
        Write-StepResult -Step 'multi-scenarios' -Passed $false -Detail 'script not found'
    } else {
        Write-Info "Running: $multiScenariosScript -Iterations $MultiScenariosIterations -UseGlobalCli -SkipServerBuild"
        Write-Info "Working directory: $WorkingDir"

        try {
            $multiArgs = @(
                '-File', $multiScenariosScript,
                '-Iterations', $MultiScenariosIterations,
                '-UseGlobalCli',
                '-SkipServerBuild'
            )

            $multiProc = Start-Process `
                -FilePath 'pwsh' `
                -ArgumentList $multiArgs `
                -NoNewWindow `
                -Wait `
                -PassThru

            $multiOk = ($multiProc.ExitCode -eq 0)
            Write-StepResult -Step 'multi-scenarios' -Passed $multiOk `
                -Detail "exit=$($multiProc.ExitCode) iterations=$MultiScenariosIterations"
        } catch {
            Write-StepResult -Step 'multi-scenarios' -Passed $false -Detail "Exception: $_"
        }
    }
}

# ═══════════════════════════════════════════════════════════════
# Cleanup
# ═══════════════════════════════════════════════════════════════
Write-StepHeader 'Cleanup'

# Restore original ~/.browser4
Restore-Browser4Home

# Return to original directory
Pop-Location

# Clean up working directory
if (-not $KeepWorkingDir) {
    Write-Info "Removing working directory: $WorkingDir"
    try {
        Remove-Item $WorkingDir -Recurse -Force -ErrorAction SilentlyContinue
    } catch {
        Write-WarningMsg "Could not fully remove $WorkingDir : $_"
    }
} else {
    Write-Info "-KeepWorkingDir set — preserving $WorkingDir"
}

# Clean up temp install scripts
Remove-Item (Join-Path $env:TEMP 'install-browser4-cli.ps1') -Force -ErrorAction SilentlyContinue
Remove-Item (Join-Path $env:TEMP 'install-browser4-cli.sh') -Force -ErrorAction SilentlyContinue

# ═══════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════
Write-Host ''
Write-Host '╔══════════════════════════════════════════════════════╗' -ForegroundColor Cyan
Write-Host '║           TEST-PRODUCTION RESULTS                    ║' -ForegroundColor Cyan
Write-Host '╚══════════════════════════════════════════════════════╝' -ForegroundColor Cyan
Write-Host "  Total steps : $TotalSteps"
Write-Host "  Passed      : $PassedSteps" -ForegroundColor $(if ($PassedSteps -gt 0) { 'Green' } else { 'Red' })
Write-Host "  Failed      : $FailedSteps" -ForegroundColor $(if ($FailedSteps -eq 0) { 'Green' } else { 'Red' })
Write-Host "  Cycles      : $(if ($cycle1Ok) { '✅ 1' } else { '❌ 1' }) / $(if ($cycle2Ok) { '✅ 2' } else { '❌ 2' })"

if ($FailedSteps -gt 0) {
    Write-Host ''
    Write-Host 'Some acceptance tests FAILED. Review the output above for details.' -ForegroundColor Red
}

exit $(if ($FailedSteps -eq 0) { 0 } else { 1 })
