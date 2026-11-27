#Requires -Version 7.0
#!/usr/bin/env pwsh

<#
.SYNOPSIS
Compares a daily snapshot with the latest earlier snapshot.

.DESCRIPTION
Writes added, removed and updated products to changes.csv. Categories that failed
on either side are excluded from both sides, preventing missing fetches from
being reported as product removals or additions.

.EXAMPLE
pwsh ./03_Compare-SnapshotsAndBuildChanges.ps1 -Date 2026-11-27

.EXAMPLE
pwsh ./03_Compare-SnapshotsAndBuildChanges.ps1 -Date 2026-11-27 -BaselineDate 2026-11-26
#>

[CmdletBinding()]
param(
    [string]$DataRoot = "",
    [Parameter(Mandatory)][string]$Date,
    [string]$BaselineDate = ""
)

. (Join-Path $PSScriptRoot "AmazonCategoryMonitor.Common.ps1")

if (-not $DataRoot) { $DataRoot = Get-DefaultMonitorDataRoot }
Assert-MonitorDate -Date $Date
$DataRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($DataRoot)
$dailyRoot = Join-Path $DataRoot 'daily'
$targetDir = Join-Path $dailyRoot $Date
$targetRows = Join-Path $targetDir 'rows.csv'
if (-not (Test-Path -LiteralPath $targetRows -PathType Leaf)) {
    throw "Target snapshot not found: $targetRows"
}

function Get-FailedCategoryIds {
    param([string]$Directory)
    $ids = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $summaryPath = Join-Path $Directory 'summary.json'
    if (Test-Path -LiteralPath $summaryPath -PathType Leaf) {
        $summary = Get-Content -LiteralPath $summaryPath -Raw | ConvertFrom-Json
        $failed = Get-JsonValue $summary 'failed_category_ids' $null
        if ($null -ne $failed) {
            foreach ($id in @($failed)) {
                if ($id) { $null = $ids.Add([string]$id) }
            }
        }
    }
    return ,$ids
}

$baselineDir = ''
if ($BaselineDate) {
    Assert-MonitorDate -Date $BaselineDate
    $candidate = Join-Path $dailyRoot $BaselineDate
    if (-not (Test-Path -LiteralPath (Join-Path $candidate 'rows.csv') -PathType Leaf)) {
        throw "Baseline snapshot not found: $candidate/rows.csv"
    }
    $baselineDir = $candidate
}
else {
    $earlierDirs = @(Get-ChildItem -LiteralPath $dailyRoot -Directory -ErrorAction SilentlyContinue |
            Where-Object {
                $_.Name -match '^\d{4}-\d{2}-\d{2}$' -and $_.Name -lt $Date -and
                (Test-Path -LiteralPath (Join-Path $_.FullName 'rows.csv') -PathType Leaf)
            } | Sort-Object -Property Name -Descending)
    if ($earlierDirs.Count -gt 0) { $baselineDir = $earlierDirs[0].FullName }
}

$changesPath = Join-Path $targetDir 'changes.csv'
$tempChangesPath = "$changesPath.tmp-$PID-$([Guid]::NewGuid().ToString('N'))"
$changeSummaryPath = Join-Path $targetDir 'changes-summary.json'
$header = @(
    'snapshot_date', 'baseline_date', 'change_type', 'category_id', 'category_url', 'asin',
    'title', 'previous_price', 'price', 'previous_rating', 'rating',
    'previous_reviews', 'reviews', 'previous_rank', 'rank', 'product_url'
)

if (-not $baselineDir) {
    $writer = [IO.StreamWriter]::new($tempChangesPath, $false, [Text.UTF8Encoding]::new($false))
    try { Write-CsvRow -Writer $writer -Values $header } finally { $writer.Dispose() }
    Move-Item -LiteralPath $tempChangesPath -Destination $changesPath -Force
    $summary = [ordered]@{
        date = $Date; baseline_date = $null; status = 'no_baseline'; excluded_categories = 0
        added = 0; removed = 0; updated = 0; changes_file = $changesPath
    }
    Write-JsonAtomic -Path $changeSummaryPath -Value $summary
    Write-Host "No earlier snapshot: $changesPath contains headers only."
    return
}

