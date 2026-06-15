#!/usr/bin/env pwsh

<#
.SYNOPSIS
    Compile Browser4.jar (Spring Boot fat JAR) to a native Windows executable
    via GraalVM native-image.

.DESCRIPTION
    Builds a standalone native executable from the Spring Boot repackaged JAR
    produced by spring-boot-maven-plugin. Handles MSVC environment setup,
    native-image invocation, and optional UPX post-compression.

    The script runs Spring AOT processing before native-image to generate
    reachability metadata (reflection, resources, proxies, serialization)
    required by the Spring Framework runtime.

    UPX is off by default — it is incompatible with GraalVM 22+ on Windows.

.PARAMETER Mode
    Build mode: size (default), default, quick, pgo.
      size    -Os +StripDebugInfo (~32 MB, recommended)
      default -O2 standard optimizations (~52 MB)
      quick   -Ob fast iteration (~60 MB)
      pgo     Two-pass profile-guided optimization

.PARAMETER OutputName
    Output executable name without extension (default: browser4-standalone).

.PARAMETER SkipJar
    Skip Maven JAR build — use the existing JAR in target/.

.PARAMETER EnableUpx
    Enable UPX post-compression (off by default — UPX is incompatible with
    GraalVM 22+ on Windows; a smoke test will restore the backup if it fails).

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
    Max heap for the native-image compiler (default: 8g — Spring Boot fat JARs
    need more memory during analysis).

.PARAMETER MavenOpts
    Additional Maven options.

.PARAMETER ServerPort
    Port the Browser4 standalone server listens on (default: 8182).

.EXAMPLE
    .\build-native-image.ps1
    Full size-optimized build (UPX disabled by default).

.EXAMPLE
    .\build-native-image.ps1 -Mode quick
    Fast dev iteration.

.EXAMPLE
    .\build-native-image.ps1 -Mode pgo
    Profile-guided optimization build.

.EXAMPLE
    .\build-native-image.ps1 -SkipJar
    Rebuild native image from an existing JAR.
#>

[CmdletBinding()]
param(
    [ValidateSet('size', 'default', 'quick', 'pgo')]
    [string]$Mode = 'size',

    [string]$OutputName = 'browser4-standalone',

    [switch]$SkipJar,

    [switch]$EnableUpx,

    [ValidateSet('ultra', 'best', 'quick')]
    [string]$UpxMode = 'ultra',

    [string]$JavaHome,

    [string]$VsBase = 'C:\Program Files\Microsoft Visual Studio\2022\Community',

    [string]$MsvcVer = '14.43.34808',

    [string]$SdkVer = '10.0.26100.0',

    [string]$JavaHeap = '8g',

    [string]$MavenOpts,

    [int]$ServerPort = 8182
)

$ErrorActionPreference = 'Stop'

# Force UTF-8 encoding for CLI output to fix garbled text
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$OutputEncoding = [System.Text.Encoding]::UTF8

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
Write-Host '       Browser4 Standalone — Native Image Build Script        ' -ForegroundColor White
Write-Host '══════════════════════════════════════════════════════════════' -ForegroundColor DarkGray
Write-Host ''
Write-Log "Build mode:   $Mode"
Write-Log "Output name:  $OutputName.exe"
Write-Log "UPX:          $(if ($EnableUpx) { $UpxMode } else { 'skipped' })"
Write-Log "Target dir:   $TargetDir"
Write-Log "Server port:  $ServerPort"
Write-Host ''

