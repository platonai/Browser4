#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use Join-Path / [System.IO.Path] for every path.
# - Keep fixture content ASCII-only so Set-Content's default encoding
#   (UTF-8 without BOM on pwsh, ANSI on Windows PowerShell) writes the
#   same bytes either way and the frontmatter stays byte-identical.
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Boundary tests for the M6 line-count rule in bin/skill-doc-lint.ps1.

.DESCRIPTION
    M6 is two rules, not one, because the two kinds of document are read
    differently (methodology v2.3):

      * the manifest (SKILL.md) loads in full on every activation, so it is a
        hard gate: 500 lines -> conformant, 501 lines -> issue (exit 1)
      * decision/procedure references are read on demand, where the methodology
        states a 500-line *target* rather than a machine test:
            501..700 lines -> advisory (reported, exit 0)
            >700 lines     -> hard failure (a split candidate, exit 1)
            -Strict        -> advisories become failures
      * `catalog` documents are unbounded
      * there is no minimum: a short document that answers its tier's questions
        is conformant

    This split is the fix for a gate that failed 24 times in 100 runs over a
    skills/ document — every time a reference that had grown past 500 lines,
    never a PowerShell defect.  The fixtures are conformant in every other check
    (they carry their tier's template sections), so the exit code asserts M6
    alone instead of masking it behind an M4 skeleton failure.

    Run standalone:
        pwsh bin/tests/skill-doc-lint.tests.ps1

    Run via runner:
        pwsh bin/test.ps1 ps
#>

BeforeAll {
    $script:LintPath = (Resolve-Path (Join-Path $PSScriptRoot '..' 'skill-doc-lint.ps1')).Path

    # The template skeleton each tier needs for M4, so an M6 boundary case fails
    # for M6 and nothing else.
    $script:TemplateByTier = @{
        decision  = @('Quick Comparison', 'Decision Tree', 'When to Use Each', 'Quick Patterns', 'Reference Map')
        procedure = @('Quick Start', 'When to Use', 'How It Works', 'Patterns', 'Flags', 'Errors & Recovery')
        catalog   = @('Overview', 'Quick Index')
    }
    $script:TemplateSkill = @('Core Loop', 'Key Concepts', 'Command Map', 'Decision Trees', 'Critical Warnings', 'Quick Patterns', 'Reference Map')

    # New-Fixture creates an isolated skills root for one test case.
    function New-Fixture {
        $root = Join-Path ([System.IO.Path]::GetTempPath()) ("b4-skill-lint-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $root -Force | Out-Null
        return $root
    }

    # New-Doc writes one fixture document under $Root: frontmatter, a heading, the
    # tier's template sections, then padding until the file holds exactly $Lines
    # physical lines.
    function New-Doc {
        param(
            [Parameter(Mandatory)][string]$Root,
            [Parameter(Mandatory)][string]$RelativePath,
            [Parameter(Mandatory)][string]$Tier,
            [Parameter(Mandatory)][int]$Lines
        )

        $path = Join-Path $Root $RelativePath
        New-Item -ItemType Directory -Path (Split-Path -Parent $path) -Force | Out-Null

        $name = [System.IO.Path]::GetFileNameWithoutExtension($path)
        $body = @(
            '---'
            "title: ""$name"""
            'description: ""fixture document for the M6 boundary tests""'
            "tier: $Tier"
            '---'
            ''
            "# $name"
        )

        $sections = if ($name -eq 'SKILL') { $script:TemplateSkill } else { $script:TemplateByTier[$Tier] }
        foreach ($section in @($sections)) { $body += @('', "## $section") }
        while ($body.Count -lt $Lines) { $body += 'Padding line for the line-count fixture.' }

        Set-Content -Path $path -Value $body
        return $path
    }

    # Invoke-Lint lints one fixture root and returns its full output (Write-Host
    # included) together with the exit code.
    function Invoke-Lint {
        param(
            [Parameter(Mandatory)][string]$Root,
            [switch]$Strict
        )

        $lintArgs = @{ Path = $Root }
        if ($Strict) { $lintArgs['Strict'] = $true }
        $output = & $script:LintPath @lintArgs *>&1 | Out-String
        return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
    }
}

Describe 'skill-doc-lint M6 line-count rule' {

    It 'accepts a SKILL.md at exactly the 500-line cap' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'ok/SKILL.md' -Tier 'decision' -Lines 500 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Match 'checked 1 files, 0 issue\(s\)'
        $result.Output | Should -Not -Match '\[M6\]'
        $result.ExitCode | Should -Be 0
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'flags a SKILL.md past the cap' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'over/SKILL.md' -Tier 'decision' -Lines 501 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Match '\[M6\] SKILL\.md is 501 lines \(cap 500\)'
        $result.Output | Should -Match 'move 1 line\(s\) into references/'
        $result.ExitCode | Should -Be 1
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'accepts a short decision document (no minimum)' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'short/references/decision-doc.md' -Tier 'decision' -Lines 92 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Not -Match '\[M6\]'
        $result.ExitCode | Should -Be 0
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'reports a decision document just over its target as an advisory' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'long/references/decision-doc.md' -Tier 'decision' -Lines 501 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Match '\[M6\] advisory: decision doc is 501 lines \(target 500, \+1\)'
        $result.Output | Should -Match 'checked 1 files, 0 issue\(s\), 1 advisory\(ies\)'
        $result.ExitCode | Should -Be 0
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'still advises (not fails) at the top of the advisory band' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'long/references/decision-doc.md' -Tier 'decision' -Lines 700 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Match '\[M6\] advisory: decision doc is 700 lines'
        $result.ExitCode | Should -Be 0
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'fails a decision document past the hard ceiling' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'huge/references/decision-doc.md' -Tier 'decision' -Lines 701 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Match '\[M6\] decision doc is 701 lines \(hard ceiling 700, target 500\)'
        $result.ExitCode | Should -Be 1
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'promotes an advisory to a failure under -Strict' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'long/references/decision-doc.md' -Tier 'decision' -Lines 501 | Out-Null

        $result = Invoke-Lint -Root $root -Strict

        $result.Output | Should -Match '\[M6\] advisory: decision doc is 501 lines'
        $result.Output | Should -Match '-Strict: 1 advisory\(ies\) counted as failure\(s\)'
        $result.ExitCode | Should -Be 1
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'accepts a short procedure document (no minimum)' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'short/references/procedure-doc.md' -Tier 'procedure' -Lines 92 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Not -Match '\[M6\]'
        $result.ExitCode | Should -Be 0
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'advises on a procedure document over target and fails past the ceiling' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'long/references/procedure-doc.md' -Tier 'procedure' -Lines 541 | Out-Null
        $advised = Invoke-Lint -Root $root
        $advised.Output | Should -Match '\[M6\] advisory: procedure doc is 541 lines \(target 500, \+41\)'
        $advised.ExitCode | Should -Be 0

        New-Doc -Root $root -RelativePath 'long/references/procedure-doc.md' -Tier 'procedure' -Lines 701 | Out-Null
        $failed = Invoke-Lint -Root $root
        $failed.Output | Should -Match '\[M6\] procedure doc is 701 lines \(hard ceiling 700, target 500\)'
        $failed.ExitCode | Should -Be 1
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'leaves a long catalog document unbounded' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'catalog/references/catalog-doc.md' -Tier 'catalog' -Lines 800 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Not -Match '\[M6\]'
        $result.ExitCode | Should -Be 0
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }
}
