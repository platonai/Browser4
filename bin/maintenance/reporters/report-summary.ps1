# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
Markdown summary reporter for maintenance check results.
Produces a formatted Markdown block suitable for Slack, email, or
GitHub job summaries.

.PARAMETER Results
One or more result objects from maintenance check scripts.

.PARAMETER OutputFile
Path to write the Markdown file. If omitted, outputs to stdout.

.EXAMPLE
$r = & .\checks\check-coverage.ps1
.\reporters\report-summary.ps1 -Results $r -OutputFile summary.md
#>

param(
    [Parameter(Mandatory = $true, ValueFromPipeline = $true)]
    [PSCustomObject[]]$Results,

    [string]$OutputFile
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

    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    $passed  = ($allResults | Where-Object { $_.Status -eq "passed" }).Count
    $failed  = ($allResults | Where-Object { $_.Status -eq "failed" }).Count
    $skipped = ($allResults | Where-Object { $_.Status -eq "skipped" }).Count
    $errors  = ($allResults | Where-Object { $_.Status -eq "error" }).Count
    $total   = $allResults.Count

    $overallIcon = if ($failed -gt 0 -or $errors -gt 0) { "❌" } elseif ($skipped -gt 0) { "⚠️" } else { "✅" }

    $sb = [System.Text.StringBuilder]::new()

    [void]$sb.AppendLine("# Maintenance Report — $timestamp")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("## Summary")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("| Status   | Count |")
    [void]$sb.AppendLine("|----------|-------|")
    [void]$sb.AppendLine("| ✅ Passed  | $passed  |")
    [void]$sb.AppendLine("| ❌ Failed  | $failed  |")
    [void]$sb.AppendLine("| ⚠️ Skipped | $skipped |")
    [void]$sb.AppendLine("| ⚡ Errors  | $errors  |")
    [void]$sb.AppendLine("| **Total**  | **$total**  |")
    [void]$sb.AppendLine("")
    [void]$sb.AppendLine("**Overall: $overallIcon $passed/$total checks passed**")
    [void]$sb.AppendLine("")

    if ($failed -gt 0 -or $errors -gt 0) {
        [void]$sb.AppendLine("## ❌ Failures & Errors")
        [void]$sb.AppendLine("")
        $problemResults = $allResults | Where-Object { $_.Status -eq "failed" -or $_.Status -eq "error" }
        foreach ($result in $problemResults) {
            $icon = if ($result.Status -eq "error") { "⚡" } else { "❌" }
            [void]$sb.AppendLine("### $icon [$($result.CheckId)] $($result.Name)")
            [void]$sb.AppendLine("")
            if ($result.Details) {
                [void]$sb.AppendLine("**$($result.Details)**")
                [void]$sb.AppendLine("")
            }
            if ($result.Results -and $result.Results.Count -gt 0) {
                $badItems = $result.Results | Where-Object { $_.Status -ne "passed" }
                foreach ($item in $badItems) {
                    [void]$sb.AppendLine("- **$($item.Item)** — $($item.Message)")
                }
                [void]$sb.AppendLine("")
            }
        }
    }

    if ($skipped -gt 0) {
        [void]$sb.AppendLine("## ⚠️ Skipped")
        [void]$sb.AppendLine("")
        $skippedResults = $allResults | Where-Object { $_.Status -eq "skipped" }
        foreach ($result in $skippedResults) {
            [void]$sb.AppendLine("- [$($result.CheckId)] $($result.Name) — $($result.Details)")
        }
        [void]$sb.AppendLine("")
    }

    $output = $sb.ToString()

    if ($OutputFile) {
        $output | Out-File -FilePath $OutputFile -Encoding UTF8
        Write-Host "Summary → $OutputFile" -ForegroundColor Gray
    }
    else {
        Write-Host $output
    }
}
