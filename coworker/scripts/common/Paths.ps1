# ── Path resolution utilities ─────────────────────────────────────────────

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
        [string]$BaseDirectory = $script:__CoworkerScriptsRoot
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'Configured path cannot be empty.'
    }

    # Expand ~ to the user's home directory ($HOME on all platforms)
    if ($Path.StartsWith('~')) {
        $Path = $HOME + $Path.Substring(1)
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
    $path = [string](Get-CoworkerConfigValue -Map $pathsConfig -Key 'WorkspaceRoot' -DefaultValue '../..')
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
    $path = [string](Get-CoworkerConfigValue -Map $pathsConfig -Key 'TasksRoot' -DefaultValue '../tasks')
    return Resolve-CoworkerConfiguredPath -Path $path
}

function Get-LogDirectory {
    $pathsConfig = Get-CoworkerConfigValue -Map $script:configData -Key 'Paths' -DefaultValue @{}
    $path = [string](Get-CoworkerConfigValue -Map $pathsConfig -Key 'LogDirectory' -DefaultValue '~/.browser4-coworker/tasks/300logs')
    $resolved = Resolve-CoworkerConfiguredPath -Path $path -BaseDirectory (Get-WorkspaceRoot)

    # Ensure the log directory exists
    if (-not (Test-Path -LiteralPath $resolved)) {
        New-Item -ItemType Directory -Path $resolved -Force | Out-Null
    }

    return $resolved
}

function Get-SchedulerWorkingDirectory {
    $schedulerConfig = Get-CoworkerConfigValue -Map $script:configData -Key 'Scheduler' -DefaultValue @{}
    $path = [string](Get-CoworkerConfigValue -Map $schedulerConfig -Key 'WorkingDirectory' -DefaultValue '../..')
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

<#
.SYNOPSIS
    Resolve a unique file path in a directory by appending a numeric suffix
    when a file with the same base name already exists.

.DESCRIPTION
    Canonical version — previously duplicated in workflow.ps1 and
    refine-drafts.ps1.  Returns a hashtable with Path and FileName.
#>
function Resolve-UniquePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Directory,
        [Parameter(Mandatory = $true)]
        [string]$BaseName,
        [Parameter(Mandatory = $true)]
        [string]$Extension
    )

    $candidateName = "$BaseName$Extension"
    $candidatePath = Join-Path $Directory $candidateName
    if (-not (Test-Path $candidatePath)) {
        return @{ Path = $candidatePath; FileName = $candidateName }
    }

    $counter = 2
    while ($true) {
        $nextName = "$BaseName.$counter$Extension"
        $nextPath = Join-Path $Directory $nextName
        if (-not (Test-Path $nextPath)) {
            return @{ Path = $nextPath; FileName = $nextName }
        }
        $counter++
    }
}
