param(
    [Parameter(Mandatory = $false)]
    [string]$BundlePath = '',

    [string]$ExpectedAssetName,

    # Functional test parameters
    [string]$HealthPath = '/actuator/health',
    [int]$StartupTimeoutSeconds = 180,
    [switch]$SkipFunctionalTest
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ============================================================================
# Utility functions
# ============================================================================

function Remove-IfExists([string]$Path) {
    if (Test-Path -LiteralPath $Path) {
        Remove-Item -LiteralPath $Path -Recurse -Force
    }
}

function Get-IsWindows {
    return [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Windows
    )
}

function Get-JavaExecutableName {
    if (Get-IsWindows) { return 'java.exe' }
    return 'java'
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

function Get-OSArchitecture {
    return [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture
}

function Get-DefaultAssetName {
    if (Get-IsWindows) {
        return 'browser4-bundle-runtime-windows-x64.zip'
    }
    if (Get-IsLinux) {
        return 'browser4-bundle-runtime-linux-x64.tar.gz'
    }
    if (Get-IsMacOS) {
        $arch = Get-OSArchitecture
        if ($arch -eq [System.Runtime.InteropServices.Architecture]::Arm64) {
            return 'browser4-bundle-runtime-darwin-arm64.tar.gz'
        }
        return 'browser4-bundle-runtime-darwin-x64.tar.gz'
    }
    throw "Unsupported OS for Browser4 runtime bundle verification."
}

function Resolve-DefaultBundlePath {
    $bundleDir = Join-Path $PSScriptRoot 'target' 'runtime-bundle'
    if (-not (Test-Path -LiteralPath $bundleDir -PathType Container)) {
        throw @(
            "Default bundle directory not found: $bundleDir",
            "Build the runtime bundle first with build-runtime-bundle.ps1, or specify -BundlePath explicitly."
        ) -join [Environment]::NewLine
    }

    $assetName = Get-DefaultAssetName
    $candidatePath = Join-Path $bundleDir $assetName
    if (Test-Path -LiteralPath $candidatePath -PathType Leaf) {
        return $candidatePath, $assetName
    }

    # Fall back: search for any .tar.gz or .zip in the runtime-bundle directory
    $archives = @(Get-ChildItem -LiteralPath $bundleDir -File |
        Where-Object { $_.Name.EndsWith('.tar.gz', [System.StringComparison]::OrdinalIgnoreCase) -or $_.Name.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase) })
    if ($archives.Count -eq 0) {
        throw "No runtime bundle archive (.tar.gz or .zip) found in $bundleDir. Build the runtime bundle first, or specify -BundlePath explicitly."
    }
    if ($archives.Count -eq 1) {
        Write-Host "  Auto-detected bundle: $($archives[0].Name)" -ForegroundColor DarkGray
        return $archives[0].FullName, $archives[0].Name
    }

    # Multiple archives: prefer the platform-specific one
    foreach ($archive in $archives) {
        if ($archive.Name -eq $assetName) {
            Write-Host "  Auto-detected bundle: $assetName" -ForegroundColor DarkGray
            return $archive.FullName, $assetName
        }
    }

    throw "Multiple runtime bundle archives found in $bundleDir but none match the expected name '$assetName'. Specify -BundlePath explicitly."
}

function Get-ArchiveKind([string]$Path) {
    if ($Path.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase)) { return 'zip' }
    if ($Path.EndsWith('.tar.gz', [System.StringComparison]::OrdinalIgnoreCase)) { return 'tar.gz' }
    throw "Unsupported runtime bundle archive format: $Path"
}

function Expand-RuntimeBundleArchive([string]$ArchivePath, [string]$DestinationDirectory) {
    $archiveKind = Get-ArchiveKind $ArchivePath
    if ($archiveKind -eq 'zip') {
        Expand-Archive -LiteralPath $ArchivePath -DestinationPath $DestinationDirectory -Force
        return
    }

    $tarCommand = Get-Command tar -ErrorAction SilentlyContinue
    if (-not $tarCommand) {
        throw 'tar is required to extract .tar.gz runtime bundles.'
    }

    & $tarCommand.Source -xzf $ArchivePath -C $DestinationDirectory
    if ($LASTEXITCODE -ne 0) {
        throw "tar extraction failed with exit code $LASTEXITCODE"
    }
}

function Test-IsRuntimeBundleRoot([string]$Path) {
    $libPath = Join-Path $Path 'lib'
    $javaPath = Join-Path $Path (Join-Path 'runtime' (Join-Path 'bin' (Get-JavaExecutableName)))
    $metadataPath = Join-Path $Path 'runtime-bundle.json'
    return (Test-Path -LiteralPath $libPath -PathType Container) `
        -and (Test-Path -LiteralPath $javaPath -PathType Leaf) `
        -and (Test-Path -LiteralPath $metadataPath -PathType Leaf)
}

function Resolve-RuntimeBundleRoot([string]$ExtractedDirectory) {
    if (Test-IsRuntimeBundleRoot $ExtractedDirectory) {
        return (Resolve-Path -LiteralPath $ExtractedDirectory).Path
    }

    $directories = Get-ChildItem -LiteralPath $ExtractedDirectory -Directory -ErrorAction Stop
    foreach ($directory in $directories) {
        if (Test-IsRuntimeBundleRoot $directory.FullName) {
            return $directory.FullName
        }
    }

    throw "Runtime bundle does not contain lib/, runtime/bin/java, and runtime-bundle.json under $ExtractedDirectory"
}

function Get-FreeTcpPort {
    $listener = [System.Net.Sockets.TcpListener]::new([System.Net.IPAddress]::Loopback, 0)
    try {
        $listener.Start()
        return ([System.Net.IPEndPoint]$listener.LocalEndpoint).Port
    }
    finally {
        $listener.Stop()
    }
}

function Convert-ContentToText($Content) {
    if ($null -eq $Content) { return '' }
    if ($Content -is [string]) { return $Content }
    if ($Content -is [byte[]]) { return [System.Text.Encoding]::UTF8.GetString($Content) }
    return ($Content | ConvertTo-Json -Depth 10 -Compress)
}

function Wait-ForHealthyEndpoint(
    [string]$Url,
    [int]$TimeoutSeconds,
    [System.Diagnostics.Process]$Process,
    [string]$StdOutPath,
    [string]$StdErrPath
) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    $attempt = 0
    do {
        $attempt++
        if ($Process.HasExited) {
            $stdout = if (Test-Path -LiteralPath $StdOutPath) { Get-Content -LiteralPath $StdOutPath -Raw } else { '' }
            $stderr = if (Test-Path -LiteralPath $StdErrPath) { Get-Content -LiteralPath $StdErrPath -Raw } else { '' }
            throw @(
                "Bundled Browser4 process exited before becoming healthy. Exit code: $($Process.ExitCode)",
                '--- stdout ---',
                $stdout,
                '--- stderr ---',
                $stderr
            ) -join [Environment]::NewLine
        }

        try {
            $response = Invoke-WebRequest -Uri $Url -UseBasicParsing -TimeoutSec 5
            if ($response.StatusCode -ge 200 -and $response.StatusCode -lt 400) {
                Write-Host "  Health check succeeded (attempt $attempt, status $($response.StatusCode))" -ForegroundColor Green
                return (Convert-ContentToText $response.Content)
            }
        }
        catch {
            if ($attempt -eq 1) {
                Write-Host "  Waiting for application to become healthy..." -ForegroundColor DarkGray
            }
            Start-Sleep -Seconds 2
            continue
        }

        Start-Sleep -Seconds 2
    }
    while ((Get-Date) -lt $deadline)

    $stdoutTimeout = if (Test-Path -LiteralPath $StdOutPath) { Get-Content -LiteralPath $StdOutPath -Raw } else { '' }
    $stderrTimeout = if (Test-Path -LiteralPath $StdErrPath) { Get-Content -LiteralPath $StdErrPath -Raw } else { '' }
    throw @(
        "Timed out waiting for Browser4 health endpoint: $Url",
        '--- stdout ---',
        $stdoutTimeout,
        '--- stderr ---',
        $stderrTimeout
    ) -join [Environment]::NewLine
}

