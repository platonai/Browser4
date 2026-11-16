#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Four-bucket attribution for agent tasks, from the L0 memory event log.

.DESCRIPTION
    Reads `app-data/memory/events/<agentUuid>/<taskId>.jsonl` and reports, per task:

      build      sum(durationMs) of `coding_mvnBuild` / `coding_verify`
      tool       sum(durationMs) of every other tool_executed event
      callback   sum(round_timing.callbacksMs)   — the loop's per-call plumbing
      model      sum(round_timing.modelMs)       — the provider round-trips
      outTok     sum(round_timing.outTokens)     — what model time is proportional to
      cache%     cached input tokens / input tokens (round_timing.cachedInTokens vs model)
      gap        wall clock not covered by the above (v1 metric; kept for comparison)

    When at least five rounds carry token accounting, the script also fits the empirical
    cost model `modelMs = a + b * outTokens` (least squares) and prints it: on the
    2026-11-13 self-development runs that was `600 ms + 4.05 ms/token`, R^2 = 94.7%, while
    uncached input had no measurable effect (92.6% prompt-cache hit rate).

    `round_timing` events only exist once the P0-0 instrumentation is in the backend
    (2026-11-13+). Older tasks report `n/a` for callback/model and are summarized with the
    v1 three-bucket view, which is what the plan's baseline table (2026-11-13) used.

    Also flags **zero-edit rebuilds**: a build call with no mutating tool call between it and
    the previous build of the same module — the waste P0-1's short-circuit removes.

.PARAMETER EventsRoot
    The agent memory events root. Defaults to the current workspace's app data under
    `~/.browser4/workspaces/*/app-data/memory/events` (the newest one wins).

.PARAMETER Last
    Number of most recently written task files to analyse (default 10).

.PARAMETER TaskId
    Analyse one task id instead of the most recent N.

.EXAMPLE
    pwsh docs-dev/copilot/scripts/analyze-agent-timing.ps1 -Last 5

.EXAMPLE
    pwsh docs-dev/copilot/scripts/analyze-agent-timing.ps1 -TaskId e1e1b5c0
#>
[CmdletBinding()]
param(
    [string]$EventsRoot,
    [int]$Last = 10,
    [string]$TaskId
)

$ErrorActionPreference = 'Stop'

function Resolve-EventsRoot {
    param([string]$Explicit)
    if ($Explicit) { return $Explicit }
    $base = Join-Path $HOME '.browser4/workspaces'
    if (-not (Test-Path $base)) { throw "No agent memory events found: $base does not exist." }
    $candidate = Get-ChildItem $base -Directory |
        ForEach-Object { Join-Path $_.FullName 'app-data/memory/events' } |
        Where-Object { Test-Path $_ } |
        Sort-Object { (Get-Item $_).LastWriteTime } -Descending |
        Select-Object -First 1
    if (-not $candidate) { throw "No app-data/memory/events under $base." }
    return $candidate
}

function Read-Events {
    param([string]$Path)
    # Tolerate a trailing partial line (the log is append-only and may be read mid-write).
    Get-Content $Path -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.Trim()) { try { $_ | ConvertFrom-Json } catch { } }
    }
}

# Mutating coding tools: the plan's zero-edit window is "no non-read tool call in between",
# and the memory log records camelCase MCP names (`coding_replace`), so match on the prefix.
$WriteToolPattern = '^coding_(write|append|replace|editLines|insertAfter|delete|mkdir|copy|move|revert|scaffold|devTask)'

$root = Resolve-EventsRoot -Explicit $EventsRoot
$files = if ($TaskId) {
    Get-ChildItem $root -Recurse -Filter "$TaskId.jsonl"
} else {
    Get-ChildItem $root -Recurse -Filter '*.jsonl' | Sort-Object LastWriteTime -Descending | Select-Object -First $Last
}
if (-not $files) { throw "No task files matched (root=$root taskId=$TaskId)." }

