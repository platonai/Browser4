# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
Console reporter for maintenance check results.
Produces colorized, human-readable output using ASCII-safe characters.

.PARAMETER Results
One or more result objects from maintenance check scripts.

.PARAMETER SummaryOnly
If set, output only the summary line, not per-item details.

.EXAMPLE
$r = & .\checks\check-ps1-syntax.ps1
.\reporters\report-console.ps1 -Results $r
#>

param(
    [Parameter(Mandatory = $true, ValueFromPipeline = $true)]
    [PSCustomObject[]]$Results,

    [switch]$SummaryOnly
)

begin {
    $allResults = @()

    # Inline helpers (for standalone reporter use)
    function Format-MaintenanceDuration {
        param([long]$Milliseconds)
        if ($Milliseconds -lt 1000) { return "${Milliseconds}ms" }
        elseif ($Milliseconds -lt 60000) { return "{0:F1}s" -f ($Milliseconds / 1000) }
        else {
            $minutes = [math]::Floor($Milliseconds / 60000)
            $seconds = ($Milliseconds % 60000) / 1000
            return "${minutes}m ${seconds:F0}s"
        }
    }

    function Test-IsMaintenanceMode {
        $mode = [Environment]::GetEnvironmentVariable("MAINTENANCE_MODE")
        if ($mode) { return $mode.ToLower() }
        return "dev"
    }
}

process {
    $allResults += $Results
}

end {
    if ($allResults.Count -eq 0) {
        Write-Host "No maintenance results to report." -ForegroundColor Gray
        return
    }

    # Header (ASCII-safe)
    $timestamp = Get-Date -Format "yyyy-MM-dd HH:mm:ss"
    Write-Host ""
    Write-Host "=========================================================="
    Write-Host "  Maintenance Check Run - $timestamp"
    Write-Host "=========================================================="
    Write-Host ""

    $totalPassed  = 0
    $totalFailed  = 0
    $totalSkipped = 0
    $totalErrors  = 0

    foreach ($result in $allResults) {
        # Status icon & color
        switch ($result.Status) {
            "passed"  { $icon = "[PASS]"; $color = "Green";  $totalPassed++  }
            "failed"  { $icon = "[FAIL]"; $color = "Red";    $totalFailed++  }
            "skipped" { $icon = "[SKIP]"; $color = "Yellow"; $totalSkipped++ }
            "error"   { $icon = "[ERR!]"; $color = "Magenta"; $totalErrors++ }
            default   { $icon = "[????]"; $color = "Gray" }
        }

        # Duration
        $dur = ""
        if ($result.DurationMs) {
            $dur = Format-MaintenanceDuration -Milliseconds $result.DurationMs
            $dur = " (${dur})"
        }

        # Check header line
        $checkLabel = "[$($result.CheckId)] $($result.Name)"
        Write-Host "$icon $checkLabel$dur" -ForegroundColor $color

        # Details line
        if ($result.Details) {
            Write-Host "    $($result.Details)" -ForegroundColor "Gray"
        }

        # Per-item results (unless summary only)
        if (-not $SummaryOnly -and $result.Results -and $result.Results.Count -gt 0) {
            foreach ($item in $result.Results) {
                $itemIcon = switch ($item.Status) {
                    "passed"  { "  OK " }
                    "failed"  { "  XX " }
                    "skipped" { "  -- " }
                    "error"   { "  !! " }
                }
                $itemColor = switch ($item.Status) {
                    "passed"  { "Green" }
                    "failed"  { "Red" }
                    "skipped" { "Yellow" }
                    "error"   { "Magenta" }
                }
                $line = "$itemIcon $($item.Item)"
                if ($item.Message) {
                    $line += " - $($item.Message)"
                }
                Write-Host "    $line" -ForegroundColor $itemColor
            }
        }

        Write-Host ""
    }

    # Summary bar (ASCII-safe)
    $total = $totalPassed + $totalFailed + $totalSkipped + $totalErrors
    Write-Host "----------------------------------------------------------"
    $summaryParts = @()
    if ($totalPassed  -gt 0) { $summaryParts += "PASS: $totalPassed" }
    if ($totalFailed  -gt 0) { $summaryParts += "FAIL: $totalFailed" }
    if ($totalSkipped -gt 0) { $summaryParts += "SKIP: $totalSkipped" }
    if ($totalErrors  -gt 0) { $summaryParts += "ERROR: $totalErrors" }

    $summaryLine = "$($summaryParts -join ' | ') - $total total checks"
    $summaryColor = if ($totalFailed -gt 0 -or $totalErrors -gt 0) { "Red" } else { "Green" }
    Write-Host $summaryLine -ForegroundColor $summaryColor
    Write-Host ""

    # Exit code
    $mode = Test-IsMaintenanceMode
    if ($mode -eq "ci" -and ($totalFailed -gt 0 -or $totalErrors -gt 0)) {
        Write-Host "CI mode: failing due to failures/errors." -ForegroundColor Red
        exit 1
    }
    elseif ($mode -eq "dev") {
        Write-Host "Dev mode: all failures are warnings only." -ForegroundColor Yellow
    }
}
