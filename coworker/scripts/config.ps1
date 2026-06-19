$configDataPath = Join-Path $PSScriptRoot 'config.psd1'
$utilScriptPath = Join-Path $PSScriptRoot 'common\Util.ps1'
if (Test-Path -LiteralPath $utilScriptPath) {
    . $utilScriptPath
    Fix-Encoding-UTF8
}

if (-not (Test-Path $configDataPath)) {
    throw "Config data file not found: $configDataPath"
}

$script:configData = Import-PowerShellDataFile -Path $configDataPath

function Get-CoworkerConfigValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Map,
        [Parameter(Mandatory = $true)]
        [string]$Key,
        $DefaultValue = $null
    )

    if ($Map -is [System.Collections.IDictionary] -and $Map.Contains($Key)) {
        return $Map[$Key]
    }

    return $DefaultValue
}

function Resolve-CoworkerConfiguredPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [string]$BaseDirectory = $PSScriptRoot
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'Configured path cannot be empty.'
    }

    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }

    return [System.IO.Path]::GetFullPath((Join-Path $BaseDirectory $Path))
}

function Get-CoworkerConfigData {
    return $script:configData
}

function Get-WorkspaceRoot {
    $pathsConfig = Get-CoworkerConfigValue -Map $script:configData -Key 'Paths' -DefaultValue @{}
    $path = [string](Get-CoworkerConfigValue -Map $pathsConfig -Key 'WorkspaceRoot' -DefaultValue '..\..')
    return Resolve-CoworkerConfiguredPath -Path $path
}

function Get-TargetRepositoryRoot {
    $pathsConfig = Get-CoworkerConfigValue -Map $script:configData -Key 'Paths' -DefaultValue @{}
    $configuredPath = Get-CoworkerConfigValue -Map $pathsConfig -Key 'TargetRepositoryRoot' -DefaultValue $null

    if ($null -eq $configuredPath -or [string]::IsNullOrWhiteSpace([string]$configuredPath)) {
        return Get-WorkspaceRoot
    }

    $resolvedPath = Resolve-CoworkerConfiguredPath -Path ([string]$configuredPath)
    if (-not (Test-Path -LiteralPath $resolvedPath -PathType Container)) {
        throw "Configured target repository root does not exist: $resolvedPath"
    }

    return $resolvedPath
}

function Get-CoworkerRoot {
    $pathsConfig = Get-CoworkerConfigValue -Map $script:configData -Key 'Paths' -DefaultValue @{}
    $path = [string](Get-CoworkerConfigValue -Map $pathsConfig -Key 'CoworkerRoot' -DefaultValue '..')
    return Resolve-CoworkerConfiguredPath -Path $path
}

function Get-TasksRoot {
    $pathsConfig = Get-CoworkerConfigValue -Map $script:configData -Key 'Paths' -DefaultValue @{}
    $path = [string](Get-CoworkerConfigValue -Map $pathsConfig -Key 'TasksRoot' -DefaultValue '..\tasks')
    return Resolve-CoworkerConfiguredPath -Path $path
}

function Get-LogDirectory {
    $pathsConfig = Get-CoworkerConfigValue -Map $script:configData -Key 'Paths' -DefaultValue @{}
    $path = [string](Get-CoworkerConfigValue -Map $pathsConfig -Key 'LogDirectory' -DefaultValue 'coworker\tasks\300logs')
    return Resolve-CoworkerConfiguredPath -Path $path -BaseDirectory (Get-WorkspaceRoot)
}

function Get-SchedulerWorkingDirectory {
    $schedulerConfig = Get-CoworkerConfigValue -Map $script:configData -Key 'Scheduler' -DefaultValue @{}
    $path = [string](Get-CoworkerConfigValue -Map $schedulerConfig -Key 'WorkingDirectory' -DefaultValue '..\..')
    return Resolve-CoworkerConfiguredPath -Path $path
}

function Resolve-WorkspacePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RelativePath
    )

    return Resolve-CoworkerConfiguredPath -Path $RelativePath -BaseDirectory (Get-WorkspaceRoot)
}

function Resolve-CoworkerPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RelativePath
    )

    return Resolve-CoworkerConfiguredPath -Path $RelativePath -BaseDirectory (Get-CoworkerRoot)
}

function Resolve-TasksPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$RelativePath
    )

    return Resolve-CoworkerConfiguredPath -Path $RelativePath -BaseDirectory (Get-TasksRoot)
}

