#requires -Version 7
<#
    Tests for bin/measure-prompt-tokens.ps1.

    Plain pwsh (no Pester) so `pwsh bin/test.ps1 ps` can always run it: the harness
    falls back to `pwsh -File` for a *.tests.ps1 that has no Describe/Context block.

    What is guarded:
      - the script parses the sources (it throws, rather than reporting zero, when an
        anchor or a toolSpec block disappears);
      - the initial tool-set sizes stay what RobustBrowserAgent builds (39 / 29);
      - the design invariants hold: the coding system prompt is far smaller than the
        browsing one, and progressive disclosure really is smaller than the full registry;
      - -Check passes at the recorded baseline and fails when a description grows.
#>

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$script:MeasurementScript = Join-Path $PSScriptRoot 'measure-prompt-tokens.ps1'
$script:RepoRoot = Split-Path -Parent $PSScriptRoot
$script:Failures = 0
$script:Checks = 0

function Assert-True {
    param([bool]$Condition, [string]$Message)
    $script:Checks++
    if ($Condition) {
        Write-Host "  PASS  $Message" -ForegroundColor Green
    } else {
        Write-Host "  FAIL  $Message" -ForegroundColor Red
        $script:Failures++
    }
}

function Invoke-Measurement {
    param([string[]]$ExtraArgs = @(), [string]$RepoRoot)
    $all = @('-NoProfile', '-File', $script:MeasurementScript) + $ExtraArgs
    if ($RepoRoot) { $all += @('-RepoRoot', $RepoRoot) }
    $out = & (Get-Process -Id $PID).Path @all 2>&1
    return [pscustomobject]@{ ExitCode = $LASTEXITCODE; Output = (($out | Out-String).TrimEnd()) }
}

# The sources the measurement reads. A drift test copies exactly these into a temp root.
$sources = @(
    'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/CodingToolExecutor.kt'
    'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/B4CliToolExecutor.kt'
    'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/tools/builtin/SystemToolExecutor.kt'
    'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/memory/MemoryToolExecutor.kt'
    'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/skills/tools/SkillToolExecutor.kt'
    'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/inference/chat/ToolDisclosure.kt'
    'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/CliAgentPrompt.kt'
    'browser4-agentic/src/main/kotlin/ai/platon/pulsar/agentic/agents/RobustBrowserAgent.kt'
    'skills/browser4-cli/references/quickstart.md'
)

Write-Host 'measure-prompt-tokens' -ForegroundColor Cyan

# ── 1. it measures -----------------------------------------------------------------
$run = Invoke-Measurement -ExtraArgs @('-Json')
Assert-True ($run.ExitCode -eq 0) "the script exits 0 (got $($run.ExitCode))"

$result = $null
if ($run.ExitCode -eq 0) {
    try { $result = $run.Output | ConvertFrom-Json } catch { }
}
Assert-True ($null -ne $result) 'the -Json output is parseable JSON'

if ($null -ne $result) {
    # ── 2. the initial tool sets match what RobustBrowserAgent builds ---------------
    Assert-True ($result.CodingTaskToolCount -eq 39) "coding task exposes 39 tools (got $($result.CodingTaskToolCount))"
    Assert-True ($result.BrowsingTaskToolCount -eq 29) "browsing task exposes 29 tools (got $($result.BrowsingTaskToolCount))"
    Assert-True ($result.CodingSpecCount -eq 58) "the coding domain declares 58 methods (got $($result.CodingSpecCount))"
    Assert-True ($result.FullCliRegistryToolCount -eq 78) "the full CLI registry is 78 tools (got $($result.FullCliRegistryToolCount))"
    Assert-True ($result.MetaToolChars -gt 300) 'the two disclosure meta tools were measured'

    # ── 3. design invariants --------------------------------------------------------
    Assert-True ($result.CodingPromptChars -lt 6000) `
        "the coding system prompt stays under 6k chars (got $($result.CodingPromptChars))"
    Assert-True ($result.BrowsingPromptChars -gt 3 * $result.CodingPromptChars) `
        'the browsing prompt is at least 3x the coding one (the resident quickstart.md is browsing-only)'
    Assert-True ($result.CodingTaskToolChars -lt $result.FullCliRegistryChars) `
        'disclosure exposes less than the full registry'
    Assert-True ($result.CodingTaskToolChars -gt 20000) `
        "the initial tool payload is ~22.5k chars (got $($result.CodingTaskToolChars))"
}

# ── 4. -Check passes at the recorded baseline --------------------------------------
$check = Invoke-Measurement -ExtraArgs @('-Check')
Assert-True ($check.ExitCode -eq 0) "-Check passes against the recorded baseline (got $($check.ExitCode))"

# ── 5. -Check fails when a tool description grows -----------------------------------
$tmp = Join-Path ([System.IO.Path]::GetTempPath()) ("b4-measure-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
try {
    foreach ($rel in $sources) {
        $dest = Join-Path $tmp $rel
        New-Item -ItemType Directory -Force -Path (Split-Path -Parent $dest) | Out-Null
        Copy-Item -LiteralPath (Join-Path $script:RepoRoot $rel) -Destination $dest
    }
    # Grow one description the way an agent-facing doc edit would: +800 chars of prose
    # inserted INSIDE the literal (before the closing quote), so the script measures it.
    $coding = Join-Path $tmp $sources[0]
    $text = Get-Content -LiteralPath $coding -Raw
    $anchor = 'description = "Read a file''s content. Supports all text file types. Binary files show metadata only."'
    Assert-True ($text.Contains($anchor)) 'the drift fixture can find the read() description'
    $padding = ' Extra prose that a future edit might add and nobody would otherwise notice.'
    $grown = $anchor.Substring(0, $anchor.Length - 1) + ($padding * 11) + '"'
    $text = $text.Replace($anchor, $grown)
    Set-Content -LiteralPath $coding -Value $text -NoNewline

    # 1% tolerance, because 800 chars on ~19.6k is ~4% — well under the shipped default.
    $drift = Invoke-Measurement -ExtraArgs @('-Check', '-Tolerance', '0.01') -RepoRoot $tmp
    Assert-True ($drift.ExitCode -ne 0) 'a grown description fails -Check (exit != 0)'
    Assert-True ($drift.Output -match 'DRIFT') 'the failure names the drift'
} finally {
    Remove-Item -LiteralPath $tmp -Recurse -Force -ErrorAction SilentlyContinue
}

Write-Host ''
if ($script:Failures -gt 0) {
    Write-Host "FAILED: $script:Failures of $script:Checks checks" -ForegroundColor Red
    exit 1
}
Write-Host "OK: $script:Checks checks passed" -ForegroundColor Green
exit 0
