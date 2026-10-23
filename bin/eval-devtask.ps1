#!/usr/bin/env pwsh
#requires -Version 7

# ===================================================================
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Do not shell out to platform-specific tools; the probes only call
#   `b4w.ps1` (PowerShell is required to run this script anyway) and,
#   optionally, `git` for the read-only gate (skipped when absent).
# - Console streams are pinned to UTF-8 so the CJK probe survives the
#   Windows OEM code page.
# ===================================================================

<#
.SYNOPSIS
    Black-box evaluation of `browser4-cli code devtask` (the Browser4
    self-development planner) through the development wrapper.

.DESCRIPTION
    Runs a fixed matrix of natural-language dev tasks through

        ./b4w.ps1 cli code devtask "<task>"

    and grades the rendered plan against the contracts DevTaskPlanner is
    supposed to honour: signal binding (module / file / test class), step
    shape and ordering, governance steps, determinism, read-only planning,
    CLI arg contract, warm-call latency, and (unless -Fast) the --verify path.

    Every check is a gate.  A gate is either:
      PASS   the contract holds
      FAIL   the contract is broken -> non-zero exit
      KNOWN  a registered, documented defect (the eval task's known-gap
             register).  Reported, but does not fail the run unless -Strict
      XPASS  a registered defect no longer reproduces -> update the register
      SKIP   the check could not run on this host

    The definition of the eval task, the probe matrix, the gate catalogue,
    and the known-gap register live in
    `docs-dev/copilot/eval-code-devtask.md`.  Keep the two in sync.

    SAFETY: this harness never passes --execute, so it only ever renders a
    plan.  The G-READONLY gate proves it: `git status --porcelain` must be
    unchanged after the whole run.

.PARAMETER Fast
    Skip the slower gates: --verify, and the determinism re-run.

.PARAMETER Strict
    Treat registered known gaps (KNOWN) as failures.  Use this once a gap is
    believed fixed, or in a gate that must stay clean.

.PARAMETER Json
    Print the report as JSON only (no human scorecard).

.PARAMETER JsonPath
    Additionally write the report as JSON to this file.

.PARAMETER Probe
    Run only the named probe(s), e.g. -Probe existing-plugin,new-plugin.
    Only the gates of the selected probes are evaluated; the global gates
    (plan shape, CLI contract, latency, --verify) always run.

.PARAMETER TimeoutSec
    Per-invocation timeout.  Default 300; the --verify probe may need more
    (Maven), bump it with -TimeoutSec 900.

.PARAMETER NoBuild
    Forward -NoBuild to b4w.ps1 (use the already-built CLI binary instead of
    letting the wrapper rebuild from source).  Faster eval loop.

.PARAMETER Help
    Show this help.

.EXAMPLE
    ./bin/eval-devtask.ps1
    Full eval: 9 plan probes + determinism + read-only + CLI contract + --verify.

.EXAMPLE
    ./bin/eval-devtask.ps1 -Fast -NoBuild
    Fast plan-only eval (no --verify, no re-run), reusing the built binary.

.EXAMPLE
    ./bin/eval-devtask.ps1 -Probe driver-bare -Json -JsonPath ./devtask-eval.json
    Grade one probe and emit a machine-readable report.

.NOTES
    Exit codes: 0 = all required gates PASS, 1 = at least one required gate
    FAIL (or any KNOWN gate with -Strict), 2 = harness/environment error.
#>

[CmdletBinding(PositionalBinding = $false)]
param(
    [switch]$Fast,
    [switch]$Strict,
    [switch]$Json,
    [string]$JsonPath = '',
    [string[]]$Probe = @(),
    [int]$TimeoutSec = 300,
    [switch]$NoBuild,
    [switch]$Help
)

$ErrorActionPreference = 'Stop'

# ── Console encoding: always UTF-8 (CJK probe + CJK narration) ───────────────
try {
    if ([Console]::OutputEncoding.CodePage -ne 65001) {
        [Console]::OutputEncoding = [System.Text.UTF8Encoding]::new($false)
    }
} catch {
    # A host without a usable console must not fail the run over an encoding.
}

# ── Repo root: by script location, never by cwd ─────────────────────────────
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$repoRoot = Split-Path -Parent $scriptDir
if (-not (Test-Path (Join-Path $repoRoot 'VERSION'))) {
    Write-Host "Error: could not locate the repository root from $scriptDir" -ForegroundColor Red
    exit 2
}

$script:RepoRoot = $repoRoot
$script:Launcher = Join-Path $repoRoot 'b4w.ps1'
$script:PwshExe = (Get-Process -Id $PID).Path
$script:TimeoutSec = if ($TimeoutSec -gt 0) { $TimeoutSec } else { 300 }
$script:Fast = [bool]$Fast
$script:Strict = [bool]$Strict

if ($Help) {
    Get-Help $MyInvocation.MyCommand.Path -Detailed
    exit 0
}

if (-not (Test-Path $script:Launcher)) {
    Write-Host "Error: $script:Launcher not found — this eval drives the CLI through the b4w.ps1 dev wrapper." -ForegroundColor Red
    exit 2
}

# b4w.ps1 strips -NoBuild itself before forwarding to the CLI.
$script:LauncherPrelude = @()
if ($NoBuild) { $script:LauncherPrelude += '-NoBuild' }

# ===================================================================
# Reporting helpers
# ===================================================================

$script:Gates = [System.Collections.Generic.List[object]]::new()
$script:Metrics = [ordered]@{}

function Write-Rule {
    param([string]$Title = '')
    Write-Host ('─' * 78) -ForegroundColor DarkGray
    if ($Title) { Write-Host $Title -ForegroundColor Cyan }
}

function Add-Gate {
    <#
        Record one gate result.
        $Gap - non-empty registers the check as a documented known gap:
              a failure is reported as KNOWN (not fatal), a pass as XPASS.
    #>
    param(
        [Parameter(Mandatory)][string]$Id,
        [Parameter(Mandatory)][string]$Title,
        [Parameter(Mandatory)][bool]$Passed,
        [string]$Evidence = '',
        [string]$Gap = ''
    )

    $status = if ($Gap) { if ($Passed) { 'XPASS' } else { 'KNOWN' } }
              elseif ($Passed) { 'PASS' } else { 'FAIL' }

    $script:Gates.Add([pscustomobject]@{
        Id       = $Id
        Title    = $Title
        Status   = $status
        Gap      = $Gap
        Evidence = $Evidence
    })
}

