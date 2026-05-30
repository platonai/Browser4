param(
    [string]$JarPath = (Join-Path $PSScriptRoot "target/Browser4.jar"),
    [string]$OutputDirectory = (Join-Path $PSScriptRoot "target/runtime-bundle"),
    [string]$AssetName,
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
        return 'browser4-runtime-windows-x64.zip'
    }
    if (Get-IsLinux) {
        if ($arch -ne [System.Runtime.InteropServices.Architecture]::X64) {
            throw "Linux runtime bundles currently support only X64. Current architecture: $arch"
        }
        return 'browser4-runtime-linux-x64.tar.gz'
    }
    if (Get-IsMacOS) {
        if ($arch -eq [System.Runtime.InteropServices.Architecture]::Arm64) {
            return 'browser4-runtime-darwin-arm64.tar.gz'
        }
        if ($arch -eq [System.Runtime.InteropServices.Architecture]::X64) {
            return 'browser4-runtime-darwin-x64.tar.gz'
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
    [string]$jarFileName
) {
    $metadata = [ordered]@{
        assetName = $assetName
        jarFileName = $jarFileName
        jreDirectoryName = 'jre'
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

if (-not (Test-Path $JarPath)) {
    throw "Browser4 jar not found: $JarPath"
}

$resolvedJarPath = (Resolve-Path $JarPath).Path
if ([string]::IsNullOrWhiteSpace($AssetName)) {
    $AssetName = Get-AssetNameForCurrentPlatform
}

$bundleBaseName = Remove-ArchiveSuffix $AssetName
$resolvedOutputDirectory = [System.IO.Path]::GetFullPath($OutputDirectory)
$workDirectory = Join-Path $resolvedOutputDirectory (Join-Path '_work' $bundleBaseName)
$extractDirectory = Join-Path $workDirectory 'jar-extracted'
$bundleDirectory = Join-Path $workDirectory $bundleBaseName
$jreDirectory = Join-Path $bundleDirectory 'jre'
$assetPath = Join-Path $resolvedOutputDirectory $AssetName

if ((Test-Path $assetPath) -and (-not $Force)) {
    throw "Target asset already exists: $assetPath. Re-run with -Force to overwrite it."
}

New-Item -ItemType Directory -Force -Path $resolvedOutputDirectory | Out-Null
Ensure-CleanDirectory $workDirectory
Ensure-CleanDirectory $extractDirectory
Ensure-CleanDirectory $bundleDirectory

$jdeps = Resolve-ToolPath 'jdeps'
$jlink = Resolve-ToolPath 'jlink'
$javaVersionText = Get-JavaVersionText
$isGraalVmRuntime = $javaVersionText -match 'GraalVM'

Add-Type -AssemblyName System.IO.Compression.FileSystem
[System.IO.Compression.ZipFile]::ExtractToDirectory($resolvedJarPath, $extractDirectory)

$classesDirectory = Join-Path $extractDirectory 'BOOT-INF/classes'
$libDirectory = Join-Path $extractDirectory 'BOOT-INF/lib'
if (-not (Test-Path $classesDirectory)) {
    throw "Extracted jar does not contain BOOT-INF/classes: $classesDirectory"
}
if (-not (Test-Path $libDirectory)) {
    throw "Extracted jar does not contain BOOT-INF/lib: $libDirectory"
}

$jdepsArgs = @(
    '-q',
    '--ignore-missing-deps',
    '--multi-release', '17',
    '--recursive',
    '--print-module-deps'
)
if ((Get-ChildItem -Path $libDirectory -File -Filter '*.jar' | Measure-Object).Count -gt 0) {
    $jdepsArgs += @('--class-path', (Join-Path $libDirectory '*'))
}
$jdepsArgs += $classesDirectory

Write-Host "Running jdeps to compute Browser4 runtime modules..." -ForegroundColor Cyan
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
    '--output', $jreDirectory
)
if ($isGraalVmRuntime) {
    $jlinkArgs = @('--add-options', '-XX:+UnlockExperimentalVMOptions -XX:-UseJVMCICompiler') + $jlinkArgs
}
& $jlink @jlinkArgs
if ($LASTEXITCODE -ne 0) {
    throw "jlink failed with exit code $LASTEXITCODE"
}

Remove-SafeRuntimePayload $jreDirectory

Copy-Item -LiteralPath $resolvedJarPath -Destination (Join-Path $bundleDirectory 'Browser4.jar') -Force
Set-Content -LiteralPath (Join-Path $bundleDirectory 'runtime-bundle.json') -Value (Get-BundleMetadataJson -assetName $AssetName -modules $modules -jarFileName 'Browser4.jar') -Encoding UTF8

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

$javaExecutableName = if (Get-IsWindows) { 'java.exe' } else { 'java' }
$javaPath = Join-Path $jreDirectory (Join-Path 'bin' $javaExecutableName)
if (-not (Test-Path $javaPath)) {
    throw "Generated runtime image is missing java launcher: $javaPath"
}

$assetSize = [math]::Round(((Get-Item $assetPath).Length / 1MB), 2)
Write-Host "Browser4 runtime bundle created: $assetPath ($assetSize MB)" -ForegroundColor Green

