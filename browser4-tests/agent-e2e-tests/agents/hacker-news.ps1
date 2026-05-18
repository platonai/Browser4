#!/usr/bin/env pwsh

$prompt = @"
Run global browser4-cli for help and read SKILL.md to learn how to use.

Remember, use browser4-cli for all browser interactions, and do not use any other tools or APIs to interact with the browser.
If you need to perform an action in the browser, find the appropriate browser4-cli command to do so.

Task:

1. Navigate to https://news.ycombinator.com/news.
2. Open the top 3 results and summarize each one.
"@

gh copilot --allow-all -p "$prompt" ## --silent
