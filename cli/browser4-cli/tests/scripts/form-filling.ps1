#!/usr/bin/env pwsh
. "$PSScriptRoot/common.ps1"

$taskPrompt = @"

1. Navigate to http://localhost:18080/generated/form-filling.html.
2. Use browser4-cli batch mode to fill in the form.
3. Use CSS selectors to locate each form field.
4. Output the complete batch command with all form data included.
"@

$prompt = $generalPrompt + $taskPrompt
Invoke-Agent -Prompt $prompt
