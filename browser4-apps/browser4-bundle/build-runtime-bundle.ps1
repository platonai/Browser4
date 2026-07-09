<#
.SYNOPSIS
Build a self-contained Browser4 runtime bundle with an embedded JRE.

.DESCRIPTION
This script builds a platform-native runtime bundle for Browser4.  It:
  1. Expects the Browser4 bundle JAR and ~/.m2 dependencies to already be
     prepared (the CLI daemon runs `mvn install -P"all-main-modules,asset-bundle"`
     before invoking this script).  When running standalone, run that Maven
     command first.
  2. Auto-detects or accepts a JDK 16+ installation.
  3. Collects all runtime JARs via Maven dependency:copy-dependencies.
  4. Computes the minimal set of JRE modules required via jdeps.
  5. Generates a bundled, stripped-down JRE with jlink.
  6. Writes launch scripts (start.sh / start.bat) and packages everything into a
     platform archive.

The output is a .zip (Windows) or .tar.gz (Linux/macOS) archive containing:
  runtime/           - bundled JRE (stripped, compressed)
  lib/               - application and dependency JARs
  plugins/           - optional plugin JARs (auto-discovered via classpath wildcard)
  bin/               - launch scripts (start.sh, start.bat)
  runtime-bundle.json - metadata

JDK auto-detection scans common install locations:
  Windows: Program Files\Java, Eclipse Adoptium, Microsoft, Zulu, Corretto, OpenLogic
  macOS:   /Library/Java/JavaVirtualMachines, ~/.sdkman/candidates/java
  Linux:   /usr/lib/jvm, /usr/java, ~/.sdkman/candidates/java

.PARAMETER JarPath
Path to the Browser4Bundle.jar file.
Default: [script-dir]/target/Browser4Bundle.jar

.PARAMETER OutputDirectory
Directory where the final runtime bundle archive will be placed.
Default: [script-dir]/target/runtime-bundle

.PARAMETER AssetName
Name of the output archive file (e.g. "browser4-bundle-runtime-windows-x64.zip").
If not specified, auto-detected based on the current OS and architecture.

.PARAMETER MainClass
Fully-qualified Java main class name for the launch scripts.
If not specified, read from the JAR manifest (Start-Class or Main-Class attribute),
falling back to the Browser4 default: ai.platon.pulsar.apps.Browser4BundleApplicationKt

.PARAMETER Force
Overwrite the output archive if it already exists.  Default: $true.

.PARAMETER ListJDKs
Scan the system for all JDK 16+ installations with jpackage and display a
version/path table showing which JDK was selected and why.
The build proceeds normally after the report is printed.

.PARAMETER JdkHome
Use the specified JDK directory directly, bypassing auto-detection entirely.
The directory must contain bin/jpackage (or bin/jpackage.exe on Windows)
and be JDK 16+.  Example: -JdkHome "C:\Program Files\Java\jdk-21"

.PARAMETER JdkVersion
Preferred JDK major version for auto-detection (e.g. 21, 17).
When specified, Find-BestJDK selects the highest JDK matching this major version.
If no JDK with that version is found, falls back to the highest available version.

.PARAMETER SkipMavenInstall
Skip the `mvn install` step that installs Browser4 modules to ~/.m2.
Use this when the caller has already run the install (e.g. the Browser4
CLI daemon runs `mvn install -P"all-main-modules,asset-bundle"` before
invoking this script).  Default: $false (run mvn install).

.PARAMETER Help
Display this help message and exit without building.

.PARAMETER ShowMavenOutput
Do not pass -q (quiet) to Maven commands.  Use this when running the script
standalone so you can inspect Maven progress and status output.
Default: $false (Maven runs with -q).

.EXAMPLE
.\build-runtime-bundle.ps1
Build the runtime bundle with all defaults: auto-detect JDK, auto-detect platform,
auto-detect main class from the JAR manifest.

.EXAMPLE
.\build-runtime-bundle.ps1 -JdkVersion 21
Build using the highest JDK 21 found on the system.  Falls back to the overall
highest JDK if no JDK 21 is installed.

.EXAMPLE
.\build-runtime-bundle.ps1 -JdkHome "C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot"
Build using a specific JDK installation.

.EXAMPLE
.\build-runtime-bundle.ps1 -ListJDKs
Scan and display all detected JDKs (with the selected one highlighted), then
proceed with the build.

.EXAMPLE
.\build-runtime-bundle.ps1 -ListJDKs -JdkVersion 17
List all JDKs and prefer JDK 17 for the build.

.EXAMPLE
.\build-runtime-bundle.ps1 -AssetName "custom-bundle.zip" -MainClass "com.example.Main"
Build with a custom output archive name and explicit main class.

.EXAMPLE
.\build-runtime-bundle.ps1 -Force:$false
Never overwrite an existing bundle archive (throws if the target already exists).

.NOTES
Requires: PowerShell 5.1+ (pwsh or powershell.exe), Maven (mvn / mvnw), JDK 16+ (for jlink/jdeps/jpackage).
On Windows the script uses mvnw.cmd from the repository root; on Linux/macOS it
uses the mvnw shell script.
#>
param(
    [string]$JarPath = (Join-Path $PSScriptRoot "target/Browser4Bundle.jar"),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "target/runtime-bundle"),
    [string]$AssetName,
    [string]$MainClass = '',
    [switch]$Force = $true,
    [switch]$ListJDKs = $false,
    [string]$JdkHome = '',
    [int]$JdkVersion = 0,
    [switch]$SkipMavenInstall = $false,
    [switch]$ShowMavenOutput = $false,
    [switch]$Help = $false
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# Display comment-based help and exit when -Help is passed.  We do this early
# (before any function definitions or side-effects) so the user gets a fast
# response without Maven/JDK/network activity.
if ($Help) {
    Get-Help -Full $PSCommandPath
    exit 0
}

