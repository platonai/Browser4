#!/usr/bin/env pwsh

$prompt = @"
Run global browser4-cli for help and read SKILL.md to learn how to use.

Remember, use browser4-cli for all browser interactions, and do not use any other tools or APIs to interact with the browser.
If you need to perform an action in the browser, find the appropriate browser4-cli command to do so.

Task:

1. go to https://www.amazon.com/
2. search for pens to draw on whiteboards
3. compare the first 4 ones
4. write the result to a markdown file
"@

gh copilot --allow-all -p "$prompt" ## --silent
