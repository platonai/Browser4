#!/usr/bin/env pwsh

param(
    [string]$Prompt,
    [string[]]$AdditionalArguments = @(),
    [switch]$AllowAllTools,
    [switch]$AllowAllPaths,
    [switch]$CaptureOutput,
    [switch]$UseTargetRepository
)

$configPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'config.ps1'
. $configPath

function Get-AgentRepoRoot {
    param(
        [switch]$UseTargetRepository
    )

    if ($UseTargetRepository) {
        return Get-TargetRepositoryRoot
    }

    return Get-WorkspaceRoot
}

function Assert-AgentDirectory {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [Parameter(Mandatory = $true)]
        [string]$ParameterName
    )

    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw "$ParameterName cannot be empty."
    }

    if (-not (Test-Path -LiteralPath $Path -PathType Container)) {
        throw "$ParameterName does not exist: $Path"
    }

    return [System.IO.Path]::GetFullPath($Path)
}

function Get-BackendType {
    if ($CLAUDE) {
        if ($CLAUDE -is [string]) {
            throw "CLAUDE must be defined as a PowerShell array in $configPath"
        }
        if ($CLAUDE.Count -lt 1) {
            throw 'CLAUDE must include at least an executable'
        }
        return 'claude'
    }
    return 'copilot'
}

function Get-AgentCommand {
    param(
        [string]$RepoRoot,
        [string]$WorkingDirectory,
        [switch]$UseTargetRepository
    )

    if (-not $PSBoundParameters.ContainsKey('RepoRoot')) {
        $RepoRoot = Get-AgentRepoRoot -UseTargetRepository:$UseTargetRepository
    }
    $RepoRoot = Assert-AgentDirectory -Path $RepoRoot -ParameterName 'RepoRoot'

    if (-not $PSBoundParameters.ContainsKey('WorkingDirectory') -or [string]::IsNullOrWhiteSpace($WorkingDirectory)) {
        $WorkingDirectory = $RepoRoot
    }
    $WorkingDirectory = Assert-AgentDirectory -Path $WorkingDirectory -ParameterName 'WorkingDirectory'

    $backend = Get-BackendType

    if ($backend -eq 'claude') {
        return [pscustomobject]@{
            RepoRoot         = $RepoRoot
            WorkingDirectory = $WorkingDirectory
            ConfigPath       = $configPath
            Executable       = $CLAUDE[0]
            BaseArgs         = @($CLAUDE | Select-Object -Skip 1)
            Backend          = 'claude'
        }
    }

    if (-not $COPILOT) {
        $COPILOT = @('gh', 'copilot')
    }

    if ($COPILOT -is [string]) {
        throw "COPILOT must be defined as a PowerShell array in $configPath"
    }

    if ($COPILOT.Count -lt 2) {
        throw 'COPILOT must include an executable and at least one argument'
    }

    return [pscustomobject]@{
        RepoRoot         = $RepoRoot
        WorkingDirectory = $WorkingDirectory
        ConfigPath       = $configPath
        Executable       = $COPILOT[0]
        BaseArgs         = @($COPILOT | Select-Object -Skip 1)
        Backend          = 'copilot'
    }
}

function New-AgentArguments {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$BaseArgs,
        [string]$Prompt,
        [string[]]$AdditionalArguments = @(),
        [string]$Backend = 'copilot'
    )

    $arguments = @($BaseArgs)
    if ($PSBoundParameters.ContainsKey('Prompt')) {
        if ($Backend -eq 'claude') {
            $arguments += '-p'
            $arguments += $Prompt
        }
        else {
            $arguments += '--'
            $arguments += '-p'
            $arguments += $Prompt
        }
    }

    if ($AdditionalArguments) {
        if ($Backend -eq 'claude') {
            $copilotOnlyFlags = @('--allow-all-tools', '--allow-all-paths')
            $filtered = foreach ($arg in $AdditionalArguments) {
                if ($arg -notin $copilotOnlyFlags) {
                    $arg
                }
            }
            $arguments += $filtered
        }
        else {
            $arguments += $AdditionalArguments
        }
    }

    return @($arguments)
}

function Format-AgentCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    $formattedArguments = foreach ($argument in $Arguments) {
        if ([string]::IsNullOrEmpty($argument)) {
            "''"
        }
        elseif ($argument -match '[\s"`]') {
            "'" + ($argument -replace "'", "''") + "'"
        }
        else {
            $argument
        }
    }

    return ('{0} {1}' -f $Executable, ($formattedArguments -join ' ')).Trim()
}

