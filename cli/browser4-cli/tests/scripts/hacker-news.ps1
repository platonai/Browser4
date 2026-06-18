#!/usr/bin/env pwsh
. "$PSScriptRoot/common.ps1"

$taskPrompt = @"

1. Navigate to https://news.ycombinator.com/news.
2. Open the top 3 results and summarize each one.
"@

$prompt = $generalPrompt + $taskPrompt
Invoke-Agent -Prompt $prompt
