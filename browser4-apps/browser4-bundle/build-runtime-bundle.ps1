param(
    [string]$JarPath = (Join-Path $PSScriptRoot "target/Browser4Bundle.jar"),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "target/runtime-bundle"),
    [string]$AssetName,
    [string]$MainClass = '',
    [switch]$Force = $true
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

function Get-IsWindows {
    return [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Windows
    )
}

function Get-IsLinux {
    return [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Linux
    )
}

function Get-IsMacOS {
    return [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::OSX
    )
}

function Get-AssetNameForCurrentPlatform {
    $arch = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
    if (Get-IsWindows) {
        if ($arch -ne [System.Runtime.InteropServices.Architecture]::X64) {
            throw "Windows runtime bundles currently support only X64. Current architecture: $arch"
        }
        return 'browser4-bundle-runtime-windows-x64.zip'
    }
    if (Get-IsLinux) {
        if ($arch -ne [System.Runtime.InteropServices.Architecture]::X64) {
            throw "Linux runtime bundles currently support only X64. Current architecture: $arch"
        }
        return 'browser4-bundle-runtime-linux-x64.tar.gz'
    }
    if (Get-IsMacOS) {
        if ($arch -eq [System.Runtime.InteropServices.Architecture]::Arm64) {
            return 'browser4-bundle-runtime-darwin-arm64.tar.gz'
        }
        if ($arch -eq [System.Runtime.InteropServices.Architecture]::X64) {
            return 'browser4-bundle-runtime-darwin-x64.tar.gz'
        }
        throw "macOS runtime bundles currently support only X64 and Arm64. Current architecture: $arch"
    }
    throw "Unsupported OS for Browser4 runtime bundle generation."
}

function Remove-ArchiveSuffix([string]$name) {
    foreach ($suffix in @('.tar.gz', '.zip')) {
        if ($name.EndsWith($suffix, [System.StringComparison]::OrdinalIgnoreCase)) {
            return $name.Substring(0, $name.Length - $suffix.Length)
        }
    }
    return [System.IO.Path]::GetFileNameWithoutExtension($name)
}

# --------------------------------------------------------------------------
# JDK auto-detection — scan known install directories for jpackage (JDK 16+),
# pick the highest version, and set JAVA_HOME accordingly before any tool use.
# --------------------------------------------------------------------------

function Get-JDKVersion([string]$jdkHome) {
    # Fast path: parse <jdk>/release — no process spawn, I/O only.
    $releaseFile = Join-Path $jdkHome 'release'
    if (-not (Test-Path $releaseFile)) { return $null }

    $content = Get-Content -LiteralPath $releaseFile -Raw -ErrorAction SilentlyContinue
    if ($content -match 'JAVA_VERSION="([^"]+)"') {
        $versionStr = $Matches[1]
        # Normalise "17", "17.0.14", "17.0.14+7" etc.
        if ($versionStr -match '^(\d+)(?:\.(\d+))?(?:\.(\d+))?') {
            $major = [int]$Matches[1]
            $minor = if ($Matches[2]) { [int]$Matches[2] } else { 0 }
            $build = if ($Matches[3]) { [int]$Matches[3] } else { 0 }
            return [version]"$major.$minor.$build"
        }
    }
    return $null
}

