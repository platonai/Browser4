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
    Comprehensive session lifecycle stress test.

.DESCRIPTION
    Repeatedly cycles through open / goto / close / kill-all across 10 pages
    on 3 different websites, performing 1–2 random interactive actions after
    each page load and verifying that session state remains correct.

    Covers:
      - Session auto-creation on open / goto
      - Session reuse across navigations (goto, go-back)
      - Session cleanup on close
      - Full reset on kill-all
      - Snapshot accuracy after each navigation
      - Random interactions (scroll, press, hover, eval) without side-effects

    All CLI invocations are logged to a per-run directory under browser4-tests/tests-production/logs/.
    Failures are reported with log paths.  If `copilot` is on PATH, it is
    invoked automatically to analyse any failures.

.PARAMETER Iterations
    Number of full test cycles (default: 3).

.PARAMETER Seed
    RNG seed for reproducible runs (default: random).
#>
param(
    [int] $Iterations = 3,
    [int] $Seed = (Get-Random),
    [string] $Locale = '',
    [string] $Phase = '',            # e.g. "C" or "A,C,E" — empty = run all
    [int] $TimeoutSeconds = 0        # 0 = unlimited; >0 skips final iterations when budget exhausted
)

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'stress-session'

# Switch the console code page to UTF-8 (65001) — Windows only
if ($IsWindows -or $env:OS -eq 'Windows_NT') {
    $null = & chcp 65001
}
[Console]::OutputEncoding = [Text.Encoding]::UTF8
[Console]::InputEncoding  = [Text.Encoding]::UTF8

Write-TestHeader -Name 'stress-session'

# -------------------------------------------------------------------
# Soft-failure tracking globals.
# Test failures are recorded rather than thrown, so the suite continues
# through all cases and reports a full summary at the end.
# -------------------------------------------------------------------
$global:TestPassed       = 0
$global:TestFailed       = 0
$global:FailureMessages  = [System.Collections.ArrayList]::new()
$global:SkippedChecks    = [System.Collections.ArrayList]::new()
$global:LastCliSucceeded = $true    # set by Invoke-Cli; assertions may skip on $false

# Set by Invoke-CliToleratingExit: the next command's non-zero exit is an
# expected outcome of the scenario (not a failure).
$script:__TolerateExitCode = $false

# -------------------------------------------------------------------
# CLI helper — invokes browser4-cli with real-time output streaming
# and a per-command timeout that prevents indefinite hangs.
# Failures are recorded (soft) rather than thrown.
# -------------------------------------------------------------------

# Resolve the CLI binary once (honours $env:BROWSER4_CLI_BIN).
$script:__SessionCliBin = if ($env:BROWSER4_CLI_BIN) {
    $env:BROWSER4_CLI_BIN
} else {
    $cmds = @(Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue)
    if ($cmds.Count -gt 0) { $cmds[0].Source } else { 'browser4-cli' }
}

