param(
    [Parameter(Mandatory=$true)]
    [string]$FilePath
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $FilePath)) {
    Write-Error "File or directory not found: $FilePath"
    exit 1
}

$inputPath = Resolve-Path $FilePath
$FilePath = $inputPath.Path

$agentHelper = Join-Path $PSScriptRoot "agent.ps1"
. $agentHelper
$repoRoot = Get-WorkspaceRoot
$agentCommand = Get-AgentCommand -RepoRoot $repoRoot

function Get-GeneratedTaskName {
    param (
        [string]$Content,
        [string]$OriginalBaseName,
        [string]$OriginalName
    )

    # Initialize variables
    $title = ""
    $description = ""
    $prompt = ""

    # Parse structured content
    if ($Content -match "(?s)\A\s*Title:\s*(?<title>.*?)(\r\n|\n)Description:\s*(?<desc>.*?)(\r\n|\n)Prompt:\s*(?<prompt>.*)$") {
        $title = $Matches['title'].Trim()
        $description = $Matches['desc'].Trim()
        $prompt = $Matches['prompt'].Trim()
    } else {
        $title = $OriginalBaseName
        $description = "Task from $OriginalName"
        $prompt = $Content
    }

    $promptSample = $prompt
    if ($promptSample.Length -gt 600) {
        $promptSample = $promptSample.Substring(0, 600)
    }

    $namingPrompt = "Create a short, descriptive task name in English kebab-case (3-6 words max). Output only the name. DO NOT use any tools. DO NOT search for files. Just use the provided text. Title: $title Description: $description Prompt: $promptSample"

    $agentNameTimeoutSeconds = 120
    $Fallback = $title -replace '[\\/*?:"<>|]', '_'
    if ([string]::IsNullOrWhiteSpace($Fallback)) {
        $Fallback = "task"
    }

    try {
        $nameStdOut = [System.IO.Path]::GetTempFileName()
        $nameStdErr = [System.IO.Path]::GetTempFileName()

        try {
            $nameProcess = Start-AgentProcess -Executable $agentCommand.Executable -BaseArgs $agentCommand.BaseArgs -Prompt $namingPrompt -WorkingDirectory $repoRoot -StdOutPath $nameStdOut -StdErrPath $nameStdErr -NoNewWindow -Backend $agentCommand.Backend
        } catch {
            Write-Host "DEBUG: Start-Process failed: $_"
            return "Error: Start-Process failed"
        }

        try {
            $null = Wait-Process -Id $nameProcess.Id -Timeout $agentNameTimeoutSeconds -ErrorAction Stop
        } catch {
            if (-not $nameProcess.HasExited) {
                & "Stop-Process" -Id $nameProcess.Id -Force -ErrorAction SilentlyContinue
                Write-Host "DEBUG: Timeout waiting for process"
                return "Error: Timeout"
            }
        }

        $rawName = ""
        if (Test-Path $nameStdOut) {
            $lines = Get-Content -Path $nameStdOut -Encoding UTF8 | Where-Object { $_ -and $_.Trim() }

            $cleanLines = @($lines | Where-Object {
                $_ -notmatch '^\s*\u25CF' -and
                        $_ -notmatch '^\s*\u0024' -and
                        $_ -notmatch '^\s*\u2514' -and
                        $_ -notmatch '^error:' -and
                        $_ -notmatch '^Try ''copilot --help''' -and
                        $_ -notmatch '^Total' -and
                        $_ -notmatch '^API' -and
                        $_ -notmatch '^Breakdown' -and
                        $_ -notmatch '^\s+gemini' -and
                        $_ -notmatch '^\s+gpt' -and
                        $_ -notmatch '^Days' -and
                        $_ -notmatch '^Hours' -and
                        $_ -notmatch '^Minutes' -and
                        $_ -notmatch '^Seconds' -and
                        $_ -notmatch '^Milliseconds' -and
                        $_ -notmatch '^Ticks'
            })

            if ($cleanLines.Count -gt 0) {
                $rawName = $cleanLines[-1].Trim()
                if ($rawName -match '^"(.*)"$') {
                    $rawName = $Matches[1]
                }
            }
        }

        if (Test-Path $nameStdErr) {
            $errContent = Get-Content -Path $nameStdErr -Encoding UTF8
            if ($errContent) {
                Write-Host "DEBUG: GH Stderr: $errContent"
            }
        }

        Remove-Item $nameStdOut -ErrorAction SilentlyContinue
        Remove-Item $nameStdErr -ErrorAction SilentlyContinue

        if ([string]::IsNullOrWhiteSpace($rawName)) {
            return "Error: Empty output"
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
            return "Error: Empty normalized name"
        } else {
            return $normalized
        }
    }
    catch {
        Write-Host "DEBUG: Exception: $_"
        return "Error: Exception $_"
    }
}

if (Test-Path -Path $FilePath -PathType Container) {
    # Directory mode
    $files = Get-ChildItem -Path $FilePath -File | Where-Object { $_.BaseName -match '^\d+$' }
    foreach ($file in $files) {
        Write-Host "Processing $($file.Name)..."
        $content = Get-Content -Path $file.FullName -Raw -Encoding UTF8
        $newName = Get-GeneratedTaskName -Content $content -OriginalBaseName $file.BaseName -OriginalName $file.Name

        if ($newName -ne $file.BaseName) {
            $newFileName = "$newName$($file.Extension)"
            $newPath = Join-Path $file.DirectoryName $newFileName
            if (Test-Path $newPath) {
                Write-Host "Skipping $($file.Name): $newFileName already exists."
            } else {
                Rename-Item -Path $file.FullName -NewName $newFileName
                Write-Host "Renamed $($file.Name) to $newFileName"
            }
        }
    }
} else {
    # Single file mode
    $content = Get-Content -Path $FilePath -Raw -Encoding UTF8
    $fileItem = Get-Item $FilePath
    $newName = Get-GeneratedTaskName -Content $content -OriginalBaseName $fileItem.BaseName -OriginalName $fileItem.Name
    Write-Output $newName
}