Write-Host "events root: $root"
Write-Host ("{0,-10} {1,8} {2,7} {3,8} {4,9} {5,9} {6,9} {7,9} {8,7} {9,8} {10,9}" -f `
    'task', 'rounds', 'calls', 'buildN', 'build', 'tool', 'callback', 'model', 'outTok', 'cachedIn', 'gap')
Write-Host ('-' * 112)

# Pooled (modelMs, outTokens) points for the empirical cost model.
$costPoints = @()

foreach ($file in $files) {
    $events = @(Read-Events -Path $file.FullName)
    if ($events.Count -eq 0) { continue }

    $tools = @($events | Where-Object { $_.type -eq 'tool_executed' })
    if ($tools.Count -eq 0) { continue }
    $rounds = @($events | Where-Object { $_.type -eq 'round_timing' })

    $buildMs = 0L; $buildN = 0
    $toolMs = 0L
    foreach ($t in $tools) {
        if ($t.tool -eq 'coding_mvnBuild' -or $t.tool -eq 'coding_verify' -or
            $t.tool -eq 'coding.mvnBuild' -or $t.tool -eq 'coding.verify') {
            $buildMs += [long]$t.durationMs
            $buildN++
        } else {
            $toolMs += [long]$t.durationMs
        }
    }

    $callbackMs = ($rounds | Measure-Object -Property callbacksMs -Sum).Sum
    $modelMs = ($rounds | Measure-Object -Property modelMs -Sum).Sum
    $outTok = ($rounds | Measure-Object -Property outTokens -Sum).Sum
    $cachedIn = ($rounds | Measure-Object -Property cachedInTokens -Sum).Sum
    $hasTiming = $rounds.Count -gt 0
    $hasTokens = $outTok -gt 0

    foreach ($r in $rounds) {
        if ([int]$r.outTokens -gt 0) {
            $costPoints += [pscustomobject]@{ modelMs = [double]$r.modelMs; outTokens = [double]$r.outTokens }
        }
    }

    # Wall clock: first tool event to the task's terminal event (completed/failed), else last event.
    $terminal = @($events | Where-Object { $_.type -eq 'completed' -or $_.type -eq 'failed' }) | Select-Object -Last 1
    $firstTs = ($tools | Select-Object -First 1).ts
    $endTs = if ($terminal) { $terminal.ts } else { ($events | Select-Object -Last 1).ts }
    $wallMs = [long]($endTs - $firstTs)
    if ($wallMs -lt 0) { $wallMs = 0 }

    $accounted = $buildMs + $toolMs + $(if ($hasTiming) { $callbackMs + $modelMs } else { 0 })
    $gapMs = [Math]::Max(0, $wallMs - $accounted)

    Write-Host ("{0,-10} {1,8} {2,7} {3,8} {4,9:N0} {5,9:N0} {6,9} {7,9} {8,7} {9,8} {10,9:N0}" -f `
        $file.BaseName, $(if ($hasTiming) { $rounds.Count } else { 0 }), $tools.Count, $buildN,
        $buildMs, $toolMs,
        $(if ($hasTiming) { "{0:N0}" -f $callbackMs } else { 'n/a' }),
        $(if ($hasTiming) { "{0:N0}" -f $modelMs } else { 'n/a' }),
        $(if ($hasTokens) { "{0:N0}" -f $outTok } else { 'n/a' }),
        $(if ($hasTokens) { "{0:N0}" -f $cachedIn } else { 'n/a' }),
        $gapMs)

    if ($buildN -gt 0) {
        # Zero-edit rebuilds: two build calls with no mutating call in between.
        $zeroEdit = 0
        $lastBuildIndex = -1
        $dirtySinceBuild = $false
        for ($i = 0; $i -lt $tools.Count; $i++) {
            $name = $tools[$i].tool
            if ($name -eq 'coding_mvnBuild' -or $name -eq 'coding_verify' -or
                $name -eq 'coding.mvnBuild' -or $name -eq 'coding.verify') {
                if ($lastBuildIndex -ge 0 -and -not $dirtySinceBuild) { $zeroEdit++ }
                $lastBuildIndex = $i
                $dirtySinceBuild = $false
            } elseif ($name -match $WriteToolPattern) {
                $dirtySinceBuild = $true
            }
        }
        if ($zeroEdit -gt 0) {
            $cachedHits = @($tools | Where-Object {
                    ($_.tool -eq 'coding_mvnBuild' -or $_.tool -eq 'coding_verify') -and
                    ($_.resultBrief -match 'cached|no source change')
                }).Count
            Write-Host ("    zero-edit rebuilds: {0} (cached answers visible in results: {1})" -f $zeroEdit, $cachedHits)
        }
    }
}

# Empirical cost model: modelMs = a + b * outTokens (least squares over pooled rounds).
if ($costPoints.Count -ge 5) {
    $n = $costPoints.Count
    $sx = ($costPoints | Measure-Object outTokens -Sum).Sum
    $sy = ($costPoints | Measure-Object modelMs -Sum).Sum
    $sxx = ($costPoints | ForEach-Object { $_.outTokens * $_.outTokens } | Measure-Object -Sum).Sum
    $sxy = ($costPoints | ForEach-Object { $_.outTokens * $_.modelMs } | Measure-Object -Sum).Sum
    $den = ($n * $sxx - $sx * $sx)
    if ([Math]::Abs($den) -gt 1e-9) {
        $b = ($n * $sxy - $sx * $sy) / $den
        $a = ($sy - $b * $sx) / $n
        $meanY = $sy / $n
        $ssTot = ($costPoints | ForEach-Object { ($_.modelMs - $meanY) * ($_.modelMs - $meanY) } | Measure-Object -Sum).Sum
        $ssRes = ($costPoints | ForEach-Object { $e = $_.modelMs - ($a + $b * $_.outTokens); $e * $e } | Measure-Object -Sum).Sum
        $r2 = if ($ssTot -gt 0) { 1 - $ssRes / $ssTot } else { 0 }
        Write-Host ''
        Write-Host ("cost model ({0} rounds): modelMs ≈ {1:N0} ms + {2:N2} ms per output token  (R² = {3:P1})" -f `
            $n, $a, $b, $r2)
        Write-Host ("  => each 1,000 output tokens ≈ {0:N1} s; each request ≈ {1:N1} s of fixed latency" -f ($b), ($a / 1000))
    }
} else {
    Write-Host ''
    Write-Host 'cost model: not enough token-bearing rounds yet (need >= 5; token fields ship with the P0-0 instrumentation).'
}

Write-Host ''
Write-Host 'Note: callback/model are `n/a` for tasks recorded before the P0-0 instrumentation;'
Write-Host 'use `-TaskId` on a task run after 2026-11-13 to fill the four-bucket row (plan appendix B).'