function Invoke-Cli {
    $desc = ($args -join ' ')
    Write-Host "       [$(Get-Date -Format 'HH:mm:ss')] cli $desc ..." -ForegroundColor DarkGray
    $sw = [Diagnostics.Stopwatch]::StartNew()

    # Per-command timeout.  open / goto wait for page load (≤ 2 min);
    # kill-all tears down the JVM plus every browser process, which this
    # scenario itself documents at ~26 s on an idle machine — and the released
    # binary measured 32.3 s on a loaded one, so the old flat 30 s deadline
    # killed a cleanup that was still making progress.  It gets the same
    # 2-minute budget as a page load.
    $cmdName = $args[0]
    $timeoutSecs = if ($cmdName -in @('open', 'goto')) { 120 }
                   elseif ($cmdName -in @('install', 'upgrade')) { 900 }
                   elseif ($cmdName -eq 'kill-all') { 120 }
                   else { 30 }

    # Resolve the actual executable — on Windows the CLI may be a .cmd
    # shim; we want the native .exe so we can launch it via Start-Process.
    $cliExe = $script:__SessionCliBin
    if (($IsWindows -or $env:OS -eq 'Windows_NT') -and $cliExe -match '\.cmd$') {
        $cmdContent = Get-Content -LiteralPath $cliExe -TotalCount 3 -ErrorAction SilentlyContinue
        # NOTE: no `break` inside ForEach-Object — it terminates the whole script.
        $found = $null
        foreach ($line in $cmdContent) {
            if ($line -match '"([^"]+\.exe)"') { $found = $Matches[1]; break }
        }
        # npm shims use `%~dp0`; expand it relative to the shim.
        if ($found -and $found -match '%~dp0') {
            $shimDir = Split-Path -Parent $cliExe
            $found = $found -replace '%~dp0', ($shimDir.TrimEnd('\', '/') + [IO.Path]::DirectorySeparatorChar)
        }
        if ($found -and (Test-Path $found)) {
            $cliExe = $found
        }
    }

    # Use Start-Process with file redirection instead of PowerShell
    # pipeline (`& cmd | ForEach-Object`).  On Windows the pipeline
    # approach hangs indefinitely because the Java server child inherits
    # the stdout pipe handle via CreateProcess.  File redirection avoids
    # this entirely: our read handles are never shared, WaitForExit is
    # alertable (Ctrl+C works), and we can still display output in
    # real-time by tailing the temp files.
    $out = [System.Collections.Generic.List[string]]::new()
    $timeoutOccurred = $false
    $procExitCode = 0

    try {
        $tempDir = if ($env:TEMP) { $env:TEMP } elseif ($env:TMPDIR) { $env:TMPDIR } else { [System.IO.Path]::GetTempPath() }
        $tmpOut = Join-Path $tempDir "b4_session_stdout_${pid}_$(Get-Random).txt"
        $tmpErr = Join-Path $tempDir "b4_session_stderr_${pid}_$(Get-Random).txt"
        Remove-Item $tmpOut, $tmpErr -Force -ErrorAction SilentlyContinue

        $proc = Start-Process `
            -FilePath $cliExe `
            -ArgumentList (($args | ForEach-Object { ConvertTo-WindowsCmdArg $_ }) -join ' ') `
            -NoNewWindow `
            -PassThru `
            -RedirectStandardOutput $tmpOut `
            -RedirectStandardError $tmpErr

        $stdoutLinesRead = 0
        $stderrLinesRead = 0
        $pollIntervalMs = 500
        $timeoutMs = $timeoutSecs * 1000

        # Poll for process exit, displaying new output lines as they
        # appear in the temp files.  Reading files avoids the anonymous-
        # pipe inheritance deadlock; WaitForExit with a short timeout
        # keeps the thread alertable so Ctrl+C is processed promptly.
        while (-not $proc.HasExited) {
            # Read new stdout lines from temp file
            if (Test-Path $tmpOut) {
                try {
                    $allOut = Get-Content -Path $tmpOut -ErrorAction SilentlyContinue
                    $newOut = if ($allOut) {
                        $allOut | Select-Object -Skip $stdoutLinesRead
                    } else { @() }
                    foreach ($line in $newOut) {
                        if ($line -ne $null) {
                            $out.Add($line)
                            Write-Host $line
                        }
                    }
                    $stdoutLinesRead += @($newOut).Count
                } catch {}
            }
            # Read new stderr lines from temp file
            if (Test-Path $tmpErr) {
                try {
                    $allErr = Get-Content -Path $tmpErr -ErrorAction SilentlyContinue
                    $newErr = if ($allErr) {
                        $allErr | Select-Object -Skip $stderrLinesRead
                    } else { @() }
                    foreach ($line in $newErr) {
                        if ($line -ne $null) {
                            $tagged = "[stderr] $line"
                            $out.Add($tagged)
                            Write-Host $tagged -ForegroundColor DarkYellow
                        }
                    }
                    $stderrLinesRead += @($newErr).Count
                } catch {}
            }

            # Check timeout
            if ($sw.Elapsed.TotalMilliseconds -gt $timeoutMs) {
                $timeoutOccurred = $true
                Write-Host "       TIMEOUT after ${timeoutSecs}s — killing process tree" -ForegroundColor Red
                # taskkill /T kills the tree natively.  The previous
                # Stop-Process + Get-CimInstance child sweep ran a full WMI
                # enumeration *inside the timeout path*, so it could hang for
                # minutes and the timeout was never reported at all.
                try { Stop-ProcessTree -Id $proc.Id } catch {}
                if (-not $proc.HasExited) { $proc.Kill() }
                break
            }

            $null = $proc.WaitForExit($pollIntervalMs)
        }

        # Drain any final lines that arrived after the last poll and
        # before process exit.  File reads never block.
        try {
            if (Test-Path $tmpOut) {
                $allOut = Get-Content -Path $tmpOut -ErrorAction SilentlyContinue
                $newOut = if ($allOut) {
                    $allOut | Select-Object -Skip $stdoutLinesRead
                } else { @() }
                foreach ($line in $newOut) {
                    if ($line -ne $null) { $out.Add($line); Write-Host $line }
                }
            }
        } catch {}
        try {
            if (Test-Path $tmpErr) {
                $allErr = Get-Content -Path $tmpErr -ErrorAction SilentlyContinue
                $newErr = if ($allErr) {
                    $allErr | Select-Object -Skip $stderrLinesRead
                } else { @() }
                foreach ($line in $newErr) {
                    if ($line -ne $null) {
                        $tagged = "[stderr] $line"
                        $out.Add($tagged)
                        Write-Host $tagged -ForegroundColor DarkYellow
                    }
                }
            }
        } catch {}

        $procExitCode = $proc.ExitCode
        if ($timeoutOccurred) { $procExitCode = -99 }
    } catch {
        $msg = "CLI invocation failed: $($_.Exception.Message)"
        Write-Host "       $msg" -ForegroundColor Red
        $out.Add($msg)
        $procExitCode = -99
    } finally {
        # Clean up temp files regardless of outcome
        if (Test-Path variable:tmpOut) { Remove-Item $tmpOut -Force -ErrorAction SilentlyContinue }
        if (Test-Path variable:tmpErr) { Remove-Item $tmpErr -Force -ErrorAction SilentlyContinue }
        $sw.Stop()
    }

    $exitCode = $procExitCode
    $label = "cli $desc"
    $toleratedExit = $script:__TolerateExitCode -and -not $timeoutOccurred -and $exitCode -ne 0

    if ($timeoutOccurred) {
        $msg = "CLI command timed out after ${timeoutSecs}s: $desc"
        Write-Host "       FAIL: $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("       FAIL: $msg")
        # The CLI timeout is registered as a *command* failure below (with an
        # explicit timeout marker) — not also as an assertion failure.  Counting
        # it in both places made one timeout read as "1 assertion failed" AND
        # "1 CLI command failed" in the same run.
        $global:LastCliSucceeded = $false
        # Persist the reason in the log itself: without this the log showed the
        # impossible pair "EXIT CODE -99 / EXPECTED -1" and nothing about the
        # deadline that was actually exceeded.
        $out.Add("TIMEOUT after ${timeoutSecs}s — process tree killed")
        $null = Register-CliResult -Label $label -ExitCode $exitCode -OutputLines ($out.ToArray()) `
            -Elapsed $sw.Elapsed -ExpectedExitCode 0 -TimedOut -TimeoutSecs $timeoutSecs
        if ($sw.Elapsed.TotalSeconds -ge 5) {
            Write-Host "       took $([math]::Round($sw.Elapsed.TotalSeconds, 1))s" -ForegroundColor DarkGray
        }
        return $out
    }

    $null = Register-CliResult -Label $label -ExitCode $exitCode -OutputLines ($out.ToArray()) `
        -Elapsed $sw.Elapsed -ExpectedExitCode $(if ($toleratedExit) { $exitCode } else { 0 })

    if ($toleratedExit) {
        # Registered against its own exit code, so test-utils does not count it
        # as a failure; the caller asserts the expected outcome itself.
        Write-Host "       (exit=$exitCode tolerated — expected outcome of this scenario)" -ForegroundColor DarkYellow
        $global:LastCliSucceeded = $true
    } elseif ($exitCode -ne 0) {
        $msg = "CLI command failed (exit=$exitCode): $desc"
        Write-Host "       FAIL: $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("       FAIL: $msg`nOutput:`n$($out -join "`n")")
        $global:TestFailed++
        $global:LastCliSucceeded = $false
    } else {
        $global:LastCliSucceeded = $true
    }
    if ($sw.Elapsed.TotalSeconds -ge 5) {
        Write-Host "       took $([math]::Round($sw.Elapsed.TotalSeconds, 1))s" -ForegroundColor DarkGray
    }
    $out
}

# Invoke a CLI command whose non-zero exit is an expected outcome of the
# scenario, so it is not counted as a failure.  Used by Phase C for
# `go-forward`: a runtime that reports "no forward entry" (instead of silently
# doing nothing) exits non-zero, and that is a browser-level condition the
# caller asserts itself.
function Invoke-CliToleratingExit {
    param([Parameter(ValueFromRemainingArguments = $true)] [object[]] $CliArgs)
    $script:__TolerateExitCode = $true
    try {
        Invoke-Cli @CliArgs
    } finally {
        $script:__TolerateExitCode = $false
    }
}

# -------------------------------------------------------------------
# Helpers
# -------------------------------------------------------------------
filter join-output { ($_ -join ' ') }

# Return only session data rows (exclude header, separator, blank lines,
# footer text, and empty-state messages).  Session rows are the pipe-delimited
# table lines that contain a session ID (UUID).  Non-row lines include:
#   (default) = unnamed session auto-created by goto/open without -s <name>.
#   No active browser sessions.
filter session-data-rows {
    $_ | Where-Object {
        $_ -match '\S' -and
        $_ -notmatch '^\s*-+\s*' -and
        $_ -notmatch '^Name\b' -and
        $_ -notmatch '^Note:' -and
        $_ -notmatch 'No active browser sessions' -and
        # Table data rows contain pipe separators and a UUID-like session ID;
        # footer lines like "(default) = …" do not.
        $_ -match '\|'
    }
}

# Assert that a condition holds; record failure on mismatch (soft — never throws).
function assert-ok {
    param(
        [string] $Label,
        [scriptblock] $Condition,
        [string] $Detail = ''
    )
    try {
        if (-not (& $Condition)) {
            $msg = "FAIL: $Label"
            if ($Detail) { $msg += "`n      $Detail" }
            Write-Host "    ❌ $Label" -ForegroundColor Red
            $null = $global:FailureMessages.Add("    $msg")
            $global:TestFailed++
            return $false
        }
    } catch {
        $msg = "FAIL: $Label`n      Exception: $($_.Exception.Message)"
        Write-Host "    ❌ $Label" -ForegroundColor Red
        $null = $global:FailureMessages.Add("    $msg")
        $global:TestFailed++
        return $false
    }
    Write-Host "    ✓ $Label" -ForegroundColor Green
    $global:TestPassed++
    return $true
}

# -------------------------------------------------------------------
# Locale-appropriate page set (10 entries from the URL store).
# Each entry has: url, site, name, keyword.
# -------------------------------------------------------------------
$resolvedTestLocale = Get-TestLocale -Locale $Locale
$pages = @(Get-TestUrlSet -Locale $resolvedTestLocale | ForEach-Object {
    @{ url = $_.url; site = $_.site; name = $_.name; keyword = $_.keyword }
})

# -------------------------------------------------------------------
# Random interaction primitives (safe — never navigate away)
# -------------------------------------------------------------------
$rng = [Random]::new($Seed)

function Invoke-RandomInteractions {
    param([int]$Count = 1)
    1..$Count | ForEach-Object {
        $act = $rng.Next(1, 9)
        try {
            switch ($act) {
                1 { Write-Host "      🖱  mousewheel down 300-600" -ForegroundColor DarkGray
                    Invoke-Cli mousewheel 0 ($rng.Next(300, 601)) }
                2 { Write-Host "      🖱  mousewheel down 500-1000" -ForegroundColor DarkGray
                    Invoke-Cli mousewheel 0 ($rng.Next(500, 1001)) }
                3 { Write-Host "      ⌨  press PageDown" -ForegroundColor DarkGray
                    Invoke-Cli press PageDown }
                4 { Write-Host "      ⌨  press ArrowDown x2" -ForegroundColor DarkGray
                    Invoke-Cli press ArrowDown; Invoke-Cli press ArrowDown }
                5 { Write-Host "      ⌨  press End" -ForegroundColor DarkGray
                    Invoke-Cli press End }
                6 { $px = $rng.Next(300, 801)
                    Write-Host "      📜 eval scrollBy(0,$px)" -ForegroundColor DarkGray
                    Invoke-Cli eval "window.scrollBy(0,$px)" }
                7 { Write-Host "      🎯 hover body" -ForegroundColor DarkGray
                    Invoke-Cli hover body }
                8 { Write-Host "      ⌨  press Escape" -ForegroundColor DarkGray
                    Invoke-Cli press Escape }
            }
        } catch {
            Write-Host "      ⚠ interaction failed (non-fatal): $($_.Exception.Message)" -ForegroundColor DarkYellow
        }
        Start-Sleep -Milliseconds ($rng.Next(400, 901))
    }
}

# -------------------------------------------------------------------
# State verification helpers
# -------------------------------------------------------------------
function Get-SessionDataRows {
    $out = Invoke-Cli list
    return ($out | session-data-rows)
}

function Assert-SessionCount {
    param([int]$Expected, [string]$Context)
    # When the last CLI command failed, dependent list/snapshot checks are
    # skipped so we don't flood the report with cascading failures.  The skip
    # is recorded: an unevaluated assertion must never look like a pass.
    if (-not $global:LastCliSucceeded) {
        Write-Host "    ⏭ session count check SKIPPED (previous CLI failure)  ($Context)" -ForegroundColor DarkYellow
        $null = $global:SkippedChecks.Add("session count == $Expected ($Context) — not evaluated: previous CLI command failed")
        return $false
    }
    try {
        $rows = Get-SessionDataRows
        $label = "session count == $Expected  ($Context)"
        if ($rows.Count -ne $Expected) {
            $all = Invoke-Cli list
            $msg = "FAIL: $label`n      found $($rows.Count) row(s):`n$($all -join "`n")"
            Write-Host "    ❌ $label" -ForegroundColor Red
            $null = $global:FailureMessages.Add("    $msg")
            $global:TestFailed++
            return $false
        }
        Write-Host "    ✓ $label" -ForegroundColor Green
        $global:TestPassed++
        return $true
    } catch {
        $msg = "FAIL: session count check ($Context)`n      Exception: $($_.Exception.Message)"
        Write-Host "    ❌ $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("    $msg")
        $global:TestFailed++
        return $false
    }
}

function Assert-SnapshotContains {
    param([string]$Keyword, [string]$Context)
    if (-not $global:LastCliSucceeded) {
        Write-Host "    ⏭ snapshot check SKIPPED (previous CLI failure)  ($Context)" -ForegroundColor DarkYellow
        $null = $global:SkippedChecks.Add("snapshot contains '$Keyword' ($Context) — not evaluated: previous CLI command failed")
        return $false
    }
    try {
        $snap = Invoke-Cli snapshot
        # Join lines into a single string so -like works as a boolean test.
        $text = $snap -join ' '
        if ($text -notlike "*$Keyword*") {
            $msg = "FAIL: snapshot contains '$Keyword'  ($Context)`n      snapshot output:`n$($snap -join "`n")"
            Write-Host "    ❌ $msg" -ForegroundColor Red
            $null = $global:FailureMessages.Add("    $msg")
            $global:TestFailed++
            return $false
        }
        Write-Host "    ✓ snapshot contains '$Keyword'  ($Context)" -ForegroundColor Green
        $global:TestPassed++
        return $true
    } catch {
        $msg = "FAIL: snapshot check ($Context)`n      Exception: $($_.Exception.Message)"
        Write-Host "    ❌ $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("    $msg")
        $global:TestFailed++
        return $false
    }
}

# Record a check that could not be evaluated because of a browser-level
# condition — never a product defect, never a silent pass: it is printed,
# collected in $global:SkippedChecks and repeated in the final summary.
function Skip-Check {
    param([string]$Label, [string]$Reason)
    Write-Host "    ⏭ SKIP: $Label" -ForegroundColor DarkYellow
    Write-Host "         reason: $Reason" -ForegroundColor DarkGray
    $null = $global:SkippedChecks.Add("$Label — $Reason")
    return $false
}

# Current page URL as reported by the page itself (`eval`, not a cached driver
# field).  Returns $null when the evaluation failed, so callers can tell
# "no session / stale context" apart from "the URL did not change".
#
# `eval` prints the evaluated value itself and has no `--result-only` option
# (that one belongs to `htmlsnapshot query`); passing it made the CLI exit 2
# with "unknown option", so this helper returned $null for every page.
function Get-CurrentHref {
    $out = @(Invoke-Cli eval "location.href")
    if (-not $global:LastCliSucceeded) { return $null }
    # The value is a plain stdout line; stderr diagnostics are collected with a
    # "[stderr] " prefix and must not be mistaken for the URL.  Prefer a line
    # that actually looks like a URL (a page href always carries a scheme —
    # http:, https:, file:, about:, data:, …) and fall back to the last plain
    # line, so an unexpected placeholder line cannot win.
    $fallback = $null
    for ($i = $out.Count - 1; $i -ge 0; $i--) {
        $line = "$($out[$i])".Trim()
        if (-not $line) { continue }
        if ($line.StartsWith('[stderr]')) { continue }
        if ($line -match '^[A-Za-z][A-Za-z0-9+.\-]*:') { return $line }
        if ($null -eq $fallback) { $fallback = $line }
    }
    return $fallback
}

# Probe whether the browser still has an entry to traverse forward to.
#
# The only reliable way to ask is to drive the traversal from the page itself:
# `history.forward()` is the same history API the browser's own Forward button
# uses, so a URL that does not move proves there is no forward entry left.
# This separates a browser-level condition (the page replaced its history entry
# and Chrome pruned everything after it — observed with live pages that are
# reloaded instead of restored during a history traversal) from a CLI that
# reported success without navigating although the browser could have.
function Test-BrowserCanGoForward {
    $before = Get-CurrentHref
    $null = Invoke-Cli eval "history.forward(); 'probe'"
    Start-Sleep -Seconds 2
    $after = Get-CurrentHref
    if ($null -eq $before -or $null -eq $after) {
        return [PSCustomObject]@{ Known = $false; Moved = $false; Url = $after }
    }
    return [PSCustomObject]@{ Known = $true; Moved = ($after -ne $before); Url = $after }
}

# -------------------------------------------------------------------
# Clean start
# -------------------------------------------------------------------
Write-Host "`n=== SESSION STRESS TEST ===" -ForegroundColor Cyan
Write-Host "  Iterations : $Iterations" -ForegroundColor Cyan
Write-Host "  Seed       : $Seed" -ForegroundColor Cyan
Write-Host "  Locale     : $resolvedTestLocale" -ForegroundColor Cyan
Write-Host "  Pages      : $($pages.Count) across $(@($pages | Group-Object site).Count) sites" -ForegroundColor Cyan
Write-Host "  Log dir    : $(Get-LogDir)" -ForegroundColor Cyan
Write-Host ""

Write-Host "── Ensuring clean slate (close any lingering sessions) ──" -ForegroundColor DarkYellow
$null = Invoke-Cli close
$null = Invoke-Cli close-all
# Let the server finish async session teardown before we create new sessions.
Start-Sleep -Seconds 3

# -------------------------------------------------------------------
# Main test loop
# -------------------------------------------------------------------
$suiteTimer = [Diagnostics.Stopwatch]::StartNew()

for ($iter = 1; $iter -le $Iterations; $iter++) {
    Write-Host "`n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
    Write-Host "  ITERATION $iter / $Iterations  (seed=$Seed)" -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

    $prevPassed = $global:TestPassed
    $prevFailed = $global:TestFailed

    # ── Per-iteration time budget ─────────────────────────────────
    # When a global timeout is set, estimate the remaining budget and
    # skip the final iteration(s) if there isn't enough time.  Uses
    # the first iteration's elapsed time as a predictor for subsequent
    # ones, plus a 15 % safety margin for variance.
    if ($TimeoutSeconds -gt 0 -and $iter -gt 1 -and $iterElapsedPrev) {
        $remaining = $TimeoutSeconds - $suiteTimer.Elapsed.TotalSeconds
        $needed = $iterElapsedPrev * 1.15
        if ($remaining -lt $needed) {
            Write-Host "`n  ⏭ Skipping remaining $($Iterations - $iter + 1) iteration(s) — not enough time budget" -ForegroundColor DarkYellow
            Write-Host "     Remaining: $([math]::Round($remaining, 0))s  Needed (est): $([math]::Round($needed, 0))s  Budget: ${TimeoutSeconds}s" -ForegroundColor DarkGray
            break
        }
    }

    # Shuffle page order each iteration for varied coverage.
    $shuffled = $pages | Sort-Object { $rng.Next() }

    # ──────────────────────────────────────────────────────────────
    # Phase A: Open each of 3 pages (different sites), interact,
    #          verify session carries over via goto, then close.
    # ──────────────────────────────────────────────────────────────
    if (-not $Phase -or $Phase -match 'A') {
    Write-Host "`n  ── Phase A: open → interact → snapshot → goto → close ──" -ForegroundColor DarkYellow

    # Pick one page from each of the first 3 distinct sites (locale-independent).
    $phaseASites = @($shuffled | Group-Object site | Select-Object -First 3 -ExpandProperty Name)
    $phaseAPages = @($phaseASites | ForEach-Object {
        $siteName = $_
        $shuffled | Where-Object { $_.site -eq $siteName } | Select-Object -First 1
    })

    # A1: open first page
    $p1 = $phaseAPages[0]
    Write-Host "`n  A1. open → $($p1.name) ($($p1.url))" -ForegroundColor White
    Invoke-Cli open $p1.url
    Start-Sleep -Seconds 3
    Invoke-RandomInteractions -Count ($rng.Next(1, 3))
    Assert-SnapshotContains -Keyword $p1.keyword -Context $p1.name
    Assert-SessionCount -Expected 1 -Context "after open $($p1.name)"

    # A2: goto second page (same session)
    $p2 = $phaseAPages[1]
    Write-Host "`n  A2. goto → $($p2.name) ($($p2.url))" -ForegroundColor White
    Invoke-Cli goto $p2.url
    Start-Sleep -Seconds 3
    Invoke-RandomInteractions -Count ($rng.Next(1, 3))
    Assert-SnapshotContains -Keyword $p2.keyword -Context $p2.name
    Assert-SessionCount -Expected 1 -Context "after goto $($p2.name)  (same session)"

    # A3: goto third page (still same session)
    $p3 = $phaseAPages[2]
    Write-Host "`n  A3. goto → $($p3.name) ($($p3.url))" -ForegroundColor White
    Invoke-Cli goto $p3.url
    Start-Sleep -Seconds 3
    Invoke-RandomInteractions -Count ($rng.Next(1, 3))
    Assert-SnapshotContains -Keyword $p3.keyword -Context $p3.name
    Assert-SessionCount -Expected 1 -Context "after goto $($p3.name)  (same session)"

    # A4: close session
    Write-Host "`n  A4. close session" -ForegroundColor White
    Invoke-Cli close
    Assert-SessionCount -Expected 0 -Context "after close"

    # A5: goto on a closed session should auto-create a new one
    $pExtra = $shuffled | Where-Object { $_.site -ne $p3.site } | Select-Object -First 1
    Write-Host "`n  A5. goto (auto-create) → $($pExtra.name) ($($pExtra.url))" -ForegroundColor White
    Invoke-Cli goto $pExtra.url
    Start-Sleep -Seconds 3
    Invoke-RandomInteractions -Count 1
    Assert-SnapshotContains -Keyword $pExtra.keyword -Context "$($pExtra.name) (auto-created)"
    Assert-SessionCount -Expected 1 -Context "after goto auto-create"
    Invoke-Cli close
    Assert-SessionCount -Expected 0 -Context "after close (cleanup A5)"

    }

    # ──────────────────────────────────────────────────────────────
    # Phase B: Rapid open/close cycles across all 10 pages.
    # ──────────────────────────────────────────────────────────────
    if (-not $Phase -or $Phase -match 'B') {
    Write-Host "`n  ── Phase B: rapid open/close across all 10 pages ──" -ForegroundColor DarkYellow

    $cycle = 0
    foreach ($p in $shuffled) {
        $cycle++
        Write-Host "`n  B${cycle}. open → $($p.name) ($($p.url))  [$($p.site)]" -ForegroundColor White
        Invoke-Cli open $p.url
        Start-Sleep -Seconds 3
        Invoke-RandomInteractions -Count ($rng.Next(1, 3))
        Assert-SnapshotContains -Keyword $p.keyword -Context $p.name
        Assert-SessionCount -Expected 1 -Context "after open $($p.name)"

        Write-Host "       close" -ForegroundColor DarkGray
        Invoke-Cli close
        Assert-SessionCount -Expected 0 -Context "after close $($p.name)"
    }

    ($shuffled.Count * 2)
    }

    # ──────────────────────────────────────────────────────────────
    # Phase C: Mixed navigation — go-back, go-forward, reload
    # ──────────────────────────────────────────────────────────────
    if (-not $Phase -or $Phase -match 'C') {
    Write-Host "`n  ── Phase C: go-back / go-forward / reload ──" -ForegroundColor DarkYellow

    # Pick one page from each of the first 3 distinct sites (locale-independent).
    $phaseCSites = @($shuffled | Group-Object site | Select-Object -First 3 -ExpandProperty Name)
    $c1 = $shuffled | Where-Object { $_.site -eq $phaseCSites[0] } | Select-Object -First 1
    $c2 = $shuffled | Where-Object { $_.site -eq $phaseCSites[1] } | Select-Object -First 1
    $c3 = $shuffled | Where-Object { $_.site -eq $phaseCSites[2] } | Select-Object -First 1

    Write-Host "`n  C1. open $($c1.name)" -ForegroundColor White
    Invoke-Cli open $c1.url
    Start-Sleep -Seconds 3
    Invoke-RandomInteractions -Count 1
    Assert-SnapshotContains -Keyword $c1.keyword -Context $c1.name

    Write-Host "  C2. goto $($c2.name)" -ForegroundColor White
    Invoke-Cli goto $c2.url
    Start-Sleep -Seconds 3
    Invoke-RandomInteractions -Count 1
    Assert-SnapshotContains -Keyword $c2.keyword -Context $c2.name

    Write-Host "  C3. goto $($c3.name)" -ForegroundColor White
    Invoke-Cli goto $c3.url
    Start-Sleep -Seconds 3
    Invoke-RandomInteractions -Count 1
    Assert-SnapshotContains -Keyword $c3.keyword -Context $c3.name

    Write-Host "  C4. go-back → should return to $($c2.name)" -ForegroundColor White
    Invoke-Cli go-back
    Start-Sleep -Seconds 2
    Assert-SnapshotContains -Keyword $c2.keyword -Context "after go-back ($($c2.name))"
    Assert-SessionCount -Expected 1 -Context "after go-back"

    Write-Host "  C5. go-back → should return to $($c1.name)" -ForegroundColor White
    Invoke-Cli go-back
    Start-Sleep -Seconds 2
    Assert-SnapshotContains -Keyword $c1.keyword -Context "after go-back ($($c1.name))"
    Assert-SessionCount -Expected 1 -Context "after go-back (2)"

    Write-Host "  C6. go-forward → should return to $($c2.name)" -ForegroundColor White
    # A second traversal is not always available: when the browser reloads
    # (instead of restores) the document of the entry it traverses to, Chrome
    # replaces that entry and prunes every entry after it, so after two
    # go-backs a live page can legitimately leave nothing to go forward to
    # (observed with the zh page set: c1 = www.163.com).  The strong contract
    # — go-forward returns to c2 — is therefore asserted while a forward entry
    # exists, verified by asking the page's own history API; a browser that
    # kept no forward entry is reported as a skip, while a CLI that stayed put
    # although the browser *could* go forward is a real failure.
    $c6Status = 'unknown'      # 'forwarded' | 'no-entry' | 'cli-error' | 'cli-noop'
    $hrefBefore = Get-CurrentHref
    if ($null -eq $hrefBefore) {
        $c6Status = 'cli-error'
        $msg = "FAIL: go-forward could not be evaluated — no page URL before the command (see the CLI failure above)"
        Write-Host "    ❌ $msg" -ForegroundColor Red
        $null = $global:FailureMessages.Add("    $msg")
        $global:TestFailed++
    } else {
        # A runtime that reports the condition exits non-zero; tolerate that
        # exit (the check reports it explicitly below) instead of failing the run.
        $fwdOut = Invoke-CliToleratingExit go-forward
        Start-Sleep -Seconds 2
        $fwdText = $fwdOut | join-output
        $hrefAfter = Get-CurrentHref

        if ($fwdText -match 'no (next|forward|such)[\w -]*entry') {
            $c6Status = 'no-entry'
            $null = Skip-Check "go-forward returns to $($c2.name)" `
                "the browser kept no forward entry after two go-backs (go-forward reported: no next entry)"
        } elseif ($null -eq $hrefAfter) {
            $c6Status = 'cli-error'
            $msg = "FAIL: go-forward could not be evaluated — no page URL after the command (see the CLI failure above)"
            Write-Host "    ❌ $msg" -ForegroundColor Red
            $null = $global:FailureMessages.Add("    $msg")
            $global:TestFailed++
        } elseif ($hrefAfter -ne $hrefBefore) {
            $c6Status = 'forwarded'
            $null = Assert-SnapshotContains -Keyword $c2.keyword -Context "after go-forward ($($c2.name))"
        } else {
            $probe = Test-BrowserCanGoForward
            if (-not $probe.Known) {
                $c6Status = 'cli-error'
                $msg = "FAIL: go-forward left the page on $hrefBefore and the browser could not be probed"
                Write-Host "    ❌ $msg" -ForegroundColor Red
                $null = $global:FailureMessages.Add("    $msg")
                $global:TestFailed++
            } elseif ($probe.Moved) {
                $c6Status = 'cli-noop'
                $msg = "FAIL: go-forward reported success without navigating although the browser still had a forward entry (landed on $($probe.Url))"
                Write-Host "    ❌ $msg" -ForegroundColor Red
                $null = $global:FailureMessages.Add("    $msg")
                $global:TestFailed++
            } else {
                $c6Status = 'no-entry'
                $null = Skip-Check "go-forward returns to $($c2.name)" `
                    "the page pruned the forward history: neither the CLI nor the page's own history.forward() could move off $hrefBefore"
            }
        }
    }

    Write-Host "  C7. reload" -ForegroundColor White
    Invoke-Cli reload
    Start-Sleep -Seconds 2
    # Reload must keep whichever page C6 landed on — c1 when the browser had
    # nothing to go forward to.  When C6 could not be evaluated at all, its
    # expectation is unknown, so only the reload itself is exercised.
    $reloadKeyword = switch ($c6Status) {
        'forwarded' { $c2.keyword }
        'no-entry'  { $c1.keyword }
        default     { $null }
    }
    if ($reloadKeyword) {
        Assert-SnapshotContains -Keyword $reloadKeyword -Context "after reload"
    } else {
        Write-Host "    ⏭ reload snapshot check SKIPPED (C6 outcome unknown)" -ForegroundColor DarkYellow
    }

    Write-Host "  C8. close" -ForegroundColor DarkGray
    Invoke-Cli close
    Assert-SessionCount -Expected 0 -Context "after close (Phase C)"

    }

    # ──────────────────────────────────────────────────────────────
    # Phase D: kill-all → verify total reset → open fresh
    #
    # kill-all stops every server process, which forces a full
    # restart on the next open (~26 s).  That overhead is useful
    # to test once (iteration 1), but in later iterations we use
    # close-all instead — it preserves the running server and
    # saves 20–30 s per iteration.
    # ──────────────────────────────────────────────────────────────
    if (-not $Phase -or $Phase -match 'D') {
    if ($iter -eq 1) {
        Write-Host "`n  ── Phase D: kill-all → fresh start (full restart) ──" -ForegroundColor DarkYellow
        Write-Host "  D1. kill-all" -ForegroundColor White
        $null = Invoke-Cli kill-all
        Start-Sleep -Seconds 5

        # After kill-all, the backend is gone — list should show zero sessions.
        Assert-SessionCount -Expected 0 -Context "after kill-all"

        # D2: open fresh after kill-all.
        $dPage = $shuffled | Select-Object -First 1
        Write-Host "  D2. open fresh after kill-all → $($dPage.name)  (server will auto-start)" -ForegroundColor White
        Invoke-Cli open $dPage.url
        Start-Sleep -Seconds 3
        Invoke-RandomInteractions -Count ($rng.Next(1, 3))
        Assert-SnapshotContains -Keyword $dPage.keyword -Context "$($dPage.name) (after kill-all)"
        Assert-SessionCount -Expected 1 -Context "after kill-all + open"

        Write-Host "  D3. close" -ForegroundColor DarkGray
        Invoke-Cli close
        Assert-SessionCount -Expected 0 -Context "after close (Phase D)"
    } else {
        Write-Host "`n  ── Phase D: close-all → fresh start (keep server, iter $iter) ──" -ForegroundColor DarkYellow
        Write-Host "  D1. close-all (preserve running server)" -ForegroundColor White
        $null = Invoke-Cli close-all
        Start-Sleep -Seconds 2

        # After close-all, all sessions are closed but the server is still up.
        Assert-SessionCount -Expected 0 -Context "after close-all"

        # D2: open a fresh session (server already running — no cold-start).
        $dPage = $shuffled | Select-Object -First 1
        Write-Host "  D2. open fresh → $($dPage.name)  (server already running)" -ForegroundColor White
        Invoke-Cli open $dPage.url
        Start-Sleep -Seconds 3
        Invoke-RandomInteractions -Count ($rng.Next(1, 3))
        Assert-SnapshotContains -Keyword $dPage.keyword -Context "$($dPage.name) (after close-all)"
        Assert-SessionCount -Expected 1 -Context "after close-all + open"

        Write-Host "  D3. close" -ForegroundColor DarkGray
        Invoke-Cli close
        Assert-SessionCount -Expected 0 -Context "after close (Phase D)"
    }

    }

    # ──────────────────────────────────────────────────────────────
    # Phase E: go-back on a single-page session (edge case).
    # ──────────────────────────────────────────────────────────────
    if (-not $Phase -or $Phase -match 'E') {
    Write-Host "`n  ── Phase E: edge cases ──" -ForegroundColor DarkYellow

    # E1: close with no active session (should be a no-op or helpful message).
    Write-Host "  E1. close with no session" -ForegroundColor White
    $closeOut = Invoke-Cli close
    Write-Host "       close output: $($closeOut -join '; ')" -ForegroundColor DarkGray
    Assert-SessionCount -Expected 0 -Context "after close-no-session"

    # E2: open → close → close again (double close).
    $ePage = $shuffled | Select-Object -First 1
    Write-Host "  E2. open → close → close (double close)" -ForegroundColor White
    Invoke-Cli open $ePage.url
    Start-Sleep -Seconds 3
    Assert-SessionCount -Expected 1 -Context "before double-close"
    Invoke-Cli close
    Assert-SessionCount -Expected 0 -Context "after first close"
    $close2 = Invoke-Cli close
    Write-Host "       second close output: $($close2 -join '; ')" -ForegroundColor DarkGray
    Assert-SessionCount -Expected 0 -Context "after double close"

    # E3: reload on a page within an active session.
    $e2Page = $shuffled | Where-Object { $_.site -ne $ePage.site } | Select-Object -First 1
    Write-Host "  E3. open → reload → snapshot" -ForegroundColor White
    Invoke-Cli open $e2Page.url
    Start-Sleep -Seconds 3
    Invoke-Cli reload
    Start-Sleep -Seconds 2
    Invoke-RandomInteractions -Count 1
    Assert-SnapshotContains -Keyword $e2Page.keyword -Context "$($e2Page.name) after reload"
    Invoke-Cli close
    }

    # ──────────────────────────────────────────────────────────────
    # Iteration summary
    # ──────────────────────────────────────────────────────────────
    $iterPasses  = $global:TestPassed - $prevPassed
    $iterFailed  = $global:TestFailed - $prevFailed
    $iterTotal   = $iterPasses + $iterFailed

    Write-Host "`n  ── Iteration $iter summary ──" -ForegroundColor Cyan
    Write-Host "  Checks: $iterTotal  Passed: $iterPasses  Failed: $iterFailed" -ForegroundColor $(if ($iterFailed -eq 0) { 'Green' } else { 'Red' })

    # Print per-iteration failure summary
    Write-PerTestSummary -Label "Iteration $iter" -PhasePasses $iterPasses -PhaseFailures $iterFailed

    # Record elapsed for time-budget prediction (see per-iteration budget check above).
    $iterElapsedPrev = $suiteTimer.Elapsed.TotalSeconds
}

# -------------------------------------------------------------------
# Final report
# -------------------------------------------------------------------
$suiteTimer.Stop()

$totalPassed  = $global:TestPassed
$totalFailed  = $global:TestFailed
$totalChecks  = $totalPassed + $totalFailed

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  SESSION STRESS TEST RESULTS" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Seed        : $Seed"
Write-Host "  Iterations  : $Iterations"
Write-Host "  Total checks: $totalChecks"
Write-Host "  Passed      : $totalPassed" -ForegroundColor $(if ($totalPassed -gt 0) { 'Green' } else { 'DarkGray' })
if ($totalFailed -gt 0) {
    Write-Host "  Failed      : $totalFailed" -ForegroundColor Red
}
Write-Host ("  Elapsed     : {0:mm\:ss}" -f $suiteTimer.Elapsed)
Write-Host "  Log dir     : $(Get-LogDir)" -ForegroundColor DarkGray
Write-Host "============================================================" -ForegroundColor Cyan

# Print all failure messages collected during the run
if ($totalFailed -gt 0) {
    Write-Host "`n  ── FAILURE DETAILS ──" -ForegroundColor Red
    foreach ($f in $global:FailureMessages) {
        Write-Host $f -ForegroundColor Red
    }
}

# Checks a browser-level condition made unevaluable (never a product defect).
if ($global:SkippedChecks.Count -gt 0) {
    Write-Host "`n  ── SKIPPED CHECKS ($($global:SkippedChecks.Count)) ──" -ForegroundColor DarkYellow
    foreach ($s in $global:SkippedChecks) {
        Write-Host "    ⏭ $s" -ForegroundColor DarkYellow
    }
}

# Check if any CLI commands failed (tracked by test-utils)
$cliFailures = Get-FailureCount
if ($totalFailed -gt 0 -or $cliFailures -gt 0) {
    Write-Host "`n⚠ $totalFailed assertion(s) failed, $cliFailures CLI command(s) failed — see logs above" -ForegroundColor Red
    $code = Finish-TestSession -ExtraCopilotPrompt "Browser4 CLI stress-session test. Iterations: $Iterations. Seed: $Seed. Total checks: $totalChecks. Skipped: $($global:SkippedChecks.Count) ($($global:SkippedChecks -join '; '))."
    exit $(if ($code -eq 0) { 1 } else { $code })
} else {
    $skippedNote = if ($global:SkippedChecks.Count -gt 0) { " ($($global:SkippedChecks.Count) browser-level check(s) skipped — see SKIPPED CHECKS above)" } else { '' }
    Write-Host "`n✅ ALL $totalChecks CHECKS PASSED$skippedNote" -ForegroundColor Green
    $code = Finish-TestSession
    exit $code
}
