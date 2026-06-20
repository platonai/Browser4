# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Use forward slashes in paths where possible.
# - Guard platform-specific commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════════
# MaintenanceUtil.ps1 — Shared utilities for all maintenance scripts
# ═══════════════════════════════════════════════════════════════════
#
# Dot-source this module from any maintenance check script:
#   . "$PSScriptRoot/../common/MaintenanceUtil.ps1"
#
# Exports:
#   New-MaintenanceResult      — Create a standardized result object
#   Add-MaintenanceResult      — Append item-level result to collection
#   Get-RepositoryRoot         — Cached git root resolution
#   Resolve-MaintenancePath    — Resolve path relative to repo root
#   Write-MaintenanceLog       — Structured logging
#   Invoke-MaintenanceStep     — Timing + logging + exit-code wrapper
#   Test-Platform              — Return platform identifier
#   Get-MaintenanceThreshold   — Read threshold with env-var override
#   Test-IsMaintenanceMode     — Check current execution mode
# ═══════════════════════════════════════════════════════════════════

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ── Dot-source shared encoding fix ──
$UtilPath = Join-Path $PSScriptRoot "..\..\common\Util.ps1"
if (Test-Path $UtilPath) {
    . $UtilPath
    Fix-Encoding-UTF8
}

# ═══════════════════════════════════════════════════════════════════
# Module-level state
# ═══════════════════════════════════════════════════════════════════

$_RepoRoot = $null
$_Thresholds = $null

# ═══════════════════════════════════════════════════════════════════
# Platform detection
# ═══════════════════════════════════════════════════════════════════

function Test-Platform {
    <#
    .SYNOPSIS
    Returns the current platform identifier.
    #>
    if ($IsWindows -or ($env:OS -eq "Windows_NT")) {
        return "windows"
    }
    elseif ($IsLinux) {
        return "linux"
    }
    elseif ($IsMacOS) {
        return "macos"
    }
    else {
        return "unknown"
    }
}

function Test-IsWindows { return (Test-Platform) -eq "windows" }
function Test-IsLinux   { return (Test-Platform) -eq "linux" }
function Test-IsMacOS   { return (Test-Platform) -eq "macos" }

# ═══════════════════════════════════════════════════════════════════
# Path resolution
# ═══════════════════════════════════════════════════════════════════

function Get-RepositoryRoot {
    <#
    .SYNOPSIS
    Returns the git repository root. Cached after first call.
    #>
    if ($null -ne $script:_RepoRoot) {
        return $script:_RepoRoot
    }
    try {
        $root = & git rev-parse --show-toplevel 2>$null
        if ($LASTEXITCODE -eq 0 -and $root) {
            $script:_RepoRoot = $root
            return $script:_RepoRoot
        }
    }
    catch {
        # If git is not available, fall back to walking up from this script
        $dir = $PSScriptRoot
        while ($dir -and (Split-Path $dir -Leaf) -ne "bin") {
            $dir = Split-Path $dir -Parent
        }
        if ($dir) {
            $script:_RepoRoot = Split-Path $dir -Parent
            return $script:_RepoRoot
        }
    }
    throw "Cannot determine repository root"
}

function Resolve-MaintenancePath {
    <#
    .SYNOPSIS
    Resolves a path that may be relative to the repository root or absolute.
    #>
    param(
        [string]$Path
    )
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return $Path
    }
    $repoRoot = Get-RepositoryRoot
    return Join-Path $repoRoot $Path
}

# ═══════════════════════════════════════════════════════════════════
# Result object factory
# ═══════════════════════════════════════════════════════════════════

function New-MaintenanceResult {
    <#
    .SYNOPSIS
    Creates a standardized result object for a maintenance check.

    .DESCRIPTION
    All check scripts MUST return this object shape. Reporters consume it.
    Status values: "passed", "failed", "skipped", "error"
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$CheckId,

        [Parameter(Mandatory = $true)]
        [string]$Name,

        [ValidateSet("passed", "failed", "skipped", "error")]
        [string]$Status = "passed",

        [long]$DurationMs = 0,

        [int]$ExitCode = 0,

        [string]$Details = "",

        $Results = @(),

        [string[]]$Artifacts = @()
    )

    $resultsList = New-Object System.Collections.ArrayList

    return [PSCustomObject]@{
        CheckId    = $CheckId
        Name       = $Name
        Status     = $Status
        DurationMs = $DurationMs
        ExitCode   = $ExitCode
        Details    = $Details
        Results    = $resultsList
        Artifacts  = $Artifacts
        Timestamp  = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
    }
}

