#!/usr/bin/env pwsh
# E-1 / E-3 measurement: the fixed cost of one CLI invocation.
#
#   1. `--version` inside this checkout vs outside it      → the development-mode overhead
#   2. the per-phase startup trace                          → where that overhead goes
#   3. `help eval` (direct exec) vs `help "eval"` (shell)   → the transport E-1 removes
#   4. the PowerShell floor                                 → what the shell itself costs here
#
# Run from the repository root:
#   pwsh docs-dev/copilot/20261115-wait-split-experiments/e3-dev-startup/e3-measure.ps1
param(
    [string]$Exe = "cli/browser4-cli/target/debug/browser4-cli.exe",
    [int]$Runs = 5
)

$ErrorActionPreference = "Stop"
$repoRoot = (Resolve-Path "$PSScriptRoot\..\..\..\..").Path
$exePath = (Resolve-Path (Join-Path $repoRoot $Exe)).Path

function Time-Invocation([string[]]$Arguments) {
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    & $exePath @Arguments 2>&1 | Out-Null
    $sw.Stop()
    return $sw.ElapsedMilliseconds
}

function Show-Runs([string]$Label, [string[]]$Arguments) {
    $times = @(1..$Runs | ForEach-Object { Time-Invocation $Arguments })
    "{0,-34} {1}" -f $Label, ($times -join ", ")
}

Write-Output "exe: $exePath"
Write-Output ""

Write-Output "== 1. wall clock, inside vs outside the checkout =="
Push-Location $repoRoot
try { Show-Runs "--version (inside checkout)" @("--version") } finally { Pop-Location }
Push-Location $env:TEMP
try { Show-Runs "--version (outside checkout)" @("--version") } finally { Pop-Location }
Write-Output ""

Write-Output "== 2. startup trace (BROWSER4_CLI_TRACE_STARTUP=1) =="
Push-Location $repoRoot
try {
    $env:BROWSER4_CLI_TRACE_STARTUP = "1"
    & $exePath --version 2>&1 | Select-String -Pattern "\[startup\]|\[timing\]"
    Remove-Item Env:BROWSER4_CLI_TRACE_STARTUP
} finally { Pop-Location }
Write-Output ""

Write-Output "== 3. direct exec vs shell, same static command (startup segment, ms) =="
Push-Location $repoRoot
try {
    $env:BROWSER4_CLI_TIMING = "1"
    Show-Runs "help eval (direct)" @("help", "eval")
    Show-Runs 'help "eval" (shell)' @("help", "eval")
    Remove-Item Env:BROWSER4_CLI_TIMING
} finally { Pop-Location }
Write-Output ""
Write-Output "note: the CLI's own clock starts inside the process, so a shell that launched"
Write-Output "      it is invisible here — `CliProcessManager` reports it as [timing] launch=."
Write-Output ""

Write-Output "== 4. PowerShell floor on this host =="
$encoded = [Convert]::ToBase64String([Text.Encoding]::Unicode.GetBytes('$true'))
$times = @(1..$Runs | ForEach-Object {
        $sw = [System.Diagnostics.Stopwatch]::StartNew()
        & pwsh -NoLogo -NoProfile -NonInteractive -OutputFormat Text -EncodedCommand $encoded 2>&1 | Out-Null
        $sw.Stop()
        $sw.ElapsedMilliseconds
    })
"{0,-34} {1}" -f "pwsh -EncodedCommand `$true", ($times -join ", ")
