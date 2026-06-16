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
    Acceptance test for the latest production release of browser4-cli.

.DESCRIPTION
    Downloads, installs, exercises, uninstalls, and re-installs the global
    browser4-cli from the public OSS distribution channel, then runs the
    multi-scenario stress suite against the global CLI.

    The script is designed to be run in CI or locally before tagging a release.
    It simulates a real end user's journey:

      1. Create a random working directory under ${system_temp_dir}/.browser4-acceptance.
      2. Clean any pre-existing global installation.
      3. Install the latest browser4-cli via the remote bootstrap script (as-is, no patching).
      4. Verify the CLI is on PATH after install (no manual fixups — fails if install script is broken).
      5. Smoke-test the CLI (--help, --version, config --help, agent-run --help, invalid command).
      6. Cold-start the browser server (browser4-cli open), verify server responds to health checks.
      7. Measure warm-start latency and compare against cold-start.
      8. Clean up server processes (close-all, kill-all), verify server is no longer reachable.
      9. Uninstall and verify runtime data / caches are removed.
     10. Repeat the install cycle to verify idempotency.
     11. Run multi-scenarios.ps1 against the global CLI with captured output.

    KEY PRINCIPLE: This test acts like a real end user. It does NOT patch the
    install script, create missing symlinks, or manually clean up after uninstall.
    If any of those are needed, the test FAILS — because a real user would hit
    the same broken behavior.

.PARAMETER WorkingDir
    Working directory for temporary artifacts.
    Default: a random subdirectory under the system temp directory
    (e.g. /tmp/.browser4-acceptance/20260611-143052-a3f2 on Unix,
    %TEMP%\.browser4-acceptance\20260611-143052-a3f2 on Windows).

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

# Resolve the repo root for informational purposes only — do NOT require it.
# This script is designed to run from any location with a globally-installed
# browser4-cli.  Sibling scripts are resolved relative to $ScriptDir, and
# remote fallbacks are used when they are missing.
$RepoRoot = if (Test-Path (Join-Path $ScriptDir '..\pom.xml')) {
    Resolve-Path (Join-Path $ScriptDir '..')
} else {
    $null
}

