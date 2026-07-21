<#
.SYNOPSIS
    Run scent-miner over a directory of HTML files.

.DESCRIPTION
    Downloads scent-miner.jar from GitHub releases if needed, resolves a Java
    runtime, and runs the WebMiner to extract structured data from local HTML files.

    Java resolution (in order):
      1. $env:JAVA_HOME/bin/java
      2. java on system PATH
      3. Browser4 runtime bundle JRE (browser4-apps/browser4-bundle/target/...)

    Scent-miner scans all *.html / *.htm files in the input directory, extracts
    structured tables, and writes results to <input-dir>-views/.

.PARAMETER InputDir
    Directory containing *.html / *.htm files to mine. Required.

.PARAMETER OutputDir
    Output directory for generated views. Default: <InputDir>-views.

.PARAMETER ScentMinerJar
    Path to scent-miner.jar. If not found at this path, downloads from GitHub releases.

.PARAMETER ScentMinerVersion
    Version of scent-miner to download. Default: latest release.

.PARAMETER ComponentSelector
    CSS selector for the main content area on each page. Example: "#mainContent"

.PARAMETER Limit
    Load at most N pages from the input directory.

.PARAMETER RequireSize
    Minimum page size in bytes (default: 500000). Smaller pages are skipped.

.PARAMETER NoTrustSamples
    Validate and clean samples instead of trusting them.

.PARAMETER ExtraArgs
    Additional arguments passed through to scent-miner.

.EXAMPLE
    .\skills\scent-miner\scripts\run-scent-miner.ps1 -InputDir /data/pages
    .\skills\scent-miner\scripts\run-scent-miner.ps1 -InputDir /data/pages -ComponentSelector "#mainContent"
    .\skills\scent-miner\scripts\run-scent-miner.ps1 -InputDir /data/pages -Limit 50

.NOTES
    Scent-miner is part of platonai/web-miner: https://github.com/platonai/web-miner
#>

[CmdletBinding()]
param(
    [Parameter(Mandatory = $true, Position = 0)]
    [string]$InputDir,

    [Parameter(Position = 1)]
    [string]$OutputDir,

    [string]$ScentMinerJar,

    [string]$ScentMinerVersion,

    [string]$ComponentSelector,

    [int]$Limit = 0,

    [int]$RequireSize = 500000,

    [switch]$NoTrustSamples,

    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]]$ExtraArgs
)

$ErrorActionPreference = "Stop"

# =============================================================================
# Resolve repository root (3 levels up from skills/scent-miner/scripts/)
# =============================================================================
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Resolve-Path "$ScriptDir\..\..\.."

# =============================================================================
# Default paths
# =============================================================================
if (-not $OutputDir) {
    $OutputDir = "$InputDir-views"
}

$DefaultJarDir = Join-Path $RepoRoot "skills\scent-miner\scripts"
$DefaultJarPath = Join-Path $DefaultJarDir "scent-miner.jar"

if (-not $ScentMinerJar) {
    $ScentMinerJar = $DefaultJarPath
}

