# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
JSON reporter for maintenance check results.
Serializes results to JSON files under bin/maintenance/logs/.

.PARAMETER Results
One or more result objects from maintenance check scripts.

.PARAMETER OutputDir
Directory to write JSON files. Defaults to bin/maintenance/logs/.

.EXAMPLE
$r = & .\checks\check-ps1-syntax.ps1
.\reporters\report-json.ps1 -Results $r
#>

param(
    [Parameter(Mandatory = $true, ValueFromPipeline = $true)]
    [PSCustomObject[]]$Results,

    [string]$OutputDir
)

begin {
    $allResults = @()
}

process {
    $allResults += $Results
}

end {
    if ($allResults.Count -eq 0) {
        return
    }

    if (-not $OutputDir) {
        $repoRoot = & { try { git rev-parse --show-toplevel 2>$null } catch { $null } }
        if (-not $repoRoot) { $repoRoot = Split-Path $PSScriptRoot -Parent }
        $OutputDir = Join-Path $repoRoot "bin\maintenance\logs"
    }

    if (-not (Test-Path $OutputDir)) {
        New-Item -ItemType Directory -Path $OutputDir -Force | Out-Null
    }

    $timestamp = Get-Date -Format "yyyyMMdd_HHmmss"

    # Write per-check JSON files
    foreach ($result in $allResults) {
        $filename = "$($result.CheckId)-${timestamp}.json"
        $filepath = Join-Path $OutputDir $filename
        $result | ConvertTo-Json -Depth 4 | Out-File -FilePath $filepath -Encoding UTF8
        Write-Host "  JSON → $filepath" -ForegroundColor Gray
    }

    # Write aggregate summary
    $summaryPath = Join-Path $OutputDir "summary-${timestamp}.json"
    $summary = @{
        timestamp = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
        total     = $allResults.Count
        passed    = @($allResults | Where-Object { $_.Status -eq "passed" }).Count
        failed    = @($allResults | Where-Object { $_.Status -eq "failed" }).Count
        skipped   = @($allResults | Where-Object { $_.Status -eq "skipped" }).Count
        errors    = @($allResults | Where-Object { $_.Status -eq "error" }).Count
        checks    = $allResults | ForEach-Object {
            @{
                checkId    = $_.CheckId
                name       = $_.Name
                status     = $_.Status
                durationMs = $_.DurationMs
                details    = $_.Details
            }
        }
    }
    $summary | ConvertTo-Json -Depth 4 | Out-File -FilePath $summaryPath -Encoding UTF8
    Write-Host "  JSON → $summaryPath" -ForegroundColor Gray

    # Also write to stdout for CI artifact capture
    $summary | ConvertTo-Json -Compress -Depth 4
}
