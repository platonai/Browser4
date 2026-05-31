param(
    [string]$JarPath = (Join-Path $PSScriptRoot "target/Browser4Bundle.jar"),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "target/runtime-bundle"),
    [string]$AssetName,
    [string]$MainClass = '',
    [switch]$Force
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

    throw "Required tool '$toolName' was not found. Ensure JDK 17+ is installed and JAVA_HOME is configured."
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
        Remove-Item -Recurse -Force $path
    }
    New-Item -ItemType Directory -Force -Path $path | Out-Null
}

function Get-PathSeparator {
    if (Get-IsWindows) { return ';' }
    return ':'
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
        Remove-Item -LiteralPath $path -Force -Recurse
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

if (-not (Test-Path $JarPath)) {
    throw "Browser4 bundle jar not found: $JarPath"
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

$jdeps = Resolve-ToolPath 'jdeps'
$jlink = Resolve-ToolPath 'jlink'
$javaVersionText = Get-JavaVersionText
$isGraalVmRuntime = $javaVersionText -match 'GraalVM'

# --------------------------------------------------------------------------
# Collect dependencies via Maven
# --------------------------------------------------------------------------
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
$appJarFileName = [System.IO.Path]::GetFileName($resolvedJarPath)
Copy-Item -LiteralPath $resolvedJarPath -Destination (Join-Path $libDirectory $appJarFileName) -Force

$libJarCount = (Get-ChildItem -Path $libDirectory -File -Filter '*.jar' | Measure-Object).Count
Write-Host "Collected $libJarCount jars in lib/" -ForegroundColor Green

# --------------------------------------------------------------------------
# Compute required JRE modules with jdeps
# --------------------------------------------------------------------------
Write-Host "Running jdeps to compute Browser4 runtime modules..." -ForegroundColor Cyan

# Create a temp directory for extracted app classes (jdeps works best with classes)
$appClassesDir = Join-Path $workDirectory 'app-classes'
Ensure-CleanDirectory $appClassesDir
Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($resolvedJarPath, $appClassesDir)

$jdepsArgs = @(
    '-q',
    '--ignore-missing-deps',
    '--multi-release', '17',
    '--recursive',
    '--print-module-deps'
)
if ($libJarCount -gt 0) {
    $jdepsArgs += @('--class-path', (Join-Path $libDirectory '*'))
}
$jdepsArgs += $appClassesDir

$jdepsOutput = & $jdeps @jdepsArgs
if ($LASTEXITCODE -ne 0) {
    throw "jdeps failed with exit code $LASTEXITCODE"
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
Write-Host "Writing launch scripts..." -ForegroundColor Cyan
Write-LaunchScripts -bundleDirectory $bundleDirectory -mainClass $MainClass

Set-Content -LiteralPath (Join-Path $bundleDirectory 'runtime-bundle.json') `
    -Value (Get-BundleMetadataJson -assetName $AssetName -modules $modules -mainClass $MainClass) `
    -Encoding UTF8

# --------------------------------------------------------------------------
# Package the bundle archive
# --------------------------------------------------------------------------
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

$assetSize = [math]::Round(((Get-Item $assetPath).Length / 1MB), 2)
Write-Host "Browser4 bundle runtime bundle created: $assetPath ($assetSize MB)" -ForegroundColor Green
