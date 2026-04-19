#!/usr/bin/env pwsh

# Delete branches created by GitHub Copilot, which are named "copilot/*".
# Usage: .\delete-copilot-branches.ps1

# --- Local Branches ---
Write-Host "Checking for local copilot branches..."
$localBranches = git branch --list "copilot/*" | ForEach-Object { $_.Trim().Trim('*').Trim() }

if ($localBranches) {
    Write-Host "Found local copilot branches: $($localBranches -join ', ')"
    foreach ($branch in $localBranches) {
        if ($branch) {
             Write-Host "Deleting local branch: $branch"
             git branch -D $branch
        }
    }
} else {
    Write-Host "No local copilot branches found."
}

# --- Remote Branches ---
Write-Host "`nChecking for remote copilot branches..."
$remoteBranches = git branch -r --list "origin/copilot/*" | ForEach-Object { $_.Trim() }

if ($remoteBranches) {
    Write-Host "Found remote copilot branches."

    foreach ($remoteBranch in $remoteBranches) {
        if ($remoteBranch) {
            # The branch name comes as "origin/copilot/branch-name"
            # We need just "copilot/branch-name" for the push command
            $branchName = $remoteBranch -replace "^origin/", ""

            $confirmation = Read-Host "Delete remote branch '$branchName'? (y/n)"
            if ($confirmation -eq 'y') {
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
