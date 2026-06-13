#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Compile MCPBrowserServer.jar to a native Windows executable via GraalVM
    native-image, optionally compressed with UPX.

.DESCRIPTION
    Builds a standalone native executable from the shaded JAR produced by
    maven-shade-plugin. Handles MSVC environment setup, native-image invocation,
    and UPX post-compression.

.PARAMETER Mode
    Build mode: size (default), default, quick, pgo.
      size    -Os +StripDebugInfo (~31 MB before UPX, recommended)
      default -O2 standard optimizations (~52 MB)
      quick   -Ob fast iteration (~60 MB)
      pgo     Two-pass profile-guided optimization

.PARAMETER OutputName
    Output executable name without extension (default: mcp-browser-server).

.PARAMETER SkipJar
    Skip Maven JAR build — use the existing JAR in target/.

.PARAMETER SkipUpx
    Skip UPX post-compression.

.PARAMETER UpxMode
    UPX compression level: ultra (default), best, quick.

.PARAMETER JavaHome
    Path to GraalVM JDK. If omitted, auto-detected from common locations.

.PARAMETER VsBase
    Visual Studio installation directory.

.PARAMETER MsvcVer
    MSVC toolchain version.

.PARAMETER SdkVer
    Windows SDK version.

.PARAMETER JavaHeap
    Max heap for the native-image compiler (default: 6g).

.PARAMETER MavenOpts
    Additional Maven options.

.EXAMPLE
    .\build-native-image.ps1
    Full size-optimized build with UPX ultra compression.

.EXAMPLE
    .\build-native-image.ps1 -Mode quick -SkipUpx
    Fast dev iteration, skip UPX.

.EXAMPLE
    .\build-native-image.ps1 -Mode pgo
    Profile-guided optimization build.

.EXAMPLE
    .\build-native-image.ps1 -SkipJar -SkipUpx
    Rebuild native image from an existing JAR without UPX.
#>

[CmdletBinding()]
param(
    [ValidateSet('size', 'default', 'quick', 'pgo')]
    [string]$Mode = 'size',

    [string]$OutputName = 'mcp-browser-server',

    [switch]$SkipJar,

    [switch]$SkipUpx,

    [ValidateSet('ultra', 'best', 'quick')]
    [string]$UpxMode = 'ultra',

    [string]$JavaHome,

    [string]$VsBase = 'C:\Program Files\Microsoft Visual Studio\2022\Community',

    [string]$MsvcVer = '14.43.34808',

    [string]$SdkVer = '10.0.26100.0',

    [string]$JavaHeap = '6g',

    [string]$MavenOpts
)

$ErrorActionPreference = 'Stop'

# ---------------------------------------------------------------------------
# Resolve paths
# ---------------------------------------------------------------------------
$ScriptDir   = Split-Path -Parent $PSCommandPath
$ModuleDir   = Resolve-Path "$ScriptDir\.."
$TargetDir   = Join-Path $ModuleDir 'target'

# Use git to find the repository root, then resolve files relative to it
$RepoRoot = (git rev-parse --show-toplevel 2>$null)
if (-not $RepoRoot) {
    Write-Err 'Cannot determine repository root — are you inside a git repository?'
    exit 1
}

# Pick the right Maven wrapper executable for this OS
$mvnwExe = if (Test-Path (Join-Path $RepoRoot 'mvnw.cmd')) {
    Join-Path $RepoRoot 'mvnw.cmd'
} else {
    Join-Path $RepoRoot 'mvnw'
}

# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------
function Write-Log  { Write-Host "[INFO]  $args" -ForegroundColor Green }
function Write-Warn { Write-Host "[WARN]  $args" -ForegroundColor Yellow }
function Write-Err  { Write-Host "[ERROR] $args" -ForegroundColor Red }
function Write-Step { Write-Host "[STEP]  $args" -ForegroundColor Cyan }

