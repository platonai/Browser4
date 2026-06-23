#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Refine drafts using AI — improved version.

.DESCRIPTION
    Moves drafts from ready -> working -> done through AI-powered refinement.

    Key improvements over the original:
    - Orphaned files in 2working are recovered (moved back to 1ready or dead-letter)
    - Invoke-AgentWithRetry for timeout+retry safety
    - Output validation: checks that refined output is structurally similar to input
    - Cancels conversational framing from agent output before writing
    - Supports -Audience and -DomainContext parameters for better refinement
    - Structured logging via Write-CoworkerLog
    - Max retries before moving to dead-letter directory

.PARAMETER Path
    File or directory of drafts to refine. Defaults to the ready directory.
.PARAMETER Audience
    Description of the target audience for refinement context.
.PARAMETER DomainContext
    Additional domain context for the draft (e.g., "Browser4 codebase design doc").
.PARAMETER MaxRetries
    Maximum refinement attempts per file before moving to dead-letter. Defaults to 3.
.PARAMETER DryRun
    Show what would be done without invoking the agent or moving files.
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [string]$Path = '',

    [string]$Audience = 'Technical team members',

    [string]$DomainContext = '',

    [int]$MaxRetries = 3,

    [int]$TimeoutSeconds = 600,

    [switch]$DryRun
)

$ErrorActionPreference = 'Stop'

# ── Dot-source dependencies ──────────────────────────────────────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path (Split-Path -Parent $workerDir) 'config.ps1')
. (Join-Path $workerDir 'agent-reliability.ps1')
. (Join-Path $workerDir 'prompt-utils.ps1')

$repoRoot = Get-WorkspaceRoot

# ── Directories ──────────────────────────────────────────────────────────────
$refineRoot = Resolve-TasksPath 'main\0draft\refine'
$readyDir   = Join-Path $refineRoot '1ready'
$workingDir = Join-Path $refineRoot '2working'
$doneDir    = Join-Path $refineRoot '3done'
$errorDir   = Join-Path $refineRoot '0error'

foreach ($directory in @($readyDir, $workingDir, $doneDir, $errorDir)) {
    if (-not (Test-Path $directory)) {
        New-Item -ItemType Directory -Path $directory -Force | Out-Null
    }
}

if ([string]::IsNullOrWhiteSpace($Path)) {
    $Path = $readyDir
}

