#!/usr/bin/env pwsh
# Production wrapper — tests globally installed browser4-cli, not cargo run.
# Canonical task lives in cli/browser4-cli/tests/scripts/tasks/.
$browser4cliMode = 'production'
. "$PSScriptRoot/../../cli/browser4-cli/tests/scripts/run-task.ps1" `
    -TaskFile "$PSScriptRoot/../../cli/browser4-cli/tests/scripts/tasks/hacker-news.md"
