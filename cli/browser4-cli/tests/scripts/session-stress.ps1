#!/usr/bin/env pwsh
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

.PARAMETER Iterations
    Number of full test cycles (default: 3).

.PARAMETER Seed
    RNG seed for reproducible runs (default: random).
#>
param(
    [int] $Iterations = 3,
    [int] $Seed = (Get-Random)
)

$ErrorActionPreference = 'Continue'

# Ensure the console uses UTF-8 so emoji / Unicode from the CLI binary
# survive the round-trip through stdout capture -> Write-Host.
[Console]::OutputEncoding = [Text.Encoding]::UTF8
[Console]::InputEncoding  = [Text.Encoding]::UTF8

# -------------------------------------------------------------------
# CLI helper
# -------------------------------------------------------------------
$cli = if ($env:BROWSER4_CLI_BIN) {
    { & $env:BROWSER4_CLI_BIN $args 2>$null }
} else {
    { cargo run --quiet -- $args 2>$null }
}

# Invoke a CLI command, print its output for progress, and fail on
# non-zero exit.  Write-Host goes to the host (not the output stream),
# so the function's return value stays clean and callers don't capture
# display noise.  When the script is launched with `*>&1` the host
# stream lands in the redirected log.
function Invoke-Cli {
    $out = & $cli @args
    if ($LASTEXITCODE -ne 0) {
        throw "CLI command failed (exit=$LASTEXITCODE): $($args -join ' ')`nOutput:`n$($out -join "`n")"
    }
    if ($out) {
        $out | ForEach-Object { Write-Host $_ }
    }
    $out
}

# -------------------------------------------------------------------
# Helpers
# -------------------------------------------------------------------
filter join-output { ($_ -join ' ') }

# Return only session data rows (exclude header, separator, blank lines).
filter session-data-rows {
    $_ | Where-Object {
        $_ -match '\S' -and
        $_ -notmatch '^\s*-+\s*' -and
        $_ -notmatch '^Name\b' -and
        $_ -notmatch '^Note:'
    }
}

# Assert that a condition holds; print context on failure.
function assert-ok {
    param(
        [string] $Label,
        [scriptblock] $Condition,
        [string] $Detail = ''
    )
    if (-not (& $Condition)) {
        $msg = "FAIL: $Label"
        if ($Detail) { $msg += "`n      $Detail" }
        throw $msg
    }
    Write-Host "    ✓ $Label" -ForegroundColor Green
}

# -------------------------------------------------------------------
# 10 pages across 3 sites
# -------------------------------------------------------------------
$pages = @(
    # Wikipedia (4 pages)
    @{ url = 'https://www.wikipedia.org/';                    site = 'wikipedia'; name = 'Wikipedia Main';    keyword = 'wikipedia' },
    @{ url = 'https://en.wikipedia.org/wiki/Web_browser';     site = 'wikipedia'; name = 'Web Browser';       keyword = 'browser' },
    @{ url = 'https://en.wikipedia.org/wiki/Internet';        site = 'wikipedia'; name = 'Internet';          keyword = 'Internet' },
    @{ url = 'https://en.wikipedia.org/wiki/Computer_network';site = 'wikipedia'; name = 'Computer Network';  keyword = 'network' },
    # GitHub (3 pages)
    @{ url = 'https://github.com/';                           site = 'github';    name = 'GitHub Home';       keyword = 'github' },
    @{ url = 'https://github.com/explore';                    site = 'github';    name = 'GitHub Explore';    keyword = 'github' },
    @{ url = 'https://github.com/trending';                   site = 'github';    name = 'GitHub Trending';   keyword = 'github' },
    # Hacker News (3 pages)
    @{ url = 'https://news.ycombinator.com/';                 site = 'hackernews'; name = 'HN Front';         keyword = 'ycombinator' },
    @{ url = 'https://news.ycombinator.com/newest';           site = 'hackernews'; name = 'HN Newest';        keyword = 'ycombinator' },
    @{ url = 'https://news.ycombinator.com/show';             site = 'hackernews'; name = 'HN Show';          keyword = 'ycombinator' }
)

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
    $rows = Get-SessionDataRows
    $label = "session count == $Expected  ($Context)"
    if ($rows.Count -ne $Expected) {
        $all = Invoke-Cli list
        throw "FAIL: $label`n      found $($rows.Count) row(s):`n$($all -join "`n")"
    }
    Write-Host "    ✓ $label" -ForegroundColor Green
}

function Assert-SnapshotContains {
    param([string]$Keyword, [string]$Context)
    $snap = Invoke-Cli snapshot
    # Join lines into a single string so -like works as a boolean test
    # (passing an array to -like would filter instead of returning bool).
    $text = $snap -join ' '
    if ($text -notlike "*$Keyword*") {
        throw "FAIL: snapshot contains '$Keyword'  ($Context)`n      snapshot output:`n$($snap -join "`n")"
    }
    Write-Host "    ✓ snapshot contains '$Keyword'  ($Context)" -ForegroundColor Green
}

# -------------------------------------------------------------------
# Clean start
# -------------------------------------------------------------------
Write-Host "`n=== SESSION STRESS TEST ===" -ForegroundColor Cyan
Write-Host "  Iterations : $Iterations" -ForegroundColor Cyan
Write-Host "  Seed       : $Seed" -ForegroundColor Cyan
Write-Host "  Pages      : $($pages.Count) across 3 sites" -ForegroundColor Cyan
Write-Host ""