function Find-BestJDK {
    # Scans common JDK install roots for jpackage (JDK 16+ marker),
    # returns the path of the highest-version JDK >= 16, or $null.
    $minMajor = 16
    $bestHome = $null
    $bestVersion = [version]'0.0'

    # Collect search roots — one filesystem scan per root, shallow.
    $searchRoots = [System.Collections.Generic.List[string]]::new()
    if (Get-IsWindows) {
        foreach ($base in @($env:ProgramFiles, ${env:ProgramFiles(x86)}, "$env:SystemDrive\Java")) {
            if ($base) { $searchRoots.Add($base) }
        }
        # Also cover each drive: <drive>:\<sub> AND <drive>:\Program Files\<sub>
        Get-PSDrive -PSProvider FileSystem | ForEach-Object {
            $pf = Join-Path $_.Root 'Program Files'
            foreach ($sub in @('Java', 'OpenLogic', 'Eclipse Adoptium', 'Microsoft', 'Zulu', 'Corretto')) {
                $searchRoots.Add((Join-Path $_.Root $sub))
                $searchRoots.Add((Join-Path $pf $sub))
            }
        }
    } elseif (Get-IsMacOS) {
        $searchRoots.Add('/Library/Java/JavaVirtualMachines')
        if ($env:HOME) { $searchRoots.Add((Join-Path $env:HOME '.sdkman/candidates/java')) }
    } else {
        foreach ($sub in @('/usr/lib/jvm', '/usr/java')) { $searchRoots.Add($sub) }
        if ($env:HOME) { $searchRoots.Add((Join-Path $env:HOME '.sdkman/candidates/java')) }
    }
    $searchRoots = $searchRoots | Where-Object { $_ -and (Test-Path $_) } | Select-Object -Unique

    foreach ($root in $searchRoots) {
        $jdkDirs = Get-ChildItem -Path $root -Directory -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -match 'jdk|graalvm|openjdk|zulu|temurin|corretto|sapmachine' }
        foreach ($jdkDir in $jdkDirs) {
            $jpkg = if (Get-IsWindows) { Join-Path $jdkDir.FullName 'bin\jpackage.exe' }
                     else { Join-Path $jdkDir.FullName 'bin/jpackage' }
            if (-not (Test-Path $jpkg)) { continue }

            $ver = Get-JDKVersion -jdkHome $jdkDir.FullName
            if ($ver -and $ver.Major -ge $minMajor -and $ver -gt $bestVersion) {
                $bestVersion = $ver
                $bestHome = $jdkDir.FullName
            }
        }
    }

    return $bestHome
}

function Resolve-JavaHome {
    $best = Find-BestJDK
    if ($best) {
        Write-Host "Auto-selected JDK for bundle build: $best ($(Get-JDKVersion -jdkHome $best))" -ForegroundColor Cyan
        $env:JAVA_HOME = $best
        return $best
    }

    if ($env:JAVA_HOME) {
        Write-Host "No JDK >= 16 found via auto-detection; using JAVA_HOME from environment: $env:JAVA_HOME" -ForegroundColor Yellow
    } else {
        Write-Host "No JDK >= 16 found; relying on PATH resolution (jlink --compress zip-9 requires JDK 21+)." -ForegroundColor Yellow
    }
    return $env:JAVA_HOME
}

function Resolve-ToolPath([string]$toolName) {
    $toolFileName = if (Get-IsWindows) { "$toolName.exe" } else { $toolName }
    if ($env:JAVA_HOME) {
        $candidate = Join-Path $env:JAVA_HOME "bin/$toolFileName"
        if (Test-Path $candidate) {
            return (Resolve-Path $candidate).Path
        }
    }

    $command = Get-Command $toolName -ErrorAction SilentlyContinue
    if ($command) {
        return $command.Source
    }

    throw "Required tool '$toolName' was not found. Ensure JDK 16+ is installed and JAVA_HOME is configured."
}