function Format-MB {
    param([long]$Bytes)
    '{0:F1} MB' -f ($Bytes / 1MB)
}

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
Write-Host ''
Write-Host '══════════════════════════════════════════════════════════════' -ForegroundColor DarkGray
Write-Host '       MCP Browser Server — Native Image Build Script        ' -ForegroundColor White
Write-Host '══════════════════════════════════════════════════════════════' -ForegroundColor DarkGray
Write-Host ''
Write-Log "Build mode:   $Mode"
Write-Log "Output name:  $OutputName.exe"
Write-Log "UPX:          $(if ($SkipUpx) { 'skipped' } else { $UpxMode })"
Write-Log "Target dir:   $TargetDir"
Write-Host ''

# ---------------------------------------------------------------------------
# Step 1 — Build the shaded JAR
# ---------------------------------------------------------------------------
if (-not $SkipJar) {
    Write-Step 'Building shaded JAR with Maven ...'
    Write-Log "Repo root: $RepoRoot"
    Push-Location $RepoRoot
    try {
        $mvnArgs = @(
            'package',
            '-pl', 'browser4-core/browser4-browser',
            '-am',
            '-DskipTests'
        )
        if ($MavenOpts) {
            $mvnArgs += $MavenOpts.Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
        }
        & $mvnwExe @mvnArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Maven build failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
    Write-Log 'JAR build complete.'
    Write-Host ''
}
else {
    Write-Log 'Skipping JAR build (-SkipJar).'
}

$JarPath = Join-Path $TargetDir 'MCPBrowserServer.jar'
if (-not (Test-Path $JarPath)) {
    Write-Err "JAR not found at $JarPath"
    Write-Err 'Run without -SkipJar to build it first.'
    exit 1
}
Write-Log "JAR: $JarPath"

# ---------------------------------------------------------------------------
# Step 2 — Locate / verify GraalVM
# ---------------------------------------------------------------------------
Write-Step 'Checking GraalVM ...'

if (-not $JavaHome) {
    $candidates = @(
        'D:\Program Files\Java\graalvm-jdk-25.0.3+9.1',
        'C:\Program Files\Java\graalvm-jdk-25.0.3+9.1',
        'D:\Program Files\Java\graalvm-jdk-24+36.1',
        'C:\Program Files\Java\graalvm-jdk-24+36.1',
        'C:\Program Files\Java\graalvm-jdk-25',
        'C:\Program Files\Java\graalvm-jdk-24',
        'C:\Program Files\Java\graalvm-jdk-22'
    )
    foreach ($candidate in $candidates) {
        $nativeImagePath = Join-Path $candidate 'bin\native-image.cmd'
        if (Test-Path $nativeImagePath) {
            $JavaHome = $candidate
            break
        }
    }
    if (-not $JavaHome) {
        Write-Err 'Could not auto-detect GraalVM. Set $env:JAVA_HOME or pass -JavaHome.'
        exit 1
    }
}

$env:JAVA_HOME = $JavaHome
$env:PATH = "$JavaHome\bin;$env:PATH"
Write-Log "JAVA_HOME: $JavaHome"

$nativeImageCmd = Get-Command native-image.cmd -ErrorAction SilentlyContinue
if (-not $nativeImageCmd) {
    $nativeImageCmd = Get-Command native-image -ErrorAction SilentlyContinue
}
if (-not $nativeImageCmd) {
    Write-Err 'native-image not found in JAVA_HOME/bin.'
    Write-Err 'Make sure you have GraalVM with the native-image component installed.'
    Write-Err 'Install: gu install native-image'
    exit 1
}
Write-Log "native-image found: $($nativeImageCmd.Source)"
Write-Host ''

# ---------------------------------------------------------------------------
# Step 3 — Set up MSVC environment
# ---------------------------------------------------------------------------
Write-Step 'Setting up MSVC environment ...'

$Msvc  = Join-Path $VsBase "VC\Tools\MSVC\$MsvcVer"
$WinKits = 'C:\Program Files (x86)\Windows Kits\10'

$env:PATH   = "$Msvc\bin\Hostx64\x64;$env:PATH"
$env:PATH   = "$WinKits\bin\$SdkVer\x64;$env:PATH"
$env:INCLUDE = "$Msvc\include;$WinKits\Include\$SdkVer\ucrt;$WinKits\Include\$SdkVer\shared;$WinKits\Include\$SdkVer\um;$WinKits\Include\$SdkVer\winrt"
$env:LIB    = "$Msvc\lib\x64;$WinKits\Lib\$SdkVer\ucrt\x64;$WinKits\Lib\$SdkVer\um\x64"

$clExe = Get-Command cl.exe -ErrorAction SilentlyContinue
if (-not $clExe) {
    Write-Warn 'cl.exe not found on PATH.'
    Write-Warn "MSVC:  $Msvc\bin\Hostx64\x64"
    Write-Warn "SDK:   $WinKits\bin\$SdkVer\x64"
    Write-Warn ''
    Write-Warn 'Try running from a Developer PowerShell / Developer Command Prompt,'
    Write-Warn 'or adjust -VsBase / -MsvcVer / -SdkVer.'
}
else {
    Write-Log "cl.exe found: $($clExe.Source)"
}

$ucrtHeader = Join-Path $WinKits "Include\$SdkVer\ucrt\stdio.h"
if (-not (Test-Path $ucrtHeader)) {
    Write-Warn "UCRT header not found at $ucrtHeader"
}
else {
    Write-Log 'Windows SDK headers found.'
}
Write-Host ''

# ---------------------------------------------------------------------------
# Step 4 — Compose flags & build native image
# ---------------------------------------------------------------------------
function Build-NativeImage {
    param(
        [string[]]$Flags,
        [string]$StepLabel = 'Building native image'
    )
    Write-Step "$StepLabel (this may take 1-2 minutes) ..."

    $timer = [System.Diagnostics.Stopwatch]::StartNew()

    Push-Location $TargetDir
    try {
        $cmd = if ($nativeImageCmd.Source) { $nativeImageCmd.Source } else { 'native-image.cmd' }
        & $cmd @Flags
        if ($LASTEXITCODE -ne 0) {
            throw "native-image failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }

    $timer.Stop()
    Write-Log ('Native image built in {0:N0}s.' -f $timer.Elapsed.TotalSeconds)
    Write-Host ''
}

# Common flags
$commonFlags = @(
    '-jar', $JarPath,
    '-o', (Join-Path $TargetDir "$OutputName.exe"),
    '--no-fallback',
    '-march=compatibility',
    "-J-Xmx$JavaHeap"
)

switch ($Mode) {
    'size' {
        Write-Step 'Composing flags for: size (-Os +StripDebugInfo)'
        $flags = $commonFlags + @('-Os', '-H:+StripDebugInfo')
        Build-NativeImage -Flags $flags
    }
    'default' {
        Write-Step 'Composing flags for: default (-O2)'
        Build-NativeImage -Flags $commonFlags
    }
    'quick' {
        Write-Step 'Composing flags for: quick (-Ob)'
        $flags = $commonFlags + @('-Ob')
        Build-NativeImage -Flags $flags
    }
    'pgo' {
        $pgoDir = Join-Path $TargetDir 'pgo'
        New-Item -ItemType Directory -Force -Path $pgoDir | Out-Null

        $instrumentedOut = Join-Path $TargetDir "$OutputName-instrumented.exe"
        $profileFile = Join-Path $pgoDir 'default.iprof'

        # -------- Pass 1: Instrumented build --------
        $instrumentFlags = @(
            '-jar', $JarPath,
            '-o', $instrumentedOut,
            '--pgo-instrument',
            '-march=compatibility',
            "-J-Xmx$JavaHeap"
        )
        Build-NativeImage -Flags $instrumentFlags -StepLabel 'PGO Pass 1/2 — Building instrumented image'

        # -------- Collect profiling data --------
        Write-Step 'PGO Profiling — Running instrumented binary ...'
        Push-Location $TargetDir
        try {
            $proc = Start-Process -FilePath $instrumentedOut -PassThru -WindowStyle Hidden
            Start-Sleep -Seconds 3

            try {
                $null = Invoke-WebRequest -Uri 'http://localhost:8182/mcp/call-tool' `
                    -Method Post `
                    -ContentType 'application/json' `
                    -Body '{"tool":"open_session","arguments":{}}' `
                    -TimeoutSec 5 `
                    -ErrorAction SilentlyContinue
                $null = Invoke-WebRequest -Uri 'http://localhost:8182/mcp/tools' `
                    -TimeoutSec 5 `
                    -ErrorAction SilentlyContinue
            }
            catch {
                Write-Warn "Profiling HTTP call failed: $_"
            }

            Stop-Process -Id $proc.Id -Force -ErrorAction SilentlyContinue
        }
        finally {
            Pop-Location
        }

        $defaultIprof = Join-Path $TargetDir 'default.iprof'
        if (Test-Path $defaultIprof) {
            Move-Item -Force $defaultIprof $profileFile
            Write-Log "Profile saved: $profileFile"
        }
        else {
            Write-Warn 'default.iprof was not generated — falling back to size mode.'
            $flags = $commonFlags + @('-Os', '-H:+StripDebugInfo')
            Build-NativeImage -Flags $flags
        }

        if (Test-Path $profileFile) {
            $flags = $commonFlags + @("--pgo=$profileFile")
            Build-NativeImage -Flags $flags -StepLabel 'PGO Pass 2/2 — Building optimized image'
        }
    }
}

$nativeExe = Join-Path $TargetDir "$OutputName.exe"
if (-not (Test-Path $nativeExe)) {
    Write-Err "Native image was not produced at $nativeExe"
    exit 1
}

$nativeItem = Get-Item $nativeExe
Write-Log ('Native image size: ' + (Format-MB $nativeItem.Length))

# ---------------------------------------------------------------------------
# Step 5 — UPX compression (optional)
# ---------------------------------------------------------------------------
if (-not $SkipUpx) {
    $upxCmd = Get-Command upx -ErrorAction SilentlyContinue
    if (-not $upxCmd) {
        Write-Warn 'UPX not found. Skipping compression.'
        Write-Warn 'Install: choco install upx  or  scoop install upx'
    }
    else {
        Write-Step "Compressing with UPX (mode: $UpxMode) ..."

        # Keep an uncompressed backup
        $backupExe = "$nativeExe.uncompressed"
        Copy-Item -Force $nativeExe $backupExe

        $upxTimer = [System.Diagnostics.Stopwatch]::StartNew()

        switch ($UpxMode) {
            'ultra' {
                Write-Log 'Running upx --ultra-brute --compress-icons=3 (may take ~2 min) ...'
                & upx --ultra-brute --compress-icons=3 -f $nativeExe
            }
            'best' {
                Write-Log 'Running upx --best --lzma ...'
                & upx --best --lzma -f $nativeExe
            }
            'quick' {
                Write-Log 'Running upx -9 --lzma ...'
                & upx -9 --lzma -f $nativeExe
            }
        }

        $upxTimer.Stop()

        if ($LASTEXITCODE -ne 0) {
            Write-Warn 'UPX exited with non-zero code — backup saved at ' + $backupExe
        }
        else {
            $finalItem = Get-Item $nativeExe
            Write-Log ('Compressed size: ' + (Format-MB $finalItem.Length) + ' (in ' + '{0:N0}s)' -f $upxTimer.Elapsed.TotalSeconds)

            # Verify
            Write-Log 'Verifying compressed binary ...'
            & upx -t $nativeExe 2>$null
            if ($LASTEXITCODE -eq 0) {
                Write-Log 'Verification OK.'
            }
            else {
                Write-Warn "Verification failed — uncompressed backup at $backupExe"
            }
        }
        Write-Host ''
    }
}

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
Write-Host '══════════════════════════════════════════════════════════════' -ForegroundColor DarkGray
Write-Host '                    Build Complete                           ' -ForegroundColor White
Write-Host '══════════════════════════════════════════════════════════════' -ForegroundColor DarkGray
Write-Host ''
Write-Log "Executable: $nativeExe"
Write-Host ''
Write-Log 'Quick test:'
Write-Host "  Start-Process '$nativeExe'"
Write-Host "  Invoke-WebRequest http://localhost:8182/mcp/tools"
Write-Host ''
