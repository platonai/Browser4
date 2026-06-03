#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Delete local and remote branches created by GitHub Copilot (copilot/*).

.DESCRIPTION
    Finds all local and remote branches matching the pattern "copilot/*" and
    deletes them. By default, prompts once for remote branches; use -Force to
    skip all prompts (useful for scripting/CI).

.PARAMETER Force
    Skip all confirmation prompts — delete every copilot branch immediately.

.PARAMETER DryRun
    List what would be deleted without actually deleting anything.

.EXAMPLE
    .\delete-copilot-branches.ps1
    Prompts for confirmation before deleting remote branches.

.EXAMPLE
    .\delete-copilot-branches.ps1 -Force
    Deletes all copilot branches without prompting.

.EXAMPLE
    .\delete-copilot-branches.ps1 -DryRun
    Shows which branches would be deleted, but makes no changes.
#>

[CmdletBinding()]
param(
    [switch] $Force,
    [switch] $DryRun
)

# --- Local Branches ---
Write-Host "Checking for local copilot branches..."
$localBranches = git branch --list "copilot/*" | ForEach-Object { $_.Trim().Trim('*').Trim() }

if ($localBranches) {
    Write-Host "Found local copilot branches: $($localBranches -join ', ')"
    foreach ($branch in $localBranches) {
        if ($branch) {
            if ($DryRun) {
                Write-Host "[DryRun] Would delete local branch: $branch"
            } else {
                Write-Host "Deleting local branch: $branch"
                git branch -D $branch
            }
        }
    }
} else {
    Write-Host "No local copilot branches found."
}

# --- Remote Branches ---
Write-Host "`nChecking for remote copilot branches..."
$remoteBranches = git branch -r --list "origin/copilot/*" | ForEach-Object { $_.Trim() }

if ($remoteBranches) {
    # Resolve branch names (strip "origin/" prefix)
    $branchNames = $remoteBranches | ForEach-Object { $_ -replace "^origin/", "" }

    Write-Host "Found $($branchNames.Count) remote copilot branch(es):"
    $branchNames | ForEach-Object { Write-Host "  $_" }

    if ($DryRun) {
        Write-Host "`n[DryRun] Would delete $($branchNames.Count) remote branch(es). No changes made."
        return
    }

    # Decide confirmation mode
    if ($Force) {
        $mode = 'all'
    } else {
        Write-Host ""
        $response = Read-Host "Delete remote branches? (y)es to all / (n)o to all / (a)sk per branch"
        switch -Regex ($response) {
            '^y' { $mode = 'all' }
            '^n' { $mode = 'none' }
            default { $mode = 'ask' }
        }
    }

    if ($mode -eq 'none') {
        Write-Host "Skipping all remote branches."
        return
    }

    foreach ($branchName in $branchNames) {
        if ($branchName) {
            $delete = $true
            if ($mode -eq 'ask') {
                $confirmation = Read-Host "Delete remote branch '$branchName'? (y/n)"
                $delete = ($confirmation -eq 'y')
            }

            if ($delete) {
                Write-Host "Deleting remote branch: $branchName"
                git push origin --delete $branchName
            } else {
                Write-Host "Skipping remote branch: $branchName"
            }
        }
    }
} else {
    Write-Host "No remote copilot branches found."
}
