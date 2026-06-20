# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
E3 — Release asset validation: verifies JAR and CLI binary integrity.

.DESCRIPTION
Post-release check that validates:
  - JAR file exists and has reasonable minimum size
  - JAR has a valid Main-Class or Start-Class manifest entry
  - CLI binary exists with expected size range

.PARAMETER JarPath
Path to the built JAR. Default: target/Browser4.jar or browser4-apps/browser4-standalone/target/Browser4.jar

.PARAMETER MinJarSizeMB
Minimum expected JAR size in MB. Default: 10

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string]$JarPath = "",
    [int]$MinJarSizeMB = 10
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "E3" -Name "Release Asset Validation"
$repoRoot = Get-RepositoryRoot

# ── Find JAR ──
if (-not $JarPath) {
    $candidates = @(
        "target\Browser4.jar",
        "browser4-apps\browser4-standalone\target\Browser4.jar",
        "browser4-apps\browser4-bundle\target\Browser4.jar"
    )
    foreach ($c in $candidates) {
        $p = Join-Path $repoRoot $c
        if (Test-Path $p) {
            $JarPath = $p
            break
        }
    }
}

$minJarBytes = $MinJarSizeMB * 1MB

if ($JarPath -and (Test-Path $JarPath)) {
    $jarSize = (Get-Item $JarPath).Length
    $jarSizeMB = [math]::Round($jarSize / 1MB, 1)

    if ($jarSize -ge $minJarBytes) {
        Add-MaintenanceResult -Result $result -Item "JAR size" -Status "passed" -Message "${jarSizeMB} MB"
    }
    else {
        Add-MaintenanceResult -Result $result -Item "JAR size" -Status "failed" -Message "${jarSizeMB} MB < ${MinJarSizeMB} MB minimum"
    }

    # Check manifest
    try {
        Add-Type -AssemblyName System.IO.Compression.FileSystem
        $jar = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
        $manifestEntry = $jar.GetEntry("META-INF/MANIFEST.MF")
        if ($manifestEntry) {
            $stream = $manifestEntry.Open()
            $reader = New-Object System.IO.StreamReader($stream)
            $manifest = $reader.ReadToEnd()
            $reader.Close()
            if ($manifest -match 'Start-Class:\s*(\S+)' -or $manifest -match 'Main-Class:\s*(\S+)') {
                Add-MaintenanceResult -Result $result -Item "JAR manifest" -Status "passed" -Message "Main class: $($matches[1])"
            }
            else {
                Add-MaintenanceResult -Result $result -Item "JAR manifest" -Status "failed" -Message "No Main-Class or Start-Class found"
            }
        }
        else {
            Add-MaintenanceResult -Result $result -Item "JAR manifest" -Status "failed" -Message "MANIFEST.MF not found in JAR"
        }
        $jar.Dispose()
    }
    catch {
        Add-MaintenanceResult -Result $result -Item "JAR manifest" -Status "error" -Message "Cannot read: $($_.Exception.Message)"
    }
}
else {
    Add-MaintenanceResult -Result $result -Item "Browser4.jar" -Status "skipped" -Message "JAR not found — run mvn package first"
}

# ── CLI binary check ──
$cliPaths = @(
    "cli\browser4-cli\target\release\browser4-cli.exe",
    "cli\browser4-cli\target\release\browser4-cli"
)
foreach ($cp in $cliPaths) {
    $fullCp = Join-Path $repoRoot $cp
    if (Test-Path $fullCp) {
        $cliSize = (Get-Item $fullCp).Length
        $cliSizeMB = [math]::Round($cliSize / 1MB, 1)
        if ($cliSizeMB -gt 1) {
            Add-MaintenanceResult -Result $result -Item $cp -Status "passed" -Message "${cliSizeMB} MB"
        }
        else {
            Add-MaintenanceResult -Result $result -Item $cp -Status "failed" -Message "${cliSizeMB} MB — unexpectedly small"
        }
        break
    }
}

Set-MaintenanceResultSummary -Result $result
$result
