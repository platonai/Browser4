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
    Measurement: what does `close` do to tabs on a CDP-attached session?

.DESCRIPTION
    Browser4 documents `close` on an attached session as "the browser keeps
    running".  It is less clear what happens to the TAB the session was driving —
    the documentation used to say the tabs remain untouched, while the driver
    shutdown path closes the tab it holds.

    This script measures the real behaviour against a live CDP endpoint, using
    the DevTools HTTP API directly for all observations (independent of the CLI):

      Phase A  baseline: GET /json/version + /json/list
      Phase B  attach a named session, record the page it binds to
      Phase C  close the session
      Phase D  re-probe /json/version (process alive?) and /json/list (tab diff)

    Verdict
      PROCESS SURVIVED / PROCESS KILLED
      TABS CLOSED        — at least one tab (usually the bound one) disappeared
      TABS UNTOUCHED     — the tab set is unchanged

    This is a measurement, not an assertion: any of those outcomes is reported
    with exit code 0.  Only precondition failures (no CDP endpoint, attach
    failure) fail the run.

    CDP only.  For an extension-attached session the same question can be checked
    manually: `attach --extension`, note the tabs with `tab-list`, then `close`
    and compare (the relay removes the tabs it drove via chrome.tabs.remove).

    Requires a globally-installed browser4-cli (honours $env:BROWSER4_CLI_BIN)
    plus a browser started with remote debugging enabled, e.g.
        chrome --remote-debugging-port=9222
    Never depends on the repository, git, or local build outputs.

.PARAMETER Cdp
    CDP endpoint: http://localhost:9222, localhost:9222, or a bare port (9222).
    When omitted, ports 9222–9333 are probed for a local CDP responder.

.PARAMETER SessionName
    Named session used for the attach (default: b4-attach-repro).

.PARAMETER KeepSession
    Leave the (now disconnected) session state in place.

.EXAMPLE
    pwsh verify-attach-close-tabs.ps1 -Cdp 9222

.EXAMPLE
    # Start the target browser first:
    #   chrome --remote-debugging-port=9222 https://example.com
    pwsh verify-attach-close-tabs.ps1
#>

[CmdletBinding()]
param(
    [string] $Cdp = '',
    [string] $SessionName = 'b4-attach-repro',
    [switch] $KeepSession
)

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
# -SkipPortCleanup: this measures an EXTERNAL browser over CDP; it must not kill
# a Browser4 server (or anything else) the user is currently running.
Start-TestSession -Name 'verify-attach-close-tabs' -SkipPortCleanup

if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    $null = & chcp 65001 2>$null
}
[Console]::OutputEncoding = [Text.Encoding]::UTF8

Write-TestHeader -Name 'verify-attach-close-tabs'

$script:ContentFailures = 0
function Assert-Output {
    param([string] $Label, [scriptblock] $Condition)
    if (& $Condition) {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    } else {
        $script:ContentFailures++
        Write-Host "    ❌ $Label" -ForegroundColor Red
    }
}

# -------------------------------------------------------------------
# Resolve CLI binary
# -------------------------------------------------------------------
$CliBin = Get-CliBin
if (-not $CliBin -or (-not (Test-Path $CliBin) -and -not (Get-Command $CliBin -ErrorAction SilentlyContinue))) {
    Write-Host "ERROR: browser4-cli not found on PATH." -ForegroundColor Red
    Write-Host "Install it with: npm i -g browser4-cli && browser4-cli install" -ForegroundColor Yellow
    Write-Host "Or set \$env:BROWSER4_CLI_BIN to the binary." -ForegroundColor Yellow
    exit 1
}
Write-Host "Using CLI : $CliBin" -ForegroundColor DarkGray

# -------------------------------------------------------------------
# CDP helpers (independent observation channel)
# -------------------------------------------------------------------
function Resolve-CdpBase {
    param([string] $Value)

    if ($Value) {
        if ($Value -match '^\d+$') { return "http://127.0.0.1:$Value" }
        if ($Value -match '^[^/]+:\d+$') { return "http://$Value" }
        if ($Value -match '^https?://') { return $Value.TrimEnd('/') }
        return ''
    }

    for ($port = 9222; $port -le 9333; $port++) {
        try {
            $null = Invoke-RestMethod -Uri "http://127.0.0.1:$port/json/version" -TimeoutSec 1 -ErrorAction Stop
            return "http://127.0.0.1:$port"
        } catch { }
    }
    return ''
}

