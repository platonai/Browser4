#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use Join-Path / Split-Path; never bake \ or / as literal.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    Unit tests for coworker/scripts/workers/task-logger.ps1.

.DESCRIPTION
    Verifies Write-ConsoleLine argument binding behavior, including
    the draft-panel regression where an empty status spacer line
    previously threw a parameter binding error.

    Run standalone:
        Invoke-Pester -Path .\task-logger.tests.ps1

    Requires Pester 5.x
#>

$ErrorActionPreference = 'Continue'

Describe 'task-logger Write-ConsoleLine' {
    BeforeAll {
        $scriptPath = Join-Path (Join-Path $PSScriptRoot '..\workers') 'task-logger.ps1'
        . $scriptPath
    }

    It 'accepts an empty string message without throwing' {
        { Write-ConsoleLine -Message '' } | Should -Not -Throw
    }

    It 'accepts -NoNewline without throwing' {
        { Write-ConsoleLine -Message 'prefix' -NoNewline } | Should -Not -Throw
    }
}
