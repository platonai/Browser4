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
    Measurement: does a named session keep its dedicated browser profile across a
    Browser4 backend restart?

.DESCRIPTION
    A named session (`-s <name>`) is documented to bind a dedicated profile
    directory
        <app-data>/context/groups/named/PULSAR_CHROME/cx.<sessionUuid>
    so that reopening the session restores the same cookies / login state.

    The backend resolves a display name to a session UUID in memory, and the CLI
    re-uses a stored session id only while the backend still reports that session
    as active.  After a backend restart the mapping is gone, so reopening by name
    may mint a NEW UUID and therefore bind a NEW (empty) profile directory.

    This script measures which behaviour the installed CLI actually exhibits:

      Phase A  open a named session, set a persistent marker cookie,
               record the session id and the named profile directories
      Phase B  restart the backend (`browser4-cli stop`; the next command
               auto-starts a fresh one)
      Phase C  reopen the same named session and read the marker cookie back

    Verdict
      STABLE        same session id AND marker cookie still present
      REBOUND       the session identity was not preserved (new session id)
                    and/or the marker cookie was lost
      INCONCLUSIVE  the restart phase was skipped

    REBOUND at session level does not by itself prove that a NEW profile
    directory was bound: a non-persistent (temporary/incognito-like) browser
    context can also drop a cookie.  The script reports the session id, the
    cookie, and the profile directories as separate signals.  The on-disk
    profile layout has differed between versions, so the directory signal is
    best effort (both `context/groups/named/**` and `browser/chrome/**` are
    searched for `cx.*` directories).

    The restart phase runs `browser4-cli stop`, which also sweeps orphaned
    browser processes — every browser Browser4 launched is closed with it.
    It is therefore opt-in: pass -RestartBackend, or confirm the interactive
    prompt.  In a non-interactive runner the phase is skipped unless
    -RestartBackend is passed.

    Requires a globally-installed browser4-cli (honours $env:BROWSER4_CLI_BIN).
    Never depends on the repository, git, or local build outputs.

.PARAMETER SessionName
    Named session to use (default: b4-profile-repro).

.PARAMETER Url
    Page used to give the cookie a real origin (default: https://example.com).

.PARAMETER AppDataDir
    Browser4 application data directory (default: $HOME/.browser4, or
    $env:USERPROFILE\.browser4 on Windows).  Only used to report the named
    profile directories.

.PARAMETER RestartBackend
    Perform the destructive restart phase without prompting.

.PARAMETER KeepSession
    Leave the named session open (default: close it at the end).

.EXAMPLE
    pwsh verify-named-session-profile.ps1 -RestartBackend

.EXAMPLE
    $env:BROWSER4_CLI_BIN = 'D:\dev\browser4-cli.exe'
    pwsh verify-named-session-profile.ps1
#>

[CmdletBinding()]
param(
    [string] $SessionName = 'b4-profile-repro',
    [string] $Url = 'https://example.com',
    [string] $AppDataDir = '',
    [switch] $RestartBackend,
    [switch] $KeepSession
)

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
# -SkipPortCleanup: this is a measurement against the user's live setup, so it
# must not kill whatever currently holds port 8182.  The only destructive step
# (backend restart) is the opt-in Phase B below.
Start-TestSession -Name 'verify-named-session-profile' -SkipPortCleanup

if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    $null = & chcp 65001 2>$null
}
[Console]::OutputEncoding = [Text.Encoding]::UTF8

Write-TestHeader -Name 'verify-named-session-profile'

# -------------------------------------------------------------------
# Content-level assertions (exit-code checks are done by Invoke-TrackedCli)
# -------------------------------------------------------------------
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
# Helpers
# -------------------------------------------------------------------
function Get-AppDataDir {
    if ($AppDataDir) { return $AppDataDir }
    # NOTE: do not name this `$home` — PowerShell is case-insensitive and `$HOME`
    # is a read-only automatic variable, so the assignment would fail.
    $userHome = if ($env:HOME) { $env:HOME } elseif ($env:USERPROFILE) { $env:USERPROFILE } else { '' }
    if (-not $userHome) { return '' }
    return (Join-Path $userHome '.browser4')
}

