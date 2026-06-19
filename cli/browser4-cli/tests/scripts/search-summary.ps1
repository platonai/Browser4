#!/usr/bin/env pwsh
. "$PSScriptRoot/common.ps1"

$taskPrompt = @"

1. Open https://www.baidu.com
2. Search for: 武汉龙虾节
3. Read multiple relevant results.
4. Summarize:

   * What the Wuhan Lobster Festival (武汉龙虾节) is
   * Its history and background
   * Major activities
   * Typical schedule and venue
   * Its significance to local tourism, food culture, and economy
"@

$prompt = $generalPrompt + $taskPrompt
Invoke-Agent -Prompt $prompt -ScenarioName 'search-summary'