function Add-Skip {
    param([string]$Id, [string]$Title, [string]$Reason)
    $script:Gates.Add([pscustomobject]@{
        Id = $Id; Title = $Title; Status = 'SKIP'; Gap = ''; Evidence = $Reason
    })
}

function Format-Evidence {
    param([string]$Text, [int]$Max = 160)
    if ([string]::IsNullOrWhiteSpace($Text)) { return '' }
    $one = ($Text -split '\r?\n' | Where-Object { $_.Trim() } | Select-Object -First 1)
    if ($null -eq $one) { return '' }
    $one = $one.Trim()
    if ($one.Length -gt $Max) { $one = $one.Substring(0, $Max) + '…' }
    return $one
}

# ===================================================================
# CLI invocation (child process: b4w.ps1 calls `exit`, so it must not
# run in this session) and plan parsing
# ===================================================================

function Invoke-Cli {
    <#
        Run `b4w.ps1 <args...>` in a child pwsh and capture everything.
        Returns @{ Text; ExitCode; Seconds; TimedOut }.
    #>
    param(
        [Parameter(Mandatory)][string[]]$CliArgs,
        [int]$TimeoutSeconds = 0
    )

    if ($TimeoutSeconds -le 0) { $TimeoutSeconds = $script:TimeoutSec }

    $psi = [System.Diagnostics.ProcessStartInfo]::new()
    $psi.FileName = $script:PwshExe
    $psi.WorkingDirectory = $script:RepoRoot
    $psi.UseShellExecute = $false
    $psi.RedirectStandardOutput = $true
    $psi.RedirectStandardError = $true
    $psi.StandardOutputEncoding = [System.Text.UTF8Encoding]::new($false)
    $psi.StandardErrorEncoding = [System.Text.UTF8Encoding]::new($false)

    $parts = @('-NoProfile', '-NonInteractive', '-File', $script:Launcher) +
             $script:LauncherPrelude + $CliArgs
    foreach ($p in $parts) { $psi.ArgumentList.Add([string]$p) }

    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    $proc = [System.Diagnostics.Process]::Start($psi)
    $outTask = $proc.StandardOutput.ReadToEndAsync()
    $errTask = $proc.StandardError.ReadToEndAsync()

    $timedOut = $false
    if (-not $proc.WaitForExit($TimeoutSeconds * 1000)) {
        $timedOut = $true
        try { $proc.Kill($true) } catch { }
    }

    $stdout = $outTask.GetAwaiter().GetResult()
    $stderr = $errTask.GetAwaiter().GetResult()
    $sw.Stop()

    $text = (@($stdout, $stderr) | Where-Object { $_ -and $_.Trim() }) -join "`n"

    return [pscustomobject]@{
        Text     = $text.Trim()
        ExitCode = if ($timedOut) { -1 } else { $proc.ExitCode }
        Seconds  = [math]::Round($sw.Elapsed.TotalSeconds, 2)
        TimedOut = $timedOut
    }
}

function ConvertFrom-Plan {
    <#
        Parse the human-readable plan rendered by `code devtask`:

            Dev task plan (N steps):
              1. [coding.read] purpose
                  coding.read(path="x")
              ...
            Signals: ...
            Inferred modules: ...

        Line-oriented on purpose: these are exactly the lines an agent (or a
        supervisor script) consumes.
    #>
    param([string]$Text)

    $plan = [pscustomobject]@{
        Header         = ''
        DeclaredSteps  = $null
        Steps          = [System.Collections.Generic.List[object]]::new()
        Signals        = ''
        Inferred       = @()
        Verification   = $false
    }

    $headerRe = [regex]'^Dev task plan \((\d+) steps?\):'
    $stepRe = [regex]'^\s{2}(\d+)\. \[(coding\.[A-Za-z]+)\] (.+)$'
    $cmdRe = [regex]'^\s{6}(coding\..+)$'

    foreach ($rawLine in ($Text -split '\r?\n')) {
        $line = $rawLine.TrimEnd()

        $m = $headerRe.Match($line)
        if ($m.Success) {
            $plan.Header = $line.Trim()
            $plan.DeclaredSteps = [int]$m.Groups[1].Value
            continue
        }

        $m = $stepRe.Match($line)
        if ($m.Success) {
            $plan.Steps.Add([pscustomobject]@{
                Order   = [int]$m.Groups[1].Value
                Tool    = $m.Groups[2].Value
                Purpose = $m.Groups[3].Value.Trim()
                Command = ''
            })
            continue
        }

        $m = $cmdRe.Match($line)
        if ($m.Success) {
            if ($plan.Steps.Count -gt 0) {
                $last = $plan.Steps[$plan.Steps.Count - 1]
                if (-not $last.Command) { $last.Command = $m.Groups[1].Value.Trim() }
            }
            continue
        }

        if ($line -match '^Signals:\s*(.*)$') { $plan.Signals = $Matches[1].Trim(); continue }
        if ($line -match '^Inferred modules:\s*(.*)$') {
            $plan.Inferred = $Matches[1].Trim().Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ }
            continue
        }
        if ($line -match '^--- Verification ---') { $plan.Verification = $true; continue }
    }

    return $plan
}

function Get-PlanTool {
    param([object]$Plan)
    return @($Plan.Steps | ForEach-Object { $_.Tool })
}

function Get-StepCommand {
    param([object]$Plan, [string]$Tool)
    return @($Plan.Steps | Where-Object { $_.Tool -eq $Tool } | ForEach-Object { $_.Command })
}

function Test-AnyCommand {
    param([object]$Plan, [string]$Pattern)
    return (@($Plan.Steps | Where-Object { $_.Command -match $Pattern }).Count -gt 0)
}

# ===================================================================
# Probe matrix (mirrors docs-dev/copilot/eval-code-devtask.md §2)
# ===================================================================