function Get-NamedProfileDirs {
    param([string] $Root)
    if (-not $Root -or -not (Test-Path $Root)) { return @() }

    # Best effort: the on-disk layout of session profiles has differed between
    # versions, so look in both known places rather than assuming one:
    #   current sources → <app-data>/context/groups/named/PULSAR_CHROME/cx.<uuid>
    #   older bundles   → <app-data>/browser/chrome/<group>/PULSAR_CHROME (no cx.*)
    $found = New-Object System.Collections.Generic.List[string]

    $namedRoot = Join-Path $Root 'context/groups/named/PULSAR_CHROME'
    if (Test-Path $namedRoot) {
        Get-ChildItem -Path $namedRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -like 'cx.*' } |
            ForEach-Object { $found.Add("context/groups/named/PULSAR_CHROME/$($_.Name)") }
    }

    $chromeRoot = Join-Path $Root 'browser/chrome'
    if (Test-Path $chromeRoot) {
        Get-ChildItem -Path $chromeRoot -Directory -ErrorAction SilentlyContinue |
            ForEach-Object {
                $group = $_.Name
                Get-ChildItem -Path (Join-Path $_.FullName 'PULSAR_CHROME') -Directory -ErrorAction SilentlyContinue |
                    Where-Object { $_.Name -like 'cx.*' } |
                    ForEach-Object { $found.Add("browser/chrome/$group/PULSAR_CHROME/$($_.Name)") }
            }
    }

    return @($found | Sort-Object)
}

function Get-NamedSessionId {
    param([string] $Name)

    $raw = (Invoke-TrackedCli -Arguments @('--json', 'list') -Label 'list --json' -PassThruOnly | Out-String)

    # Preferred: parse the JSON envelope ({command, output:{sessions:[...]}, status})
    try {
        $obj = $raw | ConvertFrom-Json -ErrorAction Stop
        $sessions = @()
        if ($obj.PSObject.Properties.Name -contains 'output' -and $obj.output) {
            if ($obj.output.PSObject.Properties.Name -contains 'sessions') { $sessions = @($obj.output.sessions) }
        } elseif ($obj.PSObject.Properties.Name -contains 'sessions') {
            $sessions = @($obj.sessions)
        }
        $hit = $sessions | Where-Object { $_.name -eq $Name } | Select-Object -First 1
        if ($hit -and $hit.session_id) { return [string]$hit.session_id }
    } catch {
        # Older/other output shapes — fall through to the textual scan.
    }

    # Fallback: scan the rendered table for a UUID on a row naming this session.
    $uuid = '[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}'
    foreach ($line in ($raw -split "`r?`n")) {
        if ($line -match [regex]::Escape($Name) -and $line -match "($uuid)") { return $Matches[1] }
    }
    return ''
}

function Test-CookieMarker {
    param([string] $Session, [string] $MarkerValue)

    $hit = $false
    $listing = (Invoke-TrackedCli -Arguments @('-s', $Session, 'cookie-list') -Label 'cookie-list' -PassThruOnly | Out-String)
    if ($listing -match [regex]::Escape($MarkerValue)) { $hit = $true }

    if (-not $hit) {
        # Secondary signal: the marker is not HttpOnly, so it must appear in
        # document.cookie when the profile was restored.
        $docCookie = (Invoke-TrackedCli -Arguments @('-s', $Session, 'eval', 'document.cookie') -Label 'eval document.cookie' -PassThruOnly | Out-String)
        if ($docCookie -match [regex]::Escape($MarkerValue)) { $hit = $true }
    }
    return $hit
}

$appData   = Get-AppDataDir
$markerVal = "b4repro-$(New-Guid)"
$markerKey = 'b4_repro_marker'

