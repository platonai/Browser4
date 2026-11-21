#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Self-tests for the permission end-to-end harness (manifest + assertion engine).

.DESCRIPTION
    Deliberately Pester-free: `pwsh bin/test.ps1 ps` runs every `*.tests.ps1` in the repo with
    `pwsh -File` when the file has no `Describe`/`Context` block, so this runs in the PowerShell
    scope of the quality gate without a module dependency.

    What it pins:
      - the manifest is well formed (unique ids, known tiers/intents, resolvable twins);
      - every `{placeholder}` a case uses is one the runner supplies;
      - the assertion engine really distinguishes PASS / FAIL / INVALID, so a green suite means
        something (a deny whose layer check trips is INVALID, never PASS);
      - canary expectations resolve to the paths the runner actually creates.
#>
[CmdletBinding()]
param()

$ErrorActionPreference = 'Stop'
$script:FailedCount = 0
$script:ChecksRun = 0

function Assert-True {
    param([bool]$Condition, [string]$Name, [string]$Detail = '')
    $script:ChecksRun++
    if ($Condition) {
        Write-Host ("  ok   {0}" -f $Name) -ForegroundColor DarkGreen
    } else {
        $script:FailedCount++
        Write-Host ("  FAIL {0} {1}" -f $Name, $Detail) -ForegroundColor Red
    }
}

function Assert-Equal {
    param($Expected, $Actual, [string]$Name)
    Assert-True -Condition ($Expected -eq $Actual) -Name $Name -Detail ("expected=[{0}] actual=[{1}]" -f $Expected, $Actual)
}

Write-Host "harness: $PSScriptRoot" -ForegroundColor Cyan

# The runner returns early when dot-sourced, so this only loads its functions.
. (Join-Path $PSScriptRoot 'run-permission-e2e.ps1')

# --------------------------------------------------------------------------------------
Write-Host "`n[manifest]"
# --------------------------------------------------------------------------------------

$manifest = Get-Content -Path (Join-Path $PSScriptRoot 'cases.json') -Raw | ConvertFrom-Json
$caseList = @($manifest.cases)

Assert-True -Condition ($caseList.Count -ge 20) -Name "at least 20 cases ($($caseList.Count))"
Assert-True -Condition (($caseList.id | Sort-Object -Unique).Count -eq $caseList.Count) -Name 'ids are unique'

$knownTiers = @('A', 'B', 'C')
$knownIntents = @('allow', 'deny', 'report')
$ids = $caseList.id
foreach ($testCase in $caseList) {
    Assert-True -Condition ($knownTiers -contains $testCase.tier) -Name "tier of $($testCase.id)" -Detail ([string]$testCase.tier)
    Assert-True -Condition ($knownIntents -contains $testCase.intent) -Name "intent of $($testCase.id)" -Detail ([string]$testCase.intent)
    Assert-True -Condition (@('coding', 'browse') -contains $testCase.mode) -Name "mode of $($testCase.id)" -Detail ([string]$testCase.mode)

    if ($testCase.tier -eq 'A') {
        Assert-True -Condition (@($testCase.steps).Count -gt 0) -Name "steps of $($testCase.id)"
        foreach ($step in @($testCase.steps)) {
            Assert-True -Condition (-not [string]::IsNullOrWhiteSpace([string]$step.tool)) -Name "step tool of $($testCase.id)"
            Assert-True -Condition ($null -ne $step.expect) -Name "step expectation of $($testCase.id)"
        }
    } else {
        # A case is either one agent run or a `phases` chain (resume / grant-isolation cases).
        $phases = @($testCase.phases | Where-Object { $_ })
        $runs = if ($phases.Count -gt 0) { $phases } else { @($testCase) }
        foreach ($run in $runs) {
            $agent = if ($phases.Count -gt 0) { $run.agent } else { $testCase.agent }
            $expect = if ($phases.Count -gt 0) { $run.expect } else { $testCase.expect }
            Assert-True -Condition ($null -ne $agent -and -not [string]::IsNullOrWhiteSpace([string]$agent.task)) -Name "agent task of $($testCase.id)"
            Assert-True -Condition ($null -ne $expect) -Name "expectation of $($testCase.id)"
        }
        if ($phases.Count -gt 1) {
            # A phase chain can only pass `{prevTaskId}` along when the first phase ran a task.
            Assert-True -Condition ($runs.Count -ge 2) -Name "phases of $($testCase.id) are a chain"
        }
    }

    if ($testCase.twin) {
        Assert-True -Condition ($ids -contains $testCase.twin) -Name "twin of $($testCase.id) resolves" -Detail ([string]$testCase.twin)
    }
    foreach ($referenced in @($testCase.twinOf)) {
        if (-not $referenced) { continue }
        Assert-True -Condition ($ids -contains $referenced) -Name "twinOf of $($testCase.id) resolves" -Detail ([string]$referenced)
    }
    if ($testCase.control) {
        # `control` replaces the twin for a case whose boundary cannot be removed per call
        # (fs.read's readable roots) or that answers with a decision (requestAccess).
        $control = [string]$testCase.control
        $resolves = $control.StartsWith('in-case') -or ($ids -contains $control)
        Assert-True -Condition $resolves -Name "control of $($testCase.id) is 'in-case' or a case id" -Detail $control
    }
}

