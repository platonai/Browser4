#requires -Version 7

# =============================================================================
# Browser4 Plugin Verification Script (PowerShell)
# =============================================================================
# Validates that a built plugin JAR has the correct structure for deployment.
#
# Usage: .\verify-plugin.ps1 <path-to-plugin.jar> [-Strict]
#
# Checks:
#   [PASS/FAIL] META-INF/browser4-plugin.json exists
#   [PASS/FAIL] AutoConfiguration.imports exists
#   [PASS/FAIL] Thin JAR (no embedded dependency JARs)
#   [PASS/FAIL] Contains compiled .class files
#   [PASS/WARN] sdkVersion declared in browser4-plugin.json
#   [PASS/WARN] Browser4-Plugin-Version in MANIFEST.MF
#   [PASS/WARN] browser4-plugin.json version matches the artifact version
#               (-Strict turns a mismatch into a FAIL)
# =============================================================================

param(
    [Parameter(Mandatory=$true)]
    [string]$JarPath,

    # Treat a plugin.json/artifact version mismatch as a FAIL instead of a WARN.
    # Useful as a release gate: `plugin list` renders the plugin.json value, so a
    # stale literal is what a user sees for the loaded build.
    [switch]$Strict
)

# Emit UTF-8 through a redirected stream — see Set-Utf8ConsoleEncoding in
# bin/common/Util.ps1 (under CP936 "·" becomes "??" and "═" becomes "?T").
. (Join-Path $PSScriptRoot 'common/Util.ps1')

if (-not (Test-Path $JarPath)) {
    Write-Error "File not found: $JarPath"
    exit 1
}

$pass = 0
$fail = 0
$warn = 0

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
    $script:warn++
}

Write-Host "=== Browser4 Plugin Verification ==="
Write-Host "JAR: $JarPath"
Write-Host ""