# --------------------------------------------------------------------------
# Runtime compatibility shim — allows the script to run under both
# powershell.exe (Windows PowerShell 5.1, .NET Framework) and
# pwsh (PowerShell 7+, .NET).  On .NET Framework the
# System.Runtime.InteropServices.RuntimeInformation type may not be
# auto-loaded; we try to load it explicitly and fall back to
# Windows-only assumptions if it is unavailable (powershell.exe is
# Windows-only, so the fallback is safe).
# --------------------------------------------------------------------------
$script:_runtimeInfoAvailable = $false
try {
    Add-Type -AssemblyName System.Runtime.InteropServices.RuntimeInformation -ErrorAction Stop
} catch {}
try {
    $null = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Windows)
    $script:_runtimeInfoAvailable = $true
} catch {}

function Get-IsWindows {
    if ($script:_runtimeInfoAvailable) {
        return [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [System.Runtime.InteropServices.OSPlatform]::Windows)
    }
    # powershell.exe runs only on Windows — this fallback is correct.
    return $true
}

function Get-IsLinux {
    if ($script:_runtimeInfoAvailable) {
        return [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [System.Runtime.InteropServices.OSPlatform]::Linux)
    }
    return $false
}

function Get-IsMacOS {
    if ($script:_runtimeInfoAvailable) {
        return [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
            [System.Runtime.InteropServices.OSPlatform]::OSX)
    }
    return $false
}