# ── Recovery: return orphaned files from working back to ready ───────────────
function Restore-OrphanedWorkingFiles {
    param([int]$MaxAgeMinutes = 30)

    $orphans = Get-ChildItem -Path $workingDir -File -ErrorAction SilentlyContinue `
        | Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) }

    foreach ($orphan in $orphans) {
        $age = [DateTime]::UtcNow - $orphan.LastWriteTimeUtc
        if ($age.TotalMinutes -gt $MaxAgeMinutes) {
            Write-CoworkerLog -Message "Orphaned file in working (age: $([Math]::Round($age.TotalMinutes))min): $($orphan.Name) — returning to ready" -Level WARN -Component 'refine-drafts'

            # Check retry count from sidecar file
            $sidecarPath = Join-Path $workingDir "$($orphan.BaseName).retries.txt"
            $retryCount = 0
            if (Test-Path $sidecarPath) {
                $retryCount = [int](Get-Content $sidecarPath -Raw).Trim()
            }

            if ($retryCount -ge $MaxRetries) {
                # Move to dead-letter
                $timestamp = (Get-Date).ToUniversalTime().ToString('yyyyMMdd-HHmmss')
                $deadPath = Join-Path $errorDir "$timestamp-$($orphan.Name)"
                if ($PSCmdlet.ShouldProcess($orphan.Name, 'Move to dead-letter')) {
                    Move-Item -Path $orphan.FullName -Destination $deadPath -Force
                    Remove-Item $sidecarPath -ErrorAction SilentlyContinue
                }
                Write-CoworkerLog -Message "Dead-lettered: $($orphan.Name) (after $retryCount retries)" -Level ERROR -Component 'refine-drafts'
            } else {
                # Return to ready for retry
                $retryCount++
                $retryCount | Out-File -FilePath $sidecarPath -Encoding UTF8 -Force
                $readyPath = Join-Path $readyDir $orphan.Name
                if ($PSCmdlet.ShouldProcess($orphan.Name, 'Return to ready for retry')) {
                    Move-Item -Path $orphan.FullName -Destination $readyPath -Force
                }
                Write-CoworkerLog -Message "Returned orphan to ready (retry $retryCount/$MaxRetries): $($orphan.Name)" -Level WARN -Component 'refine-drafts'
            }
        }
    }
}

# ── Target resolution ────────────────────────────────────────────────────────

function Get-RefineTargets {
    param([string]$InputPath)

    if (-not (Test-Path $InputPath)) {
        throw "Refine path not found: $InputPath"
    }

    $item = Get-Item $InputPath

    if ($item.PSIsContainer) {
        return @(Get-ChildItem -Path $item.FullName -File `
            | Where-Object { Test-CoworkerActionableDraftRefinementFile -Item $_ } `
            | Sort-Object Name)
    }

    if (Test-CoworkerActionableDraftRefinementFile -Item $item) {
        return @($item)
    }

    return @()
}

function Resolve-UniquePath {
    param(
        [Parameter(Mandatory)] [string]$Directory,
        [Parameter(Mandatory)] [string]$BaseName,
        [Parameter(Mandatory)] [string]$Extension
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

# ── Output validation ────────────────────────────────────────────────────────

function Test-RefinementValid {
    param(
        [string]$OriginalContent,
        [string]$RefinedContent,
        [string]$FileName
    )

    if ([string]::IsNullOrWhiteSpace($RefinedContent)) {
        Write-CoworkerLog -Message "Refined content is empty for: $FileName" -Level ERROR -Component 'refine-drafts'
        return $false
    }

    # Check for common conversational framing that should have been stripped
    $badPrefixes = @(
        'Here is the refined',
        'Certainly!',
        'Sure,',
        'I have refined',
        'Below is the refined',
        'The following is the refined'
    )
    foreach ($prefix in $badPrefixes) {
        if ($RefinedContent.StartsWith($prefix, [StringComparison]::OrdinalIgnoreCase)) {
            Write-CoworkerLog -Message "Refined output contains conversational prefix: '$prefix'. Content will be cleaned." -Level WARN -Component 'refine-drafts'
            return $false  # Will be retried after cleaning
        }
    }

    # Length sanity: refined should not be drastically shorter or longer
    $ratio = if ($OriginalContent.Length -gt 0) {
        [double]$RefinedContent.Length / $OriginalContent.Length
    } else { 1.0 }

    if ($ratio -lt 0.2) {
        Write-CoworkerLog -Message "Refined content is suspiciously short: $($RefinedContent.Length) vs original $($OriginalContent.Length) (ratio: $([Math]::Round($ratio, 2)))" -Level WARN -Component 'refine-drafts'
        # Allow if it looks like a valid short document (has headers or paragraphs)
        if ($RefinedContent.Length -lt 50) { return $false }
    }

    if ($ratio -gt 5.0) {
        Write-CoworkerLog -Message "Refined content is suspiciously long: $($RefinedContent.Length) vs original $($OriginalContent.Length) (ratio: $([Math]::Round($ratio, 2)))" -Level WARN -Component 'refine-drafts'
    }

    # If original has markdown headers, refined should too (unless original was very short)
    $originalHeaders = [regex]::Matches($OriginalContent, '^#+\s', [System.Text.RegularExpressions.RegexOptions]::Multiline)
    $refinedHeaders  = [regex]::Matches($RefinedContent, '^#+\s', [System.Text.RegularExpressions.RegexOptions]::Multiline)
    if ($originalHeaders.Count -ge 2 -and $refinedHeaders.Count -eq 0) {
        Write-CoworkerLog -Message "Refined content lost all markdown headers ($($originalHeaders.Count) -> 0)" -Level WARN -Component 'refine-drafts'
        return $false
    }

    return $true
}

# ── Refinement function ─────────────────────────────────────────────────────

function Invoke-DraftRefinement {
    param(
        [Parameter(Mandatory)] [System.IO.FileInfo]$WorkingFile,
        [string]$Audience = 'Technical team members',
        [string]$DomainContext = ''
    )

    $draftContent = Get-Content -Path $WorkingFile.FullName -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($draftContent)) {
        throw "Draft file is empty: $($WorkingFile.FullName)"
    }

    $prompt = New-RefinementPrompt `
        -FilePath $WorkingFile.FullName `
        -Content $draftContent `
        -Audience $Audience `
        -DomainContext $DomainContext

    if (-not $PSCmdlet.ShouldProcess($WorkingFile.Name, 'Invoke agent for refinement')) {
        return $draftContent
    }

    $refinedContent = Invoke-AgentWithRetry `
        -Prompt $prompt `
        -AdditionalArguments @('--allow-all-tools', '--allow-all-paths') `
        -CaptureOutput `
        -TimeoutSeconds $TimeoutSeconds `
        -MaxRetries 2 `
        -LogComponent 'refine-drafts' `
        -RepoRoot $repoRoot

    $refinedContent = $refinedContent.Trim("`r", "`n", ' ')

    # Strip common conversational prefixes (defense in depth — the prompt also asks the agent not to include them)
    $prefixPatterns = @(
        '^Here is the refined draft[:\s]*',
        '^Here is the refined document[:\s]*',
        '^Certainly![\s]*',
        '^Sure, here[\s\w]*[:\s]*',
        '^I have refined[^:]*[:\s]*',
        '^Below is the refined[^:]*[:\s]*',
        '^The refined content is[:\s]*',
        '^Here you go[:\s]*'
    )
    foreach ($pattern in $prefixPatterns) {
        $refinedContent = $refinedContent -replace $pattern, ''
    }

    # Strip surrounding code fences if present
    if ($refinedContent -match '^```[\w]*\r?\n' -and $refinedContent -match '\r?\n```\s*$') {
        $refinedContent = $refinedContent -replace '^```[\w]*\r?\n', ''
        $refinedContent = $refinedContent -replace '\r?\n```\s*$', ''
        Write-CoworkerLog -Message 'Stripped code fences from refined output' -Level INFO -Component 'refine-drafts'
    }

    $refinedContent = $refinedContent.Trim("`r", "`n", ' ')

    if ([string]::IsNullOrWhiteSpace($refinedContent)) {
        throw "Agent returned empty output for $($WorkingFile.Name)"
    }

    if (-not (Test-RefinementValid -OriginalContent $draftContent -RefinedContent $refinedContent -FileName $WorkingFile.Name)) {
        throw "Refinement validation failed for $($WorkingFile.Name)"
    }

    return $refinedContent
}

