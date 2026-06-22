# ── Coworker workflow utilities ───────────────────────────────────────────
# Dot-source after config.ps1, agent.ps1, and task-logger.ps1.

function New-AgentPromptArguments {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Prompt,
        [string[]]$AdditionalArguments = @(),
        [string]$Backend = 'copilot'
    )

    return @(New-AgentArguments -BaseArgs $script:agentBaseArgs -Prompt $Prompt -AdditionalArguments $AdditionalArguments -Backend $Backend)
}

function Format-AgentPromptCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    return Format-AgentCommand -Executable $script:agentExecutable -Arguments $Arguments
}

function Ensure-DraftPlaceholders {
    param(
        [Parameter(Mandatory=$true)]
        [string]$DraftDirectory
    )

    foreach ($draftNumber in 1..5) {
        $draftPath = Join-Path $DraftDirectory "$draftNumber.md"
        if (!(Test-Path $draftPath)) {
            Set-Content -Path $draftPath -Value '' -Encoding UTF8
            Write-LogMessage "Created missing draft placeholder: $draftPath" INFO
        }
    }
}

function Resolve-UniquePath {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Directory,
        [Parameter(Mandatory=$true)]
        [string]$BaseName,
        [Parameter(Mandatory=$true)]
        [string]$Extension
    )

    $candidateName = "$BaseName$Extension"
    $candidatePath = Join-Path $Directory $candidateName
    if (!(Test-Path $candidatePath)) {
        return @{ Path = $candidatePath; FileName = $candidateName }
    }

    $counter = 2
    while ($true) {
        $nextName = "$BaseName.$counter$Extension"
        $nextPath = Join-Path $Directory $nextName
        if (!(Test-Path $nextPath)) {
            return @{ Path = $nextPath; FileName = $nextName }
        }
        $counter++
    }
}

function Get-TaskBaseName {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Title,
        [Parameter(Mandatory=$true)]
        [string]$Description,
        [Parameter(Mandatory=$true)]
        [string]$Prompt,
        [Parameter(Mandatory=$true)]
        [string]$Fallback
    )

    $promptSample = $Prompt
    if ($promptSample.Length -gt 600) {
        $promptSample = $promptSample.Substring(0, 600)
    }

    $namingPrompt = @"
Create a short, descriptive task name in kebab-case (3-6 words max). Output only the name.
Title: $Title
Description: $Description
Prompt: $promptSample
"@

    try {
        $nameArguments = New-AgentPromptArguments -Prompt $namingPrompt -Backend $script:agentBackend

        Write-LogVerbose ("Executing Agent for naming: {0}" -f (Format-AgentPromptCommand -Arguments $nameArguments))
        Write-LogVerbose "Naming agent working directory: $($script:agentWorkingDirectory)"

        $nameStdOut = [System.IO.Path]::GetTempFileName()
        $nameStdErr = [System.IO.Path]::GetTempFileName()
        $nameProcess = Start-AgentProcess -Executable $script:agentExecutable -BaseArgs $script:agentBaseArgs -Prompt $namingPrompt -WorkingDirectory $script:agentWorkingDirectory -StdOutPath $nameStdOut -StdErrPath $nameStdErr -NoNewWindow -Backend $script:agentBackend

        $waited = $false
        try {
            $null = Wait-Process -Id $nameProcess.Id -Timeout $script:agentNameTimeoutSeconds -ErrorAction Stop
            $waited = $true
        } catch {
            $waited = $false
            Write-LogMessage "Agent naming timed out after $($script:agentNameTimeoutSeconds)s" WARN
        }

        if (-not $waited -or -not $nameProcess.HasExited) {
            Stop-Process -Id $nameProcess.Id -Force -ErrorAction SilentlyContinue

            if (Test-Path $nameStdErr) {
                $errContent = Get-Content -Path $nameStdErr -Encoding UTF8
                Write-LogVerbose "Naming agent STDERR (Timeout): $errContent"
            }

            Remove-Item $nameStdOut -ErrorAction SilentlyContinue
            Remove-Item $nameStdErr -ErrorAction SilentlyContinue
            return $Fallback
        }

        $rawName = ""
        if (Test-Path $nameStdOut) {
            $rawName = (Get-Content -Path $nameStdOut -Encoding UTF8 | Where-Object { $_ -and $_.Trim() } | Select-Object -First 1)
            Write-LogVerbose "Naming agent STDOUT: $rawName"
        } else {
            Write-LogVerbose "Naming agent STDOUT file not found"
        }

        if (Test-Path $nameStdErr) {
            $errContent = Get-Content $nameStdErr -Encoding UTF8
            if ($errContent) {
                Write-LogVerbose "Naming agent STDERR: $errContent"
            }
        }

        Remove-Item $nameStdOut -ErrorAction SilentlyContinue
        Remove-Item $nameStdErr -ErrorAction SilentlyContinue

        if ($nameProcess.ExitCode -ne 0) {
            Write-LogVerbose "Naming agent exited with code $($nameProcess.ExitCode)"
            return $Fallback
        }

        if ([string]::IsNullOrWhiteSpace($rawName)) {
            Write-LogVerbose "Naming agent returned empty name"
            return $Fallback
        }

        $normalized = $rawName.Trim()
        $normalized = $normalized -replace '\s+', '-'
        $normalized = $normalized -replace '[^A-Za-z0-9._-]', '-'
        $normalized = $normalized -replace '-+', '-'
        $normalized = $normalized.Trim(' ', '.', '-', '_')
        if ($normalized.Length -gt 60) {
            $normalized = $normalized.Substring(0, 60).Trim(' ', '.', '-', '_')
        }

        if ([string]::IsNullOrWhiteSpace($normalized)) {
            return $Fallback
        }

        return $normalized
    }
    catch {
        return $Fallback
    }
}