# Every deny case needs a control, or the deny proves nothing.
$denyWithoutTwin = @($caseList | Where-Object {
        $_.intent -eq 'deny' -and -not $_.twin -and @($_.twinOf).Count -eq 0 -and
        -not $_.control -and $_.scored -ne 'advisory'
    })
Assert-True -Condition ($denyWithoutTwin.Count -eq 0) -Name 'every gating deny case has a twin or an in-case control' -Detail (($denyWithoutTwin | ForEach-Object { $_.id }) -join ', ')

# --------------------------------------------------------------------------------------
Write-Host "`n[placeholders]"
# --------------------------------------------------------------------------------------

$knownPlaceholders = @(
    'canary', 'canaryDir', 'repoRoot', 'probeDir', 'stageScript', 'grantedDir', 'grantedDirB',
    'outsideFile', 'junction', 'junctionCanary', 'userHome', 'prevTaskId', 'm2Cache', 'fixtureUrl',
    'runDir', 'liveBackend', 'cliExe'
)
$usedPlaceholders = New-Object System.Collections.Generic.HashSet[string]
foreach ($testCase in $caseList) {
    $texts = @()
    foreach ($step in @($testCase.steps)) { $texts += ($step | ConvertTo-Json -Depth 8 -Compress) }
    if ($testCase.agent) { $texts += ($testCase.agent | ConvertTo-Json -Depth 8 -Compress) }
    if ($testCase.expect) { $texts += ($testCase.expect | ConvertTo-Json -Depth 8 -Compress) }
    if ($testCase.phases) { $texts += ($testCase.phases | ConvertTo-Json -Depth 8 -Compress) }
    foreach ($match in ((($texts -join "`n")) | Select-String -Pattern '\{([A-Za-z][A-Za-z0-9]*)\}' -AllMatches).Matches) {
        [void]$usedPlaceholders.Add($match.Groups[1].Value)
    }
}
foreach ($placeholder in $usedPlaceholders) {
    Assert-True -Condition ($knownPlaceholders -contains $placeholder) -Name "placeholder {$placeholder} is supplied by the runner"
}
# A declared placeholder nobody uses is dead weight; a case set that stopped using the canary is
# anti-vacuous evidence that the suite drifted.
foreach ($placeholder in @('canary', 'outsideFile', 'junctionCanary', 'grantedDirB', 'prevTaskId', 'userHome', 'cliExe')) {
    Assert-True -Condition ($usedPlaceholders.Contains($placeholder)) -Name "placeholder {$placeholder} is actually used"
}

