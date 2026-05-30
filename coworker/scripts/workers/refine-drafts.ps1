#!/usr/bin/env pwsh

param(
    [string]$Path
)

$ErrorActionPreference = 'Stop'

. (Join-Path $PSScriptRoot 'agent.ps1')

$repoRoot = Get-WorkspaceRoot
$agentCommand = Get-AgentCommand -RepoRoot $repoRoot

$refineRoot = Resolve-TasksPath '0draft\refine'
$readyDir = Join-Path $refineRoot '1ready'
$workingDir = Join-Path $refineRoot '2working'
$doneDir = Join-Path $refineRoot '3done'

foreach ($directory in @($readyDir, $workingDir, $doneDir)) {
    if (-not (Test-Path $directory)) {
        New-Item -ItemType Directory -Path $directory | Out-Null
    }
}

function Ensure-ReadyDraftPlaceholders {
    Ensure-CoworkerDraftRefinementPlaceholders -DraftDirectory $readyDir
}

if ([string]::IsNullOrWhiteSpace($Path)) {
    $Path = $readyDir
}

function Resolve-UniquePath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Directory,
        [Parameter(Mandatory = $true)]
        [string]$BaseName,
        [Parameter(Mandatory = $true)]
        [string]$Extension
    )

    $candidatePath = Join-Path $Directory "$BaseName$Extension"
    if (-not (Test-Path $candidatePath)) {
        return $candidatePath
    }

    $counter = 2
    while ($true) {
        $nextPath = Join-Path $Directory "$BaseName.$counter$Extension"
        if (-not (Test-Path $nextPath)) {
            return $nextPath
        }
        $counter++
    }
}

function Get-RefineTargets {
    param(
        [Parameter(Mandatory = $true)]
        [string]$InputPath
    )

    if (-not (Test-Path $InputPath)) {
        throw "Refine path not found: $InputPath"
    }

    $item = Get-Item $InputPath
    if ($item.PSIsContainer) {
        return @(Get-ChildItem -Path $item.FullName -File | Where-Object { Test-CoworkerActionableDraftRefinementFile -Item $_ } | Sort-Object Name)
    }

    if (-not (Test-CoworkerActionableDraftRefinementFile -Item $item)) {
        return @()
    }

    return @($item)
}

function Invoke-DraftRefinement {
    param(
        [Parameter(Mandatory = $true)]
        [System.IO.FileInfo]$WorkingFile
    )

    $draftContent = Get-Content -Path $WorkingFile.FullName -Raw -Encoding UTF8
    $prompt = @"
Refine the following draft for clarity, coherence, and relevance to the intended audience.
Preserve the original intent and useful structure unless a change materially improves the draft.
Return only the complete refined document content with no surrounding code fences or commentary.

Source file: $($WorkingFile.FullName)

--- BEGIN DRAFT ---
$draftContent
--- END DRAFT ---
"@

    $refinedContent = Invoke-Agent -Prompt $prompt -AdditionalArguments @('--allow-all-tools', '--allow-all-paths') -RepoRoot $repoRoot -WorkingDirectory $repoRoot -CaptureOutput
    if ($LASTEXITCODE -ne 0) {
        throw "Agent exited with code $LASTEXITCODE while refining $($WorkingFile.Name)"
    }

    $refinedContent = $refinedContent.Trim("`r", "`n")
    if ([string]::IsNullOrWhiteSpace($refinedContent)) {
        throw "Agent returned empty output for $($WorkingFile.Name)"
    }

    return $refinedContent
}

Ensure-ReadyDraftPlaceholders

$targets = Get-RefineTargets -InputPath $Path
if ($targets.Count -eq 0) {
    Write-Host "No actionable draft files found in $Path"
    exit 0
}

$failureCount = 0

foreach ($target in $targets) {
    $workingPath = Resolve-UniquePath -Directory $workingDir -BaseName $target.BaseName -Extension $target.Extension
    Move-Item -Path $target.FullName -Destination $workingPath -Force
    $workingFile = Get-Item $workingPath
    Write-Host "Moved draft to working: $workingPath"

    try {
        $refinedContent = Invoke-DraftRefinement -WorkingFile $workingFile
        Set-Content -Path $workingFile.FullName -Value $refinedContent -Encoding UTF8

        $donePath = Resolve-UniquePath -Directory $doneDir -BaseName $workingFile.BaseName -Extension $workingFile.Extension
        Move-Item -Path $workingFile.FullName -Destination $donePath -Force
        Write-Host "Refined draft moved to done: $donePath"
    }
    catch {
        $failureCount++
        Write-Host "Failed to refine $($workingFile.Name): $_" -ForegroundColor Red
    }

    Ensure-ReadyDraftPlaceholders
}

if ($failureCount -gt 0) {
    exit 1
}
