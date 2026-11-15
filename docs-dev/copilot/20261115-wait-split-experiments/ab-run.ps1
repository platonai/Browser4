param(
    [Parameter(Mandatory = $true)][ValidateSet('baseline', 'speculative')][string]$Arm,
    [Parameter(Mandatory = $true)][int]$Attempt,
    # The backend inherits this script's stdout handle, so a chain of runs deadlocks on the
    # first pipe that never closes. Stopping it here closes the pipe; the next run starts a
    # fresh JVM, which is what the arm switch needs anyway.
    [switch]$StopBackendWhenDone
)

$ErrorActionPreference = 'Continue'
$repo = 'D:\workspace\Browser4\Browser4-4.16'
$ws = 'D:\tmp\b4-ab'
$outDir = 'D:\tmp\ab-results'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Set-Location $repo

$TASK = 'Run coding.mvnBuild with module "app" and goals "compile" and report its exit code. ' +
        'Then in app/src/main/java/demo/App.java add a second println line that prints Lib.add(5, 7). ' +
        'Then run coding.mvnBuild with module "app" and goals "compile" again and report its exit code. ' +
        'Then run coding.verify with checks "compile" and module "app". ' +
        'Reply DONE only when both builds and the verify succeeded.'

$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
$specFlag = if ($Arm -eq 'speculative') { 'true' } else { 'false' }
$env:BROWSER4_SERVER_OPTS = "-Dbrowser4.agent.workspace=$ws -Dbrowser4.agent.finishGateCheck=warn -Dbrowser4.agent.build.speculative=$specFlag"

function Get-BackendPid {
    $c = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -eq 8283 } | Select-Object -First 1
    if ($c) { return $c.OwningProcess } else { return $null }
}

# One arm per server JVM: the feature switch is a system property, so the backend has to
# be restarted when the arm changes (never between two attempts of the same arm).
$armFile = Join-Path $outDir 'current-arm.txt'
$currentArm = if (Test-Path $armFile) { (Get-Content $armFile -Raw).Trim() } else { '' }
if ($currentArm -ne $Arm) {
    $pid8283 = Get-BackendPid
    if ($pid8283) {
        Write-Output "arm change $currentArm -> $Arm : stopping backend pid $pid8283"
        Stop-Process -Id $pid8283 -Force -ErrorAction SilentlyContinue
        for ($i = 0; $i -lt 30; $i++) { Start-Sleep -Milliseconds 500; if (-not (Get-BackendPid)) { break } }
    }
    Set-Content -Path $armFile -Value $Arm -NoNewline
    Write-Output "arm now: $Arm (speculative=$specFlag)"
}

# Identical start state for every attempt: seed commit, no leftover worktree, no target/.
git -C $ws worktree prune 2>&1 | Out-Null
if (Test-Path "$ws\.browser4\worktrees") { Remove-Item "$ws\.browser4\worktrees" -Recurse -Force -ErrorAction SilentlyContinue }
git -C $ws reset --hard -q 2>&1 | Out-Null
git -C $ws clean -fdxq 2>&1 | Out-Null
$head = (git -C $ws rev-parse --short HEAD)
$dirty = @(git -C $ws status --porcelain).Count
Write-Output "workspace reset: head=$head dirtyFiles=$dirty"

$runLog = Join-Path $outDir ("{0}-{1}-run.log" -f $Arm, $Attempt)
$runOut = & pwsh "$repo\b4w.ps1" agent run $TASK --coding --follow 2>&1
$runOut | Out-File -FilePath $runLog -Encoding utf8
$runExit = $LASTEXITCODE

$taskId = $null
foreach ($line in $runOut) {
    if ("$line" -match 'Task submitted:\s*([0-9a-fA-F-]{36})') { $taskId = $Matches[1]; break }
}
Write-Output "run exit=$runExit taskId=$taskId log=$runLog"
if (-not $taskId) { Write-Output 'NO TASK ID — aborting'; exit 2 }

$jsonFile = Join-Path $outDir ("{0}-{1}-result.json" -f $Arm, $Attempt)
$jsonOut = & pwsh "$repo\b4w.ps1" agent result $taskId --json --allow-failed 2>&1
$jsonOut | Out-File -FilePath $jsonFile -Encoding utf8

try {
    $outer = (Get-Content $jsonFile -Raw) | ConvertFrom-Json -Depth 20
    $env1 = $outer.output.raw | ConvertFrom-Json -Depth 40
    $t = $env1.result.timings
    $s = $t.speculative
    Write-Output ("RESULT arm={0} attempt={1} status={2} elapsedMs={3}" -f $Arm, $Attempt, $env1.status, $env1.elapsedMs)
    Write-Output ("  phases: wall={0}ms setup={1}ms isolation={2}ms run={3}ms merge={4}ms finalize={5}ms overhead={6}ms" -f `
        $t.phases.wallMs, $t.phases.setupMs, $t.phases.isolationMs, $t.phases.runMs, $t.phases.mergeMs, $t.phases.finalizeMs, $t.runOverheadMs)
    Write-Output ("  buckets: rounds={0} model={1}ms build={2}ms tool={3}ms callback={4}ms" -f `
        $t.rounds, $t.modelMs, $t.buildMs, $t.toolMs, $t.callbackMs)
    Write-Output ("  speculative: builds={0} specBuilds={1} reused={2} cancelled={3} failed={4} specMs={5} ratio={6}" -f `
        $s.builds, $s.speculativeBuilds, $s.reused, $s.cancelled, $s.failed, $s.speculativeMs, $s.reuseRatio)
    Write-Output ("  merge: {0}" -f ($env1.result.merge | ConvertTo-Json -Compress -Depth 5))
}
catch {
    Write-Output "PARSE FAILED: $($_.Exception.Message)"
    if ($StopBackendWhenDone) {
        $p = Get-BackendPid
        if ($p) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
    }
    exit 3
}

if ($StopBackendWhenDone) {
    $p = Get-BackendPid
    if ($p) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 40; $i++) {
        Start-Sleep -Milliseconds 500
        if (-not (Get-BackendPid)) { break }
    }
    Write-Output 'backend stopped'
}
