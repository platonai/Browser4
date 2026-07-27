#!/usr/bin/env pwsh
# ═══════════════════════════════════════════════════════════════════════════════
# review-recent-issues.tests.ps1 — Tests for review-recent-issues.ps1 worker
#
# Run:  Invoke-Pester -Path .\coworker\scripts\tests\review-recent-issues.tests.ps1
# ═══════════════════════════════════════════════════════════════════════════════

$ErrorActionPreference = 'Continue'

# ═══════════════════════════════════════════════════════════════════════════════
# Pure functions under test (no external dependencies)
# ═══════════════════════════════════════════════════════════════════════════════

function global:Test-IsDotDirectory {
    param([Parameter(Mandatory)] [string]$DirectoryName)
    return $DirectoryName.StartsWith('.')
}

function global:Test-IsAlreadyReviewed {
    param(
        [Parameter(Mandatory)] [string]$FilePath,
        [Parameter(Mandatory)] [string]$ReviewDoneDir,
        [Parameter(Mandatory)] [string]$ReviewDiscardDir
    )
    $normalized = [System.IO.Path]::GetFullPath($FilePath)
    $doneNorm = [System.IO.Path]::GetFullPath($ReviewDoneDir)
    $discardNorm = [System.IO.Path]::GetFullPath($ReviewDiscardDir)
    return ($normalized.StartsWith($doneNorm, [StringComparison]::OrdinalIgnoreCase) -or
            $normalized.StartsWith($discardNorm, [StringComparison]::OrdinalIgnoreCase))
}

