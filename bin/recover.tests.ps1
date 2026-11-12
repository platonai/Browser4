#requires -Version 7
<#
    Pester tests for bin/recover.ps1 — the backend-independent recovery command.

    Run with:  pwsh bin/test.ps1 ps        (discovers every *.tests.ps1)
    Or:        pwsh -c "Invoke-Pester -Path bin/recover.tests.ps1"

    Every test drives a throw-away git repository: this suite must never touch the
    real checkout, its refs or its runtime bundle.
#>

BeforeAll {
    $script:RecoverScript = Join-Path $PSScriptRoot 'recover.ps1'
    $script:Pwsh = (Get-Process -Id $PID).Path

    function New-TestRepo {
        $repo = Join-Path $TestDrive ("repo-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
        New-Item -ItemType Directory -Force -Path $repo | Out-Null
        Push-Location $repo
        try {
            & git init -q
            & git config user.email 'test@example.com'
            & git config user.name 'recover test'
            & git config commit.gpgsign false
            Set-Content -LiteralPath (Join-Path $repo 'a.txt') -Value 'line1'
            # Mirror the real repository: agent bookkeeping is git-ignored, so a
            # revert's `git status` is the user's change set only.
            Set-Content -LiteralPath (Join-Path $repo '.gitignore') -Value ".browser4/`n.b4build/"
            & git add -A
            & git commit -qm initial
        } finally { Pop-Location }
        return $repo
    }

    function Invoke-Recover {
        param([string]$Repo, [string[]]$RecoverArgs = @())
        $all = @('-NoProfile', '-File', $script:RecoverScript, '-Repo', $Repo) + $RecoverArgs
        $out = & $script:Pwsh @all 2>&1
        return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = (($out | Out-String).TrimEnd()) }
    }

    function New-MergedTask {
        <#
            Reproduce what an isolation merge leaves behind: a file present in the
            work tree, a patch under .browser4/patches and a refs/b4/pre/<id> ref.
        #>
        param([string]$Repo, [string]$TaskId, [string]$FileName = 'agent.txt', [string]$Content = 'agent')

        Push-Location $Repo
        try {
            $file = Join-Path $Repo $FileName
            Set-Content -LiteralPath $file -Value $Content
            & git add -A
            $patchDir = Join-Path $Repo '.browser4/patches'
            New-Item -ItemType Directory -Force -Path $patchDir | Out-Null
            $patch = Join-Path $patchDir "$TaskId.patch"
            # `--output` writes the patch bytes verbatim (no PowerShell line-ending rewrite).
            & git diff --cached --binary "--output=$patch"
            & git reset -q
            Remove-Item -LiteralPath $file -Force
            & git apply $patch
            if ($LASTEXITCODE -ne 0) { throw "could not apply the simulated patch" }
            & git update-ref "refs/b4/pre/$TaskId" HEAD
            & git update-ref "refs/b4/baseline/$TaskId" HEAD
        } finally { Pop-Location }
    }

    function New-ManagedWorktree {
        param([string]$Repo, [string]$Id, [int]$AgeDays)
        $path = Join-Path $Repo ".browser4/worktrees/$Id"
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $path) | Out-Null
        & git -C $Repo worktree add --detach --force $path HEAD 2>&1 | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "could not create worktree $Id" }
        (Get-Item -LiteralPath $path).LastWriteTime = (Get-Date).AddDays(-$AgeDays)
        return $path
    }

    function Get-ManagedWorktreeCount {
        param([string]$Repo)
        $out = & git -C $Repo worktree list --porcelain | Out-String
        return ([regex]::Matches($out, '(?m)^worktree ')).Count - 1  # the main tree is not managed
    }
}