$script:AllProbes = @(
    [pscustomobject]@{
        Id = 'driver-bare'
        Why = 'Bare browser-driver filename: must resolve to its owning module, never to an unrelated one'
        # PulsarWebDriver.kt moved to the pulsar-browser library; the in-repo
        # driver is Browser4WebDriver.kt. The probe intent (bare filename → the
        # owning module) is unchanged.
        Task = 'fix cursor positioning in Browser4WebDriver.kt and add a test class FillCursorTest in browser4-rest'
    },
    [pscustomobject]@{
        Id = 'existing-plugin'
        Why = 'Existing plugin module + named test: build/test bind the deepest module'
        Task = 'fix a test in browser4-plugins/browser4-seo and run SeoServiceTest'
    },
    [pscustomobject]@{
        Id = 'new-plugin'
        Why = 'Unknown browser4-plugins/<name> mention: scaffold first, then bind build/test to the new module'
        Task = '创建新插件 browser4-plugins/browser4-evalprobe，实现 src/main/resources/evalprobe/count.js 与 EvalprobeServiceTest'
    },
    [pscustomobject]@{
        Id = 'cli-crate'
        Why = 'Rust CLI crate task: cargo unit tests, no bogus Maven module'
        Task = 'add a new browser4-cli command with a batch test in cli/browser4-cli/src/commands.rs'
    },
    [pscustomobject]@{
        Id = 'no-signal'
        Why = 'No code signal: say so instead of fabricating scope'
        Task = 'please improve the overall performance'
    },
    [pscustomobject]@{
        Id = 'prose-test'
        Why = 'Prose word "test" is not a test class: no -Dtest scoping'
        Task = 'write a test and run the module suite for browser4-rest'
    },
    [pscustomobject]@{
        Id = 'zh-mixed'
        Why = 'CJK task text must not break ASCII signal parsing'
        Task = '在 browser4-rest 的 MCPToolController 增加一个端点，并补 MCPToolControllerTest'
    },
    [pscustomobject]@{
        Id = 'path-rooted'
        Why = 'Workspace-relative path is read as-is; module inferred from path'
        Task = 'touch browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/DevTaskPlanner.kt'
    },
    [pscustomobject]@{
        Id = 'dependents-hijack'
        Why = 'DEPENDENTS-key mentions must not hijack build/test from the new plugin module'
        Task = '在 browser4-agentic、browser4-core/browser4-protocol、browser4-core/browser4-skeleton、browser4-pdk 的 DEPENDENTS 补 browser4-plugins/browser4-evalprobe2，并补 Evalprobe2ConfigTest'
    }
)

$selected = if ($Probe.Count -gt 0) {
    $known = $script:AllProbes | ForEach-Object { $_.Id }
    $unknown = $Probe | Where-Object { $_ -notin $known }
    if ($unknown) {
        Write-Host "Error: unknown probe id(s): $($unknown -join ', ')" -ForegroundColor Red
        Write-Host "Known probes: $($known -join ', ')" -ForegroundColor Yellow
        exit 2
    }
    $script:AllProbes | Where-Object { $_.Id -in $Probe }
} else {
    $script:AllProbes
}

# ===================================================================
# Run the probes
# ===================================================================

$ctx = @{}
$gitAvailable = $null -ne (Get-Command git -ErrorAction SilentlyContinue)
$gitBefore = ''
if ($gitAvailable) {
    $gitBefore = (& git -C $script:RepoRoot status --porcelain 2>$null | Out-String).Trim()
}

if (-not $Json) {
    Write-Rule "code devtask evaluation — repo $((Get-Content (Join-Path $script:RepoRoot 'VERSION') -TotalCount 1).Trim())"
    Write-Host "  launcher : $script:Launcher"
    Write-Host "  probes   : $($selected.Count) plan probe(s)$(if ($Fast) { ', -Fast' } else { ', --verify + determinism' })"
    Write-Host "  timeout  : $($script:TimeoutSec)s per invocation"
    Write-Host ''
}

$runStart = Get-Date

# ── Latency pair ────────────────────────────────────────────────────────────
# Run one fixed task twice back to back.  `DevTaskPlanner` re-scans the whole
# workspace tree on every call (ModuleGraph.scanPoms walks every file, then
# filters), so the first call of a session is dominated by cold file-system
# caches while the second reflects the steady state.  The warm number is the
# gate; the cold/warm delta is reported as evidence (see known gap K8).
$latencyTask = 'please improve the overall performance'
if (-not $Json) { Write-Host '  ▶ latency-pair    ' -NoNewline -ForegroundColor DarkGray }
$coldRun = Invoke-Cli -CliArgs @('cli', 'code', 'devtask', $latencyTask)
$warmRun = Invoke-Cli -CliArgs @('cli', 'code', 'devtask', $latencyTask)
if (-not $Json) {
    Write-Host ("cold {0:N2}s / warm {1:N2}s" -f $coldRun.Seconds, $warmRun.Seconds) -ForegroundColor DarkGray
}

# Transport floor: a trivial coding tool call in the same run. The absolute 3 s
# target was calibrated on a faster host; most of that budget is the pwsh
# wrapper + HTTP round-trip (`code workspace` alone costs ~3.5 s here), not
# planning. Grading the plan cost against the floor makes the gate
# host-independent: planning that re-scans the workspace adds seconds ON TOP of
# the floor, while a cached plan is floor + noise.
$floorRun = Invoke-Cli -CliArgs @('cli', 'code', 'workspace')
$floorSeconds = $floorRun.Seconds
if (-not $Json) { Write-Host ("  ▶ transport floor  {0:N2}s" -f $floorSeconds) -ForegroundColor DarkGray }

foreach ($p in $selected) {
    if (-not $Json) { Write-Host ("  ▶ {0,-18} " -f $p.Id) -NoNewline -ForegroundColor DarkGray }

    $r = Invoke-Cli -CliArgs @('cli', 'code', 'devtask', $p.Task)
    $plan = ConvertFrom-Plan -Text $r.Text

    $ctx[$p.Id] = [pscustomobject]@{
        Probe    = $p
        Result   = $r
        Plan     = $plan
        Tools    = (Get-PlanTool -Plan $plan)
    }

    if (-not $Json) {
        $state = if ($r.TimedOut) { 'TIMEOUT' } elseif ($r.ExitCode -ne 0) { "exit $($r.ExitCode)" } else { 'ok' }
        Write-Host ("{0,8:N2}s  {1}" -f $r.Seconds, $state) -ForegroundColor $(if ($state -eq 'ok') { 'DarkGray' } else { 'Red' })
    }
}

$planSeconds = @($ctx.Values | ForEach-Object { $_.Result.Seconds } | Where-Object { $_ -gt 0 })

# ===================================================================
# Gate 1..3: plan shape (applies to every probe output)
# ===================================================================