function Add-MaintenanceResult {
    <#
    .SYNOPSIS
    Appends an item-level result to the Results collection of a result object.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject]$Result,

        [Parameter(Mandatory = $true)]
        [string]$Item,

        [ValidateSet("passed", "failed", "skipped", "error")]
        [string]$Status = "passed",

        [string]$Message = ""
    )

    $itemResult = @{
        Item    = $Item
        Status  = $Status
        Message = $Message
    }

    [void]$Result.Results.Add($itemResult)
}

# ═══════════════════════════════════════════════════════════════════
# Logging
# ═══════════════════════════════════════════════════════════════════

function Write-MaintenanceLog {
    <#
    .SYNOPSIS
    Writes a structured log entry to the console with timestamp and level.
    #>
    param(
        [ValidateSet("INFO", "WARN", "ERROR", "DEBUG")]
        [string]$Level = "INFO",

        [string]$Component,

        [string]$Message
    )

    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $color = switch ($Level) {
        "ERROR" { "Red" }
        "WARN"  { "Yellow" }
        "DEBUG" { "Gray" }
        default { "White" }
    }

    $prefix = "[$timestamp] [$Level]"
    if ($Component) {
        $prefix += " [$Component]"
    }

    Write-Host "$prefix $Message" -ForegroundColor $color
}

# ═══════════════════════════════════════════════════════════════════
# Execution wrapper
# ═══════════════════════════════════════════════════════════════════

function Invoke-MaintenanceStep {
    <#
    .SYNOPSIS
    Wraps a maintenance check execution with timing, logging, and exit-code
    handling. Returns a hashtable with stdout, stderr, ExitCode, and DurationMs.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$StepName,

        [Parameter(Mandatory = $true)]
        [scriptblock]$ScriptBlock,

        [int]$TimeoutSeconds = 3600,

        [string]$WorkingDirectory = $null
    )

    Write-MaintenanceLog -Level "INFO" -Component $StepName -Message "Starting..."

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $stdout = $null
    $stderr = $null
    $exitCode = 0

    try {
        if ($WorkingDirectory) {
            Push-Location $WorkingDirectory
        }

        $job = Start-Job -ScriptBlock $ScriptBlock
        $completed = Wait-Job $job -Timeout $TimeoutSeconds

        if (-not $completed) {
            Stop-Job $job
            $stdout = "TIMEOUT: Step exceeded $TimeoutSeconds seconds"
            $stderr = "TIMEOUT"
            $exitCode = 124
            Write-MaintenanceLog -Level "ERROR" -Component $StepName -Message "TIMEOUT after ${TimeoutSeconds}s"
        }
        else {
            $output = Receive-Job $job
            $stdout = $output -join "`n"
            $jobErrors = $job.ChildJobs[0].Error
            if ($jobErrors) {
                $stderr = ($jobErrors | ForEach-Object { "$_" }) -join "`n"
            }
            else {
                $stderr = ""
            }
            $exitCode = $job.ChildJobs[0].ExitCode
            if ($exitCode -eq 0) {
                Write-MaintenanceLog -Level "INFO" -Component $StepName -Message "Completed successfully"
            }
            else {
                Write-MaintenanceLog -Level "ERROR" -Component $StepName -Message "Failed with exit code $exitCode"
            }
        }

        Remove-Job $job -Force -ErrorAction SilentlyContinue
    }
    catch {
        $stdout = ""
        $stderr = $_.Exception.Message
        $exitCode = 1
        Write-MaintenanceLog -Level "ERROR" -Component $StepName -Message "Exception: $($_.Exception.Message)"
    }
    finally {
        if ($WorkingDirectory) {
            Pop-Location
        }
        $sw.Stop()
    }

    return @{
        StepName    = $StepName
        Stdout      = $stdout
        Stderr      = $stderr
        ExitCode    = $exitCode
        DurationMs  = $sw.ElapsedMilliseconds
    }
}

# ═══════════════════════════════════════════════════════════════════
# Threshold management
# ═══════════════════════════════════════════════════════════════════