# ─────────────────────────────────────────────────────
# Resolve working directory — default to a random
# subdirectory under the system temp dir so each
# run is isolated without the caller needing to supply
# a unique path.
# ─────────────────────────────────────────────────────
if (-not $WorkingDir) {
    $acceptanceRoot = Join-Path ([System.IO.Path]::GetTempPath()) '.browser4-acceptance'
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
# Platform-specific paths
# $env:TEMP / $env:LOCALAPPDATA / $env:APPDATA are
# Windows concepts and may be $null on Linux/macOS.
# Resolve them once at the top so Join-Path never
# receives a null Path argument.
# ─────────────────────────────────────────────────────
$script:TempDir = if ($env:TEMP) {
    $env:TEMP
} elseif ($env:TMPDIR) {
    $env:TMPDIR
} else {
    '/tmp'
}
$script:LocalAppData = if ($env:LOCALAPPDATA) {
    $env:LOCALAPPDATA
} else {
    Join-Path $env:HOME '.local'
}
$script:AppData = if ($env:APPDATA) {
    $env:APPDATA
} else {
    $env:HOME  # ~/.browser4 lives under HOME on Linux/macOS
}

# ─────────────────────────────────────────────────────
# Constants
# ─────────────────────────────────────────────────────
$InstallPs1Url = 'https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1'
$InstallShUrl  = 'https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh'
$Browser4Home  = if ($OSWin) { Join-Path $env:USERPROFILE '.browser4' } else { Join-Path $env:HOME '.browser4' }
$ServerBaseUrl = 'http://localhost:8182'
$ServerHealthUrl = "$ServerBaseUrl/actuator/health"

# Runtime data directory (what uninstall should remove)
$RuntimeDataDir = if ($OSWin) {
    Join-Path $AppData 'browser4'
} elseif ($OSMac) {
    Join-Path $env:HOME 'Library/Application Support/browser4'
} else {
    # $XDG_DATA_HOME/browser4 or ~/.local/share/browser4
    if ($env:XDG_DATA_HOME) {
        Join-Path $env:XDG_DATA_HOME 'browser4'
    } else {
        Join-Path $env:HOME '.local/share/browser4'
    }
}

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
        # Resolve the executable path.  Start-Process handles .cmd wrappers
        # and .exe files equally well when given the full path.
        $exe = (Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
        if (-not $exe) { $exe = 'browser4-cli' }

        $tmpOut = Join-Path $TempDir 'b4cli-stdout.txt'
        $tmpErr = Join-Path $TempDir 'b4cli-stderr.txt'
        Remove-Item $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue

        $proc = Start-Process `
            -FilePath $exe `
            -ArgumentList $Arguments `
            -NoNewWindow `
            -PassThru `
            -RedirectStandardInput $(if ($script:OSWin) { 'NUL' } else { '/dev/null' }) `
            -RedirectStandardOutput $tmpOut `
            -RedirectStandardError $tmpErr

        $completed = $proc.WaitForExit($TimeoutSeconds * 1000)
        if (-not $completed) {
            Write-WarningMsg "Command timed out after ${TimeoutSeconds}s — killing process (PID $($proc.Id))"
            $proc.Kill($true) | Out-Null
            $proc.WaitForExit(5000) | Out-Null
        }

        $stdout = Get-Content -Path $tmpOut -Raw -ErrorAction SilentlyContinue
        $stderr = Get-Content -Path $tmpErr -Raw -ErrorAction SilentlyContinue
        Remove-Item $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue

        $combined = (@($stdout, $stderr) | Where-Object { -not [string]::IsNullOrWhiteSpace($_) }) -join "`n"

        $sw.Stop()
        return [PSCustomObject]@{
            ExitCode = if ($completed) { [int]$proc.ExitCode } else { -1 }
            Output   = $combined.Trim()
            Stdout   = if ($stdout) { $stdout.Trim() } else { '' }
            Stderr   = if ($stderr) { $stderr.Trim() } else { '' }
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
    # Use Select-Object -First 1 in case multiple browser4-cli
    # commands exist on PATH (e.g. npm version + standalone binary).
    $exe = (Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1).Source
    if (-not $exe) { $exe = 'browser4-cli' }
    $proc = Start-Process `
        -FilePath $exe `
        -ArgumentList $Arguments `
        -NoNewWindow `
        -PassThru `
        -RedirectStandardInput $(if ($script:OSWin) { 'NUL' } else { '/dev/null' }) `
        -RedirectStandardOutput (Join-Path $TempDir 'b4cli-async-stdout.txt') `
        -RedirectStandardError (Join-Path $TempDir 'b4cli-async-stderr.txt')

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
    $stdout = Get-Content -Path (Join-Path $TempDir 'b4cli-async-stdout.txt') -Raw -ErrorAction SilentlyContinue
    $stderr = Get-Content -Path (Join-Path $TempDir 'b4cli-async-stderr.txt') -Raw -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $TempDir 'b4cli-async-stdout.txt'), (Join-Path $TempDir 'b4cli-async-stderr.txt') -Force -ErrorAction SilentlyContinue

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
        $searchRoots += Join-Path $AppData 'browser4'
        $searchRoots += Join-Path $LocalAppData 'browser4'
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
# NEW: Health-check-based server readiness
# ═══════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Poll the Browser4 server health endpoint until it responds or times out.

.DESCRIPTION
    Sends GET requests to $ServerHealthUrl (/actuator/health) with exponential
    backoff.  Returns an object with .Healthy (bool) and .Elapsed (TimeSpan).

    This replaces brittle Start-Sleep-based polling with a real readiness
    check — exactly what the CLI itself and Docker healthcheck use.
#>
function Wait-ServerHealthy {
    param(
        [string]$BaseUrl = $ServerBaseUrl,
        [int]$TimeoutSeconds = 120
    )
    $healthUrl = "$BaseUrl/actuator/health"
    $deadline = [DateTime]::UtcNow.AddSeconds($TimeoutSeconds)
    $sleep = 1
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $lastStatus = ''

    while (([DateTime]::UtcNow) -lt $deadline) {
        try {
            # Invoke-RestMethod parses the JSON response directly into a
            # PSCustomObject — avoids the byte[]-vs-string inconsistency
            # that Invoke-WebRequest + ConvertFrom-Json can hit when the
            # server returns a compressed or chunked response on Windows.
            $healthData = Invoke-RestMethod -Uri $healthUrl -TimeoutSec 5 -ErrorAction Stop

            if ($healthData.status -eq 'UP') {
                $sw.Stop()
                return [PSCustomObject]@{
                    Healthy  = $true
                    Elapsed  = $sw.Elapsed
                    Content  = ($healthData | ConvertTo-Json -Compress)
                }
            }

            # Status field present but not UP (e.g. DOWN, OUT_OF_SERVICE)
            $lastStatus = $healthData.status
            Write-Info "Health status=$lastStatus (waiting for UP)"
        } catch {
            # Server not reachable, non-2xx, or invalid JSON —
            # all expected during startup.  Log the first few times
            # so the operator can see progress, then go quiet.
            if ($sleep -le 4) {
                Write-Info "Health endpoint not ready: $_"
            }
        }

        $remaining = ($deadline - [DateTime]::UtcNow).TotalSeconds
        if ($remaining -le 0) { break }
        $sleep = [Math]::Min($sleep * 1.5, 15)
        Start-Sleep -Seconds ([Math]::Min($sleep, $remaining))
    }

    $sw.Stop()
    if ($lastStatus) {
        Write-WarningMsg "Health check timed out. Last status: $lastStatus"
    }
    return [PSCustomObject]@{
        Healthy  = $false
        Elapsed  = $sw.Elapsed
        Content  = ''
    }
}

# ═══════════════════════════════════════════════════════════════
# NEW: Install from remote bootstrap script (no patching)
# ═══════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Download and execute the remote install script AS-IS.

.DESCRIPTION
    Downloads the platform-appropriate install script and runs it without
    any modifications.  If the published script has bugs (e.g. PS7-incompatible
    variable names), this function will surface them by returning $false —
    just as a real user would encounter them.

    This replaces the old inline install logic that patched the downloaded
    script, created missing .cmd wrappers, and manually created symlinks.
#>
function Invoke-InstallFromRemoteScript {
    Write-Info 'Downloading and executing remote install script (unmodified) ...'

    try {
        if ($OSWin) {
            Write-Info "URL: $InstallPs1Url"
            $installScript = Join-Path $TempDir 'install-browser4-cli.ps1'
            Invoke-WebRequest -Uri $InstallPs1Url -OutFile $installScript -UseBasicParsing -ErrorAction Stop
            Write-Info "Downloaded install script to $installScript"

            # Run the script AS-IS.  No variable-name patches, no workarounds.
            # If the published script is broken, the test MUST fail.
            & $installScript
            $exitCode = $LASTEXITCODE
        } else {
            Write-Info "URL: $InstallShUrl"
            $installScript = Join-Path $TempDir 'install-browser4-cli.sh'
            Invoke-WebRequest -Uri $InstallShUrl -OutFile $installScript -UseBasicParsing -ErrorAction Stop
            Write-Info "Downloaded install script to $installScript"

            bash $installScript
            $exitCode = $LASTEXITCODE
        }

        if ($exitCode -ne 0) {
            Write-WarningMsg "Install script exited with code $exitCode"
            return $false
        }

        Write-Info 'Install script completed successfully'
        return $true
    } catch {
        Write-WarningMsg "Install script download/execution failed: $_"
        return $false
    }
}

# ═══════════════════════════════════════════════════════════════
# NEW: Refresh session PATH from system state
# ═══════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Reload PATH for the current session from persistent system state.

.DESCRIPTION
    After the install script modifies persistent PATH (registry on Windows,
    shell rc files on Linux/macOS), the current session may not see the
    change.  This function reads the persistent state into the session.

    Unlike the old script, this does NOT create missing symlinks, .cmd
    wrappers, or directories — it only refreshes PATH.  If the binary is
    missing after this, the test will fail at the verification step.
#>
function Update-SessionPath {
    if ($OSWin) {
        $userPath   = [System.Environment]::GetEnvironmentVariable('Path', 'User')
        $machinePath = [System.Environment]::GetEnvironmentVariable('Path', 'Machine')
        $env:Path = (@($userPath, $machinePath) | Where-Object { $_ }) -join [System.IO.Path]::PathSeparator
        Write-Info 'Session PATH refreshed from registry (User + Machine)'
    } else {
        # ~/.local/bin is the default install location on Linux/macOS.
        # Many distros already include it in PATH via .profile; if not,
        # add it so the test can proceed.
        $localBin = Join-Path $env:HOME '.local/bin'
        $pathEntries = $env:Path -split [System.IO.Path]::PathSeparator
        if ((Test-Path $localBin) -and ($localBin -notin $pathEntries)) {
            $env:Path = "$localBin$([System.IO.Path]::PathSeparator)$env:Path"
            Write-Info "Added ~/.local/bin to session PATH"
        }
    }
}

# ═══════════════════════════════════════════════════════════════
# NEW: Resolve the CLI binary path
# ═══════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Find the browser4-cli executable on PATH.

.DESCRIPTION
    Returns the full path to the browser4-cli executable, or $null if
    it cannot be found.  Does NOT create any files or symlinks.
#>
function Resolve-CliPath {
    $cmd = Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue | Select-Object -First 1
    if ($cmd) {
        if ($cmd -is [string]) { return $cmd } else { return $cmd.Source }
    }
    # Fallback: try which/where.exe
    $whichCmd = if ($OSWin) { 'where.exe' } else { 'which' }
    $raw = & $whichCmd 'browser4-cli' 2>$null | Select-Object -First 1
    if ($raw) { return $raw.Trim() }
    return $null
}

# ═══════════════════════════════════════════════════════════════
# STEP 0 — Setup working directory
# ═══════════════════════════════════════════════════════════════
Write-StepHeader 'STEP 0 — Setup'

Write-Info "WorkingDir    : $WorkingDir"
Write-Info "Browser4Home  : $Browser4Home"
Write-Info "ServerHealth  : $ServerHealthUrl"
Write-Info "RuntimeData   : $RuntimeDataDir"

if (-not (Test-Path $WorkingDir)) {
    New-Item -ItemType Directory -Path $WorkingDir -Force | Out-Null
    Write-Info "Created working directory"
} else {
    Write-Info 'Working directory already exists'
}
Push-Location $WorkingDir
Write-Info "Pushed to $WorkingDir"

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

# ─────────────────────────────────────────────────────
# Helper: copy config from backup into the current
# ~/.browser4 so the backend server can read config
# files (e.g. LLM keys).  This is needed when testing
# with a configured setup.  For clean-room (new user)
# testing, this function is intentionally NOT called.
# ─────────────────────────────────────────────────────
function Copy-ConfigFromBackup {
    if (-not $browser4HomeBackup -or -not (Test-Path $browser4HomeBackup)) {
        return
    }

    $backupConfig = Join-Path $browser4HomeBackup 'config'
    if (-not (Test-Path $backupConfig)) {
        Write-Info 'No config directory in backup — nothing to copy'
        return
    }

    if (-not (Test-Path $Browser4Home)) {
        New-Item -ItemType Directory -Path $Browser4Home -Force | Out-Null
    }

    $targetConfig = Join-Path $Browser4Home 'config'
    if (Test-Path $targetConfig) {
        Write-Info "Config already exists at $targetConfig — replacing with backup config"
        Remove-Item $targetConfig -Recurse -Force -ErrorAction SilentlyContinue
    }

    Copy-Item -Path $backupConfig -Destination $targetConfig -Recurse -Force
    Write-Info "Copied config from backup ($backupConfig) to $targetConfig"
}

try {

# ─────────────────────────────────────────────────────
# Rename ~/.browser4 out of the way (if it exists) so
# testing starts from a clean slate.  Restore it after
# the test run completes (see Restore-Browser4Home).
# ─────────────────────────────────────────────────────
$browser4HomeBackup = $null
if (Test-Path $Browser4Home) {
    $browser4HomeBackup = "$Browser4Home.backup.$(Get-Date -Format 'yyyyMMdd-HHmmss')"
    Write-Info "Renaming ~/.browser4 → $browser4HomeBackup"
    # On Windows, Move-Item can fail with "being used by another process"
    # when files inside the directory are locked (e.g. by a running server,
    # antivirus, or search indexer).  Fall back to copy+remove, and if even
    # that fails, warn but continue with a clean slate by removing what we can.
    try {
        Move-Item -Path $Browser4Home -Destination $browser4HomeBackup -Force -ErrorAction Stop
    } catch {
        Write-Info "Move-Item failed ($_), falling back to copy+remove"
        Copy-Item -Path $Browser4Home -Destination $browser4HomeBackup -Recurse -Force -ErrorAction SilentlyContinue
        Remove-Item -Path $Browser4Home -Recurse -Force -ErrorAction SilentlyContinue
        if (Test-Path $Browser4Home) {
            Write-WarningMsg "Could not fully remove original ~/.browser4 — some files may be locked. Proceeding anyway."
            # Rename the remnant so the test still starts from a clean slate
            $remnant = "$Browser4Home.remnant.$(Get-Date -Format 'yyyyMMdd-HHmmss')"
            Rename-Item -Path $Browser4Home -NewName (Split-Path $remnant -Leaf) -ErrorAction SilentlyContinue
        }
    }
    Write-Info 'Clean slate: no ~/.browser4 present'
} else {
    Write-Info 'No existing ~/.browser4 — already clean'
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

    $result = Invoke-CliCommand -Arguments @('uninstall', '-y') -IgnoreExitCode
    if ($result.Output -match 'too many arguments') {
        Write-Info "uninstall -y not supported by this binary; retrying without -y …"
        $result = Invoke-CliCommand -Arguments @('uninstall') -IgnoreExitCode
    }
    Write-Info "uninstall output: $($result.Output)"

    # Also use the comprehensive remove script (non-interactive).
    $removeScript = Join-Path $ScriptDir 'tools\remove-global-browser4-cli.ps1'
    if (-not (Test-Path $removeScript) -and $RepoRoot) {
        $removeScript = Join-Path $RepoRoot 'bin\tools\remove-global-browser4-cli.ps1'
    }
    if (Test-Path $removeScript) {
        Write-Info 'Running remove-global-browser4-cli.ps1 for thorough cleanup …'
        try {
            & $removeScript -Confirm:$false -ErrorAction SilentlyContinue
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
# Core test cycle — simulates a real end user's journey
# ═══════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Run one full install→exercise→uninstall cycle.

.DESCRIPTION
    Each cycle simulates what a real user does:
      1. Runs the remote install script (as-is, no patches).
      2. Verifies browser4-cli is on PATH.
      3. Runs --help, --version, and exercises key subcommands.
      4. Tests error handling with an invalid command.
      5. Cold-starts the server (browser4-cli open), verifies it responds
         to health checks.
      6. Optionally measures warm-start latency vs cold-start.
      7. Shuts down the server and verifies it is no longer reachable.
      8. Uninstalls and verifies runtime data is removed.

    When -CopyConfig is passed, pre-existing config (LLM keys, etc.) is
    copied into ~/.browser4 before starting the server.  When omitted,
    the cycle tests the clean-room first-run experience.

    When -MeasureStartupTime is passed, the cycle runs a second `open`
    with the cached bundle and compares startup latencies.
#>
function Invoke-InstallationCycle {
    param(
        [int]$CycleNumber,
        [string]$CycleLabel,
        [switch]$CopyConfig,
        [switch]$MeasureStartupTime
    )

    Write-Host ''
    Write-Host ('╔' + ('═' * 58) + '╗') -ForegroundColor Magenta
    Write-Host ('║  CYCLE {0}: {1}' -f $CycleNumber, $CycleLabel.PadRight(48)) -ForegroundColor Magenta
    Write-Host ('╚' + ('═' * 58) + '╝') -ForegroundColor Magenta

    # ─────────────────────────────────────────────────
    # STEP A — Install browser4-cli from the remote
    #           bootstrap script (NO PATCHING)
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP A: Install browser4-cli from remote script"

    $installedOk = Invoke-InstallFromRemoteScript

    if (-not $installedOk) {
        Write-StepResult -Step 'Install' -Passed $false -Detail 'Remote install script failed — a real user would be stuck here'
        return $false
    }

    # Refresh session PATH so we can find the newly installed binary.
    Update-SessionPath

    # ─────────────────────────────────────────────────
    # STEP B — Verify browser4-cli is on PATH
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP B: Verify browser4-cli on PATH"

    $cliPath = Resolve-CliPath
    if ($cliPath) {
        Write-StepResult -Step 'CLI on PATH' -Passed $true `
            -Detail $cliPath
    } else {
        Write-StepResult -Step 'CLI on PATH' -Passed $false `
            -Detail 'browser4-cli not found on PATH after install — install script may be broken'
        return $false
    }

    # ─────────────────────────────────────────────────
    # STEP C — browser4-cli --help
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP C: browser4-cli --help"

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
    # STEP D — browser4-cli --version
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP D: browser4-cli --version"

    $versionResult = Invoke-CliCommand -Arguments @('--version')
    $versionOk = Assert-ExitOk $versionResult.ExitCode
    Write-StepResult -Step '--version' -Passed $versionOk `
        -Detail $versionResult.Output.Trim()

    # ─────────────────────────────────────────────────
    # STEP E — Invalid command (error handling)
    #           A real user WILL make typos.
    #           The output must be helpful — not a
    #           stack trace or an empty response.
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP E: Invalid command (error handling)"

    $invalidResult = Invoke-CliCommand -Arguments @('--nonexistent-flag-xyz123') -IgnoreExitCode
    $hasOutput = $invalidResult.Output.Length -gt 0
    $hasHelpfulMessage = $invalidResult.Output -match 'error|unknown|usage|help|invalid|unrecognized|try|see|Usage'
    $invalidOk = ($invalidResult.ExitCode -ne 0) -and $hasOutput -and $hasHelpfulMessage

    $invalidDetail = "exit=$($invalidResult.ExitCode) output="
    if ($hasOutput) {
        $invalidDetail += "'$($invalidResult.Output.Substring(0, [Math]::Min(100, $invalidResult.Output.Length)))'"
    } else {
        $invalidDetail += '(empty — bad user experience!)'
    }

    Write-StepResult -Step 'Invalid command' -Passed $invalidOk -Detail $invalidDetail

    # ─────────────────────────────────────────────────
    # STEP F — browser4-cli config --help
    #           Users need to configure API keys.
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP F: browser4-cli config --help"

    $configResult = Invoke-CliCommand -Arguments @('config', '--help') -IgnoreExitCode
    $configOk = ($configResult.ExitCode -eq 0) -and ($configResult.Output.Length -gt 0)
    Write-StepResult -Step 'config --help' -Passed $configOk `
        -Detail "exit=$($configResult.ExitCode)"

    # ─────────────────────────────────────────────────
    # STEP G — browser4-cli agent-run --help
    #           Agent is a headline feature.
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP G: browser4-cli agent-run --help"

    $agentResult = Invoke-CliCommand -Arguments @('agent-run', '--help') -IgnoreExitCode
    $agentOk = ($agentResult.ExitCode -eq 0) -and ($agentResult.Output.Length -gt 0)
    Write-StepResult -Step 'agent-run --help' -Passed $agentOk `
        -Detail "exit=$($agentResult.ExitCode)"

    # ─────────────────────────────────────────────────
    # STEP H — browser4-cli open (cold start)
    #           Verifies the server actually responds
    #           to health checks — not just that a
    #           bundle directory appeared on disk.
    # ─────────────────────────────────────────────────
    $startupLabel = if ($CopyConfig) { 'cold start (with config)' } else { 'cold start (clean-room — no config)' }
    Write-StepHeader "CYCLE $CycleNumber — STEP H: browser4-cli open — $startupLabel"

    if ($CopyConfig) {
        Copy-ConfigFromBackup
    } else {
        Write-Info 'No config copied — testing what a brand-new user experiences on first run'
    }

    # Ensure no runtime bundle is cached so we test the cold-start path
    $bundleBefore = Get-RuntimeBundleDir
    if ($bundleBefore) {
        Write-Info "Runtime bundle exists before test: $bundleBefore"
        Write-Info 'Removing to test cold-start download path …'
        Remove-Item $bundleBefore -Recurse -Force -ErrorAction SilentlyContinue
    } else {
        Write-Info 'No runtime bundle cached — will test download path'
    }

    Write-Info 'Launching browser4-cli open (async) …'
    $coldStartSw = [Diagnostics.Stopwatch]::StartNew()
    $openProc = Invoke-CliCommandAsync -Arguments @('open')

    # Poll the server health endpoint until it responds or we time out.
    Write-Info "Polling $ServerHealthUrl for readiness …"
    $coldHealth = Wait-ServerHealthy -TimeoutSeconds 120
    $coldStartSw.Stop()
    $coldStartTime = $coldStartSw.Elapsed

    $coldStartOk = $coldHealth.Healthy
    if ($coldStartOk) {
        Write-Info "Server healthy after $($coldHealth.Elapsed.TotalSeconds.ToString('F1'))s"
        Write-Info "Total cold-start time (wall clock): $($coldStartTime.TotalSeconds.ToString('F1'))s"

        # Diagnostic: show what the health endpoint returned
        try {
            $healthData = $coldHealth.Content | ConvertFrom-Json
            Write-Info "Health response: status=$($healthData.status)"
        } catch {
            Write-Info "Health response: $($coldHealth.Content)"
        }

        # Show bundle location for diagnostics
        $bundleAfter = Get-RuntimeBundleDir
        if ($bundleAfter) {
            Write-Info "Runtime bundle at: $bundleAfter"
        }
    } else {
        Write-WarningMsg "Server did NOT become healthy within $($coldHealth.Elapsed.TotalSeconds.ToString('F1'))s"
        # Check if a bundle at least appeared (download/extract may have worked)
        $bundleAfter = Get-RuntimeBundleDir
        if ($bundleAfter) {
            Write-Info "Runtime bundle exists at $bundleAfter but server is not responding"
            # Check for server log
            $logPath = Join-Path $bundleAfter 'logs\pulsar.log'
            if (Test-Path $logPath) {
                $logTail = Get-Content -Path $logPath -Tail 20 -ErrorAction SilentlyContinue | Out-String
                Write-WarningMsg "pulsar.log tail:`n$logTail"
            }
        } else {
            Write-WarningMsg 'Runtime bundle also not found — download/extract may have failed'
        }
    }

    Write-StepResult -Step 'open (cold start)' -Passed $coldStartOk `
        -Detail "healthy=$coldStartOk time=$($coldStartTime.TotalSeconds.ToString('F1'))s"

    # ─────────────────────────────────────────────────
    # STEP I — Warm start (only if cold start succeeded
    #           and measurement is requested)
    # ─────────────────────────────────────────────────
    if ($MeasureStartupTime -and $coldStartOk) {
        Write-StepHeader "CYCLE $CycleNumber — STEP I: browser4-cli open (warm start — bundle cached)"

        $bundleCached = Get-RuntimeBundleDir
        if ($bundleCached) {
            Write-Info "Bundle cached at: $bundleCached"
            Write-Info 'Stopping server before warm-start measurement …'

            # Stop the server that cold-start launched
            try { & 'browser4-cli' 'close-all' *>$null } catch {}
            Start-Sleep -Seconds 2
            try { & 'browser4-cli' 'kill-all' *>$null } catch {}
            Start-Sleep -Seconds 3

            # Verify server is down before measuring warm start
            try {
                $checkResp = Invoke-WebRequest -Uri $ServerHealthUrl -TimeoutSec 2 -UseBasicParsing -ErrorAction Stop
                if ($checkResp.StatusCode -eq 200) {
                    Write-WarningMsg 'Server still reachable after kill-all — waiting longer'
                    Start-Sleep -Seconds 10
                }
            } catch {
                Write-Info 'Server confirmed stopped'
            }

            # Measure warm start
            Write-Info 'Launching warm start …'
            $warmStartSw = [Diagnostics.Stopwatch]::StartNew()
            $warmProc = Invoke-CliCommandAsync -Arguments @('open')
            $warmHealth = Wait-ServerHealthy -TimeoutSeconds 60
            $warmStartSw.Stop()
            $warmStartTime = $warmStartSw.Elapsed

            $warmOk = $warmHealth.Healthy
            $coldSec = $coldStartTime.TotalSeconds
            $warmSec = $warmStartTime.TotalSeconds
            $speedup = if ($coldSec -gt 0) { [Math]::Round(($coldSec - $warmSec) / $coldSec * 100) } else { 0 }
            $speedupLabel = if ($speedup -gt 0) { "${speedup}% faster" } else { 'no speedup' }

            if ($warmOk) {
                Write-Info "Warm start: $($warmSec.ToString('F1'))s (cold was $($coldSec.ToString('F1'))s, $speedupLabel)"
            }

            Write-StepResult -Step 'open (warm start)' -Passed $warmOk `
                -Detail "cold=$($coldSec.ToString('F1'))s warm=$($warmSec.ToString('F1'))s $speedupLabel"
        } else {
            Write-WarningMsg 'No cached bundle — skipping warm start test'
            Write-StepResult -Step 'open (warm start)' -Passed $true -Detail 'skipped (bundle not cached)'
        }
    }

    # ─────────────────────────────────────────────────
    # STEP J — close-all / kill-all + verify shutdown
    # ─────────────────────────────────────────────────
    Write-StepHeader "CYCLE $CycleNumber — STEP J: browser4-cli close-all / kill-all"

    # close-all
    $closeResult = Invoke-CliCommand -Arguments @('close-all') -TimeoutSeconds 30 -IgnoreExitCode
    Write-StepResult -Step 'close-all' -Passed $true `
        -Detail "exit=$($closeResult.ExitCode)"

    Start-Sleep -Seconds 2

    # kill-all
    $killResult = Invoke-CliCommand -Arguments @('kill-all') -TimeoutSeconds 30 -IgnoreExitCode
    Write-StepResult -Step 'kill-all' -Passed $true `
        -Detail "exit=$($killResult.ExitCode)"

    Start-Sleep -Seconds 2

    # Verify the server is no longer reachable.
    # This confirms kill-all actually stopped the server process —
    # a real user expects "kill-all" to mean the server is gone.
    Write-Info "Verifying server is no longer reachable at $ServerHealthUrl ..."
    $serverStillUp = $false
    try {
        $shutdownCheck = Invoke-WebRequest -Uri $ServerHealthUrl -TimeoutSec 3 -UseBasicParsing -ErrorAction Stop
        if ($shutdownCheck.StatusCode -eq 200) {
            $serverStillUp = $true
        }
    } catch {
        # Expected — server should be unreachable
    }

    if ($serverStillUp) {
        Write-WarningMsg 'Server is still reachable after kill-all — force-killing remaining processes'
        try {
            Get-Process -Name 'java' -ErrorAction SilentlyContinue |
                Where-Object { $_.CommandLine -match 'browser4|Browser4Bundle' } |
                ForEach-Object {
                    Write-Info "  Force-killing PID $($_.Id): $($_.ProcessName)"
                    Stop-Process -Id $_.Id -Force -ErrorAction SilentlyContinue
                }
        } catch {
            Write-Info 'Process cleanup skipped (non-fatal)'
        }
        Start-Sleep -Seconds 2
    }

    $shutdownOk = -not $serverStillUp
    Write-StepResult -Step 'Server shutdown' -Passed $shutdownOk `
        -Detail $(if ($shutdownOk) { 'server unreachable (clean shutdown)' } else { 'server still reachable!' })

    # ═══════════════════════════════════════════════════════════════
    # STEP K — browser4-cli uninstall + verify artifacts removed
    # ═══════════════════════════════════════════════════════════════
    Write-StepHeader "CYCLE $CycleNumber — STEP K: browser4-cli uninstall"

    # Record pre-uninstall state so we can verify removal
    $hadRuntimeData = Test-Path $RuntimeDataDir
    Write-Info "Runtime data dir before uninstall: $RuntimeDataDir (exists=$hadRuntimeData)"

    # Run uninstall with --yes for non-interactive mode.
    # Older binaries may not support --yes; fall back to bare uninstall.
    $uninstallResult = Invoke-CliCommand -Arguments @('uninstall', '--yes') -TimeoutSeconds 120 -IgnoreExitCode
    if ($uninstallResult.Output -match 'too many arguments') {
        Write-Info "uninstall --yes not supported by this binary; retrying without --yes …"
        $uninstallResult = Invoke-CliCommand -Arguments @('uninstall') -TimeoutSeconds 120 -IgnoreExitCode
    }
    Write-Info "uninstall output: $($uninstallResult.Output)"

    # Allow the filesystem to settle
    Start-Sleep -Seconds 3

    # Verify: if runtime data existed before uninstall, it should be gone now.
    # We do NOT manually delete it — if uninstall left it behind, that's a bug
    # a real user would encounter.
    $runtimeDataGone = -not (Test-Path $RuntimeDataDir)

    if ($hadRuntimeData -and -not $runtimeDataGone) {
        Write-WarningMsg "Runtime data NOT removed by uninstall: $RuntimeDataDir"
        Write-WarningMsg 'This is a bug — a real user would have leftover files'
    } elseif (-not $hadRuntimeData) {
        Write-Info 'No runtime data existed before uninstall — nothing to verify'
    }

    $uninstallOk = ($uninstallResult.ExitCode -eq 0) -and $runtimeDataGone
    Write-StepResult -Step 'uninstall' -Passed $uninstallOk `
        -Detail $(if ($uninstallOk) {
            'runtime data removed by uninstall'
        } elseif (-not $runtimeDataGone) {
            'FAILED: runtime data not removed — user would have leftover files'
        } else {
            "uninstall exit=$($uninstallResult.ExitCode)"
        })

    # Verify ~/.browser4 (state dir) survived uninstall.
    # The state directory is intentionally preserved so user settings
    # survive an uninstall/reinstall cycle.
    $homeSurvived = Test-Path $Browser4Home
    Write-StepResult -Step '~/.browser4 preserved' -Passed $homeSurvived `
        -Detail $(if ($homeSurvived) { 'state directory intact' } else { 'STATE DIRECTORY MISSING — user settings lost!' })

    return $uninstallOk
}

# ═══════════════════════════════════════════════════════════════
# Run Cycle 1 — Clean-room install (no pre-existing config)
#               Simulates a brand-new user's first experience.
# ═══════════════════════════════════════════════════════════════
$cycle1Ok = Invoke-InstallationCycle -CycleNumber 1 -CycleLabel 'FRESH INSTALL (clean-room)'

# ═══════════════════════════════════════════════════════════════
# Run Cycle 2 — Re-install with config and timing
#               Simulates a returning user who has config set up.
# ═══════════════════════════════════════════════════════════════
$cycle2Ok = Invoke-InstallationCycle -CycleNumber 2 -CycleLabel 'RE-INSTALL (with config + timing)' -CopyConfig -MeasureStartupTime

# ═══════════════════════════════════════════════════════════════
# FINAL STEP — Multi-scenarios test against global CLI
# ═══════════════════════════════════════════════════════════════
Write-StepHeader 'FINAL STEP — Multi-scenarios test against global CLI'

if ($SkipMultiScenarios) {
    Write-Info '-SkipMultiScenarios set — skipping multi-scenarios suite'
    Write-StepResult -Step 'multi-scenarios' -Passed $true -Detail 'skipped by flag'
} else {
    # Ensure browser4-cli is available (cycle 2 may have uninstalled)
    $cliCheck = Resolve-CliPath
    if (-not $cliCheck) {
        Write-WarningMsg 'browser4-cli not on PATH — re-installing for multi-scenarios test …'
        $reinstallOk = Invoke-InstallFromRemoteScript
        if (-not $reinstallOk) {
            Write-StepResult -Step 'multi-scenarios' -Passed $false -Detail 'reinstall failed — cannot run scenarios'
        } else {
            Update-SessionPath
            $cliCheck = Resolve-CliPath
        }
    }

    if ($cliCheck) {
        # Ensure config is available before running multi-scenarios
        Copy-ConfigFromBackup

        $multiScenariosScript = Join-Path $ScriptDir 'tests\multi-scenarios.ps1'
        if (-not (Test-Path $multiScenariosScript) -and $RepoRoot) {
            # Fall back to repo-relative path for bw-compat
            $multiScenariosScript = Join-Path $RepoRoot 'bin\tests\multi-scenarios.ps1'
        }
        if (-not (Test-Path $multiScenariosScript)) {
            Write-WarningMsg "multi-scenarios.ps1 not found at: $multiScenariosScript"
            Write-StepResult -Step 'multi-scenarios' -Passed $false -Detail 'script not found'
        } else {
            Write-Info "Running: $multiScenariosScript -Iterations $MultiScenariosIterations -UseGlobalCli -SkipServerBuild"
            Write-Info "Working directory: $WorkingDir"

            # Capture stdout/stderr so we can show context on failure
            $multiStdout = Join-Path $TempDir 'multi-scenarios-stdout.txt'
            $multiStderr = Join-Path $TempDir 'multi-scenarios-stderr.txt'
            Remove-Item $multiStdout, $multiStderr -Force -ErrorAction SilentlyContinue

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
                    -PassThru `
                    -RedirectStandardInput $(if ($script:OSWin) { 'NUL' } else { '/dev/null' }) `
                    -RedirectStandardOutput $multiStdout `
                    -RedirectStandardError $multiStderr

                $multiOk = ($multiProc.ExitCode -eq 0)

                if (-not $multiOk) {
                    Write-WarningMsg 'Multi-scenarios failed — showing tail of captured output:'
                    Write-WarningMsg '--- STDOUT (last 40 lines) ---'
                    if (Test-Path $multiStdout) {
                        Get-Content -Path $multiStdout -Tail 40 | ForEach-Object { Write-Host "    $_" -ForegroundColor DarkGray }
                    }
                    Write-WarningMsg '--- STDERR (last 20 lines) ---'
                    if (Test-Path $multiStderr) {
                        Get-Content -Path $multiStderr -Tail 20 | ForEach-Object { Write-Host "    $_" -ForegroundColor Red }
                    }
                }

                Write-StepResult -Step 'multi-scenarios' -Passed $multiOk `
                    -Detail "exit=$($multiProc.ExitCode) iterations=$MultiScenariosIterations"
            } catch {
                Write-StepResult -Step 'multi-scenarios' -Passed $false -Detail "Exception: $_"
            } finally {
                Remove-Item $multiStdout, $multiStderr -Force -ErrorAction SilentlyContinue
            }
        }
    }
}

} finally {
    # ═══════════════════════════════════════════════════════════════
    # Cleanup (guaranteed to run even on error)
    # ═══════════════════════════════════════════════════════════════
    Write-StepHeader 'Cleanup'

    # Stop any server processes that are still running
    Write-Info 'Ensuring no server processes remain …'
    try {
        & 'browser4-cli' 'kill-all' *>$null
    } catch {
        # browser4-cli may not be available
    }
    Start-Sleep -Seconds 2

    # Force-kill any remaining Java processes tied to Browser4
    try {
        $remaining = Get-Process -Name 'java' -ErrorAction SilentlyContinue |
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

    # Restore original ~/.browser4
    try { Restore-Browser4Home } catch { Write-WarningMsg "Restore-Browser4Home failed: $_" }

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
    Remove-Item (Join-Path $TempDir 'install-browser4-cli.ps1') -Force -ErrorAction SilentlyContinue
    Remove-Item (Join-Path $TempDir 'install-browser4-cli.sh') -Force -ErrorAction SilentlyContinue
}

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
Write-Host "  Cycle 1     : $(if ($cycle1Ok) { '✅ Clean-room install' } else { '❌ Clean-room install' })"
Write-Host "  Cycle 2     : $(if ($cycle2Ok) { '✅ Re-install + timing' } else { '❌ Re-install + timing' })"

if ($FailedSteps -gt 0) {
    Write-Host ''
    Write-Host 'Some acceptance tests FAILED. Review the output above for details.' -ForegroundColor Red
}

exit $(if ($FailedSteps -eq 0) { 0 } else { 1 })
