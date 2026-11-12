#!/usr/bin/env pwsh
#requires -Version 7

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use Join-Path / [System.IO.Path]::Combine for paths.
# - Never assume cmd.exe, PowerShell 5.1 or a Windows drive layout.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    Diagnose and undo a self-development coding task that broke the checkout.

.DESCRIPTION
    `agent run --coding` executes in an isolated git worktree and merges the change
    back as a patch (see docs-dev/copilot/20261113-self-dev-worktree-isolation-plan.md).

    Every merge keeps three recovery handles:

      refs/b4/pre/<taskId>      the main tree's HEAD captured before the merge
      refs/b4/baseline/<taskId> the commit the worktree was created from
      .browser4/patches/<taskId>.patch   the applied patch

    and every build keeps the previous runtime bundle under `.b4build/`, so a
    checkout that no longer compiles can still host a backend while it is fixed.

    This command never needs the backend, the network or the source to compile.

.PARAMETER Status
    (default) Report worktrees, patches, refs and bundle state.

.PARAMETER Revert
    Reverse exactly one task's patch in the main work tree. This is the safe
    default recovery action: it touches only the files that task changed.

.PARAMETER Hard
    `git reset --hard refs/b4/pre/<taskId>`. Destroys uncommitted changes — prints
    them first and requires -Yes.

.PARAMETER UseLastGood
    Restore the previously built runtime bundle, so a source tree that no longer
    compiles can still start a backend.

.PARAMETER Remote
    Print (and apply to child processes) the release-runtime switch, for when
    neither the current nor the previous bundle is usable.

.PARAMETER CheckBuild
    Also probe whether the checkout still builds (slow: runs Maven). Opt-in.

.PARAMETER Prune
    Reclaim stale isolated worktrees using the same rule as the backend
    (`browser4.agent.isolation.keep` / `maxAgeDays`): keep the newest -Keep and
    anything younger than -MaxAgeDays, remove the rest with `git worktree remove`.
    Refuses while a coding task holds the checkout lock.

.PARAMETER Keep
    Worktrees always retained by -Prune (default 3).

.PARAMETER MaxAgeDays
    Worktrees younger than this are never pruned (default 7).

.PARAMETER DryRun
    With -Prune: report what would be removed without removing it.

.PARAMETER Yes
    Skip the confirmation for destructive actions (-Hard).

.EXAMPLE
    b4w recover

.EXAMPLE
    b4w recover -Revert 4f2c1a

.EXAMPLE
    b4w recover -UseLastGood
#>
[CmdletBinding()]
param(
    [switch]$Status,
    [string]$Revert,
    [string]$Hard,
    [switch]$UseLastGood,
    [switch]$Remote,
    [switch]$CheckBuild,
    [switch]$Yes,
    [switch]$Prune,
    [int]$Keep = 3,
    [int]$MaxAgeDays = 7,
    [switch]$DryRun,
    # Explicit checkout root. Defaults to the script's own checkout (`b4w recover`
    # always runs from one); tests and cross-checkout use point it elsewhere.
    [string]$Repo,
    # `b4w recover --revert <id>` forwards argv verbatim; PowerShell would reject a
    # double-dash token as a positional parameter, so collect the remainder here and
    # normalise it below.
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$Rest = @()
)

# Accept the CLI-style spelling documented in the plan (`--revert <id>`) as well as
# PowerShell-style switches.
for ($i = 0; $i -lt $Rest.Count; $i++) {
    $token = $Rest[$i]
    switch -Regex ($token) {
        '^--?status$' { $Status = $true }
        '^--?revert$' { $i++; if ($i -lt $Rest.Count) { $Revert = $Rest[$i] } }
        '^--?hard$' { $i++; if ($i -lt $Rest.Count) { $Hard = $Rest[$i] } }
        '^--?use-?last-?good$' { $UseLastGood = $true }
        '^--?remote$' { $Remote = $true }
        '^--?check-?build$' { $CheckBuild = $true }
        '^--?prune$' { $Prune = $true }
        '^--?keep$' { $i++; if ($i -lt $Rest.Count) { $Keep = [int]$Rest[$i] } }
        '^--?max-?age-?days$' { $i++; if ($i -lt $Rest.Count) { $MaxAgeDays = [int]$Rest[$i] } }
        '^--?dry-?run$' { $DryRun = $true }
        '^--?yes$' { $Yes = $true }
        default {
            Write-Host "recover: unknown option '$token'" -ForegroundColor Red
            exit 2
        }
    }
}

