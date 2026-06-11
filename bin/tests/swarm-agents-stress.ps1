#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Stress-test swarm create / submit / status / result lifecycle using a seed file.

.DESCRIPTION
    Creates a seeds.txt file with URLs to scrape, opens a swarm session, submits
    the seed file, polls for completion, and retrieves results.
#>
param(
    [int]$TimeoutSeconds = 120,
    [string]$SeedFile = 'seeds.txt'
)

$ErrorActionPreference = 'Stop'

$cli = if ($env:BROWSER4_CLI_BIN) {
    { & $env:BROWSER4_CLI_BIN @args }
} else {
    { browser4-cli @args }
}

# -------------------------------------------------------------------
# 1. Prepare seeds.txt with URLs to scrape
# -------------------------------------------------------------------
$testUrls = @(
    'https://news.ycombinator.com/',
    'https://news.ycombinator.com/newest',
    'https://news.ycombinator.com/show',
    'https://www.wikipedia.org/',
    'https://en.wikipedia.org/wiki/Web_scraping'
)

# Add a timestamp query param so each run produces fresh results.
$stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
$urls = $testUrls | ForEach-Object { "$_?b4_stress=$stamp" }
$urls | Set-Content -Path $SeedFile -Encoding UTF8

Write-Host "Seeds file ($SeedFile): $($urls.Count) URLs" -ForegroundColor Cyan
$urls | ForEach-Object { Write-Host "  $_" -ForegroundColor DarkGray }

# -------------------------------------------------------------------
# 2. Open session + swarm create
# -------------------------------------------------------------------
Write-Host "`nOpening session..." -ForegroundColor Cyan
& $cli open 2>$null

Write-Host "Creating swarm session..." -ForegroundColor Cyan
& $cli swarm create 2>$null

# -------------------------------------------------------------------
# 3. Submit the seed file
# -------------------------------------------------------------------
Write-Host "`nSubmitting seed file..." -ForegroundColor Cyan
$output = & $cli swarm submit --seed-file $SeedFile 2>&1 | Out-String
Write-Host $output

$taskIdMatch = [regex]::Match($output, 'Task ID:\s*(\S+)')
if (-not $taskIdMatch.Success) {
    throw "Unable to parse Task ID from swarm submit output:`n$output"
}
$taskId = $taskIdMatch.Groups[1].Value
Write-Host "`nTask ID: $taskId" -ForegroundColor Green

# -------------------------------------------------------------------
# 4. Poll for completion
# -------------------------------------------------------------------
Write-Host "`nWaiting for task $taskId to finish (timeout: ${TimeoutSeconds}s)..." -ForegroundColor Cyan

$done = $false
$deadline = (Get-Date).AddSeconds($TimeoutSeconds)

while ((Get-Date) -lt $deadline) {
    $status = & $cli swarm status $taskId 2>&1 | Out-String
    $status = $status.Trim()

    if ($status -match '"done"\s*:\s*true' -or $status -match '"isDone"\s*:\s*true' -or $status -match 'status:\s*done') {
        Write-Host "Task $taskId completed." -ForegroundColor Green
        $done = $true
        break
    }

    Write-Host "  ... waiting ($status)" -ForegroundColor DarkGray
    Start-Sleep -Seconds 3
}

if (-not $done) {
    Write-Host "WARNING: Task $taskId did not finish within ${TimeoutSeconds}s." -ForegroundColor Yellow
}

# -------------------------------------------------------------------
# 5. Retrieve results
# -------------------------------------------------------------------
Write-Host "`nRetrieving results for task $taskId..." -ForegroundColor Cyan
$result = & $cli swarm result $taskId 2>&1 | Out-String
Write-Host $result

# -------------------------------------------------------------------
# 6. Cleanup
# -------------------------------------------------------------------
Write-Host "`nCleaning up..." -ForegroundColor Cyan
& $cli close 2>$null

Write-Host "Done." -ForegroundColor Green