Write-Host "`nConfiguration" -ForegroundColor Cyan
Write-Host "  session   : $SessionName" -ForegroundColor DarkGray
Write-Host "  url       : $Url" -ForegroundColor DarkGray
Write-Host "  app data  : $appData" -ForegroundColor DarkGray
Write-Host "  marker    : $markerKey=$markerVal" -ForegroundColor DarkGray

# -------------------------------------------------------------------
# Phase A — open named session, plant marker cookie, snapshot profile dirs
# -------------------------------------------------------------------
Write-Host "`n━━━ Phase A: create named session + marker cookie ━━━" -ForegroundColor Cyan

$dirsBefore = Get-NamedProfileDirs -Root $appData
Write-Host "  named profile dirs before: $(if ($dirsBefore.Count) { $dirsBefore -join ', ' } else { '(none)' })" -ForegroundColor DarkGray

$openBefore = Invoke-TrackedCli -Arguments @('-s', $SessionName, 'open', '--headless', $Url) `
    -Label "open named session ($SessionName)" -TimeoutSecs 300
$idBefore = Get-NamedSessionId -Name $SessionName
Write-Host "  session id: $(if ($idBefore) { $idBefore } else { '(not resolved)' })" -ForegroundColor DarkGray

Invoke-TrackedCli -Arguments @('-s', $SessionName, 'cookie-set', $markerKey, $markerVal, '--expires', '7d') `
    -Label 'cookie-set marker' | Out-Null
$markerBefore = Test-CookieMarker -Session $SessionName -MarkerValue $markerVal

Assert-Output -Label 'named session created and visible in `list`' -Condition { $idBefore -ne '' }
Assert-Output -Label 'marker cookie set (visible before restart)' -Condition { $markerBefore }

# -------------------------------------------------------------------
# Phase B — restart the backend (opt-in; destructive)
# -------------------------------------------------------------------
Write-Host "`n━━━ Phase B: restart backend ━━━" -ForegroundColor Cyan

$isNonInteractive = ($env:CI -eq 'true') -or ([Environment]::GetCommandLineArgs() -contains '-NonInteractive')
if (-not $RestartBackend -and -not $isNonInteractive) {
    Write-Host "  This phase stops the Browser4 backend and every session it serves." -ForegroundColor Yellow
    $answer = Read-Host "  Restart the backend now? [y/N]"
    if ($answer -match '^(y|yes)$') { $RestartBackend = $true }
}

$restartPerformed = $false
if ($RestartBackend) {
    Write-Host "  ⚠ 'browser4-cli stop' also sweeps orphaned browser processes it" -ForegroundColor Yellow
    Write-Host "    considers stale, so any browser it launched is closed too." -ForegroundColor Yellow
    Invoke-TrackedCli -Arguments @('stop') -Label 'stop backend' -PassThruOnly | Out-Null
    Start-Sleep -Seconds 5

    # The next CLI invocation auto-starts a fresh backend (JVM boot, ~10 s;
    # a first-ever source build can take minutes, hence the long timeout).
    $openAfter = Invoke-TrackedCli -Arguments @('-s', $SessionName, 'open', '--headless', $Url) `
        -Label 'reopen named session (auto-starts backend)' -TimeoutSecs 600
    $restartPerformed = $true

    $idAfter = Get-NamedSessionId -Name $SessionName
    Write-Host "  session id after restart: $(if ($idAfter) { $idAfter } else { '(not resolved)' })" -ForegroundColor DarkGray
} else {
    Write-Host "  ⏭  Skipped — pass -RestartBackend to run this phase." -ForegroundColor Yellow
    Write-Host "     (Also skippable when no restart is needed; the rest of the script reports Phase A only.)" -ForegroundColor DarkGray
}

# -------------------------------------------------------------------
# Phase C — read the marker back
# -------------------------------------------------------------------
$dirsAfter  = Get-NamedProfileDirs -Root $appData
$markerAfter = $false

if ($restartPerformed) {
    Write-Host "`n━━━ Phase C: verify cookie + profile binding after restart ━━━" -ForegroundColor Cyan
    $markerAfter = Test-CookieMarker -Session $SessionName -MarkerValue $markerVal
    Write-Host "  named profile dirs after : $(if ($dirsAfter.Count) { $dirsAfter -join ', ' } else { '(none)' })" -ForegroundColor DarkGray
} else {
    $idAfter = $idBefore
}

