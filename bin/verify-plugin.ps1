# =============================================================================
# Browser4 Plugin Verification Script (PowerShell)
# =============================================================================
# Validates that a built plugin JAR has the correct structure for deployment.
#
# Usage: .\verify-plugin.ps1 <path-to-plugin.jar>
# =============================================================================

param(
    [Parameter(Mandatory=$true)]
    [string]$JarPath
)

if (-not (Test-Path $JarPath)) {
    Write-Error "File not found: $JarPath"
    exit 1
}

$pass = 0
$fail = 0

function Pass([string]$msg) {
    Write-Host "[PASS] $msg" -ForegroundColor Green
    $script:pass++
}

function Fail([string]$msg) {
    Write-Host "[FAIL] $msg" -ForegroundColor Red
    $script:fail++
}

function Warn([string]$msg) {
    Write-Host "[WARN] $msg" -ForegroundColor Yellow
}

Write-Host "=== Browser4 Plugin Verification ==="
Write-Host "JAR: $JarPath"
Write-Host ""

# Add Java to PATH if JAVA_HOME is set
if ($env:JAVA_HOME) {
    $env:PATH = "$env:JAVA_HOME\bin;$env:PATH"
}

# List JAR contents
$jarContent = & jar tf $JarPath 2>$null
if (-not $jarContent) {
    Fail "Cannot read JAR file — is jar on PATH?"
    exit 1
}

# ---------------------------------------------------------------------------
# Check 1: META-INF/browser4-plugin.json exists
# ---------------------------------------------------------------------------
$manifestEntry = $jarContent | Select-String "META-INF/browser4-plugin.json"
if ($manifestEntry) {
    Pass "META-INF/browser4-plugin.json: found"
} else {
    Fail "META-INF/browser4-plugin.json: NOT FOUND"
}

# ---------------------------------------------------------------------------
# Check 2: AutoConfiguration.imports exists
# ---------------------------------------------------------------------------
$importsEntry = $jarContent | Select-String "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports"
if ($importsEntry) {
    Pass "AutoConfiguration.imports: found"
} else {
    Fail "AutoConfiguration.imports: NOT FOUND"
}

# ---------------------------------------------------------------------------
# Check 3: Thin JAR (no embedded JARs)
# ---------------------------------------------------------------------------
$embeddedJars = ($jarContent | Select-String '\.jar$').Count
if ($embeddedJars -eq 0) {
    Pass "Thin JAR: no embedded dependency JARs"
} else {
    Fail "Thin JAR: $embeddedJars embedded JAR(s) found — use 'provided' scope"
}

# ---------------------------------------------------------------------------
# Check 4: Contains .class files
# ---------------------------------------------------------------------------
$classCount = ($jarContent | Select-String '\.class$').Count
if ($classCount -gt 0) {
    Pass "Compiled classes: $classCount .class file(s) found"
} else {
    Fail "Compiled classes: NO .class files found"
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "=== Results: $pass passed, $fail failed ==="

if ($fail -gt 0) {
    Write-Host "Fix the FAIL items above before deploying this plugin." -ForegroundColor Red
    exit 1
} else {
    Write-Host "Plugin JAR is ready for deployment." -ForegroundColor Green
    Write-Host ""
    Write-Host "To install:"
    Write-Host "  curl -X POST http://localhost:8080/api/plugins/install -F `"file=@$JarPath`""
    Write-Host "  # Or copy to the plugins/ directory and restart"
    exit 0
}
