# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
C1 — Internal documentation link validation.

.DESCRIPTION
Validates all internal .md cross-reference links in documentation files.
Uses bin/quality/fix-links.py under the hood with --check-only mode.
Detects:
  - Broken internal links (file does not exist)
  - Broken anchor links (section header missing)

.PARAMETER SearchPaths
Directories/files to scan. Default: "docs/", "README.md", "README.zh.md",
"skills/", "coworker/", "AGENTS.md"

.PARAMETER ExcludePatterns
Patterns to exclude. Default: "node_modules", "target", ".git"

.OUTPUTS
Standard maintenance result object.
#>

param(
    [string[]]$SearchPaths = @("docs", "README.md", "README.zh.md", "skill", "coworker", "AGENTS.md", "browser4-agentic/src/main/resources/skills"),
    [string[]]$ExcludePatterns = @("node_modules", "target", ".git")
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Continue"

$ScriptDir = $PSScriptRoot
. (Join-Path $ScriptDir "..\common\MaintenanceUtil.ps1")

$result = New-MaintenanceResult -CheckId "C1" -Name "Internal Documentation Links"
$repoRoot = Get-RepositoryRoot

# ── Locate fix-links.py ──
$fixLinksPath = Join-Path $repoRoot "bin\quality\fix-links.py"
$hasFixLinks = Test-Path $fixLinksPath

if (-not $hasFixLinks) {
    # Fall back to PowerShell-based link checking
    Write-MaintenanceLog -Level "WARN" -Component "C1" -Message "fix-links.py not found, using basic PowerShell checker"

    $checkedCount = 0
    $brokenCount = 0

    foreach ($searchPath in $SearchPaths) {
        $fullPath = Resolve-MaintenancePath $searchPath
        if (-not (Test-Path $fullPath)) {
            Add-MaintenanceResult -Result $result -Item $searchPath -Status "skipped" -Message "Path not found"
            continue
        }

        $mdFiles = if (Test-Path $fullPath -PathType Container) {
            Get-ChildItem -Path $fullPath -Filter "*.md" -Recurse -File -ErrorAction SilentlyContinue
        } else {
            @(Get-Item $fullPath)
        }

        foreach ($file in $mdFiles) {
            $relPath = $file.FullName.Replace($repoRoot, "").TrimStart("\", "/")
            $content = Get-Content $file.FullName -Raw -Encoding UTF8 -ErrorAction SilentlyContinue
            if (-not $content) { continue }

            # Find markdown links: [text](path) or [text](path#anchor)
            $linkPattern = '\[([^\]]*)\]\(([^)]+)\)'
            $matches = [regex]::Matches($content, $linkPattern)
            $fileIssues = @()

            foreach ($m in $matches) {
                $linkText = $m.Groups[1].Value
                $linkTarget = $m.Groups[2].Value

                # Skip external URLs and mailto
                if ($linkTarget -match '^(https?://|mailto:|#)') { continue }

                # Resolve relative to the file's directory
                $fileDir = Split-Path $file.FullName -Parent
                $targetPath = $linkTarget -replace '#.*$', ''  # Strip anchor

                if ([string]::IsNullOrWhiteSpace($targetPath)) { continue }  # Anchor-only on same page

                $resolved = Join-Path $fileDir $targetPath
                $resolved = [System.IO.Path]::GetFullPath($resolved)

                if (-not (Test-Path $resolved)) {
                    $fileIssues += "Broken link: `"$linkText`" -> $linkTarget (resolved: $resolved)"
                }
                $checkedCount++
            }

            if ($fileIssues.Count -eq 0) {
                Add-MaintenanceResult -Result $result -Item $relPath -Status "passed" -Message "All links valid"
            }
            else {
                foreach ($issue in $fileIssues) {
                    Add-MaintenanceResult -Result $result -Item "$relPath" -Status "failed" -Message $issue
                    $brokenCount++
                }
            }
        }
    }

    $result.Details = "$checkedCount links checked, $brokenCount broken"
}
else {
    # Use fix-links.py
    $pyResult = Invoke-MaintenanceStep `
        -StepName "fix-links.py" `
        -WorkingDirectory $repoRoot `
        -TimeoutSeconds 300 `
        -ScriptBlock {
            python bin/quality/fix-links.py --check-only 2>&1
            $LASTEXITCODE
        }

    if ($pyResult.ExitCode -eq 0) {
        Add-MaintenanceResult -Result $result -Item "All docs" -Status "passed" -Message "All internal links valid"
    }
    else {
        Add-MaintenanceResult -Result $result -Item "All docs" -Status "failed" -Message "Broken links detected"
    }
}

Set-MaintenanceResultSummary -Result $result
$result