$baselineDate = Split-Path -Leaf $baselineDir
$excludedIds = Get-FailedCategoryIds -Directory $targetDir
foreach ($id in (Get-FailedCategoryIds -Directory $baselineDir)) { $null = $excludedIds.Add($id) }
$baselineRows = Join-Path $baselineDir 'rows.csv'
$previous = @{}
foreach ($row in Import-Csv -LiteralPath $baselineRows) {
    $categoryId = [string](Get-JsonValue $row 'category_id' '')
    $asin = ([string](Get-JsonValue $row 'asin' '')).Trim().ToUpperInvariant()
    if (-not $categoryId -or -not $asin -or $excludedIds.Contains($categoryId)) { continue }
    $previous["$categoryId|$asin"] = [string[]]@(
        [string](Get-JsonValue $row 'category_url' ''),
        [string](Get-JsonValue $row 'title' ''),
        [string](Get-JsonValue $row 'price' ''),
        [string](Get-JsonValue $row 'rating' ''),
        [string](Get-JsonValue $row 'reviews' ''),
        [string](Get-JsonValue $row 'product_url' ''),
        [string](Get-JsonValue $row 'rank' '')
    )
}

$added = 0
$removed = 0
$updated = 0
$writer = [IO.StreamWriter]::new($tempChangesPath, $false, [Text.UTF8Encoding]::new($false))
try {
    Write-CsvRow -Writer $writer -Values $header
    foreach ($row in Import-Csv -LiteralPath $targetRows) {
        $categoryId = [string](Get-JsonValue $row 'category_id' '')
        $asin = ([string](Get-JsonValue $row 'asin' '')).Trim().ToUpperInvariant()
        if (-not $categoryId -or -not $asin -or $excludedIds.Contains($categoryId)) { continue }
        $key = "$categoryId|$asin"
        $categoryUrl = [string](Get-JsonValue $row 'category_url' '')
        $title = [string](Get-JsonValue $row 'title' '')
        $price = [string](Get-JsonValue $row 'price' '')
        $rating = [string](Get-JsonValue $row 'rating' '')
        $reviews = [string](Get-JsonValue $row 'reviews' '')
        $productUrl = [string](Get-JsonValue $row 'product_url' '')
        $rank = [string](Get-JsonValue $row 'rank' '')

        if (-not $previous.ContainsKey($key)) {
            Write-CsvRow -Writer $writer -Values @(
                $Date, $baselineDate, 'added', $categoryId, $categoryUrl, $asin, $title,
                '', $price, '', $rating, '', $reviews, '', $rank, $productUrl
            )
            $added++
            continue
        }

        $old = $previous[$key]
        $isUpdated = $old[1] -cne $title -or $old[2] -cne $price -or $old[3] -cne $rating -or
            $old[4] -cne $reviews -or $old[6] -cne $rank
        if ($isUpdated) {
            Write-CsvRow -Writer $writer -Values @(
                $Date, $baselineDate, 'updated', $categoryId, $categoryUrl, $asin, $title,
                $old[2], $price, $old[3], $rating, $old[4], $reviews, $old[6], $rank, $productUrl
            )
            $updated++
        }
        $null = $previous.Remove($key)
    }

    foreach ($entry in $previous.GetEnumerator()) {
        $keyParts = $entry.Key.Split('|', 2)
        $old = $entry.Value
        Write-CsvRow -Writer $writer -Values @(
            $Date, $baselineDate, 'removed', $keyParts[0], $old[0], $keyParts[1], $old[1],
            $old[2], '', $old[3], '', $old[4], '', $old[6], '', $old[5]
        )
        $removed++
    }
}
finally {
    $writer.Dispose()
}
Move-Item -LiteralPath $tempChangesPath -Destination $changesPath -Force

$summary = [ordered]@{
    date                = $Date
    baseline_date       = $baselineDate
    status              = if ($excludedIds.Count -eq 0) { 'complete' } else { 'partial' }
    excluded_categories = $excludedIds.Count
    added               = $added
    removed             = $removed
    updated             = $updated
    changes_file        = $changesPath
}
Write-JsonAtomic -Path $changeSummaryPath -Value $summary
Write-Host "Compared $Date with $baselineDate : $added added, $removed removed, $updated updated; $($excludedIds.Count) failed categor(ies) excluded."
Write-Host "  Changes: $changesPath"