foreach ($id in $ctx.Keys) {
    $c = $ctx[$id]
    $bad = @($c.Plan.Steps | Where-Object { -not $_.Command })
    $orders = @($c.Plan.Steps | ForEach-Object { $_.Order })
    $expected = @(if ($c.Plan.Steps.Count -gt 0) { 1..$c.Plan.Steps.Count } else { @() })
    $parseOk = $null -ne $c.Plan.DeclaredSteps
    $countOk = $parseOk -and ($c.Plan.DeclaredSteps -eq $c.Plan.Steps.Count)
    $orderOk = ($orders -join ',') -eq ($expected -join ',')
    $commandOk = $bad.Count -eq 0

    Add-Gate -Id "G-EXIT[$id]" -Title "probe '$id' exits 0 and prints a plan" `
        -Passed ($c.Result.ExitCode -eq 0 -and $parseOk -and -not $c.Result.TimedOut) `
        -Evidence (Format-Evidence $c.Result.Text)

    Add-Gate -Id "G-SHAPE[$id]" -Title "steps 1..N, each with a [coding.*] tool and a command line" `
        -Passed ($parseOk -and $countOk -and $orderOk -and $commandOk) `
        -Evidence ("declared=$(if ($parseOk) { $c.Plan.DeclaredSteps } else { 'none' }) parsed=$($c.Plan.Steps.Count) " +
                   "orders=($($orders -join ',')) missingCommand=$($bad.Count)")

    $gitSteps = @($c.Plan.Steps | Where-Object { $_.Command -match '\bgit\s+(add|commit|push)\b' })
    Add-Gate -Id "G-NO-COMMIT[$id]" -Title "no git add/commit step (agents must not auto-commit)" `
        -Passed ($gitSteps.Count -eq 0) `
        -Evidence (Format-Evidence ($gitSteps | ForEach-Object { $_.Command }))
}

# ===================================================================
# Gate 4: probe-specific signal binding
# ===================================================================

function Get-Ctx { param([string]$Id) return $ctx[$Id] }

# --- driver-bare -----------------------------------------------------------
$c = Get-Ctx 'driver-bare'
if ($c) {
    $locateOrRead = @($c.Plan.Steps | Where-Object {
        $_.Tool -in @('coding.read', 'coding.listDir', 'coding.impact', 'coding.trapCheck')
    } | ForEach-Object { $_.Command })
    $boundToDriver = (@($locateOrRead | Where-Object { $_ -match 'browser4-core/browser4-browser' }).Count -gt 0)

    Add-Gate -Id 'G-BARE-CONTEXT' -Title 'bare driver filename grounds the change in browser4-core/browser4-browser' `
        -Passed $boundToDriver `
        -Evidence (Format-Evidence ($locateOrRead -join ' | '))

    $trapPaths = Get-StepCommand -Plan $c.Plan -Tool 'coding.trapCheck' |
        ForEach-Object { if ($_ -match 'path="([^"]+)"') { $Matches[1] } }
    $trapExists = @()
    foreach ($tp in $trapPaths) {
        $trapExists += (Test-Path (Join-Path $script:RepoRoot $tp))
    }
    Add-Gate -Id 'G-TRAP-PATH' -Title 'every trapCheck path exists on disk' `
        -Passed ($trapPaths.Count -gt 0 -and -not ($trapExists -contains $false)) `
        -Evidence ("paths=$(($trapPaths -join ', ')); exists=$(($trapExists -join ', '))")

    # Green baseline: the named test still binds to the module the task names.
    Add-Gate -Id 'G-TEST-BIND-DRIVER' -Title 'named test class binds to -pl browser4-rest with -Dtest' `
        -Passed (@($c.Plan.Steps | Where-Object {
            $_.Command -match '-Dtest=FillCursorTest' -and $_.Command -match '-pl browser4-rest' -and
            $_.Command -match '-Dsurefire.failIfNoSpecifiedTests=false'
        }).Count -gt 0) `
        -Evidence (Format-Evidence ((Get-StepCommand -Plan $c.Plan -Tool 'coding.shell') -join ' | '))
}

# --- existing-plugin ------------------------------------------------------
$c = Get-Ctx 'existing-plugin'
if ($c) {
    $build = Get-StepCommand -Plan $c.Plan -Tool 'coding.mvnBuild'
    Add-Gate -Id 'G-DEEPEST-BUILD' -Title 'mvnBuild targets the deepest module (browser4-plugins/browser4-seo)' `
        -Passed (@($build | Where-Object { $_ -match 'module="browser4-plugins/browser4-seo"' }).Count -gt 0) `
        -Evidence (Format-Evidence ($build -join ' | '))

    $testCmd = Get-StepCommand -Plan $c.Plan -Tool 'coding.shell'
    Add-Gate -Id 'G-TEST-SCOPE' -Title 'test step runs in the owning module with -Dtest and the -am tolerance flag' `
        -Passed (@($testCmd | Where-Object {
            $_ -match '-pl browser4-plugins/browser4-seo' -and $_ -match '-Dtest=SeoServiceTest' -and
            $_ -match '-Dsurefire.failIfNoSpecifiedTests=false'
        }).Count -gt 0) `
        -Evidence (Format-Evidence ($testCmd -join ' | '))

    $impact = Get-StepCommand -Plan $c.Plan -Tool 'coding.impact'
    Add-Gate -Id 'G-IMPACT-DEEPEST' -Title 'impact analysis targets the deepest module, not the aggregator' `
        -Passed (@($impact | Where-Object { $_ -match 'path="browser4-plugins/browser4-seo"' }).Count -gt 0) `
        -Evidence (Format-Evidence ($impact -join ' | '))

    Add-Gate -Id 'G-NO-AGGREGATOR-SIGNAL' -Title 'the aggregator browser4-plugins is not counted as an inferred module' `
        -Passed (-not ($c.Plan.Inferred -contains 'browser4-plugins')) `
        -Evidence ("Inferred modules: $($c.Plan.Inferred -join ', ')")
}

