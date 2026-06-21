#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Scan fetched GitHub issues and auto-queue low-risk, high-relevance ones for AI execution.

.DESCRIPTION
    Scans all .md files under coworker/tasks/main/0draft/issues/github (recursively,
    including YYYY/MMDD subdirectories).  For each file not yet evaluated, invokes the
    AI agent to assess:

      - Relevance: Is this issue highly relevant to the Browser4 project?
      - Risk:       Would an AI-driven fix have low risk and minimal side effects?

    Issues that pass both checks are moved to coworker/tasks/main/1ready, where the
    main coworker pipeline picks them up for automatic execution.

    Issues that are uncertain, medium/low relevance, or medium/high risk are left in
    place for other agents or human review.

    A .triage-state.json file alongside the issues records every evaluation so files
    are never re-evaluated on subsequent runs.

.PARAMETER MaxPerRun
    Maximum number of new files to evaluate per invocation. Defaults to 5.

.PARAMETER TimeoutSeconds
    Per-issue AI evaluation timeout in seconds. Defaults to 120.

.EXAMPLE
    .\triage-github-issues.ps1

.EXAMPLE
    .\triage-github-issues.ps1 -MaxPerRun 10
#>

[CmdletBinding(SupportsShouldProcess)]
param(
    [int]$MaxPerRun = 5,
    [int]$TimeoutSeconds = 120
)

$ErrorActionPreference = 'Stop'

# ── Dot-source dependencies ──────────────────────────────────────────────────
$workerDir = Split-Path -Parent $MyInvocation.MyCommand.Path
. (Join-Path (Split-Path -Parent $workerDir) 'config.ps1')
. (Join-Path $workerDir 'agent-reliability.ps1')

$repoRoot = Get-WorkspaceRoot
$tasksRoot = Get-TasksRoot

# ── Directories ──────────────────────────────────────────────────────────────
$issuesDir = Resolve-TasksPath 'main\0draft\issues\github'
$readyDir  = Resolve-TasksPath 'main\1ready'

if (-not (Test-Path -LiteralPath $issuesDir -PathType Container)) {
    Write-CoworkerLog -Message "Issues directory not found: $issuesDir — nothing to triage." -Level INFO -Component 'triage-github-issues'
    exit 0
}

Ensure-CoworkerDirectory -Path $readyDir

# ══════════════════════════════════════════════════════════════════════════════
# Triage State Persistence
# ══════════════════════════════════════════════════════════════════════════════

$triageStateFile = Join-Path $issuesDir '.triage-state.json'

function Get-TriageState {
    param([string]$StateFilePath)

    if (Test-Path -LiteralPath $StateFilePath) {
        try {
            $state = Get-Content -LiteralPath $StateFilePath -Raw -Encoding UTF8 | ConvertFrom-Json
            return @{
                files         = if ($state.files) { $state.files } else { @{} }
                lastRunAt     = if ($state.lastRunAt) { $state.lastRunAt } else { $null }
                totalChecked  = if ($state.totalChecked) { [int]$state.totalChecked } else { 0 }
                totalApproved = if ($state.totalApproved) { [int]$state.totalApproved } else { 0 }
            }
        }
        catch {
            Write-CoworkerLog -Message "Corrupt triage state file, resetting: $_" -Level WARN -Component 'triage-github-issues'
        }
    }

    return @{
        files         = @{}
        lastRunAt     = $null
        totalChecked  = 0
        totalApproved = 0
    }
}

function Set-TriageState {
    param(
        [string]$StateFilePath,
        [hashtable]$State
    )

    $jsonObj = [PSCustomObject]@{
        files         = $State.files
        lastRunAt     = (Get-Date).ToUniversalTime().ToString('o')
        totalChecked  = $State.totalChecked
        totalApproved = $State.totalApproved
    }

    $json = $jsonObj | ConvertTo-Json -Depth 10 -Compress
    Set-Content -LiteralPath $StateFilePath -Value $json -Encoding UTF8 -NoNewline
}

# ══════════════════════════════════════════════════════════════════════════════
# Prompt Construction
# ══════════════════════════════════════════════════════════════════════════════

