#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Console-encoding tests: every entry point emits UTF-8, not the OEM code page.

.DESCRIPTION
    A redirected stream starts on the Windows OEM code page (CP936 on a Chinese
    host) while the relay in bin/test.ps1, the per-run logs, CI and the capture
    used for issue tracking all read UTF-8.  Under CP936 the decoration these
    scripts print degrades into line noise:

        ·  —  │   ->  "??"   (A1 A4 / A1 AA / A9 A6, read as UTF-8)
        ═         ->  "?T"   (A8 54 — the 0x54 is a literal 'T')

    which is how a heartbeat/tail block became "?T?T last 10 lines ?T?T" in a
    real scenario log.

    Each test starts a child process on CP936 and reads the child's *raw bytes*
    through an OS-level redirection (Start-Process -RedirectStandardOutput), so
    a green run proves the bytes on the wire are UTF-8 rather than merely that
    two PowerShell processes agree with each other.

    Run standalone:
        pwsh bin/tests/console-encoding.tests.ps1

    Run via runner:
        pwsh bin/test.ps1 ps
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Resolve paths
# -------------------------------------------------------------------
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = (Resolve-Path (Join-Path $ScriptDir '..\..')).Path
$TestUtilsModule = Join-Path $ScriptDir '..\..\browser4-tests\tests-production\test-utils.psm1'

# -------------------------------------------------------------------
# Self-contained counters.
#
# This suite deliberately does NOT import browser4-tests/tests-production/
# test-utils.psm1: that module's session helpers clear port 18182, kill stale
# Browser4 processes and run an AI analysis on failure.  An encoding test must
# stay hermetic and fast, and it still runs under `bin/test.ps1 ps` because the
# runner only needs an exit code.
# -------------------------------------------------------------------
$script:TestsPassed = 0
$script:TestsFailed = 0
$script:TempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("b4-console-encoding-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $script:TempRoot -Force | Out-Null

# -------------------------------------------------------------------
# Assertions
# -------------------------------------------------------------------
function Assert-ContainsString {
    param([string]$Label, [string]$Haystack, [string]$Needle)
    if ($Haystack -match [regex]::Escape($Needle)) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
        $script:TestsPassed++
    } else {
        Write-Host "    ❌ $Label — string does not contain '$Needle'" -ForegroundColor Red
        $script:TestsFailed++
    }
}

function Assert-NotContainsString {
    param([string]$Label, [string]$Haystack, [string]$Needle)
    if ($Haystack -notmatch [regex]::Escape($Needle)) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
        $script:TestsPassed++
    } else {
        Write-Host "    ❌ $Label — string unexpectedly contains '$Needle'" -ForegroundColor Red
        $script:TestsFailed++
    }
}

