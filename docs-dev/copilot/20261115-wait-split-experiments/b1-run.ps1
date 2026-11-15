param(
    [Parameter(Mandatory = $true)][int]$Index,
    [Parameter(Mandatory = $true)][string]$TaskFile,
    [switch]$StopBackendWhenDone
)

$ErrorActionPreference = 'Continue'
$repo = 'D:\workspace\Browser4\Browser4-4.16'
$outDir = 'D:\tmp\b1-results'
New-Item -ItemType Directory -Force -Path $outDir | Out-Null
Set-Location $repo

$env:DEEPSEEK_API_KEY = [Environment]::GetEnvironmentVariable('DEEPSEEK_API_KEY', 'User')
Remove-Item Env:BROWSER4_SERVER_OPTS -ErrorAction SilentlyContinue

$task = Get-Content $TaskFile -Raw

function Get-BackendPid {
    $c = Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
        Where-Object { $_.LocalPort -eq 8283 } | Select-Object -First 1
    if ($c) { return $c.OwningProcess } else { return $null }
}

$runLog = Join-Path $outDir ("task{0}-run.log" -f $Index)
$runOut = & pwsh "$repo\b4w.ps1" agent run $task --follow 2>&1
$runOut | Out-File -FilePath $runLog -Encoding utf8
Write-Output ("run exit={0} log={1}" -f $LASTEXITCODE, $runLog)

$taskId = $null
foreach ($line in $runOut) {
    if ("$line" -match 'Task submitted:\s*([0-9a-fA-F-]{36})') { $taskId = $Matches[1]; break }
}
Write-Output "taskId=$taskId"
if (-not $taskId) { Write-Output 'NO TASK ID'; exit 2 }

$jsonFile = Join-Path $outDir ("task{0}-result.json" -f $Index)
$jsonOut = & pwsh "$repo\b4w.ps1" agent result $taskId --json --allow-failed 2>&1
$jsonOut | Out-File -FilePath $jsonFile -Encoding utf8

try {
    $outer = (Get-Content $jsonFile -Raw) | ConvertFrom-Json -Depth 20
    $env1 = $outer.output.raw | ConvertFrom-Json -Depth 40
    $t = $env1.result.timings
    $sum = [int]$t.modelMs + [int]$t.toolMs + [int]$t.callbackMs + [int]$t.buildMs
    $toolShare = if ($sum -gt 0) { [math]::Round(100.0 * $t.toolMs / $sum, 1) } else { 0 }
    Write-Output ("RESULT task={0} status={1} elapsed={2}ms rounds={3}" -f $Index, $env1.status, $env1.elapsedMs, $t.rounds)
    Write-Output ("  buckets: model={0}ms ({1}%) tool={2}ms ({3}%) callback={4}ms build={5}ms sum={6}ms" -f `
        $t.modelMs, [math]::Round(100.0 * $t.modelMs / $sum, 1), $t.toolMs, $toolShare, $t.callbackMs, $t.buildMs, $sum)
    Write-Output ("  phases: wall={0}ms setup={1} run={2} finalize={3} overhead={4}" -f `
        $t.phases.wallMs, $t.phases.setupMs, $t.phases.runMs, $t.phases.finalizeMs, $t.runOverheadMs)
    Write-Output ("  TOOL_SHARE={0}" -f $toolShare)
}
catch {
    Write-Output "PARSE FAILED: $($_.Exception.Message)"
}

if ($StopBackendWhenDone) {
    $p = Get-BackendPid
    if ($p) { Stop-Process -Id $p -Force -ErrorAction SilentlyContinue }
    for ($i = 0; $i -lt 40; $i++) { Start-Sleep -Milliseconds 500; if (-not (Get-BackendPid)) { break } }
    Write-Output 'backend stopped'
}