# Add Java to PATH if JAVA_HOME is set.  Build the separator from the platform
# instead of hard-coding ';' — on Linux/macOS that produced one unusable entry
# ('.../bin;<PATH>') and `jar` was then resolved from somewhere else or not at all.
if ($env:JAVA_HOME) {
    $javaBinDirectory = Join-Path $env:JAVA_HOME 'bin'
    $env:PATH = "$javaBinDirectory$([System.IO.Path]::PathSeparator)$env:PATH"
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
# Check 5: sdkVersion declared in browser4-plugin.json (host compat check)
# ---------------------------------------------------------------------------
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = $null
$sdkVersion = $null
$manifestVersion = $null
try {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    $entry = $zip.GetEntry("META-INF/browser4-plugin.json")
    if ($entry) {
        $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
        try { $manifestJson = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $match = [regex]::Match($manifestJson, '"sdkVersion"\s*:\s*"([^"]*)"')
        if ($match.Success) { $sdkVersion = $match.Groups[1].Value }
        $versionMatch = [regex]::Match($manifestJson, '"version"\s*:\s*"([^"]*)"')
        if ($versionMatch.Success) { $manifestVersion = $versionMatch.Groups[1].Value }
    }
} catch {
    Warn "Could not read browser4-plugin.json content: $($_.Exception.Message)"
} finally {
    if ($zip) { $zip.Dispose() }
}

if ($sdkVersion) {
    Pass "sdkVersion: $sdkVersion declared"
} else {
    Warn "sdkVersion: not declared — host will load on best-effort (pre-4.14 plugins are exempt)"
}

# ---------------------------------------------------------------------------
# Check 6: Browser4-Plugin-Version in MANIFEST.MF (SDK version fallback)
# ---------------------------------------------------------------------------
$mfSdkVersion = $null
try {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    $entry = $zip.GetEntry("META-INF/MANIFEST.MF")
    if ($entry) {
        $reader = New-Object System.IO.StreamReader($entry.Open(), [System.Text.Encoding]::UTF8)
        try { $mfContent = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $mfMatch = [regex]::Match($mfContent, 'Browser4-Plugin-Version:\s*([^\r\n]+)')
        if ($mfMatch.Success) { $mfSdkVersion = $mfMatch.Groups[1].Value.Trim() }
    }
} catch {
    Warn "Could not read MANIFEST.MF: $($_.Exception.Message)"
} finally {
    if ($zip) { $zip.Dispose() }
}

if ($mfSdkVersion) {
    Pass "MANIFEST.MF Browser4-Plugin-Version: $mfSdkVersion"
} else {
    Warn "MANIFEST.MF Browser4-Plugin-Version: not set — SDK version falls back to plugin.json sdkVersion"
}

# ---------------------------------------------------------------------------
# Check 7: browser4-plugin.json "version" vs the artifact version
# ---------------------------------------------------------------------------
# `plugin list` (and GET /api/plugins) render the manifest value, so a literal
# that was never filtered from the Maven project version makes the loaded build
# unidentifiable — a 4.14.1-SNAPSHOT JAR reporting 4.12.0-rc.1.  The artifact
# version comes from META-INF/maven/.../pom.properties, with the JAR file name
# as the fallback.
$artifactVersion = $null
$artifactVersionSource = ''

$zip = $null
try {
    $zip = [System.IO.Compression.ZipFile]::OpenRead($JarPath)
    $pomEntry = $zip.Entries |
        Where-Object { $_.FullName -like 'META-INF/maven/*/*/pom.properties' } |
        Select-Object -First 1
    if ($pomEntry) {
        $reader = New-Object System.IO.StreamReader($pomEntry.Open(), [System.Text.Encoding]::UTF8)
        try { $pomProperties = $reader.ReadToEnd() } finally { $reader.Dispose() }
        $pomMatch = [regex]::Match($pomProperties, '(?m)^version\s*=\s*(.+)$')
        if ($pomMatch.Success) {
            $artifactVersion = $pomMatch.Groups[1].Value.Trim()
            $artifactVersionSource = 'pom.properties'
        }
    }
} catch {
    Warn "Could not read META-INF/maven/.../pom.properties: $($_.Exception.Message)"
} finally {
    if ($zip) { $zip.Dispose() }
}

if (-not $artifactVersion) {
    $jarFileName = [System.IO.Path]::GetFileName($JarPath)
    if ($jarFileName -match '^(?<artifact>.+?)-(?<version>\d[^/]*?)(?:-shaded)?\.jar$') {
        $artifactVersion = $Matches['version']
        $artifactVersionSource = 'JAR file name'
    }
}

if (-not $manifestVersion) {
    Warn "browser4-plugin.json version: not declared — nothing to compare with the artifact version"
} elseif (-not $artifactVersion) {
    Warn ("browser4-plugin.json version ($manifestVersion): the artifact version could not be determined " +
        "(no pom.properties entry and an unparseable JAR name)")
} elseif ($manifestVersion -eq $artifactVersion) {
    Pass "Manifest version matches the artifact version: $manifestVersion"
} else {
    $versionSkewMessage = (
        "browser4-plugin.json version '$manifestVersion' != artifact version '$artifactVersion' " +
        "($artifactVersionSource): 'plugin list' renders the manifest value, so the loaded build cannot be " +
        "identified. Filter the field from `${project.version} in the module's " +
        "src/main/resources/META-INF/browser4-plugin.json — browser4-pdk/pom.xml already filters that resource. " +
        "A plugin may also version itself independently (browser4-swarm reports 1.0.0), so confirm the value is intended."
    )
    if ($Strict) { Fail $versionSkewMessage } else { Warn $versionSkewMessage }
}

# ---------------------------------------------------------------------------
# Summary
# ---------------------------------------------------------------------------
Write-Host ""
Write-Host "=== Results: $pass passed, $fail failed, $warn warning(s) ==="

if ($fail -gt 0) {
    Write-Host "Fix the FAIL items above before deploying this plugin." -ForegroundColor Red
    exit 1
} else {
    if ($warn -gt 0) {
        Write-Host "No FAIL items, but $warn warning(s) above are worth reading (pass -Strict to fail on the version-skew check)." -ForegroundColor Yellow
    }
    Write-Host "Plugin JAR is ready for deployment." -ForegroundColor Green
    Write-Host ""
    Write-Host "To install (the server loads plugins/ at startup, so restart it afterwards):"
    Write-Host "  browser4-cli plugin install `"$JarPath`""
    Write-Host "  # Or copy the JAR into the server's plugins/ directory and restart it"
    exit 0
}