function Get-CdpVersion {
    param([string] $Base)
    try { return Invoke-RestMethod -Uri "$Base/json/version" -TimeoutSec 3 -ErrorAction Stop } catch { return $null }
}

function Get-CdpPages {
    param([string] $Base)
    try {
        $targets = @(Invoke-RestMethod -Uri "$Base/json/list" -TimeoutSec 3 -ErrorAction Stop)
        return @($targets | Where-Object { $_.type -eq 'page' })
    } catch {
        return @()
    }
}

function Format-PageSet {
    param($Pages)
    if (-not $Pages -or @($Pages).Count -eq 0) { return '(no page targets)' }
    return (@($Pages) | ForEach-Object { "    - [$($_.id)] $($_.url)" }) -join "`n"
}

# -------------------------------------------------------------------
# Phase A — baseline
# -------------------------------------------------------------------
Write-Host "`n━━━ Phase A: locate the CDP endpoint and take a baseline ━━━" -ForegroundColor Cyan

$base = Resolve-CdpBase -Value $Cdp
if (-not $base) {
    Write-Host "ERROR: no CDP endpoint found." -ForegroundColor Red
    Write-Host "Start the target browser with remote debugging enabled, e.g.:" -ForegroundColor Yellow
    Write-Host "  chrome --remote-debugging-port=9222 https://example.com" -ForegroundColor Yellow
    Write-Host "then re-run with -Cdp http://localhost:9222 (probed ports: 9222-9333)." -ForegroundColor Yellow
    exit 1
}

$versionBefore = Get-CdpVersion -Base $base
if (-not $versionBefore) {
    Write-Host "ERROR: $base does not answer /json/version." -ForegroundColor Red
    exit 1
}
$browserLabel = "$($versionBefore.Browser) @ $base"
Write-Host "  endpoint : $base" -ForegroundColor DarkGray
Write-Host "  browser  : $browserLabel" -ForegroundColor DarkGray

$pagesBefore = Get-CdpPages -Base $base
Write-Host "  tabs before attach ($(@($pagesBefore).Count)):" -ForegroundColor DarkGray
Write-Host (Format-PageSet -Pages $pagesBefore) -ForegroundColor DarkGray

Assert-Output -Label 'CDP endpoint answers /json/version' -Condition { $null -ne $versionBefore }
Assert-Output -Label 'at least one page target exists' -Condition { @($pagesBefore).Count -gt 0 }

# -------------------------------------------------------------------
# Phase B — attach a named session and record the bound page
# -------------------------------------------------------------------
Write-Host "`n━━━ Phase B: attach a named session ━━━" -ForegroundColor Cyan

$attachOut = Invoke-TrackedCli -Arguments @('-s', $SessionName, 'attach', '--cdp', $base) `
    -Label "attach --cdp $base" -TimeoutSecs 120
$attachText = ($attachOut | Out-String).Trim()
if ($attachText) { Write-Host "  $attachText" -ForegroundColor DarkGray }

$boundRaw = (Invoke-TrackedCli -Arguments @('-s', $SessionName, 'eval', 'document.location.href') `
    -Label 'eval document.location.href' -PassThruOnly | Out-String).Trim()
$boundUrl = $boundRaw.Trim('"').Trim()
Write-Host "  bound page: $(if ($boundUrl) { $boundUrl } else { '(not reported)' })" -ForegroundColor DarkGray

$pagesAttached = Get-CdpPages -Base $base
Write-Host "  tabs while attached ($(@($pagesAttached).Count)):" -ForegroundColor DarkGray
Write-Host (Format-PageSet -Pages $pagesAttached) -ForegroundColor DarkGray

Assert-Output -Label 'attach bound a page tab' -Condition { $boundUrl -ne '' }

# -------------------------------------------------------------------
# Phase C — close the session
# -------------------------------------------------------------------
Write-Host "`n━━━ Phase C: close the attached session ━━━" -ForegroundColor Cyan
Invoke-TrackedCli -Arguments @('-s', $SessionName, 'close') -Label 'close attached session' -TimeoutSecs 60 | Out-Null
Start-Sleep -Seconds 3

