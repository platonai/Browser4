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
    M6 (methodology v2.3) applies one cap to every document that loads in full:
    SKILL.md, `decision` and `procedure` documents are <= 500 physical lines,
    with NO minimum, and `catalog` documents are unbounded.  These tests pin the
    boundaries down against throwaway fixture trees, so the rule can be changed
    without hand-checking the linter against the real skills/ tree:

      500 lines -> conformant      501 lines -> flagged
       92 lines -> conformant (no minimum)
      catalog 800 lines -> conformant (unbounded)

    Every "conformant" case is paired with a failing case that goes through the
    same fixture and capture path, so a fixture that silently stops being
    scanned fails the suite instead of passing it.

    Run standalone:
        pwsh bin/tests/skill-doc-lint.tests.ps1

    Run via runner:
        pwsh bin/test.ps1 ps
#>

BeforeAll {
    $script:LintPath = (Resolve-Path (Join-Path $PSScriptRoot '..' 'skill-doc-lint.ps1')).Path

    # New-Fixture creates an isolated skills root for one test case.
    function New-Fixture {
        $root = Join-Path ([System.IO.Path]::GetTempPath()) ("b4-skill-lint-" + [guid]::NewGuid().ToString('N'))
        New-Item -ItemType Directory -Path $root -Force | Out-Null
        return $root
    }

    # New-Doc writes one fixture document under $Root: frontmatter, a heading,
    # then padding until the file holds exactly $Lines physical lines.
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
        while ($body.Count -lt $Lines) { $body += 'Padding line for the line-count fixture.' }

        Set-Content -Path $path -Value $body
        return $path
    }

    # Invoke-Lint lints one fixture root and returns its full output (Write-Host
    # included) together with the exit code.
    function Invoke-Lint {
        param([Parameter(Mandatory)][string]$Root)

        $output = & $script:LintPath -Path $Root *>&1 | Out-String
        return [pscustomobject]@{ Output = $output; ExitCode = $LASTEXITCODE }
    }
}

Describe 'skill-doc-lint M6 line-count rule' {

    It 'accepts a SKILL.md at exactly the 500-line cap' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'ok/SKILL.md' -Tier 'decision' -Lines 500 | Out-Null

        $result = Invoke-Lint -Root $root

        # The exit code is not asserted here: a bare fixture manifest fails other
        # checks (M4's section skeleton). What matters is that the file was scanned
        # and that M6 stayed silent at exactly the cap.
        $result.Output | Should -Match 'checked 1 files'
        $result.Output | Should -Not -Match '\[M6\]'
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'flags a SKILL.md past the cap' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'over/SKILL.md' -Tier 'decision' -Lines 501 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Match '\[M6\] SKILL\.md is 501 lines \(cap 500\)'
        $result.ExitCode | Should -Be 1
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'accepts a short decision document (no minimum)' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'short/references/decision-doc.md' -Tier 'decision' -Lines 92 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Not -Match '\[M6\]'
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'flags a decision document past the cap' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'long/references/decision-doc.md' -Tier 'decision' -Lines 501 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Match '\[M6\] decision doc is 501 lines \(cap 500\)'
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'accepts a short procedure document (no minimum)' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'short/references/procedure-doc.md' -Tier 'procedure' -Lines 92 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Not -Match '\[M6\]'
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'flags a procedure document past the cap' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'long/references/procedure-doc.md' -Tier 'procedure' -Lines 501 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Match '\[M6\] procedure doc is 501 lines \(cap 500\)'
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }

    It 'leaves a long catalog document unbounded' {
        $root = New-Fixture
        New-Doc -Root $root -RelativePath 'catalog/references/catalog-doc.md' -Tier 'catalog' -Lines 800 | Out-Null

        $result = Invoke-Lint -Root $root

        $result.Output | Should -Not -Match '\[M6\]'
        Remove-Item -Path $root -Recurse -Force -ErrorAction SilentlyContinue
    }
}