# --- new-plugin -----------------------------------------------------------
$c = Get-Ctx 'new-plugin'
if ($c) {
    $first = if ($c.Plan.Steps.Count -gt 0) { $c.Plan.Steps[0] } else { $null }
    Add-Gate -Id 'G-SCAFFOLD-FIRST' -Title 'scaffold step comes first and names the new module directory' `
        -Passed ($null -ne $first -and $first.Tool -eq 'coding.scaffoldToDir' -and
                 $first.Command -match 'dir="browser4-plugins/browser4-evalprobe"') `
        -Evidence (Format-Evidence $(if ($first) { $first.Command } else { '<no steps>' }))

    Add-Gate -Id 'G-NEW-MODULE-BUILD' -Title 'mvnBuild binds the new module, never a DEPENDENTS key' `
        -Passed ((@(Get-StepCommand -Plan $c.Plan -Tool 'coding.mvnBuild' | Where-Object {
            $_ -match 'module="browser4-plugins/browser4-evalprobe"' -and $_ -notmatch 'browser4-core/browser4-protocol'
        }).Count -gt 0)) `
        -Evidence (Format-Evidence ((Get-StepCommand -Plan $c.Plan -Tool 'coding.mvnBuild') -join ' | '))

    Add-Gate -Id 'G-NEW-MODULE-TEST' -Title 'test step runs in the new module with the named test class' `
        -Passed (@(Get-StepCommand -Plan $c.Plan -Tool 'coding.shell' | Where-Object {
            $_ -match '-pl browser4-plugins/browser4-evalprobe' -and $_ -match '-Dtest=EvalprobeServiceTest'
        }).Count -gt 0) `
        -Evidence (Format-Evidence ((Get-StepCommand -Plan $c.Plan -Tool 'coding.shell') -join ' | '))

    $reads = Get-StepCommand -Plan $c.Plan -Tool 'coding.read'
    Add-Gate -Id 'G-NEW-MODULE-PATH-PREFIX' -Title 'a slashed path in a new module is module-prefixed' `
        -Passed (@($reads | Where-Object { $_ -match 'path="browser4-plugins/browser4-evalprobe/src/main/resources/evalprobe/count\.js"' }).Count -gt 0) `
        -Evidence (Format-Evidence ($reads -join ' | '))
}

# --- cli-crate ------------------------------------------------------------
$c = Get-Ctx 'cli-crate'
if ($c) {
    Add-Gate -Id 'G-CARGO-STEP' -Title 'CLI crate task schedules cargo unit tests' `
        -Passed (Test-AnyCommand -Plan $c.Plan -Pattern 'cargo test --bin browser4-cli') `
        -Evidence (Format-Evidence ((Get-StepCommand -Plan $c.Plan -Tool 'coding.shell') -join ' | '))

    Add-Gate -Id 'G-CLI-NO-MVN' -Title 'CLI crate task does not fabricate a Maven module build' `
        -Passed (@(Get-StepCommand -Plan $c.Plan -Tool 'coding.mvnBuild').Count -eq 0) `
        -Evidence (Format-Evidence ((Get-StepCommand -Plan $c.Plan -Tool 'coding.mvnBuild') -join ' | '))

    Add-Gate -Id 'G-CLI-READ-PATH' -Title 'the touched CLI file is read as a workspace-relative path' `
        -Passed (Test-AnyCommand -Plan $c.Plan -Pattern 'path="cli/browser4-cli/src/commands\.rs"') `
        -Evidence (Format-Evidence ((Get-StepCommand -Plan $c.Plan -Tool 'coding.read') -join ' | '))
}

# --- no-signal ------------------------------------------------------------
$c = Get-Ctx 'no-signal'
if ($c) {
    Add-Gate -Id 'G-NOSIGNAL-TEXT' -Title 'a no-signal task says so in the Signals line' `
        -Passed ($c.Plan.Signals -match 'No module/file signals') `
        -Evidence (Format-Evidence $c.Plan.Signals)

    Add-Gate -Id 'G-NOSIGNAL-THIN' -Title 'a no-signal plan stays thin (governance validate only)' `
        -Passed ($c.Plan.Steps.Count -eq 1 -and $c.Plan.Steps[0].Tool -eq 'coding.validate') `
        -Evidence ("steps=$(($c.Tools) -join ', ')")
}

# --- prose-test -----------------------------------------------------------
$c = Get-Ctx 'prose-test'
if ($c) {
    Add-Gate -Id 'G-PROSE-NO-DTEST' -Title 'prose "test" does not become -Dtest scoping' `
        -Passed (-not (Test-AnyCommand -Plan $c.Plan -Pattern '-Dtest=')) `
        -Evidence (Format-Evidence ((Get-StepCommand -Plan $c.Plan -Tool 'coding.shell') -join ' | '))
}

# --- zh-mixed -------------------------------------------------------------
$c = Get-Ctx 'zh-mixed'
if ($c) {
    Add-Gate -Id 'G-CJK-MODULE' -Title 'CJK task text still resolves the module signal' `
        -Passed ($c.Plan.Signals -match 'modules: browser4-rest') `
        -Evidence (Format-Evidence $c.Plan.Signals)

    Add-Gate -Id 'G-CJK-DTEST' -Title 'CJK task text still scopes the named test class' `
        -Passed (@(Get-StepCommand -Plan $c.Plan -Tool 'coding.shell' | Where-Object {
            $_ -match '-pl browser4-rest' -and $_ -match '-Dtest=MCPToolControllerTest'
        }).Count -gt 0) `
        -Evidence (Format-Evidence ((Get-StepCommand -Plan $c.Plan -Tool 'coding.shell') -join ' | '))
}

# --- path-rooted ----------------------------------------------------------
$c = Get-Ctx 'path-rooted'
if ($c) {
    Add-Gate -Id 'G-ROOTED-PATH' -Title 'a workspace-relative path is read verbatim and its module inferred' `
        -Passed ((Test-AnyCommand -Plan $c.Plan -Pattern 'path="browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/DevTaskPlanner\.kt"') -and
                 $c.Plan.Signals -match 'modules: browser4-coding') `
        -Evidence (Format-Evidence "signals: $($c.Plan.Signals)")
}

# --- dependents-hijack ----------------------------------------------------
$c = Get-Ctx 'dependents-hijack'
if ($c) {
    Add-Gate -Id 'G-DEPENDENTS-NO-HIJACK' -Title 'DEPENDENTS-key mentions do not hijack build/test binding' `
        -Passed ((@(Get-StepCommand -Plan $c.Plan -Tool 'coding.mvnBuild' | Where-Object {
            $_ -match 'browser4-plugins/browser4-evalprobe2' -and $_ -notmatch 'browser4-core/browser4-protocol'
        }).Count -gt 0) -and
                 @(Get-StepCommand -Plan $c.Plan -Tool 'coding.shell' | Where-Object {
            $_ -match '-pl browser4-plugins/browser4-evalprobe2'
        }).Count -gt 0) `
        -Evidence (Format-Evidence (((Get-StepCommand -Plan $c.Plan -Tool 'coding.mvnBuild') +
                                     (Get-StepCommand -Plan $c.Plan -Tool 'coding.shell')) -join ' | '))
}

# ===================================================================
# Gate 5: determinism + read-only planning
# ===================================================================

