#!/usr/bin/env pwsh
<#
.SYNOPSIS
Production wrapper for run-tests.ps1 — tests globally installed browser4-cli, not cargo run.

.DESCRIPTION
Sets $browser4cliMode = 'production' before invoking run-tests.ps1 so that common.ps1
resolves the CLI as `browser4-cli help` and loads the skill reference from the public
URL (https://browser4.ioSKILL.md) instead of the local dev paths.

Auto-discovers and executes task markdown files in tasks/ sequentially.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests-production.ps1

    Run every task in production mode.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests-production.ps1 search-summary amazon

    Run only the two named tasks in production mode.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests-production.ps1 -List

    List discovered tasks without running them.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-tests-production.ps1 -FailFast

    Stop after the first failing task.
#>

[CmdletBinding()]
param(
    # One or more task names to run (e.g. "search-summary", "amazon.md").
    # When omitted every discovered task runs.
    [Parameter(Position = 0, ValueFromRemainingArguments = $true)]
    [string[]] $Tasks,

    # Stop after the first failure instead of continuing.
    [switch] $FailFast,

    # List discovered tasks and exit.
    [switch] $List
)

$ErrorActionPreference = 'Stop'

# Delegate to run-tests.ps1 with -Production.
$runTestsParams = @{
    Production = $true
}
if ($Tasks -and $Tasks.Count -gt 0) {
    $runTestsParams['Tasks'] = $Tasks
}
if ($FailFast) {
    $runTestsParams['FailFast'] = $true
}
if ($List) {
    $runTestsParams['List'] = $true
}

. "$PSScriptRoot/run-tests.ps1" @runTestsParams