function Get-JavaVersionText {
    $java = Resolve-ToolPath 'java'
    $tempRoot = [System.IO.Path]::GetTempPath()
    $stdoutPath = Join-Path $tempRoot ([System.IO.Path]::GetRandomFileName())
    $stderrPath = Join-Path $tempRoot ([System.IO.Path]::GetRandomFileName())
    try {
        $process = Start-Process -FilePath $java `
            -ArgumentList @('-version') `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -Wait `
            -PassThru
        if ($process.ExitCode -ne 0) {
            throw "java -version failed with exit code $($process.ExitCode)"
        }
        $stdout = if (Test-Path $stdoutPath) { Get-Content $stdoutPath -Raw } else { '' }
        $stderr = if (Test-Path $stderrPath) { Get-Content $stderrPath -Raw } else { '' }
        return ($stdout + [Environment]::NewLine + $stderr).Trim()
    }
    finally {
        Remove-IfExists $stdoutPath
        Remove-IfExists $stderrPath
    }
}

function Ensure-CleanDirectory([string]$path) {
    if (Test-Path $path) {
        # Use \\?\ prefix to bypass Windows MAX_PATH (260 char) limit.
        $longPath = if (Get-IsWindows -and -not $path.StartsWith('\\?\' -as [String])) { "\\?\$path" } else { $path }
        Remove-Item -LiteralPath $longPath -Recurse -Force -ErrorAction SilentlyContinue
        # If the long-path removal left empty directories behind, clean up via normal path.
        if (Test-Path $path) {
            Remove-Item -Recurse -Force $path -ErrorAction SilentlyContinue
        }
    }
    New-Item -ItemType Directory -Force -Path $path | Out-Null
}

function Get-PathSeparator {
    if (Get-IsWindows) { return ';' }
    return ':'
}

function Test-ValidJar([string]$jarPath) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem

    $archive = $null
    try {
        $archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
        return $true
    }
    catch {
        Write-Warning "Skipping invalid jar for jdeps: $jarPath"
        return $false
    }
    finally {
        if ($archive) {
            $archive.Dispose()
        }
    }
}

function Get-JdepsClassPath([string]$libDirectory) {
    # Use wildcard classpath (lib/*) instead of listing every JAR individually.
    # Joining all JAR paths into a single argument easily exceeds the Windows
    # command-line length limit (~32K chars) when the dependency tree is deep.
    # Java / jdeps resolve wildcard classpaths natively.
    $validJars = @(
        Get-ChildItem -Path $libDirectory -File -Filter '*.jar' -ErrorAction SilentlyContinue |
            Where-Object { Test-ValidJar $_.FullName } |
            Select-Object -ExpandProperty FullName
    )

    if ($validJars.Count -eq 0) {
        return $null
    }

    # Normalize to forward slashes for Java compatibility on all platforms.
    $normalizedPath = $libDirectory -replace '\\', '/'
    return "$normalizedPath/*"
}

function Get-BundleMetadataJson(
    [string]$assetName,
    [string[]]$modules,
    [string]$mainClass
) {
    $metadata = [ordered]@{
        assetName = $assetName
        mainClass = $mainClass
        runtimeDirectoryName = 'runtime'
        libDirectoryName = 'lib'
        modules = $modules
        builtAtUtc = [DateTime]::UtcNow.ToString('o')
    }
    return ($metadata | ConvertTo-Json -Depth 6)
}

function Remove-IfExists([string]$path) {
    if (Test-Path $path) {
        $longPath = if (Get-IsWindows -and -not $path.StartsWith('\\?\' -as [String])) { "\\?\$path" } else { $path }
        Remove-Item -LiteralPath $longPath -Force -Recurse -ErrorAction SilentlyContinue
    }
}

function Remove-SafeRuntimePayload([string]$runtimeRoot) {
    $safeToRemove = @(
        'lib\ct.sym',
        'lib\jvm.lib',
        'bin\jvmcicompiler.dll',
        'bin\libjvmcicompiler.dylib',
        'bin\libjvmcicompiler.so',
        'bin\jar.exe',
        'bin\jarsigner.exe',
        'bin\javac.exe',
        'bin\javadoc.exe',
        'bin\javap.exe',
        'bin\jdb.exe',
        'bin\jdeps.exe',
        'bin\jfr.exe',
        'bin\jimage.exe',
        'bin\jlink.exe',
        'bin\jmod.exe',
        'bin\jpackage.exe',
        'bin\jrunscript.exe',
        'bin\jshell.exe',
        'bin\jstatd.exe',
        'bin\keytool.exe',
        'bin\kinit.exe',
        'bin\klist.exe',
        'bin\ktab.exe',
        'bin\rmiregistry.exe',
        'bin\serialver.exe',
        'bin\jaccessinspector.exe',
        'bin\jaccesswalker.exe'
    )

    foreach ($relativePath in $safeToRemove) {
        Remove-IfExists (Join-Path $runtimeRoot $relativePath)
    }
}

function Read-ManifestAttribute([string]$jarPath, [string]$attributeName) {
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $archive = [System.IO.Compression.ZipFile]::OpenRead($jarPath)
    try {
        $entry = $archive.GetEntry('META-INF/MANIFEST.MF')
        if (-not $entry) {
            return $null
        }
        $reader = [System.IO.StreamReader]::new($entry.Open())
        try {
            $manifestText = $reader.ReadToEnd()
            foreach ($line in $manifestText -split "`n") {
                if ($line -match "^$attributeName\s*:\s*(.+)$") {
                    return $Matches[1].Trim()
                }
            }
            return $null
        }
        finally {
            $reader.Dispose()
        }
    }
    finally {
        $archive.Dispose()
    }
}

