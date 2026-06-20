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
