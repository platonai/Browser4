#Requires -Version 7.0
#!/usr/bin/env pwsh

<#
.SYNOPSIS
Runs one daily Amazon category snapshot with bounded batches and retries.

.DESCRIPTION
Reads config.json and manifest.csv produced by 01_Init-ManifestFromSeed.ps1. Successful
categories are not fetched again on a same-day rerun. Exit code 6 means the run
finished but at least one category is still failed; all available rows and logs
are still written.

.EXAMPLE
pwsh ./02_Run-DailyCategorySnapshot.ps1

.EXAMPLE
pwsh ./02_Run-DailyCategorySnapshot.ps1 -Limit 20 -BatchSize 10 -MaxAttempts 1 -RetryDelaySeconds 0 -BatchDelaySeconds 0

.EXAMPLE
pwsh ./02_Run-DailyCategorySnapshot.ps1 -Date 2026-11-27 -Parallel 2
#>

[CmdletBinding()]
param(
    [string]$DataRoot = "",
    [string]$Date = (Get-Date -Format 'yyyy-MM-dd'),
    [string]$CliPath = "",
    [string]$SessionName = "",
    [ValidateRange(0, 2000)][int]$BatchSize = 0,
    [ValidateRange(0, 8)][int]$Parallel = 0,
    [ValidateRange(0, 5)][int]$MaxAttempts = 0,
    [ValidateRange(-1, 3600)][int]$RetryDelaySeconds = -1,
    [ValidateRange(-1, 600)][int]$BatchDelaySeconds = -1,
    [ValidateRange(0, 1000000)][int]$Limit = 0,
    [switch]$NoCompare
)

. (Join-Path $PSScriptRoot "AmazonCategoryMonitor.Common.ps1")

if (-not $DataRoot) {
    $DataRoot = Get-DefaultMonitorDataRoot
}
Assert-MonitorDate -Date $Date
$DataRoot = $ExecutionContext.SessionState.Path.GetUnresolvedProviderPathFromPSPath($DataRoot)
$configPath = Join-Path $DataRoot "config.json"
$manifestPath = Join-Path $DataRoot "manifest.csv"
if (-not (Test-Path -LiteralPath $configPath -PathType Leaf) -or
    -not (Test-Path -LiteralPath $manifestPath -PathType Leaf)) {
    throw "Monitor is not initialized in $DataRoot. Run 01_Init-ManifestFromSeed.ps1 first."
}

$config = Get-Content -LiteralPath $configPath -Raw | ConvertFrom-Json
if (-not $CliPath) { $CliPath = [string](Get-JsonValue $config 'cliPath' '') }
if (-not $SessionName) { $SessionName = [string](Get-JsonValue $config 'sessionName' '') }
if ($BatchSize -eq 0) { $BatchSize = [int](Get-JsonValue $config 'batchSize' 500) }
if ($Parallel -eq 0) { $Parallel = [int](Get-JsonValue $config 'parallel' 4) }
if ($MaxAttempts -eq 0) { $MaxAttempts = [int](Get-JsonValue $config 'maxAttempts' 3) }
if ($RetryDelaySeconds -lt 0) { $RetryDelaySeconds = [int](Get-JsonValue $config 'retryDelaySeconds' 60) }
if ($BatchDelaySeconds -lt 0) { $BatchDelaySeconds = [int](Get-JsonValue $config 'batchDelaySeconds' 5) }
$Timeout = [string](Get-JsonValue $config 'timeout' '45m')
$PageLoadTimeout = [string](Get-JsonValue $config 'pageLoadTimeout' '45s')
$LoadOptions = [string](Get-JsonValue $config 'loadOptions' '-nMaxRetry 2 -requireSize 20000')
$SqlFile = [string](Get-JsonValue $config 'sqlFile' (Join-Path $DataRoot 'amazon-category-snapshot.x-sql.sql'))

