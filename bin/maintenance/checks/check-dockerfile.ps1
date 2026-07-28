# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
G1 — Docker build integrity: verifies Docker images build cleanly.

.DESCRIPTION
Builds Dockerfile and Dockerfile.fast without cache to verify they
produce valid images. Catches Dockerfile syntax errors, missing files,
and build argument issues early.

.PARAMETER Dockerfiles
Array of Dockerfile paths to check. Default: "Dockerfile", "Dockerfile.fast"

.PARAMETER SkipBuild
If set, skips the actual build and only validates Dockerfile syntax.

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string[]]$Dockerfiles = @("Dockerfile", "Dockerfile.fast"),
    [switch]$SkipBuild
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "G1" -Name "Docker Build Integrity"
$repoRoot = Get-RepositoryRoot

# Check if Docker is available
$dockerAvailable = $null -ne (Get-Command docker -ErrorAction SilentlyContinue)
if (-not $dockerAvailable) {
    $result.Status = "skipped"
    $result.Details = "Docker not available"
    Add-MaintenanceResult -Result $result -Item "Docker" -Status "skipped" -Message "docker command not found"
    $result
    return
}

foreach ($df in $Dockerfiles) {
    $dockerfilePath = Join-Path $repoRoot $df
    if (-not (Test-Path $dockerfilePath)) {
        Add-MaintenanceResult -Result $result -Item $df -Status "skipped" -Message "File not found"
        continue
    }

    # Dockerfile.fast requires a pre-built Browser4.jar — skip if missing
    if ($df -eq "Dockerfile.fast" -and -not $SkipBuild) {
        $fastJar = Join-Path $repoRoot "browser4-apps\browser4-standalone\target\Browser4.jar"
        if (-not (Test-Path $fastJar)) {
            Add-MaintenanceResult -Result $result -Item $df -Status "skipped" `
                -Message "Browser4.jar not built — run 'mvn package -pl browser4-apps/browser4-standalone -am -DskipTests' first"
            continue
        }
    }

    $timeoutSecs = Get-MaintenanceThreshold -Section "Performance" -Key "MaxDockerBuildMinutes" -Default 25
    $timeoutSecs = [int]$timeoutSecs * 60

    if ($SkipBuild) {
        # Just validate the Dockerfile syntax
        $valResult = Invoke-MaintenanceStep `
            -StepName "docker build --check $df" `
            -WorkingDirectory $repoRoot `
            -TimeoutSeconds 30 `
            -ScriptBlock {
                docker build --check -f $dockerfilePath . 2>&1
                $LASTEXITCODE
            }
        if ($valResult.ExitCode -eq 0) {
            Add-MaintenanceResult -Result $result -Item $df -Status "passed" -Message "Syntax OK"
        }
        else {
            $errTail = if ($valResult.Stdout) {
                ($valResult.Stdout -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -Last 2) -join " | "
            } else { "unknown error" }
            Add-MaintenanceResult -Result $result -Item $df -Status "failed" -Message "Syntax errors — $errTail"
        }
    }
    else {
        # Full build (no-cache to catch stale layer issues)
        $tag = "maintenance-check-$(Get-Date -Format 'yyyyMMddHHmmss')"
        $buildResult = Invoke-MaintenanceStep `
            -StepName "docker build $df" `
            -WorkingDirectory $repoRoot `
            -TimeoutSeconds $timeoutSecs `
            -ScriptBlock {
                docker build -t $tag -f $dockerfilePath --no-cache . 2>&1
                $exit = $LASTEXITCODE
                # Clean up image
                docker rmi $tag 2>&1 | Out-Null
                $exit
            }

        if ($buildResult.ExitCode -eq 0) {
            Add-MaintenanceResult -Result $result -Item $df -Status "passed" -Message "Build succeeded ($($buildResult.DurationMs)ms)"
        }
        else {
            $errTail = if ($buildResult.Stdout) {
                ($buildResult.Stdout -split "`n" | Where-Object { $_ -match '\S' } | Select-Object -Last 2) -join " | "
            } else { "unknown error" }
            Add-MaintenanceResult -Result $result -Item $df -Status "failed" -Message "Build failed — $errTail"
        }
    }
}

Set-MaintenanceResultSummary -Result $result
$result
