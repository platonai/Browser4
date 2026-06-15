# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

# Dependency Health Check Script for Browser4 (Windows)
# 依赖健康检查脚本
# Usage: .\check-dependencies.ps1 [-Full]

param(
    [switch]$Full
)

$ErrorActionPreference = "Stop"

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$ProjectRoot = Resolve-Path "$ScriptDir\..\.."
$ReportDir = Join-Path $ProjectRoot "target\dependency-reports"

$MvnLogFlags = "-Dorg.slf4j.simpleLogger.defaultLogLevel=warn"
$VersionsLogFlags = "-Dorg.slf4j.simpleLogger.log.org.codehaus.mojo.versions=info"
$DependencyLogFlags = "-Dorg.slf4j.simpleLogger.log.org.apache.maven.plugins.dependency=info"

# Create report directory
New-Item -ItemType Directory -Force -Path $ReportDir | Out-Null

Write-Host "==================================" -ForegroundColor Blue
Write-Host "Browser4 Dependency Health Check" -ForegroundColor Blue
Write-Host "==================================" -ForegroundColor Blue
Write-Host ""

function Print-Header {
    param($Message)
    Write-Host ""
    Write-Host ">>> $Message" -ForegroundColor Blue
    Write-Host "-----------------------------------"
}

function Test-CommandExists {
    param($Command)
    $null = Get-Command $Command -ErrorAction SilentlyContinue
    return $?
}

# Change to project root
Set-Location $ProjectRoot

# Check prerequisites
Print-Header "Checking Prerequisites"

if (-not (Test-CommandExists "java")) {
    Write-Host "Error: Java is not installed" -ForegroundColor Red
    exit 1
}

# Use Maven Wrapper if available
$MvnCmd = ".\mvnw.cmd"
if (-not (Test-Path $MvnCmd)) {
    $MvnCmd = "mvn"
    if (-not (Test-CommandExists "mvn")) {
        Write-Host "Error: Maven is not installed" -ForegroundColor Red
        exit 1
    }
}

$javaVersion = java -version 2>&1 | Select-Object -First 1
Write-Host "✓ Java version: $javaVersion" -ForegroundColor Green
Write-Host "✓ Maven command: $MvnCmd" -ForegroundColor Green

# 1. Check for dependency updates
Print-Header "1. Checking for Dependency Updates"
Write-Host "Generating dependency update report..."

$depUpdatesFile = Join-Path $ReportDir "dependency-updates.txt"
& $MvnCmd versions:display-dependency-updates $MvnLogFlags $VersionsLogFlags "-Dversions.outputFile=$depUpdatesFile" 2>&1 | Out-Null

$content = Get-Content $depUpdatesFile -Raw
if ($content -match "No dependencies in Dependencies") {
    Write-Host "✓ All dependencies are up to date" -ForegroundColor Green
} else {
    Write-Host "⚠ Updates available. See: $depUpdatesFile" -ForegroundColor Yellow
    Write-Host ""
    Write-Host "Summary of updates:"
    Get-Content $depUpdatesFile | Select-Object -First 25
}

# 2. Check for plugin updates
Print-Header "2. Checking for Plugin Updates"
Write-Host "Generating plugin update report..."

$pluginUpdatesFile = Join-Path $ReportDir "plugin-updates.txt"
& $MvnCmd versions:display-plugin-updates $MvnLogFlags $VersionsLogFlags "-Dversions.outputFile=$pluginUpdatesFile" 2>&1 | Out-Null

$content = Get-Content $pluginUpdatesFile -Raw
if ($content -match "All plugins have a version specified" -or
    $content -match "All plugins with a version specified are using the latest versions") {
    Write-Host "✓ All plugins are up to date" -ForegroundColor Green
} else {
    Write-Host "⚠ Plugin updates available. See: $pluginUpdatesFile" -ForegroundColor Yellow
}

# 3. Check for property updates
Print-Header "3. Checking for Property Updates"
Write-Host "Generating property update report..."

$propUpdatesFile = Join-Path $ReportDir "property-updates.txt"
& $MvnCmd versions:display-property-updates $MvnLogFlags $VersionsLogFlags "-Dversions.outputFile=$propUpdatesFile" 2>&1 | Out-Null
Write-Host "✓ Report generated: $propUpdatesFile" -ForegroundColor Green

# 4. Analyze dependencies
Print-Header "4. Analyzing Dependency Usage"
Write-Host "Analyzing declared dependencies..."

$depAnalysisFile = Join-Path $ReportDir "dependency-analysis.txt"
& $MvnCmd dependency:analyze $MvnLogFlags $DependencyLogFlags -DscriptableOutput=true 2>&1 | Set-Content -Path $depAnalysisFile -Encoding UTF8

