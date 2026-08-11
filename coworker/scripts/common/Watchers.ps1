# ── File system watchers and file validation utilities ─────────────────────

function Test-CoworkerPlaceholderFile {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item
    )

    return $Item.Name -eq '.gitkeep'
}

# ── Memoization cache for Test-CoworkerDotPath ──────────────────────────
# Keyed by directory path so all files in the same directory hit the cache
# after the first lookup.  Uses a generic Dictionary for O(1) lookups.
$script:DotPathCache = [System.Collections.Generic.Dictionary[string, bool]]::new()

function Test-CoworkerDotPath {
    <#
    .SYNOPSIS
        Check whether a file or directory lives under a dot-prefixed path segment.
    .DESCRIPTION
        Uses string-based path-segment inspection (no filesystem parent traversal)
        with a per-directory memoization cache so repeated checks on files in the
        same directory return instantly.
    #>
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileSystemInfo]$Item
    )

    # Resolve the containing directory as the cache key
    $dirPath = if ($Item.PSIsContainer) {
        $Item.FullName
    } else {
        Split-Path -Parent $Item.FullName
    }

    # Fast path: cache hit
    $cached = $false
    if ($script:DotPathCache.TryGetValue($dirPath, [ref]$cached)) {
        return $cached
    }

    # String-based segment scan — walks only the path string, never the filesystem
    $result = $false
    $fullName = $Item.FullName
    foreach ($segment in $fullName.Split(
        [System.IO.Path]::DirectorySeparatorChar,
        [System.IO.Path]::AltDirectorySeparatorChar
    )) {
        if ($segment.Length -gt 0 -and $segment[0] -eq '.') {
            $result = $true
            break
        }
    }

    $script:DotPathCache[$dirPath] = $result
    return $result
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