$context = @{ canary = 'C:\tmp\c.txt'; canaryDir = 'C:\tmp'; grantedDir = 'C:\g'; liveBackend = 'windows-low-il' }
Assert-Equal -Expected 'C:\tmp\c.txt' -Actual (Expand-Placeholders -Text '{canary}' -Context $context) -Name 'expand {canary}'
Assert-Equal -Expected 'write C:\g\grant.txt' -Actual (Expand-Placeholders -Text 'write {grantedDir}\grant.txt' -Context $context) -Name 'expand {grantedDir}'
Assert-Equal -Expected 'OS sandbox: windows-low-il' -Actual (Expand-Placeholders -Text 'OS sandbox: {liveBackend}' -Context $context) -Name 'expand {liveBackend}'

# --------------------------------------------------------------------------------------
Write-Host "`n[cli advisories]"
# --------------------------------------------------------------------------------------

# A refusal is judged from the first *meaningful* line, and `Invoke-Native` merges stdout+stderr, so
# the CLI's own advisories would otherwise push the marker off the head. Measured 2026-11-20: with an
# edited-but-not-rebuilt bundle, `A05/A06/A17` reported `ok=True` for calls the sandbox had refused.
$withAdvisories = "Backend sources are newer than the runtime bundle; the next backend command rebuilds it automatically (a few minutes).`nbrowser4-cli: warning: cannot write CLI state to C:\x (permission denied)`n✗ coding_shell FAILED · exit 1"
$stripped = Remove-CliAdvisoryHead -Text $withAdvisories
Assert-True -Condition ($stripped.StartsWith('✗')) -Name 'advisory head is skipped, so the refusal stays leading'
Assert-True -Condition ($stripped -notmatch 'newer than the runtime bundle') -Name 'the advisory itself is dropped'

$report = "coding_permissions report`nPERMISSION_DENIED | code=PATH_DENIED"
Assert-Equal -Expected $report -Actual (Remove-CliAdvisoryHead -Text $report) -Name 'a report that merely mentions a code is left alone'
Assert-Equal -Expected '' -Actual (Remove-CliAdvisoryHead -Text '') -Name 'empty text stays empty'
Assert-Equal -Expected '' -Actual (Remove-CliAdvisoryHead -Text "browser4-cli: note: moved 1 CLI state file(s)`r`n⚠ using another directory") -Name 'all-advisory text yields empty, not a header'

# --------------------------------------------------------------------------------------
Write-Host "`n[assertion engine]"
# --------------------------------------------------------------------------------------

