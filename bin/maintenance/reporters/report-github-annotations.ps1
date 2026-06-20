# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
GitHub Actions annotation reporter for maintenance check results.
Produces ::warning and ::error workflow commands for CI integration.

.PARAMETER Results
One or more result objects from maintenance check scripts.

.EXAMPLE
$r = & .\checks\check-doc-links-internal.ps1
.\reporters\report-github-annotations.ps1 -Results $r
#>

param(
    [Parameter(Mandatory = $true, ValueFromPipeline = $true)]
    [PSCustomObject[]]$Results
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

    foreach ($result in $allResults) {
        if ($result.Results.Count -eq 0) {
            # No item-level results; produce a single annotation for the check
            $level = switch ($result.Status) {
                "failed" { "error" }
                "error"  { "error" }
                "skipped" { "warning" }
                default  { "notice" }
            }
            $title = "[$($result.CheckId)] $($result.Name)"
            Write-Host "::$level title=$title::$($result.Details)"
            continue
        }

        foreach ($item in $result.Results) {
            $level = switch ($item.Status) {
                "failed" { "error" }
                "error"  { "error" }
                "skipped" { "warning" }
                "passed" { "notice" }
                default  { "notice" }
            }

            # Skip passed items in CI mode to reduce noise
            if ($item.Status -eq "passed") { continue }

            $title = "[$($result.CheckId)] $($result.Name)"
            $file  = $item.Item -replace ":.*$", ""  # Extract file path from "file:line" format
            $line  = if ($item.Item -match ":(\d+)") { $matches[1] } else { $null }

            $annotation = "::$level "
            if ($file) {
                $annotation += "file=$file"
                if ($line) {
                    $annotation += ",line=$line"
                }
                $annotation += " "
            }
            $annotation += "title=$title::$($item.Message)"

            Write-Host $annotation
        }
    }

    # Produce a summary group for the GitHub Actions log
    $passed  = ($allResults | Where-Object { $_.Status -eq "passed" }).Count
    $failed  = ($allResults | Where-Object { $_.Status -eq "failed" }).Count
    $skipped = ($allResults | Where-Object { $_.Status -eq "skipped" }).Count
    $errors  = ($allResults | Where-Object { $_.Status -eq "error" }).Count

    Write-Host "::group::📊 Maintenance Check Summary"
    Write-Host "Passed:  $passed"
    Write-Host "Failed:  $failed"
    Write-Host "Skipped: $skipped"
    Write-Host "Errors:  $errors"
    Write-Host "::endgroup::"
}