function New-TriagePrompt {
    param(
        [Parameter(Mandatory)] [string]$IssueContent,
        [Parameter(Mandatory)] [string]$IssueFileName
    )

    return @"
You are triaging a GitHub issue for a project called "Browser4" — a CLI tool that controls a web browser for automation and testing. Your job is to decide whether this issue should be automatically assigned to an AI coder for a fix.

Evaluate the issue on TWO dimensions:

1. RELEVANCE (to Browser4 project):
   - "high": The issue is clearly about Browser4 CLI behavior, a Browser4 command, or Browser4 internals.
   - "medium": Tangentially related (e.g., upstream website behavior, environment/config issues).
   - "low": Not related to Browser4 (e.g., external service, misunderstanding, spam).

2. RISK (of an AI-driven fix):
   - "low": The fix is straightforward and unlikely to cause side effects. Examples:
     * Documentation / help-text fixes
     * Simple CLI argument parsing bugs
     * Adding a missing command alias
     * Fixing a clear, isolated logic error in one function
     * Adding a small, self-contained feature with well-defined inputs/outputs
   - "medium": The fix touches multiple subsystems or requires design decisions.
   - "high": The fix could break core functionality, change public API, affect security, or requires deep architectural changes.

VERDICT rules:
- "approve": relevance = "high" AND risk = "low" → move to queue for AI execution
- "defer": anything else → leave for human review

Respond with ONLY a single JSON object (no markdown fences, no commentary). The JSON must have exactly these keys:

{
  "relevance": "high|medium|low",
  "risk": "low|medium|high",
  "verdict": "approve|defer",
  "summary": "One sentence explaining the decision."
}

Issue file: $IssueFileName

--- BEGIN ISSUE ---
$IssueContent
--- END ISSUE ---
"@
}

# ══════════════════════════════════════════════════════════════════════════════
# Triage Evaluation
# ══════════════════════════════════════════════════════════════════════════════

function Invoke-TriageEvaluation {
    param(
        [Parameter(Mandatory)] [string]$IssueFilePath,
        [Parameter(Mandatory)] [string]$RelativePath
    )

    $content = Get-Content -LiteralPath $IssueFilePath -Raw -Encoding UTF8
    if ([string]::IsNullOrWhiteSpace($content)) {
        Write-CoworkerLog -Message "Skipping empty file: $RelativePath" -Level WARN -Component 'triage-github-issues'
        return $null
    }

    $fileName = [System.IO.Path]::GetFileName($IssueFilePath)
    $prompt = New-TriagePrompt -IssueContent $content -IssueFileName $fileName

    Write-CoworkerLog -Message "Evaluating: $RelativePath" -Level INFO -Component 'triage-github-issues'

    try {
        $rawOutput = Invoke-AgentWithRetry `
            -Prompt $prompt `
            -CaptureOutput `
            -TimeoutSeconds $TimeoutSeconds `
            -MaxRetries 2 `
            -LogComponent 'triage-github-issues' `
            -RepoRoot $repoRoot `
            -OutputDelimiter 'RESPONSE'

        if ([string]::IsNullOrWhiteSpace($rawOutput)) {
            Write-CoworkerLog -Message "Agent returned empty output for: $RelativePath" -Level WARN -Component 'triage-github-issues'
            return $null
        }

        # Strip any lingering markdown fences or whitespace
        $cleanOutput = $rawOutput.Trim("`r", "`n", ' ', "`t")
        if ($cleanOutput -match '^```(?:json)?\s*\n') {
            $cleanOutput = $cleanOutput -replace '^```(?:json)?\s*\n', ''
            $cleanOutput = $cleanOutput -replace '\n```\s*$', ''
            $cleanOutput = $cleanOutput.Trim()
        }

        try {
            $result = $cleanOutput | ConvertFrom-Json
        }
        catch {
            Write-CoworkerLog -Message "Failed to parse agent JSON for $RelativePath. Raw: $cleanOutput" -Level WARN -Component 'triage-github-issues'
            return $null
        }

        # Validate required fields
        if (-not $result.relevance -or -not $result.risk -or -not $result.verdict) {
            Write-CoworkerLog -Message "Incomplete JSON from agent for $RelativePath. Got: $cleanOutput" -Level WARN -Component 'triage-github-issues'
            return $null
        }

        return @{
            relevance = $result.relevance.ToString().ToLower()
            risk      = $result.risk.ToString().ToLower()
            verdict   = $result.verdict.ToString().ToLower()
            summary   = if ($result.summary) { $result.summary.ToString() } else { '' }
        }
    }
    catch {
        Write-CoworkerLog -Message "Evaluation failed for $RelativePath : $_" -Level ERROR -Component 'triage-github-issues'
        return $null
    }
}

# ══════════════════════════════════════════════════════════════════════════════
# File Discovery
# ══════════════════════════════════════════════════════════════════════════════

