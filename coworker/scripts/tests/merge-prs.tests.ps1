#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use Join-Path / Split-Path; never bake \ or / as literal.
# - Avoid Windows-only env vars; use $env:TMPDIR fallback.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    Unit tests for merge-prs.ps1 — author filtering, PR parsing,
    task-file generation, conflict detection, and test-arg construction.

.DESCRIPTION
    Tests the pure-logic portions of the merge-prs worker:
    - Filter-PrsByAuthor (self-authored PR filtering)
    - ConvertFrom-PrListJson (gh pr list JSON parsing edge cases)
    - New-TestFailureTask (task file content generation)
    - Detect-MergeConflict (gh pr merge output classification)
    - Build-TestArgs (test-type to argument list conversion)
    - Format-PrSummary (summary display string construction)

    Run standalone:
        Invoke-Pester -Path .\merge-prs.tests.ps1

    Requires Pester 5.x
#>

$ErrorActionPreference = 'Continue'

# ═══════════════════════════════════════════════════════════════════════════════
# Test fixture management
# ═══════════════════════════════════════════════════════════════════════════════

$script:TestRoot = $null

function global:Initialize-TestFixture {
    $script:TestRoot = Join-Path ([System.IO.Path]::GetTempPath()) "MergePrsTests_$(Get-Random -Minimum 1000 -Maximum 9999)"
    New-Item -ItemType Directory -Path $script:TestRoot -Force | Out-Null
}