function Stop-ProcessQuietly([System.Diagnostics.Process]$Process) {
    if (-not $Process) { return }

    try {
        if (-not $Process.HasExited) {
            Stop-Process -Id $Process.Id -Force -ErrorAction SilentlyContinue
            $Process.WaitForExit(10000) | Out-Null
        }
    }
    catch {
        Write-Warning "Failed to stop process $($Process.Id): $($_.Exception.Message)"
    }
}

function Write-VerificationStatus([string]$Label, [bool]$Passed, [string]$Detail = '') {
    $status = if ($Passed) { 'PASS' } else { 'FAIL' }
    $color = if ($Passed) { 'Green' } else { 'Red' }
    $line = "  [$status] $Label"
    if ($Detail) {
        $line += " ($Detail)"
    }
    Write-Host $line -ForegroundColor $color
    return $Passed
}

# ============================================================================
# Validation functions
# ============================================================================

function Find-MainClassInJars([string]$LibDirectory, [string]$MainClass) {
    # Convert class name to path: ai.platon.pulsar.apps.Browser4BundleApplicationKt -> ai/platon/pulsar/apps/Browser4BundleApplicationKt.class
    $classEntryPath = ($MainClass -replace '\.', '/') + '.class'

    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $jars = Get-ChildItem -Path $LibDirectory -File -Filter '*.jar' | Sort-Object Name
    foreach ($jar in $jars) {
        $archive = $null
        try {
            $archive = [System.IO.Compression.ZipFile]::OpenRead($jar.FullName)
            $entry = $archive.GetEntry($classEntryPath)
            if ($entry) {
                return $jar.Name
            }
        }
        catch {
            # Skip jars that can't be read (corrupted, invalid zip, etc.)
        }
        finally {
            if ($archive) {
                $archive.Dispose()
            }
        }
    }
    return $null
}