function Get-MaintenanceThreshold {
    <#
    .SYNOPSIS
    Reads a threshold value from thresholds.psd1, with environment variable
    override. Environment variable format: MAINTENANCE_<Section>_<Key>
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$Section,

        [Parameter(Mandatory = $true)]
        [string]$Key,

        [object]$Default = $null
    )

    # Check for environment variable override first
    $envKey = "MAINTENANCE_${Section}_${Key}"
    $envValue = [Environment]::GetEnvironmentVariable($envKey)
    if ($envValue) {
        return $envValue
    }

    # Load thresholds file if not already cached
    if ($null -eq $script:_Thresholds) {
        # Resolve path relative to this module file (common/)
        $thresholdsPath = Join-Path $PSScriptRoot "..\thresholds\thresholds.psd1"
        if (Test-Path $thresholdsPath) {
            try {
                $raw = Get-Content $thresholdsPath -Raw -Encoding UTF8
                $script:_Thresholds = Invoke-Expression $raw
            }
            catch {
                Write-Warning "Failed to load thresholds: $($_.Exception.Message)"
                $script:_Thresholds = @{}
            }
        }
        else {
            $script:_Thresholds = @{}
        }
    }

    if ($script:_Thresholds -and
        $script:_Thresholds.ContainsKey($Section) -and
        $script:_Thresholds[$Section] -and
        $script:_Thresholds[$Section].ContainsKey($Key)) {
        return $script:_Thresholds[$Section][$Key]
    }

    return $Default
}

# ═══════════════════════════════════════════════════════════════════
# Execution mode
# ═══════════════════════════════════════════════════════════════════

function Test-IsMaintenanceMode {
    <#
    .SYNOPSIS
    Returns the current maintenance execution mode.
    - "ci"      → strict, fails fast
    - "nightly" → relaxed, collects all failures
    - "dev"     → warn only, never fails
    #>
    $mode = [Environment]::GetEnvironmentVariable("MAINTENANCE_MODE")
    if ($mode) {
        return $mode.ToLower()
    }
    return "dev"
}

function Format-MaintenanceDuration {
    <#
    .SYNOPSIS
    Formats a duration in milliseconds as a human-readable string.
    #>
    param([long]$Milliseconds)

    if ($Milliseconds -lt 1000) {
        return "${Milliseconds}ms"
    }
    elseif ($Milliseconds -lt 60000) {
        return "{0:F1}s" -f ($Milliseconds / 1000)
    }
    else {
        $minutes = [math]::Floor($Milliseconds / 60000)
        $seconds = ($Milliseconds % 60000) / 1000
        return "${minutes}m ${seconds:F0}s"
    }
}

# ═══════════════════════════════════════════════════════════════════
# Convenience: compute overall status from item results
# ═══════════════════════════════════════════════════════════════════

function Set-MaintenanceResultSummary {
    <#
    .SYNOPSIS
    Computes the overall Status and Details for a result object from its
    item-level Results collection. Call this after populating all Results.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [PSCustomObject]$Result
    )

    # Guard against null/empty Results
    $items = @($Result.Results)
    $passed  = @($items | Where-Object { $_.Status -eq "passed" }).Count
    $failed  = @($items | Where-Object { $_.Status -eq "failed" }).Count
    $skipped = @($items | Where-Object { $_.Status -eq "skipped" }).Count
    $errors  = @($items | Where-Object { $_.Status -eq "error" }).Count
    $total   = $items.Count

    if ($total -eq 0) {
        $Result.Status = "skipped"
        $Result.Details = "No items checked"
        return
    }

    if ($errors -gt 0) {
        $Result.Status = "error"
    }
    elseif ($failed -gt 0) {
        $Result.Status = "failed"
    }
    else {
        $Result.Status = "passed"
    }

    $parts = @()
    if ($passed  -gt 0) { $parts += "${passed} passed" }
    if ($failed  -gt 0) { $parts += "${failed} failed" }
    if ($skipped -gt 0) { $parts += "${skipped} skipped" }
    if ($errors  -gt 0) { $parts += "${errors} errors" }

    $Result.Details = "$($parts -join ', ') — ${total} total"
}

# ═══════════════════════════════════════════════════════════════════
# Log directory helper
# ═══════════════════════════════════════════════════════════════════

function Get-MaintenanceLogDir {
    <#
    .SYNOPSIS
    Returns the maintenance log directory, creating it if needed.
    #>
    $repoRoot = Get-RepositoryRoot
    $logDir = Join-Path $repoRoot "bin\maintenance\logs"
    if (-not (Test-Path $logDir)) {
        New-Item -ItemType Directory -Path $logDir -Force | Out-Null
    }
    return $logDir
}

Write-MaintenanceLog -Level "DEBUG" -Component "MaintenanceUtil" -Message "Module loaded"