function global:Remove-TestFixture {
    if ($script:TestRoot -and (Test-Path $script:TestRoot)) {
        Remove-Item -Path $script:TestRoot -Recurse -Force -ErrorAction SilentlyContinue
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Filter-PrsByAuthor
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Filter-PrsByAuthor' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the author-filtering logic from merge-prs.ps1.
            Takes a list of PR objects (from gh pr list --json) and a
            current user login; returns self-authored PRs and foreign PRs.
        #>
        function Filter-PrsByAuthor {
            param(
                [object[]]$AllPrs,
                [string]$CurrentUser
            )

            if (-not $CurrentUser) {
                return @{ Prs = @($AllPrs); ForeignPrs = @() }
            }

            $prs = @($AllPrs | Where-Object { $_.author.login -eq $CurrentUser })
            $foreignPrs = @($AllPrs | Where-Object { $_.author.login -ne $CurrentUser })
            return @{ Prs = $prs; ForeignPrs = $foreignPrs }
        }

        function New-MockPr {
            param(
                [int]$Number,
                [string]$Title,
                [string]$Author,
                [string]$HeadRefName = "feature-$Number",
                [string]$Mergeable = 'MERGEABLE'
            )
            return [pscustomobject]@{
                number      = $Number
                title       = $Title
                headRefName = $HeadRefName
                mergeable   = $Mergeable
                author      = [pscustomobject]@{ login = $Author }
            }
        }
    }

    Context 'With current user filter' {

        It 'filters a mix of self and foreign authors' {
            $prs = @(
                New-MockPr -Number 1 -Title 'Fix bug'       -Author 'galaxyeye'
                New-MockPr -Number 2 -Title 'Add feature'   -Author 'other-dev'
                New-MockPr -Number 3 -Title 'Update docs'   -Author 'galaxyeye'
                New-MockPr -Number 4 -Title 'Refactor core' -Author 'another-dev'
            )

            $result = Filter-PrsByAuthor -AllPrs $prs -CurrentUser 'galaxyeye'

            $result.Prs.Count        | Should -Be 2
            $result.ForeignPrs.Count | Should -Be 2
            $result.Prs[0].number    | Should -Be 1
            $result.Prs[1].number    | Should -Be 3
            $result.ForeignPrs[0].number | Should -Be 2
            $result.ForeignPrs[1].number | Should -Be 4
        }

        It 'returns all PRs as self when all are by current user' {
            $prs = @(
                New-MockPr -Number 1 -Title 'Fix A' -Author 'galaxyeye'
                New-MockPr -Number 2 -Title 'Fix B' -Author 'galaxyeye'
            )

            $result = Filter-PrsByAuthor -AllPrs $prs -CurrentUser 'galaxyeye'

            $result.Prs.Count        | Should -Be 2
            $result.ForeignPrs.Count | Should -Be 0
        }

        It 'returns all PRs as foreign when none are by current user' {
            $prs = @(
                New-MockPr -Number 1 -Title 'Fix A' -Author 'other-dev'
                New-MockPr -Number 2 -Title 'Fix B' -Author 'another-dev'
            )

            $result = Filter-PrsByAuthor -AllPrs $prs -CurrentUser 'galaxyeye'

            $result.Prs.Count        | Should -Be 0
            $result.ForeignPrs.Count | Should -Be 2
        }

        It 'handles single PR by current user' {
            $prs = @(New-MockPr -Number 1 -Title 'Solo PR' -Author 'galaxyeye')
            $result = Filter-PrsByAuthor -AllPrs $prs -CurrentUser 'galaxyeye'

            $result.Prs.Count        | Should -Be 1
            $result.ForeignPrs.Count | Should -Be 0
            $result.Prs[0].title     | Should -BeExactly 'Solo PR'
        }

        It 'handles empty PR array' {
            $result = Filter-PrsByAuthor -AllPrs @() -CurrentUser 'galaxyeye'

            $result.Prs.Count        | Should -Be 0
            $result.ForeignPrs.Count | Should -Be 0
        }
    }

    Context 'Without current user (fallback to all authors)' {

        It 'returns all PRs as self when CurrentUser is empty string' {
            $prs = @(
                New-MockPr -Number 1 -Title 'PR A' -Author 'galaxyeye'
                New-MockPr -Number 2 -Title 'PR B' -Author 'other-dev'
            )

            $result = Filter-PrsByAuthor -AllPrs $prs -CurrentUser ''

            $result.Prs.Count        | Should -Be 2
            $result.ForeignPrs.Count | Should -Be 0
        }

        It 'returns all PRs as self when CurrentUser is null' {
            $prs = @(New-MockPr -Number 1 -Title 'PR A' -Author 'other-dev')
            $result = Filter-PrsByAuthor -AllPrs $prs -CurrentUser $null

            $result.Prs.Count        | Should -Be 1
            $result.ForeignPrs.Count | Should -Be 0
        }
    }

    Context 'Case sensitivity' {

        It 'matches author login case-insensitively (GitHub logins are case-insensitive for auth)' {
            $prs = @(
                New-MockPr -Number 1 -Title 'PR A' -Author 'GalaxyEye'
                New-MockPr -Number 2 -Title 'PR B' -Author 'galaxyeye'
            )

            $result = Filter-PrsByAuthor -AllPrs $prs -CurrentUser 'galaxyeye'

            # PowerShell -eq is case-insensitive; both PRs match the current user
            $result.Prs.Count        | Should -Be 2
            $result.ForeignPrs.Count | Should -Be 0
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: ConvertFrom-PrListJson
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'ConvertFrom-PrListJson' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the gh pr list JSON parsing logic from merge-prs.ps1.
            gh pr list --json outputs a JSON array; ConvertFrom-Json returns
            either a single object or an array.  We always force an array with @().
        #>
        function ConvertFrom-PrListJson {
            param([string]$Json)
            if ([string]::IsNullOrWhiteSpace($Json)) {
                return @()
            }
            return @($Json | ConvertFrom-Json)
        }
    }

    Context 'Valid JSON input' {

        It 'parses an array of multiple PRs' {
            $json = @'
[
  {"number":1,"title":"Fix bug","headRefName":"fix-bug","mergeable":"MERGEABLE","author":{"login":"galaxyeye"}},
  {"number":2,"title":"Add feature","headRefName":"add-feature","mergeable":"CONFLICTING","author":{"login":"galaxyeye"}}
]
'@
            $result = ConvertFrom-PrListJson -Json $json
            $result.Count | Should -Be 2
            $result[0].number | Should -Be 1
            $result[1].number | Should -Be 2
            $result[1].mergeable | Should -BeExactly 'CONFLICTING'
        }

        It 'parses a single PR (returns 1-element array, not scalar)' {
            $json = '[{"number":42,"title":"Single PR","headRefName":"single","mergeable":"MERGEABLE","author":{"login":"galaxyeye"}}]'
            $result = ConvertFrom-PrListJson -Json $json

            # @() ensures it's always an array
            $result.Count | Should -Be 1
            $result[0].number | Should -Be 42
        }

        It 'parses an empty array' {
            $json = '[]'
            $result = ConvertFrom-PrListJson -Json $json
            $result.Count | Should -Be 0
        }
    }

    Context 'Edge cases' {

        It 'returns empty array for empty string' {
            $result = ConvertFrom-PrListJson -Json ''
            $result.Count | Should -Be 0
        }

        It 'returns empty array for whitespace-only string' {
            $result = ConvertFrom-PrListJson -Json '   '
            $result.Count | Should -Be 0
        }

        It 'preserves all PR fields from JSON' {
            $json = '[{"number":1,"title":"Fix bug","headRefName":"fix-bug","mergeable":"UNKNOWN","author":{"login":"galaxyeye"}}]'
            $result = ConvertFrom-PrListJson -Json $json

            $result[0].number      | Should -Be 1
            $result[0].title       | Should -BeExactly 'Fix bug'
            $result[0].headRefName | Should -BeExactly 'fix-bug'
            $result[0].mergeable   | Should -BeExactly 'UNKNOWN'
            $result[0].author.login | Should -BeExactly 'galaxyeye'
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Detect-MergeConflict
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Detect-MergeConflict' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the conflict-detection logic from merge-prs.ps1.
            Classifies gh pr merge failure output as conflict-related or not.
        #>
        function Test-IsMergeConflict {
            param([string]$MergeOutput)
            return $MergeOutput -match 'conflict|not mergeable|mergeable'
        }
    }

    Context 'Conflict indicators' {

        It 'detects "conflict" in merge output' {
            Test-IsMergeConflict -MergeOutput 'Error: merge conflict detected in src/main.rs' | Should -BeTrue
        }

        It 'detects "not mergeable" in merge output' {
            Test-IsMergeConflict -MergeOutput 'Pull request #42 is not mergeable' | Should -BeTrue
        }

        It 'detects "mergeable" in merge output (UNKNOWN status)' {
            Test-IsMergeConflict -MergeOutput 'Error: pull request mergeable state is UNKNOWN' | Should -BeTrue
        }

        It 'detects "conflict" anywhere in multi-line output' {
            $output = @'
Attempting merge...
Checking branch protections...
Error: This pull request has conflicts that must be resolved.
Please resolve conflicts and try again.
'@
            Test-IsMergeConflict -MergeOutput $output | Should -BeTrue
        }

        It 'is case-insensitive' {
            Test-IsMergeConflict -MergeOutput 'Pull request has CONFLICTING files.' | Should -BeTrue
            Test-IsMergeConflict -MergeOutput 'Pull request NOT MERGEABLE.' | Should -BeTrue
        }
    }

    Context 'Non-conflict failures' {

        It 'returns false for required-check failure' {
            Test-IsMergeConflict -MergeOutput 'Error: required status check "CI" has not passed' | Should -BeFalse
        }

        It 'returns false for branch-protection failure' {
            Test-IsMergeConflict -MergeOutput 'Error: at least 1 approving review is required' | Should -BeFalse
        }

        It 'returns false for auth error' {
            Test-IsMergeConflict -MergeOutput 'Error: authentication failed. Please run gh auth login.' | Should -BeFalse
        }

        It 'returns false for network error' {
            Test-IsMergeConflict -MergeOutput 'Error: connection to github.com timed out' | Should -BeFalse
        }

        It 'returns false for empty output' {
            Test-IsMergeConflict -MergeOutput '' | Should -BeFalse
        }
    }

    Context 'Edge cases' {

        It 'matches "mergeable" even as part of a JSON field name' {
            # The -match regex matches any occurrence of the string
            Test-IsMergeConflict -MergeOutput '{"mergeable":"MERGEABLE"}' | Should -BeTrue
        }

        It 'does not match unrelated words' {
            Test-IsMergeConflict -MergeOutput 'The process completed successfully.' | Should -BeFalse
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Build-TestArgs
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Build-TestArgs' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of test-argument construction from merge-prs.ps1.
            Splits the TestType string into individual arguments,
            always prepending -NoSession.
        #>
        function Build-TestArgs {
            param([string]$TestType)
            $testArgs = @('-NoSession')
            foreach ($t in ($TestType -split '\s+')) {
                if ($t) { $testArgs += $t }
            }
            return $testArgs
        }
    }

    It 'returns -NoSession and "fast" for default TestType' {
        $result = Build-TestArgs -TestType 'fast'
        $result.Count | Should -Be 2
        $result[0] | Should -BeExactly '-NoSession'
        $result[1] | Should -BeExactly 'fast'
    }

    It 'splits multiple test types by whitespace' {
        $result = Build-TestArgs -TestType 'fast ps'
        $result.Count | Should -Be 3
        $result[1] | Should -BeExactly 'fast'
        $result[2] | Should -BeExactly 'ps'
    }

    It 'handles extra whitespace between test types' {
        $result = Build-TestArgs -TestType 'fast    ps    cli'
        $result.Count | Should -Be 4
        $result[1] | Should -BeExactly 'fast'
        $result[2] | Should -BeExactly 'ps'
        $result[3] | Should -BeExactly 'cli'
    }

    It 'trims leading/trailing whitespace' {
        $result = Build-TestArgs -TestType '  it  '
        $result.Count | Should -Be 2
        $result[1] | Should -BeExactly 'it'
    }

    It 'returns only -NoSession for empty TestType' {
        $result = @(Build-TestArgs -TestType '')
        $result.Count | Should -Be 1
        $result[0] | Should -BeExactly '-NoSession'
    }

    It 'returns only -NoSession for whitespace-only TestType' {
        $result = @(Build-TestArgs -TestType '   ')
        $result.Count | Should -Be 1
        $result[0] | Should -BeExactly '-NoSession'
    }

    It 'handles Maven integration test flags' {
        $result = Build-TestArgs -TestType 'it e2e'
        $result.Count | Should -Be 3
        $result | Should -Contain 'it'
        $result | Should -Contain 'e2e'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: New-TestFailureTask
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'New-TestFailureTask' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the task-file generation logic from merge-prs.ps1.
            Produces the Markdown content for a coworker task that asks the
            agent to fix test failures after PR merges.
        #>
        function New-TestFailureTask {
            param(
                [string]$BaseBranch,
                [int[]]$Merged,
                [int[]]$ConflictResolved,
                [int]$TestExitCode,
                [string]$TestType,
                [string[]]$TestOutputTail
            )

            $mergedSummary = if ($Merged.Count -gt 0) {
                "Merged: #$($Merged -join ', #')"
            } else {
                "No direct merges"
            }
            $resolvedSummary = if ($ConflictResolved.Count -gt 0) {
                "Resolved: #$($ConflictResolved -join ', #')"
            } else {
                "No conflict resolutions"
            }

            $mergedList = if ($Merged.Count -gt 0) { $Merged -join ', ' } else { 'none' }
            $resolvedList = if ($ConflictResolved.Count -gt 0) { $ConflictResolved -join ', ' } else { 'none' }

            return @"
Title: Fix failing tests after PR merge into $BaseBranch
Description: Tests failed after merging PRs into $BaseBranch. $mergedSummary. $resolvedSummary.
Prompt: |
  The following PRs were just merged into `$BaseBranch`:
  - Direct merges: $mergedList
  - Conflict-resolved merges: $resolvedList

  Tests failed with exit code $TestExitCode when running `./bin/test.ps1 $TestType`.
  Investigate the test failures and fix them. Read the test output below,
  identify the root cause(s), and apply fixes. Run the tests again to verify.

  Test command: ./bin/test.ps1 $TestType
  Test output (last lines):
  $($TestOutputTail -join "`n")

  #auto-approve
"@
        }
    }

    Context 'Task content structure' {

        It 'includes Title header with base branch' {
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @(1, 2) `
                -ConflictResolved @() -TestExitCode 1 -TestType 'fast' -TestOutputTail @('FAIL')

            $result | Should -Match 'Title: Fix failing tests after PR merge into main'
        }

        It 'includes Description with merged PRs' {
            $result = New-TestFailureTask -BaseBranch 'develop' -Merged @(42) `
                -ConflictResolved @() -TestExitCode 1 -TestType 'fast' -TestOutputTail @('FAIL')

            $result | Should -Match 'Description: Tests failed after merging PRs into develop'
            $result | Should -Match 'Merged: #42'
        }

        It 'includes Description with conflict-resolved PRs' {
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @(1) `
                -ConflictResolved @(99) -TestExitCode 1 -TestType 'fast' -TestOutputTail @('FAIL')

            $result | Should -Match 'Resolved: #99'
        }

        It 'includes Prompt with merged and resolved PR lists' {
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @(1, 2, 3) `
                -ConflictResolved @(10, 11) -TestExitCode 2 -TestType 'fast ps' `
                -TestOutputTail @('error: test failed', '  at line 42')

            $result | Should -Match '- Direct merges: 1, 2, 3'
            $result | Should -Match '- Conflict-resolved merges: 10, 11'
            $result | Should -Match 'Tests failed with exit code 2'
            $result | Should -Match './bin/test.ps1 fast ps'
        }

        It 'shows "none" for empty merged list' {
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @() `
                -ConflictResolved @(5) -TestExitCode 1 -TestType 'fast' -TestOutputTail @('FAIL')

            $result | Should -Match '- Direct merges: none'
        }

        It 'shows "none" for empty conflict-resolved list' {
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @(1) `
                -ConflictResolved @() -TestExitCode 1 -TestType 'fast' -TestOutputTail @('FAIL')

            $result | Should -Match '- Conflict-resolved merges: none'
        }

        It 'includes test output tail' {
            $tail = @(
                '[ERROR] TestSuite: 5 tests, 3 passed, 2 failed',
                '  FAILED: should_handle_empty_input',
                '  FAILED: should_parse_json_correctly'
            )
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @(1) `
                -ConflictResolved @() -TestExitCode 1 -TestType 'fast' -TestOutputTail $tail

            $result | Should -Match '\[ERROR\] TestSuite'
            $result | Should -Match 'FAILED: should_handle_empty_input'
            $result | Should -Match 'FAILED: should_parse_json_correctly'
        }

        It 'includes #auto-approve marker' {
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @(1) `
                -ConflictResolved @() -TestExitCode 1 -TestType 'fast' -TestOutputTail @()

            $result | Should -Match '#auto-approve'
        }

        It 'handles base branch names with slashes (e.g. feature/foo)' {
            $result = New-TestFailureTask -BaseBranch 'feature/merge-prs' -Merged @(1) `
                -ConflictResolved @() -TestExitCode 1 -TestType 'fast' -TestOutputTail @()

            $result | Should -Match 'Title: Fix failing tests after PR merge into feature/merge-prs'
        }
    }

    Context 'Edge cases' {

        It 'handles empty test output tail' {
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @(1) `
                -ConflictResolved @() -TestExitCode 127 -TestType 'cli' -TestOutputTail @()

            $result | Should -Match 'Tests failed with exit code 127'
            # The "Test output (last lines):" section should be present but empty
            $result | Should -Match 'Test output \(last lines\):'
        }

        It 'handles large PR numbers' {
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @(99999) `
                -ConflictResolved @(88888, 77777) -TestExitCode 1 -TestType 'fast' `
                -TestOutputTail @('FAIL')

            $result | Should -Match '#99999'
            $result | Should -Match '#88888, #77777'
        }

        It 'handles non-zero test exit codes' {
            $result = New-TestFailureTask -BaseBranch 'main' -Merged @(1) `
                -ConflictResolved @() -TestExitCode 42 -TestType 'fast' -TestOutputTail @('FAIL')

            $result | Should -Match 'Tests failed with exit code 42'
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Format-PrSummary
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Format-PrSummary' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the merge-summary formatting logic from merge-prs.ps1.
            Produces display strings for the post-merge summary table.
        #>
        function Format-PrSummary {
            param(
                [int[]]$Merged,
                [int[]]$ConflictResolved,
                [int[]]$Skipped
            )
            return @{
                MergedDirect      = "$($Merged.Count) $($Merged -join ', ')"
                MergedAfterResolve = "$($ConflictResolved.Count) $($ConflictResolved -join ', ')"
                Skipped            = "$($Skipped.Count) $($Skipped -join ', ')"
            }
        }
    }

    It 'formats summary with all categories populated' {
        $result = Format-PrSummary -Merged @(1, 2) -ConflictResolved @(3) -Skipped @(4)

        $result.MergedDirect       | Should -BeExactly '2 1, 2'
        $result.MergedAfterResolve | Should -BeExactly '1 3'
        $result.Skipped            | Should -BeExactly '1 4'
    }

    It 'shows "0" for empty categories' {
        $result = Format-PrSummary -Merged @() -ConflictResolved @() -Skipped @()

        $result.MergedDirect       | Should -BeExactly '0 '
        $result.MergedAfterResolve | Should -BeExactly '0 '
        $result.Skipped            | Should -BeExactly '0 '
    }

    It 'formats single-item categories without trailing commas' {
        $result = Format-PrSummary -Merged @(42) -ConflictResolved @() -Skipped @()

        $result.MergedDirect | Should -BeExactly '1 42'
        # Single PR with no trailing comma
        $result.MergedDirect | Should -Not -Match ',$'
    }

    It 'joins multiple PRs with comma-space' {
        $result = Format-PrSummary -Merged @(1, 2, 3) -ConflictResolved @() -Skipped @()

        $result.MergedDirect | Should -BeExactly '3 1, 2, 3'
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Describe: Resolve-CurrentUser (gh api user fallback logic)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Resolve-CurrentUser' {

    BeforeAll {
        <#
        .SYNOPSIS
            Replica of the current-user resolution logic from merge-prs.ps1.
            When -AllAuthors is set, or gh api user fails, falls back to
            empty CurrentUser (which means "process all PRs").
        #>
        function Resolve-CurrentUser {
            param(
                [switch]$AllAuthors,
                [string]$GhApiResult,
                [int]$GhApiExitCode = 0
            )

            if ($AllAuthors) {
                return [pscustomobject]@{ CurrentUser = ''; UsedAllAuthors = $true }
            }

            if ($GhApiExitCode -ne 0 -or [string]::IsNullOrWhiteSpace($GhApiResult)) {
                return [pscustomobject]@{ CurrentUser = ''; UsedAllAuthors = $false; Fallback = $true }
            }

            return [pscustomobject]@{ CurrentUser = $GhApiResult.Trim(); UsedAllAuthors = $false; Fallback = $false }
        }
    }

    It 'returns empty user when -AllAuthors is set' {
        $result = Resolve-CurrentUser -AllAuthors
        $result.CurrentUser     | Should -BeExactly ''
        $result.UsedAllAuthors  | Should -BeTrue
    }

    It 'returns username from gh api output' {
        $result = Resolve-CurrentUser -GhApiResult 'galaxyeye'
        $result.CurrentUser     | Should -BeExactly 'galaxyeye'
        $result.UsedAllAuthors  | Should -BeFalse
        $result.Fallback        | Should -BeFalse
    }

    It 'trims whitespace from gh api output' {
        $result = Resolve-CurrentUser -GhApiResult "  galaxyeye`n"
        $result.CurrentUser | Should -BeExactly 'galaxyeye'
    }

    It 'falls back to empty user when gh api fails (non-zero exit)' {
        $result = Resolve-CurrentUser -GhApiResult 'gh: authentication failed' -GhApiExitCode 1
        $result.CurrentUser     | Should -BeExactly ''
        $result.Fallback        | Should -BeTrue
    }

    It 'falls back to empty user when gh api returns empty' {
        $result = Resolve-CurrentUser -GhApiResult ''
        $result.CurrentUser     | Should -BeExactly ''
        $result.Fallback        | Should -BeTrue
    }

    It 'falls back to empty user when gh api returns whitespace only' {
        $result = Resolve-CurrentUser -GhApiResult '   '
        $result.CurrentUser     | Should -BeExactly ''
        $result.Fallback        | Should -BeTrue
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
Write-Host "`nTest suite loaded. Run with:" -ForegroundColor Green
Write-Host "  Invoke-Pester -Path .\merge-prs.tests.ps1" -ForegroundColor Cyan