# =============================================================================
# 1. Resolve Java executable
# =============================================================================
function Resolve-Java {
    # 1a. JAVA_HOME
    if ($env:JAVA_HOME) {
        $javaExe = Join-Path $env:JAVA_HOME "bin\java.exe"
        if (Test-Path $javaExe) {
            Write-Host "[Java] Using JAVA_HOME: $javaExe" -ForegroundColor DarkGray
            return $javaExe
        }
    }

    # 1b. System PATH
    $javaCmd = Get-Command java -ErrorAction SilentlyContinue
    if ($javaCmd) {
        Write-Host "[Java] Using system PATH: $($javaCmd.Source)" -ForegroundColor DarkGray
        return $javaCmd.Source
    }

    # 1c. Browser4 runtime bundle JRE
    $BundleBase = Join-Path $RepoRoot "browser4-apps\browser4-bundle\target\runtime-bundle\_work"
    if (Test-Path $BundleBase) {
        $bundles = Get-ChildItem -Path $BundleBase -Directory -Filter "browser4-bundle-runtime-*" |
            Sort-Object LastWriteTime -Descending
        foreach ($bundle in $bundles) {
            $runtimeExe = Join-Path $bundle.FullName "runtime\bin\java.exe"
            if (Test-Path $runtimeExe) {
                Write-Host "[Java] Using runtime bundle: $runtimeExe" -ForegroundColor DarkGray
                return $runtimeExe
            }
        }
    }

    Write-Error @"
No Java runtime found. Tried:
  - `$env:JAVA_HOME/bin/java.exe
  - java on system PATH
  - Browser4 runtime bundle JRE ($BundleBase)

Install Java 17+ or set JAVA_HOME, or build the Browser4 runtime bundle:
  .\bin\build.ps1 runtimeBundle
"@
}

# =============================================================================
# 2. Download scent-miner.jar if not present
# =============================================================================
function Ensure-ScentMinerJar {
    param([string]$JarPath)

    if (Test-Path $JarPath) {
        Write-Host "[Jar] Found: $JarPath" -ForegroundColor DarkGray
        return $JarPath
    }

    $jarDir = Split-Path $JarPath -Parent
    if (-not (Test-Path $jarDir)) {
        New-Item -ItemType Directory -Path $jarDir -Force | Out-Null
    }

    # Determine version to download
    $downloadUrl = if ($ScentMinerVersion) {
        "https://github.com/platonai/web-miner/releases/download/v$ScentMinerVersion/scent-miner.jar"
    } else {
        "https://github.com/platonai/web-miner/releases/latest/download/scent-miner.jar"
    }

    Write-Host "[Download] Fetching scent-miner.jar from GitHub releases..." -ForegroundColor Yellow
    Write-Host "[Download] $downloadUrl" -ForegroundColor DarkGray

    try {
        # Try curl first, then wget, then Invoke-WebRequest
        $curl = Get-Command curl -ErrorAction SilentlyContinue
        if ($curl) {
            & curl -L --progress-bar -o "$JarPath" "$downloadUrl" 2>&1
        } else {
            Invoke-WebRequest -Uri $downloadUrl -OutFile $JarPath -ErrorAction Stop
        }

        if (Test-Path $JarPath) {
            $size = (Get-Item $JarPath).Length
            Write-Host "[Download] Done: $JarPath ($('{0:N0}' -f $size) bytes)" -ForegroundColor Green
            return $JarPath
        }
    } catch {
        Write-Error @"
Failed to download scent-miner.jar from GitHub releases.
Error: $($_.Exception.Message)

Please download manually:
  1. Visit https://github.com/platonai/web-miner/releases
  2. Download scent-miner.jar from the latest release
  3. Place it at: $JarPath
Or pass an existing jar via -ScentMinerJar <path>
"@
    }
}

# =============================================================================
# 3. Build command line
# =============================================================================
$JavaExe = Resolve-Java
$JarPath = Ensure-ScentMinerJar -JarPath $ScentMinerJar

if (-not (Test-Path $InputDir)) {
    Write-Error "Input directory not found: $InputDir"
}

Write-Host "============================================================" -ForegroundColor Cyan
Write-Host "         Scent-Miner (WebMiner)                             " -ForegroundColor Cyan
Write-Host "============================================================" -ForegroundColor Cyan
Write-Host ""
Write-Host "  Input  : $InputDir" -ForegroundColor DarkGray
Write-Host "  Output : $OutputDir" -ForegroundColor DarkGray
Write-Host "  Java   : $JavaExe" -ForegroundColor DarkGray
Write-Host "  Jar    : $JarPath" -ForegroundColor DarkGray
if ($ComponentSelector) { Write-Host "  CSS    : $ComponentSelector" -ForegroundColor DarkGray }
if ($Limit -gt 0)       { Write-Host "  Limit  : $Limit pages" -ForegroundColor DarkGray }
Write-Host ""

# Build argument list
$JavaArgs = @("-jar", $JarPath, "--input", $InputDir)

if ($OutputDir -ne "$InputDir-views") {
    # Only pass explicit output if different from default
    $JavaArgs += "--output"
    $JavaArgs += $OutputDir
}
if ($ComponentSelector) {
    $JavaArgs += "--component-selector"
    $JavaArgs += $ComponentSelector
}
if ($Limit -gt 0) {
    $JavaArgs += "--limit"
    $JavaArgs += $Limit
}
if ($RequireSize -ne 500000) {
    $JavaArgs += "--require-size"
    $JavaArgs += $RequireSize
}
if ($NoTrustSamples) {
    $JavaArgs += "--no-trust-samples"
}
if ($ExtraArgs) {
    $JavaArgs += $ExtraArgs
}

# =============================================================================
# 4. Run scent-miner
# =============================================================================
Write-Host "Running scent-miner..." -ForegroundColor Green
Write-Host ""

$process = Start-Process -FilePath $JavaExe `
    -ArgumentList $JavaArgs `
    -NoNewWindow `
    -Wait `
    -PassThru

Write-Host ""

if ($process.ExitCode -eq 0) {
    Write-Host "Scent-miner completed successfully." -ForegroundColor Green
    Write-Host ""
    Write-Host "  Output views: $OutputDir" -ForegroundColor Cyan
    if (Test-Path "$OutputDir\views\index.html") {
        Write-Host "  HTML report : $OutputDir\views\index.html" -ForegroundColor Cyan
    }
    if (Test-Path "$OutputDir\views") {
        $xlsxFiles = Get-ChildItem -Path "$OutputDir\views" -Filter "*.xlsx" -ErrorAction SilentlyContinue
        if ($xlsxFiles) {
            Write-Host "  Excel files : $($xlsxFiles.Count) file(s)" -ForegroundColor Cyan
            foreach ($f in $xlsxFiles) {
                Write-Host "    $($f.Name)" -ForegroundColor DarkGray
            }
        }
    }
} else {
    Write-Error "Scent-miner exited with code: $($process.ExitCode)"
}

exit $process.ExitCode