function Get-DefaultMainClass([string]$jarPath) {
    # Try Start-Class first (Spring Boot convention), then Main-Class
    $startClass = Read-ManifestAttribute -jarPath $jarPath -attributeName 'Start-Class'
    if ($startClass) {
        return $startClass
    }
    $mainClass = Read-ManifestAttribute -jarPath $jarPath -attributeName 'Main-Class'
    if ($mainClass) {
        return $mainClass
    }
    # Fall back to the known main class for browser4-bundle
    return 'ai.platon.pulsar.apps.Browser4BundleApplicationKt'
}

function Write-LaunchScripts([string]$bundleDirectory, [string]$mainClass) {
    $binDirectory = Join-Path $bundleDirectory 'bin'
    New-Item -ItemType Directory -Force -Path $binDirectory | Out-Null

    $startShPath = Join-Path $binDirectory 'start.sh'
    $startShContent = @'
#!/bin/bash
set -e
DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
BUNDLE_DIR="$(dirname "$DIR")"
RUNTIME="$BUNDLE_DIR/runtime/bin/java"
LIB_CP="$BUNDLE_DIR/lib/*"
MAIN_CLASS="{mainClass}"
exec "$RUNTIME" -cp "$LIB_CP" "$MAIN_CLASS" "$@"
'@ -replace '\{mainClass\}', $mainClass
    $startShContent = $startShContent -replace "`r`n", "`n"
    Set-Content -LiteralPath $startShPath -Value $startShContent -Encoding UTF8 -NoNewline
    # Ensure newline at end for UNIX compatibility
    Add-Content -LiteralPath $startShPath -Value "`n" -Encoding UTF8

    $startBatPath = Join-Path $binDirectory 'start.bat'
    $startBatContent = @"
@echo off
setlocal
set "DIR=%~dp0"
set "BUNDLE_DIR=%DIR%.."
set "RUNTIME=%BUNDLE_DIR%\runtime\bin\java.exe"
set "LIB_CP=%BUNDLE_DIR%\lib\*"
set "MAIN_CLASS=$mainClass"
"%RUNTIME%" -cp "%LIB_CP%" %MAIN_CLASS% %*
"@
    Set-Content -LiteralPath $startBatPath -Value $startBatContent -Encoding ASCII
}

# ============================================================================
# Main script
# ============================================================================

# Resolve Maven command and repo root early — both the JAR auto-build and
# dependency:copy-dependencies need them.
$repoRoot = git rev-parse --show-toplevel
$mvnCmd = if (Get-IsWindows) { Join-Path $repoRoot 'mvnw.cmd' } else { Join-Path $repoRoot 'mvnw' }
$bundleModule = 'browser4-apps/browser4-bundle'

# Install core modules to ~/.m2 so dependency:copy-dependencies can resolve
# internal reactor artifacts (browser4-resources, browser4-skeleton, etc.)
# that are not published to Maven Central.  This is idempotent — on the
# second run Maven only touches unchanged files.
Write-Host "Ensuring core modules are installed to ~/.m2 ..."
$coreArgs = @('install', '-DskipTests', '-Dmaven.javadoc.skip=true', '-q')
& $mvnCmd @coreArgs
if ($LASTEXITCODE -ne 0) { throw "Core modules install failed with exit code $LASTEXITCODE" }

