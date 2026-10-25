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
  plugins/           - plugin JARs (auto-discovered via classpath wildcard): the
                       first-party plugins this checkout builds (see
                       $defaultBundledPlugins) plus every JAR a previous bundle
                       build or `browser4-cli plugin install` left behind — a
                       rebuild preserves those instead of deleting them.
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
    [switch]$SkipCli = $false,
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

function Reset-CleanDirectory([string]$path, [string]$label = $path, [bool]$Recreate = $true) {
    # Rename-then-delete reset.  On Windows, renaming a directory that holds
    # open/locked files (e.g. a backend daemon auto-started from this bundle)
    # fails atomically — nothing is deleted, so a failed reset never corrupts
    # a previously working bundle.  A plain Remove-Item -Recurse can delete
    # half the tree before hitting a locked file (e.g. deleting jvm.cfg while
    # java.exe stays locked), which is exactly how a re-run used to leave a
    # broken runtime behind ("java.exe: could not open jvm.cfg").
    if (Test-Path $path) {
        $trash = Join-Path (Split-Path -Parent $path) ("." + (Split-Path -Leaf $path) + ".old-" + [Guid]::NewGuid().ToString('N').Substring(0, 8))
        try {
            Rename-Item -LiteralPath $path -NewName (Split-Path -Leaf $trash) -ErrorAction Stop
        } catch {
            throw "Cannot reset $label ('$path'): the directory is locked / in use. If a backend daemon is running from this runtime bundle, stop it first, then re-run the build."
        }
        Remove-Item -LiteralPath $trash -Recurse -Force -ErrorAction SilentlyContinue
    }
    # Most callers need the directory back (empty) for the next phase, but
    # jlink refuses to write into an output directory that exists at all —
    # even an empty one — so the jlink pre-reset passes -Recreate:$false.
    if ($Recreate) {
        New-Item -ItemType Directory -Force -Path $path | Out-Null
    }
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
    [string]$mainClass,
    [string[]]$plugins = @(),
    [string]$pluginVersion = '',
    [string[]]$pluginWarnings = @(),
    [string[]]$availablePlugins = @(),
    [string[]]$preservedPlugins = @()
) {
    $metadata = [ordered]@{
        assetName = $assetName
        mainClass = $mainClass
        runtimeDirectoryName = 'runtime'
        libDirectoryName = 'lib'
        pluginsDirectoryName = 'plugins'
        modules = $modules
        # Bundled plugin JARs and any reason the plugin could not be bundled.
        # Recorded here because the CLI prints a successful build's stdout only
        # on failure — the manifest is what makes a plugin-less bundle auditable
        # instead of silent (every /api/swarm/** call would answer 503).
        plugins = $plugins
        pluginVersion = $pluginVersion
        pluginWarnings = $pluginWarnings
        # First-party plugins the checkout builds but this bundle does not ship
        # (`browser4-cli plugin install <jar>` makes them available), and JARs a
        # previous install left in plugins/ that this rebuild carried over.
        availablePlugins = $availablePlugins
        preservedPlugins = $preservedPlugins
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

<#
.SYNOPSIS
    Newest timestamp among the browser4-cli sources that end up in the binary.

.DESCRIPTION
    Mirrors the staleness check `b4w.ps1` uses for the debug binary: the CLI's
    Rust sources plus Cargo.toml/Cargo.lock.  Returns [datetime]::MinValue when
    the directory has no sources (nothing to compare against).

.PARAMETER CliSourceDir
    The `cli/browser4-cli` directory.
#>
function Get-Browser4CliSourceTimestamp {
    param(
        [Parameter(Mandatory)][string]$CliSourceDir
    )

    $newest = [datetime]::MinValue
    $candidates = @()

    $srcDir = Join-Path $CliSourceDir 'src'
    if (Test-Path -LiteralPath $srcDir) {
        $candidates += @(Get-ChildItem -Path $srcDir -Recurse -File -Filter '*.rs' -ErrorAction SilentlyContinue)
    }
    foreach ($name in @('Cargo.toml', 'Cargo.lock')) {
        $path = Join-Path $CliSourceDir $name
        if (Test-Path -LiteralPath $path) {
            $candidates += @(Get-Item -LiteralPath $path)
        }
    }

    foreach ($item in $candidates) {
        if ($item.LastWriteTimeUtc -gt $newest) { $newest = $item.LastWriteTimeUtc }
    }
    return $newest
}

<#
.SYNOPSIS
    True when the browser4-cli binary must be (re)built from source.

.DESCRIPTION
    A bundle that only rebuilt when the binary was MISSING copied whatever stale
    release build sat in `target/release` — observed as a 4.14.0-rc.6 CLI shipped
    inside a 4.14.4 bundle, which then panicked on `attach --extension msedge`
    and sent a bare `--extension` to Chrome.  Comparing timestamps keeps the
    bundled CLI aligned with the sources it was built from.

    A missing binary always needs a build; an unknown source timestamp never
    forces one (nothing to compare, build only when the binary is absent).

.PARAMETER BinaryPath
    The candidate binary (usually `target/release/browser4-cli[.exe]`).

.PARAMETER SourceTimestamp
    Result of [Get-Browser4CliSourceTimestamp].
#>
function Test-Browser4CliBinaryNeedsRebuild {
    param(
        [Parameter(Mandatory)][string]$BinaryPath,
        [Parameter(Mandatory)][datetime]$SourceTimestamp
    )

    if (-not (Test-Path -LiteralPath $BinaryPath -PathType Leaf)) { return $true }
    if ($SourceTimestamp -le [datetime]::MinValue) { return $false }

    $binaryTime = (Get-Item -LiteralPath $BinaryPath).LastWriteTimeUtc
    return ($binaryTime -lt $SourceTimestamp)
}

<#
.SYNOPSIS
Bundle the browser4-cli binary into the runtime bundle's bin/ directory.

.DESCRIPTION
Uses a prebuilt release binary when it is NEWER than the CLI sources, otherwise
builds one with cargo (best effort).  When neither is possible the bundle is
still produced — the CliBinaryResolver falls back to PATH / auto-install.  This
guarantees the SKILL.md tool surface is available on a fresh machine that only
has the bundle (design §4.2/§4.3).
#>
function Install-Browser4Cli([string]$bundleDirectory) {
    $binDirectory = Join-Path $bundleDirectory 'bin'
    $cliExeName = if (Get-IsWindows) { 'browser4-cli.exe' } else { 'browser4-cli' }
    $cliSourceDir = Join-Path $PSScriptRoot (Join-Path '..' (Join-Path '..' (Join-Path 'cli' 'browser4-cli')))
    $releaseBinary = Join-Path $cliSourceDir (Join-Path 'target' (Join-Path 'release' $cliExeName))

    $sourceTimestamp = Get-Browser4CliSourceTimestamp -CliSourceDir $cliSourceDir
    $needsBuild = Test-Browser4CliBinaryNeedsRebuild -BinaryPath $releaseBinary -SourceTimestamp $sourceTimestamp

    if ($needsBuild) {
        $cargo = Get-Command cargo -ErrorAction SilentlyContinue
        if (-not $cargo) {
            if (Test-Path -LiteralPath $releaseBinary -PathType Leaf) {
                Write-Warning ("browser4-cli sources are newer than the release binary but cargo is not " +
                    "available; the bundle ships the STALE $cliExeName. Run 'cargo build --release' in " +
                    "cli/browser4-cli, or pass -SkipCli.")
            } else {
                Write-Warning "No browser4-cli release binary and no cargo found; the bundle will not ship a CLI binary."
            }
            return
        }
        if (Test-Path -LiteralPath $releaseBinary -PathType Leaf) {
            Write-Host "browser4-cli sources changed since the release binary was built; rebuilding..." -ForegroundColor Cyan
        } else {
            Write-Host "Building browser4-cli release binary (first bundle build may take a while)..." -ForegroundColor Cyan
        }
        Push-Location $cliSourceDir
        try {
            & $cargo.Source build --release
        } finally {
            Pop-Location
        }
        if ($LASTEXITCODE -ne 0 -or -not (Test-Path -LiteralPath $releaseBinary -PathType Leaf)) {
            Write-Warning "cargo build for browser4-cli failed; the bundle will not ship a CLI binary."
            return
        }
    }

    Copy-Item -LiteralPath $releaseBinary -Destination (Join-Path $binDirectory $cliExeName) -Force
    Write-Host "Bundled browser4-cli -> bin/$cliExeName" -ForegroundColor Green
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
    # GraalVM (JDK 25) defaults UseJVMCICompiler=true, which breaks the Kotlin
    # compile/kapt daemon's RMI handshake ("Failed connecting to the daemon in
    # 4 retries"). Fall back to in-process compilation in that case.
    $mavenJdkIsGraalVm = (Get-JavaVersionText) -match 'GraalVM'
    # -Dmaven.jar.forceCreation=true is load-bearing, not belt-and-braces: the jar
    # plugin skips repackaging when it believes nothing changed, and it compares
    # the output jar against the *classes directory* mtime, which does not move
    # when only the contents of existing class files change.  Without this, an
    # incremental `mvn install` can leave a module jar older than the sources it
    # was compiled from — exactly the staleness dev mode refuses to serve.
    $installArgs = @(
        'install', '-Pall-main-modules,asset-bundle', '-DskipTests',
        '-Dmaven.jar.forceCreation=true'
    )
    if ($mavenJdkIsGraalVm) {
        Write-Host "GraalVM detected - disabling the Kotlin compiler daemon (in-process compilation)." -ForegroundColor Yellow
        $installArgs += '-Dkotlin.compiler.daemon=false'
    }
    if (-not $ShowMavenOutput) {
        $installArgs += '-q'
    }
    # Clean the code modules first: `install` alone keeps orphaned .class files
    # in target/classes, so a class deleted or moved between builds is still
    # packaged and can shadow a plugin bean at startup.  The clean runs without
    # the bundle profiles so the bundle's own work directory — and the jlink
    # image inside it — is preserved.
    & $mvnCmd clean -q -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "Core modules clean failed with exit code $LASTEXITCODE" }
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
# Preserve the plugin JARs a previous `browser4-cli plugin install` (or an
# earlier build) put into the bundle's plugins/ directory.  The resets below
# delete that directory as part of the build tree — which used to make an
# installed plugin disappear silently on the next rebuild, with nothing in the
# output tying the loss to the build.  The snapshot lives outside the reset
# scope and is restored after this build's own plugins are in place.
$pluginSnapshotDirectory = Join-Path $resolvedOutputDirectory '.plugin-snapshot'
Reset-CleanDirectory $pluginSnapshotDirectory 'plugin snapshot directory'
$preservedPluginJars = @()
if (Test-Path -LiteralPath $pluginsDirectory -PathType Container) {
    $preservedPluginJars = @(Get-ChildItem -LiteralPath $pluginsDirectory -File -Filter '*.jar' -ErrorAction SilentlyContinue)
    foreach ($preservedJar in $preservedPluginJars) {
        Copy-Item -LiteralPath $preservedJar.FullName -Destination (Join-Path $pluginSnapshotDirectory $preservedJar.Name) -Force
    }
}
if ($preservedPluginJars.Count -gt 0) {
    Write-Host "Preserving $($preservedPluginJars.Count) installed plugin JAR(s) across this rebuild." -ForegroundColor DarkCyan
}

# Reset (not just ensure) the working directories so a re-run over an existing
# build always starts from a clean slate — jlink refuses to write into a
# non-empty output directory.  Reset-CleanDirectory renames before deleting so
# a bundle that is locked by a running daemon fails fast instead of being
# half-deleted (see Reset-CleanDirectory).
Reset-CleanDirectory $workDirectory 'working directory'
Reset-CleanDirectory $bundleDirectory 'bundle directory'
Reset-CleanDirectory $libDirectory 'lib directory'
Reset-CleanDirectory $pluginsDirectory 'plugins directory'
Reset-CleanDirectory $jdepsLogDirectory 'jdeps log directory'

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
# Bundle the first-party plugins this checkout builds.
#
# The swarm plugin is mandatory (the swarm REST facade is a thin shell that
# delegates to it; without it /api/swarm/** returns 503), but the other
# in-repo plugins matter just as much for a source checkout: their tools are
# documented as part of the CLI surface, yet a bundle that ships none of them
# makes `plugin <domain> <method>` fail with an unknown-tool error while the
# built JAR sits in the Maven local repository — with nothing in the output
# naming the plugin or the install command.
#
# Membership rules for $defaultBundledPlugins — keep them explicit, a wrong
# entry is a bundle that shadows host classes or fails to boot:
#   * the module builds a thin JAR (host classes come from lib/*), and
#   * every non-`browser4-*` dependency in the module POM exists in lib/*, and
#   * the plugin constructs no bean that needs an external service at startup
#     (first-party plugins are @Lazy + @ConditionalOnProperty(matchIfMissing =
#     true), so an unconfigured plugin fails only when its tool is called).
#
# Two failures made this step ship a bundle with no (or a version-skewed)
# plugin, and both were invisible in the CLI's rebuild output:
#   1. the local repository was assumed to be ~/.m2/repository although a
#      settings.xml that declares <localRepository> moves every artifact
#      Maven installs (common on Windows, where a redirected D: repository
#      keeps C: free) — the scan then found nothing and copied nothing;
#   2. the newest JAR by mtime won, so a plugin from another release train
#      could be bundled into this backend.
# The plugin outcome is therefore recorded in the bundle metadata and reported
# at the end of the build (the CLI prints the build's own stdout/stderr only
# when the build fails, so the metadata is what other tools can read).
#
# JARs that `browser4-cli plugin install` (or a previous build) put into
# plugins/ are snapshotted before the clean-slate reset and carried over
# afterwards, so an installed plugin survives a rebuild instead of silently
# disappearing with the build-output tree.  A carried-over JAR never shadows a
# plugin this build bundles: same-plugin duplicates are dropped (newest wins)
# and the decision is reported.
# --------------------------------------------------------------------------

function Get-CheckedOutProjectVersion([string]$repositoryRoot) {
    <#
    .SYNOPSIS
        Read the project version of the checkout (used to match the plugin JAR).
    .DESCRIPTION
        Mirrors the CLI's dev-mode staleness check: the <version> that belongs
        to the root artifact (<artifactId>browser4</artifactId>), not the parent
        POM version declared above it.  Falls back to the VERSION file.
    #>
    $pomPath = [System.IO.Path]::Combine($repositoryRoot, 'pom.xml')
    if (Test-Path -LiteralPath $pomPath) {
        $pom = Get-Content -LiteralPath $pomPath -Raw
        $marker = '<artifactId>browser4</artifactId>'
        $markerIndex = $pom.IndexOf($marker, [System.StringComparison]::Ordinal)
        if ($markerIndex -ge 0) {
            $rest = $pom.Substring($markerIndex + $marker.Length)
            $head = if ($rest.Length -gt 512) { $rest.Substring(0, 512) } else { $rest }
            if ($head -match '<version>\s*([^<]+?)\s*</version>') {
                return $Matches[1].Trim()
            }
        }
    }

    $versionPath = [System.IO.Path]::Combine($repositoryRoot, 'VERSION')
    if (Test-Path -LiteralPath $versionPath) {
        return ([string](Get-Content -LiteralPath $versionPath -TotalCount 1)).Trim()
    }

    return ''
}

function Resolve-MavenLocalRepository([string]$mavenCommand) {
    <#
    .SYNOPSIS
        Resolve Maven's effective local repository, never assuming ~/.m2.
    .DESCRIPTION
        Order: MAVEN_REPO_LOCAL override > Maven's own answer
        (`mvn -q help:evaluate -Dexpression=settings.localRepository
        -DforceStdout`) > a settings.xml scan (user settings, then the global
        settings of MAVEN_HOME / M2_HOME) > ~/.m2/repository.

        The settings.xml scan is a regex over the raw file so it also works
        under PowerShell's ConstrainedLanguage mode and does not care about
        namespaces or a BOM.
    #>
    if ($env:MAVEN_REPO_LOCAL) { return $env:MAVEN_REPO_LOCAL }

    if ($mavenCommand) {
        try {
            # -q keeps Maven quiet; -DforceStdout prints the value alone on
            # stdout.  A failure (offline, missing help plugin) leaves nothing
            # usable, so the settings.xml scan below still runs.
            $evaluated = @(& $mavenCommand -q 'help:evaluate' '-Dexpression=settings.localRepository' '-DforceStdout' 2>$null)
            for ($i = $evaluated.Count - 1; $i -ge 0; $i--) {
                $candidate = ([string]$evaluated[$i]).Trim().Trim('"')
                if ($candidate -and ($candidate -match '^[A-Za-z]:[\\/]' -or $candidate -match '^/')) {
                    return $candidate
                }
            }
        } catch {
            # Fall through to the settings.xml scan.
        }
    }

    $homeDir = if ($env:HOME) { $env:HOME } elseif ($env:USERPROFILE) { $env:USERPROFILE } else { '' }

    $settingsCandidates = @()
    if ($env:MAVEN_USER_HOME) { $settingsCandidates += [System.IO.Path]::Combine($env:MAVEN_USER_HOME, 'settings.xml') }
    if ($homeDir) { $settingsCandidates += [System.IO.Path]::Combine($homeDir, '.m2', 'settings.xml') }
    foreach ($mavenHome in @($env:MAVEN_HOME, $env:M2_HOME)) {
        if ($mavenHome) { $settingsCandidates += [System.IO.Path]::Combine($mavenHome, 'conf', 'settings.xml') }
    }
    foreach ($settingsPath in $settingsCandidates) {
        if (-not (Test-Path -LiteralPath $settingsPath -PathType Leaf)) { continue }
        try {
            $raw = Get-Content -LiteralPath $settingsPath -Raw -ErrorAction Stop
        } catch {
            continue
        }
        if ($raw -match '(?s)<localRepository>\s*([^<]+?)\s*</localRepository>') {
            $declared = $Matches[1].Trim()
            if ($declared.StartsWith('~') -and $homeDir) {
                $declared = [System.IO.Path]::Combine($homeDir, $declared.Substring(1).TrimStart('/', '\'))
            }
            if ($declared) { return $declared }
        }
    }

    if ($homeDir) { return [System.IO.Path]::Combine($homeDir, '.m2', 'repository') }
    return ''
}

function Get-PluginJarIdentity([string]$jarName) {
    <#
    .SYNOPSIS
        Split a plugin JAR file name into its plugin name and version.
    .DESCRIPTION
        Follows the Maven artifact convention '<plugin>-<version>[-shaded].jar':
        'browser4-images-4.14.1-SNAPSHOT.jar' yields Plugin 'browser4-images',
        Version '4.14.1-SNAPSHOT'.  A name without a parseable version returns
        $null, and so does the 'original-<artifact>-<version>.jar' intermediate
        maven-shade-plugin leaves next to a shaded artifact — that file is not a
        deployable plugin.
    .OUTPUTS
        PSCustomObject with Plugin/Version, or $null when the name does not
        follow the convention (e.g. a third-party JAR without a version).
    #>
    if ($jarName -like 'original-*') { return $null }
    if ($jarName -match '^(?<plugin>.+?)-(?<version>\d[^/]*?)(?:-shaded)?\.jar$') {
        return [pscustomobject]@{ Plugin = $Matches['plugin']; Version = $Matches['version'] }
    }
    return $null
}

function Get-PluginVersionFromJarName([string]$jarName) {
    <#
    .SYNOPSIS
        Extract the version from '<plugin>-<version>[-shaded].jar'.
    .OUTPUTS
        String — the parsed version, or '' when the name does not match.
    #>
    $identity = Get-PluginJarIdentity -jarName $jarName
    if ($identity) { return $identity.Version }
    return ''
}

function Get-PluginBaseNameFromJarName([string]$jarName) {
    <#
    .SYNOPSIS
        Extract the plugin (artifact) name from '<plugin>-<version>.jar'.
    .OUTPUTS
        String — the parsed plugin name, or '' when the name does not match.
    #>
    $identity = Get-PluginJarIdentity -jarName $jarName
    if ($identity) { return $identity.Plugin }
    return ''
}

function Select-BundlePluginJar {
    <#
    .SYNOPSIS
        Pick the plugin JAR that matches the checked-out project version.
    .DESCRIPTION
        Version first: a JAR from another release train must never be bundled,
        because it looks installed while behaving unpredictably.  Only
        same-version candidates (e.g. several snapshot installs) are ranked by
        mtime.
    .OUTPUTS
        PSCustomObject: Jar (the selected file, or $null) and FoundVersions
        (every version parsed from the candidates, for the warning message).
    #>
    param(
        [object[]]$Candidates = @(),
        [string]$ProjectVersion = ''
    )

    $foundVersions = [System.Collections.Generic.List[string]]::new()
    $matching = [System.Collections.Generic.List[object]]::new()

    foreach ($candidate in $Candidates) {
        $version = Get-PluginVersionFromJarName -jarName $candidate.Name
        if ($version) { $foundVersions.Add($version) }
        if ($version -and $ProjectVersion -and $version.Equals($ProjectVersion, [System.StringComparison]::OrdinalIgnoreCase)) {
            $matching.Add($candidate)
        }
    }

    $selected = $null
    if ($matching.Count -gt 0) {
        $selected = $matching | Sort-Object { $_.LastWriteTime } -Descending | Select-Object -First 1
    }

    return [pscustomobject]@{
        Jar = $selected
        FoundVersions = @($foundVersions | Sort-Object -Unique)
    }
}

function Select-PreservedPluginJars {
    <#
    .SYNOPSIS
        Decide which JARs from a pre-build plugins/ snapshot survive a rebuild.
    .DESCRIPTION
        A rebuild resets the bundle directory, which deletes plugins/ — the very
        directory `browser4-cli plugin install` writes to.  Every JAR found there
        before the reset is a candidate for restoration, with two rules:

          * a JAR belonging to a plugin this build bundles is dropped (the fresh
            copy wins; loading two versions of one plugin is not supported);
          * when the same plugin appears more than once, the newest JAR wins.

    .OUTPUTS
        PSCustomObject: Restore (the JARs to copy back) and Dropped (one
        human-readable line per JAR that is not restored, for the build report).
    #>
    param(
        [object[]]$PreservedJars = @(),
        [string[]]$BundledPluginNames = @()
    )

    $restore = [System.Collections.Generic.List[object]]::new()
    $dropped = [System.Collections.Generic.List[string]]::new()
    $byPlugin = @{}

    foreach ($jar in $PreservedJars) {
        $bundlingPlugin = $null
        foreach ($pluginName in $BundledPluginNames) {
            if ($jar.Name -like "$pluginName-*") { $bundlingPlugin = $pluginName; break }
        }
        if ($bundlingPlugin) {
            $dropped.Add("$($jar.Name): dropped — this build bundles $bundlingPlugin itself, and the bundled JAR wins")
            continue
        }

        $identity = Get-PluginJarIdentity -jarName $jar.Name
        # A name without a version cannot be grouped; keep it under its own key.
        $key = if ($identity) { $identity.Plugin } else { $jar.Name }
        if (-not $byPlugin.ContainsKey($key)) {
            $byPlugin[$key] = [System.Collections.Generic.List[object]]::new()
        }
        $byPlugin[$key].Add($jar)
    }

    foreach ($key in ($byPlugin.Keys | Sort-Object)) {
        $group = @($byPlugin[$key] | Sort-Object { $_.LastWriteTime } -Descending)
        $restore.Add($group[0])
        for ($i = 1; $i -lt $group.Count; $i++) {
            $dropped.Add("$($group[$i].Name): dropped — $key is installed more than once; the newest JAR ($($group[0].Name)) is kept")
        }
    }

    return [pscustomobject]@{
        Restore = @($restore)
        Dropped = @($dropped)
    }
}

function Resolve-SourceTreePluginJar {
    <#
    .SYNOPSIS
        Find the JAR a plugin module built in this checkout, for the install hint.
    .DESCRIPTION
        Prefers 'browser4-plugins/<module>/target/<module>-<version>.jar' and
        falls back to the newest non-source JAR of that module, so the reported
        `plugin install` command works without the caller guessing a version.
    .OUTPUTS
        String — the absolute JAR path, or '' when the module has not been built.
    #>
    param(
        [string]$RepositoryRoot,
        [string]$ModuleName,
        [string]$ProjectVersion = ''
    )

    if (-not $RepositoryRoot -or -not $ModuleName) { return '' }
    $targetDirectory = [System.IO.Path]::Combine($RepositoryRoot, 'browser4-plugins', $ModuleName, 'target')
    if (-not (Test-Path -LiteralPath $targetDirectory -PathType Container)) { return '' }

    if ($ProjectVersion) {
        $preferred = Join-Path $targetDirectory "$ModuleName-$ProjectVersion.jar"
        if (Test-Path -LiteralPath $preferred -PathType Leaf) { return $preferred }
    }

    $candidate = Get-ChildItem -LiteralPath $targetDirectory -File -Filter "$ModuleName-*.jar" -ErrorAction SilentlyContinue |
        Where-Object { $_.Name -notmatch '(sources|javadoc|original-)' } |
        Sort-Object LastWriteTime -Descending |
        Select-Object -First 1
    if ($candidate) { return $candidate.FullName }
    return ''
}

$projectVersion = Get-CheckedOutProjectVersion -repositoryRoot $repoRoot
$localRepo = Resolve-MavenLocalRepository -mavenCommand $mvnCmd

# First-party plugin modules this bundle ships.  swarm is not optional (its REST
# facade is a thin shell that answers 503 without it); the rest are the in-repo
# plugins whose tools the CLI documents as usable from a source checkout.
# Membership rules and the exclusion rationale live in the section comment above.
$defaultBundledPlugins = @(
    'browser4-swarm',
    'browser4-images',
    'browser4-markdown',
    'browser4-media',
    'browser4-seo'
)

# In-repo plugins deliberately NOT bundled: each needs something the bundle does
# not provide (an external service, a shaded dependency tree, a missing library)
# or is a demo.  They stay installable with `browser4-cli plugin install <jar>`,
# and the build names them — with the exact command — so their absence is
# discoverable instead of looking like a capability that never existed.
$optionalPlugins = @(
    @{ Module = 'browser4-captcha'; Reason = 'needs an external CAPTCHA-solving service' },
    @{ Module = 'browser4-pptx'; Reason = 'shaded JAR (embeds Apache POI); install it on demand' },
    @{ Module = 'browser4-profile-import'; Reason = 'needs dd-plist, which the bundle does not ship' },
    @{ Module = 'browser4-summary-demo'; Reason = 'demo module' }
)

$pluginBundleWarnings = [System.Collections.Generic.List[string]]::new()
$bundledPluginNames = [System.Collections.Generic.List[string]]::new()
$bundledPluginBaseNames = [System.Collections.Generic.List[string]]::new()
$availablePlugins = [System.Collections.Generic.List[string]]::new()
$bundledPluginVersion = ''

Write-Host "Maven local repository: $localRepo" -ForegroundColor DarkGray
if ($projectVersion) {
    Write-Host "Checked-out project version: $projectVersion" -ForegroundColor DarkGray
}

foreach ($pluginModule in $defaultBundledPlugins) {
    $pluginGroupDir = [System.IO.Path]::Combine($localRepo, 'ai', 'platon', 'pulsar', $pluginModule)
    $pluginInstallHint = "Build and install the matching plugin, then rebuild the bundle:`n" +
        "    mvn install -pl browser4-plugins/$pluginModule -DskipTests`n" +
        "    (or run this script again with -SkipMavenInstall after installing it)"

    # Thin JARs only: a '-shaded' artifact embeds its own dependencies and would
    # shadow the host classes the bundle already provides on the classpath.
    $pluginCandidates = @(
        Get-ChildItem -Path $pluginGroupDir -Recurse -File -Filter "$pluginModule-*.jar" -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch 'sources|javadoc|original-|-shaded\.jar$' }
    )

    if ($pluginCandidates.Count -eq 0) {
        $pluginBundleWarnings.Add(
            "browser4-plugins/$pluginModule is part of this checkout but has no JAR in the Maven local " +
            "repository ($pluginGroupDir), so it is NOT bundled and its tools are missing.`n" +
            $pluginInstallHint)
        continue
    }

    $selection = Select-BundlePluginJar -Candidates $pluginCandidates -ProjectVersion $projectVersion
    if ($selection.Jar) {
        Copy-Item -LiteralPath $selection.Jar.FullName -Destination (Join-Path $pluginsDirectory $selection.Jar.Name) -Force
        $bundledPluginNames.Add($selection.Jar.Name)
        $bundledPluginBaseNames.Add($pluginModule)
        if (-not $bundledPluginVersion) { $bundledPluginVersion = $projectVersion }
        Write-Host "  + Plugin: $($selection.Jar.Name)" -ForegroundColor Green
        continue
    }

    $foundVersions = if ($selection.FoundVersions.Count -gt 0) {
        $selection.FoundVersions -join ', '
    } else {
        '(unparseable JAR names)'
    }
    $pluginBundleWarnings.Add(
        "Found $pluginModule JAR(s) in $pluginGroupDir, but none for the checked-out project version " +
        "'$projectVersion' (found: $foundVersions). Refusing to bundle a plugin from another release " +
        "train — a version-skewed plugin looks installed while behaving unpredictably.`n" +
        $pluginInstallHint)
}

# Record the in-repo plugins this bundle does not ship, with the exact command
# that makes each one available.
foreach ($optional in $optionalPlugins) {
    $optionalJar = Resolve-SourceTreePluginJar -RepositoryRoot $repoRoot -ModuleName $optional.Module -ProjectVersion $projectVersion
    $entry = "$($optional.Module) — $($optional.Reason); "
    if ($optionalJar) {
        $entry += "install: browser4-cli plugin install `"$optionalJar`""
    } else {
        $entry += "build first: mvn install -pl browser4-plugins/$($optional.Module) -DskipTests"
    }
    $availablePlugins.Add($entry)
}

# Carry the preserved JARs back into the fresh plugins/ directory.  A JAR that
# belongs to a plugin this build bundles is dropped (the bundled copy wins: two
# versions of one plugin must not end up on the classpath), and so is an older
# duplicate of a plugin installed more than once.
$preservedPluginNames = [System.Collections.Generic.List[string]]::new()
if ($preservedPluginJars.Count -gt 0) {
    $preservedSelection = Select-PreservedPluginJars -PreservedJars $preservedPluginJars `
        -BundledPluginNames $bundledPluginBaseNames.ToArray()
    foreach ($preservedJar in $preservedSelection.Restore) {
        $snapshotJar = Join-Path $pluginSnapshotDirectory $preservedJar.Name
        if (-not (Test-Path -LiteralPath $snapshotJar -PathType Leaf)) { continue }
        Copy-Item -LiteralPath $snapshotJar -Destination (Join-Path $pluginsDirectory $preservedJar.Name) -Force
        $preservedPluginNames.Add($preservedJar.Name)
    }
    foreach ($dropNote in $preservedSelection.Dropped) {
        $pluginBundleWarnings.Add("plugin carried over from the previous bundle: $dropNote")
    }
    if ($preservedPluginNames.Count -gt 0) {
        Write-Host "  + Preserved: $($preservedPluginNames -join ', ')" -ForegroundColor DarkCyan
    }
}
Reset-CleanDirectory $pluginSnapshotDirectory 'plugin snapshot directory' -Recreate:$false

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

# Record that the jars under lib/ were assembled from the checked-out sources at
# this moment.  The dev-mode launcher compares source timestamps against the jar
# timestamps, but this project builds reproducibly, so Maven leaves a jar whose
# recompiled content is byte-identical with its original timestamp — a jar mtime
# alone can never prove the bundle is current.  Only written when Maven ran (see
# -SkipMavenInstall), because otherwise lib/ may hold jars installed by an
# earlier build.
if (-not $SkipMavenInstall) {
    $stampPath = Join-Path $libDirectory '.browser4-bundle-build-stamp'
    try {
        Set-Content -Path $stampPath -Value 'rebuilt from the checked-out sources' -Encoding ascii -NoNewline
    } catch {
        Write-Warning "Could not write the bundle build stamp at ${stampPath}: $_"
    }
}

# --------------------------------------------------------------------------
# Compute required JRE modules with jdeps
# --------------------------------------------------------------------------
$phaseIndex = 3
Write-BuildProgress -Status $buildPhases[$phaseIndex - 1].Label

Write-Host "Running jdeps to compute Browser4 runtime modules..." -ForegroundColor Cyan


# Create a temp directory for extracted app classes (jdeps works best with classes)
$appClassesDir = Join-Path $workDirectory 'app-classes'
Reset-CleanDirectory $appClassesDir 'app-classes directory'
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

# jlink refuses to write into an output directory that exists — even an
# empty one — so remove the runtime directory entirely (rename-then-delete
# for lock safety) WITHOUT recreating it; jlink creates it itself.  This
# makes the script idempotent over an existing build: a plain re-run after
# a source change must not fail with 'directory already exists' or leave a
# broken runtime behind.
Reset-CleanDirectory $runtimeDirectory 'runtime (jlink output) directory' -Recreate:$false

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
if (-not $SkipCli) {
    Install-Browser4Cli -bundleDirectory $bundleDirectory
} else {
    Write-Host "Skipping browser4-cli bundling (-SkipCli)." -ForegroundColor DarkGray
}

Set-Content -LiteralPath (Join-Path $bundleDirectory 'runtime-bundle.json') `
    -Value (Get-BundleMetadataJson -assetName $AssetName -modules $modules -mainClass $MainClass `
        -plugins $bundledPluginNames.ToArray() -pluginVersion $bundledPluginVersion `
        -pluginWarnings $pluginBundleWarnings.ToArray() `
        -availablePlugins $availablePlugins.ToArray() -preservedPlugins $preservedPluginNames.ToArray()) `
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

# --------------------------------------------------------------------------
# Plugin report — repeated here, at the very end, because this is the part of
# the output a human actually reads; a Write-Warning in the middle of a long
# build scrolls away, and the CLI only echoes a *failing* build's output.
# --------------------------------------------------------------------------
if ($bundledPluginNames.Count -gt 0) {
    Write-Host "Bundled plugins: $($bundledPluginNames -join ', ')" -ForegroundColor Green
} else {
    Write-Host ''
    Write-Host '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!' -ForegroundColor Yellow
    Write-Host ' WARNING: this bundle ships NO plugins.' -ForegroundColor Yellow
    Write-Host ' Every /api/swarm/** endpoint will answer 503 "Swarm backend is not installed".' -ForegroundColor Yellow
    Write-Host ' The condition is also recorded in the bundle manifest (pluginWarnings).' -ForegroundColor Yellow
    Write-Host '!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!' -ForegroundColor Yellow
}

if ($preservedPluginNames.Count -gt 0) {
    Write-Host "Preserved installed plugin(s): $($preservedPluginNames -join ', ')" -ForegroundColor DarkCyan
}

if ($pluginBundleWarnings.Count -gt 0) {
    Write-Host ''
    Write-Host 'Plugin warnings:' -ForegroundColor Yellow
    foreach ($warning in $pluginBundleWarnings) {
        Write-Host "   - $warning" -ForegroundColor Yellow
    }
    Write-Host ' They are also recorded in the bundle manifest (pluginWarnings).' -ForegroundColor DarkYellow
}

if ($availablePlugins.Count -gt 0) {
    Write-Host ''
    Write-Host 'In-repo plugins NOT bundled by default (install on demand):' -ForegroundColor DarkCyan
    foreach ($entry in $availablePlugins) {
        Write-Host "   - $entry" -ForegroundColor DarkCyan
    }
}
