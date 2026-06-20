# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
A7 — Dead code detection: finds unused imports and dead code.

.DESCRIPTION
Runs Kotlin compiler with -Xlint:unused and cargo clippy with
dead_code lint to detect unused imports, private functions, and
dead code branches.

.PARAMETER MaxWarnings
Maximum allowed warnings before failing. Default from thresholds.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [int]$MaxWarnings = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "A7" -Name "Dead Code Detection"
$repoRoot = Get-RepositoryRoot

if ($MaxWarnings -le 0) {
    $MaxWarnings = Get-MaintenanceThreshold -Section "CodeQuality" -Key "MaxDeadCodeWarnings" -Default 100
}

$mvnCmd = if (Test-IsWindows) { ".\mvnw.cmd" } else { "./mvnw" }
$totalWarnings = 0

# ── Kotlin compiler warnings ──
$kotlinResult = Invoke-MaintenanceStep `
    -StepName "Kotlin Lint" `
    -WorkingDirectory $repoRoot `
    -TimeoutSeconds 600 `
    -ScriptBlock {
        & $mvnCmd compile -P all-main-modules -DskipTests -q 2>&1 | Select-String -Pattern "unused|UNUSED|never used|is never used"
        $LASTEXITCODE
    }

$unusedLines = ($kotlinResult.Stdout -split "`n" | Select-String -Pattern "unused|UNUSED|never used" | Where-Object { $_ -notmatch "^\s*$" })
foreach ($line in $unusedLines) {
    $trimmed = $line.ToString().Trim()
    if ($trimmed) {
        $totalWarnings++
        Add-MaintenanceResult -Result $result -Item $trimmed -Status "failed" -Message "Unused/dead code"
    }
}

# ── Rust clippy dead_code ──
$cliDir = Join-Path $repoRoot "cli\browser4-cli"
if (Test-Path (Join-Path $cliDir "Cargo.toml")) {
    $rustResult = Invoke-MaintenanceStep `
        -StepName "Cargo Clippy Dead Code" `
        -WorkingDirectory $cliDir `
        -TimeoutSeconds 300 `
        -ScriptBlock {
            cargo clippy -- -W dead_code -W unused_imports 2>&1 | Select-String -Pattern "warning:|dead_code|unused"
            $LASTEXITCODE
        }

    $rustWarnings = ($rustResult.Stdout -split "`n" | Select-String -Pattern "warning:|dead_code|unused" | Where-Object { $_ -notmatch "^\s*$" })
    foreach ($line in $rustWarnings) {
        $trimmed = $line.ToString().Trim()
        if ($trimmed) {
            $totalWarnings++
            Add-MaintenanceResult -Result $result -Item $trimmed -Status "failed" -Message "Rust dead code"
        }
    }
}

if ($totalWarnings -le $MaxWarnings) {
    Add-MaintenanceResult -Result $result -Item "Total" -Status "passed" -Message "$totalWarnings warnings (threshold: $MaxWarnings)"
}
else {
    Add-MaintenanceResult -Result $result -Item "Total" -Status "failed" -Message "$totalWarnings > $MaxWarnings"
}

Set-MaintenanceResultSummary -Result $result
$result