$temp = Join-Path ([IO.Path]::GetTempPath()) ("b4-perm-e2e-tests-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $temp -Force | Out-Null
try {
    $canary = Join-Path $temp 'escape.txt'
    $baseContext = @{ canary = $canary; canaryDir = $temp; grantedDir = $temp }

    # A deny that held: refusal code present, canary absent.
    $denyText = 'PERMISSION_DENIED | code=SANDBOX_DENIED | tool=coding.shell | detail=the OS denied access'
    $denyExpect = [pscustomobject]@{ ok = $false; resultContains = @('code=SANDBOX_DENIED'); canaryAbsent = $true }
    $ctx = $baseContext.Clone(); $ctx['resultText'] = $denyText; $ctx['toolOk'] = $false
    $evaluated = Get-ExpectationChecks -Expect $denyExpect -Context $ctx
    Assert-Equal -Expected 'PASS' -Actual (Get-Verdict -Checks $evaluated) -Name 'deny that held is PASS'

    # The same refusal text with a canary on disk is a real escape: FAIL.
    Set-Content -Path $canary -Value 'escaped'
    $verdict = Get-Verdict -Checks (Get-ExpectationChecks -Expect $denyExpect -Context $ctx)
    Assert-Equal -Expected 'FAIL' -Actual $verdict -Name 'deny with a canary present is FAIL'
    Remove-Item -LiteralPath $canary -Force

    # A policy pre-emption where the OS was required means the OS was never exercised: INVALID.
    $policyText = 'PERMISSION_DENIED | code=PATH_OUTSIDE_WORKSPACE | tool=coding.write'
    $layerExpect = [pscustomobject]@{
        ok                     = $false
        resultContains         = @('code=')
        forbidResultContains   = @('code=PATH_OUTSIDE_WORKSPACE')
        canaryAbsent           = $true
    }
    $ctx['resultText'] = $policyText
    $verdict = Get-Verdict -Checks (Get-ExpectationChecks -Expect $layerExpect -Context $ctx)
    Assert-Equal -Expected 'INVALID' -Actual $verdict -Name 'policy pre-emption of an OS case is INVALID'

    # An unevaluated check (no backend log available) must not turn into a pass.
    $ctx['resultText'] = $denyText
    $logExpect = [pscustomobject]@{ logContains = @('code=SANDBOX_DENIED') }
    $ctxNoLog = $ctx.Clone(); $ctxNoLog['logDelta'] = ''
    Assert-Equal -Expected 'PASS?' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $logExpect -Context $ctxNoLog)) -Name 'unevaluated log check is PASS?'
    $ctxWithLog = $ctx.Clone(); $ctxWithLog['logDelta'] = 'coding access denied: tool=coding.shell code=SANDBOX_DENIED'
    Assert-Equal -Expected 'PASS' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $logExpect -Context $ctxWithLog)) -Name 'log check evaluates when the log is readable'

    # Tier B: tool results are matched by name fragment, and ok/text are asserted on the match.
    $toolResults = @(
        [pscustomobject]@{ Tool = 'coding.shell'; Ok = $false; Text = $denyText },
        [pscustomobject]@{ Tool = 'coding.read'; Ok = $true; Text = 'file content' }
    )
    $agentExpect = [pscustomobject]@{
        taskCompleted = $true
        toolResult    = [pscustomobject]@{ tool = 'shell'; ok = $false; resultContains = @('code=SANDBOX_DENIED') }
    }
    $ctxB = $baseContext.Clone()
    $ctxB['toolResults'] = $toolResults
    $ctxB['agentCompleted'] = $true
    $ctxB['toolOk'] = $false
    $ctxB['resultText'] = $denyText
    Assert-Equal -Expected 'PASS' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $agentExpect -Context $ctxB)) -Name 'Tier B toolResult matches by fragment'

    # A tool the case expected but the agent never called means the path was not exercised:
    # that is INVALID (the case proves nothing), not a red that looks like a boundary failure.
    $missingTool = [pscustomobject]@{ toolResult = [pscustomobject]@{ tool = 'browser.click'; ok = $true } }
    Assert-Equal -Expected 'INVALID' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $missingTool -Context $ctxB)) -Name 'a tool that never ran is INVALID'

    $absentExpect = [pscustomobject]@{ toolResultAbsent = [pscustomobject]@{ tool = 'shell' } }
    Assert-Equal -Expected 'FAIL' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $absentExpect -Context $ctxB)) -Name 'toolResultAbsent fails when the tool ran'

    # `--access read` must not execute a shell at all.
    $readExpect = [pscustomobject]@{ toolResultAbsent = [pscustomobject]@{ tool = 'shell' } }
    $ctxRead = $baseContext.Clone()
    $ctxRead['toolResults'] = @([pscustomobject]@{ Tool = 'coding.write'; Ok = $false; Text = 'READ_ONLY' })
    Assert-Equal -Expected 'PASS' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $readExpect -Context $ctxRead)) -Name 'toolResultAbsent passes when the tool is gated'

    # canaryPresent with a path string, and the grant/scope pairing.
    $grantPath = Join-Path $temp 'grant.txt'
    Set-Content -Path $grantPath -Value 'ok'
    $presentExpect = [pscustomobject]@{ canaryPresent = $grantPath }
    Assert-Equal -Expected 'PASS' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $presentExpect -Context $baseContext)) -Name 'canaryPresent with an absolute path'
    Remove-Item -LiteralPath $grantPath -Force

    # Tool matching is suffix-based: `shell` must not match `coding.shellSetEnv`, or a deny case
    # would assert against the result of the *setup* call instead of the escape.
    Assert-True -Condition (Test-ToolMatch -Tool 'coding.shell' -Fragment 'shell') -Name 'tool match: coding.shell == shell'
    Assert-True -Condition (Test-ToolMatch -Tool 'coding.shellSetEnv' -Fragment 'coding.shellSetEnv') -Name 'tool match: exact name'
    Assert-True -Condition (-not (Test-ToolMatch -Tool 'coding.shellSetEnv' -Fragment 'shell')) -Name 'tool match: shellSetEnv is not the shell'
    Assert-True -Condition (Test-ToolMatch -Tool 'coding_permissions' -Fragment 'permissions') -Name 'tool match: coding_permissions == permissions'

    $trapExpect = [pscustomobject]@{
        toolResult = [pscustomobject]@{ tool = 'shell'; ok = $false; resultContains = @('code=SANDBOX_DENIED') }
    }
    $ctxTrap = $baseContext.Clone()
    $ctxTrap['toolResults'] = @(
        [pscustomobject]@{ Tool = 'coding.shellSetEnv'; Ok = $true; Text = 'Environment variable set' },
        [pscustomobject]@{ Tool = 'coding.shell'; Ok = $false; Text = 'PERMISSION_DENIED | code=SANDBOX_DENIED' }
    )
    Assert-Equal -Expected 'PASS' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $trapExpect -Context $ctxTrap)) -Name 'shellSetEnv does not satisfy a shell assertion'

    # A leak that must never appear (PROBE_ESCAPED / PRIVATE KEY) is a plain failure, not a layer question.
    $leakExpect = [pscustomobject]@{ forbidResultContainsAny = @('PROBE_ESCAPED', 'PRIVATE KEY') }
    $ctxLeak = $baseContext.Clone()
    $ctxLeak['resultText'] = 'PROBE_ESCAPED: C:\tmp\canary.txt'
    Assert-Equal -Expected 'FAIL' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $leakExpect -Context $ctxLeak)) -Name 'forbidResultContainsAny detects a leak'
    $ctxClean = $baseContext.Clone()
    $ctxClean['resultText'] = 'PROBE_DENIED: Access is denied.'
    Assert-Equal -Expected 'PASS' -Actual (Get-Verdict -Checks (Get-ExpectationChecks -Expect $leakExpect -Context $ctxClean)) -Name 'forbidResultContainsAny passes on a clean refusal'

    # A tool-result event without an `ok` flag must not collapse the evidence into a dangling
    # `actual=[`: the operator has to see "not reported", not an empty list.
    $nullOkExpect = [pscustomobject]@{ toolResult = [pscustomobject]@{ tool = 'shell'; ok = $false } }
    $ctxNullOk = $baseContext.Clone()
    $ctxNullOk['toolResults'] = @([pscustomobject]@{ Tool = 'coding.shell'; Ok = $null; Text = 'no flag' })
    $nullOkChecks = Get-ExpectationChecks -Expect $nullOkExpect -Context $ctxNullOk
    $nullOkEvidence = ($nullOkChecks | Where-Object { $_.Name -like 'toolResult:*ok=*' }).Evidence
    Assert-True -Condition ($nullOkEvidence -match 'actual=\[\?\]') -Name 'a null tool-result flag renders as ? rather than a dangling actual=[' -Detail $nullOkEvidence
} finally {
    Remove-Item -LiteralPath $temp -Recurse -Force -ErrorAction SilentlyContinue
}