if ($Fast -or -not (Get-Ctx 'existing-plugin')) {
    Add-Skip -Id 'G-DETERMINISTIC' -Title 'same task renders a byte-identical plan' `
        -Reason ($(if ($Fast) { '-Fast' } else { 'probe existing-plugin not selected' }))
} else {
    if (-not $Json) { Write-Host "  ▶ determinism      " -NoNewline -ForegroundColor DarkGray }
    $rerun = Invoke-Cli -CliArgs @('cli', 'code', 'devtask', $ctx['existing-plugin'].Probe.Task)
    if (-not $Json) { Write-Host ("{0,8:N2}s" -f $rerun.Seconds) -ForegroundColor DarkGray }
    Add-Gate -Id 'G-DETERMINISTIC' -Title 'same task renders a byte-identical plan' `
        -Passed ($rerun.Text -eq $ctx['existing-plugin'].Result.Text) `
        -Evidence ("first=$($ctx['existing-plugin'].Result.Text.Length) chars, second=$($rerun.Text.Length) chars")
}

if (-not $gitAvailable) {
    Add-Skip -Id 'G-READONLY' -Title 'planning leaves the working tree untouched' -Reason 'git not available'
} else {
    $gitAfter = (& git -C $script:RepoRoot status --porcelain 2>$null | Out-String).Trim()
    Add-Gate -Id 'G-READONLY' -Title 'planning leaves the working tree untouched (git status unchanged)' `
        -Passed ($gitBefore -eq $gitAfter) `
        -Evidence (Format-Evidence $(if ($gitBefore -eq $gitAfter) { 'clean/unchanged' } else { "before=<$gitBefore> after=<$gitAfter>" }))
}

# ===================================================================
# Gate 6: CLI argument contract
# ===================================================================

if (-not $Json) { Write-Host "  ▶ cli-contract     " -NoNewline -ForegroundColor DarkGray }

$missing = Invoke-Cli -CliArgs @('cli', 'code', 'devtask')
Add-Gate -Id 'G-ARG-MISSING' -Title 'omitting <task> fails with usage and a non-zero exit' `
    -Passed ($missing.ExitCode -ne 0 -and $missing.Text -match 'Missing required argument' -and $missing.Text -match 'code devtask') `
    -Evidence (Format-Evidence "exit=$($missing.ExitCode) $($missing.Text)")

$unknownAfter = Invoke-Cli -CliArgs @('cli', 'code', 'devtask', 'probe the argument parser', '--bogus')
Add-Gate -Id 'G-ARG-UNKNOWN' -Title 'an unknown option after the task is rejected with the supported list' `
    -Passed ($unknownAfter.ExitCode -ne 0 -and
             $unknownAfter.Text -match 'unknown option' -and
             $unknownAfter.Text -match '--verify') `
    -Evidence (Format-Evidence "exit=$($unknownAfter.ExitCode) $($unknownAfter.Text)")

$unknownBefore = Invoke-Cli -CliArgs @('cli', 'code', 'devtask', '--verfiy', 'fix a test in browser4-plugins/browser4-seo')
Add-Gate -Id 'G-ARG-UNKNOWN-BEFORE' -Title 'an unknown option before the task is diagnosed as an unknown option' `
    -Passed ($unknownBefore.ExitCode -ne 0 -and $unknownBefore.Text -match 'unknown option') `
    -Evidence (Format-Evidence "exit=$($unknownBefore.ExitCode) $($unknownBefore.Text)")

$helpOut = Invoke-Cli -CliArgs @('cli', '--help', 'code', 'devtask')
Add-Gate -Id 'G-HELP-OPTIONS' -Title 'help documents all three options' `
    -Passed ($helpOut.ExitCode -eq 0 -and $helpOut.Text -match '--verify' -and
             $helpOut.Text -match '--run-tests' -and $helpOut.Text -match '--execute') `
    -Evidence (Format-Evidence "exit=$($helpOut.ExitCode)")

$examples = Invoke-Cli -CliArgs @('cli', 'code', 'devtask', '--help', '--examples')
Add-Gate -Id 'G-HELP-EXAMPLES' -Title 'the advertised --examples flag actually has examples' `
    -Passed ($examples.ExitCode -eq 0 -and $examples.Text -notmatch 'No examples available') `
    -Evidence (Format-Evidence $examples.Text)

if (-not $Json) { Write-Host 'ok' -ForegroundColor DarkGray }

# ===================================================================
# Gate 7: --verify path (skipped with -Fast)
# ===================================================================

if ($Fast) {
    Add-Skip -Id 'G-VERIFY-SECTION' -Title '--verify renders a --- Verification --- section' -Reason '-Fast'
    Add-Skip -Id 'G-VERIFY-COMPILE' -Title '--verify compile sub-check really runs' -Reason '-Fast'
    Add-Skip -Id 'G-VERIFY-VERDICT' -Title '--verify surfaces failing sub-checks as a non-zero exit' -Reason '-Fast'
} else {
    if (-not $Json) { Write-Host "  ▶ verify           " -NoNewline -ForegroundColor DarkGray }
    $ver = Invoke-Cli -CliArgs @('cli', 'code', 'devtask', '--verify',
        'touch browser4-coding/src/main/kotlin/ai/platon/pulsar/coding/DevTaskPlanner.kt')
    if (-not $Json) { Write-Host ("{0,8:N2}s" -f $ver.Seconds) -ForegroundColor DarkGray }

    # Presence of the section only: the exit code is graded by
    # G-VERIFY-VERDICT, so fixing K4/K5 must not make this gate flap.
    Add-Gate -Id 'G-VERIFY-SECTION' -Title '--verify renders a --- Verification --- section' `
        -Passed ($ver.Text -match '--- Verification ---') `
        -Evidence ("exit=$($ver.ExitCode)")

    $compileBlocked = $ver.Text -match 'is not allowed \(safe=' -or $ver.Text -match 'Command blocked'
    Add-Gate -Id 'G-VERIFY-COMPILE' -Title '--verify compile sub-check really runs (not blocked by the shell allow-list)' `
        -Passed (-not $compileBlocked) `
        -Evidence (Format-Evidence $(if ($compileBlocked) { ($ver.Text -split '\r?\n' | Where-Object { $_ -match 'blocked|is not allowed' } | Select-Object -First 1) } else { 'compile ran' }))

    $subFailed = $ver.Text -match 'Build failed with exit' -or $ver.Text -match 'exit -1' -or
                 $ver.Text -match '✗' -or $ver.Text -match 'tests on .* \(exit [1-9]'
    Add-Gate -Id 'G-VERIFY-VERDICT' -Title '--verify surfaces failing sub-checks as a non-zero exit' `
        -Passed ((-not $subFailed) -or $ver.ExitCode -ne 0) `
        -Evidence ("sub-check failed=$subFailed, cli exit=$($ver.ExitCode)")
}

# ===================================================================
# Gate 8: structured verdict (P0.1–P0.3)
# ===================================================================

if (-not $Json) { Write-Host '  ▶ verdict         ' -NoNewline -ForegroundColor DarkGray }

$verdictOk = Invoke-Cli -CliArgs @('cli', 'code', 'verify', '--module', 'browser4-coding', '--checks', 'repo-consistency')
if (-not $Json) { Write-Host ("healthy {0:N2}s exit {1}" -f $verdictOk.Seconds, $verdictOk.ExitCode) -ForegroundColor DarkGray }
Add-Gate -Id 'G-VERDICT-PASS' -Title 'code verify on a healthy check exits 0 and prints a machine verdict' `
    -Passed ($verdictOk.ExitCode -eq 0 -and $verdictOk.Text -match 'VERDICT_JSON: ' -and
             $verdictOk.Text -match '"passed":true') `
    -Evidence (Format-Evidence (($verdictOk.Text -split '\r?\n' |
        Where-Object { $_ -match 'Verification:|VERDICT_JSON' } | Select-Object -First 1)))

$verdictFail = Invoke-Cli -CliArgs @('cli', 'code', 'verify', '--module', 'browser4-eval-no-such-module', '--checks', 'compile')
if (-not $Json) { Write-Host ("  failing {0:N2}s exit {1}" -f $verdictFail.Seconds, $verdictFail.ExitCode) -ForegroundColor DarkGray }
Add-Gate -Id 'G-VERDICT-FAIL' -Title 'a failing verdict exits non-zero and reports passed=false at the CLI' `
    -Passed ($verdictFail.ExitCode -ne 0 -and $verdictFail.Text -match '"passed":false') `
    -Evidence ("exit=$($verdictFail.ExitCode); " + (Format-Evidence (($verdictFail.Text -split '\r?\n' |
        Where-Object { $_ -match 'Verification:|failed' } | Select-Object -First 2) -join ' | ')))

# ===================================================================
# Gate 9: implementation contract + acceptance list (P1.1/P1.2)
# ===================================================================

$implCtx = Get-Ctx 'existing-plugin'
if (-not $implCtx) {
    Add-Skip -Id 'G-IMPL-STEP' -Title 'a plan with a signal carries an implement contract + acceptance list' `
        -Reason 'probe existing-plugin not selected'
} else {
    $implStep = @($implCtx.Plan.Steps | Where-Object { $_.Tool -eq 'coding.implement' })
    $hasAcceptance = $implCtx.Result.Text -match '(?m)^Acceptance: \['
    $implEvidence = "implement steps=$($implStep.Count); acceptance line=$hasAcceptance"
    if ($implStep.Count -gt 0) {
        $implEvidence = "$implEvidence; $($implStep[0].Command)"
    }
    Add-Gate -Id 'G-IMPL-STEP' -Title 'a plan with a signal carries an implement contract + acceptance list' `
        -Passed ($implStep.Count -eq 1 -and
                 $implStep[0].Purpose -match 'not mechanically executable' -and
                 $implStep[0].Command -match 'acceptance=' -and
                 $hasAcceptance) `
        -Evidence (Format-Evidence $implEvidence)
}