Write-Host "── Ensuring clean slate (close any lingering sessions) ──" -ForegroundColor DarkYellow
try { $null = Invoke-Cli close } catch { Write-Host "       (no session to close, ok)" -ForegroundColor DarkGray }
try { $null = Invoke-Cli close-all } catch { Write-Host "       (close-all skipped)" -ForegroundColor DarkGray }
# Let the server finish async session teardown before we create new
# sessions; otherwise a late-arriving close-all can nuke our active
# session mid-test.
Start-Sleep -Seconds 3

# -------------------------------------------------------------------
# Main test loop
# -------------------------------------------------------------------
$totalPasses = 0
$suiteTimer = [Diagnostics.Stopwatch]::StartNew()

for ($iter = 1; $iter -le $Iterations; $iter++) {
    Write-Host "`n━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
    Write-Host "  ITERATION $iter / $Iterations  (seed=$Seed)" -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

    $iterPasses = 0

    # Shuffle page order each iteration for varied coverage.
    $shuffled = $pages | Sort-Object { $rng.Next() }

    # ──────────────────────────────────────────────────────────────
    # Phase A: Open each of 3 pages (different sites), interact,
    #          verify session carries over via goto, then close.
    # ──────────────────────────────────────────────────────────────
    Write-Host "`n  ── Phase A: open → interact → snapshot → goto → close ──" -ForegroundColor DarkYellow

    # Pick one page per site for this phase.
    $phaseAPages = @(
        ($shuffled | Where-Object { $_.site -eq 'wikipedia' } | Select-Object -First 1),
        ($shuffled | Where-Object { $_.site -eq 'github' } | Select-Object -First 1),
        ($shuffled | Where-Object { $_.site -eq 'hackernews' } | Select-Object -First 1)
    )

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

    $iterPasses += 5

    # ──────────────────────────────────────────────────────────────
    # Phase B: Rapid open/close cycles across all 10 pages.
    #          Each iteration: open → interact → snapshot → close.
    # ──────────────────────────────────────────────────────────────
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

    $iterPasses += ($shuffled.Count * 2)

    # ──────────────────────────────────────────────────────────────
    # Phase C: Mixed navigation — go-back, go-forward, reload
    #          within a single session across 3 pages.
    # ──────────────────────────────────────────────────────────────
    Write-Host "`n  ── Phase C: go-back / go-forward / reload ──" -ForegroundColor DarkYellow

    $c1 = ($shuffled | Where-Object { $_.site -eq 'wikipedia' } | Select-Object -First 1)
    $c2 = ($shuffled | Where-Object { $_.site -eq 'github' } | Select-Object -First 1)
    $c3 = ($shuffled | Where-Object { $_.site -eq 'hackernews' } | Select-Object -First 1)

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
    Invoke-Cli go-forward
    Start-Sleep -Seconds 2
    Assert-SnapshotContains -Keyword $c2.keyword -Context "after go-forward ($($c2.name))"

    Write-Host "  C7. reload" -ForegroundColor White
    Invoke-Cli reload
    Start-Sleep -Seconds 2
    Assert-SnapshotContains -Keyword $c2.keyword -Context "after reload ($($c2.name))"

    Write-Host "  C8. close" -ForegroundColor DarkGray
    Invoke-Cli close
    Assert-SessionCount -Expected 0 -Context "after close (Phase C)"

    $iterPasses += 8

    # ──────────────────────────────────────────────────────────────
    # Phase D: kill-all → verify total reset → open fresh
    # ──────────────────────────────────────────────────────────────
    Write-Host "`n  ── Phase D: kill-all → fresh start ──" -ForegroundColor DarkYellow

    Write-Host "  D1. kill-all" -ForegroundColor White
    $null = Invoke-Cli kill-all
    Start-Sleep -Seconds 5

    # After kill-all, the backend is gone — list should show zero sessions.
    Assert-SessionCount -Expected 0 -Context "after kill-all"

    # D2: open fresh after kill-all.
    # `open` will auto-start the server; give it extra time.
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

    $iterPasses += 3

    # ──────────────────────────────────────────────────────────────
    # Phase E: go-back on a single-page session (edge case).
    #          Should handle gracefully.
    # ──────────────────────────────────────────────────────────────
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

    $iterPasses += 3

    # ──────────────────────────────────────────────────────────────
    # Iteration summary
    # ──────────────────────────────────────────────────────────────
    Write-Host "`n  ── Iteration $iter summary ──" -ForegroundColor Cyan
    Write-Host "  Passes: $iterPasses" -ForegroundColor Green

    $totalPasses += $iterPasses
}

# -------------------------------------------------------------------
# Final report
# -------------------------------------------------------------------
$suiteTimer.Stop()

Write-Host "`n============================================================" -ForegroundColor Cyan
Write-Host "  SESSION STRESS TEST RESULTS" -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "  Seed        : $Seed"
Write-Host "  Iterations  : $Iterations"
Write-Host "  Total checks: $totalPasses"
Write-Host "  Passed      : $totalPasses" -ForegroundColor Green
Write-Host ("  Elapsed     : {0:mm\:ss}" -f $suiteTimer.Elapsed)
Write-Host "============================================================" -ForegroundColor Cyan

Write-Host "`n✅ ALL $totalPasses CHECKS PASSED" -ForegroundColor Green
exit 0
