# ── File system watchers and file validation utilities ─────────────────────

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