$content = Get-Content $depAnalysisFile -Raw
if ($content -match "Used undeclared dependencies") {
    Write-Host "⚠ Found used undeclared dependencies" -ForegroundColor Yellow
}

if ($content -match "Unused declared dependencies") {
    Write-Host "⚠ Found unused declared dependencies" -ForegroundColor Yellow
}

Write-Host "✓ Analysis complete. See: $depAnalysisFile" -ForegroundColor Green

# 5. Check for duplicate dependencies
Print-Header "5. Checking for Duplicate Dependencies"
Write-Host "Generating dependency tree..."

$depTreeFile = Join-Path $ReportDir "dependency-tree.txt"
if (Test-Path $depTreeFile) { Remove-Item $depTreeFile }
& $MvnCmd dependency:tree $MvnLogFlags $DependencyLogFlags -DoutputFile="$depTreeFile" -DappendOutput=true 2>&1 | Out-Null

$content = Get-Content $depTreeFile -Raw
if ($content -match "omitted for conflict") {
    Write-Host "⚠ Version conflicts detected in dependency tree" -ForegroundColor Yellow
    Write-Host "Conflicts found:"
    Get-Content $depTreeFile | Select-String "omitted for conflict" | Select-Object -First 10
    Write-Host ""
    Write-Host "See full report: $depTreeFile"
} else {
    Write-Host "✓ No obvious version conflicts detected" -ForegroundColor Green
}

# 6. Security vulnerability check (if enabled)
if ($Full) {
    Print-Header "6. Security Vulnerability Check"
    Write-Host "Running OWASP Dependency Check (this may take several minutes)..."
    Write-Host "Note: First run will download CVE database" -ForegroundColor Yellow

    $securityLogFile = Join-Path $ReportDir "security-check.log"
    # Note: dependency-check plugin might use a different logger package, usually org.owasp
    $securityResult = & $MvnCmd dependency-check:check $MvnLogFlags 2>&1 | Out-File -FilePath $securityLogFile

    if ($LASTEXITCODE -eq 0) {
        Write-Host "✓ No advanced severity vulnerabilities found" -ForegroundColor Green
    } else {
        Write-Host "✗ Security vulnerabilities detected!" -ForegroundColor Red
        Write-Host "See report: target\dependency-check-report.html"
    }
} else {
    Write-Host ""
    Write-Host "Tip: Run with -Full flag to include security vulnerability check" -ForegroundColor Yellow
}

# 7. Generate summary report
Print-Header "7. Summary Report"

$summaryFile = Join-Path $ReportDir "summary.txt"
$summaryContent = @"
Browser4 Dependency Health Report
Generated: $(Get-Date)

Report Location: $ReportDir

Files Generated:
  - dependency-updates.txt: Available dependency updates
  - plugin-updates.txt: Available plugin updates
  - property-updates.txt: Available property updates
  - dependency-analysis.txt: Dependency usage analysis
  - dependency-tree.txt: Full dependency tree
"@

if ($Full) {
    $summaryContent += @"

  - security-check.log: Security vulnerability scan log
  - ..\dependency-check-report.html: Detailed security report
"@
}

$pomCount = (Get-ChildItem -Path . -Filter "pom.xml" -Recurse).Count
$summaryContent += @"

Quick Stats:
  Total POM files: $pomCount
"@

$summaryContent | Out-File -FilePath $summaryFile
Write-Host $summaryContent

# 8. Recommendations
Print-Header "8. Recommendations"

Write-Host ""
Write-Host "Next Steps:"
Write-Host "  1. Review dependency updates in $ReportDir\dependency-updates.txt"
Write-Host "  2. Prioritize security updates (run with -Full to check)"
Write-Host "  3. Create upgrade plan based on priority (see docs\dependency-upgrade-plan.md)"
Write-Host "  4. Test upgrades in feature branch before merging"
Write-Host ""
Write-Host "Useful Commands:"
Write-Host "  # Update specific dependency:"
Write-Host "  $MvnCmd versions:use-dep-version -D`"includes=groupId:artifactId`" -D`"depVersion=x.y.z`""
Write-Host ""
Write-Host "  # Update all properties to latest:"
Write-Host "  $MvnCmd versions:update-properties"
Write-Host ""
Write-Host "  # Run full security scan:"
Write-Host "  $MvnCmd dependency-check:check"
Write-Host ""

Write-Host "==================================" -ForegroundColor Green
Write-Host "Health check completed!" -ForegroundColor Green
Write-Host "==================================" -ForegroundColor Green
Write-Host ""
Write-Host "Reports saved to: " -NoNewline
Write-Host $ReportDir -ForegroundColor Blue