# ---------------------------------------------------------------------------
# Step 1 — Build the Spring Boot fat JAR (with AOT processing)
# ---------------------------------------------------------------------------
if (-not $SkipJar) {
    Write-Step 'Building Spring Boot JAR with Maven (including AOT processing) ...'
    Write-Log "Repo root: $RepoRoot"
    Push-Location $RepoRoot
    try {
        # Phase 1: Compile + AOT process
        # spring-boot:process-aot generates GraalVM reachability metadata under
        # target/classes/META-INF/native-image/ and processes the application
        # context for ahead-of-time optimisations.
        Write-Step 'Running Spring AOT processing ...'
        # The asset-standalone profile adds browser4-apps/browser4-standalone to
        # the reactor (it's not in the default reactor).
        $aotArgs = @(
            'spring-boot:process-aot',
            '-pl', 'browser4-apps/browser4-standalone',
            '-P', 'asset-standalone'
        )
        if ($MavenOpts) {
            $aotArgs += $MavenOpts.Split(' ', [StringSplitOptions]::RemoveEmptyEntries)
        }
        & $mvnwExe @aotArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Spring AOT processing failed with exit code $LASTEXITCODE"
        }
        Write-Log 'AOT processing complete.'

        # Phase 2: Compile + copy dependencies to target/dependency/
        # native-image cannot read Spring Boot's nested JAR layout (BOOT-INF/classes,
        # BOOT-INF/lib/), so we need a flat classpath of target/classes + all runtime
        # JARs.  The dependency plugin copies them to a single directory so we can
        # use a concise "target/dependency/*" classpath wildcard.
        Write-Step 'Packaging application and copying runtime dependencies ...'
        $mvnArgs = @(
            'package',
            'dependency:copy-dependencies',
            '-pl', 'browser4-apps/browser4-standalone',
            '-P', 'asset-standalone',
            '-DskipTests',
            '-DoutputDirectory=target/dependency',
            '-DincludeScope=runtime'
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

# Build the flat classpath that native-image will compile against.
# target/classes contains the AOT-processed application classes;
# target/dependency/* contains every runtime-scoped JAR.
$ClassesDir = Join-Path $TargetDir 'classes'
$DepDir     = Join-Path $TargetDir 'dependency'

if (-not (Test-Path $ClassesDir)) {
    Write-Err "Classes directory not found at $ClassesDir"
    Write-Err 'Run without -SkipJar to build the project first.'
    exit 1
}

# If the dependency directory is missing or empty (e.g. after -SkipJar on a
# clean checkout), run just the dependency:copy-dependencies goal to populate it.
$depJars = @()
if (Test-Path $DepDir) {
    $depJars = @(Get-ChildItem -Path $DepDir -Filter '*.jar' -ErrorAction SilentlyContinue)
}
if ($depJars.Count -eq 0) {
    Write-Log 'Dependency directory empty or missing — copying runtime dependencies ...'
    Push-Location $RepoRoot
    try {
        $copyDepArgs = @(
            'dependency:copy-dependencies',
            '-pl', 'browser4-apps/browser4-standalone',
            '-P', 'asset-standalone',
            '-DoutputDirectory=target/dependency',
            '-DincludeScope=runtime'
        )
        & $mvnwExe @copyDepArgs
        if ($LASTEXITCODE -ne 0) {
            throw "Dependency copy failed with exit code $LASTEXITCODE"
        }
    }
    finally {
        Pop-Location
    }
    $depJars = @(Get-ChildItem -Path $DepDir -Filter '*.jar' -ErrorAction SilentlyContinue)
}

$depCount = $depJars.Count
Write-Log "Classes:    $ClassesDir"
Write-Log "Classpath:  $ClassesDir" + $(if ($depCount -gt 0) { " + $DepDir ($depCount dependency JARs)" } else { '' })

# Remove Jetty JARs from the flat dependency directory — Jetty's
# extensive use of Cleaner, daemon threads, and reflective inner-class
# instantiation is fundamentally incompatible with native-image's closed
# world.  Tomcat is also on the classpath and Spring Boot will use it
# once Jetty's auto-config classes are excluded (see application.properties
# and --initialize-at-run-time below).
if (Test-Path $DepDir) {
    $jettyJars = @(Get-ChildItem -Path $DepDir -Filter 'jetty-*.jar')
    if ($jettyJars.Count -gt 0) {
        Write-Log ('Removing ' + $jettyJars.Count + ' Jetty JARs from classpath (use Tomcat instead) ...')
        $jettyJars | Remove-Item -Force
        $depJars = @(Get-ChildItem -Path $DepDir -Filter '*.jar')
        $depCount = $depJars.Count
    }
}

# On Windows the classpath separator is ';'
$ClassPath = "$ClassesDir"
if ($depCount -gt 0) {
    $ClassPath += ";$DepDir\*"
}

# Spring Boot fat JAR is still produced as a side-effect (useful for reference)
$JarPath = Join-Path $TargetDir 'Browser4.jar'
if (Test-Path $JarPath) {
    $jarItem = Get-Item $JarPath
    Write-Log "Fat JAR:    $JarPath ($(Format-MB $jarItem.Length))"
}

# Provide a minimal logback.xml during the native-image build so that
# Logback's auto-configuration (a build-time operation) does not attempt
# to discover or watch files on disk — those FileDescriptors would leak
# into the image heap and crash the build.
$minimalLogbackXml = Join-Path $ClassesDir 'logback.xml'
if (-not (Test-Path $minimalLogbackXml)) {
    Write-Log 'Writing minimal logback.xml to suppress file auto-discovery ...'
    @'
<configuration scan="false">
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %-5level [%thread] %logger{36} - %msg%n</pattern>
        </encoder>
    </appender>
    <root level="INFO">
        <appender-ref ref="CONSOLE"/>
    </root>
</configuration>
'@ | Out-File -FilePath $minimalLogbackXml -Encoding UTF8 -NoNewline
    Write-Log "Wrote: $minimalLogbackXml"
}

# Register commons-logging's LogFactoryImpl for reflection.  commons-logging
# loads its factory implementation via Class.forName() at runtime; native-image
# cannot trace that, so we must explicitly tell it to include these classes.
$nativeImageMetaDir = Join-Path $ClassesDir 'META-INF\native-image\ai.platon.pulsar\browser4-standalone'
# Exclude Jetty auto-configuration so Spring Boot falls back to Tomcat.
# Jetty's extensive reflective inner-class instantiation is incompatible
# with native-image, and its JARs are removed from the flat classpath.
$appProperties = Join-Path $ClassesDir 'application.properties'
Write-Log 'Writing application.properties (exclude Jetty auto-config) ...'
$appPropsContent = @'
spring.autoconfigure.exclude=org.springframework.boot.jetty.autoconfigure.servlet.JettyServletWebServerAutoConfiguration,org.springframework.boot.jetty.autoconfigure.websocket.JettyWebSocketAutoConfiguration,org.springframework.boot.jetty.autoconfigure.JettyWebServerFactoryAutoConfiguration
'@
[System.IO.File]::WriteAllText($appProperties, $appPropsContent, [System.Text.UTF8Encoding]::new($false))
Write-Log "Wrote: $appProperties"

$reflectConfigJson = Join-Path $nativeImageMetaDir 'reflect-config.json'
if (-not (Test-Path $reflectConfigJson)) {
    Write-Log 'Writing reflect-config.json for commons-logging factories ...'
    New-Item -ItemType Directory -Force -Path $nativeImageMetaDir | Out-Null
    $reflectJson = @'
[
  {
    "name": "org.apache.commons.logging.impl.LogFactoryImpl",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredMethods": true,
    "allPublicMethods": true
  },
  {
    "name": "org.apache.commons.logging.impl.Slf4jLogFactory",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredMethods": true,
    "allPublicMethods": true
  },
  {
    "name": "org.eclipse.jetty.util.ClassMatcher$ByPackageOrName",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredMethods": true,
    "allPublicMethods": true
  },
  {
    "name": "org.eclipse.jetty.util.ClassMatcher$ByClass",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredMethods": true,
    "allPublicMethods": true
  },
  {
    "name": "org.eclipse.jetty.util.ClassMatcher$ByName",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredMethods": true,
    "allPublicMethods": true
  },
  {
    "name": "org.eclipse.jetty.util.ClassMatcher$ByLocationOrModule",
    "allDeclaredConstructors": true,
    "allPublicConstructors": true,
    "allDeclaredMethods": true,
    "allPublicMethods": true
  }
]
'@
    # Use .NET WriteAllText to avoid the UTF-8 BOM that Out-File appends.
    # GraalVM's JSON parser rejects files that start with a BOM.
    [System.IO.File]::WriteAllText($reflectConfigJson, $reflectJson, [System.Text.UTF8Encoding]::new($false))
    Write-Log "Wrote: $reflectConfigJson"
}

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
    Write-Step "$StepLabel (this may take several minutes for a Spring Boot app) ..."

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

# ---------------------------------------------------------------------------
# Spring Boot native-image flags
# ---------------------------------------------------------------------------
# These flags are required for Spring Boot applications compiled with
# native-image. They enable:
#   - HTTP/HTTPS URL protocol support (embedded web server)
#   - Resource pattern matching (Spring resource resolution)
#   - Build-time initialization of critical frameworks
#   - Reporting of exception stack traces for debugging
# ---------------------------------------------------------------------------
$springFlags = @(
    # Enable HTTP(S) protocol handlers for the embedded web server (Tomcat/Jetty)
    '--enable-url-protocols=http,https',

    # Report full exception stack traces (vs. "null" messages on native image)
    '-H:+ReportExceptionStackTraces',

    # Report unsupported / fallback features — helps diagnose missing metadata
    '-H:+ReportUnsupportedElementsAtRuntime',

    # Unlock experimental options (ReportUnsupportedElementsAtRuntime,
    # StripDebugInfo, etc. are still experimental in current GraalVM)
    '-H:+UnlockExperimentalVMOptions',

    # Initialize SLF4J API and Logback implementation at build time.
    # This allows static Logger fields to be safely stored in the image
    # heap.  Disable Logback's file watching (auto-scan) to prevent
    # FileDescriptor objects from leaking into the image heap.
    '--initialize-at-build-time=org.slf4j',
    '--initialize-at-build-time=ch.qos.logback',

    # Logback parses logback.xml via SAX at build time; SAX types must
    # be build-time-initialized so their objects (LocatorImpl, etc.)
    # can live in the image heap.
    '--initialize-at-build-time=org.xml.sax.helpers',
    '--initialize-at-build-time=org.xml.sax',

    # Initialise the application layer at runtime.  Most application
    # classes have static Logger fields that would pull Logback objects
    # into the image heap at build time.  Runtime init avoids this and
    # also lets Spring Boot's dynamic configuration work correctly.
    '--initialize-at-run-time=ai.platon.pulsar',

    # Spring Boot's Jetty auto-configuration classes must stay at
    # runtime so their annotations (@ConditionalOnClass, etc.) are
    # not parsed at build time.  Jetty JARs are removed from the
    # classpath; if the auto-config classes are build-time-init,
    # the annotation parser crashes trying to resolve Jetty types.
    '--initialize-at-run-time=org.springframework.boot.jetty',

    # SpringApplication.<clinit> calls LogFactory.getLog() which does an
    # SPI lookup for LogFactoryImpl that only resolves at runtime.
    # spring-jcl bridges JCL → SLF4J, so this must stay at runtime.
    '--initialize-at-run-time=org.springframework.boot.SpringApplication',

    # JCL's LogFactory also has SPI-based discovery in its <clinit>
    # (it searches for LogFactoryImpl on the classpath).
    '--initialize-at-run-time=org.apache.commons.logging.LogFactory',

    # Initialize Netty internal logging at build time so its Logger
    # objects (which wrap Logback loggers) can live in the image heap.
    '--initialize-at-build-time=io.netty.util.internal.logging',

    # ChannelHandlerMask has a static Logger that runs in a privileged
    # block during <clinit> — keep it at runtime.
    '--initialize-at-run-time=io.netty.channel.ChannelHandlerMask',

    # SslHandler references native SSL libraries that must be loaded at
    # runtime (they are not available during image build).
    '--initialize-at-run-time=io.netty.handler.ssl.SslHandler'
)

# AOT-generated metadata path (produced by spring-boot:process-aot)
$nativeImageMetaDir = Join-Path $TargetDir 'classes\META-INF\native-image'
if (-not (Test-Path $nativeImageMetaDir)) {
    # Fallback: look for metadata inside the JAR's META-INF/native-image
    Write-Log 'No AOT metadata directory found on disk — native-image will use metadata embedded in the JAR (if any).'
}

# Common flags
# Use -cp (flat classpath) instead of -jar because native-image cannot
# read Spring Boot's nested JAR layout (BOOT-INF/classes + BOOT-INF/lib/).
# target/classes holds the AOT-processed application classes;
# target/dependency/* holds every runtime-scoped JAR.
$commonFlags = @(
    '-cp', $ClassPath,
    '-H:Class=ai.platon.pulsar.apps.Browser4StandaloneApplicationKt',
    '-o', (Join-Path $TargetDir $OutputName),
    '--no-fallback',
    '-march=compatibility',
    "-J-Xmx$JavaHeap"
) + $springFlags

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

        $instrumentedOut = Join-Path $TargetDir "$OutputName-instrumented"
        $profileFile = Join-Path $pgoDir 'default.iprof'

        # -------- Pass 1: Instrumented build --------
        $instrumentFlags = @(
            '-cp', $ClassPath,
            '-H:Class=ai.platon.pulsar.apps.Browser4StandaloneApplicationKt',
            '-o', $instrumentedOut,
            '--pgo-instrument',
            '-march=compatibility',
            "-J-Xmx$JavaHeap"
        ) + $springFlags
        Build-NativeImage -Flags $instrumentFlags -StepLabel 'PGO Pass 1/2 — Building instrumented image'

        # -------- Collect profiling data --------
        Write-Step 'PGO Profiling — Running instrumented binary ...'
        Push-Location $TargetDir
        try {
            $proc = Start-Process -FilePath "$instrumentedOut.exe" -PassThru -WindowStyle Hidden
            Start-Sleep -Seconds 5

            try {
                # Exercise common endpoints to gather representative profiles
                $null = Invoke-WebRequest -Uri "http://localhost:$ServerPort/actuator/health" `
                    -TimeoutSec 5 `
                    -ErrorAction SilentlyContinue
                $null = Invoke-WebRequest -Uri "http://localhost:$ServerPort/api" `
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
if ($EnableUpx) {
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

            # UPX internal decompression test
            Write-Log 'Verifying compressed binary (UPX -t) ...'
            & upx -t $nativeExe 2>$null
            if ($LASTEXITCODE -ne 0) {
                Write-Warn "UPX -t verification failed — restoring uncompressed backup."
                Copy-Item -Force $backupExe $nativeExe
            }
            else {
                Write-Log 'UPX -t OK. Running smoke test ...'

                # Smoke test: the binary must stay alive for at least 3 seconds.
                # UPX can corrupt GraalVM native images (the binary exits
                # silently without producing any output). Restore the backup
                # if the smoke test fails.
                $smokeProc = Start-Process -FilePath $nativeExe `
                    -PassThru -WindowStyle Hidden `
                    -RedirectStandardError (Join-Path $TargetDir 'smoke-test-stderr.txt') `
                    -RedirectStandardOutput (Join-Path $TargetDir 'smoke-test-stdout.txt')

                Start-Sleep -Seconds 4

                if ($smokeProc.HasExited) {
                    Write-Warn 'Smoke test FAILED — binary exited immediately.'
                    Write-Warn 'UPX compression is incompatible with this GraalVM native image.'
                    Write-Warn 'Restoring uncompressed backup ...'
                    Copy-Item -Force $backupExe $nativeExe
                }
                else {
                    Stop-Process -Id $smokeProc.Id -Force -ErrorAction SilentlyContinue
                    Write-Log 'Smoke test PASSED — binary stays alive.'
                }
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
Write-Host "  Invoke-WebRequest http://localhost:$ServerPort/actuator/health"
Write-Host ''
