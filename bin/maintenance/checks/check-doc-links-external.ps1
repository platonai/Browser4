# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
C2 — External link validation: verifies external URLs in documentation.

.DESCRIPTION
Scans all .md files for external HTTP/HTTPS links and validates they are
reachable (HTTP 2xx/3xx). Uses HEAD requests with a configurable timeout.

.PARAMETER SearchPaths
Directories/files to scan.

.PARAMETER TimeoutSecs
Per-URL timeout in seconds. Default: 10

.PARAMETER MaxConcurrency
Max parallel requests. Default: 10

.PARAMETER SkipLocalhost
Skip localhost URLs. Default: $true

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string[]]$SearchPaths = @("docs", "README.md", "README.zh.md", "AGENTS.md", "skill"),
    [int]$TimeoutSecs = 10,
    [int]$MaxConcurrency = 10,
    [switch]$SkipLocalhost
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "C2" -Name "External Documentation Links"
$repoRoot = Get-RepositoryRoot

# ── Collect all external URLs ──
$urls = @{}
foreach ($searchPath in $SearchPaths) {
    $fullPath = Resolve-MaintenancePath $searchPath
    if (-not (Test-Path $fullPath)) { continue }

    $mdFiles = if (Test-Path $fullPath -PathType Container) {
        Get-ChildItem -Path $fullPath -Filter "*.md" -Recurse -File -ErrorAction SilentlyContinue
    } else {
        @(Get-Item $fullPath)
    }

    foreach ($file in $mdFiles) {
        $relPath = $file.FullName.Replace($repoRoot, "").TrimStart("\", "/")
        $content = Get-Content $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
        if (-not $content) { continue }

        $linkMatches = [regex]::Matches($content, '\[([^\]]*)\]\((https?://[^)\s]+)\)')
        foreach ($m in $linkMatches) {
            $url = $m.Groups[2].Value
            if ($SkipLocalhost -and ($url -match '://localhost|://127\.0\.0\.1')) { continue }
            if (-not $urls.ContainsKey($url)) {
                $urls[$url] = @()
            }
            $urls[$url] += $relPath
        }
    }
}

if ($urls.Count -eq 0) {
    $result.Status = "skipped"
    $result.Details = "No external URLs found"
    $result
    return
}

# ── Check each URL ──
$checked = 0
$broken = 0
$urlList = $urls.Keys | Sort-Object

foreach ($url in $urlList) {
    $sources = $urls[$url] -join ", "
    $checked++
    try {
        $response = Invoke-WebRequest -Uri $url -Method Head -TimeoutSec $TimeoutSecs -ErrorAction Stop
        $statusCode = [int]$response.StatusCode
        if ($statusCode -ge 200 -and $statusCode -lt 400) {
            Add-MaintenanceResult -Result $result -Item $url -Status "passed" -Message "HTTP $statusCode"
        }
        else {
            Add-MaintenanceResult -Result $result -Item $url -Status "failed" -Message "HTTP $statusCode (from: $sources)"
            $broken++
        }
    }
    catch {
        Add-MaintenanceResult -Result $result -Item $url -Status "failed" -Message "Unreachable: $($_.Exception.Message) (from: $sources)"
        $broken++
    }
}

$result.Details = "$checked URLs checked, $broken unreachable"

Set-MaintenanceResultSummary -Result $result
$result