function ConvertTo-WindowsCommandLineArgument {
    param(
        [AllowEmptyString()]
        [string]$Argument
    )

    if ($null -eq $Argument -or $Argument.Length -eq 0) {
        return '""'
    }

    if ($Argument -notmatch '[\s"]') {
        return $Argument
    }

    $builder = [System.Text.StringBuilder]::new()
    [void]$builder.Append('"')

    $backslashCount = 0
    foreach ($character in $Argument.ToCharArray()) {
        if ($character -eq '\') {
            $backslashCount++
            continue
        }

        if ($character -eq '"') {
            if ($backslashCount -gt 0) {
                [void]$builder.Append('\' * ($backslashCount * 2))
                $backslashCount = 0
            }
            [void]$builder.Append('\"')
            continue
        }

        if ($backslashCount -gt 0) {
            [void]$builder.Append('\' * $backslashCount)
            $backslashCount = 0
        }

        [void]$builder.Append($character)
    }

    if ($backslashCount -gt 0) {
        [void]$builder.Append('\' * ($backslashCount * 2))
    }

    [void]$builder.Append('"')
    return $builder.ToString()
}

function Start-AgentProcess {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Executable,
        [Parameter(Mandatory = $true)]
        [string[]]$BaseArgs,
        [string]$Prompt,
        [string[]]$AdditionalArguments = @(),
        [string]$WorkingDirectory,
        [string]$StdOutPath,
        [string]$StdErrPath,
        [switch]$NoNewWindow,
        [string]$Backend = 'copilot'
    )

    $arguments = New-AgentArguments -BaseArgs $BaseArgs -Prompt $Prompt -AdditionalArguments $AdditionalArguments -Backend $Backend
    $startProcessArgs = @{
        FilePath = $Executable
        PassThru = $true
    }

    $isWindowsPlatform = $false
    if ($null -ne $PSVersionTable -and $PSVersionTable.PSEdition -eq 'Desktop') {
        $isWindowsPlatform = $true
    }
    elseif ($null -ne (Get-Variable -Name IsWindows -ErrorAction SilentlyContinue)) {
        $isWindowsPlatform = [bool]$IsWindows
    }

    if ($isWindowsPlatform) {
        # Use one escaped command line on Windows to preserve multiline/quoted prompt text.
        $escapedArguments = foreach ($argument in $arguments) {
            ConvertTo-WindowsCommandLineArgument -Argument $argument
        }
        $startProcessArgs.ArgumentList = ($escapedArguments -join ' ')
    }
    else {
        $startProcessArgs.ArgumentList = $arguments
    }

    if ($PSBoundParameters.ContainsKey('WorkingDirectory') -and -not [string]::IsNullOrWhiteSpace($WorkingDirectory)) {
        $startProcessArgs.WorkingDirectory = $WorkingDirectory
    }
    if ($NoNewWindow) {
        $startProcessArgs.NoNewWindow = $true
    }
    if ($PSBoundParameters.ContainsKey('StdOutPath')) {
        $startProcessArgs.RedirectStandardOutput = $StdOutPath
    }
    if ($PSBoundParameters.ContainsKey('StdErrPath')) {
        $startProcessArgs.RedirectStandardError = $StdErrPath
    }

    return Start-Process @startProcessArgs
}

function Invoke-Agent {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,
        [string[]]$AdditionalArguments = @(),
        [string]$RepoRoot,
        [string]$WorkingDirectory,
        [switch]$CaptureOutput,
        [switch]$UseTargetRepository
    )

    $commandArgs = @{
        UseTargetRepository = $UseTargetRepository
    }
    if ($PSBoundParameters.ContainsKey('RepoRoot')) {
        $commandArgs.RepoRoot = $RepoRoot
    }
    if ($PSBoundParameters.ContainsKey('WorkingDirectory')) {
        $commandArgs.WorkingDirectory = $WorkingDirectory
    }

    $command = Get-AgentCommand @commandArgs
    if ($CaptureOutput) {
        $stdOutPath = [System.IO.Path]::GetTempFileName()
        $stdErrPath = [System.IO.Path]::GetTempFileName()

        try {
            $process = Start-AgentProcess -Executable $command.Executable -BaseArgs $command.BaseArgs -Prompt $Prompt -AdditionalArguments $AdditionalArguments -WorkingDirectory $command.WorkingDirectory -StdOutPath $stdOutPath -StdErrPath $stdErrPath -NoNewWindow -Backend $command.Backend
            $process.WaitForExit()
            $global:LASTEXITCODE = $process.ExitCode

            if (Test-Path $stdErrPath) {
                $errorOutput = Get-Content -Path $stdErrPath -Raw -Encoding UTF8
                if (-not [string]::IsNullOrWhiteSpace($errorOutput)) {
                    [Console]::Error.Write($errorOutput)
                }
            }

            if (Test-Path $stdOutPath) {
                return Get-Content -Path $stdOutPath -Raw -Encoding UTF8
            }

            return ''
        }
        finally {
            Remove-Item $stdOutPath -ErrorAction SilentlyContinue
            Remove-Item $stdErrPath -ErrorAction SilentlyContinue
        }
    }

    $process = Start-AgentProcess -Executable $command.Executable -BaseArgs $command.BaseArgs -Prompt $Prompt -AdditionalArguments $AdditionalArguments -WorkingDirectory $command.WorkingDirectory -NoNewWindow -Backend $command.Backend
    $process.WaitForExit()
    $global:LASTEXITCODE = $process.ExitCode
    return $null
}

if ($MyInvocation.InvocationName -ne '.') {
    if ([string]::IsNullOrWhiteSpace($Prompt)) {
        throw 'Prompt is required when executing agent.ps1 directly.'
    }

    $directArguments = @($AdditionalArguments)
    if ($AllowAllTools) {
        $directArguments += '--allow-all-tools'
    }
    if ($AllowAllPaths) {
        $directArguments += '--allow-all-paths'
    }

    if ($CaptureOutput) {
        $output = Invoke-Agent -Prompt $Prompt -AdditionalArguments $directArguments -CaptureOutput -UseTargetRepository:$UseTargetRepository
        if (-not [string]::IsNullOrEmpty($output)) {
            Write-Output $output
        }
        exit $LASTEXITCODE
    }

    $command = Get-AgentCommand -UseTargetRepository:$UseTargetRepository
    $process = Start-AgentProcess -Executable $command.Executable -BaseArgs $command.BaseArgs -Prompt $Prompt -AdditionalArguments $directArguments -WorkingDirectory $command.WorkingDirectory -NoNewWindow -Backend $command.Backend
    $process.WaitForExit()
    exit $process.ExitCode
}