# ===================================================================
# Gate 10: protection + command validation safety probes (K9/K10)
# ===================================================================
#
# Both probes are self-healing: K9 restores the governance file if a regression
# ever lets the write through, and K10 targets a scratch directory under the
# git-ignored .browser4/, so a red gate can never damage the checkout.

if (-not $Json) { Write-Host '  ▶ safety          ' -NoNewline -ForegroundColor DarkGray }

$versionPath = Join-Path $script:RepoRoot 'VERSION'
$versionOriginal = Get-Content -LiteralPath $versionPath -Raw
$versionWrite = Invoke-Cli -CliArgs @('cli', 'code', 'write', 'VERSION', '0.0.0-eval-probe')
$versionAfter = Get-Content -LiteralPath $versionPath -Raw
$versionIntact = ($versionAfter -eq $versionOriginal)
if (-not $versionIntact) {
    Set-Content -LiteralPath $versionPath -Value $versionOriginal -NoNewline -Encoding utf8
}
Add-Gate -Id 'G-PROTECT' -Title 'a governance file refuses a direct write (K9 protection bypass)' `
    -Passed ($versionWrite.Text -match 'protected' -and $versionIntact) `
    -Evidence ("refused=$($versionWrite.Text -match 'protected'); file intact=$versionIntact")

# K10: every segment of a compound command must be validated. The default policy
# always denies network tools (allowNetwork=false), so a network command hidden
# behind a chain / pipe / semicolon is the clean probe: if the validator only
# looked at the head token, 'ls' would be allowed and curl would run.
# Command substitution cannot be probed through the pwsh wrapper (PowerShell
# consumes $() before the CLI sees it); CodingAgentShellTest covers that case.
$chainProbe = 'ls && curl -s http://127.0.0.1:1/'
$semicolonProbe = 'ls; curl -s http://127.0.0.1:1/'
$pipeProbe = 'ls | curl -s http://127.0.0.1:1/'
$chainResult = Invoke-Cli -CliArgs @('cli', 'code', 'shell', $chainProbe)
$semicolonResult = Invoke-Cli -CliArgs @('cli', 'code', 'shell', $semicolonProbe)
$pipeResult = Invoke-Cli -CliArgs @('cli', 'code', 'shell', $pipeProbe)
$isBlocked = { param($t) $t -match 'blocked|not allowed|requires network' }
$chainBlocked = & $isBlocked $chainResult.Text
$semicolonBlocked = & $isBlocked $semicolonResult.Text
$pipeBlocked = & $isBlocked $pipeResult.Text
$injectionEvidence = "&&=$chainBlocked ;=$semicolonBlocked |=$pipeBlocked; " +
    (($chainResult.Text -split '\r?\n' | Select-Object -First 1) -join ' ')
Add-Gate -Id 'G-INJECTION' -Title 'every segment of a compound command is validated (K10 injection)' `
    -Passed ($chainBlocked -and $semicolonBlocked -and $pipeBlocked) `
    -Evidence (Format-Evidence $injectionEvidence)

if (-not $Json) { Write-Host 'ok' -ForegroundColor DarkGray }