$ErrorActionPreference = 'Stop'
$script:ExitNeedsHuman = 1
$script:ExitUsage = 2

function Get-RepoRoot {
    if ($Repo) {
        $explicit = Resolve-Path $Repo -ErrorAction Stop
        if (-not (Test-Path (Join-Path $explicit '.git'))) {
            throw "recover: '$($explicit.Path)' is not a git checkout root"
        }
        return $explicit.Path
    }
    $dir = $PSScriptRoot
    while ($dir -and -not (Test-Path (Join-Path $dir '.git'))) {
        $parent = Split-Path -Parent $dir
        if (-not $parent -or $parent -eq $dir) { break }
        $dir = $parent
    }
    if (-not $dir -or -not (Test-Path (Join-Path $dir '.git'))) {
        throw "recover: not inside a Browser4 git checkout (no .git above $PSScriptRoot)"
    }
    return (Resolve-Path $dir).Path
}

function Invoke-Git {
    param([string]$RepoRoot, [string[]]$GitArgs)
    $out = & git -C $RepoRoot @GitArgs 2>&1
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = (($out | Out-String).TrimEnd()) }
}

function Get-Patches {
    param([string]$RepoRoot)
    $dir = Join-Path $RepoRoot '.browser4/patches'
    if (-not (Test-Path $dir)) { return @() }
    return @(Get-ChildItem -LiteralPath $dir -Filter '*.patch' -File | Sort-Object Name)
}

