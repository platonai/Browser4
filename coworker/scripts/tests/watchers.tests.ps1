#!/usr/bin/env pwsh
# ═══════════════════════════════════════════════════════════════════════════════
# watchers.tests.ps1 — Tests for common/Watchers.ps1 file-classification helpers
#
# Unlike most sibling test files (which test inline replicas), these tests
# dot-source the REAL common/Watchers.ps1 because the memoization cache in
# Test-CoworkerDotPath is exactly where regressions live.
#
# Run:  Invoke-Pester -Path .\coworker\scripts\tests\watchers.tests.ps1
# ═══════════════════════════════════════════════════════════════════════════════

$ErrorActionPreference = 'Continue'

function global:New-WatchersFixtureDir {
    $dir = Join-Path ([System.IO.Path]::GetTempPath()) "CoworkerWatchersTests_$(Get-Random -Minimum 100000 -Maximum 999999)"
    New-Item -ItemType Directory -Path $dir -Force | Out-Null
    return $dir
}

BeforeAll {
    # Dot-source the REAL Watchers.ps1 (not a replica) — the memoization cache
    # in Test-CoworkerDotPath is exactly where regressions live.
    . (Join-Path $PSScriptRoot '..\common\Watchers.ps1')
}

Describe 'Test-CoworkerDotPath (real Watchers.ps1)' {
    It 'flags a dot-prefixed file itself' {
        $dir = New-WatchersFixtureDir
        try {
            $f = Join-Path $dir '.hidden.md'
            Set-Content -Path $f -Value 'x' -Encoding UTF8
            Test-CoworkerDotPath -Item (Get-Item -LiteralPath $f) | Should -BeTrue
        } finally { Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue }
    }

    It 'flags a file under a dot-prefixed directory' {
        $dir = New-WatchersFixtureDir
        try {
            $dotDir = Join-Path $dir '.locks'
            New-Item -ItemType Directory -Path $dotDir -Force | Out-Null
            $f = Join-Path $dotDir 'lock.md'
            Set-Content -Path $f -Value 'x' -Encoding UTF8
            Test-CoworkerDotPath -Item (Get-Item -LiteralPath $f) | Should -BeTrue
        } finally { Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue }
    }

    It 'does not flag a normal file in a clean directory' {
        $dir = New-WatchersFixtureDir
        try {
            $f = Join-Path $dir 'task.md'
            Set-Content -Path $f -Value 'x' -Encoding UTF8
            Test-CoworkerDotPath -Item (Get-Item -LiteralPath $f) | Should -BeFalse
        } finally { Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue }
    }

    It 'flags a dot-prefixed directory itself, not its clean sibling' {
        $dir = New-WatchersFixtureDir
        try {
            $dotDir = Join-Path $dir '.hidden-dir'
            $cleanDir = Join-Path $dir 'visible-dir'
            New-Item -ItemType Directory -Path $dotDir -Force | Out-Null
            New-Item -ItemType Directory -Path $cleanDir -Force | Out-Null
            Test-CoworkerDotPath -Item (Get-Item -LiteralPath $dotDir)   | Should -BeTrue
            Test-CoworkerDotPath -Item (Get-Item -LiteralPath $cleanDir) | Should -BeFalse
        } finally { Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue }
    }

    It 'is not order-dependent: a dot file checked last is still flagged' {
        # Regression: the old per-directory cache returned the first file's
        # result for every sibling, so a normal file checked first made a
        # later dot file "invisible".
        $dir = New-WatchersFixtureDir
        try {
            $normal = Join-Path $dir 'task.md'
            $hidden = Join-Path $dir '.hidden.md'
            Set-Content -Path $normal -Value 'x' -Encoding UTF8
            Set-Content -Path $hidden -Value 'x' -Encoding UTF8
            Test-CoworkerDotPath -Item (Get-Item -LiteralPath $normal) | Should -BeFalse
            Test-CoworkerDotPath -Item (Get-Item -LiteralPath $hidden) | Should -BeTrue
        } finally { Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue }
    }

    It 'is not order-dependent: a dot file checked first does not hide its siblings' {
        # Regression: the old per-directory cache stored the dot file's TRUE
        # result under the shared directory key and then ignored EVERY other
        # file in the same directory (e.g. a .gitkeep poisoned real tasks).
        $dir = New-WatchersFixtureDir
        try {
            $hidden = Join-Path $dir '.hidden.md'
            $normal = Join-Path $dir 'task.md'
            Set-Content -Path $hidden -Value 'x' -Encoding UTF8
            Set-Content -Path $normal -Value 'x' -Encoding UTF8
            Test-CoworkerDotPath -Item (Get-Item -LiteralPath $hidden) | Should -BeTrue
            Test-CoworkerDotPath -Item (Get-Item -LiteralPath $normal) | Should -BeFalse
        } finally { Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue }
    }
}