# ===================================================================
# Metrics + report
# ===================================================================

$script:Metrics['planSecondsMedian'] = if ($planSeconds.Count -gt 0) { [math]::Round(($planSeconds | Sort-Object)[[int][math]::Floor($planSeconds.Count / 2)], 2) } else { $null }
$script:Metrics['planSecondsMin'] = if ($planSeconds.Count -gt 0) { ($planSeconds | Measure-Object -Minimum).Minimum } else { $null }
$script:Metrics['planSecondsMax'] = if ($planSeconds.Count -gt 0) { ($planSeconds | Measure-Object -Maximum).Maximum } else { $null }
$script:Metrics['planSecondsAll'] = $planSeconds
$script:Metrics['coldSeconds'] = $coldRun.Seconds
$script:Metrics['warmSeconds'] = $warmRun.Seconds
$script:Metrics['transportFloorSeconds'] = $floorSeconds
$script:Metrics['coldWarmDelta'] = [math]::Round($coldRun.Seconds - $warmRun.Seconds, 2)
$script:Metrics['elapsedSeconds'] = [math]::Round(((Get-Date) - $runStart).TotalSeconds, 1)
$script:Metrics['probes'] = @($ctx.Keys)

# Planning must not cost more than ~1 s on top of the transport floor of the
# same run (see the floor measurement above): a plan call that re-scans the
# workspace shows up as seconds above it (eval K8).
$latencyBudget = $floorSeconds + 1.0
Add-Gate -Id 'G-PLAN-LATENCY' -Title 'a warm plan costs no more than the transport floor + 1s' `
    -Passed ($warmRun.Seconds -le $latencyBudget) `
    -Evidence ("cold=$($coldRun.Seconds)s warm=$($warmRun.Seconds)s floor=${floorSeconds}s budget=$([math]::Round($latencyBudget, 2))s " +
               "delta=$([math]::Round($coldRun.Seconds - $warmRun.Seconds, 2))s " +
               "probes min=$($script:Metrics['planSecondsMin'])s median=$($script:Metrics['planSecondsMedian'])s max=$($script:Metrics['planSecondsMax'])s")

$ceilingSample = @($coldRun.Seconds, $warmRun.Seconds) + $planSeconds
Add-Gate -Id 'G-PLAN-LATENCY-CEILING' -Title 'no plan-only call hangs (hard ceiling 90s)' `
    -Passed (($ceilingSample | Measure-Object -Maximum).Maximum -le 90) `
    -Evidence ("max=$(($ceilingSample | Measure-Object -Maximum).Maximum)s over $($ceilingSample.Count) call(s)")

$fail = @($script:Gates | Where-Object { $_.Status -eq 'FAIL' })
$known = @($script:Gates | Where-Object { $_.Status -eq 'KNOWN' })
$xpass = @($script:Gates | Where-Object { $_.Status -eq 'XPASS' })
$skip = @($script:Gates | Where-Object { $_.Status -eq 'SKIP' })
$pass = @($script:Gates | Where-Object { $_.Status -eq 'PASS' })

$exitCode = if ($fail.Count -gt 0) { 1 } elseif ($Strict -and $known.Count -gt 0) { 1 } else { 0 }

$verdict = if ($exitCode -eq 0) { 'PASS' } else { 'FAIL' }

$report = [ordered]@{
    eval        = 'code-devtask'
    repo        = $script:RepoRoot
    version     = (Get-Content (Join-Path $script:RepoRoot 'VERSION') -TotalCount 1).Trim()
    commit      = (& git -C $script:RepoRoot rev-parse --short HEAD 2>$null | Out-String).Trim()
    fast        = [bool]$Fast
    strict      = [bool]$Strict
    verdict     = $verdict
    counts      = [ordered]@{ pass = $pass.Count; fail = $fail.Count; known = $known.Count; xpass = $xpass.Count; skip = $skip.Count }
    metrics     = $script:Metrics
    gates       = @($script:Gates)
    startedAt   = $runStart.ToString('s')
}

if ($JsonPath) {
    $report | ConvertTo-Json -Depth 6 | Set-Content -Path $JsonPath -Encoding utf8
}

if ($Json) {
    $report | ConvertTo-Json -Depth 6
    exit $exitCode
}

# ── Human scorecard ─────────────────────────────────────────────────────────
Write-Host ''
Write-Rule 'Gate results'
foreach ($g in $script:Gates) {
    $color = switch ($g.Status) {
        'PASS'  { 'Green' }
        'FAIL'  { 'Red' }
        'KNOWN' { 'Yellow' }
        'XPASS' { 'Magenta' }
        default { 'DarkGray' }
    }
    $tag = if ($g.Gap) { "$($g.Status)/$($g.Gap)" } else { $g.Status }
    Write-Host ("  [{0,-9}] {1,-24} {2}" -f $tag, $g.Id, $g.Title) -ForegroundColor $color
    if ($g.Status -ne 'PASS' -and $g.Evidence) {
        Write-Host ("             ↳ $($g.Evidence)") -ForegroundColor DarkGray
    }
}

Write-Host ''
Write-Rule 'Summary'
Write-Host ("  gates    : {0} pass, {1} fail, {2} known gap, {3} unexpected pass, {4} skipped" -f `
    $pass.Count, $fail.Count, $known.Count, $xpass.Count, $skip.Count)
if ($null -ne $script:Metrics['planSecondsMedian']) {
    Write-Host ("  latency  : cold {0}s / warm {1}s (delta {2}s); probes median {3}s (min {4}s / max {5}s)" -f `
        $script:Metrics['coldSeconds'], $script:Metrics['warmSeconds'], $script:Metrics['coldWarmDelta'],
        $script:Metrics['planSecondsMedian'], $script:Metrics['planSecondsMin'],
        $script:Metrics['planSecondsMax'])
}
Write-Host ("  wall     : {0}s" -f $script:Metrics['elapsedSeconds'])
Write-Host ("  verdict  : {0}" -f $verdict) -ForegroundColor $(if ($verdict -eq 'PASS') { 'Green' } else { 'Red' })
if ($known.Count -gt 0 -and -not $Strict) {
    Write-Host "  note     : known gaps are registered in docs-dev/copilot/eval-code-devtask.md — rerun with -Strict to make them fatal" -ForegroundColor DarkGray
}
if ($xpass.Count -gt 0) {
    Write-Host "  note     : an XPASS gate means a registered gap no longer reproduces — delete its K entry and refresh the baseline" -ForegroundColor Magenta
}

exit $exitCode
