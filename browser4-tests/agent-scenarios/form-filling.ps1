#!/usr/bin/env pwsh
# Production wrapper — tests globally installed browser4-cli, not cargo run.
# Canonical script lives in cli/browser4-cli/tests/scripts/.
$browser4cliMode = 'production'
. "$PSScriptRoot/../../cli/browser4-cli/tests/scripts/form-filling.ps1"
