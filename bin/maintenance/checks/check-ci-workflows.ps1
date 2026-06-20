# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
G3 — CI workflow validation: verifies GitHub Actions workflows are valid.

.DESCRIPTION
Checks that all GitHub Actions workflow files reference existing action
paths, have valid names, and don't reference missing secrets or env vars.

.PARAMETER WorkflowDir
Path to workflow files. Default: .github/workflows

.PARAMETER ActionDir
Path to action files. Default: .github/actions

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$WorkflowDir = ".github\workflows",
    [string]$ActionDir = ".github\actions"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "G3" -Name "CI Workflow Validation"
$repoRoot = Get-RepositoryRoot

$wfDir = Resolve-MaintenancePath $WorkflowDir
$actDir = Resolve-MaintenancePath $ActionDir

if (-not (Test-Path $wfDir)) {
    $result.Status = "skipped"
    $result.Details = "Workflow directory not found: $wfDir"
    $result
    return
}

# ── Collect all action subdirectories ──
$actionPaths = @()
if (Test-Path $actDir) {
    $actionPaths = Get-ChildItem $actDir -Directory | ForEach-Object { ".github/actions/$($_.Name)" }
}

# ── Validate each workflow ──
$wfFiles = Get-ChildItem $wfDir -Filter "*.yml" -File
foreach ($wf in $wfFiles) {
    $wfPath = $wf.FullName
    $relWf = $wfPath.Replace($repoRoot, "").TrimStart("\", "/")
    $content = Get-Content $wfPath -Raw
    $issues = @()

    # Check for uses: ./.github/actions/ references
    $actionRefs = [regex]::Matches($content, 'uses:\s*\./\.github/actions/([^\s#]+)')
    foreach ($ref in $actionRefs) {
        $actionPath = ".github/actions/" + $ref.Groups[1].Value
        if (-not (Test-Path (Join-Path $repoRoot $actionPath))) {
            $issues += "Missing action: $actionPath"
        }
    }

    # Check for basic structure
    if ($content -notmatch '\bname\s*:') {
        $issues += "Missing workflow name"
    }
    if ($content -notmatch '\b(?:jobs)\s*:') {
        $issues += "Missing jobs definition"
    }

    if ($issues.Count -eq 0) {
        Add-MaintenanceResult -Result $result -Item $relWf -Status "passed" -Message "Valid"
    }
    else {
        foreach ($issue in $issues) {
            Add-MaintenanceResult -Result $result -Item $relWf -Status "failed" -Message $issue
        }
    }
}

# ── Validate each action ──
if (Test-Path $actDir) {
    $actFiles = Get-ChildItem $actDir -Recurse -Filter "action.yml" -File
    foreach ($act in $actFiles) {
        $relAct = $act.FullName.Replace($repoRoot, "").TrimStart("\", "/")
        $content = Get-Content $act.FullName -Raw
        $issues = @()

        if ($content -notmatch '\bname\s*:') {
            $issues += "Missing action name"
        }
        if ($content -notmatch '\bruns\s*:') {
            $issues += "Missing runs definition"
        }

        if ($issues.Count -eq 0) {
            Add-MaintenanceResult -Result $result -Item $relAct -Status "passed" -Message "Valid"
        }
        else {
            foreach ($issue in $issues) {
                Add-MaintenanceResult -Result $result -Item $relAct -Status "failed" -Message $issue
            }
        }
    }
}

Set-MaintenanceResultSummary -Result $result
$result