function Get-OSArchitecture {
    if ($script:_runtimeInfoAvailable) {
        return [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
    }
    # Fallback for .NET Framework 2.0+ — powershell.exe is Windows-only
    if ([Environment]::Is64BitOperatingSystem) { return 'X64' }
    return 'X86'
}

function Get-AssetNameForCurrentPlatform {
    # Fast path when RuntimeInformation is unavailable (powershell.exe on
    # older .NET Framework) — the script is Windows-only, so skip the
    # platform/architecture type lookups entirely.
    if (-not $script:_runtimeInfoAvailable) {
        if (-not [Environment]::Is64BitOperatingSystem) {
            throw "Windows runtime bundles currently support only X64."
        }
        return 'browser4-bundle-runtime-windows-x64.zip'
    }

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

function Convert-ToExtendedLengthPath([string]$path) {
    if (-not (Get-IsWindows) -or [string]::IsNullOrWhiteSpace($path)) {
        return $path
    }

    $fullPath = [System.IO.Path]::GetFullPath($path)
    if ($fullPath.StartsWith('\\?\')) {
        return $fullPath
    }
    if ($fullPath.StartsWith('\\')) {
        return ('\\?\UNC\' + $fullPath.TrimStart('\'))
    }
    return "\\?\$fullPath"
}

function Resolve-InputPath([string]$path, [string]$baseDirectory) {
    if ([string]::IsNullOrWhiteSpace($path)) {
        return $path
    }
    if ([System.IO.Path]::IsPathRooted($path)) {
        return [System.IO.Path]::GetFullPath($path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $baseDirectory $path))
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
    param([int]$PreferredMajor = 0)

    # Scans common JDK install roots for jpackage (JDK 16+ marker),
    # returns the path of the best JDK >= 16, or $null.
    # When PreferredMajor is specified, prefers JDKs matching that major version;
    # falls back to the highest version if no match is found.
    # Populates $script:AllFoundJDKs with every valid JDK found (for -ListJDKs).
    $minMajor = 16
    $bestHome = $null
    $bestVersion = [version]'0.0'
    $preferredHome = $null
    $preferredVersion = [version]'0.0'
    $script:AllFoundJDKs = @()

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
            if ($ver -and $ver.Major -ge $minMajor) {
                # Track all valid JDKs for the listing feature
                $script:AllFoundJDKs += @{ Home = $jdkDir.FullName; Version = $ver }

                if ($ver -gt $bestVersion) {
                    $bestVersion = $ver
                    $bestHome = $jdkDir.FullName
                }
                if ($PreferredMajor -gt 0 -and $ver.Major -eq $PreferredMajor -and $ver -gt $preferredVersion) {
                    $preferredVersion = $ver
                    $preferredHome = $jdkDir.FullName
                }
            }
        }
    }

    # Prefer the requested major version if found; otherwise use the highest.
    if ($preferredHome) { return $preferredHome }
    return $bestHome
}

function Show-AllJDKs {
    param([string]$selectedHome, [int]$preferredMajor = 0)

    if ($script:AllFoundJDKs.Count -eq 0) {
        Write-Host "No JDKs (>= 16) found in the system." -ForegroundColor Yellow
        return
    }

    Write-Host ""
    Write-Host "=== JDK Discovery Report ===" -ForegroundColor Cyan
    Write-Host "Found $($script:AllFoundJDKs.Count) JDK(s) with jpackage (JDK 16+ marker):"
    Write-Host ""
    Write-Host ("{0,-12} {1}" -f 'Version', 'Path')
    Write-Host ("{0,-12} {1}" -f '-------', '----')

    foreach ($jdk in ($script:AllFoundJDKs | Sort-Object -Property Version -Descending)) {
        $marker = if ($jdk.Home -eq $selectedHome) { ' [SELECTED]' } else { '' }
        Write-Host ("{0,-12} {1}{2}" -f $jdk.Version, $jdk.Home, $marker)
    }

    Write-Host ""
    if ($selectedHome) {
        $reason = if ($preferredMajor -gt 0) { "preferred version $preferredMajor" } else { "highest version >= 16" }
        Write-Host "Selected: $selectedHome" -ForegroundColor Green
        Write-Host "Reason: $reason" -ForegroundColor Green
    }
    Write-Host ""
}

function Resolve-JavaHome {
    # --jdk-home: use the exact path provided, bypass auto-detection entirely.
    if ($JdkHome) {
        if (-not (Test-Path $JdkHome)) {
            throw "Specified JDK home does not exist: $JdkHome"
        }
        $jpkgExe = if (Get-IsWindows) { Join-Path $JdkHome 'bin\jpackage.exe' }
                    else { Join-Path $JdkHome 'bin/jpackage' }
        if (-not (Test-Path $jpkgExe)) {
            throw "Specified JDK home does not contain jpackage (JDK 16+ required): $JdkHome"
        }
        $jdkVer = Get-JDKVersion -jdkHome $JdkHome
        if (-not $jdkVer -or $jdkVer.Major -lt 16) {
            throw "Specified JDK is version $jdkVer (JDK 16+ required): $JdkHome"
        }
        Write-Host "Using specified JDK: $JdkHome ($jdkVer)" -ForegroundColor Cyan
        $env:JAVA_HOME = $JdkHome
        return $JdkHome
    }

    $best = Find-BestJDK -PreferredMajor $JdkVersion

    # Show all found JDKs if requested (only meaningful when not using --jdk-home).
    if ($ListJDKs) {
        Show-AllJDKs -selectedHome $best -preferredMajor $JdkVersion
    }

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

function Resolve-RepositoryRoot {
    $currentDirectory = [System.IO.Path]::GetFullPath($PSScriptRoot)
    $wrapperName = if (Get-IsWindows) { 'mvnw.cmd' } else { 'mvnw' }
    while (-not [string]::IsNullOrWhiteSpace($currentDirectory)) {
        $pomPath = Join-Path $currentDirectory 'pom.xml'
        $mavenWrapperPath = Join-Path $currentDirectory $wrapperName
        if ((Test-Path -LiteralPath $pomPath) -and (Test-Path -LiteralPath $mavenWrapperPath)) {
            return $currentDirectory
        }

        $parentDirectory = Split-Path -Path $currentDirectory -Parent
        if ([string]::IsNullOrWhiteSpace($parentDirectory) -or $parentDirectory -eq $currentDirectory) {
            break
        }
        $currentDirectory = $parentDirectory
    }

    $gitCommand = Get-Command git -ErrorAction SilentlyContinue
    if ($gitCommand) {
        $gitRoot = & $gitCommand.Source -C $PSScriptRoot rev-parse --show-toplevel 2>$null
        if ($LASTEXITCODE -eq 0 -and -not [string]::IsNullOrWhiteSpace($gitRoot)) {
            return $gitRoot.Trim()
        }
        $global:LASTEXITCODE = 0
    }

    throw "Unable to locate the Browser4 repository root from $PSScriptRoot. Run the script from inside a Browser4 checkout with pom.xml and the Maven wrapper available."
}

function Resolve-MavenCommand([string]$repositoryRoot) {
    $wrapperName = if (Get-IsWindows) { 'mvnw.cmd' } else { 'mvnw' }
    $wrapperPath = Join-Path $repositoryRoot $wrapperName
    if (Test-Path -LiteralPath $wrapperPath) {
        return (Resolve-Path -LiteralPath $wrapperPath).Path
    }

    $mavenCommand = Get-Command mvn -ErrorAction SilentlyContinue
    if ($mavenCommand) {
        return $mavenCommand.Source
    }

    if ($env:MAVEN_HOME) {
        $mavenHomeExecutable = if (Get-IsWindows) { Join-Path $env:MAVEN_HOME 'bin/mvn.cmd' } else { Join-Path $env:MAVEN_HOME 'bin/mvn' }
        if (Test-Path -LiteralPath $mavenHomeExecutable) {
            return (Resolve-Path -LiteralPath $mavenHomeExecutable).Path
        }
    }

    throw 'Maven is required to build the Browser4 runtime bundle. Install Maven or use a checkout that contains the Maven wrapper.'
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

function Get-JavaMajorVersionFromText([string]$javaVersionText) {
    if ($javaVersionText -match 'version\s+"(\d+)') {
        return [int]$Matches[1]
    }
    if ($javaVersionText -match 'version\s+"[\d._]+-(\d+)') {
        return [int]$Matches[1]
    }
    return $null
}

function Get-JlinkCompressValue([Nullable[int]]$javaMajorVersion) {
    if ($javaMajorVersion -ge 21) {
        return 'zip-9'
    }
    return '2'
}

function Ensure-CleanDirectory([string]$path) {
    if (Test-Path $path) {
        # Use extended-length paths on Windows to bypass MAX_PATH (260 char) limits.
        $longPath = Convert-ToExtendedLengthPath $path
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
        pluginsDirectoryName = 'plugins'
        modules = $modules
        builtAtUtc = [DateTime]::UtcNow.ToString('o')
    }
    return ($metadata | ConvertTo-Json -Depth 6)
}

function Remove-IfExists([string]$path) {
    if (Test-Path $path) {
        $longPath = Convert-ToExtendedLengthPath $path
        Remove-Item -LiteralPath $longPath -Force -Recurse -ErrorAction SilentlyContinue
    }
}

function Remove-SafeRuntimePayload([string]$runtimeRoot) {
    $safeToRemove = @(
        'lib/ct.sym',
        'lib/jvm.lib',
        'bin/jvmcicompiler.dll',
        'bin/libjvmcicompiler.dylib',
        'bin/libjvmcicompiler.so',
        'bin/jar.exe',
        'bin/jarsigner.exe',
        'bin/javac.exe',
        'bin/javadoc.exe',
        'bin/javap.exe',
        'bin/jdb.exe',
        'bin/jdeps.exe',
        'bin/jfr.exe',
        'bin/jimage.exe',
        'bin/jlink.exe',
        'bin/jmod.exe',
        'bin/jpackage.exe',
        'bin/jrunscript.exe',
        'bin/jshell.exe',
        'bin/jstatd.exe',
        'bin/keytool.exe',
        'bin/kinit.exe',
        'bin/klist.exe',
        'bin/ktab.exe',
        'bin/rmiregistry.exe',
        'bin/serialver.exe',
        'bin/jaccessinspector.exe',
        'bin/jaccesswalker.exe'
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
            $manifestLines = [System.Collections.Generic.List[string]]::new()
            $currentLine = $null
            foreach ($rawLine in ($manifestText -split "`r?`n")) {
                if ($rawLine.StartsWith(' ') -and $null -ne $currentLine) {
                    $currentLine += $rawLine.Substring(1)
                    continue
                }

                if ($null -ne $currentLine) {
                    $manifestLines.Add($currentLine)
                }
                $currentLine = $rawLine
            }
            if ($null -ne $currentLine) {
                $manifestLines.Add($currentLine)
            }

            $attributePattern = '^{0}\s*:\s*(.+)$' -f [regex]::Escape($attributeName)
            foreach ($line in $manifestLines) {
                if ($line -match $attributePattern) {
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
PLUGINS_CP="$BUNDLE_DIR/plugins/*"
MAIN_CLASS="{mainClass}"
exec "$RUNTIME" -cp "$LIB_CP:$PLUGINS_CP" "$MAIN_CLASS" "$@"
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
set "PLUGINS_CP=%BUNDLE_DIR%\plugins\*"
set "MAIN_CLASS=$mainClass"
"%RUNTIME%" -cp "%LIB_CP%;%PLUGINS_CP%" %MAIN_CLASS% %*
"@
    Set-Content -LiteralPath $startBatPath -Value $startBatContent -Encoding ASCII
}

# ============================================================================
# Main script
# ============================================================================

$invocationDirectory = if ($PWD -and $PWD.ProviderPath) { $PWD.ProviderPath } else { (Get-Location).Path }
$JarPath = Resolve-InputPath -path $JarPath -baseDirectory $invocationDirectory
$OutputDirectory = Resolve-InputPath -path $OutputDirectory -baseDirectory $invocationDirectory
if (-not [string]::IsNullOrWhiteSpace($JdkHome)) {
    $JdkHome = Resolve-InputPath -path $JdkHome -baseDirectory $invocationDirectory
}

# Resolve Maven command and repo root early — dependency:copy-dependencies needs them.
$repoRoot = Resolve-RepositoryRoot
Set-Location $repoRoot

$mvnCmd = Resolve-MavenCommand -repositoryRoot $repoRoot

# Install Maven modules to ~/.m2 and build the bundle JAR.
# Skip when the caller has already done this (e.g. the Browser4 CLI daemon
# runs `mvn install` before invoking this script and passes -SkipMavenInstall).
if (-not $SkipMavenInstall) {
    Write-Host "Ensuring main modules are installed to ~/.m2 ..."
    $installArgs = @('install', '-Pall-main-modules,asset-bundle', '-DskipTests')
    if (-not $ShowMavenOutput) {
        $installArgs += '-q'
    }
    & $mvnCmd @installArgs
    if ($LASTEXITCODE -ne 0) { throw "Core modules install failed with exit code $LASTEXITCODE" }
}

if (-not (Test-Path $JarPath)) {
    throw "Bundle JAR not found: $JarPath. Build it first: mvn install -P`"all-main-modules,asset-bundle`" -DskipTests -q"
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
$pluginsDirectory = Join-Path $bundleDirectory 'plugins'
$jdepsLogDirectory = Join-Path $workDirectory 'jdeps-logs'
$assetPath = Join-Path $resolvedOutputDirectory $AssetName

if ((Test-Path $assetPath) -and (-not $Force)) {
    throw "Target asset already exists: $assetPath. Re-run with -Force to overwrite it."
}

New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null
Ensure-CleanDirectory $workDirectory
Ensure-CleanDirectory $bundleDirectory
Ensure-CleanDirectory $libDirectory
Ensure-CleanDirectory $pluginsDirectory
Ensure-CleanDirectory $jdepsLogDirectory

# Auto-detect best JDK before resolving jdeps/jlink.
$null = Resolve-JavaHome

$jdeps = Resolve-ToolPath 'jdeps'
$jlink = Resolve-ToolPath 'jlink'
$javaVersionText = Get-JavaVersionText
$isGraalVmRuntime = $javaVersionText -match 'GraalVM'
$selectedJdkVersion = if ($env:JAVA_HOME) { Get-JDKVersion -jdkHome $env:JAVA_HOME } else { $null }

# Parse the JDK major version from java -version output as a fallback for
# --multi-release (primary source is the release file read by Get-JDKVersion).
# On some platforms / JDK distributions the release file may be in an
# unexpected location, so this secondary check is essential.
$javaVersionMajor = if ($selectedJdkVersion) { $selectedJdkVersion.Major } else { Get-JavaMajorVersionFromText -javaVersionText $javaVersionText }
$jlinkCompressValue = Get-JlinkCompressValue -javaMajorVersion $javaVersionMajor

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

# Use the already-resolved Maven wrapper/command from earlier in this script.
$mvnPath = $mvnCmd
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
# Clean up unnecessary JARs from the runtime bundle.
# These removals are a safety net: the POM exclusions should prevent most of
# these from appearing, but runtime-scoped native classifiers and compiler
# JARs from third-party modules can still leak through.
# --------------------------------------------------------------------------

# -- Non-Windows Netty native JARs (keep only windows-x86_64) --
$nonPlatformNativePatterns = @(
    'netty-codec-native-quic-*-linux-*',
    'netty-codec-native-quic-*-osx-*',
    'netty-transport-native-epoll-*-linux-*',
    'netty-resolver-dns-native-macos-*',
    'netty-resolver-dns-classes-macos-*'
)
foreach ($pattern in $nonPlatformNativePatterns) {
    $removed = Get-ChildItem -Path $libDirectory -File -Filter $pattern -ErrorAction SilentlyContinue
    foreach ($jar in $removed) {
        Remove-Item -LiteralPath $jar.FullName -Force
        Write-Host "  Removed non-platform native: $($jar.Name)" -ForegroundColor DarkGray
    }
}

# -- Compiler JARs (no runtime purpose) --
$compilerJars = @(
    'avro-compiler-*.jar',
    'gora-compiler-*.jar'
)
foreach ($pattern in $compilerJars) {
    $removed = Get-ChildItem -Path $libDirectory -File -Filter $pattern -ErrorAction SilentlyContinue
    foreach ($jar in $removed) {
        Remove-Item -LiteralPath $jar.FullName -Force
        Write-Host "  Removed compiler JAR: $($jar.Name)" -ForegroundColor DarkGray
    }
}

# -- Kotlin Gradle plugin JARs (build-tool artifacts, safety net) --
$buildToolPatterns = @(
    'kotlin-gradle-plugin-api-*.jar',
    'kotlin-gradle-plugin-annotations-*.jar',
    'kotlin-build-tools-api-*.jar',
    'kotlin-native-utils-*.jar',
    'kotlin-tooling-core-*.jar',
    'kotlin-util-io-*.jar'
)
foreach ($pattern in $buildToolPatterns) {
    $removed = Get-ChildItem -Path $libDirectory -File -Filter $pattern -ErrorAction SilentlyContinue
    foreach ($jar in $removed) {
        Remove-Item -LiteralPath $jar.FullName -Force
        Write-Host "  Removed build-tool JAR: $($jar.Name)" -ForegroundColor DarkGray
    }
}

# -- Strip orphaned service file from gora-core-shaded (multi-release JAR without Multi-Release: true in manifest).
#    dnsjava's InetAddressResolverProvider class is at META-INF/versions/18/ but the manifest
#    lacks Multi-Release: true, so the ServiceLoader finds the declaration but can't load the
#    class, throwing ServiceConfigurationError (which extends Error, not Exception) at runtime. --
$goraShadedCandidates = Get-ChildItem -Path $libDirectory -File -Filter 'gora-core-shaded-*.jar' -ErrorAction SilentlyContinue
Add-Type -AssemblyName System.IO.Compression.FileSystem
foreach ($jar in $goraShadedCandidates) {
    $serviceEntry = 'META-INF/services/java.net.spi.InetAddressResolverProvider'
    try {
        $source = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
        $hasEntry = $false
        foreach ($e in $source.Entries) {
            if ($e.FullName -eq $serviceEntry) { $hasEntry = $true; break }
        }
        $source.Dispose()
        if (-not $hasEntry) { continue }
        Write-Host "  Stripping $serviceEntry from $($jar.Name)" -ForegroundColor DarkGray
        # Rebuild the JAR without the offending entry
        $tempFile = Join-Path $libDirectory ([System.IO.Path]::GetRandomFileName() + '.jar')
        $source = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
        $dest = [System.IO.Compression.ZipFile]::Open($tempFile, 'Create')
        foreach ($entry in $source.Entries) {
            if ($entry.FullName -eq $serviceEntry) { continue }
            $destEntry = $dest.CreateEntry($entry.FullName)
            if ($entry.Length -gt 0) {
                $s = $entry.Open(); $d = $destEntry.Open()
                try { $s.CopyTo($d) } finally { $d.Dispose(); $s.Dispose() }
            }
        }
        $source.Dispose(); $dest.Dispose()
        Remove-Item -LiteralPath $jar.FullName -Force
        Rename-Item -LiteralPath $tempFile -NewName $jar.Name -Force
    } catch {
        Write-Warning "  Failed to strip service entry from $($jar.Name): $($_.Exception.Message)"
    }
}

# -- POM files (accidentally placed in lib/) --
$pomFiles = Get-ChildItem -Path $libDirectory -File -Filter '*.pom' -ErrorAction SilentlyContinue
foreach ($pom in $pomFiles) {
    Remove-Item -LiteralPath $pom.FullName -Force
    Write-Host "  Removed POM from lib/: $($pom.Name)" -ForegroundColor DarkGray
}

# -- Test framework JARs (safety net; should be excluded by includeScope=runtime) --
$testJarPatterns = @(
    'spring-test-*.jar',
    'spring-boot-test-*.jar',
    'junit-*.jar',
    'mockito-*.jar',
    'mockk-*.jar'
)
foreach ($pattern in $testJarPatterns) {
    $removed = Get-ChildItem -Path $libDirectory -File -Filter $pattern -ErrorAction SilentlyContinue
    foreach ($jar in $removed) {
        Remove-Item -LiteralPath $jar.FullName -Force
        Write-Host "  Removed test JAR: $($jar.Name)" -ForegroundColor DarkGray
    }
}

$libJarCountAfter = (Get-ChildItem -Path $libDirectory -File -Filter '*.jar' | Measure-Object).Count
$removedCount = $libJarCount - $libJarCountAfter
if ($removedCount -gt 0) {
    Write-Host "Cleaned up $removedCount unnecessary JARs ($libJarCountAfter remaining in lib/)" -ForegroundColor Green
}

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
# Priority: 1) selected JDK release file  2) java -version output
#            3) hardcoded '17' as last resort
$detectedMajor = if ($selectedJdkVersion) { $selectedJdkVersion.Major } elseif ($javaVersionMajor) { $javaVersionMajor } else { $null }
$multiReleaseVersion = if ($detectedMajor) { [string]$detectedMajor } else { '17' }
Write-Host "Using multi-release version: $multiReleaseVersion (release-file: $($selectedJdkVersion), java-cmd: $javaVersionMajor)" -ForegroundColor Cyan

# --------------------------------------------------------------------------
# Diagnostic logging helper -- writes timestamped entries to the jdeps log
# directory so we can diagnose slow or failing jdeps invocations in CI.
# --------------------------------------------------------------------------
function Write-JdepsDiagnostic {
    param([string]$Message)
    $timestamp = (Get-Date -Format 'yyyy-MM-dd HH:mm:ss.fff')
    $line = "[$timestamp] $Message"
    Add-Content -LiteralPath (Join-Path $jdepsLogDirectory 'jdeps-diagnostic.log') -Value $line -Encoding UTF8
}

Write-JdepsDiagnostic "jdeps binary: $jdeps"
Write-JdepsDiagnostic "multi-release version: $multiReleaseVersion"
Write-JdepsDiagnostic "app classes dir: $appClassesDir"
Write-JdepsDiagnostic "lib jar count: $libJarCount"
Write-JdepsDiagnostic "JDK version: $(Get-JDKVersion -jdkHome $env:JAVA_HOME)"

# --------------------------------------------------------------------------
# Primary jdeps strategy: full recursive analysis with class-path.
# On some platforms, specific dependency JARs (e.g. native transports)
# may contain class files that jdeps cannot parse.  When that happens we
# fall back to analysing only the application classes.
# --------------------------------------------------------------------------
$jdepsOutput = $null
$jdepsSuccess = $false
$primaryStdoutPath = Join-Path $jdepsLogDirectory 'jdeps-primary-stdout.txt'
$primaryStderrPath = Join-Path $jdepsLogDirectory 'jdeps-primary-stderr.txt'

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

Write-JdepsDiagnostic "Primary jdeps args: $($primaryJdepsArgs -join ' ')"

# Run primary jdeps -- capture stdout and stderr separately so we can save
# both to disk immediately, even on failure.
$prevErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$primaryStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
Write-JdepsDiagnostic "Primary jdeps START"

$primaryExitCode = $null
$primaryStdout = ''
$primaryStderr = ''

# Use a temp file approach so stdout and stderr are captured independently
# even when the process writes > 64 KB (PowerShell's 2>&1 merging can
# interleave or truncate large outputs).
$tempStdout = Join-Path ([System.IO.Path]::GetTempPath()) ([System.IO.Path]::GetRandomFileName())
$tempStderr = Join-Path ([System.IO.Path]::GetTempPath()) ([System.IO.Path]::GetRandomFileName())
try {
    $process = Start-Process -FilePath $jdeps `
        -ArgumentList $primaryJdepsArgs `
        -RedirectStandardOutput $tempStdout `
        -RedirectStandardError $tempStderr `
        -Wait `
        -PassThru `
        -NoNewWindow
    $primaryExitCode = $process.ExitCode
    $primaryStdout = if (Test-Path $tempStdout) { Get-Content -LiteralPath $tempStdout -Raw -ErrorAction SilentlyContinue } else { '' }
    $primaryStderr = if (Test-Path $tempStderr) { Get-Content -LiteralPath $tempStderr -Raw -ErrorAction SilentlyContinue } else { '' }
    $jdepsOutput = $primaryStdout
    $jdepsSuccess = ($primaryExitCode -eq 0)
} catch {
    $primaryExitCode = -1
    $primaryStderr = $_.Exception.Message
    $jdepsSuccess = $false
} finally {
    $primaryStopwatch.Stop()
    Remove-IfExists $tempStdout
    Remove-IfExists $tempStderr
}

# Save stdout and stderr to disk IMMEDIATELY, regardless of success/failure.
if ($primaryStdout) {
    Set-Content -LiteralPath $primaryStdoutPath -Value $primaryStdout -Encoding UTF8
}
if ($primaryStderr) {
    Set-Content -LiteralPath $primaryStderrPath -Value $primaryStderr -Encoding UTF8
}

Write-JdepsDiagnostic "Primary jdeps END -- exit=$primaryExitCode, elapsed=$($primaryStopwatch.Elapsed.TotalSeconds.ToString('F1'))s, success=$jdepsSuccess"
if (-not $jdepsSuccess) {
    Write-JdepsDiagnostic "Primary jdeps STDERR (first 2KB): $($primaryStderr.Substring(0, [Math]::Min(2048, $primaryStderr.Length)))"
}

$ErrorActionPreference = $prevErrorAction
if (-not $jdepsSuccess) { $global:LASTEXITCODE = 0 }

if (-not $jdepsSuccess) {
    Write-Warning "Primary jdeps failed (full recursive analysis with $libJarCount dependency JARs)."
    Write-Warning "jdeps stderr saved to: $primaryStderrPath"

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
    # recursive transitive closure -- it processes every class in every JAR,
    # not just classes reachable from the app entry points.  This is
    # correct and safe: it can only over-approximate, never miss a module.
    # ----------------------------------------------------------------------
    Write-Host "Falling back to per-JAR analysis (individual JAR scanning)..." -ForegroundColor Yellow
    Write-JdepsDiagnostic "Fallback per-JAR analysis START -- $libJarCount JARs to scan"

    $perJarLogPath = Join-Path $jdepsLogDirectory 'jdeps-perjar.log'
    # Truncate the per-JAR log before the fallback run
    Set-Content -LiteralPath $perJarLogPath -Value "# Per-JAR jdeps analysis -- $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')`n" -Encoding UTF8

    $allJarModules = @()
    $goodJars = 0
    $badJars = 0
    $badJarNames = @()
    $perJarStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
    $totalElapsed = 0.0

    $ErrorActionPreference = 'Continue'
    try {
        # Analyse the application JAR first.
        $jarStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        $appModuleText = & $jdeps -q --ignore-missing-deps --multi-release $multiReleaseVersion --print-module-deps $resolvedJarPath 2>&1
        $jarStopwatch.Stop()
        Add-Content -LiteralPath $perJarLogPath -Value "[$($jarStopwatch.Elapsed.TotalSeconds.ToString('F1'))s] APP  $([System.IO.Path]::GetFileName($resolvedJarPath))  exit=$LASTEXITCODE  $appModuleText" -Encoding UTF8
        if ($LASTEXITCODE -eq 0) {
            $allJarModules += ($appModuleText -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
            $goodJars++
        } else {
            Write-Warning "  Even the application JAR failed jdeps: $appModuleText"
            $badJarNames += [System.IO.Path]::GetFileName($resolvedJarPath)
            $badJars++
        }
        $totalElapsed += $jarStopwatch.Elapsed.TotalSeconds
        $global:LASTEXITCODE = 0

        # Analyse every dependency JAR in lib/ -- skip the few that upset jdeps.
        $allJars = @(Get-ChildItem -Path $libDirectory -File -Filter '*.jar' -ErrorAction SilentlyContinue)
        $processedCount = 1
        foreach ($jar in $allJars) {
            $processedCount++
            $jarStopwatch.Restart()
            $jarOutput = & $jdeps -q --ignore-missing-deps --multi-release $multiReleaseVersion --print-module-deps $jar.FullName 2>&1
            $jarStopwatch.Stop()
            $jarSeconds = $jarStopwatch.Elapsed.TotalSeconds

            if ($LASTEXITCODE -eq 0) {
                $allJarModules += ($jarOutput -split ',' | ForEach-Object { $_.Trim() } | Where-Object { $_ })
                Add-Content -LiteralPath $perJarLogPath -Value "[$($jarSeconds.ToString('F1'))s] OK   $($jar.Name)  -> $jarOutput" -Encoding UTF8
                $goodJars++
            } else {
                $shortError = if ($jarOutput.Length -gt 200) { $jarOutput.Substring(0, 200) + '...' } else { $jarOutput }
                Add-Content -LiteralPath $perJarLogPath -Value "[$($jarSeconds.ToString('F1'))s] SKIP $($jar.Name)  exit=$LASTEXITCODE  $shortError" -Encoding UTF8
                Write-Warning "  Skipping problematic JAR: $($jar.Name) ($($jarSeconds.ToString('F1'))s)"
                $badJarNames += $jar.Name
                $badJars++
            }
            $totalElapsed += $jarSeconds
            $global:LASTEXITCODE = 0

            # Progress heartbeat every 50 JARs so CI logs don't look hung.
            if ($processedCount % 50 -eq 0) {
                Write-Host "  Per-JAR progress: $processedCount / $libJarCount ($goodJars ok, $badJars skipped, $([math]::Round($totalElapsed, 1))s elapsed)" -ForegroundColor Cyan
            }
        }

        $perJarStopwatch.Stop()
        Write-JdepsDiagnostic "Per-JAR analysis END -- $goodJars succeeded, $badJars skipped, total=$($perJarStopwatch.Elapsed.TotalSeconds.ToString('F1'))s"
        Write-Host "Per-JAR analysis: $goodJars succeeded, $badJars skipped ($([math]::Round($perJarStopwatch.Elapsed.TotalSeconds, 1))s)." -ForegroundColor Cyan
        if ($badJars -gt 0) {
            Write-Host "  Skipped JARs: $($badJarNames -join ', ')" -ForegroundColor Yellow
            Write-JdepsDiagnostic "Skipped JARs: $($badJarNames -join ', ')"
        }

        if ($allJarModules.Count -gt 0) {
            $jdepsOutput = ($allJarModules | Select-Object -Unique) -join ','
            $jdepsSuccess = $true
        } else {
            Write-Warning "Per-JAR analysis produced no modules (all $libJarCount JARs are problematic)."
        }
    } catch {
        Write-Warning "Per-JAR analysis threw: $($_.Exception.Message)"
        Write-JdepsDiagnostic "Per-JAR analysis EXCEPTION: $($_.Exception.Message)"
    } finally {
        $ErrorActionPreference = $prevErrorAction
        if (-not $jdepsSuccess) { $global:LASTEXITCODE = 0 }
    }
}

if (-not $jdepsSuccess) {
    Write-JdepsDiagnostic "FATAL: jdeps failed with both primary and per-JAR strategies"
    throw "jdeps failed with both primary (full classpath) and per-JAR strategies."
}

# Save the resolved module list immediately.
Set-Content -LiteralPath (Join-Path $jdepsLogDirectory 'jdeps-modules.txt') `
    -Value $jdepsOutput `
    -Encoding UTF8
Write-Host "  jdeps-modules.txt  -> $($jdepsOutput)" -ForegroundColor Green
Write-JdepsDiagnostic "Resolved modules: $jdepsOutput"

# --------------------------------------------------------------------------
# Supplementary jdeps analyses -- best-effort, failures are non-fatal.
# These provide detailed dependency graphs for offline auditing.
# --------------------------------------------------------------------------

# 2) Full verbose class-level dependency graph.
$verboseJdepsArgs = @(
    '--ignore-missing-deps',
    '--multi-release', $multiReleaseVersion,
    '--recursive',
    '-verbose'
)
if ($libJarCount -gt 0) {
    $jdepsClassPath = Get-JdepsClassPath -libDirectory $libDirectory
    if (-not [string]::IsNullOrWhiteSpace($jdepsClassPath)) {
        $verboseJdepsArgs += @('--class-path', $jdepsClassPath)
    }
}
$verboseJdepsArgs += $appClassesDir

$verboseLogPath = Join-Path $jdepsLogDirectory 'jdeps-verbose.log'
$prevErrorAction = $ErrorActionPreference
$ErrorActionPreference = 'Continue'
$verboseStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
Write-JdepsDiagnostic "Verbose jdeps START"
try {
    $verboseOutput = & $jdeps @verboseJdepsArgs 2>&1
    $verboseStopwatch.Stop()
    if ($verboseOutput) {
        Set-Content -LiteralPath $verboseLogPath -Value $verboseOutput -Encoding UTF8
        Write-Host "  jdeps-verbose.log saved ($([math]::Round(((Get-Item $verboseLogPath).Length / 1KB), 1)) KB, $($verboseStopwatch.Elapsed.TotalSeconds.ToString('F1'))s)" -ForegroundColor Green
        Write-JdepsDiagnostic "Verbose jdeps END -- ok, $($verboseStopwatch.Elapsed.TotalSeconds.ToString('F1'))s, $([math]::Round(((Get-Item $verboseLogPath).Length / 1KB), 1)) KB"
    } else {
        Write-JdepsDiagnostic "Verbose jdeps END -- empty output, $($verboseStopwatch.Elapsed.TotalSeconds.ToString('F1'))s"
    }
} catch {
    $verboseStopwatch.Stop()
    Write-Warning "Failed to generate verbose jdeps log: $($_.Exception.Message)"
    Write-JdepsDiagnostic "Verbose jdeps EXCEPTION: $($_.Exception.Message)"
} finally {
    $ErrorActionPreference = $prevErrorAction
    $global:LASTEXITCODE = 0
}

# 3) Package-level summary (medium detail -- useful for auditing).
$packageJdepsArgs = @(
    '--ignore-missing-deps',
    '--multi-release', $multiReleaseVersion,
    '--recursive',
    '-verbose:package'
)
if ($libJarCount -gt 0) {
    $jdepsClassPath = Get-JdepsClassPath -libDirectory $libDirectory
    if (-not [string]::IsNullOrWhiteSpace($jdepsClassPath)) {
        $packageJdepsArgs += @('--class-path', $jdepsClassPath)
    }
}
$packageJdepsArgs += $appClassesDir

$packageLogPath = Join-Path $jdepsLogDirectory 'jdeps-packages.log'
$packageStopwatch = [System.Diagnostics.Stopwatch]::StartNew()
Write-JdepsDiagnostic "Package jdeps START"
try {
    $packageOutput = & $jdeps @packageJdepsArgs 2>&1
    $packageStopwatch.Stop()
    if ($packageOutput) {
        Set-Content -LiteralPath $packageLogPath -Value $packageOutput -Encoding UTF8
        Write-Host "  jdeps-packages.log saved ($([math]::Round(((Get-Item $packageLogPath).Length / 1KB), 1)) KB, $($packageStopwatch.Elapsed.TotalSeconds.ToString('F1'))s)" -ForegroundColor Green
        Write-JdepsDiagnostic "Package jdeps END -- ok, $($packageStopwatch.Elapsed.TotalSeconds.ToString('F1'))s, $([math]::Round(((Get-Item $packageLogPath).Length / 1KB), 1)) KB"
    } else {
        Write-JdepsDiagnostic "Package jdeps END -- empty output, $($packageStopwatch.Elapsed.TotalSeconds.ToString('F1'))s"
    }
} catch {
    $packageStopwatch.Stop()
    Write-Warning "Failed to generate package-level jdeps log: $($_.Exception.Message)"
    Write-JdepsDiagnostic "Package jdeps EXCEPTION: $($_.Exception.Message)"
} finally {
    $ErrorActionPreference = $prevErrorAction
    $global:LASTEXITCODE = 0
}

Write-JdepsDiagnostic "All jdeps phases complete"

$recommendedModules = @(
    'java.management',
    'jdk.crypto.ec'
)
# On Linux and macOS the per-JAR jdeps fallback can miss modules that are
# only reachable through reflective class-initialisation chains (e.g.
# javax.naming.NamingException via logback → slf4j → commons-logging →
# SpringApplication.<clinit>).  Add them explicitly so a jlink image built
# from the fallback path can still boot.
if (-not (Get-IsWindows)) {
    $recommendedModules += @(
        'java.naming',
        'java.security.jgss'
    )
}
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
Write-Host "Using jlink compression mode: $jlinkCompressValue" -ForegroundColor Cyan
$jlinkArgs = @(
    '--add-modules', ($modules -join ','),
    '--vm', 'server',
    '--strip-debug',
    '--strip-java-debug-attributes',
    '--no-header-files',
    '--no-man-pages',
    '--dedup-legal-notices', 'error-if-not-same-content',
    '--compress', $jlinkCompressValue,
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
