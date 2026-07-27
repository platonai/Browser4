# ── Coworker state persistence ─────────────────────────────────────────────
# JSON state file at ~/.browser4-coworker/state.json.
# Provides read/write helpers plus a cached-editor subsystem so
# Find-BestEditor can skip the tier scan on subsequent runs.

$script:CoworkerStateCache = $null
$script:EditorCacheValidDays = 7

function Get-CoworkerStatePath {
    $dir = Join-Path $HOME '.browser4-coworker'
    if (-not (Test-Path -LiteralPath $dir)) {
        New-Item -ItemType Directory -Path $dir -Force | Out-Null
    }
    return Join-Path $dir 'state.json'
}

<#
.SYNOPSIS
    Read and parse the coworker state file.
    Returns a [hashtable] (empty if the file is missing or corrupt).
#>
function Read-CoworkerState {
    if ($null -ne $script:CoworkerStateCache) {
        return $script:CoworkerStateCache
    }

    $statePath = Get-CoworkerStatePath
    if (-not (Test-Path -LiteralPath $statePath)) {
        $script:CoworkerStateCache = @{}
        return $script:CoworkerStateCache
    }

    try {
        $raw = Get-Content -LiteralPath $statePath -Raw -Encoding UTF8
        if ([string]::IsNullOrWhiteSpace($raw)) {
            $script:CoworkerStateCache = @{}
            return $script:CoworkerStateCache
        }
        $obj = $raw | ConvertFrom-Json
        $ht = ConvertTo-StateHashtable -InputObject $obj
        $script:CoworkerStateCache = $ht
        return $script:CoworkerStateCache
    }
    catch {
        Write-CoworkerLog -Message "State file corrupt, resetting: $_" -Level WARN
        $script:CoworkerStateCache = @{}
        return $script:CoworkerStateCache
    }
}

<#
.SYNOPSIS
    Recursively convert a PSCustomObject (from ConvertFrom-Json) to a nested
    hashtable so callers can use .ContainsKey() and [hashtable] type checks.
    Arrays, scalar values, and $null pass through unchanged.
#>
function ConvertTo-StateHashtable {
    param($InputObject)

    if ($null -eq $InputObject) { return $null }

    if ($InputObject -is [array]) {
        $result = @()
        foreach ($item in $InputObject) {
            $result += ConvertTo-StateHashtable -InputObject $item
        }
        return $result
    }

    if ($InputObject -is [System.Management.Automation.PSCustomObject]) {
        $ht = @{}
        $InputObject.PSObject.Properties | ForEach-Object {
            $ht[$_.Name] = ConvertTo-StateHashtable -InputObject $_.Value
        }
        return $ht
    }

    # Scalar: return as-is
    return $InputObject
}

<#
.SYNOPSIS
    Write the coworker state hashtable to disk.
#>
function Write-CoworkerState {
    param(
        [Parameter(Mandatory = $true)]
        [hashtable]$State
    )

    $statePath = Get-CoworkerStatePath
    try {
        $State | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $statePath -Encoding UTF8
        $script:CoworkerStateCache = $State
    }
    catch {
        Write-CoworkerLog -Message "Failed to write state file: $_" -Level ERROR
    }
}

<#
.SYNOPSIS
    Update a single key in the state file without re-reading from disk.
    In-memory cache is also updated so subsequent reads see the change.
#>
function Update-CoworkerStateKey {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Key,
        [Parameter(Mandatory = $true)]
        $Value
    )

    $state = Read-CoworkerState
    $state[$Key] = $Value
    Write-CoworkerState -State $state
}

<#
.SYNOPSIS
    Get the cached editor command from state, if still valid.
    Returns an array (executable, flag, ...) ready for splatting,
    or $null if the cache is stale / missing / invalid.
#>
function Get-StateEditor {
    $state = Read-CoworkerState
    if (-not $state.ContainsKey('editor')) {
        return $null
    }

    $editor = $state['editor']

    # Must be a hashtable with a 'command' array
    if ($editor -isnot [hashtable] -and $editor -isnot [System.Collections.IDictionary]) {
        return $null
    }
    if (-not $editor.ContainsKey('command') -or $editor['command'] -isnot [array] -or $editor['command'].Count -eq 0) {
        return $null
    }

    # Check expiry
    if ($editor.ContainsKey('checkedAt') -and $editor['checkedAt']) {
        try {
            $checkedAt = [DateTimeOffset]::Parse($editor['checkedAt'])
            $age = [DateTimeOffset]::Now - $checkedAt
            if ($age.TotalDays -gt $script:EditorCacheValidDays) {
                return $null
            }
        }
        catch {
            return $null
        }
    }
    else {
        # No timestamp — stale
        return $null
    }

    # Verify the executable is still on PATH
    $exe = $editor['command'][0]
    if (-not (Get-Command $exe -ErrorAction SilentlyContinue)) {
        return $null
    }

    return $editor['command']
}

<#
.SYNOPSIS
    Write the editor entry into the state file (or clear it by passing $null).
#>
function Set-StateEditor {
    param(
        [Parameter(Mandatory = $false)]
        [array]$Command,
        [Parameter(Mandatory = $false)]
        [string]$Desc = ''
    )

    if ($null -eq $Command -or $Command.Count -eq 0) {
        # Clear the editor cache by removing the key entirely
        $state = Read-CoworkerState
        $state.Remove('editor')
        Write-CoworkerState -State $state
        return
    }

    $editorEntry = @{
        command   = $Command
        desc      = $Desc
        checkedAt = [DateTimeOffset]::Now.ToString('yyyy-MM-ddTHH:mm:sszzz')
    }

    Update-CoworkerStateKey -Key 'editor' -Value $editorEntry
}