# -------------------------------------------------------------------
# Helper: run a child pwsh and return its stdout as UTF-8 text.
#
# The redirection is done by Start-Process (an OS-level handle), NOT by
# PowerShell's ">" operator — the latter would decode and re-encode through
# this process, hiding exactly the byte-level problem under test.
# -------------------------------------------------------------------
function Invoke-ChildPwshRaw {
    param(
        [Parameter(Mandatory = $true)][string]$Body,
        [int]$TimeoutSec = 180
    )

    $outFile = Join-Path $script:TempRoot ("out-" + [guid]::NewGuid().ToString('N') + '.txt')
    $errFile = Join-Path $script:TempRoot ("err-" + [guid]::NewGuid().ToString('N') + '.txt')

    $proc = Start-Process -FilePath 'pwsh' `
        -ArgumentList @('-NoProfile', '-ExecutionPolicy', 'Bypass', '-Command', $Body) `
        -RedirectStandardOutput $outFile -RedirectStandardError $errFile `
        -NoNewWindow -PassThru

    if (-not $proc.WaitForExit($TimeoutSec * 1000)) {
        try { $proc.Kill() } catch { }
        throw "child pwsh timed out after ${TimeoutSec}s"
    }

    $bytes = if (Test-Path -LiteralPath $outFile) { [System.IO.File]::ReadAllBytes($outFile) } else { @() }
    return @{
        Text     = [System.Text.Encoding]::UTF8.GetString($bytes)
        Stderr   = if (Test-Path -LiteralPath $errFile) { [System.IO.File]::ReadAllText($errFile) } else { '' }
        ExitCode = $proc.ExitCode
    }
}

# Force the OEM code page at child start-up: without this the child can inherit
# UTF-8 from this (already pinned) process and the test would prove nothing.
$forceOem = @'
if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    try { [Console]::OutputEncoding = [System.Text.Encoding]::GetEncoding(936) } catch { }
}
'@

$replacement = [string][char]0xFFFD
$boxArtifact = $replacement + 'T'   # the "?T" signature: CP936 A8 54 read as UTF-8

# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ bin/common/Util.ps1: dot-sourcing pins the console to UTF-8 ━━━" -ForegroundColor Cyan

$utilPath = (Join-Path $RepoRoot 'bin\common\Util.ps1').Replace("'", "''")
$body = $forceOem + @"

. '$utilPath'
[Console]::WriteLine('  ══ last 10 lines ══')
[Console]::WriteLine('  · claude --dangerously-skip-permissions — running (0m 30s elapsed, heartbeat #1)')
[Console]::WriteLine('  │ [claude-code:unrecognized_model] {"model":"deepseek-v4-flash[1m]"}')
[Console]::WriteLine('  ══════════════════')
"@
$utilResult = Invoke-ChildPwshRaw -Body $body
Assert-ContainsString -Label 'Util.ps1: box-drawing rule survives' -Haystack $utilResult.Text -Needle '══ last 10 lines ══'
Assert-ContainsString -Label 'Util.ps1: middot and em dash survive' -Haystack $utilResult.Text -Needle '· claude --dangerously-skip-permissions — running'
Assert-ContainsString -Label 'Util.ps1: tail bar survives' -Haystack $utilResult.Text -Needle '│ [claude-code:unrecognized_model]'
Assert-NotContainsString -Label 'Util.ps1: no replacement characters' -Haystack $utilResult.Text -Needle $replacement
Assert-NotContainsString -Label 'Util.ps1: no CP936 box-drawing artifact' -Haystack $utilResult.Text -Needle $boxArtifact

# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ maintenance / build helpers inherit the pin through Util.ps1 ━━━" -ForegroundColor Cyan

$maintenanceUtil = (Join-Path $RepoRoot 'bin\maintenance\common\MaintenanceUtil.ps1').Replace("'", "''")
$body = $forceOem + @"

. '$maintenanceUtil'
[Console]::WriteLine('  ━━━ check-log-sizes: 135 log file(s) ━━━')
[Console]::WriteLine('  · 12 file(s) over the warning threshold — 3.4 MB')
"@
$maintenanceResult = Invoke-ChildPwshRaw -Body $body
Assert-ContainsString -Label 'MaintenanceUtil.ps1: heavy rule survives' -Haystack $maintenanceResult.Text -Needle '━━━ check-log-sizes: 135 log file(s) ━━━'
Assert-ContainsString -Label 'MaintenanceUtil.ps1: middot and em dash survive' -Haystack $maintenanceResult.Text -Needle '· 12 file(s) over the warning threshold — 3.4 MB'
Assert-NotContainsString -Label 'MaintenanceUtil.ps1: no replacement characters' -Haystack $maintenanceResult.Text -Needle $replacement

# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ tests-production: importing test-utils.psm1 pins the suite ━━━" -ForegroundColor Cyan

$modulePath = $TestUtilsModule.Replace("'", "''")
$body = $forceOem + @"

Import-Module '$modulePath' -Force
[Console]::WriteLine('  ══ cli-basics: 8 command(s), 8 passed ══')
[Console]::WriteLine('  · verify-attach-close-tabs — running (2m 10s elapsed)')
"@
$moduleResult = Invoke-ChildPwshRaw -Body $body
Assert-ContainsString -Label 'test-utils.psm1: box-drawing rule survives' -Haystack $moduleResult.Text -Needle '══ cli-basics: 8 command(s), 8 passed ══'
Assert-ContainsString -Label 'test-utils.psm1: middot and em dash survive' -Haystack $moduleResult.Text -Needle '· verify-attach-close-tabs — running'
Assert-NotContainsString -Label 'test-utils.psm1: no replacement characters' -Haystack $moduleResult.Text -Needle $replacement

# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ bin/test.ps1: its own banners and trees are UTF-8 ━━━" -ForegroundColor Cyan

$testPs1 = (Join-Path $RepoRoot 'bin\test.ps1').Replace("'", "''")
# A directory that really holds task .md files — the tree view lists those, and
# an empty listing would make the glyph assertion vacuous.
$treeRoot = (Join-Path $RepoRoot 'browser4-tests\real-world-scenarios\tasks\mock-site').Replace("'", "''")
$body = $forceOem + @"

& '$testPs1' rws dir --tree '$treeRoot'
"@
$runnerResult = Invoke-ChildPwshRaw -Body $body
Assert-ContainsString -Label 'bin/test.ps1: tree glyphs survive' -Haystack $runnerResult.Text -Needle '├──'
Assert-NotContainsString -Label 'bin/test.ps1: no replacement characters' -Haystack $runnerResult.Text -Needle $replacement
Assert-NotContainsString -Label 'bin/test.ps1: no CP936 box-drawing artifact' -Haystack $runnerResult.Text -Needle $boxArtifact

# ═══════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════
Remove-Item -LiteralPath $script:TempRoot -Recurse -Force -ErrorAction SilentlyContinue

Write-Host ''
$total = $script:TestsPassed + $script:TestsFailed
Write-Host "Results: $($script:TestsPassed) passed, $($script:TestsFailed) failed ($total total)" `
    -ForegroundColor $(if ($script:TestsFailed -eq 0) { 'Green' } else { 'Red' })

if ($script:TestsFailed -gt 0) { exit 1 }
exit 0