function global:Get-RepoRelativePath {
    param(
        [Parameter(Mandatory)] [string]$AbsolutePath,
        [Parameter(Mandatory)] [string]$RepoRoot
    )
    $repoNorm = [System.IO.Path]::GetFullPath($RepoRoot).TrimEnd('\', '/')
    $fileNorm = [System.IO.Path]::GetFullPath($AbsolutePath)
    if ($fileNorm.StartsWith($repoNorm, [StringComparison]::OrdinalIgnoreCase)) {
        $relative = $fileNorm.Substring($repoNorm.Length).TrimStart('\', '/')
        return $relative -replace '\\', '/'
    }
    return $AbsolutePath
}

function global:Find-RecentIssueFiles {
    param(
        [Parameter(Mandatory)] [string]$BaseDirectory,
        [Parameter(Mandatory)] [datetime]$CutoffDate,
        [Parameter(Mandatory)] [string]$ReviewDoneDir,
        [Parameter(Mandatory)] [string]$ReviewDiscardDir,
        [string]$SourceLabel = 'test'
    )
    if (-not (Test-Path $BaseDirectory)) { return @() }
    $result = [System.Collections.ArrayList]::new()
    try {
        $stack = [System.Collections.Stack]::new()
        $stack.Push($BaseDirectory)
        while ($stack.Count -gt 0) {
            $currentDir = $stack.Pop()
            $dirName = Split-Path -Leaf $currentDir
            if ($currentDir -ne $BaseDirectory -and (Test-IsDotDirectory -DirectoryName $dirName)) { continue }
            $files = Get-ChildItem -Path $currentDir -File -Filter '*.issues.md' -ErrorAction SilentlyContinue |
                Where-Object { $_.LastWriteTime -ge $CutoffDate } |
                Where-Object { -not (Test-IsAlreadyReviewed -FilePath $_.FullName -ReviewDoneDir $ReviewDoneDir -ReviewDiscardDir $ReviewDiscardDir) }
            foreach ($file in $files) {
                [void]$result.Add(@{
                    FileInfo = $file; SourceLabel = $SourceLabel; SourceDir = $BaseDirectory
                    IsInDraft = ($SourceLabel -eq 'draft')
                })
            }
            $subdirs = Get-ChildItem -Path $currentDir -Directory -ErrorAction SilentlyContinue |
                Where-Object { -not (Test-IsDotDirectory -DirectoryName $_.Name) }
            foreach ($subdir in $subdirs) { $stack.Push($subdir.FullName) }
        }
    } catch { }
    $sorted = $result | Sort-Object { -not $_.IsInDraft }, { $_.FileInfo.LastWriteTime.ToString('o') }
    return , @($sorted)
}

function global:Move-DraftToReview {
    param(
        [Parameter(Mandatory)] [System.IO.FileInfo]$File,
        [Parameter(Mandatory)] [string]$DraftBase,
        [Parameter(Mandatory)] [string]$ReviewDir,
        [switch]$DryRun
    )
    $draftNorm = [System.IO.Path]::GetFullPath($DraftBase)
    $fileNorm  = [System.IO.Path]::GetFullPath($File.FullName)
    $relativePath = $fileNorm.Substring($draftNorm.Length).TrimStart('\', '/')
    $reviewNorm  = [System.IO.Path]::GetFullPath($ReviewDir)
    $destDir = Join-Path $reviewNorm (Split-Path -Parent $relativePath)
    $destPath = Join-Path $destDir $File.Name
    if (-not (Test-Path $destDir)) { New-Item -ItemType Directory -Path $destDir -Force | Out-Null }
    if (Test-Path $destPath) {
        $baseName = $File.BaseName; $ext = $File.Extension; $counter = 2
        while (Test-Path $destPath) { $destPath = Join-Path $destDir "$baseName.$counter$ext"; $counter++ }
    }
    if ($DryRun) { return $destPath }
    Move-Item -Path $File.FullName -Destination $destPath -Force
    return $destPath
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test helpers — each It block manages its own temp directory
# ═══════════════════════════════════════════════════════════════════════════════

function global:New-TestRoot {
    $root = Join-Path ([System.IO.Path]::GetTempPath()) "RRT_$(Get-Random -Minimum 10000 -Maximum 99999)"
    New-Item -ItemType Directory -Path $root -Force | Out-Null
    return $root
}

function global:Remove-TestRoot {
    param([string]$Path)
    if ($Path -and (Test-Path $Path)) {
        Remove-Item -Path $Path -Recurse -Force -ErrorAction SilentlyContinue
    }
}

function global:New-TDir {
    param([string]$Root, [string]$RelativePath)
    $p = Join-Path $Root $RelativePath
    New-Item -ItemType Directory -Path $p -Force | Out-Null
    return $p
}

function global:New-TFile {
    param(
        [string]$Root,
        [string]$RelativePath,
        [string]$Content = '# test',
        $LastWriteTime = $null
    )
    $p = Join-Path $Root $RelativePath
    $parent = Split-Path -Parent $p
    if (-not (Test-Path $parent)) { New-Item -ItemType Directory -Path $parent -Force | Out-Null }
    Set-Content -Path $p -Value $Content -Encoding UTF8
    if ($null -ne $LastWriteTime) { (Get-Item $p).LastWriteTime = $LastWriteTime }
    return Get-Item $p
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test-IsDotDirectory
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Test-IsDotDirectory' {

    It 'returns true for names starting with "."' {
        Test-IsDotDirectory -DirectoryName '.git'     | Should -BeTrue
        Test-IsDotDirectory -DirectoryName '.hidden'  | Should -BeTrue
        Test-IsDotDirectory -DirectoryName '.'        | Should -BeTrue
        Test-IsDotDirectory -DirectoryName '.config'  | Should -BeTrue
    }

    It 'returns false for normal directory names' {
        Test-IsDotDirectory -DirectoryName 'draft'    | Should -BeFalse
        Test-IsDotDirectory -DirectoryName 'review'   | Should -BeFalse
        Test-IsDotDirectory -DirectoryName '2026'     | Should -BeFalse
        Test-IsDotDirectory -DirectoryName '0709'     | Should -BeFalse
        Test-IsDotDirectory -DirectoryName 'done'     | Should -BeFalse
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Test-IsAlreadyReviewed
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Test-IsAlreadyReviewed' {

    It 'returns true for files under review/done/' {
        $root = New-TestRoot
        try {
            $doneDir    = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'
            $file = New-TFile -Root $root -RelativePath 'review\done\2026\0709\test.issues.md'

            Test-IsAlreadyReviewed -FilePath $file.FullName `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir | Should -BeTrue
        } finally { Remove-TestRoot $root }
    }

    It 'returns true for files under review/done/discard/' {
        $root = New-TestRoot
        try {
            $doneDir    = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'
            $file = New-TFile -Root $root -RelativePath 'review\done\discard\2026\0709\test.issues.md'

            Test-IsAlreadyReviewed -FilePath $file.FullName `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir | Should -BeTrue
        } finally { Remove-TestRoot $root }
    }

    It 'returns false for files in review/ (not under done/)' {
        $root = New-TestRoot
        try {
            $doneDir    = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'
            $file = New-TFile -Root $root -RelativePath 'review\2026\0709\test.issues.md'

            Test-IsAlreadyReviewed -FilePath $file.FullName `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir | Should -BeFalse
        } finally { Remove-TestRoot $root }
    }

    It 'returns false for files in draft/' {
        $root = New-TestRoot
        try {
            $doneDir    = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'
            $file = New-TFile -Root $root -RelativePath 'draft\test.issues.md'

            Test-IsAlreadyReviewed -FilePath $file.FullName `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir | Should -BeFalse
        } finally { Remove-TestRoot $root }
    }

    It 'is case-insensitive' {
        $root = New-TestRoot
        try {
            $doneDir    = New-TDir -Root $root -RelativePath 'REVIEW\DONE'
            $discardDir = New-TDir -Root $root -RelativePath 'REVIEW\DONE\DISCARD'
            $file = New-TFile -Root $root -RelativePath 'review\done\case-test.issues.md'

            Test-IsAlreadyReviewed -FilePath $file.FullName `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir | Should -BeTrue
        } finally { Remove-TestRoot $root }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Get-RepoRelativePath
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Get-RepoRelativePath' {

    It 'returns a path relative to the repo root' {
        $root = New-TestRoot
        try {
            $repo = New-TDir -Root $root -RelativePath 'repo'
            $file = New-TFile -Root $root -RelativePath 'repo\coworker\tasks\issues\review\test.issues.md'
            $result = Get-RepoRelativePath -AbsolutePath $file.FullName -RepoRoot $repo
            $result | Should -BeExactly 'coworker/tasks/issues/review/test.issues.md'
        } finally { Remove-TestRoot $root }
    }

    It 'uses forward slashes regardless of OS' {
        $root = New-TestRoot
        try {
            $repo = New-TDir -Root $root -RelativePath 'repo'
            $file = New-TFile -Root $root -RelativePath 'repo\subdir\file.md'
            $result = Get-RepoRelativePath -AbsolutePath $file.FullName -RepoRoot $repo
            $result | Should -Not -Match '\\'
            $result | Should -BeExactly 'subdir/file.md'
        } finally { Remove-TestRoot $root }
    }

    It 'returns the absolute path unchanged when not under repo root' {
        $root = New-TestRoot
        try {
            $repo = New-TDir -Root $root -RelativePath 'repo'
            $file = New-TFile -Root $root -RelativePath 'other\file.md'
            $result = Get-RepoRelativePath -AbsolutePath $file.FullName -RepoRoot $repo
            $result | Should -BeExactly $file.FullName
        } finally { Remove-TestRoot $root }
    }

    It 'handles trailing slashes on repo root' {
        $root = New-TestRoot
        try {
            $repo = New-TDir -Root $root -RelativePath 'repo'
            $file = New-TFile -Root $root -RelativePath 'repo\subdir\file.md'
            $result = Get-RepoRelativePath -AbsolutePath $file.FullName -RepoRoot ($repo + '\')
            $result | Should -BeExactly 'subdir/file.md'
        } finally { Remove-TestRoot $root }
    }

    It 'returns empty string for repo root itself' {
        $root = New-TestRoot
        try {
            $repo = New-TDir -Root $root -RelativePath 'repo'
            $result = Get-RepoRelativePath -AbsolutePath $repo -RepoRoot $repo
            $result | Should -BeExactly ''
        } finally { Remove-TestRoot $root }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Find-RecentIssueFiles
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Find-RecentIssueFiles' {

    It 'returns empty array when directory does not exist' {
        $root = New-TestRoot
        try {
            $result = Find-RecentIssueFiles -BaseDirectory "$root\nonexistent" `
                -CutoffDate (Get-Date).AddDays(-3) `
                -ReviewDoneDir "$root\review\done" `
                -ReviewDiscardDir "$root\review\done\discard"
            @($result).Length | Should -Be 0
        } finally { Remove-TestRoot $root }
    }

    It 'finds .issues.md files modified after the cutoff date' {
        $root = New-TestRoot
        try {
            $draftDir = New-TDir -Root $root -RelativePath 'draft'
            New-TFile -Root $root -RelativePath 'draft\recent.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'draft\old.issues.md' -LastWriteTime (Get-Date).AddDays(-10)
            $cutoff = (Get-Date).AddDays(-3)

            $result = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard"

            @($result).Length | Should -Be 1
            $result[0].FileInfo.Name | Should -BeExactly 'recent.issues.md'
        } finally { Remove-TestRoot $root }
    }

    It 'excludes files in review/done/' {
        $root = New-TestRoot
        try {
            New-TDir -Root $root -RelativePath 'review\done'
            New-TDir -Root $root -RelativePath 'review\done\discard'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'

            New-TFile -Root $root -RelativePath 'review\active.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'review\done\already-reviewed.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'review\done\discard\discarded.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            $cutoff = (Get-Date).AddDays(-3)

            $result = Find-RecentIssueFiles -BaseDirectory $reviewDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard"

            @($result).Length | Should -Be 1
            $result[0].FileInfo.Name | Should -BeExactly 'active.issues.md'
        } finally { Remove-TestRoot $root }
    }

    It 'skips dot-directories' {
        $root = New-TestRoot
        try {
            $draftDir = New-TDir -Root $root -RelativePath 'draft'
            New-TFile -Root $root -RelativePath 'draft\normal-dir\visible.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'draft\.hidden-dir\hidden.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            $cutoff = (Get-Date).AddDays(-3)

            $result = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard"

            @($result).Length | Should -Be 1
            $result[0].FileInfo.Name | Should -BeExactly 'visible.issues.md'
        } finally { Remove-TestRoot $root }
    }

    It 'does not skip the root scan directory even if it starts with dot' {
        $root = New-TestRoot
        try {
            $dotBaseDir = New-TDir -Root $root -RelativePath '.my-drafts'
            New-TFile -Root $root -RelativePath '.my-drafts\test.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            $cutoff = (Get-Date).AddDays(-3)

            $result = Find-RecentIssueFiles -BaseDirectory $dotBaseDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard"

            @($result).Length | Should -Be 1
        } finally { Remove-TestRoot $root }
    }

    It 'sorts draft files before review files' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'

            New-TFile -Root $root -RelativePath 'draft\first.issues.md' -LastWriteTime (Get-Date).AddHours(-2)
            New-TFile -Root $root -RelativePath 'review\second.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            $cutoff = (Get-Date).AddDays(-3)

            $draftResult = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard" `
                -SourceLabel 'draft'

            $reviewResult = Find-RecentIssueFiles -BaseDirectory $reviewDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard" `
                -SourceLabel 'review'

            $combined = @($draftResult; $reviewResult) | Sort-Object { -not $_.IsInDraft }, { $_.FileInfo.LastWriteTime.ToString('o') }

            @($combined).Length | Should -Be 2
            $combined[0].IsInDraft | Should -BeTrue
            $combined[0].FileInfo.Name | Should -BeExactly 'first.issues.md'
            $combined[1].IsInDraft | Should -BeFalse
            $combined[1].FileInfo.Name | Should -BeExactly 'second.issues.md'
        } finally { Remove-TestRoot $root }
    }

    It 'ignores non-.issues.md files' {
        $root = New-TestRoot
        try {
            $draftDir = New-TDir -Root $root -RelativePath 'draft'
            New-TFile -Root $root -RelativePath 'draft\real.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'draft\notes.txt' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'draft\readme.md' -LastWriteTime (Get-Date).AddHours(-1)
            $cutoff = (Get-Date).AddDays(-3)

            $result = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard"

            @($result).Length | Should -Be 1
            $result[0].FileInfo.Name | Should -BeExactly 'real.issues.md'
        } finally { Remove-TestRoot $root }
    }

    It 'discovers files recursively in nested date directories' {
        $root = New-TestRoot
        try {
            $draftDir = New-TDir -Root $root -RelativePath 'draft'
            New-TFile -Root $root -RelativePath 'draft\2026\0709\20260709-221515-form-filling.issues.md' `
                -LastWriteTime (Get-Date).AddHours(-1)
            $cutoff = (Get-Date).AddDays(-3)

            $result = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard"

            @($result).Length | Should -Be 1
            $result[0].FileInfo.Name | Should -BeExactly '20260709-221515-form-filling.issues.md'
        } finally { Remove-TestRoot $root }
    }

    It 'returns empty array when no recent files exist' {
        $root = New-TestRoot
        try {
            $draftDir = New-TDir -Root $root -RelativePath 'draft'
            New-TFile -Root $root -RelativePath 'draft\old.issues.md' -LastWriteTime (Get-Date).AddDays(-30)
            $cutoff = (Get-Date).AddDays(-3)

            $result = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard"

            @($result).Length | Should -Be 0
        } finally { Remove-TestRoot $root }
    }

    It 'handles empty directory gracefully' {
        $root = New-TestRoot
        try {
            $draftDir = New-TDir -Root $root -RelativePath 'draft'
            $cutoff = (Get-Date).AddDays(-3)

            $result = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $cutoff `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard"

            @($result).Length | Should -Be 0
        } finally { Remove-TestRoot $root }
    }

    It 'handles exact-boundary cutoff dates' {
        $root = New-TestRoot
        try {
            $draftDir = New-TDir -Root $root -RelativePath 'draft'
            $exactCutoffTime = (Get-Date).AddDays(-3)
            New-TFile -Root $root -RelativePath 'draft\exact.issues.md' -LastWriteTime $exactCutoffTime
            New-TFile -Root $root -RelativePath 'draft\before.issues.md' -LastWriteTime $exactCutoffTime.AddSeconds(-1)

            $result = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $exactCutoffTime `
                -ReviewDoneDir "$root\review\done" -ReviewDiscardDir "$root\review\done\discard"

            @($result).Length | Should -Be 1
            $result[0].FileInfo.Name | Should -BeExactly 'exact.issues.md'
        } finally { Remove-TestRoot $root }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Move-DraftToReview
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Move-DraftToReview' {

    It 'moves a file from draft/ to review/ preserving relative path' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $file = New-TFile -Root $root -RelativePath 'draft\2026\0709\my-issues.issues.md'

            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir

            $destPath | Should -Match 'review\\2026\\0709\\my-issues\.issues\.md$'
            Test-Path -LiteralPath $destPath | Should -BeTrue
            Test-Path -LiteralPath $file.FullName | Should -BeFalse
        } finally { Remove-TestRoot $root }
    }

    It 'moves a file from draft/ root to review/ root' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $file = New-TFile -Root $root -RelativePath 'draft\root-file.issues.md'

            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir

            $destPath | Should -Match 'review\\root-file\.issues\.md$'
            Test-Path -LiteralPath $destPath | Should -BeTrue
        } finally { Remove-TestRoot $root }
    }

    It 'creates the destination directory if it does not exist' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = Join-Path $root 'review'  # Does not exist yet
            $file = New-TFile -Root $root -RelativePath 'draft\2026\0709\test.issues.md'

            Test-Path -LiteralPath (Join-Path $reviewDir '2026\0709') | Should -BeFalse

            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir

            Test-Path -LiteralPath (Join-Path $reviewDir '2026\0709') | Should -BeTrue
            Test-Path -LiteralPath $destPath | Should -BeTrue
        } finally { Remove-TestRoot $root }
    }

    It 'handles filename collisions by appending a counter' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $file = New-TFile -Root $root -RelativePath 'draft\collision.issues.md' -Content 'new content'
            New-TFile -Root $root -RelativePath 'review\collision.issues.md' -Content 'existing content'

            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir

            $destName = Split-Path -Leaf $destPath
            $destName | Should -Not -BeExactly 'collision.issues.md'
            $destName | Should -Match 'collision\.issues\.\d+\.md$'

            Test-Path -LiteralPath (Join-Path $reviewDir 'collision.issues.md') | Should -BeTrue
            (Get-Content -Path (Join-Path $reviewDir 'collision.issues.md') -Raw).Trim() | Should -BeExactly 'existing content'
        } finally { Remove-TestRoot $root }
    }

    It 'handles multiple collisions (counter > 2)' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            New-TFile -Root $root -RelativePath 'review\collision.issues.md' -Content 'existing 1'
            New-TFile -Root $root -RelativePath 'review\collision.issues.2.md' -Content 'existing 2'
            $file = New-TFile -Root $root -RelativePath 'draft\collision.issues.md' -Content 'new content'

            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir

            $destName = Split-Path -Leaf $destPath
            $destName | Should -Match 'collision\.issues\.3\.md$'
        } finally { Remove-TestRoot $root }
    }

    It 'does not actually move in DryRun mode' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $file = New-TFile -Root $root -RelativePath 'draft\dryrun.issues.md'

            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir -DryRun

            $destPath | Should -Match 'review\\dryrun\.issues\.md$'
            Test-Path -LiteralPath $file.FullName | Should -BeTrue
            Test-Path -LiteralPath $destPath | Should -BeFalse
        } finally { Remove-TestRoot $root }
    }

    It 'preserves file content across the move' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $content = "# Issues: test`n`n> **Source:** ``test.md`` | **Date:** 20260728 | **Mode:** dev`n`n## Issues Found`n`n### Issue 1: Test`n`n**Severity:** Low`n**Category:** UX"
            $file = New-TFile -Root $root -RelativePath 'draft\content-test.issues.md' -Content $content

            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir

            $movedContent = (Get-Content -Path $destPath -Raw -Encoding UTF8).TrimEnd("`r`n")
            $movedContent | Should -BeExactly $content
        } finally { Remove-TestRoot $root }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Integration: DryRun pipeline (end-to-end without invoking real review)
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Integration: DryRun pipeline' {

    It 'discovers draft files, moves them to review, and reports results' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $doneDir   = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'

            New-TFile -Root $root -RelativePath 'draft\recent-a.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'draft\recent-b.issues.md' -LastWriteTime (Get-Date).AddHours(-2)
            New-TFile -Root $root -RelativePath 'draft\old.issues.md' -LastWriteTime (Get-Date).AddDays(-10)
            $cutoff = (Get-Date).AddDays(-3)

            $draftResults = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $cutoff `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir

            @($draftResults).Length | Should -Be 2

            $movedPaths = @()
            foreach ($entry in $draftResults) {
                $movedPaths += Move-DraftToReview -File $entry.FileInfo `
                    -DraftBase $draftDir -ReviewDir $reviewDir -DryRun
            }

            @($movedPaths).Length | Should -Be 2
            foreach ($p in $movedPaths) { $p | Should -Match 'review\\' }

            $names = $draftResults | ForEach-Object { $_.FileInfo.Name } | Sort-Object
            $names -contains 'recent-a.issues.md' | Should -BeTrue
            $names -contains 'recent-b.issues.md' | Should -BeTrue
            $names -contains 'old.issues.md' | Should -BeFalse
        } finally { Remove-TestRoot $root }
    }

    It 'skips already-reviewed files even if recently modified' {
        $root = New-TestRoot
        try {
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $doneDir   = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'

            New-TFile -Root $root -RelativePath 'review\done\recent-done.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'review\done\discard\recent-discard.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'review\active.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            $cutoff = (Get-Date).AddDays(-3)

            $results = Find-RecentIssueFiles -BaseDirectory $reviewDir -CutoffDate $cutoff `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir

            @($results).Length | Should -Be 1
            $results[0].FileInfo.Name | Should -BeExactly 'active.issues.md'
        } finally { Remove-TestRoot $root }
    }

    It 'handles mixed draft+review sources correctly' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $doneDir   = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'

            New-TFile -Root $root -RelativePath 'draft\draft-file.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'review\review-file.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            $cutoff = (Get-Date).AddDays(-3)

            $draftResults = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate $cutoff `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir -SourceLabel 'draft'

            $reviewResults = Find-RecentIssueFiles -BaseDirectory $reviewDir -CutoffDate $cutoff `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir -SourceLabel 'review'

            @($draftResults).Length | Should -Be 1
            @($reviewResults).Length | Should -Be 1
            $draftResults[0].IsInDraft | Should -BeTrue
            $reviewResults[0].IsInDraft | Should -BeFalse

            $movedDraft = Move-DraftToReview -File $draftResults[0].FileInfo `
                -DraftBase $draftDir -ReviewDir $reviewDir -DryRun

            $movedDraft | Should -Match 'review\\draft-file\.issues\.md$'
        } finally { Remove-TestRoot $root }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# Edge cases
# ═══════════════════════════════════════════════════════════════════════════════

Describe 'Edge cases' {

    It 'handles files with special characters in names' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $doneDir   = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'

            $specialName = 'Use Case 1_ E-commerce Product Comparison (Single-site).issues.md'
            $file = New-TFile -Root $root -RelativePath "draft\$specialName" -LastWriteTime (Get-Date).AddHours(-1)

            $results = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate (Get-Date).AddDays(-3) `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir

            @($results).Length | Should -Be 1
            $results[0].FileInfo.Name | Should -BeExactly $specialName

            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir
            Test-Path -LiteralPath $destPath | Should -BeTrue
        } finally { Remove-TestRoot $root }
    }

    It 'handles Unicode characters in filenames' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $doneDir   = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'

            $unicodeName = '百度百科公司信息对比（单站点）.issues.md'
            $file = New-TFile -Root $root -RelativePath "draft\$unicodeName" -LastWriteTime (Get-Date).AddHours(-1)

            $results = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate (Get-Date).AddDays(-3) `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir

            @($results).Length | Should -Be 1
            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir
            Test-Path -LiteralPath $destPath | Should -BeTrue
            (Split-Path -Leaf $destPath) | Should -BeExactly $unicodeName
        } finally { Remove-TestRoot $root }
    }

    It 'handles deeply nested directory structures' {
        $root = New-TestRoot
        try {
            $draftDir  = New-TDir -Root $root -RelativePath 'draft'
            $reviewDir = New-TDir -Root $root -RelativePath 'review'
            $doneDir   = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'

            $file = New-TFile -Root $root -RelativePath 'draft\2026\0709\a\b\c\d\deep-file.issues.md' `
                -LastWriteTime (Get-Date).AddHours(-1)

            $results = Find-RecentIssueFiles -BaseDirectory $draftDir -CutoffDate (Get-Date).AddDays(-3) `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir

            @($results).Length | Should -Be 1

            $destPath = Move-DraftToReview -File $file -DraftBase $draftDir -ReviewDir $reviewDir
            $destPath | Should -Match 'review\\2026\\0709\\a\\b\\c\\d\\deep-file\.issues\.md$'
            Test-Path -LiteralPath $destPath | Should -BeTrue
        } finally { Remove-TestRoot $root }
    }

    It 'handles many nested dot-directories mixed with normal dirs' {
        $root = New-TestRoot
        try {
            $baseDir   = New-TDir -Root $root -RelativePath 'mixed'
            $doneDir   = New-TDir -Root $root -RelativePath 'review\done'
            $discardDir = New-TDir -Root $root -RelativePath 'review\done\discard'

            New-TFile -Root $root -RelativePath 'mixed\good.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'mixed\normal-subdir\nested-good.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'mixed\.hidden\hidden.issues.md' -LastWriteTime (Get-Date).AddHours(-1)
            New-TFile -Root $root -RelativePath 'mixed\normal-subdir\.nested-dot\nested-hidden.issues.md' -LastWriteTime (Get-Date).AddHours(-1)

            $results = Find-RecentIssueFiles -BaseDirectory $baseDir -CutoffDate (Get-Date).AddDays(-3) `
                -ReviewDoneDir $doneDir -ReviewDiscardDir $discardDir

            @($results).Length | Should -Be 2
            $names = $results | ForEach-Object { $_.FileInfo.Name } | Sort-Object
            $names[0] | Should -BeExactly 'good.issues.md'
            $names[1] | Should -BeExactly 'nested-good.issues.md'
        } finally { Remove-TestRoot $root }
    }
}
