#!/usr/bin/env pwsh

$prompt = @"
Run global browser4-cli for help and read SKILL.md to learn how to use.

Remember, use browser4-cli for all browser interactions, and do not use any other tools or APIs to interact with the browser.
If you need to perform an action in the browser, find the appropriate browser4-cli command to do so.

访问 baidu.com 搜索 "武汉龙虾节"，介绍下这是个什么活动。
"@

gh copilot --allow-all -p "$prompt" ## --silent
# claude --dangerously-skip-permissions $prompt
