# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Use forward slashes in paths where possible.
# ═══════════════════════════════════════════════════════════════════

# ═══════════════════════════════════════════════════════════════════
# MaintenanceState.ps1 — Persistent state for maintenance checks
# ═══════════════════════════════════════════════════════════════════
#
# Dot-source this module from orchestrator or any script that needs
# to read/write the shared maintenance state:
#   . "$PSScriptRoot/../common/MaintenanceState.ps1"
#
# The state file (bin/maintenance/state/maintenance-state.json) is
# tracked in git so the team shares a single source of truth for
# what checks have run and when. Each machine that runs the
# orchestrator reads this file and skips tasks whose last-run time
# falls within their configured interval.
#
# Exports:
#   Get-MaintenanceStatePath        — Resolve path to state JSON file
#   Read-MaintenanceState           — Load and parse the state file
#   Write-MaintenanceState          — Persist state with file locking
#   Update-MaintenanceTaskState     — Update a single task's entry
#   Test-MaintenanceTaskDue         — Check if a task should run
#   Initialize-MaintenanceState     — Create a fresh state file
# ═══════════════════════════════════════════════════════════════════

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

# ── Dot-source shared utilities for path resolution & logging ──
$UtilPath = Join-Path $PSScriptRoot "MaintenanceUtil.ps1"
if (Test-Path $UtilPath) {
    . $UtilPath
}

# ═══════════════════════════════════════════════════════════════════
# Module-level state (cached)
# ═══════════════════════════════════════════════════════════════════

$_StatePath = $null
$_CachedState = $null

# ═══════════════════════════════════════════════════════════════════
# Path resolution
# ═══════════════════════════════════════════════════════════════════

function Get-MaintenanceStatePath {
    <#
    .SYNOPSIS
    Returns the absolute path to the maintenance state JSON file.
    Cached after first call.
    #>
    if ($null -ne $script:_StatePath) {
        return $script:_StatePath
    }
    $repoRoot = Get-RepositoryRoot
    $stateDir = Join-Path $repoRoot "bin\maintenance\state"

    # Ensure the directory exists
    if (-not (Test-Path $stateDir)) {
        New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
    }

    $script:_StatePath = Join-Path $stateDir "maintenance-state.json"
    return $script:_StatePath
}

# ═══════════════════════════════════════════════════════════════════
# File locking helpers
# ═══════════════════════════════════════════════════════════════════

function Get-MaintenanceStateLockPath {
    $statePath = Get-MaintenanceStatePath
    return "$statePath.lock"
}