function Get-IssueFilesToEvaluate {
    param(
        [string]$BaseDir,
        [hashtable]$KnownFiles,
        [int]$MaxCount
    )

    $candidates = [System.Collections.ArrayList]::new()

    $allMdFiles = @(Get-ChildItem -LiteralPath $BaseDir -Filter '*.md' -Recurse -ErrorAction SilentlyContinue |
        Where-Object {
            # Explicitly exclude the triage state file itself and any other dot-file
            $_.Name -notlike '.*'
        } |
        Sort-Object Name)

    foreach ($file in $allMdFiles) {
        if ($candidates.Count -ge $MaxCount) {
            break
        }

        # Compute relative path from issuesDir for stable state keys
        $relativePath = $file.FullName.Substring($BaseDir.Length).TrimStart([System.IO.Path]::DirectorySeparatorChar)
        # Normalize to forward-slash for cross-platform state consistency
        $stateKey = $relativePath.Replace('\', '/')

        if ($KnownFiles.ContainsKey($stateKey)) {
            continue
        }

        [void]$candidates.Add(@{ File = $file; StateKey = $stateKey })
    }

    return $candidates
}

# ══════════════════════════════════════════════════════════════════════════════
# Main
# ══════════════════════════════════════════════════════════════════════════════

$triageState = Get-TriageState -StateFilePath $triageStateFile

Write-CoworkerLog -Message "Starting triage scan. Previously checked: $($triageState.totalChecked), approved: $($triageState.totalApproved). Max this run: $MaxPerRun" `
    -Level INFO -Component 'triage-github-issues'

$candidates = Get-IssueFilesToEvaluate -BaseDir $issuesDir -KnownFiles $triageState.files -MaxCount $MaxPerRun

if ($candidates.Count -eq 0) {
    Write-CoworkerLog -Message 'No new issue files to triage.' -Level INFO -Component 'triage-github-issues'
    Set-TriageState -StateFilePath $triageStateFile -State $triageState
    exit 0
}

Write-CoworkerLog -Message "Found $($candidates.Count) new issue file(s) to evaluate." -Level INFO -Component 'triage-github-issues'

$evaluatedThisRun = 0
$approvedThisRun = 0
$failedThisRun = 0

foreach ($candidate in $candidates) {
    $file = $candidate.File
    $stateKey = $candidate.StateKey

    $evalResult = Invoke-TriageEvaluation -IssueFilePath $file.FullName -RelativePath $stateKey

    if ($null -eq $evalResult) {
        $failedThisRun++
        # Do NOT record in state — allow retry on next run
        continue
    }

    $evaluatedThisRun++

    # Record in state
    $checkedAt = (Get-Date).ToUniversalTime().ToString('o')
    $stateEntry = @{
        checkedAt = $checkedAt
        verdict   = $evalResult.verdict
        relevance = $evalResult.relevance
        risk      = $evalResult.risk
        summary   = $evalResult.summary
    }
    $triageState.files[$stateKey] = $stateEntry
    $triageState.totalChecked++

    Write-CoworkerLog -Message "Verdict for $stateKey : $($evalResult.verdict) (relevance=$($evalResult.relevance), risk=$($evalResult.risk)) — $($evalResult.summary)" `
        -Level INFO -Component 'triage-github-issues'

    if ($evalResult.verdict -eq 'approve') {
        $destPath = Join-Path $readyDir $file.Name

        # Avoid overwriting an existing file in 1ready
        if (Test-Path -LiteralPath $destPath) {
            $baseName = [System.IO.Path]::GetFileNameWithoutExtension($file.Name)
            $extension = [System.IO.Path]::GetExtension($file.Name)
            $counter = 1
            do {
                $newName = "{0}-triage{1}{2}" -f $baseName, $counter, $extension
                $destPath = Join-Path $readyDir $newName
                $counter++
            } while (Test-Path -LiteralPath $destPath)
        }

        if ($PSCmdlet.ShouldProcess($file.Name, 'Move to 1ready (approved by triage)')) {
            Move-Item -LiteralPath $file.FullName -Destination $destPath -Force -ErrorAction Stop
        }

        $triageState.totalApproved++
        $approvedThisRun++
        Write-CoworkerLog -Message "APPROVED and queued: $stateKey -> 1ready\$([System.IO.Path]::GetFileName($destPath))" `
            -Level INFO -Component 'triage-github-issues'
    }
}

# ── Persist state ────────────────────────────────────────────────────────────
Set-TriageState -StateFilePath $triageStateFile -State $triageState

# ── Summary ──────────────────────────────────────────────────────────────────
$summaryParts = @()
if ($evaluatedThisRun -gt 0) { $summaryParts += "$evaluatedThisRun evaluated" }
if ($approvedThisRun -gt 0) { $summaryParts += "$approvedThisRun approved" }
if ($failedThisRun -gt 0) { $summaryParts += "$failedThisRun failed (will retry)" }
if ($candidates.Count -eq 0) { $summaryParts += "nothing new" }

$message = "Triage complete: $($summaryParts -join ', '). Lifetime: $($triageState.totalChecked) checked, $($triageState.totalApproved) approved."
if ($approvedThisRun -gt 0) {
    Write-CoworkerLog -Message $message -Level INFO -Component 'triage-github-issues'
} else {
    Write-CoworkerLog -Message $message -Level INFO -Component 'triage-github-issues'
}

exit 0