function Get-ManagedWorktrees {
    param([string]$RepoRoot)
    $git = Invoke-Git $RepoRoot @('worktree', 'list', '--porcelain')
    if ($git.ExitCode -ne 0) { return @() }
    $root = (Join-Path $RepoRoot '.browser4/worktrees').Replace('\', '/')
    return @($git.Output -split "`r?`n" |
        Where-Object { $_ -like 'worktree *' } |
        ForEach-Object { ($_ -replace '^worktree\s+', '').Trim().Replace('\', '/') } |
        Where-Object { $_ -like "$root/*" })
}

function Get-Refs {
    param([string]$RepoRoot)
    $git = Invoke-Git $RepoRoot @('for-each-ref', '--format=%(refname)', 'refs/b4')
    if ($git.ExitCode -ne 0) { return @() }
    return @($git.Output -split "`r?`n" | Where-Object { $_ -like 'refs/b4/*' })
}

function Get-BundlePaths {
    param([string]$RepoRoot)
    return [pscustomobject]@{
        Current  = Join-Path $RepoRoot 'browser4-apps/browser4-bundle/target/runtime-bundle'
        Previous = Join-Path $RepoRoot '.b4build/runtime-bundle.prev'
    }
}

function Write-Section {
    param([string]$Title)
    Write-Host ''
    Write-Host "── $Title " -NoNewline -ForegroundColor Cyan
    Write-Host ('─' * [Math]::Max(0, 60 - $Title.Length)) -ForegroundColor DarkGray
}

function Show-Status {
    param([string]$RepoRoot)

    $worktrees = Get-ManagedWorktrees $RepoRoot
    $patches = Get-Patches $RepoRoot
    $refs = Get-Refs $RepoRoot
    $bundle = Get-BundlePaths $RepoRoot
    $dirty = Invoke-Git $RepoRoot @('status', '--porcelain')
    $head = (Invoke-Git $RepoRoot @('rev-parse', '--short', 'HEAD')).Output

    Write-Section 'checkout'
    Write-Host "  repo:   $RepoRoot"
    Write-Host "  head:   $head"
    Write-Host "  status: $(if ($dirty.Output) { 'dirty' } else { 'clean' })"

    Write-Section 'isolated worktrees'
    if ($worktrees.Count -eq 0) { Write-Host '  (none)' } else { $worktrees | ForEach-Object { Write-Host "  $_" } }

    Write-Section 'merged patches (revert handles)'
    if ($patches.Count -eq 0) { Write-Host '  (none)' } else {
        $patches | ForEach-Object {
            $id = $_.BaseName
            $hasPre = $refs -contains "refs/b4/pre/$id"
            Write-Host ("  {0}  {1,8:N1} KB  pre-ref={2}" -f $id, ($_.Length / 1KB), $(if ($hasPre) { 'yes' } else { 'no' }))
        }
    }

    Write-Section 'runtime bundle'
    Write-Host "  current:  $(if (Test-Path $bundle.Current) { 'present' } else { 'MISSING' })"
    Write-Host "            $($bundle.Current)"
    Write-Host "  previous: $(if (Test-Path $bundle.Previous) { 'present' } else { 'none' })"
    Write-Host "            $($bundle.Previous)"

    if ($CheckBuild) {
        Write-Section 'build probe'
        $mvnw = Join-Path $RepoRoot $(if ($IsWindows) { 'mvnw.cmd' } else { 'mvnw' })
        if (-not (Test-Path $mvnw)) {
            Write-Host '  mvnw not found — cannot probe' -ForegroundColor Yellow
        } else {
            Write-Host '  running: mvn -q -DskipTests validate ...' -ForegroundColor DarkGray
            & $mvnw -q -f (Join-Path $RepoRoot 'pom.xml') -DskipTests validate 2>&1 | Select-Object -Last 10
            Write-Host "  exit=$LASTEXITCODE"
        }
    }

    # A checkout that has never been built has no bundle — that is a fresh clone, not
    # a recovery situation. Status stays informational (exit 0); only the actions
    # above report failure.
    $usableBundle = (Test-Path $bundle.Current) -or (Test-Path $bundle.Previous)
    if (-not $usableBundle) {
        Write-Host ''
        Write-Host '  No runtime bundle yet (fresh checkout?). Options:' -ForegroundColor Yellow
        Write-Host '    b4w build              # build one (needs a compiling checkout)'
        Write-Host '    b4w recover -Remote    # start from the released runtime instead'
    }
    if ($patches.Count -gt 0 -or $worktrees.Count -gt 0) {
        Write-Host ''
        Write-Host '  Recovery options:' -ForegroundColor DarkGray
        if ($patches.Count -gt 0) {
            Write-Host '    b4w recover -Revert <taskId>        # undo exactly one task'
            Write-Host '    b4w recover -Hard <taskId> -Yes     # reset to the pre-merge HEAD (destroys local edits)'
        }
        if (Test-Path $bundle.Previous) {
            Write-Host '    b4w recover -UseLastGood            # start from the previously built bundle'
        }
        Write-Host '    b4w recover -Remote                 # start from the released runtime'
        if ($worktrees.Count -gt $Keep) {
            Write-Host '    b4w recover -Prune [-DryRun]        # reclaim old worktrees now'
        }
    }
    return 0
}

function Resolve-Patch {
    param([string]$RepoRoot, [string]$TaskId)
    $path = Join-Path $RepoRoot ".browser4/patches/$TaskId.patch"
    if (-not (Test-Path $path)) {
        $known = @(Get-Patches $RepoRoot | ForEach-Object BaseName)
        $hint = if ($known.Count -gt 0) { "known tasks: $($known -join ', ')" } else { 'no patches are recorded' }
        throw "recover: no patch for task '$TaskId' ($hint)"
    }
    return $path
}

function Test-Apply {
    param([string]$RepoRoot, [string]$Patch, [switch]$Reverse, [switch]$ThreeWay)
    $gitArgs = @('apply', '--check')
    if ($Reverse) { $gitArgs += '-R' }
    if ($ThreeWay) { $gitArgs += '--3way' }
    $gitArgs += $Patch
    return ((Invoke-Git $RepoRoot $gitArgs).ExitCode -eq 0)
}

function Invoke-Revert {
    param([string]$RepoRoot, [string]$TaskId)
    $patch = Resolve-Patch $RepoRoot $TaskId
    # Plain reverse apply first: it uses the working tree's context and never touches
    # the index. `--3way` is the fallback for a path edited after the merge.
    $mode = if (Test-Apply $RepoRoot $patch -Reverse) { @('-R') }
            elseif (Test-Apply $RepoRoot $patch -Reverse -ThreeWay) { @('-R', '--3way') }
            else { @() }
    if ($mode.Count -eq 0) {
        Write-Host "recover: the patch for '$TaskId' does not reverse cleanly — the checkout changed after it was applied." -ForegroundColor Red
        Write-Host "         Inspect the patch: $patch" -ForegroundColor DarkGray
        Write-Host "         Undo manually, or use: b4w recover -Hard $TaskId -Yes" -ForegroundColor DarkGray
        return $script:ExitNeedsHuman
    }
    $apply = Invoke-Git $RepoRoot (@('apply') + $mode + $patch)
    if ($apply.ExitCode -ne 0) {
        Write-Host "recover: reverse apply failed: $($apply.Output)" -ForegroundColor Red
        return $script:ExitNeedsHuman
    }
    Write-Host "reverted task $TaskId (patch: $patch)" -ForegroundColor Green
    $dirty = Invoke-Git $RepoRoot @('status', '--porcelain')
    Write-Host "checkout is now $(if ($dirty.Output) { 'dirty (other changes remain)' } else { 'clean' })"
    return 0
}

function Invoke-HardReset {
    param([string]$RepoRoot, [string]$TaskId)
    $ref = "refs/b4/pre/$TaskId"
    $exists = (Invoke-Git $RepoRoot @('rev-parse', '--verify', '--quiet', $ref)).ExitCode -eq 0
    if (-not $exists) {
        throw "recover: no pre-merge ref '$ref' (was the task merged?)"
    }
    $dirty = Invoke-Git $RepoRoot @('status', '--porcelain')
    if ($dirty.Output) {
        Write-Host 'These uncommitted changes will be LOST:' -ForegroundColor Yellow
        $dirty.Output -split "`r?`n" | ForEach-Object { Write-Host "  $_" }
    }
    if (-not $Yes) {
        Write-Host "recover: refusing to reset without -Yes (target: $ref)" -ForegroundColor Red
        return $script:ExitUsage
    }
    $reset = Invoke-Git $RepoRoot @('reset', '--hard', $ref)
    if ($reset.ExitCode -ne 0) {
        Write-Host "recover: reset failed: $($reset.Output)" -ForegroundColor Red
        return $script:ExitNeedsHuman
    }
    Write-Host "reset to $ref" -ForegroundColor Green
    return 0
}

function Invoke-UseLastGood {
    param([string]$RepoRoot)
    $bundle = Get-BundlePaths $RepoRoot
    if (-not (Test-Path $bundle.Previous)) {
        Write-Host "recover: no previous bundle at $($bundle.Previous)" -ForegroundColor Red
        return $script:ExitNeedsHuman
    }
    if (Test-Path $bundle.Current) {
        $broken = "$($bundle.Current).broken-$(Get-Date -Format 'yyyyMMdd-HHmmss')"
        Move-Item -LiteralPath $bundle.Current -Destination $broken -Force
        Write-Host "moved the current bundle aside: $broken" -ForegroundColor DarkGray
    }
    New-Item -ItemType Directory -Force -Path (Split-Path -Parent $bundle.Current) | Out-Null
    Move-Item -LiteralPath $bundle.Previous -Destination $bundle.Current -Force
    Write-Host "restored the previous runtime bundle: $($bundle.Current)" -ForegroundColor Green
    $env:BROWSER4_CLI_ALLOW_STALE_BUNDLE = '1'
    Write-Host 'The restored bundle is older than the sources; start with:' -ForegroundColor Yellow
    Write-Host '  $env:BROWSER4_CLI_ALLOW_STALE_BUNDLE=1; b4w status' -ForegroundColor DarkGray
    return 0
}

function Invoke-Remote {
    $env:BROWSER4_CLI_FORCE_REMOTE_BUNDLE = '1'
    Write-Host 'Release runtime selected for this shell:' -ForegroundColor Green
    Write-Host '  $env:BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1' -ForegroundColor DarkGray
    Write-Host 'Then run: b4w status   (the CLI installs/launches the released runtime)' -ForegroundColor DarkGray
    return 0
}

<#
    Reclaim stale isolated worktrees with the same rule the backend applies
    (`browser4.agent.isolation.keep` / `.maxAgeDays`), but on demand: the backend
    only sweeps at the start of a coding task.
#>
function Invoke-Prune {
    param([string]$RepoRoot, [int]$Keep, [int]$MaxAgeDays, [switch]$DryRun)

    $lockFile = Join-Path $RepoRoot '.git/b4-coding.lock'
    if (Test-Path $lockFile) {
        Write-Host "recover: refusing to prune while a coding task may be running ($lockFile exists)." -ForegroundColor Red
        Write-Host "         Re-run after the task finishes, or delete that file if the process is gone." -ForegroundColor DarkGray
        return $script:ExitNeedsHuman
    }

    $managed = Join-Path $RepoRoot '.browser4/worktrees'
    if (-not (Test-Path $managed)) {
        Write-Host 'recover: no managed worktrees'
        return 0
    }
    $managedFull = (Resolve-Path $managed).Path
    $entries = @(Get-ChildItem -LiteralPath $managedFull -Directory | Sort-Object LastWriteTime -Descending)
    if ($entries.Count -eq 0) {
        Write-Host 'recover: no managed worktrees'
        return 0
    }

    $now = Get-Date
    $kept = 0
    $removed = 0
    $errors = 0
    for ($i = 0; $i -lt $entries.Count; $i++) {
        $entry = $entries[$i]
        if ($i -lt $Keep) { $kept++; continue }
        $ageDays = ($now - $entry.LastWriteTime).TotalDays
        if ($ageDays -lt $MaxAgeDays) { $kept++; continue }
        if (-not $entry.FullName.StartsWith($managedFull)) { continue }

        if ($DryRun) {
            Write-Host "  would remove $($entry.FullName) (age $([math]::Round($ageDays,1))d)" -ForegroundColor DarkGray
            $kept++
            continue
        }
        $remove = Invoke-Git $RepoRoot @('worktree', 'remove', '--force', $entry.FullName)
        if ($remove.ExitCode -eq 0) {
            $removed++
            Write-Host "  removed $($entry.Name) (age $([math]::Round($ageDays,1))d)" -ForegroundColor DarkGray
        } else {
            $errors++
            Write-Host "  could not remove $($entry.Name): $($remove.Output)" -ForegroundColor Yellow
        }
    }
    if (-not $DryRun) { Invoke-Git $RepoRoot @('worktree', 'prune') | Out-Null }

    Write-Host "worktree prune$(if ($DryRun) { ' (dry-run)' }): kept=$kept removed=$removed errors=$errors"
    if ($errors -gt 0) { return $script:ExitNeedsHuman }
    return 0
}

try {
    $repoRoot = Get-RepoRoot
} catch {
    Write-Host $_.Exception.Message -ForegroundColor Red
    exit $script:ExitUsage
}

if ($Revert -and $Hard) {
    Write-Host 'recover: -Revert and -Hard are mutually exclusive' -ForegroundColor Red
    exit $script:ExitUsage
}

try {
    if ($Prune) { exit (Invoke-Prune $repoRoot $Keep $MaxAgeDays -DryRun:$DryRun) }
    if ($Revert) { exit (Invoke-Revert $repoRoot $Revert) }
    if ($Hard) { exit (Invoke-HardReset $repoRoot $Hard) }
    if ($UseLastGood) { exit (Invoke-UseLastGood $repoRoot) }
    if ($Remote) { exit (Invoke-Remote) }
    exit (Show-Status $repoRoot)
} catch {
    Write-Host "recover: $($_.Exception.Message)" -ForegroundColor Red
    exit $script:ExitNeedsHuman
}
