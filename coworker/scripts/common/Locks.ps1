# ── Script-level mutex (one instance per script, different scripts run in parallel)
# Lock files live under coworker/tasks/.locks/.  Each lock is keyed by the
# SHA256 of the resolved script path so the same script always maps to the
# same lock file regardless of how it was invoked.

$script:__CoworkerLockDirectory = $null

function Get-CoworkerLockDirectory {
    if ($null -eq $script:__CoworkerLockDirectory) {
        $script:__CoworkerLockDirectory = Join-Path (Get-TasksRoot) '.locks'
        Ensure-CoworkerDirectory -Path $script:__CoworkerLockDirectory
    }
    return $script:__CoworkerLockDirectory
}

function Get-CoworkerScriptLockPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptPath
    )

    $resolvedPath = [System.IO.Path]::GetFullPath($ScriptPath)
    $hashBytes = [System.Security.Cryptography.SHA256]::Create().ComputeHash(
        [System.Text.Encoding]::UTF8.GetBytes($resolvedPath)
    )
    $hashHex = -join ($hashBytes | ForEach-Object { $_.ToString('x2') })
    return Join-Path (Get-CoworkerLockDirectory) "$hashHex.lock"
}

<#
.SYNOPSIS
    Test whether a script's lock is currently held by a running process.

.DESCRIPTION
    Returns $true when a live process holds the lock for the given script.
    Stale locks (PID no longer running) are cleaned up and return $false.
#>
function Test-CoworkerScriptLockHeld {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptPath
    )

    $lockFilePath = Get-CoworkerScriptLockPath -ScriptPath $ScriptPath
    if (-not (Test-Path -LiteralPath $lockFilePath)) {
        return $false
    }

    try {
        $lockContent = Get-Content -LiteralPath $lockFilePath -Raw -Encoding UTF8
        $lockData = $lockContent | ConvertFrom-Json
        $lockedPid = [int]$lockData.PID

        $existingProcess = Get-Process -Id $lockedPid -ErrorAction SilentlyContinue
        if ($null -ne $existingProcess) {
            return $true
        }
    }
    catch {
        # Unparseable lock -> treat as stale
    }

    # Stale lock -> clean up
    Remove-Item -LiteralPath $lockFilePath -Force -ErrorAction SilentlyContinue
    return $false
}

<#
.SYNOPSIS
    Acquire an exclusive file-based mutex for a script.

.DESCRIPTION
    Creates a lock file atomically so only one process can hold the lock.
    Returns a lock object on success.  Throws on failure unless -SkipIfHeld
    is passed, in which case it returns $null.
#>
function New-CoworkerScriptLock {
    param(
        [Parameter(Mandatory = $true)]
        [string]$ScriptPath,
        [switch]$SkipIfHeld
    )

    $resolvedPath = [System.IO.Path]::GetFullPath($ScriptPath)
    $lockFilePath = Get-CoworkerScriptLockPath -ScriptPath $resolvedPath

    # First pass: try atomic creation
    $fileStream = $null
    try {
        $fileStream = [System.IO.File]::Open(
            $lockFilePath,
            [System.IO.FileMode]::CreateNew,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::Read
        )
    }
    catch [System.IO.IOException] {
        # File exists -> check staleness
        if (Test-CoworkerScriptLockHeld -ScriptPath $resolvedPath) {
            if ($SkipIfHeld) {
                return $null
            }
            $lockContent = Get-Content -LiteralPath $lockFilePath -Raw -Encoding UTF8
            throw "Script '$resolvedPath' is already running (lock: $lockFilePath, content: $lockContent)"
        }

        # Stale lock was cleaned by Test-CoworkerScriptLockHeld -> retry
        try {
            $fileStream = [System.IO.File]::Open(
                $lockFilePath,
                [System.IO.FileMode]::CreateNew,
                [System.IO.FileAccess]::Write,
                [System.IO.FileShare]::Read
            )
        }
        catch {
            if ($SkipIfHeld) {
                return $null
            }
            throw "Failed to acquire lock for script '$resolvedPath' after stale cleanup: $_"
        }
    }

    # Write lock payload
    $lockData = @{
        PID        = $PID
        ScriptPath = $resolvedPath
        StartedAt  = Get-CoworkerTimestamp
    } | ConvertTo-Json -Compress

    $lockBytes = [System.Text.Encoding]::UTF8.GetBytes($lockData)
    $fileStream.Write($lockBytes, 0, $lockBytes.Length)
    $fileStream.Close()

    return @{
        LockFilePath = $lockFilePath
        ScriptPath   = $resolvedPath
    }
}

<#
.SYNOPSIS
    Release a lock previously acquired by New-CoworkerScriptLock.
#>
function Remove-CoworkerScriptLock {
    param(
        [Parameter(Mandatory = $true)]
        [hashtable]$Lock
    )

    if ($null -eq $Lock -or [string]::IsNullOrWhiteSpace($Lock.LockFilePath)) {
        return
    }

    if (Test-Path -LiteralPath $Lock.LockFilePath) {
        # Only remove if we own it (match PID)
        try {
            $lockContent = Get-Content -LiteralPath $Lock.LockFilePath -Raw -Encoding UTF8
            $lockData = $lockContent | ConvertFrom-Json
            if ([int]$lockData.PID -eq $PID) {
                Remove-Item -LiteralPath $Lock.LockFilePath -Force -ErrorAction SilentlyContinue
            }
        }
        catch {
            Remove-Item -LiteralPath $Lock.LockFilePath -Force -ErrorAction SilentlyContinue
        }
    }
}
