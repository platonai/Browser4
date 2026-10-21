$ErrorActionPreference = 'Stop'
$dir = $PSScriptRoot
$hook = [System.IO.File]::ReadAllText("$dir\leak-hook.js")
$escaped = $hook.Replace('\', '\\').Replace('"', '\"').Replace("`r", ' ').Replace("`n", ' ').Replace("`t", ' ')
$json = '{"source":"' + $escaped + '"}'
[System.IO.File]::WriteAllText("$dir\cdp-params.json", $json, (New-Object System.Text.UTF8Encoding($false)))
Write-Output ("params bytes: " + (Get-Item "$dir\cdp-params.json").Length)
