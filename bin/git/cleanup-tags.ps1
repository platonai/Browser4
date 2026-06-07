<#!
.SYNOPSIS
    Delete local and optionally remote git tags matching the given patterns.

.DESCRIPTION
    Delete local (and optionally remote) git tags whose names contain any of the
    given case-insensitive patterns.

.PARAMETER Pattern
    One or more substrings to match against tag names (case-insensitive).
    Defaults: "dry_run", "ci", "npm_publish"

.PARAMETER Remote
    Remote to delete tags from (default: origin).
    Remote deletion only happens when -DeleteRemote is also passed.

.PARAMETER DryRun
    List matching tags without deleting anything.

.PARAMETER DeleteRemote
    Also delete matching tags from the remote.

.PARAMETER Yes
    Skip the confirmation prompt.

.EXAMPLE
    .\cleanup-tags.ps1 -DryRun
    Dry-run with default patterns.

.EXAMPLE
    .\cleanup-tags.ps1 -DeleteRemote ci
    Delete local + remote tags matching 'ci'.

.EXAMPLE
    .\cleanup-tags.ps1 -Remote upstream -DeleteRemote dry_run ci
#>

[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true, Position = 0)]
    [string[]] $Pattern,

    [string] $Remote = "origin",

    [switch] $DryRun,

    [switch] $DeleteRemote,

    [switch] $Yes
)

$ErrorActionPreference = "Stop"

# --------------- defaults ---------------
if ($Pattern.Count -eq 0) {
    $Pattern = @("dry_run", "ci", "npm_publish", "rb")
}

# --------------- safety checks ---------------
$repoRoot = git rev-parse --show-toplevel 2>$null
if (-not $repoRoot) {
    Write-Error "Error: not inside a git repository."
    exit 1
}
Push-Location $repoRoot

try {
    # --------------- collect matching tags ---------------
    $allTags = git tag 2>$null
    $tagsToDelete = [System.Collections.Generic.List[string]]::new()

    foreach ($pat in $Pattern) {
        foreach ($tag in $allTags) {
            if ($tag -like "*$pat*" -and $tagsToDelete -notcontains $tag) {
                $tagsToDelete.Add($tag)
            }
        }
    }

    if ($tagsToDelete.Count -eq 0) {
        Write-Host "No matching local tags found."
        exit 0
    }

    # --------------- display & dry-run ---------------
    Write-Host ""
    Write-Host "Patterns:      $($Pattern -join ' ')"
    Write-Host "Matching tags ($($tagsToDelete.Count)):"
    foreach ($tag in $tagsToDelete) {
        Write-Host "  $tag"
    }
    Write-Host ""

    if ($DryRun) {
        Write-Host "Dry-run mode — no tags were deleted."
        exit 0
    }

    # --------------- confirmation ---------------
    $target = "local"
    if ($DeleteRemote) { $target = "local + remote '$Remote'" }

    if (-not $Yes) {
        $confirm = Read-Host "Delete these $($tagsToDelete.Count) tags from $target? (y/N)"
        if ($confirm -notmatch '^[Yy]([Ee][Ss])?$') {
            Write-Host "Cancelled. No tags were deleted."
            exit 0
        }
    }

    # --------------- fetch remote tags (needed for remote deletion) ---------------
    $remoteTagSet = @()
    if ($DeleteRemote) {
        Write-Host "Fetching tags from remote '$Remote'..."
        git fetch --tags $Remote 2>$null

        $lsRemote = git ls-remote --tags $Remote 2>$null
        foreach ($line in $lsRemote) {
            if ($line -match '\S+\s+(\S+)') {
                $ref = $Matches[1]
                $tagName = $ref -replace '^refs/tags/', '' -replace '\^\{\}$', ''
                $remoteTagSet += $tagName
            }
        }
    }

    # --------------- delete local (all in one shot) ---------------
    Write-Host "Deleting $($tagsToDelete.Count) local tags..."
    git tag -d @($tagsToDelete)
    $deletedLocal = $tagsToDelete.Count

    # --------------- delete remote (all in one shot) ---------------
    $deletedRemote = 0
    if ($DeleteRemote) {
        $remoteToDelete = [System.Collections.Generic.List[string]]::new()

        foreach ($tag in $tagsToDelete) {
            if ($tag -in $remoteTagSet) {
                $remoteToDelete.Add($tag)
            } else {
                Write-Host "Skipping remote tag '$tag' (not found on '$Remote')"
            }
        }

        if ($remoteToDelete.Count -gt 0) {
            Write-Host "Deleting $($remoteToDelete.Count) remote tags from '$Remote'..."
            git push $Remote --delete @($remoteToDelete)
            $deletedRemote = $remoteToDelete.Count
        } else {
            Write-Host "No matching tags to delete on remote '$Remote'."
        }
    }

    # --------------- summary ---------------
    Write-Host ""
    Write-Host "Done. Deleted local tags:  $deletedLocal"
    if ($DeleteRemote) {
        Write-Host "       Deleted remote tags: $deletedRemote"
    }
}
finally {
    Pop-Location
}