Describe 'recover.ps1' {

    It 'reports a clean checkout with no patches and exits 0' {
        $repo = New-TestRepo
        $result = Invoke-Recover -Repo $repo

        $result.ExitCode | Should -Be 0
        $result.Output | Should -Match 'isolated worktrees'
        $result.Output | Should -Match '\(none\)'
    }

    It 'reverts exactly one merged task and leaves the checkout clean' {
        $repo = New-TestRepo
        New-MergedTask -Repo $repo -TaskId 'task-a'
        (Test-Path (Join-Path $repo 'agent.txt')) | Should -BeTrue

        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-Revert', 'task-a')

        $result.ExitCode | Should -Be 0
        (Test-Path (Join-Path $repo 'agent.txt')) | Should -BeFalse
        $status = (& git -C $repo status --porcelain | Out-String).Trim()
        $status | Should -Be ''
    }

    It 'accepts the documented double-dash spelling (--revert)' {
        $repo = New-TestRepo
        New-MergedTask -Repo $repo -TaskId 'task-dash'

        $result = Invoke-Recover -Repo $repo -RecoverArgs @('--revert', 'task-dash')

        $result.ExitCode | Should -Be 0
        (Test-Path (Join-Path $repo 'agent.txt')) | Should -BeFalse
    }

    It 'refuses an unknown task id with a useful message' {
        $repo = New-TestRepo
        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-Revert', 'nope')

        $result.ExitCode | Should -Be 1
        $result.Output | Should -Match "no patch for task 'nope'"
    }

    It 'refuses -Hard without -Yes and leaves the tree untouched' {
        $repo = New-TestRepo
        New-MergedTask -Repo $repo -TaskId 'task-hard'

        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-Hard', 'task-hard')

        $result.ExitCode | Should -Be 2
        $result.Output | Should -Match 'will be LOST'
        (Test-Path (Join-Path $repo 'agent.txt')) | Should -BeTrue
    }

    It 'resets to the pre-merge ref with -Hard -Yes' {
        $repo = New-TestRepo
        New-MergedTask -Repo $repo -TaskId 'task-reset'
        Push-Location $repo
        try { & git add -A; & git commit -qm 'merged task' } finally { Pop-Location }

        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-Hard', 'task-reset', '-Yes')

        $result.ExitCode | Should -Be 0
        (Test-Path (Join-Path $repo 'agent.txt')) | Should -BeFalse
    }

    It 'restores the previous runtime bundle with -UseLastGood' {
        $repo = New-TestRepo
        $prev = Join-Path $repo '.b4build/runtime-bundle.prev'
        New-Item -ItemType Directory -Force -Path $prev | Out-Null
        Set-Content -LiteralPath (Join-Path $prev 'marker.txt') -Value 'previous'

        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-UseLastGood')

        $result.ExitCode | Should -Be 0
        $current = Join-Path $repo 'browser4-apps/browser4-bundle/target/runtime-bundle'
        (Test-Path (Join-Path $current 'marker.txt')) | Should -BeTrue
        (Test-Path $prev) | Should -BeFalse
    }

    It 'reports a missing previous bundle instead of pretending to restore it' {
        $repo = New-TestRepo
        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-UseLastGood')

        $result.ExitCode | Should -Be 1
        $result.Output | Should -Match 'no previous bundle'
    }

    It 'fails with exit 2 when the target is not a git checkout' {
        $dir = Join-Path $TestDrive ("plain-" + [guid]::NewGuid().ToString('N').Substring(0, 6))
        New-Item -ItemType Directory -Force -Path $dir | Out-Null

        $result = Invoke-Recover -Repo $dir

        $result.ExitCode | Should -Be 2
        $result.Output | Should -Match 'not a git checkout'
    }

    It 'prunes stale worktrees and keeps the newest ones' {
        $repo = New-TestRepo
        $newest = New-ManagedWorktree -Repo $repo -Id 'wt-newest' -AgeDays 0
        $middle = New-ManagedWorktree -Repo $repo -Id 'wt-middle' -AgeDays 5
        $oldest = New-ManagedWorktree -Repo $repo -Id 'wt-oldest' -AgeDays 10
        (Get-ManagedWorktreeCount -Repo $repo) | Should -Be 3

        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-Prune', '-Keep', '1', '-MaxAgeDays', '0')

        $result.ExitCode | Should -Be 0
        $result.Output | Should -Match 'kept=1 removed=2'
        (Test-Path $newest) | Should -BeTrue
        (Test-Path $middle) | Should -BeFalse
        (Test-Path $oldest) | Should -BeFalse
        (Get-ManagedWorktreeCount -Repo $repo) | Should -Be 1
    }

    It 'keeps worktrees younger than -MaxAgeDays even beyond -Keep' {
        $repo = New-TestRepo
        $a = New-ManagedWorktree -Repo $repo -Id 'wt-recent-a' -AgeDays 1
        $b = New-ManagedWorktree -Repo $repo -Id 'wt-recent-b' -AgeDays 2

        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-Prune', '-Keep', '0', '-MaxAgeDays', '7')

        $result.ExitCode | Should -Be 0
        $result.Output | Should -Match 'removed=0'
        (Test-Path $a) | Should -BeTrue
        (Test-Path $b) | Should -BeTrue
    }

    It 'reports what -Prune would remove in -DryRun without removing it' {
        $repo = New-TestRepo
        $old = New-ManagedWorktree -Repo $repo -Id 'wt-dry' -AgeDays 20

        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-Prune', '-Keep', '0', '-MaxAgeDays', '0', '-DryRun')

        $result.ExitCode | Should -Be 0
        $result.Output | Should -Match 'dry-run'
        (Test-Path $old) | Should -BeTrue
        (Get-ManagedWorktreeCount -Repo $repo) | Should -Be 1
    }

    It 'refuses to prune while a coding task holds the lock' {
        $repo = New-TestRepo
        New-ManagedWorktree -Repo $repo -Id 'wt-locked' -AgeDays 20 | Out-Null
        Set-Content -LiteralPath (Join-Path $repo '.git/b4-coding.lock') -Value '{"pid":1}'

        $result = Invoke-Recover -Repo $repo -RecoverArgs @('-Prune', '-MaxAgeDays', '0')

        $result.ExitCode | Should -Be 1
        $result.Output | Should -Match 'refusing to prune'
        (Test-Path (Join-Path $repo '.browser4/worktrees/wt-locked')) | Should -BeTrue
    }
}