function Validate-BundleStructure([string]$BundleRoot) {
    Write-Host "`n=== Bundle Structure Validation ===" -ForegroundColor Cyan
    $allPassed = $true

    # Required directory: runtime/
    $runtimeDir = Join-Path $BundleRoot 'runtime'
    $runtimeExists = Test-Path -LiteralPath $runtimeDir -PathType Container
    if (-not (Write-VerificationStatus 'runtime/ directory' $runtimeExists)) { $allPassed = $false }

    # Required file: runtime/bin/java
    $javaPath = Join-Path $runtimeDir (Join-Path 'bin' (Get-JavaExecutableName))
    $javaExists = Test-Path -LiteralPath $javaPath -PathType Leaf
    if (-not (Write-VerificationStatus 'runtime/bin/java launcher' $javaExists)) { $allPassed = $false }

    # Required directory: lib/
    $libDir = Join-Path $BundleRoot 'lib'
    $libExists = Test-Path -LiteralPath $libDir -PathType Container
    if (-not (Write-VerificationStatus 'lib/ directory' $libExists)) { $allPassed = $false }

    # Required: jars in lib/
    if ($libExists) {
        $jars = @(Get-ChildItem -Path $libDir -File -Filter '*.jar')
        $hasJars = $jars.Count -gt 0
        if (-not (Write-VerificationStatus 'lib/ contains jar files' $hasJars "$($jars.Count) jars")) { $allPassed = $false }
    }

    # Required directory: bin/
    $binDir = Join-Path $BundleRoot 'bin'
    $binExists = Test-Path -LiteralPath $binDir -PathType Container
    if (-not (Write-VerificationStatus 'bin/ directory' $binExists)) { $allPassed = $false }

    # Required file: bin/start.sh
    $startShPath = Join-Path $binDir 'start.sh'
    $startShExists = Test-Path -LiteralPath $startShPath -PathType Leaf
    if (-not (Write-VerificationStatus 'bin/start.sh' $startShExists)) { $allPassed = $false }

    # Required file: bin/start.bat
    $startBatPath = Join-Path $binDir 'start.bat'
    $startBatExists = Test-Path -LiteralPath $startBatPath -PathType Leaf
    if (-not (Write-VerificationStatus 'bin/start.bat' $startBatExists)) { $allPassed = $false }

    # Required file: runtime-bundle.json
    $metadataPath = Join-Path $BundleRoot 'runtime-bundle.json'
    $metadataExists = Test-Path -LiteralPath $metadataPath -PathType Leaf
    if (-not (Write-VerificationStatus 'runtime-bundle.json' $metadataExists)) { $allPassed = $false }

    return $allPassed, $libDir, $javaPath, $metadataPath, $startShPath, $startBatPath
}

