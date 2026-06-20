# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
A3 — Static analysis: runs Qodana JVM code quality analysis.

.DESCRIPTION
Runs the JetBrains Qodana JVM linter (configured in qodana.yaml)
against the project and checks quality gate thresholds:
  - MaxAnySeverity (default 15)
  - MaxCriticalSeverity (default 5)

Requires Docker to run the Qodana container.

.PARAMETER CacheDir
Qodana cache directory. Default: target/qodana-cache

.PARAMETER ReportDir
Qodana report output directory. Default: target/qodana-report

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$CacheDir = "target\qodana-cache",
    [string]$ReportDir = "target\qodana-report"
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "A3" -Name "Static Analysis (Qodana)"
$repoRoot = Get-RepositoryRoot

$dockerAvailable = $null -ne (Get-Command docker -ErrorAction SilentlyContinue)
if (-not $dockerAvailable) {
    $result.Status = "skipped"
    $result.Details = "Docker not available"
    Add-MaintenanceResult -Result $result -Item "Qodana" -Status "skipped" -Message "docker command not found"
    $result
    return
}

$qodanaYaml = Join-Path $repoRoot "qodana.yaml"
if (-not (Test-Path $qodanaYaml)) {
    Add-MaintenanceResult -Result $result -Item "qodana.yaml" -Status "error" -Message "qodana.yaml not found"
    Set-MaintenanceResultSummary -Result $result
    $result
    return
}

$cacheDirAbs = Resolve-MaintenancePath $CacheDir
$reportDirAbs = Resolve-MaintenancePath $ReportDir
$resultDirAbs = Join-Path $repoRoot "target\qodana"

# Clean up previous results
if (Test-Path $resultDirAbs) {
    Remove-Item -Path $resultDirAbs -Recurse -Force -ErrorAction SilentlyContinue
}

$timeoutSecs = Get-MaintenanceThreshold -Section "Performance" -Key "MaxCompilationMinutes" -Default 15
$timeoutSecs = [int]$timeoutSecs * 60 * 2  # Qodana is slower than compilation

$qodanaResult = Invoke-MaintenanceStep `
    -StepName "Qodana" `
    -WorkingDirectory $repoRoot `
    -TimeoutSeconds $timeoutSecs `
    -ScriptBlock {
        docker run --rm `
            -v "${using:repoRoot}:/data/project" `
            -v "${using:cacheDirAbs}:/data/cache" `
            -v "${using:reportDirAbs}:/data/results" `
            jetbrains/qodana-jvm:2026.1 `
            --results-dir /data/results `
            --cache-dir /data/cache `
            --project-dir /data/project `
            --profile-name "qodana.starter" 2>&1
        $LASTEXITCODE
    }

# ── Parse Qodana results if available ──
$qodanaReport = Join-Path $reportDirAbs "qodana.sarif.json"
if (Test-Path $qodanaReport) {
    try {
        $sarif = Get-Content $qodanaReport -Raw | ConvertFrom-Json
        $totalProblems = 0
        $criticalProblems = 0
        foreach ($run in $sarif.runs) {
            foreach ($res in $run.results) {
                $totalProblems++
                if ($res.level -eq "error") { $criticalProblems++ }
            }
        }
        Add-MaintenanceResult -Result $result -Item "Qodana SARIF" -Status "passed" -Message "$totalProblems problems ($criticalProblems critical)"

        # Check thresholds
        $maxAny = Get-MaintenanceThreshold -Section "Qodana" -Key "MaxAnySeverity" -Default 15
        $maxCrit = Get-MaintenanceThreshold -Section "Qodana" -Key "MaxCriticalSeverity" -Default 5
        if ($totalProblems -gt $maxAny) {
            Add-MaintenanceResult -Result $result -Item "Any severity" -Status "failed" -Message "$totalProblems > $maxAny"
        }
        if ($criticalProblems -gt $maxCrit) {
            Add-MaintenanceResult -Result $result -Item "Critical severity" -Status "failed" -Message "$criticalProblems > $maxCrit"
        }
    }
    catch {
        Add-MaintenanceResult -Result $result -Item "Qodana SARIF" -Status "error" -Message "Cannot parse SARIF: $($_.Exception.Message)"
    }
}
else {
    Add-MaintenanceResult -Result $result -Item "Qodana" -Status "error" -Message "No SARIF report generated (exit code $($qodanaResult.ExitCode))"
}

Set-MaintenanceResultSummary -Result $result
$result