# --------------------------------------------------------------------------------------
Write-Host "`n[backend log discovery]"
# --------------------------------------------------------------------------------------

# The runner reads the backend log delta for layer attribution. The live file is picked by its
# *content* because Windows keeps a writer's mtime stale; the parser must handle both logback
# layouts (`yyyy-MM-dd HH:mm:ss.SSS` from the app log, `HH:mm:ss.SSS` from the startup log).
$logTemp = Join-Path ([IO.Path]::GetTempPath()) ("b4-perm-e2e-log-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $logTemp -Force | Out-Null
try {
    $appLog = Join-Path $logTemp 'browser4-server-jar-port18182-test.log'
    @(
        'args: --server.port=18182',
        '2026-11-20 11:01:27.696  INFO [r-worker-8] a.p.p.c.PermissionAudit - coding access denied: tool=coding.shell code=SANDBOX_DENIED',
        '2026-11-20 11:08:15.700  INFO [r-worker-6] a.p.p.c.SandboxDenials - sandbox denied: sandbox=windows-low-il tool=coding.shell'
    ) | Set-Content -LiteralPath $appLog -Encoding utf8
    $appStamp = Get-LogFileTimestamp -Path $appLog
    Assert-True -Condition ($null -ne $appStamp) -Name 'the newest logback timestamp is parsed'
    Assert-Equal -Expected '2026-11-20 11:08:15' -Actual ($appStamp.ToString('yyyy-MM-dd HH:mm:ss')) -Name 'the last entry wins, not the first'

    $startupLog = Join-Path $logTemp 'pulsar.log'
    @('10:55:24.330 [main] INFO  a.p.p.c.s.SandboxDefaults - sandbox configured explicitly: windows-low-il') |
        Set-Content -LiteralPath $startupLog -Encoding utf8
    $startupStamp = Get-LogFileTimestamp -Path $startupLog
    Assert-True -Condition ($null -ne $startupStamp -and $startupStamp.Hour -eq 10 -and $startupStamp.Minute -eq 55) -Name 'a date-less startup timestamp is parsed'

    $garbage = Join-Path $logTemp 'garbage.log'
    @('no timestamps here', 'just prose') | Set-Content -LiteralPath $garbage -Encoding utf8
    Assert-True -Condition ($null -eq (Get-LogFileTimestamp -Path $garbage)) -Name 'a log with no timestamp yields null, not a guess'

    # Newest content wins even when the older file carries a newer mtime.
    $staleContent = Join-Path $logTemp 'stale-content.log'
    @('2026-11-19 09:00:00.000  INFO stale') | Set-Content -LiteralPath $staleContent -Encoding utf8
    (Get-Item $staleContent).LastWriteTime = (Get-Date)
    $selected = Select-NewestLogFile -Files @((Get-Item $staleContent), (Get-Item $appLog))
    Assert-Equal -Expected $appLog -Actual $selected.File -Name 'the newest content wins over a newer mtime'

    # `$env:TEMP` does not exist on the Linux/macOS runners. A `Join-Path $env:TEMP ...` in the
    # candidate scan threw "Cannot bind argument to parameter 'Path' because it is null" and took
    # the whole self-test down before the retention checks ever ran. The fixture lives in its own
    # root so the newer-stamped log it plants cannot win a later assertion.
    $previousTemp = $env:TEMP
    $previousTmpDir = $env:TMPDIR
    $captureRoot = Join-Path ([IO.Path]::GetTempPath()) ("b4-perm-e2e-cap-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
    try {
        $captureDir = Join-Path (Join-Path $captureRoot 'browser4') 'browser4-cli'
        New-Item -ItemType Directory -Path $captureDir -Force | Out-Null
        $captureLog = Join-Path $captureDir 'browser4-server-port18182-test.log'
        @('12:00:00.000 [main] INFO started') | Set-Content -LiteralPath $captureLog -Encoding utf8
        $env:TEMP = $null
        $env:TMPDIR = $captureRoot
        $discovered = @(Find-BackendLogFiles -RepoRoot $captureRoot)
        Assert-True -Condition ($discovered -contains $captureLog) -Name 'the log scan falls back to TMPDIR when TEMP is unset'
    } finally {
        if ($null -eq $previousTemp) { Remove-Item env:TEMP -ErrorAction SilentlyContinue } else { $env:TEMP = $previousTemp }
        if ($null -eq $previousTmpDir) { Remove-Item env:TMPDIR -ErrorAction SilentlyContinue } else { $env:TMPDIR = $previousTmpDir }
        Remove-Item -LiteralPath $captureRoot -Recurse -Force -ErrorAction SilentlyContinue
    }

    $previous = $env:BROWSER4_SERVER_LOG_DIR
    try {
        $env:BROWSER4_SERVER_LOG_DIR = $logTemp
        $resolved = Resolve-LogDir -Requested 'auto' -RepoRoot $logTemp -Fallback $logTemp
        Assert-Equal -Expected 'auto' -Actual $resolved.Source -Name 'auto discovery reports its source'
        # The env var's directory has to be scanned. Asserting that it *wins* the content-based
        # selection only holds on a host with no other backend log - a developer machine keeps
        # `%TEMP%\browser4\browser4-cli` captures that can carry a newer stamp than this fixture.
        $fromEnvDir = @($resolved.Files | Where-Object { $_ -and (Split-Path -Parent $_) -eq $logTemp })
        Assert-True -Condition ($fromEnvDir -contains $appLog) -Name 'BROWSER4_SERVER_LOG_DIR participates in discovery'
        $explicit = Resolve-LogDir -Requested $logTemp -RepoRoot $logTemp -Fallback 'C:\nope'
        Assert-Equal -Expected 'explicit' -Actual $explicit.Source -Name 'an explicit -LogDir is not second-guessed'
    } finally {
        if ($null -eq $previous) { Remove-Item env:BROWSER4_SERVER_LOG_DIR -ErrorAction SilentlyContinue }
        else { $env:BROWSER4_SERVER_LOG_DIR = $previous }
    }
} finally {
    Remove-Item -LiteralPath $logTemp -Recurse -Force -ErrorAction SilentlyContinue
}

# --------------------------------------------------------------------------------------
Write-Host "`n[run retention]"
# --------------------------------------------------------------------------------------

$runTemp = Join-Path ([IO.Path]::GetTempPath()) ("b4-perm-e2e-runs-" + [guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $runTemp -Force | Out-Null
try {
    foreach ($name in 'run-20261120-010101', 'run-20261120-020202', 'run-20261120-030303', 'run-20261120-040404', 'run-20261120-050505') {
        New-Item -ItemType Directory -Path (Join-Path $runTemp $name) -Force | Out-Null
    }
    $currentRun = Join-Path $runTemp 'run-20261120-050505'
    $removed = @(Remove-OldRunDirs -Root $runTemp -KeepCount 3 -Current $currentRun)
    Assert-Equal -Expected 2 -Actual $removed.Count -Name 'retention prunes everything past the newest N'
    Assert-True -Condition (Test-Path -LiteralPath $currentRun) -Name 'the current run is never pruned'
    Assert-Equal -Expected 3 -Actual @(Get-ChildItem -LiteralPath $runTemp -Directory).Count -Name 'Keep 3 leaves exactly three run directories'
} finally {
    Remove-Item -LiteralPath $runTemp -Recurse -Force -ErrorAction SilentlyContinue
}

# --------------------------------------------------------------------------------------
Write-Host ''
if ($script:FailedCount -gt 0) {
    Write-Host ("{0}/{1} checks failed" -f $script:FailedCount, $script:ChecksRun) -ForegroundColor Red
    exit 1
}
Write-Host ("all {0} checks passed" -f $script:ChecksRun) -ForegroundColor Green
exit 0
