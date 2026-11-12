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
}
