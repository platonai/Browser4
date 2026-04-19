#!/usr/bin/env pwsh

# Match Browser4 started either from fat-jar or Spring Boot launcher main class.
$script:Browser4CmdPattern = '(?i)(Browser4\.jar|\bBrowser4LauncherKt\b)'

function Get-Browser4JavaProcesses {
    Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^(java|javaw)\.exe$' -and
        -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and
        $_.CommandLine -match $script:Browser4CmdPattern
    }
}