# -------------------------------------------------------------------
# Phase D — observe the result
# -------------------------------------------------------------------
Write-Host "`n━━━ Phase D: measure tabs + process after close ━━━" -ForegroundColor Cyan

$versionAfter = Get-CdpVersion -Base $base
$processAlive = ($null -ne $versionAfter)
$pagesAfter = Get-CdpPages -Base $base

$idsBefore = @($pagesAttached | ForEach-Object { $_.id })
$gone = @($pagesAttached | Where-Object { $_.id -notin @($pagesAfter | ForEach-Object { $_.id }) })

$boundGone = $false
if ($boundUrl) {
    $boundGone = -not (@($pagesAfter) | Where-Object {
        $_.url -eq $boundUrl -or $_.url -like "$boundUrl*" -or $boundUrl -like "$($_.url)*"
    })
}

Write-Host "  browser alive after close : $processAlive" -ForegroundColor DarkGray
Write-Host "  tabs after close ($(@($pagesAfter).Count)):" -ForegroundColor DarkGray
Write-Host (Format-PageSet -Pages $pagesAfter) -ForegroundColor DarkGray
Write-Host "  tab(s) that disappeared   : $(if (@($gone).Count) { @($gone).Count } else { 0 })" -ForegroundColor DarkGray

# -------------------------------------------------------------------
# Verdict
# -------------------------------------------------------------------
Write-Host "`n━━━ Verdict ━━━" -ForegroundColor Cyan

if (-not $processAlive) {
    Write-Host "  ❌ PROCESS KILLED — the browser no longer answers /json/version." -ForegroundColor Red
    Write-Host "     Documented behaviour is that an attached browser keeps running." -ForegroundColor Red
} else {
    Write-Host "  ✅ PROCESS SURVIVED — the browser still answers /json/version." -ForegroundColor Green
}

if (@($gone).Count -gt 0) {
    Write-Host "  ✅ TABS CLOSED — close removed $(@($gone).Count) tab(s):" -ForegroundColor Green
    foreach ($t in @($gone)) { Write-Host "       - $($t.url)" -ForegroundColor Green }
    if ($boundGone) {
        Write-Host "     The tab the session was bound to is among them — this matches" -ForegroundColor Green
        Write-Host "     the corrected close semantics documented in attach.md." -ForegroundColor Green
    } else {
        Write-Host "     The bound page ('$boundUrl') is still open; a different tab was closed." -ForegroundColor Yellow
    }
} elseif ($processAlive) {
    Write-Host "  ⚠  TABS UNTOUCHED — the page set is unchanged after close." -ForegroundColor Yellow
    Write-Host "     If you are relying on the old 'tabs remain untouched' wording, this run" -ForegroundColor Yellow
    Write-Host "     confirms it; the driver-shutdown path may still close tabs in other setups." -ForegroundColor Yellow
}

Write-Host "`n  Reference : skills/browser4-cli/references/attach.md (Close vs Disconnect)" -ForegroundColor DarkGray

# -------------------------------------------------------------------
# Cleanup
# -------------------------------------------------------------------
if (-not $KeepSession) {
    # The session was already closed above; this is a best-effort state cleanup.
    Invoke-TrackedCli -Arguments @('--quiet', '-s', $SessionName, 'status') -Label 'state check (best effort)' -PassThruOnly 2>$null | Out-Null
}

# -------------------------------------------------------------------
# Final report
# -------------------------------------------------------------------
$exitCode = Finish-TestSession -ExtraCopilotPrompt @"
These are failures from an attach/close tab-behaviour measurement script.
It attaches to an existing browser over CDP, closes the session, and observes
via /json/version and /json/list whether the browser survived and which tabs
were closed.  A 'TABS CLOSED' result is an expected finding, not a script bug.
"@

if ($script:ContentFailures -gt 0) {
    Write-Host "  ⚠ $($script:ContentFailures) content-based assertion(s) also failed" -ForegroundColor Red
    if ($exitCode -eq 0) { $exitCode = 1 }
}
exit $exitCode
