#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Avoid Windows-only env vars ($env:TEMP) — use $env:TMPDIR fallback.
# - Paths: use Join-Path / Split-Path; never bake \ or / as literal.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    Pester tests for browser4-eval-prompt.ps1.

.DESCRIPTION
    Covers the per-run scratch-directory contract that was added when every test
    run gained its own .test-sessions/<run-id>/ subdirectory:

    - a fresh run id is minted when no parent published BROWSER4_TEST_SESSION_DIR
    - an inherited directory is reused, whether absolute or relative
    - the advertised directory is actually created, and lives under .test-sessions/
    - the prompt points the agent at that directory and not at the shared root
    - dev / production mode switching is unaffected

    Run with:
      Invoke-Pester -Path .\browser4-eval-prompt.tests.ps1 -EnableExit
#>

Describe 'browser4-eval-prompt' {

    # Pester 5 runs each It in a fresh scope, so everything the tests need —
    # the dot-sourced script under test, the helper and the bookkeeping — is
    # set up here rather than in the file body.
    BeforeAll {
        $ErrorActionPreference = 'Stop'

        # Globals (not script scope): Pester 5 runs BeforeAll, each It and
        # AfterAll in different scopes, so only a process-wide variable is
        # reliably shared between the helper and the cleanup block.
        $global:B4EvalPromptTestSavedEnv = $env:BROWSER4_TEST_SESSION_DIR
        $global:B4EvalPromptTestCreatedDirs = [System.Collections.Generic.List[string]]::new()

        # Dot-sourcing the prompt script also brings in Get-WorkspaceRoot
        # (dot-sourced from coworker/scripts/config.ps1).
        . (Join-Path $PSScriptRoot 'browser4-eval-prompt.ps1')

        <#
        .SYNOPSIS
            Render the prompt with a controlled BROWSER4_TEST_SESSION_DIR value.
        .DESCRIPTION
            Pass $null to emulate "no parent run published a directory".
        #>
        function Invoke-PromptWithEnv {
            param(
                [AllowNull()]
                [string]$EnvValue,
                [string]$Mode = ''
            )

            if ($null -eq $EnvValue) {
                Remove-Item Env:BROWSER4_TEST_SESSION_DIR -ErrorAction SilentlyContinue
            } else {
                $env:BROWSER4_TEST_SESSION_DIR = $EnvValue
            }

            $prompt = if ($Mode) { New-Browser4EvalPrompt -Mode $Mode } else { New-Browser4EvalPrompt }

            $dir = $env:BROWSER4_TEST_SESSION_DIR
            if ($dir -and -not $global:B4EvalPromptTestCreatedDirs.Contains($dir)) {
                [void]$global:B4EvalPromptTestCreatedDirs.Add($dir)
            }

            return [PSCustomObject]@{ Prompt = $prompt; Dir = $dir }
        }
    }

    AfterAll {
        # Restore the caller's environment and remove only what this suite created.
        if ($null -eq $global:B4EvalPromptTestSavedEnv) {
            Remove-Item Env:BROWSER4_TEST_SESSION_DIR -ErrorAction SilentlyContinue
        } else {
            $env:BROWSER4_TEST_SESSION_DIR = $global:B4EvalPromptTestSavedEnv
        }

        foreach ($dir in $global:B4EvalPromptTestCreatedDirs) {
            if ($dir -and (Test-Path -LiteralPath $dir -PathType Container)) {
                Remove-Item -LiteralPath $dir -Recurse -Force -ErrorAction SilentlyContinue
            }
        }
    }

    Context 'per-run scratch directory' {

        It 'mints a fresh run directory when nothing was inherited' {
            $r = Invoke-PromptWithEnv -EnvValue $null
            $r.Dir | Should -Not -BeNullOrEmpty
            (Split-Path -Leaf $r.Dir) | Should -Match '^\d{8}T\d{6}\d+Z$'
        }

        It 'places the run directory under .test-sessions/' {
            $r = Invoke-PromptWithEnv -EnvValue $null
            $expectedRoot = Join-Path (Get-WorkspaceRoot) '.test-sessions'
            (Split-Path -Parent $r.Dir) | Should -Be $expectedRoot
        }

        It 'creates the directory it advertises' {
            # The prompt asserts the directory already exists, so the function
            # must have materialised it before returning.
            $r = Invoke-PromptWithEnv -EnvValue $null
            (Test-Path -LiteralPath $r.Dir -PathType Container) | Should -BeTrue
        }

        It 'reuses an inherited absolute directory' {
            $inherited = Join-Path (Get-WorkspaceRoot) '.test-sessions\20260101T0000000000000Z'
            $r = Invoke-PromptWithEnv -EnvValue $inherited
            $r.Dir | Should -Be $inherited
        }

        It 'resolves an inherited relative directory against the workspace root' {
            $r = Invoke-PromptWithEnv -EnvValue '.test-sessions/20260101T0000010000000Z'
            (Split-Path -Leaf $r.Dir) | Should -Be '20260101T0000010000000Z'
            [System.IO.Path]::IsPathRooted($r.Dir) | Should -BeTrue
            (Split-Path -Parent $r.Dir) | Should -Be (Join-Path (Get-WorkspaceRoot) '.test-sessions')
        }

        It 'does not reuse the directory of an unrelated run' {
            $first = (Invoke-PromptWithEnv -EnvValue $null).Dir
            $second = (Invoke-PromptWithEnv -EnvValue $null).Dir
            $second | Should -Not -Be $first
        }
    }

    Context 'prompt content' {

        It 'points the agent at the run directory' {
            $r = Invoke-PromptWithEnv -EnvValue $null
            $rel = '.test-sessions/' + (Split-Path -Leaf $r.Dir)
            $r.Prompt | Should -Match ([regex]::Escape($rel))
        }

        It 'warns against writing into the shared .test-sessions/ root' {
            $r = Invoke-PromptWithEnv -EnvValue $null
            $r.Prompt | Should -Match 'This directory belongs to \*this run only\*'
            $r.Prompt | Should -Match 'shared'
        }

        It 'still carries the evaluation structure' {
            $r = Invoke-PromptWithEnv -EnvValue $null
            $r.Prompt | Should -Match 'usability'
            $r.Prompt | Should -Match '# Task'
            ($r.Prompt.Length -gt 1000) | Should -BeTrue
        }

        It 'states a different run directory for a different run' {
            $a = Invoke-PromptWithEnv -EnvValue $null
            $b = Invoke-PromptWithEnv -EnvValue $null
            $relA = Split-Path -Leaf $a.Dir
            $relB = Split-Path -Leaf $b.Dir
            $relA | Should -Not -Be $relB
            $b.Prompt | Should -Not -Match ([regex]::Escape($relA))
        }
    }

    Context 'mode switching' {

        It 'uses the released CLI and remote skill in production mode' {
            $r = Invoke-PromptWithEnv -EnvValue $null -Mode 'production'
            $r.Prompt | Should -Match 'browser4-cli help'
            $r.Prompt | Should -Match ([regex]::Escape('https://browser4.io/SKILL.md'))
        }

        It 'uses the from-source invocation and local skill in dev mode' {
            $r = Invoke-PromptWithEnv -EnvValue $null -Mode 'dev'
            $r.Prompt | Should -Match ([regex]::Escape('cli/browser4-cli'))
            $r.Prompt | Should -Match ([regex]::Escape('skills/browser4-cli/SKILL.md'))
        }
    }
}