function Validate-Metadata([string]$MetadataPath, [string]$ExpectedAsset) {
    Write-Host "`n=== Metadata Validation ===" -ForegroundColor Cyan
    $allPassed = $true

    $metadata = Get-Content -LiteralPath $MetadataPath -Raw | ConvertFrom-Json

    # assetName
    $assetMatch = $metadata.assetName -eq $ExpectedAsset
    if (-not (Write-VerificationStatus 'assetName' $assetMatch "'$($metadata.assetName)'")) { $allPassed = $false }

    # mainClass
    $hasMainClass = -not [string]::IsNullOrWhiteSpace($metadata.mainClass)
    if (-not (Write-VerificationStatus 'mainClass' $hasMainClass "'$($metadata.mainClass)'")) { $allPassed = $false }

    # runtimeDirectoryName
    $runtimeDirOk = $metadata.runtimeDirectoryName -eq 'runtime'
    if (-not (Write-VerificationStatus 'runtimeDirectoryName = runtime' $runtimeDirOk "'$($metadata.runtimeDirectoryName)'")) { $allPassed = $false }

    # libDirectoryName
    $libDirOk = $metadata.libDirectoryName -eq 'lib'
    if (-not (Write-VerificationStatus 'libDirectoryName = lib' $libDirOk "'$($metadata.libDirectoryName)'")) { $allPassed = $false }

    # modules
    $hasModules = $metadata.modules -and $metadata.modules.Count -gt 0
    if (-not (Write-VerificationStatus 'modules' $hasModules "$($metadata.modules.Count) modules")) { $allPassed = $false }

    # builtAtUtc
    $hasTimestamp = -not [string]::IsNullOrWhiteSpace($metadata.builtAtUtc)
    if (-not (Write-VerificationStatus 'builtAtUtc' $hasTimestamp "$($metadata.builtAtUtc)")) { $allPassed = $false }

    return $allPassed, $metadata
}

function Validate-MainClassPresence([string]$LibDir, [string]$MainClass) {
    Write-Host "`n=== Main Class Validation ===" -ForegroundColor Cyan
    $allPassed = $true

    Write-Host "  Searching for class: $MainClass" -ForegroundColor DarkGray
    $containingJar = Find-MainClassInJars -LibDirectory $LibDir -MainClass $MainClass

    $found = $null -ne $containingJar
    if (-not (Write-VerificationStatus 'Main class found in lib/' $found $(if ($found) { $containingJar } else { 'not found' }))) {
        $allPassed = $false
        # List jars for debugging
        Write-Host "  Searched jars:" -ForegroundColor DarkYellow
        Get-ChildItem -Path $LibDir -File -Filter '*.jar' | ForEach-Object {
            Write-Host "    - $($_.Name)" -ForegroundColor DarkGray
        }
    }

    return $allPassed, $containingJar
}