function Test-CoworkerPlaceholderFile {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item
    )

    return $Item.Name -eq '.gitkeep'
}

function Test-CoworkerDotPath {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item
    )

    $currentItem = $Item
    while ($null -ne $currentItem) {
        if ($currentItem.Name.StartsWith('.')) {
            return $true
        }

        if ($currentItem.PSObject.Properties.Match('Directory').Count -gt 0) {
            $currentItem = $currentItem.Directory
            continue
        }

        if ($currentItem.PSObject.Properties.Match('Parent').Count -gt 0) {
            $currentItem = $currentItem.Parent
            continue
        }

        $currentItem = $null
    }

    return $false
}

function Test-CoworkerIgnoredFile {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item
    )

    return (Test-CoworkerDotPath -Item $Item) -or (Test-CoworkerPlaceholderFile -Item $Item)
}

function Test-CoworkerPendingFile {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item
    )

    return -not $Item.PSIsContainer -and -not (Test-CoworkerIgnoredFile -Item $Item)
}

function Test-CoworkerActionableDraftRefinementFile {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item
    )

    if (-not (Test-CoworkerPendingFile -Item $Item)) {
        return $false
    }

    $content = Get-Content -LiteralPath $Item.FullName -Raw -Encoding UTF8 -ErrorAction Stop
    return -not [string]::IsNullOrWhiteSpace($content)
}

function Ensure-CoworkerDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

function Write-CoworkerLog {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Message,
        [ValidateSet('DEBUG', 'INFO', 'WARN', 'ERROR')]
        [string]$Level = 'INFO',
        [string]$Component = 'coworker',
        [switch]$NoColor
    )

    $timestamp = (Get-Date).ToUniversalTime().ToString('o')
    $formattedMessage = "[{0}] [{1}] [{2}] {3}" -f $timestamp, $Level, $Component, $Message
    $color = switch ($Level) {
        'DEBUG' { 'DarkGray' }
        'WARN' { 'Yellow' }
        'ERROR' { 'Red' }
        default { 'Gray' }
    }

    if ($NoColor) {
        Write-Host $formattedMessage
        return
    }

    Write-Host $formattedMessage -ForegroundColor $color
}

function Remove-AnsiEscapeSequences {
    param(
        [AllowNull()]
        [string]$Text
    )

    if ([string]::IsNullOrEmpty($Text)) {
        return $Text
    }

    $escapeCharacter = [string][char]27
    $ansiPattern = [regex]::Escape($escapeCharacter) + '\[[0-9;?]*[ -/]*[@-~]'
    return ($Text -replace $ansiPattern, '')
}

function Normalize-CoworkerLogFile {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path)) {
        return
    }

    try {
        $bytes = [System.IO.File]::ReadAllBytes($Path)
        if ($null -eq $bytes -or $bytes.Length -eq 0) {
            return
        }

        $content = $null
        foreach ($encodingName in @('utf-8', [System.Text.Encoding]::Default, [System.Text.Encoding]::GetEncoding([Console]::OutputEncoding.CodePage), 'unicode')) {
            try {
                if ($encodingName -is [string]) {
                    $encoding = [System.Text.Encoding]::GetEncoding($encodingName, [System.Text.EncoderFallback]::ExceptionFallback, [System.Text.DecoderFallback]::ExceptionFallback)
                }
                else {
                    $encoding = [System.Text.Encoding]::GetEncoding($encodingName.WebName, [System.Text.EncoderFallback]::ExceptionFallback, [System.Text.DecoderFallback]::ExceptionFallback)
                }

                $content = $encoding.GetString($bytes)
                break
            }
            catch {
                continue
            }
        }

        if ($null -eq $content) {
            $content = [System.Text.Encoding]::UTF8.GetString($bytes)
        }

        $sanitizedContent = Remove-AnsiEscapeSequences -Text $content
        $utf8NoBom = [System.Text.UTF8Encoding]::new($false)
        [System.IO.File]::WriteAllText($Path, $sanitizedContent, $utf8NoBom)
    }
    catch {
        # Best-effort normalization: keep original log if conversion fails.
        return
    }
}

