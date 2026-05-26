param(
    [Parameter(Mandatory = $true)]
    [string]$BundlePath,

    [string]$ExpectedAssetName,
    [string]$HealthPath = '/actuator/health',
    [int]$StartupTimeoutSeconds = 180
)

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

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
    if (Get-IsWindows) {
        return 'java.exe'
    }

    return 'java'
}

function Get-ArchiveKind([string]$Path) {
    if ($Path.EndsWith('.zip', [System.StringComparison]::OrdinalIgnoreCase)) {
        return 'zip'
    }
    if ($Path.EndsWith('.tar.gz', [System.StringComparison]::OrdinalIgnoreCase)) {
        return 'tar.gz'
    }

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
    $jarPath = Join-Path $Path 'Browser4.jar'
    $javaPath = Join-Path $Path (Join-Path 'jre' (Join-Path 'bin' (Get-JavaExecutableName)))
    return (Test-Path -LiteralPath $jarPath -PathType Leaf) -and (Test-Path -LiteralPath $javaPath -PathType Leaf)
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

    throw "Runtime bundle does not contain Browser4.jar and a bundled JRE under $ExtractedDirectory"
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
    if ($null -eq $Content) {
        return ''
    }
    if ($Content -is [string]) {
        return $Content
    }
    if ($Content -is [byte[]]) {
        return [System.Text.Encoding]::UTF8.GetString($Content)
    }

    return ($Content | ConvertTo-Json -Depth 10 -Compress)
}

function Wait-ForHealthyEndpoint([string]$Url, [int]$TimeoutSeconds, [System.Diagnostics.Process]$Process, [string]$StdOutPath, [string]$StdErrPath) {
    $deadline = (Get-Date).AddSeconds($TimeoutSeconds)
    do {
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
                return (Convert-ContentToText $response.Content)
            }
        }
        catch {
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
    if (-not $Process) {
        return
    }

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

$resolvedBundlePath = (Resolve-Path -LiteralPath $BundlePath).Path
$expectedAsset = if ([string]::IsNullOrWhiteSpace($ExpectedAssetName)) { [System.IO.Path]::GetFileName($resolvedBundlePath) } else { $ExpectedAssetName }

$tempRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("browser4-runtime-verify-" + [System.Guid]::NewGuid().ToString('N'))
$extractDirectory = Join-Path $tempRoot 'extract'
$stdoutPath = Join-Path $tempRoot 'browser4.stdout.log'
$stderrPath = Join-Path $tempRoot 'browser4.stderr.log'
$process = $null

try {
    New-Item -ItemType Directory -Force -Path $extractDirectory | Out-Null

    Write-Host "Extracting runtime bundle: $resolvedBundlePath" -ForegroundColor Cyan
    Expand-RuntimeBundleArchive -ArchivePath $resolvedBundlePath -DestinationDirectory $extractDirectory

    $bundleRoot = Resolve-RuntimeBundleRoot -ExtractedDirectory $extractDirectory
    $jarPath = Join-Path $bundleRoot 'Browser4.jar'
    $javaPath = Join-Path $bundleRoot (Join-Path 'jre' (Join-Path 'bin' (Get-JavaExecutableName)))
    $metadataPath = Join-Path $bundleRoot 'runtime-bundle.json'

    if (-not (Test-Path -LiteralPath $metadataPath -PathType Leaf)) {
        throw "Runtime bundle is missing metadata file: $metadataPath"
    }

    $metadata = Get-Content -LiteralPath $metadataPath -Raw | ConvertFrom-Json
    if ($metadata.assetName -ne $expectedAsset) {
        throw "Runtime bundle metadata assetName '$($metadata.assetName)' does not match expected '$expectedAsset'"
    }
    if ($metadata.jarFileName -ne 'Browser4.jar') {
        throw "Runtime bundle metadata jarFileName must be Browser4.jar but was '$($metadata.jarFileName)'"
    }
    if ($metadata.jreDirectoryName -ne 'jre') {
        throw "Runtime bundle metadata jreDirectoryName must be jre but was '$($metadata.jreDirectoryName)'"
    }

    Write-Host "Checking bundled Java runtime..." -ForegroundColor Cyan
    & $javaPath -version
    if ($LASTEXITCODE -ne 0) {
        throw "Bundled java -version failed with exit code $LASTEXITCODE"
    }

    $port = Get-FreeTcpPort
    $healthUrl = "http://127.0.0.1:$port$HealthPath"
    Write-Host "Starting Browser4 from runtime bundle on port $port" -ForegroundColor Cyan
    $process = Start-Process -FilePath $javaPath `
        -ArgumentList @(
            '-jar',
            $jarPath,
            '--server.address=127.0.0.1',
            "--server.port=$port",
            '--browser.display.mode=HEADLESS'
        ) `
        -WorkingDirectory $bundleRoot `
        -RedirectStandardOutput $stdoutPath `
        -RedirectStandardError $stderrPath `
        -PassThru

    $healthContent = Wait-ForHealthyEndpoint -Url $healthUrl -TimeoutSeconds $StartupTimeoutSeconds -Process $process -StdOutPath $stdoutPath -StdErrPath $stderrPath
    if ($healthContent -notmatch 'UP') {
        throw "Health endpoint responded but did not contain UP. Response: $healthContent"
    }

    Write-Host "Runtime bundle verification passed: $resolvedBundlePath" -ForegroundColor Green
}
finally {
    Stop-ProcessQuietly -Process $process
    Remove-IfExists $tempRoot
}
