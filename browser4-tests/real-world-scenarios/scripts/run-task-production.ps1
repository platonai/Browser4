#!/usr/bin/env pwsh
<#
.SYNOPSIS
Production wrapper for run-task.ps1 — tests globally installed browser4-cli, not cargo run.

.DESCRIPTION
Sets $browser4cliMode = 'production' before invoking run-task.ps1 so that common.ps1
resolves the CLI as `browser4-cli help` and loads the skill reference from the public
URL (https://browser4.io/SKILL.md) instead of the local dev paths.

Replaces the per-task wrapper scripts previously in browser4-tests/real-world-scenarios/.
Task files remain canonical in browser4-tests/real-world-scenarios/tasks/.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-task-production.ps1 -TaskFile tasks/amazon.md

    Run the amazon scenario in production mode.

.EXAMPLE
./browser4-tests/real-world-scenarios/scripts/run-task-production.ps1 -TaskFile tasks/search-summary.md -Silent

    Run the search-summary scenario with silent output.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string] $TaskFile,

    [switch] $Silent
)

$ErrorActionPreference = 'Stop'

# Switch to production mode before dot-sourcing run-task.ps1.
# common.ps1 reads this variable to decide CLI paths and documentation URLs.
$browser4cliMode = 'production'

$runTaskParams = @{
    TaskFile = $TaskFile
}
if ($Silent) {
    $runTaskParams['Silent'] = $true
}

. "$PSScriptRoot/run-task.ps1" @runTaskParams