# Auto-build the bundle JAR if it doesn't exist yet (package only — the
# CLI handles installation via its own flow).
if (-not (Test-Path $JarPath)) {
    Write-Host "Bundle JAR not found, building from source ..." -ForegroundColor Yellow
    Write-Host "  Building $bundleModule ..."
    $bundleArgs = @('package', '-pl', $bundleModule, '-am', '-Passet-bundle', '-DskipTests', '-Dmaven.javadoc.skip=true', '-q')
    & $mvnCmd @bundleArgs
    if ($LASTEXITCODE -ne 0) { throw "Bundle JAR build failed with exit code $LASTEXITCODE" }

    if (-not (Test-Path $JarPath)) {
        throw "Bundle JAR still not found after build: $JarPath"
    }
    Write-Host "  Bundle JAR built successfully." -ForegroundColor Green
}

$resolvedJarPath = (Resolve-Path $JarPath).Path
if ([string]::IsNullOrWhiteSpace($AssetName)) {
    $AssetName = Get-AssetNameForCurrentPlatform
}

# Determine main class
if ([string]::IsNullOrWhiteSpace($MainClass)) {
    $MainClass = Get-DefaultMainClass -jarPath $resolvedJarPath
}
Write-Host "Using main class: $MainClass" -ForegroundColor Cyan

$bundleBaseName = Remove-ArchiveSuffix $AssetName
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$workDirectory = Join-Path $resolvedOutputDirectory (Join-Path '_work' $bundleBaseName)
$bundleDirectory = Join-Path $workDirectory $bundleBaseName
$runtimeDirectory = Join-Path $bundleDirectory 'runtime'
$libDirectory = Join-Path $bundleDirectory 'lib'
$assetPath = Join-Path $resolvedOutputDirectory $AssetName

if ((Test-Path $assetPath) -and (-not $Force)) {
    throw "Target asset already exists: $assetPath. Re-run with -Force to overwrite it."
}

New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null
Ensure-CleanDirectory $workDirectory
Ensure-CleanDirectory $bundleDirectory
Ensure-CleanDirectory $libDirectory

# Auto-detect best JDK before resolving jdeps/jlink.
$null = Resolve-JavaHome

$jdeps = Resolve-ToolPath 'jdeps'
$jlink = Resolve-ToolPath 'jlink'
$javaVersionText = Get-JavaVersionText
$isGraalVmRuntime = $javaVersionText -match 'GraalVM'

# Parse the JDK major version from java -version output as a fallback for
# --multi-release (primary source is the release file read by Get-JDKVersion).
# On some platforms / JDK distributions the release file may be in an
# unexpected location, so this secondary check is essential.
$javaVersionMajor = $null
if ($javaVersionText -match 'version\s+"(\d+)') {
    $javaVersionMajor = [int]$Matches[1]
} elseif ($javaVersionText -match 'version\s+"[\d._]+-(\d+)') {
    $javaVersionMajor = [int]$Matches[1]
}

# --------------------------------------------------------------------------
# Progress tracking
# --------------------------------------------------------------------------
$buildPhases = @(
    @{ Label = 'Collecting runtime dependencies (Maven)' },
    @{ Label = 'Copying application JAR' },
    @{ Label = 'Computing JRE modules (jdeps)' },
    @{ Label = 'Generating bundled JRE (jlink)' },
    @{ Label = 'Writing launch scripts' },
    @{ Label = 'Packaging bundle archive' }
)
$totalPhases = $buildPhases.Count
$phaseIndex = 0

function Write-BuildProgress {
    param([string]$Status)
    $percent = [math]::Round(($phaseIndex / $totalPhases) * 100)
    $activity = "Building Browser4 runtime bundle: $AssetName"
    Write-Progress -Activity $activity -Status $Status -PercentComplete $percent
}

function Complete-BuildProgress {
    Write-Progress -Activity "Building Browser4 runtime bundle: $AssetName" -Completed
}

# --------------------------------------------------------------------------
# Collect dependencies via Maven
# --------------------------------------------------------------------------
$phaseIndex = 1
Write-BuildProgress -Status $buildPhases[$phaseIndex - 1].Label

$mvnCommand = Get-Command mvn -ErrorAction SilentlyContinue
if (-not $mvnCommand) {
    # Check common locations
    $mvnHome = $env:MAVEN_HOME
    if ($mvnHome) {
        $mvnExe = if (Get-IsWindows) { Join-Path $mvnHome 'bin/mvn.cmd' } else { Join-Path $mvnHome 'bin/mvn' }
        if (Test-Path $mvnExe) {
            $mvnCommand = $mvnExe
        }
    }
    if (-not $mvnCommand) {
        throw 'Maven (mvn) is required to collect dependencies. Ensure Maven is installed and on PATH, or set MAVEN_HOME.'
    }
}