function Acquire-MaintenanceStateLock {
    <#
    .SYNOPSIS
    Acquires an exclusive lock on the state file.
    Returns a disposable lock handle, or $null if lock cannot be acquired.

    .DESCRIPTION
    Uses a .lock file with retry and stale-lock detection.
    A lock older than 60 seconds is considered stale and broken.
    Retries up to 10 times with exponential backoff (100ms → 51.2s max).
    #>
    param(
        [int]$MaxRetries = 10,
        [int]$BaseDelayMs = 100,
        [int]$StaleLockSeconds = 60
    )

    $lockPath = Get-MaintenanceStateLockPath
    $delay = $BaseDelayMs

    for ($i = 0; $i -lt $MaxRetries; $i++) {
        try {
            # Try to create the lock file exclusively
            $stream = [System.IO.File]::Open(
                $lockPath,
                [System.IO.FileMode]::CreateNew,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::None
            )
            # Write PID and timestamp so stale-lock detection has context
            $writer = New-Object System.IO.StreamWriter($stream, [System.Text.Encoding]::UTF8)
            $writer.WriteLine("pid=$pid")
            $writer.WriteLine("machine=$env:COMPUTERNAME")
            $writer.WriteLine("timestamp=$(Get-Date -Format 'yyyy-MM-ddTHH:mm:ssK')")
            $writer.Flush()
            $stream.Position = 0
            return @{
                Stream = $stream
                Path   = $lockPath
            }
        }
        catch [System.IO.IOException] {
            # Lock already exists — check if stale
            if (Test-Path $lockPath) {
                $lockItem = Get-Item $lockPath -ErrorAction SilentlyContinue
                if ($lockItem) {
                    $lockAge = (Get-Date) - $lockItem.LastWriteTime
                    if ($lockAge.TotalSeconds -gt $StaleLockSeconds) {
                        Write-MaintenanceLog -Level "WARN" -Component "StateLock" `
                            -Message "Breaking stale lock (age: $([math]::Round($lockAge.TotalSeconds))s)"
                        Remove-Item $lockPath -Force -ErrorAction SilentlyContinue
                        continue
                    }
                }
            }
        }

        if ($i -lt $MaxRetries - 1) {
            Start-Sleep -Milliseconds $delay
            $delay = [Math]::Min($delay * 2, 5000)
        }
    }

    Write-MaintenanceLog -Level "ERROR" -Component "StateLock" `
        -Message "Could not acquire state lock after $MaxRetries retries"
    return $null
}

function Release-MaintenanceStateLock {
    <#
    .SYNOPSIS
    Releases a previously acquired state lock.
    #>
    param(
        [Parameter(Mandatory = $true)]
        $LockHandle
    )

    if ($null -eq $LockHandle) { return }

    try {
        if ($LockHandle.Stream) {
            $LockHandle.Stream.Dispose()
        }
    }
    catch {
        Write-MaintenanceLog -Level "WARN" -Component "StateLock" `
            -Message "Error disposing lock stream: $($_.Exception.Message)"
    }

    if ($LockHandle.Path -and (Test-Path $LockHandle.Path)) {
        Remove-Item $LockHandle.Path -Force -ErrorAction SilentlyContinue
    }
}

# ═══════════════════════════════════════════════════════════════════
# State I/O
# ═══════════════════════════════════════════════════════════════════

function Read-MaintenanceState {
    <#
    .SYNOPSIS
    Reads the maintenance state from disk. Returns a PSCustomObject
    with .Tasks (hashtable keyed by task name) and metadata.
    Returns $null if the state file does not exist or is corrupt.
    #>
    $statePath = Get-MaintenanceStatePath

    if (-not (Test-Path $statePath)) {
        Write-MaintenanceLog -Level "DEBUG" -Component "State" `
            -Message "State file not found, will initialize"
        return $null
    }

    try {
        $raw = Get-Content $statePath -Raw -Encoding UTF8
        if (-not $raw.Trim()) {
            return $null
        }
        $state = $raw | ConvertFrom-Json

        # Normalize: ensure Tasks is a non-null PSCustomObject.
        # An empty "Tasks": {} parses to PSCustomObject with no properties;
        # "Tasks": null or missing Tasks parses to $null. Both need fixing.
        if ($null -eq $state.Tasks) {
            $state | Add-Member -MemberType NoteProperty -Name "Tasks" -Value ([PSCustomObject]@{}) -Force
        }
        elseif (@($state.Tasks.PSObject.Properties).Count -eq 0) {
            # Already a PSCustomObject (empty), keep as-is
        }

        # Validate version; reinitialize if from an incompatible future version
        if ($state.version -and $state.version -gt 1) {
            Write-MaintenanceLog -Level "WARN" -Component "State" `
                -Message "State file version $($state.version) is newer than supported (1), reinitializing"
            return $null
        }

        return $state
    }
    catch {
        Write-MaintenanceLog -Level "WARN" -Component "State" `
            -Message "Failed to parse state file: $($_.Exception.Message). Will reinitialize."
        return $null
    }
}

function Write-MaintenanceState {
    <#
    .SYNOPSIS
    Writes the maintenance state to disk with file locking.
    Blocks until the lock is acquired or times out.
    #>
    param(
        [Parameter(Mandatory = $true)]
        $State
    )

    $statePath = Get-MaintenanceStatePath

    # Ensure directory exists
    $stateDir = Split-Path $statePath -Parent
    if (-not (Test-Path $stateDir)) {
        New-Item -ItemType Directory -Path $stateDir -Force | Out-Null
    }

    $lock = Acquire-MaintenanceStateLock
    if ($null -eq $lock) {
        Write-MaintenanceLog -Level "ERROR" -Component "State" `
            -Message "Could not acquire lock to write state"
        return $false
    }

    try {
        # Stamp metadata
        $state | Add-Member -MemberType NoteProperty -Name "updatedAt" `
            -Value (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK") -Force

        $json = $state | ConvertTo-Json -Depth 8 -Compress
        # Re-format with indentation for human-readability (and to keep diffs clean)
        $pretty = ($state | ConvertTo-Json -Depth 8)
        $pretty | Out-File -FilePath $statePath -Encoding UTF8 -NoNewline
        # Append trailing newline (Out-File -NoNewline omits it, but we want one)
        Add-Content -Path $statePath -Value "" -Encoding UTF8 -NoNewline:$false

        $script:_CachedState = $state
        return $true
    }
    catch {
        Write-MaintenanceLog -Level "ERROR" -Component "State" `
            -Message "Failed to write state: $($_.Exception.Message)"
        return $false
    }
    finally {
        Release-MaintenanceStateLock -LockHandle $lock
    }
}

# ═══════════════════════════════════════════════════════════════════
# Task state management
# ═══════════════════════════════════════════════════════════════════

function Update-MaintenanceTaskState {
    <#
    .SYNOPSIS
    Updates the state entry for a single task and persists to disk.

    .PARAMETER TaskName
    The task name (e.g. "check-compilation").

    .PARAMETER Result
    The result object returned by the check script.

    .PARAMETER State
    Optional existing state object. If not provided, reads from disk.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$TaskName,

        [Parameter(Mandatory = $true)]
        $Result,

        $State = $null
    )

    if ($null -eq $State) {
        $State = Read-MaintenanceState
    }
    if ($null -eq $State) {
        $State = Initialize-MaintenanceState -PassThru
    }

    $machine = if ($env:COMPUTERNAME) { $env:COMPUTERNAME } else { [System.Environment]::MachineName }

    $taskEntry = @{
        lastRun         = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
        lastResult      = $Result.Status
        lastDurationMs  = $Result.DurationMs
        lastMachine     = $machine
        lastExitCode    = $Result.ExitCode
    }

    # Preserve runCount from previous state (increment it)
    $oldEntry = $null
    $existingNames = @($State.Tasks.PSObject.Properties | ForEach-Object { $_.Name })
    if ($existingNames -contains $TaskName) {
        $oldEntry = $State.Tasks.$TaskName
    }
    $taskEntry.runCount = if ($oldEntry -and $oldEntry.runCount) { $oldEntry.runCount + 1 } else { 1 }

    # Build or update the Tasks property
    $tasks = @{}
    foreach ($prop in $State.Tasks.PSObject.Properties) {
        $tasks[$prop.Name] = $prop.Value
    }
    $tasks[$TaskName] = [PSCustomObject]$taskEntry

    $State.Tasks = [PSCustomObject]$tasks

    Write-MaintenanceState -State $State
}

function Test-MaintenanceTaskDue {
    <#
    .SYNOPSIS
    Returns $true if a task should run based on its interval and last-run time.

    .DESCRIPTION
    A task is "due" if:
      - It has never been run (no state entry)
      - Time since lastRun >= IntervalSeconds
      - -Force was specified

    .PARAMETER TaskName
    The task name to check.

    .PARAMETER IntervalSeconds
    The task's configured interval in seconds.

    .PARAMETER Force
    If $true, always returns $true (bypass state check).

    .PARAMETER State
    Optional existing state object. If omitted, reads from disk.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [string]$TaskName,

        [Parameter(Mandatory = $true)]
        [int]$IntervalSeconds,

        [switch]$Force,

        $State = $null
    )

    if ($Force) {
        return $true
    }

    if ($null -eq $State) {
        $State = Read-MaintenanceState
    }

    # No state at all — everything is due
    if ($null -eq $State) {
        return $true
    }

    # Task never run before
    $existingNames = @($State.Tasks.PSObject.Properties | ForEach-Object { $_.Name })
    if (-not ($existingNames -contains $TaskName)) {
        return $true
    }

    $entry = $State.Tasks.$TaskName
    if (-not $entry -or -not $entry.lastRun) {
        return $true
    }

    try {
        $lastRun = [DateTime]::Parse($entry.lastRun)
        $elapsed = ([DateTime]::Now - $lastRun).TotalSeconds

        if ($elapsed -ge $IntervalSeconds) {
            Write-MaintenanceLog -Level "DEBUG" -Component "State" `
                -Message "$TaskName is due: ${elapsed}s elapsed >= ${IntervalSeconds}s interval"
            return $true
        }

        Write-MaintenanceLog -Level "DEBUG" -Component "State" `
            -Message "$TaskName not due: ${elapsed}s elapsed < ${IntervalSeconds}s interval (last: $($entry.lastRun))"
        return $false
    }
    catch {
        Write-MaintenanceLog -Level "WARN" -Component "State" `
            -Message "Could not parse lastRun for $TaskName : $($_.Exception.Message)"
        return $true
    }
}

# ═══════════════════════════════════════════════════════════════════
# Initialization
# ═══════════════════════════════════════════════════════════════════

function Initialize-MaintenanceState {
    <#
    .SYNOPSIS
    Creates a fresh maintenance state file if it doesn't exist.

    .PARAMETER PassThru
    If set, returns the state object without writing to disk.

    .PARAMETER Force
    If set, overwrites any existing state file.
    #>
    param(
        [switch]$PassThru,
        [switch]$Force
    )

    $statePath = Get-MaintenanceStatePath

    if (Test-Path $statePath -and -not $Force) {
        Write-MaintenanceLog -Level "DEBUG" -Component "State" `
            -Message "State file already exists, skipping init"
        return $null
    }

    $machine = if ($env:COMPUTERNAME) { $env:COMPUTERNAME } else { [System.Environment]::MachineName }

    $state = [PSCustomObject]@{
        version   = 1
        createdAt = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
        updatedAt = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
        machine   = $machine
        Tasks     = [PSCustomObject]@{}
    }

    if ($PassThru) {
        return $state
    }

    Write-MaintenanceState -State $state
    Write-MaintenanceLog -Level "INFO" -Component "State" `
        -Message "Initialized new maintenance state at $statePath"
    return $state
}

# ═══════════════════════════════════════════════════════════════════
# Convenience: get summary for reporting
# ═══════════════════════════════════════════════════════════════════

function Get-MaintenanceStateSummary {
    <#
    .SYNOPSIS
    Returns a human-readable summary of the current maintenance state.
    #>
    param(
        $State = $null
    )

    if ($null -eq $State) {
        $State = Read-MaintenanceState
    }
    if ($null -eq $State) {
        return "No maintenance state exists yet."
    }

    $lines = @()
    $lines += "Maintenance State (updated: $($State.updatedAt))"
    $lines += "Machine: $($State.machine)"
    $lines += ""

    $taskNames = @($State.Tasks.PSObject.Properties | ForEach-Object { $_.Name } | Sort-Object)
    if ($taskNames.Count -eq 0) {
        $lines += "  No tasks have been recorded yet."
    }
    else {
        $lines += "  Task                                 Last Run              Result   Count  Machine"
        $lines += "  ----                                 --------              ------   -----  -------"
        foreach ($name in $taskNames) {
            $t = $State.Tasks.$name
            $lastRunStr = if ($t.lastRun) { "$($t.lastRun)" } else { "-" }
            $lastRun = if ($lastRunStr.Length -gt 19) { $lastRunStr.Substring(0, 19) } else { $lastRunStr.PadRight(19) }
            $result  = if ($t.lastResult) { $t.lastResult.PadRight(8) } else { "-" }
            $count   = if ($t.runCount) { "$($t.runCount)".PadRight(7) } else { "0" }
            $machine = if ($t.lastMachine) { $t.lastMachine } else { "-" }
            $lines += "  $($name.PadRight(38)) $lastRun  $result $count $machine"
        }
    }

    return $lines -join "`n"
}

Write-MaintenanceLog -Level "DEBUG" -Component "MaintenanceState" -Message "Module loaded"