Describe 'Test-CoworkerIgnoredFile / Test-CoworkerPendingFile (real Watchers.ps1)' {
    It 'ignores .gitkeep placeholders but not their siblings' {
        $dir = New-WatchersFixtureDir
        try {
            $keep = Join-Path $dir '.gitkeep'
            $task = Join-Path $dir 'task.md'
            [System.IO.File]::WriteAllBytes($keep, @())
            Set-Content -Path $task -Value 'x' -Encoding UTF8
            Test-CoworkerIgnoredFile -Item (Get-Item -LiteralPath $keep) | Should -BeTrue
            Test-CoworkerIgnoredFile -Item (Get-Item -LiteralPath $task) | Should -BeFalse
            Test-CoworkerPendingFile   -Item (Get-Item -LiteralPath $keep) | Should -BeFalse
            Test-CoworkerPendingFile   -Item (Get-Item -LiteralPath $task) | Should -BeTrue
        } finally { Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue }
    }

    It 'ignores dot files, dot directories, and .gitkeep in the same directory without hiding real tasks' {
        $dir = New-WatchersFixtureDir
        try {
            $dotFile = Join-Path $dir '.state.json'
            $keep    = Join-Path $dir '.gitkeep'
            $dotDir  = Join-Path $dir '.locks'
            $task    = Join-Path $dir 'real-task.md'
            New-Item -ItemType Directory -Path $dotDir -Force | Out-Null
            Set-Content -Path $dotFile -Value 'x' -Encoding UTF8
            [System.IO.File]::WriteAllBytes($keep, @())
            Set-Content -Path (Join-Path $dotDir 'inner.md') -Value 'x' -Encoding UTF8
            Set-Content -Path $task -Value 'x' -Encoding UTF8

            # Check the dot entries FIRST (alphabetical/enumeration order) so
            # the old cache-poisoning bug would have hidden real-task.md.
            Test-CoworkerIgnoredFile -Item (Get-Item -LiteralPath $dotFile) | Should -BeTrue
            Test-CoworkerIgnoredFile -Item (Get-Item -LiteralPath $keep)    | Should -BeTrue
            Test-CoworkerIgnoredFile -Item (Get-Item -LiteralPath (Join-Path $dotDir 'inner.md')) | Should -BeTrue
            Test-CoworkerIgnoredFile -Item (Get-Item -LiteralPath $task)    | Should -BeFalse
        } finally { Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue }
    }
}

Describe 'Recursive stage scan (engineer.ps1 1ready expression)' {
    It 'finds tasks in date-organized subdirectories while skipping every dot entry' {
        $dir = New-WatchersFixtureDir
        try {
            $ready = Join-Path $dir '1ready'
            $nested = Join-Path $ready '2026\0905'
            $deeper = Join-Path $nested 'deep'
            $dotDir = Join-Path $ready '.locks'
            foreach ($d in @($nested, $deeper, $dotDir)) {
                New-Item -ItemType Directory -Path $d -Force | Out-Null
            }
            # Real tasks
            Set-Content -Path (Join-Path $ready  'a-root-task.md')                 -Value 'x' -Encoding UTF8
            Set-Content -Path (Join-Path $nested 'b-issues.issues.md')             -Value 'x' -Encoding UTF8
            Set-Content -Path (Join-Path $deeper 'c-deep-task.md')                 -Value 'x' -Encoding UTF8
            # Dot entries that must be ignored (root + nested, checked first by
            # Get-ChildItem's enumeration order on Windows)
            [System.IO.File]::WriteAllBytes((Join-Path $ready  '.gitkeep'), @())
            [System.IO.File]::WriteAllBytes((Join-Path $nested '.gitkeep'), @())
            Set-Content -Path (Join-Path $ready  '.hidden.md')    -Value 'x' -Encoding UTF8
            Set-Content -Path (Join-Path $nested '.deep-hidden.md') -Value 'x' -Encoding UTF8
            Set-Content -Path (Join-Path $dotDir 'lock.md')       -Value 'x' -Encoding UTF8

            # The exact expression used by engineer.ps1 to scan 1ready
            $files = @(Get-ChildItem -Path $ready -Recurse -File |
                Where-Object { -not (Test-CoworkerIgnoredFile -Item $_) })

            $names = @($files | ForEach-Object { $_.FullName.Substring($ready.Length + 1) })
            ($names | Sort-Object) -join '|' | Should -BeExactly '2026\0905\b-issues.issues.md|2026\0905\deep\c-deep-task.md|a-root-task.md' -Because 'every real task at any depth is found and every dot entry is skipped'
        } finally { Remove-Item -Path $dir -Recurse -Force -ErrorAction SilentlyContinue }
    }
}
