#!/usr/bin/env pwsh

$prompt = @"
Run global browser4-cli for help and read cli/skill/SKILL.md to learn how to use.

Remember, use browser4-cli for all browser interactions, and do not use any other tools or APIs to interact with the browser.
If you need to perform an action in the browser, find the appropriate documented browser4-cli command to do so.

访问 `http://localhost:18080/generated/form-filling.html` ，构造表单数据，使用 browser4-cli batch 模式填写表单。
使用 css path 来定位每一个表单项。
输出填写表单的完整命令，命令中需要包含完整的表单数据。

"@

copilot --allow-all -p "$prompt" ## --silent
# gh copilot --allow-all -p "$prompt" ## --silent
# claude --dangerously-skip-permissions $prompt