function Remove-CoworkerEventSubscription {
    param(
        [string[]]$SourceIdentifiers = @()
    )

    foreach ($sourceIdentifier in @($SourceIdentifiers)) {
        if ([string]::IsNullOrWhiteSpace($sourceIdentifier)) {
            continue
        }

        Unregister-Event -SourceIdentifier $sourceIdentifier -ErrorAction SilentlyContinue
        Get-Event -SourceIdentifier $sourceIdentifier -ErrorAction SilentlyContinue |
            Remove-Event -ErrorAction SilentlyContinue
    }
}

function New-CoworkerFileWatcher {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [string]$SourcePrefix = 'coworker'
    )

    $resolvedPath = [System.IO.Path]::GetFullPath($Path)
    $watchContainer = $true

    if (Test-Path -LiteralPath $resolvedPath) {
        $item = Get-Item -LiteralPath $resolvedPath
        $watchContainer = $item.PSIsContainer
    }
    elseif ([System.IO.Path]::HasExtension($resolvedPath)) {
        $watchContainer = $false
    }

    if ($watchContainer) {
        Ensure-CoworkerDirectory -Path $resolvedPath
        $watchDirectory = $resolvedPath
        $filter = '*'
        $includeSubdirectories = $true
    }
    else {
        $watchDirectory = Split-Path -Parent $resolvedPath
        if ([string]::IsNullOrWhiteSpace($watchDirectory)) {
            throw "Cannot determine watcher directory for path: $resolvedPath"
        }

        Ensure-CoworkerDirectory -Path $watchDirectory
        $filter = Split-Path -Leaf $resolvedPath
        $includeSubdirectories = $false
    }

    $watcher = [System.IO.FileSystemWatcher]::new($watchDirectory, $filter)
    $watcher.IncludeSubdirectories = $includeSubdirectories
    $watcher.NotifyFilter = [System.IO.NotifyFilters]'FileName, DirectoryName, LastWrite, CreationTime'

    $sourceIdentifiers = @()
    foreach ($eventName in @('Created', 'Changed', 'Deleted', 'Renamed')) {
        $sourceIdentifier = 'coworker.{0}.{1}.{2}' -f $SourcePrefix, $eventName.ToLowerInvariant(), ([guid]::NewGuid().ToString('N'))
        Register-ObjectEvent -InputObject $watcher -EventName $eventName -SourceIdentifier $sourceIdentifier | Out-Null
        $sourceIdentifiers += $sourceIdentifier
    }

    $watcher.EnableRaisingEvents = $true

    $registration = @{
        Path              = $resolvedPath
        Directory         = $watchDirectory
        'Filter'          = $filter
        Watcher           = $watcher
        SourceIdentifiers = $sourceIdentifiers
    }

    return $registration
}

function Remove-CoworkerFileWatcher {
    param(
        [Parameter(Mandatory = $true)]
        [psobject]$Registration
    )

    Remove-CoworkerEventSubscription -SourceIdentifiers $Registration.SourceIdentifiers
    if ($null -ne $Registration.Watcher) {
        $Registration.Watcher.EnableRaisingEvents = $false
        $Registration.Watcher.Dispose()
    }
}

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
        # Unparseable lock → treat as stale
    }

    # Stale lock — clean up
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
        # File exists — check staleness
        if (Test-CoworkerScriptLockHeld -ScriptPath $resolvedPath) {
            if ($SkipIfHeld) {
                return $null
            }
            $lockContent = Get-Content -LiteralPath $lockFilePath -Raw -Encoding UTF8
            throw "Script '$resolvedPath' is already running (lock: $lockFilePath, content: $lockContent)"
        }

        # Stale lock was cleaned by Test-CoworkerScriptLockHeld — retry
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
        StartedAt  = (Get-Date).ToUniversalTime().ToString('o')
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

# ── Ensure common tool directories are on PATH ───────────────────────────
# Scheduled tasks run with -NoProfile, so user-profile tool shims (scoop, etc.)
# are not automatically available. Prepend known tool directories to PATH.
$knownToolPaths = @(
    Join-Path $env:USERPROFILE 'scoop\shims'
    Join-Path $env:USERPROFILE 'AppData\Roaming\npm'
    'C:\Program Files\Git\cmd'
)
foreach ($toolPath in $knownToolPaths) {
    if ((Test-Path -LiteralPath $toolPath) -and ($env:PATH -notlike "*$toolPath*")) {
        $env:PATH = "$toolPath;$env:PATH"
    }
}

$COPILOT = @($script:configData['COPILOT'])
if ($script:configData.ContainsKey('CLAUDE')) {
    $CLAUDE = @($script:configData['CLAUDE'])
}