if (-not (Test-Path -LiteralPath $CliPath -PathType Leaf)) {
    $command = Get-Command $CliPath -ErrorAction SilentlyContinue | Select-Object -First 1
    if (-not $command) { throw "Browser4 CLI not found: $CliPath" }
    $CliPath = $command.Source
}
if (-not (Test-Path -LiteralPath $SqlFile -PathType Leaf)) {
    throw "X-SQL file not found: $SqlFile"
}
if ($BatchSize -lt 1 -or $Parallel -lt 1 -or $MaxAttempts -lt 1) {
    throw "BatchSize, Parallel and MaxAttempts must be positive."
}

$runStarted = [DateTimeOffset]::UtcNow
$runDir = Join-Path $DataRoot "daily/$Date"
$workDir = Join-Path $runDir "work"
$null = New-Item -ItemType Directory -Path $workDir -Force
$lockPath = Join-Path $DataRoot "monitor.lock"
$lockStream = $null
$scriptExitCode = 0

try {
    if (Test-Path -LiteralPath $lockPath -PathType Leaf) {
        $lockPid = 0
        $lockText = Get-Content -LiteralPath $lockPath -Raw -ErrorAction SilentlyContinue
        $lockObject = $null
        try { $lockObject = $lockText | ConvertFrom-Json -ErrorAction Stop } catch { }
        $lockPidText = if ($lockObject) { [string](Get-JsonValue $lockObject 'pid' '') } else { '' }
        if ([int]::TryParse($lockPidText, [ref]$lockPid)) {
            $liveProcess = Get-Process -Id $lockPid -ErrorAction SilentlyContinue
            if ($liveProcess) {
                throw "Another monitor run is active with PID $lockPid. Remove $lockPath only if that process is gone."
            }
        }
        Remove-Item -LiteralPath $lockPath -Force
    }
    $lockStream = [IO.File]::Open($lockPath, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write, [IO.FileShare]::None)
    $lockBytes = [Text.Encoding]::UTF8.GetBytes((@{ pid = $PID; startedAt = $runStarted.ToString('o') } | ConvertTo-Json))
    $lockStream.Write($lockBytes, 0, $lockBytes.Length)
    $lockStream.Flush()

    $manifestRows = [System.Collections.Generic.List[object]]::new()
    $byUrl = @{}
    $byCategoryId = @{}
    foreach ($row in @(Import-Csv -LiteralPath $manifestPath)) {
        $null = Ensure-ManifestRowSchema -Row $row
        $manifestRows.Add($row)
        $byUrl[$row.url] = $row
        $byCategoryId[$row.category_id] = $row
    }

    function Find-ManifestRowByUrl {
        param([string]$Url)
        if (-not $Url) { return $null }
        if ($byUrl.ContainsKey($Url)) { return $byUrl[$Url] }
        try {
            $normalized = ConvertTo-NormalizedAmazonUrl -Url $Url
            if ($byUrl.ContainsKey($normalized)) { return $byUrl[$normalized] }
        }
        catch {
            return $null
        }
        return $null
    }

    $enabledRows = @($manifestRows | Where-Object { $_.enabled -ne 'false' } | Sort-Object -Property category_id)
    $selectedRows = if ($Limit -gt 0) { @($enabledRows | Select-Object -First $Limit) } else { $enabledRows }
    if ($selectedRows.Count -eq 0) {
        throw "No enabled categories are selected."
    }

    $attemptedIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $succeededIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $failedIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $skippedIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $batchCount = [int][Math]::Ceiling($selectedRows.Count / [double]$BatchSize)

    for ($batchIndex = 0; $batchIndex -lt $batchCount; $batchIndex++) {
        $batchNumber = $batchIndex + 1
        $batchLabel = '{0:D4}' -f $batchNumber
        $first = $batchIndex * $BatchSize
        $last = [Math]::Min($first + $BatchSize, $selectedRows.Count) - 1
        $batchRows = @($selectedRows[$first..$last])
        $remaining = [System.Collections.Generic.List[object]]::new()
        foreach ($row in $batchRows) {
            if (Test-ManifestSuccessOnDate -Row $row -Date $Date) {
                $null = $skippedIds.Add($row.category_id)
            }
            else {
                $remaining.Add($row)
            }
        }
        if ($remaining.Count -eq 0) {
            Write-Host "Batch ${batchLabel}/${batchCount}: already complete; skipped."
            continue
        }

        for ($attempt = 1; $attempt -le $MaxAttempts -and $remaining.Count -gt 0; $attempt++) {
            $attemptStarted = [DateTimeOffset]::UtcNow
            $prefix = "batch-$batchLabel-attempt-$attempt"
            $seedPath = Join-Path $workDir "$prefix-seed.txt"
            $csvPath = Join-Path $workDir "$prefix.csv"
            $stdoutPath = Join-Path $workDir "$prefix.stdout.log"
            $stderrPath = Join-Path $workDir "$prefix.stderr.log"
            $recordPath = Join-Path $workDir "$prefix.record.json"
            $recordStderrPath = Join-Path $workDir "$prefix.record.stderr.log"
            [IO.File]::WriteAllLines($seedPath, [string[]]@($remaining | ForEach-Object { $_.url }), [Text.UTF8Encoding]::new($false))
            foreach ($path in @($csvPath, $stdoutPath, $stderrPath, $recordPath, $recordStderrPath)) {
                if (Test-Path -LiteralPath $path) { Remove-Item -LiteralPath $path -Force }
            }

            Write-Host "Batch ${batchLabel}/${batchCount} attempt ${attempt}/${MaxAttempts}: $($remaining.Count) URL(s)."
            $crawlArgs = [System.Collections.Generic.List[string]]::new()
            $crawlArgs.AddRange([string[]]@(
                    'crawl', '--seed-file', $seedPath, '--depth', '0', '--refresh',
                    '--sql', "@$SqlFile", '--format', 'csv', '-o', $csvPath,
                    '--parallel', [string]$Parallel, '--timeout', $Timeout,
                    '--page-load-timeout', $PageLoadTimeout
                ))
            if ($SessionName) {
                $crawlArgs.AddRange([string[]]@('--session', $SessionName))
            }
            if ($LoadOptions) {
                $crawlArgs.AddRange([string[]]@('-a', $LoadOptions))
            }
            $crawlExit = Invoke-MonitorCli -CliPath $CliPath -Arguments $crawlArgs.ToArray() -StdoutPath $stdoutPath -StderrPath $stderrPath

            $results = @{}
            foreach ($row in $remaining) {
                $null = $attemptedIds.Add($row.category_id)
                $results[$row.category_id] = [pscustomobject]@{
                    Row     = $row
                    Ok      = $false
                    Error   = "crawl exited $crawlExit without an accountably successful page"
                    Attempt = $attempt
                }
            }

            $stdoutText = if (Test-Path -LiteralPath $stdoutPath) { Get-Content -LiteralPath $stdoutPath -Raw } else { '' }
            $stderrText = if (Test-Path -LiteralPath $stderrPath) { Get-Content -LiteralPath $stderrPath -Raw } else { '' }
            $combinedLog = ($stdoutText + "`n" + $stderrText).Trim()
            $taskMatch = [regex]::Match($combinedLog, '(?im)Crawl task submitted:\s*([A-Za-z0-9-]+)')
            $record = $null
            if ($taskMatch.Success) {
                $taskId = $taskMatch.Groups[1].Value
                $recordExit = Invoke-MonitorCli -CliPath $CliPath -Arguments @('crawl', 'result', $taskId) -StdoutPath $recordPath -StderrPath $recordStderrPath
                if ($recordExit -eq 0 -and (Test-Path -LiteralPath $recordPath -PathType Leaf)) {
                    try {
                        $record = Get-Content -LiteralPath $recordPath -Raw | ConvertFrom-Json
                    }
                    catch {
                        Write-Warning "Could not parse crawl record for batch ${batchLabel}: $($_.Exception.Message)"
                    }
                }
            }

            if ($record) {
                $pagesValue = Get-JsonValue $record 'pages' $null
                $pages = if ($null -eq $pagesValue) { @() } else { @($pagesValue) }
                foreach ($page in $pages) {
                    $pageUrl = [string](Get-JsonValue $page 'url' '')
                    $row = Find-ManifestRowByUrl -Url $pageUrl
                    if (-not $row -or -not $results.ContainsKey($row.category_id)) { continue }
                    $extractionError = [string](Get-JsonValue $page 'extractionError' '')
                    $contentLength = [long](Get-JsonValue $page 'contentLength' -1)
                    if ($extractionError -or $contentLength -eq 0) {
                        $reason = if ($extractionError) { $extractionError } else { 'page content was empty (0 bytes)' }
                        $results[$row.category_id].Error = $reason
                    }
                    else {
                        $results[$row.category_id].Ok = $true
                        $results[$row.category_id].Error = ''
                    }
                }

                $failedValue = Get-JsonValue $record 'failedPages' $null
                $failedPages = if ($null -eq $failedValue) { @() } else { @($failedValue) }
                foreach ($failedPage in $failedPages) {
                    $row = Find-ManifestRowByUrl -Url ([string](Get-JsonValue $failedPage 'url' ''))
                    if (-not $row -or -not $results.ContainsKey($row.category_id)) { continue }
                    $results[$row.category_id].Ok = $false
                    $results[$row.category_id].Error = [string](Get-JsonValue $failedPage 'reason' 'page was not delivered')
                }
            }
            elseif ($combinedLog) {
                $shortLog = ($combinedLog -replace '\s+', ' ')
                if ($shortLog.Length -gt 800) { $shortLog = $shortLog.Substring(0, 800) }
                foreach ($result in $results.Values) { $result.Error = $shortLog }
            }

            $nextRemaining = [System.Collections.Generic.List[object]]::new()
            $now = [DateTimeOffset]::UtcNow.ToString('o')
            foreach ($row in $remaining) {
                $result = $results[$row.category_id]
                $row.last_attempt_at = $now
                $row.last_attempt_snapshot = $Date
                $row.last_error = ([string]$result.Error).Substring(0, [Math]::Min(1000, ([string]$result.Error).Length))
                if ($result.Ok) {
                    $row.status = 'ok'
                    $row.last_ok_at = $now
                    $row.last_ok_snapshot = $Date
                    $row.fail_count = '0'
                    $row.last_error = ''
                    $null = $succeededIds.Add($row.category_id)
                }
                else {
                    $nextRemaining.Add($row)
                    if ($attempt -eq $MaxAttempts) {
                        $row.status = 'failed'
                        $row.fail_count = [string]([int]$row.fail_count + 1)
                        $null = $failedIds.Add($row.category_id)
                    }
                    else {
                        $row.status = 'retrying'
                    }
                }
            }
            Write-ManifestAtomic -Path $manifestPath -Rows $manifestRows.ToArray()
            $okThisAttempt = $remaining.Count - $nextRemaining.Count
            Write-Host "  Result: $okThisAttempt ok, $($nextRemaining.Count) failed; crawl exit $crawlExit; $([int]([DateTimeOffset]::UtcNow - $attemptStarted).TotalSeconds)s."
            $remaining = $nextRemaining

            if ($remaining.Count -gt 0 -and $attempt -lt $MaxAttempts -and $RetryDelaySeconds -gt 0) {
                $jitter = Get-Random -Minimum 0 -Maximum ([Math]::Max(2, [int]($RetryDelaySeconds / 4) + 1))
                Start-Sleep -Seconds ($RetryDelaySeconds + $jitter)
            }
        }

        if ($BatchDelaySeconds -gt 0 -and $batchIndex -lt $batchCount - 1) {
            Start-Sleep -Seconds $BatchDelaySeconds
        }
    }

    $failedForDateIds = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    foreach ($id in $failedIds) { $null = $failedForDateIds.Add($id) }
    foreach ($row in $manifestRows) {
        if ($row.status -ne 'failed') { continue }
        $attemptSnapshot = [string](Get-JsonValue $row 'last_attempt_snapshot' '')
        if ($attemptSnapshot) {
            if ($attemptSnapshot -eq $Date) { $null = $failedForDateIds.Add($row.category_id) }
            continue
        }
        $attemptAt = [DateTimeOffset]::MinValue
        if ([DateTimeOffset]::TryParse([string]$row.last_attempt_at, [ref]$attemptAt) -and
            $attemptAt.LocalDateTime.ToString('yyyy-MM-dd') -eq $Date) {
            $null = $failedForDateIds.Add($row.category_id)
        }
    }

    $rowsPath = Join-Path $runDir "rows.csv"
    $tempRowsPath = "$rowsPath.tmp-$PID-$([Guid]::NewGuid().ToString('N'))"
    $seenKeys = [Collections.Generic.HashSet[string]]::new([StringComparer]::Ordinal)
    $rowCounts = @{}
    foreach ($id in $attemptedIds) { $rowCounts[$id] = 0 }
    $rowsWritten = 0
    $unmappedRows = 0
    $duplicateRows = 0
    $writer = [IO.StreamWriter]::new($tempRowsPath, $false, [Text.UTF8Encoding]::new($false))
    try {
        $header = @('snapshot_date', 'category_id', 'category_url', 'asin', 'title', 'price', 'rating', 'reviews', 'product_url', 'rank', 'scraped_at')
        Write-CsvRow -Writer $writer -Values $header

        if (Test-Path -LiteralPath $rowsPath -PathType Leaf) {
            foreach ($oldRow in Import-Csv -LiteralPath $rowsPath) {
                $oldCategoryId = [string](Get-JsonValue $oldRow 'category_id' '')
                $oldAsin = ([string](Get-JsonValue $oldRow 'asin' '')).Trim()
                if (-not $oldCategoryId -or -not $oldAsin -or $attemptedIds.Contains($oldCategoryId)) { continue }
                if (-not $seenKeys.Add("$oldCategoryId|$oldAsin")) { $duplicateRows++; continue }
                Write-CsvRow -Writer $writer -Values @(
                    $Date, $oldCategoryId, (Get-JsonValue $oldRow 'category_url' ''), $oldAsin,
                    (Get-JsonValue $oldRow 'title' ''), (Get-JsonValue $oldRow 'price' ''),
                    (Get-JsonValue $oldRow 'rating' ''), (Get-JsonValue $oldRow 'reviews' ''),
                    (Get-JsonValue $oldRow 'product_url' ''), (Get-JsonValue $oldRow 'rank' ''),
                    (Get-JsonValue $oldRow 'scraped_at' '')
                )
                $currentCount = if ($rowCounts.ContainsKey($oldCategoryId)) { [int]$rowCounts[$oldCategoryId] } else { 0 }
                $rowCounts[$oldCategoryId] = $currentCount + 1
                $rowsWritten++
            }
        }

        $partFiles = @(Get-ChildItem -LiteralPath $workDir -Filter 'batch-*-attempt-*.csv' -File | Sort-Object -Property Name)
        foreach ($partFile in $partFiles) {
            foreach ($extracted in Import-Csv -LiteralPath $partFile.FullName) {
                $sourceUrl = [string](Get-JsonValue $extracted 'category_url' '')
                $manifestRow = Find-ManifestRowByUrl -Url $sourceUrl
                if (-not $manifestRow) { $unmappedRows++; continue }
                $categoryId = $manifestRow.category_id
                if ($failedForDateIds.Contains($categoryId)) { continue }
                $productUrl = [string](Get-JsonValue $extracted 'product_url' '')
                $asin = ([string](Get-JsonValue $extracted 'asin' '')).Trim().ToUpperInvariant()
                if (-not $asin -and $productUrl -match '/(?:dp|gp/product)/([A-Za-z0-9]{10})(?:[/?]|$)') {
                    $asin = $Matches[1].ToUpperInvariant()
                }
                if (-not $asin) { $unmappedRows++; continue }
                if (-not $seenKeys.Add("$categoryId|$asin")) { $duplicateRows++; continue }
                Write-CsvRow -Writer $writer -Values @(
                    $Date, $categoryId, $manifestRow.url, $asin,
                    (Get-JsonValue $extracted 'title' ''), (Get-JsonValue $extracted 'price' ''),
                    (Get-JsonValue $extracted 'rating' ''), (Get-JsonValue $extracted 'reviews' ''),
                    $productUrl, (Get-JsonValue $extracted 'rank' ''), $runStarted.ToString('o')
                )
                $currentCount = if ($rowCounts.ContainsKey($categoryId)) { [int]$rowCounts[$categoryId] } else { 0 }
                $rowCounts[$categoryId] = $currentCount + 1
                $rowsWritten++
            }
        }
    }
    finally {
        $writer.Dispose()
    }
    Move-Item -LiteralPath $tempRowsPath -Destination $rowsPath -Force

    foreach ($id in $rowCounts.Keys) {
        if ($byCategoryId.ContainsKey($id)) {
            $byCategoryId[$id].row_count = [string]$rowCounts[$id]
        }
    }
    Write-ManifestAtomic -Path $manifestPath -Rows $manifestRows.ToArray()

    $runFinished = [DateTimeOffset]::UtcNow
    $failedCategoryIds = @($failedForDateIds | Sort-Object)
    $summary = [ordered]@{
        date                 = $Date
        started_at           = $runStarted.ToString('o')
        finished_at          = $runFinished.ToString('o')
        duration_seconds     = [Math]::Round(($runFinished - $runStarted).TotalSeconds, 3)
        selected_categories  = $selectedRows.Count
        attempted_categories = $attemptedIds.Count
        skipped_categories   = $skippedIds.Count
        succeeded_categories = $succeededIds.Count
        failed_categories    = $failedCategoryIds.Count
        failed_category_ids  = $failedCategoryIds
        batches              = $batchCount
        rows_written         = $rowsWritten
        duplicate_rows       = $duplicateRows
        unmapped_rows        = $unmappedRows
        rows_file            = $rowsPath
        status               = if ($failedCategoryIds.Count -eq 0) { 'complete' } else { 'partial' }
    }
    $summaryPath = Join-Path $runDir "summary.json"
    Write-JsonAtomic -Path $summaryPath -Value $summary

    if (-not $NoCompare) {
        try {
            & (Join-Path $PSScriptRoot '03_Compare-SnapshotsAndBuildChanges.ps1') -DataRoot $DataRoot -Date $Date
        }
        catch {
            Write-Warning "Snapshot was written, but comparison failed: $($_.Exception.Message)"
        }
    }

    Write-Host ""
    Write-Host "Run finished: $($summary.status)."
    Write-Host "  Categories: $($attemptedIds.Count) attempted, $($succeededIds.Count) succeeded, $($failedCategoryIds.Count) failed, $($skippedIds.Count) skipped."
    Write-Host "  Rows:       $rowsWritten ($duplicateRows duplicates and $unmappedRows unmapped rows omitted)."
    Write-Host "  Output:     $rowsPath"
    Write-Host "  Summary:    $summaryPath"
    if ($failedCategoryIds.Count -gt 0) { $scriptExitCode = 6 }
}
finally {
    if ($lockStream) { $lockStream.Dispose() }
    if (Test-Path -LiteralPath $lockPath) { Remove-Item -LiteralPath $lockPath -Force }
    $tempRowsPath = Join-Path (Join-Path $DataRoot "daily/$Date") 'rows.csv'
    Get-ChildItem -LiteralPath (Split-Path -Parent $tempRowsPath) -Filter 'rows.csv.tmp-*' -File -ErrorAction SilentlyContinue |
        Remove-Item -Force -ErrorAction SilentlyContinue
}

exit $scriptExitCode