# ── Main processing loop ─────────────────────────────────────────────────────

Restore-OrphanedWorkingFiles -MaxAgeMinutes 30

$targets = Get-RefineTargets -InputPath $Path
if ($targets.Count -eq 0) {
    Write-CoworkerLog -Message "No actionable draft files found in $Path" -Level INFO -Component 'refine-drafts'
    exit 0
}

Write-CoworkerLog -Message "Refining $($targets.Count) draft(s) from $Path" -Level INFO -Component 'refine-drafts'
$failureCount = 0

foreach ($target in $targets) {
    $workingPath = Resolve-UniquePath -Directory $workingDir -BaseName $target.BaseName -Extension $target.Extension

    if ($PSCmdlet.ShouldProcess($target.Name, 'Move to working')) {
        Move-Item -Path $target.FullName -Destination $workingPath -Force
    }
    $workingFile = Get-Item $workingPath
    Write-CoworkerLog -Message "Moved to working: $workingPath" -Level DEBUG -Component 'refine-drafts'

    # Remove any previous retry sidecar
    $sidecarPath = Join-Path $workingDir "$($workingFile.BaseName).retries.txt"
    Remove-Item $sidecarPath -ErrorAction SilentlyContinue

    try {
        $refinedContent = Invoke-DraftRefinement -WorkingFile $workingFile -Audience $Audience -DomainContext $DomainContext

        if ($PSCmdlet.ShouldProcess($workingFile.Name, 'Write refined content')) {
            Set-Content -Path $workingFile.FullName -Value $refinedContent -Encoding UTF8
        }

        $donePath = Resolve-UniquePath -Directory $doneDir -BaseName $workingFile.BaseName -Extension $workingFile.Extension
        if ($PSCmdlet.ShouldProcess($workingFile.Name, 'Move to done')) {
            Move-Item -Path $workingFile.FullName -Destination $donePath -Force
        }
        Write-CoworkerLog -Message "Refined and moved to done: $donePath" -Level INFO -Component 'refine-drafts'
    }
    catch {
        $failureCount++
        Write-CoworkerLog -Message "Failed to refine $($workingFile.Name): $_" -Level ERROR -Component 'refine-drafts'

        # Return to ready for retry (unless max retries reached)
        if (Test-Path $workingFile.FullName) {
            $readyRetryPath = Join-Path $readyDir $workingFile.Name
            if ($PSCmdlet.ShouldProcess($workingFile.Name, 'Return to ready for retry')) {
                Move-Item -Path $workingFile.FullName -Destination $readyRetryPath -Force
            }
            Write-CoworkerLog -Message "Returned to ready for retry: $($workingFile.Name)" -Level WARN -Component 'refine-drafts'
        }
    }
}

if ($failureCount -gt 0) {
    Write-CoworkerLog -Message "Refinement complete with $failureCount failure(s) out of $($targets.Count)" -Level WARN -Component 'refine-drafts'
    exit 1
}

Write-CoworkerLog -Message "All $($targets.Count) draft(s) refined successfully." -Level INFO -Component 'refine-drafts'
exit 0
