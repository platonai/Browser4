#Requires -Version 7.0
#!/usr/bin/env pwsh

<#
.SYNOPSIS
Initializes or incrementally updates the Amazon category URL manifest.

.EXAMPLE
pwsh ./01_Init-ManifestFromSeed.ps1 -SeedFile ./amazon-categories.txt

.EXAMPLE
pwsh ./01_Init-ManifestFromSeed.ps1 -SeedFile ./amazon-categories.txt -SessionName amz-us -BatchSize 500 -Parallel 4

Input seed files contain one URL per line. Blank lines and lines starting with '#'
are ignored. Re-running preserves existing manifest rows and their run state.
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory)][string]$SeedFile,
    [string]$DataRoot = "",
    [string]$CliPath = "",
    [string]$SessionName = "amz-us",
    [ValidateRange(1, 2000)][int]$BatchSize = 500,
    [ValidateRange(1, 8)][int]$Parallel = 4,
    [ValidateRange(1, 5)][int]$MaxAttempts = 3,
    [ValidateRange(0, 3600)][int]$RetryDelaySeconds = 60,
    [ValidateRange(0, 600)][int]$BatchDelaySeconds = 5,
    [ValidatePattern('^[0-9]+[smh]$')][string]$Timeout = "45m",
    [ValidatePattern('^[0-9]+[smh]$')][string]$PageLoadTimeout = "45s"
)

. (Join-Path $PSScriptRoot "AmazonCategoryMonitor.Common.ps1")

if (-not $DataRoot) {
    $DataRoot = Get-DefaultMonitorDataRoot
}
$resolvedSeedFile = (Resolve-Path -LiteralPath $SeedFile).Path
$DataRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($DataRoot)
$null = New-Item -ItemType Directory -Path $DataRoot -Force
$resolvedCliPath = Resolve-MonitorCliPath -RequestedPath $CliPath -ScriptDirectory $PSScriptRoot

$sqlTarget = Join-Path $DataRoot "amazon-category-snapshot.x-sql.sql"
if (-not (Test-Path -LiteralPath $sqlTarget -PathType Leaf)) {
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot "amazon-category-snapshot.x-sql.sql") -Destination $sqlTarget
}

$configPath = Join-Path $DataRoot "config.json"
$config = [ordered]@{
    version            = 1
    cliPath            = $resolvedCliPath
    sessionName        = $SessionName
    batchSize          = $BatchSize
    parallel           = $Parallel
    maxAttempts        = $MaxAttempts
    retryDelaySeconds  = $RetryDelaySeconds
    batchDelaySeconds  = $BatchDelaySeconds
    timeout            = $Timeout
    pageLoadTimeout    = $PageLoadTimeout
    loadOptions        = "-nMaxRetry 2 -requireSize 20000"
    sqlFile            = $sqlTarget
}
Write-JsonAtomic -Path $configPath -Value $config

$manifestPath = Join-Path $DataRoot "manifest.csv"
$manifestRows = [System.Collections.Generic.List[object]]::new()
$byUrl = @{}
if (Test-Path -LiteralPath $manifestPath -PathType Leaf) {
    foreach ($row in @(Import-Csv -LiteralPath $manifestPath)) {
        $null = Ensure-ManifestRowSchema -Row $row
        $manifestRows.Add($row)
        $byUrl[$row.url] = $row
    }
}

$rejected = [System.Collections.Generic.List[object]]::new()
$lineNumber = 0
$added = 0
$duplicates = 0
foreach ($line in [IO.File]::ReadLines($resolvedSeedFile)) {
    $lineNumber++
    $candidate = $line.Trim()
    if (-not $candidate -or $candidate.StartsWith('#')) {
        continue
    }

    try {
        $normalized = ConvertTo-NormalizedAmazonUrl -Url $candidate
        if ($byUrl.ContainsKey($normalized)) {
            $duplicates++
            continue
        }
        $uri = [Uri]$normalized
        $row = [pscustomobject][ordered]@{
            category_id     = Get-CategoryIdFromUrl -Url $normalized
            url             = $normalized
            marketplace     = $uri.IdnHost
            enabled         = "true"
            status          = "pending"
            last_attempt_at = ""
            last_ok_at      = ""
            last_attempt_snapshot = ""
            last_ok_snapshot = ""
            fail_count      = "0"
            last_error      = ""
            row_count       = "0"
        }
        $manifestRows.Add($row)
        $byUrl[$normalized] = $row
        $added++
    }
    catch {
        $rejected.Add([pscustomobject]@{
                line   = $lineNumber
                url    = $candidate
                reason = $_.Exception.Message
            })
    }
}

if ($manifestRows.Count -eq 0) {
    throw "No valid Amazon category URLs were found in $resolvedSeedFile"
}

$sortedRows = @($manifestRows | Sort-Object -Property category_id)
Write-ManifestAtomic -Path $manifestPath -Rows $sortedRows
if ($rejected.Count -gt 0) {
    $rejectedPath = Join-Path $DataRoot "rejected-seeds.csv"
    $rejected | Export-Csv -LiteralPath $rejectedPath -NoTypeInformation -Encoding utf8
    Write-Warning "$($rejected.Count) invalid URL(s) were rejected. See $rejectedPath"
}

Write-Host "Amazon category monitor initialized."
Write-Host "  Data root:  $DataRoot"
Write-Host "  CLI:        $resolvedCliPath"
Write-Host "  Manifest:   $($sortedRows.Count) URL(s); added $added, duplicates $duplicates, rejected $($rejected.Count)"
Write-Host "  Extraction: $sqlTarget"
Write-Host ""
Write-Host "Warm the locale/currency profile once before the first run, for example:"
Write-Host "  & '$resolvedCliPath' -s $SessionName open --headless 'https://www.amazon.com/'"
Write-Host "Then select language/currency/delivery location in that session and run 02_Run-DailyCategorySnapshot.ps1."