$mvnPath = if ($mvnCommand -is [string]) { $mvnCommand } else { $mvnCommand.Source }
Write-Host "Collecting runtime dependencies with Maven..." -ForegroundColor Cyan
$mvnArgs = @(
    'dependency:copy-dependencies',
    "-DoutputDirectory=$libDirectory",
    '-DincludeScope=runtime',
    '-DexcludeTransitive=false',
    '-f', (Join-Path $PSScriptRoot 'pom.xml')
)
& $mvnPath @mvnArgs
if ($LASTEXITCODE -ne 0) {
    throw "Maven dependency collection failed with exit code $LASTEXITCODE"
}

# Copy the application jar into lib/
$phaseIndex = 2
Write-BuildProgress -Status $buildPhases[$phaseIndex - 1].Label

$appJarFileName = [System.IO.Path]::GetFileName($resolvedJarPath)
Copy-Item -LiteralPath $resolvedJarPath -Destination (Join-Path $libDirectory $appJarFileName) -Force

$libJarCount = (Get-ChildItem -Path $libDirectory -File -Filter '*.jar' | Measure-Object).Count
Write-Host "Collected $libJarCount jars in lib/" -ForegroundColor Green

# --------------------------------------------------------------------------
# Compute required JRE modules with jdeps
# --------------------------------------------------------------------------
$phaseIndex = 3
Write-BuildProgress -Status $buildPhases[$phaseIndex - 1].Label

Write-Host "Running jdeps to compute Browser4 runtime modules..." -ForegroundColor Cyan

# Create a temp directory for extracted app classes (jdeps works best with classes)
$appClassesDir = Join-Path $workDirectory 'app-classes'
Ensure-CleanDirectory $appClassesDir
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($resolvedJarPath, $appClassesDir)





# Compute the multi-release version from the JDK being used.
# This ensures jdeps processes multi-release JARs with the correct
# version-specific class files for the target runtime.
# Priority: 1) release file via Get-JDKVersion  2) java -version output
#            3) hardcoded '17' as last resort
$jdkVersion = Get-JDKVersion -jdkHome $env:JAVA_HOME
$detectedMajor = if ($jdkVersion) { $jdkVersion.Major } elseif ($javaVersionMajor) { $javaVersionMajor } else { $null }
$multiReleaseVersion = if ($detectedMajor) { [string]$detectedMajor } else { '17' }
Write-Host "Using multi-release version: $multiReleaseVersion (release-file: $($jdkVersion), java-cmd: $javaVersionMajor)" -ForegroundColor Cyan

# --------------------------------------------------------------------------
# Primary jdeps strategy: full recursive analysis with class-path.
# On some platforms, specific dependency JARs (e.g. native transports)
# may contain class files that jdeps cannot parse.  When that happens we
# fall back to analysing only the application classes.
# --------------------------------------------------------------------------
$jdepsOutput = $null
$jdepsSuccess = $false

# Build primary jdeps argument list
$primaryJdepsArgs = @(
    '-q',
    '--ignore-missing-deps',
    '--multi-release', $multiReleaseVersion,
    '--recursive',
    '--print-module-deps'
)
if ($libJarCount -gt 0) {
    $jdepsClassPath = Get-JdepsClassPath -libDirectory $libDirectory
    if (-not [string]::IsNullOrWhiteSpace($jdepsClassPath)) {
        $primaryJdepsArgs += @('--class-path', $jdepsClassPath)
    }
}
$primaryJdepsArgs += $appClassesDir

# Run primary jdeps — capture all output (stdout + stderr) so we can
# inspect failures without the ErrorActionPreference=Stop killing us.
$prevErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
try {
    $jdepsOutput = & $jdeps @primaryJdepsArgs 2>&1
    $jdepsSuccess = ($LASTEXITCODE -eq 0)
} catch {
    $jdepsOutput = $_.Exception.Message
    $jdepsSuccess = $false
} finally {
    $ErrorActionPreference = $prevErrorAction
    # Reset LASTEXITCODE so later non-zero checks are not poisoned
    if (-not $jdepsSuccess) { $global:LASTEXITCODE = 0 }
}

