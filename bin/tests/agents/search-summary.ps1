#!/usr/bin/env pwsh

$prompt = @"
Run browser4-cli for help, use the global command.

Remember, use browser4-cli for all browser interactions, and do not use any other tools or APIs to interact with the browser.
If you need to perform an action in the browser, find the appropriate browser4-cli command to do so.

访问 bing.com 搜索 "江岸区龙虾节"，介绍下这是个什么活动。
"@

gh copilot --allow-all -p "$prompt" ## --silent