# -------------------------------------------------------------------
# Verdict
# -------------------------------------------------------------------
Write-Host "`n━━━ Verdict ━━━" -ForegroundColor Cyan

$newDirs = @($dirsAfter | Where-Object { $_ -notin $dirsBefore })
$idStable = ($idBefore -ne '') -and ($idBefore -eq $idAfter)

Write-Host "  session id before : $(if ($idBefore) { $idBefore } else { '(none)' })" -ForegroundColor DarkGray
Write-Host "  session id after  : $(if (-not $restartPerformed) { '(not checked)' } elseif ($idAfter) { $idAfter } else { '(none)' })" -ForegroundColor DarkGray
Write-Host "  marker after      : $(if (-not $restartPerformed) { '(not checked)' } elseif ($markerAfter) { 'present' } else { 'missing' })" -ForegroundColor DarkGray
Write-Host "  new profile dirs  : $(if ($newDirs.Count) { $newDirs -join ', ' } else { '(none)' })" -ForegroundColor DarkGray

$verdict = 'INCONCLUSIVE'
if (-not $restartPerformed) {
    $verdict = 'INCONCLUSIVE'
} elseif ($idStable -and $markerAfter) {
    $verdict = 'STABLE'
} else {
    $verdict = 'REBOUND'
}

switch ($verdict) {
    'STABLE' {
        Write-Host "  ✅ STABLE — the named session kept its session id and its profile" -ForegroundColor Green
        Write-Host "     (cookies survived the backend restart)." -ForegroundColor Green
    }
    'REBOUND' {
        Write-Host "  ⚠  REBOUND — the named session was not preserved:" -ForegroundColor Yellow
        if (-not $idStable) { Write-Host "     - the session id changed (name → UUID is not preserved)" -ForegroundColor Yellow }
        if (-not $markerAfter) { Write-Host "     - the persistent marker cookie was not restored" -ForegroundColor Yellow }
        Write-Host "     Note: the cookie loss can come from a new profile directory OR from a" -ForegroundColor Yellow
        Write-Host "     non-persistent browser context — check the directory signal above." -ForegroundColor Yellow
        Write-Host "     Login state is therefore NOT guaranteed across a backend restart;" -ForegroundColor Yellow
        Write-Host "     persist auth with 'state-save' / 'state-load' instead." -ForegroundColor Yellow
    }
    default {
        Write-Host "  ⏭  INCONCLUSIVE — restart phase skipped; re-run with -RestartBackend." -ForegroundColor Yellow
    }
}

Write-Host "`n  Reference : skills/browser4-cli/references/browser-modes.md (§7)" -ForegroundColor DarkGray

# -------------------------------------------------------------------
# Cleanup
# -------------------------------------------------------------------
if (-not $KeepSession) {
    Write-Host "`n━━━ Cleanup ━━━" -ForegroundColor Cyan
    Invoke-TrackedCli -Arguments @('-s', $SessionName, 'close') -Label 'close session (best effort)' -PassThruOnly 2>$null | Out-Null
}

# -------------------------------------------------------------------
# Final report
# -------------------------------------------------------------------
$exitCode = Finish-TestSession -ExtraCopilotPrompt @"
These are failures from a named-session profile-binding measurement script.
The script measures whether a named session (-s <name>) keeps its dedicated
profile (context/groups/named/PULSAR_CHROME/cx.<sessionUuid>) across a Browser4
backend restart.  A REBOUND verdict is an expected finding, not a script bug.
"@

if ($script:ContentFailures -gt 0) {
    Write-Host "  ⚠ $($script:ContentFailures) content-based assertion(s) also failed" -ForegroundColor Red
    if ($exitCode -eq 0) { $exitCode = 1 }
}
exit $exitCode