function Validate-StartScripts([string]$StartShPath, [string]$StartBatPath, [string]$MainClass) {
    Write-Host "`n=== Start Script Validation ===" -ForegroundColor Cyan
    $allPassed = $true

    # Validate start.sh
    if (Test-Path -LiteralPath $StartShPath -PathType Leaf) {
        $shContent = Get-Content -LiteralPath $StartShPath -Raw
        $shHasMainClass = $shContent -match [regex]::Escape($MainClass)
        if (-not (Write-VerificationStatus 'start.sh contains correct main class' $shHasMainClass)) { $allPassed = $false }

        $shHasRuntime = $shContent -match 'runtime/bin/java'
        if (-not (Write-VerificationStatus 'start.sh references runtime/bin/java' $shHasRuntime)) { $allPassed = $false }

        $shHasLibCp = $shContent -match 'lib/\*'
        if (-not (Write-VerificationStatus 'start.sh uses lib/* classpath' $shHasLibCp)) { $allPassed = $false }

        $shHasShebang = ($shContent -match '^#!/bin/bash') -or ($shContent -match '^#!/bin/sh')
        if (-not (Write-VerificationStatus 'start.sh has shebang' $shHasShebang)) { $allPassed = $false }
    }

    # Validate start.bat
    if (Test-Path -LiteralPath $StartBatPath -PathType Leaf) {
        $batContent = Get-Content -LiteralPath $StartBatPath -Raw
        $batHasMainClass = $batContent -match [regex]::Escape($MainClass)
        if (-not (Write-VerificationStatus 'start.bat contains correct main class' $batHasMainClass)) { $allPassed = $false }

        $batHasRuntime = $batContent -match 'runtime\\bin\\java'
        if (-not (Write-VerificationStatus 'start.bat references runtime\bin\java' $batHasRuntime)) { $allPassed = $false }

        $batHasLibCp = $batContent -match 'lib\\\*'
        if (-not (Write-VerificationStatus 'start.bat uses lib\* classpath' $batHasLibCp)) { $allPassed = $false }
    }

    return $allPassed
}

function Test-BundledJavaRuntime([string]$JavaPath) {
    Write-Host "`n=== Bundled JRE Validation ===" -ForegroundColor Cyan
    Write-Host "  Running: $JavaPath -version" -ForegroundColor DarkGray

    $tempRoot = [System.IO.Path]::GetTempPath()
    $stdoutPath = Join-Path $tempRoot ([System.IO.Path]::GetRandomFileName())
    $stderrPath = Join-Path $tempRoot ([System.IO.Path]::GetRandomFileName())
    try {
        $process = Start-Process -FilePath $JavaPath `
            -ArgumentList @('-version') `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -Wait -PassThru

        $stdout = if (Test-Path $stdoutPath) { Get-Content $stdoutPath -Raw } else { '' }
        $stderr = if (Test-Path $stderrPath) { Get-Content $stderrPath -Raw } else { '' }
        $versionOutput = ($stdout + $stderr).Trim()

        if ($process.ExitCode -ne 0) {
            Write-VerificationStatus 'java -version' $false "exit code $($process.ExitCode)"
            Write-Host "  Output: $versionOutput" -ForegroundColor Red
            return $false, ''
        }

        Write-VerificationStatus 'java -version' $true ''
        # Show the first meaningful line of version output
        $firstLine = ($versionOutput -split "`n")[0].Trim()
        Write-Host "  Version: $firstLine" -ForegroundColor DarkGray
        return $true, $versionOutput
    }
    finally {
        Remove-IfExists $stdoutPath
        Remove-IfExists $stderrPath
    }
}

# ============================================================================
# Functional test
# ============================================================================