if (-not $jdepsSuccess) {
    Write-Warning "Primary jdeps failed (full recursive analysis with $libJarCount dependency JARs)."
    Write-Warning "jdeps stderr: $jdepsOutput"

    # ----------------------------------------------------------------------
    # Fallback: per-JAR analysis.
    #
    # Run jdeps on each JAR individually (no --recursive, no --class-path)
    # and union all the module dependencies.  JARs that cause jdeps errors
    # (e.g. ClassFileError: Bad magic number from platform-specific native
    # dependency JARs like netty-transport-native-epoll on Linux or
    # netty-transport-native-kqueue on macOS) are skipped.
    #
    # The union of every JAR's direct module deps is a superset of the
    # recursive transitive closure — it processes every class in every JAR,
    # not just classes reachable from the app entry points.  This is
    # correct and safe: it can only over-approximate, never miss a module.
    # ----------------------------------------------------------------------
    Write-Host "Falling back to per-JAR analysis (individual JAR scanning)..." -ForegroundColor Yellow

    $allJarModules = @()
    $goodJars = 0
    $badJars = 0
    $badJarNames = @()

    # Temporarily relax ErrorActionPreference so a single bad JAR cannot
    # abort the whole loop.  Restore via try/finally to avoid leaking the
    # relaxed setting into later phases (jlink, packaging, ...).
    $ErrorActionPreference = 'Continue'
    try {
        # Analyse the application JAR first (it is also in lib/, but we
        # process it explicitly so a failure here is visible).
        $appModuleText = & $jdeps -q --ignore-missing-deps --multi-release $multiReleaseVersion --print-module-deps $resolvedJarPath 2>&1
        if ($LASTEXITCODE -eq 0) {
            $allJarModules += ($appModuleText -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
        } else {
            Write-Warning "  Even the application JAR failed jdeps: $appModuleText"
        }
        $global:LASTEXITCODE = 0

        # Analyse every dependency JAR in lib/ — skip the few that upset jdeps.
        $allJars = @(Get-ChildItem -Path $libDirectory -File -Filter '*.jar' -ErrorAction SilentlyContinue)
        foreach ($jar in $allJars) {
            $jarOutput = & $jdeps -q --ignore-missing-deps --multi-release $multiReleaseVersion --print-module-deps $jar.FullName 2>&1
            if ($LASTEXITCODE -eq 0) {
                $allJarModules += ($jarOutput -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
                $goodJars++
            } else {
                Write-Warning "  Skipping problematic JAR: $($jar.Name)"
                $badJarNames += $jar.Name
                $badJars++
            }
            $global:LASTEXITCODE = 0
        }

        Write-Host "Per-JAR analysis: $goodJars succeeded, $badJars skipped." -ForegroundColor Cyan
        if ($badJars -gt 0) {
            Write-Host "  Skipped JARs: $($badJarNames -join ', ')" -ForegroundColor Yellow
        }

        if ($allJarModules.Count -gt 0) {
            # Reproduce the comma-separated output format that the
            # module-processing block below expects.
            $jdepsOutput = ($allJarModules | Select-Object -Unique) -join ','
            $jdepsSuccess = $true
        } else {
            Write-Warning "Per-JAR analysis produced no modules (all $libJarCount JARs are problematic)."
        }
    } catch {
        Write-Warning "Per-JAR analysis threw: $($_.Exception.Message)"
    } finally {
        $ErrorActionPreference = $prevErrorAction
        if (-not $jdepsSuccess) { $global:LASTEXITCODE = 0 }
    }
}

if (-not $jdepsSuccess) {
    throw "jdeps failed with both primary (full classpath) and per-JAR strategies."
}






$recommendedModules = @(
    'java.management',
    'jdk.crypto.ec'
)
$excludedModules = @(
    'jdk.attach',
    'jdk.jdi',
    'jdk.jfr',
    'jdk.management',
    'jdk.zipfs'
)
$modules = @(
    ($jdepsOutput -split ',') + $recommendedModules |
        ForEach-Object { $_.Trim() } |
        Where-Object {
            (-not [string]::IsNullOrWhiteSpace($_)) -and
            ($_.StartsWith('java.') -or $_.StartsWith('jdk.')) -and
            ($_ -notin $excludedModules)
        } |
        Select-Object -Unique
)
if ($modules.Count -eq 0) {
    throw 'jdeps did not return any modules to include in the Browser4 runtime image.'
}

# --------------------------------------------------------------------------
# Generate bundled JRE with jlink
# --------------------------------------------------------------------------
$phaseIndex = 4
Write-BuildProgress -Status $buildPhases[$phaseIndex - 1].Label

Write-Host "Running jlink with modules: $($modules -join ',')" -ForegroundColor Cyan
$jlinkArgs = @(
    '--add-modules', ($modules -join ','),
    '--vm', 'server',
    '--strip-debug',
    '--strip-java-debug-attributes',
    '--no-header-files',
    '--no-man-pages',
    '--dedup-legal-notices', 'error-if-not-same-content',
    '--compress', 'zip-9',
    '--output', $runtimeDirectory
)
if ($isGraalVmRuntime) {
    $jlinkArgs = @('--add-options', '-XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler') + $jlinkArgs
}
& $jlink @jlinkArgs
if ($LASTEXITCODE -ne 0) {
    throw "jlink failed with exit code $LASTEXITCODE"
}

Remove-SafeRuntimePayload $runtimeDirectory

# --------------------------------------------------------------------------
# Write launch scripts and metadata
# --------------------------------------------------------------------------
$phaseIndex = 5
Write-BuildProgress -Status $buildPhases[$phaseIndex - 1].Label

Write-Host "Writing launch scripts..." -ForegroundColor Cyan
Write-LaunchScripts -bundleDirectory $bundleDirectory -mainClass $MainClass

Set-Content -LiteralPath (Join-Path $bundleDirectory 'runtime-bundle.json') `
    -Value (Get-BundleMetadataJson -assetName $AssetName -modules $modules -mainClass $MainClass) `
    -Encoding UTF8

# --------------------------------------------------------------------------
# Package the bundle archive
# --------------------------------------------------------------------------
$phaseIndex = 6
Write-BuildProgress -Status $buildPhases[$phaseIndex - 1].Label

if (Test-Path $assetPath) {
    Remove-Item -Force $assetPath
}

if ($AssetName.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase)) {
    Compress-Archive -Path (Join-Path $bundleDirectory '*') -DestinationPath $assetPath -CompressionLevel Optimal -Force
} elseif ($AssetName.EndsWith('.tar.gz', [System.StringComparison]::OrdinalIgnoreCase)) {
    $tarCommand = Get-Command tar -ErrorAction SilentlyContinue
    if (-not $tarCommand) {
        throw 'tar is required to create .tar.gz runtime bundles on this platform.'
    }
    & $tarCommand.Source -czf $assetPath -C $bundleDirectory .
    if ($LASTEXITCODE -ne 0) {
        throw "tar failed with exit code $LASTEXITCODE"
    }
} else {
    throw "Unsupported runtime bundle archive format: $AssetName"
}

# --------------------------------------------------------------------------
# Sanity checks
# --------------------------------------------------------------------------
$javaExecutableName = if (Get-IsWindows) { 'java.exe' } else { 'java' }
$javaPath = Join-Path $runtimeDirectory (Join-Path 'bin' $javaExecutableName)
if (-not (Test-Path $javaPath)) {
    throw "Generated runtime image is missing java launcher: $javaPath"
}

if ((Get-ChildItem -Path $libDirectory -File -Filter '*.jar' | Measure-Object).Count -eq 0) {
    throw "No jars found in lib/ directory"
}

Complete-BuildProgress

$assetSize = [math]::Round(((Get-Item $assetPath).Length / 1MB), 2)
Write-Host "Browser4 runtime bundle created: $assetPath ($assetSize MB)" -ForegroundColor Green
