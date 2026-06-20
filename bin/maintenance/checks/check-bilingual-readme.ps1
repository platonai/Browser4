# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
C4 — Bilingual README consistency: checks README.md ↔ README.zh.md alignment.

.DESCRIPTION
Compares the section headers between README.md (English) and README.zh.md
(Chinese) to verify they cover the same topics. Reports missing or extra
sections in either file.

.PARAMETER MinAlignment
Minimum section alignment ratio. Default from thresholds (0.80).

.OUTPUTS
Standard maintenance result object.
#>

param(
    [double]$MinAlignment = 0
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "C4" -Name "Bilingual README Consistency"
$repoRoot = Get-RepositoryRoot

if ($MinAlignment -le 0) {
    $MinAlignment = Get-MaintenanceThreshold -Section "Documentation" -Key "BilingualMinAlignment" -Default 0.80
}

$enPath = Join-Path $repoRoot "README.md"
$zhPath = Join-Path $repoRoot "README.zh.md"

if (-not (Test-Path $enPath) -or -not (Test-Path $zhPath)) {
    if (-not (Test-Path $enPath)) {
        Add-MaintenanceResult -Result $result -Item "README.md" -Status "error" -Message "File not found"
    }
    if (-not (Test-Path $zhPath)) {
        Add-MaintenanceResult -Result $result -Item "README.zh.md" -Status "error" -Message "File not found"
    }
    Set-MaintenanceResultSummary -Result $result
    $result
    return
}

# ── Extract section headers ──
function Get-SectionHeaders {
    param([string]$FilePath)
    $headers = @()
    $content = Get-Content $FilePath -Encoding UTF8
    foreach ($line in $content) {
        if ($line -match '^##\s+(.+)') {
            $headers += $matches[1].Trim()
        }
    }
    return $headers
}

$enHeaders = Get-SectionHeaders $enPath
$zhHeaders = Get-SectionHeaders $zhPath

# ── Compare ──
$enSet = [System.Collections.Generic.HashSet[string]]::new([string[]]$enHeaders)
$zhSet = [System.Collections.Generic.HashSet[string]]::new([string[]]$zhHeaders)

$onlyEn = $enHeaders | Where-Object { $_ -notin $zhHeaders }
$onlyZh = $zhHeaders | Where-Object { $_ -notin $enHeaders }
$common  = $enHeaders | Where-Object { $_ -in $zhHeaders }

$totalSections = [Math]::Max($enHeaders.Count, $zhHeaders.Count)
$commonCount = @($common).Count
$alignment = if ($totalSections -gt 0) { [math]::Round($commonCount / $totalSections, 2) } else { 1.0 }

Add-MaintenanceResult -Result $result -Item "README.md" -Status "passed" -Message "$($enHeaders.Count) sections"
Add-MaintenanceResult -Result $result -Item "README.zh.md" -Status "passed" -Message "$($zhHeaders.Count) sections"

if (@($onlyEn).Count -gt 0) {
    Add-MaintenanceResult -Result $result -Item "Only in EN" -Status "failed" -Message ($onlyEn -join ', ')
}
if ($onlyZh.Count -gt 0) {
    Add-MaintenanceResult -Result $result -Item "Only in ZH" -Status "failed" -Message ($onlyZh -join ', ')
}

if ($alignment -ge $MinAlignment) {
    Add-MaintenanceResult -Result $result -Item "Alignment" -Status "passed" -Message "$($alignment * 100)% ($commonCount/$totalSections sections)"
}
else {
    Add-MaintenanceResult -Result $result -Item "Alignment" -Status "failed" -Message "$($alignment * 100)% < threshold $($MinAlignment * 100)%"
}

Set-MaintenanceResultSummary -Result $result
$result