function Invoke-FunctionalTest(
    [string]$BundleRoot,
    [string]$JavaPath,
    [string]$MainClass,
    [string]$HealthPath,
    [int]$StartupTimeoutSeconds
) {
    Write-Host "`n=== Functional Test ===" -ForegroundColor Cyan

    $port = Get-FreeTcpPort
    $healthUrl = "http://127.0.0.1:$port$HealthPath"
    Write-Host "  Port: $port" -ForegroundColor DarkGray
    Write-Host "  Health URL: $healthUrl" -ForegroundColor DarkGray

    $classpathArg = if (Get-IsWindows) { "lib\*" } else { "lib/*" }
    Write-Host "  Command: $JavaPath -cp $classpathArg $MainClass --server.port=$port" -ForegroundColor DarkGray

    $tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("b4-func-test-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
    New-Item -ItemType Directory -Force -Path $tempRoot | Out-Null
    $stdoutPath = Join-Path $tempRoot 'stdout.log'
    $stderrPath = Join-Path $tempRoot 'stderr.log'
    $process = $null

    try {
        Write-Host "  Starting Browser4 from runtime bundle..." -ForegroundColor DarkGray
        $process = Start-Process -FilePath $JavaPath `
            -ArgumentList @(
                '-cp',
                $classpathArg,
                $MainClass,
                '--server.address=127.0.0.1',
                "--server.port=$port",
                '--browser.display.mode=HEADLESS'
            ) `
            -WorkingDirectory $BundleRoot `
            -RedirectStandardOutput $stdoutPath `
            -RedirectStandardError $stderrPath `
            -PassThru

        $healthContent = Wait-ForHealthyEndpoint `
            -Url $healthUrl `
            -TimeoutSeconds $StartupTimeoutSeconds `
            -Process $process `
            -StdOutPath $stdoutPath `
            -StdErrPath $stderrPath

        if ($healthContent -notmatch 'UP') {
            Write-VerificationStatus 'Health endpoint reports UP' $false "Response: $healthContent"
            return $false
        }

        Write-VerificationStatus 'Health endpoint reports UP' $true ''
        Write-VerificationStatus 'Application starts successfully' $true ''
        return $true
    }
    finally {
        Stop-ProcessQuietly -Process $process
        # Keep logs for a moment in case of failure
        if (Test-Path $stdoutPath) {
            $stdoutSize = (Get-Item $stdoutPath).Length
            if ($stdoutSize -gt 0) {
                Write-Host "  stdout log: $stdoutPath ($([math]::Round($stdoutSize/1KB, 1)) KB)" -ForegroundColor DarkGray
            }
        }
        if (Test-Path $stderrPath) {
            $stderrSize = (Get-Item $stderrPath).Length
            if ($stderrSize -gt 0) {
                Write-Host "  stderr log: $stderrPath ($([math]::Round($stderrSize/1KB, 1)) KB)" -ForegroundColor DarkGray
            }
        }
        # Clean up only on success
        # Remove-IfExists $tempRoot
    }
}

# ============================================================================
# Main
# ============================================================================

if ([string]::IsNullOrWhiteSpace($BundlePath)) {
    $resolvedBundlePath, $autoAssetName = Resolve-DefaultBundlePath
} else {
    $resolvedBundlePath = (Resolve-Path -LiteralPath $BundlePath).Path
    $autoAssetName = $null
}

$expectedAsset = if ([string]::IsNullOrWhiteSpace($ExpectedAssetName)) {
    if ($autoAssetName) { $autoAssetName } else { [System.IO.Path]::GetFileName($resolvedBundlePath) }
} else {
    $ExpectedAssetName
}

Write-Host "`nBrowser4 Bundle Verification" -ForegroundColor Magenta
Write-Host "============================" -ForegroundColor Magenta
Write-Host "  Bundle: $resolvedBundlePath" -ForegroundColor DarkGray
Write-Host "  Expected asset: $expectedAsset" -ForegroundColor DarkGray

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("browser4-runtime-verify-" + [System.Guid]::NewGuid().ToString('N'))
$extractDirectory = Join-Path $tempRoot 'extract'

try {
    # ----------------------------------------------------------------------
    # Phase 1: Extract
    # ----------------------------------------------------------------------
    Write-Host "`n--- Phase 1: Extract ---" -ForegroundColor Cyan
    New-Item -ItemType Directory -Force -Path $extractDirectory | Out-Null
    Expand-RuntimeBundleArchive -ArchivePath $resolvedBundlePath -DestinationDirectory $extractDirectory
    $bundleRoot = Resolve-RuntimeBundleRoot -ExtractedDirectory $extractDirectory
    Write-Host "  Bundle root: $bundleRoot" -ForegroundColor DarkGray

    # ----------------------------------------------------------------------
    # Phase 2: Structure validation
    # ----------------------------------------------------------------------
    Write-Host "`n--- Phase 2: Structure ---" -ForegroundColor Cyan
    $structureOk, $libDir, $javaPath, $metadataPath, $startShPath, $startBatPath = Validate-BundleStructure -BundleRoot $bundleRoot
    if (-not $structureOk) {
        throw "Bundle structure validation failed. The bundle may be corrupted or incomplete."
    }

    # ----------------------------------------------------------------------
    # Phase 3: Metadata validation
    # ----------------------------------------------------------------------
    Write-Host "`n--- Phase 3: Metadata ---" -ForegroundColor Cyan
    $metadataOk, $metadata = Validate-Metadata -MetadataPath $metadataPath -ExpectedAsset $expectedAsset
    if (-not $metadataOk) {
        throw "Bundle metadata validation failed."
    }
    $mainClass = $metadata.mainClass

    # ----------------------------------------------------------------------
    # Phase 4: Main class validation
    # ----------------------------------------------------------------------
    Write-Host "`n--- Phase 4: Main Class ---" -ForegroundColor Cyan
    $mainClassOk, $containingJar = Validate-MainClassPresence -LibDir $libDir -MainClass $mainClass
    if (-not $mainClassOk) {
        throw "Main class '$mainClass' was not found in any jar in lib/. The bundle cannot start."
    }

    # ----------------------------------------------------------------------
    # Phase 5: Start script validation
    # ----------------------------------------------------------------------
    Write-Host "`n--- Phase 5: Start Scripts ---" -ForegroundColor Cyan
    $scriptsOk = Validate-StartScripts -StartShPath $startShPath -StartBatPath $startBatPath -MainClass $mainClass
    if (-not $scriptsOk) {
        Write-Host "  WARNING: Start script validation found issues but continuing..." -ForegroundColor Yellow
    }

    # ----------------------------------------------------------------------
    # Phase 6: Bundled JRE validation
    # ----------------------------------------------------------------------
    Write-Host "`n--- Phase 6: Bundled JRE ---" -ForegroundColor Cyan
    $jreOk, $jreVersion = Test-BundledJavaRuntime -JavaPath $javaPath
    if (-not $jreOk) {
        throw "Bundled JRE validation failed."
    }

    # ----------------------------------------------------------------------
    # Phase 7: Functional test (optional)
    # ----------------------------------------------------------------------
    if ($SkipFunctionalTest) {
        Write-Host "`n--- Phase 7: Functional Test (SKIPPED) ---" -ForegroundColor Yellow
        Write-Host "  Use -SkipFunctionalTest:`$false to run the full health-check test." -ForegroundColor DarkGray
    }
    else {
        Write-Host "`n--- Phase 7: Functional Test ---" -ForegroundColor Cyan
        $funcOk = Invoke-FunctionalTest `
            -BundleRoot $bundleRoot `
            -JavaPath $javaPath `
            -MainClass $mainClass `
            -HealthPath $HealthPath `
            -StartupTimeoutSeconds $StartupTimeoutSeconds
        if (-not $funcOk) {
            throw "Functional test failed. The application did not become healthy within ${StartupTimeoutSeconds}s."
        }
    }

    # ----------------------------------------------------------------------
    # Summary
    # ----------------------------------------------------------------------
    Write-Host "`n========================================" -ForegroundColor Green
    Write-Host "  Bundle verification PASSED" -ForegroundColor Green
    Write-Host "========================================" -ForegroundColor Green
    Write-Host "  Main class : $mainClass" -ForegroundColor DarkGray
    Write-Host "  Found in   : $containingJar" -ForegroundColor DarkGray
    Write-Host "  JRE version: $(($jreVersion -split "`n")[0].Trim())" -ForegroundColor DarkGray
    if (-not $SkipFunctionalTest) {
        Write-Host "  Health     : UP (functional test passed)" -ForegroundColor DarkGray
    }
    Write-Host "  Bundle     : $resolvedBundlePath" -ForegroundColor DarkGray
}
finally {
    Remove-IfExists $tempRoot
}
