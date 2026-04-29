param(
    [string]$Prompt,
    [string[]]$AdditionalArguments = @(),
    [switch]$AllowAllTools,
    [switch]$AllowAllPaths,
    [switch]$CaptureOutput
)

$configPath = Join-Path (Split-Path -Parent $PSScriptRoot) 'config.ps1'
. $configPath

function Get-GHCopilotRepoRoot {
    return Get-WorkspaceRoot
}

function Get-GHCopilotCommand {
    param(
        [string]$RepoRoot = (Get-GHCopilotRepoRoot)
    )

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
        WorkingDirectory = $RepoRoot
        ConfigPath       = $configPath
        Executable       = $COPILOT[0]
        BaseArgs         = @($COPILOT | Select-Object -Skip 1)
    }
}

function New-GHCopilotArguments {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$BaseArgs,
        [string]$Prompt,
        [string[]]$AdditionalArguments = @()
    )

    $arguments = @($BaseArgs)
    if ($PSBoundParameters.ContainsKey('Prompt')) {
        $arguments += '--'
        $arguments += '-p'
        $arguments += $Prompt
    }

    if ($AdditionalArguments) {
        $arguments += $AdditionalArguments
    }

    return @($arguments)
}

function Format-GHCopilotCommand {
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

function Start-GHCopilotProcess {
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
        [switch]$NoNewWindow
    )

    $arguments = New-GHCopilotArguments -BaseArgs $BaseArgs -Prompt $Prompt -AdditionalArguments $AdditionalArguments
    $startProcessArgs = @{
        FilePath = $Executable
        PassThru = $true
    }

    # Always build an escaped command-line string rather than passing the raw array.
    # On Linux/macOS, Start-Process joins array elements with spaces without quoting, so
    # multi-word arguments (e.g. the prompt) get split into separate arguments.
    # The double-quote escaping used by ConvertTo-WindowsCommandLineArgument is also
    # honoured by .NET's cross-platform argument parser on Linux/macOS.
    $escapedArguments = foreach ($argument in $arguments) {
        ConvertTo-WindowsCommandLineArgument -Argument $argument
    }
    $startProcessArgs.ArgumentList = ($escapedArguments -join ' ')

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

function Invoke-GHCopilot {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,
        [string[]]$AdditionalArguments = @(),
        [string]$RepoRoot = (Get-GHCopilotRepoRoot),
        [string]$WorkingDirectory = $RepoRoot,
        [switch]$CaptureOutput
    )

    $command = Get-GHCopilotCommand -RepoRoot $RepoRoot
    if ($CaptureOutput) {
        $captureArguments = @($AdditionalArguments)
        if ($captureArguments -notcontains '--silent') {
            $captureArguments += '--silent'
        }

        $stdOutPath = [System.IO.Path]::GetTempFileName()
        $stdErrPath = [System.IO.Path]::GetTempFileName()

        try {
            $process = Start-GHCopilotProcess -Executable $command.Executable -BaseArgs $command.BaseArgs -Prompt $Prompt -AdditionalArguments $captureArguments -WorkingDirectory $WorkingDirectory -StdOutPath $stdOutPath -StdErrPath $stdErrPath -NoNewWindow
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

    $process = Start-GHCopilotProcess -Executable $command.Executable -BaseArgs $command.BaseArgs -Prompt $Prompt -AdditionalArguments $AdditionalArguments -WorkingDirectory $WorkingDirectory -NoNewWindow
    $process.WaitForExit()
    $global:LASTEXITCODE = $process.ExitCode
    return $null
}

if ($MyInvocation.InvocationName -ne '.') {
    if ([string]::IsNullOrWhiteSpace($Prompt)) {
        throw 'Prompt is required when executing gh-copilot.ps1 directly.'
    }

    $directArguments = @($AdditionalArguments)
    if ($AllowAllTools) {
        $directArguments += '--allow-all-tools'
    }
    if ($AllowAllPaths) {
        $directArguments += '--allow-all-paths'
    }

    if ($CaptureOutput) {
        $output = Invoke-GHCopilot -Prompt $Prompt -AdditionalArguments $directArguments -CaptureOutput
        if (-not [string]::IsNullOrEmpty($output)) {
            Write-Output $output
        }
        exit $LASTEXITCODE
    }

    $command = Get-GHCopilotCommand
    $process = Start-GHCopilotProcess -Executable $command.Executable -BaseArgs $command.BaseArgs -Prompt $Prompt -AdditionalArguments $directArguments -WorkingDirectory $command.WorkingDirectory -NoNewWindow
    $process.WaitForExit()
    exit $process.ExitCode
}
