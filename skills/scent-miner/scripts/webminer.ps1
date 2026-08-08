#!/usr/bin/env pwsh
<#
.SYNOPSIS
    WebMiner — extract structured data from local HTML files.

.DESCRIPTION
    Finds a Java 17+ installation and launches the WebMiner fat JAR
    with all required JVM module-opens flags.  Every argument is
    forwarded directly to WebMiner.main().

    Management commands for self-install and self-update from GitHub Releases:
      install   [version]    Download and install a release
      update                 Check for and install the latest release
      version                Show installed and latest available versions
      uninstall              Remove the installed release
      run-example            Download the test dataset and run the full
                             pipeline on it (requires 7-Zip)

.PARAMETER JavaHome
    Explicit JAVA_HOME path.  If omitted the script auto-detects a
    Java 17+ installation.

.EXAMPLE
    # Management
    .\webminer.ps1 install
    .\webminer.ps1 install v0.0.1
    .\webminer.ps1 update
    .\webminer.ps1 version
    .\webminer.ps1 uninstall

.EXAMPLE
    .\webminer.ps1 run-example

.EXAMPLE
    .\webminer.ps1 run-example --k 8

.EXAMPLE
    .\webminer.ps1 all C:\data\html-pages

.EXAMPLE
    .\webminer.ps1 all C:\data\html-pages --k 12 --max-files 50

.EXAMPLE
    .\webminer.ps1 -JavaHome "D:\jdk-17" all C:\data\html-pages
#>

$ErrorActionPreference = 'Stop'

# ==================================================================
# Constants
# ==================================================================
$REPO_OWNER = 'platonai'
$REPO_NAME  = 'web-miner'
$GITHUB_API_LATEST = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"

# OSS mirror (Alibaba Cloud) — fallback when GitHub is unavailable
$OSS_BASE_URL = 'https://web-miner.oss-cn-beijing.aliyuncs.com'
$OSS_LATEST_JSON = "$OSS_BASE_URL/releases/latest-release.json"
$OSS_LATEST_DOWNLOAD = "$OSS_BASE_URL/releases/latest/download"

# Cross-platform home directory: USERPROFILE on Windows, HOME on Linux/macOS
$HomeDir = if ($IsWindows) { $env:USERPROFILE } else { $env:HOME }
if (-not $HomeDir) { throw 'Cannot determine home directory: neither USERPROFILE nor HOME is set.' }
$InstallRoot = Join-Path $HomeDir '.scent\webminer'
$InstallLib  = Join-Path $InstallRoot 'lib'
$InstallJar  = Join-Path $InstallLib 'scent-miner.jar'
$VersionFile = Join-Path $InstallRoot 'version.txt'
$ChecksumFile = Join-Path $InstallRoot 'checksum.sha256'

# Cross-platform temp directory
$TempDir = if ($IsWindows -or $env:TEMP) { $env:TEMP } elseif ($env:TMPDIR) { $env:TMPDIR } else { '/tmp' }
if (-not $TempDir) { $TempDir = '/tmp' }

# Platform-aware binary names
$JavaExeName = if ($IsWindows) { 'java.exe' } else { 'java' }
$SevenZipName = if ($IsWindows) { '7z.exe' } else { '7z' }
$CurlExeName = if ($IsWindows) { 'curl.exe' } else { 'curl' }

# Management subcommands
$ManagementCommands = @('install', 'update', 'version', 'uninstall', 'run-example')

# ------------------------------------------------------------------
# Parse -JavaHome and command out of the argument list
# ------------------------------------------------------------------
$JavaHome = ''
$Command = ''
$RemainingArgs = @()

$i = 0
while ($i -lt $args.Count) {
    $arg = $args[$i]
    if ($arg -eq '-JavaHome') {
        $JavaHome = $args[$i + 1]
        $i += 2
    }
    elseif ($i -eq 0 -and $arg -notmatch '^-' -and $arg -in $ManagementCommands) {
        $Command = $arg
        $i++
    }
    else {
        $RemainingArgs += $arg
        $i++
    }
}

# ------------------------------------------------------------------
# Resolve the script directory
# ------------------------------------------------------------------
$ScriptDir = Split-Path $PSCommandPath -Parent

# ------------------------------------------------------------------
# Help
# ------------------------------------------------------------------
$HelpRequested = ($Command -eq '--help' -or $Command -eq '-h' -or
                  $RemainingArgs -contains '--help' -or $RemainingArgs -contains '-h' -or
                  (-not $Command -and $RemainingArgs.Count -eq 0))
if ($HelpRequested) {
    Write-Host @"

WebMiner — extract structured data from local HTML files.

Management:
  install   [version]    Download and install a release (GitHub → OSS mirror fallback)
  update                 Check for and install the latest release
  version                Show installed and latest available versions
  uninstall              Remove the installed release
  run-example            Run the full pipeline on the mock e-commerce site

Usage:
  .\webminer.ps1 [command] [options]

Management examples:
  .\webminer.ps1 install
  .\webminer.ps1 install v0.0.1
  .\webminer.ps1 update
  .\webminer.ps1 version
  .\webminer.ps1 uninstall
  .\webminer.ps1 run-example

Run examples:
  .\webminer.ps1 all C:\data\html-pages
  .\webminer.ps1 all C:\data\html-pages --k 12 --max-files 50
  .\webminer.ps1 -JavaHome "D:\jdk-17" all C:\data\html-pages

Run priority (automatic):
  1. Installed JAR  → ~/.scent/webminer/lib/scent-miner.jar
  2. Bundled JAR    → lib/scent-miner.jar (next to or above this script)

"@
    exit 0
}

# ------------------------------------------------------------------
# Find Java 17+
# ------------------------------------------------------------------
function Find-Java17 {
    param([string] $ExplicitHome)

    if ($ExplicitHome) {
        $javaExe = Join-Path $ExplicitHome "bin\$JavaExeName"
        if (Test-Path $javaExe) { return $ExplicitHome }
        throw "JAVA_HOME not found at: $ExplicitHome"
    }

    # 1. JAVA_HOME env var
    $envJavaHome = $env:JAVA_HOME
    if ($envJavaHome) {
        $javaExe = Join-Path $envJavaHome "bin\$JavaExeName"
        if (Test-Path $javaExe) {
            $ver = & $javaExe -version 2>&1 | Select-Object -First 1
            if ($ver -match 'version "(\d+)' -and [int]$Matches[1] -ge 17) {
                return $envJavaHome
            }
        }
    }

    # 2. Common install locations
    $candidates = if ($IsWindows) {
        @(
            'D:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot',
            'C:\Program Files\OpenLogic\jdk-17.0.14.7-hotspot',
            'D:\Program Files\Java\jdk-17',
            'C:\Program Files\Java\jdk-17',
            'D:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot',
            'C:\Program Files\Eclipse Adoptium\jdk-17.0.14.7-hotspot'
        )
    } else {
        @(
            '/usr/lib/jvm/java-17-openjdk',
            '/usr/lib/jvm/java-17-openjdk-amd64',
            '/usr/lib/jvm/jdk-17',
            '/usr/local/lib/jvm/jdk-17'
        )
    }

    foreach ($candidate in $candidates) {
        $javaExe = Join-Path $candidate "bin\$JavaExeName"
        if (Test-Path $javaExe) { return $candidate }
    }

    # 3. `java` on PATH
    $pathJava = Get-Command java -ErrorAction SilentlyContinue
    if ($pathJava) {
        $ver = & java -version 2>&1 | Select-Object -First 1
        if ($ver -match 'version "(\d+)' -and [int]$Matches[1] -ge 17) {
            $javaBin = (Get-Command java).Source
            return (Split-Path (Split-Path $javaBin -Parent) -Parent)
        }
    }

    throw @"
No Java 17+ installation found.
Set JAVA_HOME or pass -JavaHome explicitly.

Download a JDK:
  https://adoptium.net/download/
  https://jdk.java.net/17/
  https://www.microsoft.com/openjdk
"@
}

# ------------------------------------------------------------------
# Find the WebMiner JAR
# ------------------------------------------------------------------
function Find-WebMinerJar {
<#
.SYNOPSIS
    Searches for scent-miner.jar. Checks, in order:
    1. Installed location: ~/.scent/webminer/lib/scent-miner.jar
    2. Walk up from script dir looking for lib/scent-miner.jar or scent-miner.jar
#>
    # Tier 1: Installed release
    if (Test-Path $InstallJar) {
        return (Resolve-Path $InstallJar).Path
    }

    # Tier 2: Walk up from script directory (published layout or flat layout)
    $dir = $ScriptDir
    while ($dir -and $dir.Length -gt 3) {
        $candidate = Join-Path $dir 'lib\scent-miner.jar'
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
        $candidate = Join-Path $dir 'scent-miner.jar'
        if (Test-Path $candidate) { return (Resolve-Path $candidate).Path }
        $parent = Split-Path $dir -Parent
        if ($parent -eq $dir) { break }
        $dir = $parent
    }

    return $null
}

# ==================================================================
# Module-opens required by the stack at runtime
# ==================================================================
$ModuleOpts = @(
    '--add-opens=java.base/java.lang=ALL-UNNAMED',
    '--add-opens=java.base/java.lang.invoke=ALL-UNNAMED',
    '--add-opens=java.base/java.lang.reflect=ALL-UNNAMED',
    '--add-opens=java.base/java.io=ALL-UNNAMED',
    '--add-opens=java.base/java.net=ALL-UNNAMED',
    '--add-opens=java.base/java.nio=ALL-UNNAMED',
    '--add-opens=java.base/java.util=ALL-UNNAMED',
    '--add-opens=java.base/java.util.concurrent=ALL-UNNAMED',
    '--add-opens=java.base/java.util.concurrent.atomic=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.ch=ALL-UNNAMED',
    '--add-opens=java.base/sun.nio.cs=ALL-UNNAMED',
    '--add-opens=java.base/sun.security.action=ALL-UNNAMED',
    '--add-opens=java.base/sun.util.calendar=ALL-UNNAMED',
    '--add-opens=java.security.jgss/sun.security.krb5=ALL-UNNAMED'
)

# ==================================================================
# Invoke-WebMiner — launch Java with the given JAR and arguments
# ==================================================================
function Invoke-WebMiner {
    param(
        [Parameter(Mandatory = $true)]
        [string] $JarPath
    )

    $appName = if ($env:APP_NAME) { $env:APP_NAME } else { 'webminer' }

    $javaArgs = @(
        "-Dapp.name=$appName"
    ) + $ModuleOpts + @(
        '-jar', $JarPath
    ) + $RemainingArgs

    Write-Host '[WebMiner] Launching ...' -ForegroundColor DarkGray

    # Ensure PowerShell decodes Java's UTF-8 output correctly
    $prevOutputEncoding = [Console]::OutputEncoding
    [Console]::OutputEncoding = [System.Text.Encoding]::UTF8

    $javaExe = Join-Path $Java17Home "bin\$JavaExeName"
    try {
        & $javaExe @javaArgs
    } finally {
        [Console]::OutputEncoding = $prevOutputEncoding
    }
    return $LASTEXITCODE
}

# ==================================================================
# Release management functions
# ==================================================================

function Get-InstalledVersion {
<#
.SYNOPSIS
    Reads the installed version from ~/.scent/webminer/version.txt.
    Returns $null if no installation exists.
#>
    if (Test-Path $VersionFile) {
        return (Get-Content $VersionFile -Raw).Trim()
    }
    return $null
}

function Get-OssJarUrl {
<#
.SYNOPSIS
    Returns the OSS mirror download URL for a given release tag.
#>
    param([string] $TagName)

    if ($TagName -eq 'latest') {
        return "$OSS_LATEST_DOWNLOAD/scent-miner.jar"
    }
    return "$OSS_BASE_URL/releases/download/$TagName/scent-miner.jar"
}

function Get-OssSha256Url {
<#
.SYNOPSIS
    Returns the OSS mirror URL for the JAR's .sha256 checksum file.
#>
    param([string] $TagName)

    if ($TagName -eq 'latest') {
        return "$OSS_LATEST_DOWNLOAD/scent-miner.jar.sha256"
    }
    return "$OSS_BASE_URL/releases/download/$TagName/scent-miner.jar.sha256"
}

function Get-LatestRelease {
<#
.SYNOPSIS
    Queries the GitHub API for the latest release.
    Returns a hashtable with keys: tagName, name, publishedAt, jarUrl, jarSize, jarChecksum.
    Returns $null on failure (no internet, rate limit, etc.).
#>
    try {
        Write-Host '[WebMiner] Checking latest release ...' -ForegroundColor DarkGray
        $release = Invoke-RestMethod -Uri $GITHUB_API_LATEST -ErrorAction Stop

        $jarAsset = $release.assets | Where-Object { $_.name -eq 'scent-miner.jar' } | Select-Object -First 1

        if (-not $jarAsset) {
            Write-Warning "Latest release ($($release.tag_name)) does not contain scent-miner.jar"
            return $null
        }

        return @{
            tagName      = $release.tag_name
            name         = $release.name
            publishedAt  = $release.published_at
            jarUrl       = $jarAsset.browser_download_url
            jarSize      = $jarAsset.size
            jarChecksum  = $jarAsset.digest
        }
    }
    catch {
        if ($_.Exception.Response.StatusCode -eq 403) {
            Write-Warning "GitHub API rate limit exceeded. Trying OSS mirror ..."
        }
        else {
            Write-Warning "Cannot reach GitHub API: $($_.Exception.Message)"
            Write-Host '[WebMiner] Falling back to OSS mirror ...' -ForegroundColor DarkGray
        }
    }

    # Fallback: try OSS mirror
    return Get-LatestReleaseFromOss
}

function Get-LatestReleaseFromOss {
<#
.SYNOPSIS
    Fetches the latest release metadata from the Aliyun OSS mirror.
    Returns the same hashtable shape as Get-LatestRelease, or $null on failure.
#>
    try {
        Write-Host '[WebMiner] Querying OSS mirror ...' -ForegroundColor DarkGray
        $meta = Invoke-RestMethod -Uri $OSS_LATEST_JSON -ErrorAction Stop

        if (-not $meta -or -not $meta.tag) {
            Write-Warning 'OSS mirror returned incomplete metadata.'
            return $null
        }

        $jarAsset = $meta.assets | Where-Object { $_.name -eq 'scent-miner.jar' } | Select-Object -First 1
        if (-not $jarAsset) {
            Write-Warning "OSS release ($($meta.tag)) does not contain scent-miner.jar"
            return $null
        }

        $ossJarUrl = Get-OssJarUrl -TagName $meta.tag

        return @{
            tagName      = $meta.tag
            name         = $meta.tag
            publishedAt  = $meta.published_at
            jarUrl       = $ossJarUrl
            jarSize      = $jarAsset.size
            jarChecksum  = "sha256:$($jarAsset.sha256)"
        }
    }
    catch {
        Write-Warning "Cannot reach OSS mirror either: $($_.Exception.Message)"
        return $null
    }
}

function Install-WebMiner {
<#
.SYNOPSIS
    Downloads and installs a WebMiner release.
.PARAMETER Release
    A hashtable from Get-LatestRelease (must contain tagName, jarUrl, jarSize, jarChecksum).
.PARAMETER Force
    Overwrite even if the same version is already installed.
#>
    param(
        [Parameter(Mandatory = $true)]
        [hashtable] $Release,
        [switch] $Force
    )

    $tagName     = $Release.tagName
    $jarUrl      = $Release.jarUrl
    $jarSize     = $Release.jarSize
    $jarChecksum = $Release.jarChecksum

    # Check if already installed
    $installed = Get-InstalledVersion
    if ($installed -eq $tagName -and -not $Force) {
        Write-Host "[WebMiner] $tagName is already installed." -ForegroundColor Green
        return $true
    }

    if ($installed -and $installed -ne $tagName) {
        Write-Host "[WebMiner] Upgrading from $installed to $tagName ..." -ForegroundColor Cyan
    }
    else {
        Write-Host "[WebMiner] Installing $tagName ..." -ForegroundColor Cyan
    }

    # Prepare directories
    if (-not (Test-Path $InstallLib)) {
        New-Item -ItemType Directory -Path $InstallLib -Force | Out-Null
    }

    # Download
    $sizeMB = if ($jarSize) { "{0:N1}" -f ($jarSize / 1MB) } else { '?' }
    Write-Host "[WebMiner] Downloading scent-miner.jar ($sizeMB MB) ..." -ForegroundColor DarkGray

    $tempJar = Join-Path $TempDir 'scent-miner-download.jar'
    try {
        if (Test-Path $tempJar) { Remove-Item $tempJar -Force }

        # Try primary URL first, fall back to OSS mirror
        $downloadUrl = $jarUrl
        $downloaded = $false
        $urlsToTry = @($jarUrl)

        # Add OSS mirror as fallback if the primary URL is GitHub
        $ossUrl = Get-OssJarUrl -TagName $tagName
        if ($jarUrl -notmatch [regex]::Escape($OSS_BASE_URL)) {
            $urlsToTry += $ossUrl
        }

        foreach ($url in $urlsToTry) {
            try {
                Write-Host "[WebMiner] From: $url" -ForegroundColor DarkGray
                Invoke-WebRequest -Uri $url -OutFile $tempJar -UseBasicParsing

                $downloadedSize = (Get-Item $tempJar).Length
                if ($downloadedSize -eq 0) {
                    if ($url -eq $urlsToTry[-1]) {
                        throw 'Downloaded file is empty.'
                    }
                    Write-Warning "Download from mirror returned empty file, trying next ..."
                    continue
                }

                $downloaded = $true
                $downloadUrl = $url
                break
            }
            catch {
                if ($url -eq $urlsToTry[-1]) {
                    throw
                }
                Write-Warning "Primary download failed, trying OSS mirror ..."
            }
        }

        if (-not $downloaded) {
            throw 'All download sources exhausted.'
        }

        Write-Host "[WebMiner] Downloaded $('{0:N1}' -f ($downloadedSize / 1MB)) MB" -ForegroundColor DarkGray

        # Verify checksum
        $actualHash = (Get-FileHash -Path $tempJar -Algorithm SHA256).Hash.ToLower()
        $expectedHash = $jarChecksum -replace '^sha256:', ''

        # If checksum is missing (e.g. OSS fallback), try to fetch it from OSS .sha256 file
        if (-not $expectedHash) {
            try {
                $sha256Url = Get-OssSha256Url -TagName $tagName
                $remoteHash = (Invoke-RestMethod -Uri $sha256Url -ErrorAction SilentlyContinue).Trim()
                if ($remoteHash -match '^[0-9a-f]{64}$') {
                    $expectedHash = $remoteHash
                    Write-Host '[WebMiner] Fetched checksum from OSS mirror.' -ForegroundColor DarkGray
                }
            }
            catch {
                Write-Warning 'Cannot fetch checksum from mirror; skipping verification.'
            }
        }
        if ($expectedHash -and $actualHash -ne $expectedHash) {
            throw "Checksum mismatch!`n  Expected: $expectedHash`n  Actual:   $actualHash"
        }
        if ($expectedHash) {
            Write-Host "[WebMiner] SHA-256 verified." -ForegroundColor Green
        }
        else {
            Write-Warning "No checksum available from GitHub; skipping verification."
        }

        # Move into place
        Move-Item -Path $tempJar -Destination $InstallJar -Force

        # Write version + checksum
        $tagName | Out-File -FilePath $VersionFile -Encoding utf8 -NoNewline
        $actualHash | Out-File -FilePath $ChecksumFile -Encoding utf8 -NoNewline

        Write-Host "[WebMiner] Installed $tagName to $InstallRoot" -ForegroundColor Green
        return $true
    }
    catch {
        Write-Error "Installation failed: $_"
        if (Test-Path $tempJar) { Remove-Item $tempJar -Force -ErrorAction SilentlyContinue }
        return $false
    }
    finally {
        if (Test-Path $tempJar) { Remove-Item $tempJar -Force -ErrorAction SilentlyContinue }
    }
}

# ==================================================================
# Run-example: download test dataset and run the full pipeline
# ==================================================================

function Find-7Zip {
<#
.SYNOPSIS
    Locates 7-Zip on PATH or in common install locations.
#>
    # Try primary binary name first
    $onPath = Get-Command $SevenZipName -ErrorAction SilentlyContinue
    if ($onPath) { return $onPath.Source }

    # On Linux, some distros use '7zz' instead of '7z'
    if (-not $IsWindows) {
        $onPath = Get-Command '7zz' -ErrorAction SilentlyContinue
        if ($onPath) { return $onPath.Source }
    }

    $candidates = if ($IsWindows) {
        @(
            'C:\Program Files\7-Zip\7z.exe',
            'D:\Program Files\7-Zip\7z.exe',
            "${env:ProgramFiles}\7-Zip\7z.exe",
            "${env:ProgramFiles(x86)}\7-Zip\7z.exe"
        )
    } else {
        @(
            '/usr/bin/7z',
            '/usr/bin/7zz',
            '/usr/local/bin/7z',
            '/usr/local/bin/7zz'
        )
    }

    foreach ($candidate in $candidates) {
        if ($candidate -and (Test-Path $candidate)) {
            return $candidate
        }
    }

    Write-Error @"
7-Zip not found.
Install from https://www.7-zip.org/ or ensure 7z is on PATH.
"@
    exit 1
}

function Invoke-RunExample {
<#
.SYNOPSIS
    Downloads the pre-uploaded test dataset (real Amazon HTML pages),
    extracts it, and runs the full WebMiner pipeline on it.
#>
    # Extra args after "run-example" are forwarded to WebMiner (e.g. --k 8)
    $ExtraArgs = $RemainingArgs

    $ArchiveUrl  = 'https://web-miner.oss-cn-beijing.aliyuncs.com/test/amazon.com.7z'
    $ArchiveName = 'amazon.com.7z'
    $ExtractDir  = Join-Path $HomeDir '.scent\test-data'
    $DataDir     = Join-Path $ExtractDir 'amazon.com'    # archive contains this subdirectory
    $ArchivePath = Join-Path $TempDir $ArchiveName

    # --- Already extracted? Skip download ---
    if ((Test-Path $DataDir) -and (Get-ChildItem -Recurse -File $DataDir -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match '\.html?$' } | Select-Object -First 1)) {
        Write-Host "[WebMiner] Test dataset already present at: $DataDir" -ForegroundColor Green
    }
    else {
        # --- Download the archive ---
        if ((Test-Path $ArchivePath) -and (Get-Item $ArchivePath).Length -gt 0) {
            Write-Host "[WebMiner] Archive already cached at: $ArchivePath" -ForegroundColor DarkGray
        }
        else {
            Write-Host "[WebMiner] Downloading test dataset ..." -ForegroundColor Cyan
            Write-Host "[WebMiner] From: $ArchiveUrl" -ForegroundColor DarkGray

            try {
                Invoke-WebRequest -Uri $ArchiveUrl -OutFile $ArchivePath -UseBasicParsing
            }
            catch {
                # Fallback: try curl
                $curl = Get-Command $CurlExeName -ErrorAction SilentlyContinue
                if ($curl) {
                    Write-Host "[WebMiner] Invoke-WebRequest failed, trying curl ..." -ForegroundColor Yellow
                    & $CurlExeName -L -o $ArchivePath $ArchiveUrl
                    if ($LASTEXITCODE -ne 0) {
                        Write-Error "Download failed (curl exit code: $LASTEXITCODE)"
                        exit 1
                    }
                }
                else {
                    Write-Error "Download failed: $_"
                    exit 1
                }
            }

            $size = '{0:N1} MB' -f ((Get-Item $ArchivePath).Length / 1MB)
            Write-Host "[WebMiner] Downloaded $size to $ArchivePath" -ForegroundColor Green
        }

        # --- Extract (archive contains an amazon.com/ directory) ---
        $SevenZip = Find-7Zip
        Write-Host "[WebMiner] Extracting to $ExtractDir ..." -ForegroundColor Cyan
        Remove-Item -Recurse -Force $ExtractDir -ErrorAction SilentlyContinue
        New-Item -ItemType Directory -Force -Path $ExtractDir | Out-Null

        & $SevenZip x $ArchivePath -o"$ExtractDir" -y | Out-Null
        if ($LASTEXITCODE -ne 0) {
            Write-Error "7-Zip extraction failed (exit code: $LASTEXITCODE)"
            exit 1
        }

        $htmlCount = (Get-ChildItem -Recurse -File $DataDir -ErrorAction SilentlyContinue | Where-Object { $_.Extension -match '\.html?$' }).Count
        Write-Host "[WebMiner] Extracted $htmlCount HTML files to $DataDir" -ForegroundColor Green
    }

    # --- Set up RemainingArgs so the main dispatch launches WebMiner ---
    $script:RemainingArgs = @('all', $DataDir) + $ExtraArgs
}

# ==================================================================
# Management command handlers
# ==================================================================

function Invoke-Install {
    param([string] $Version)

    if ($Version) {
        $tagName = if ($Version -match '^v') { $Version } else { "v$Version" }

        Write-Host "[WebMiner] Installing specific version: $tagName" -ForegroundColor Cyan
        $releaseUrl = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/tags/$tagName"
        $release = $null

        # Try GitHub API first
        try {
            $releaseInfo = Invoke-RestMethod -Uri $releaseUrl -ErrorAction Stop

            $jarAsset = $releaseInfo.assets | Where-Object { $_.name -eq 'scent-miner.jar' } | Select-Object -First 1
            if (-not $jarAsset) {
                Write-Error "Release '$tagName' does not contain scent-miner.jar"
                exit 1
            }

            $release = @{
                tagName     = $releaseInfo.tag_name
                name        = $releaseInfo.name
                publishedAt = $releaseInfo.published_at
                jarUrl      = $jarAsset.browser_download_url
                jarSize     = $jarAsset.size
                jarChecksum = $jarAsset.digest
            }
        }
        catch {
            if ($_.Exception.Response.StatusCode -eq 404) {
                Write-Error "Release '$tagName' not found on GitHub."
                Write-Host "`nAvailable releases: https://github.com/$REPO_OWNER/$REPO_NAME/releases" -ForegroundColor DarkGray
                exit 1
            }

            # Network error — fall back to OSS mirror
            Write-Warning "Cannot reach GitHub API: $($_.Exception.Message)"
            Write-Host '[WebMiner] Falling back to OSS mirror ...' -ForegroundColor DarkGray

            $ossJarUrl = Get-OssJarUrl -TagName $tagName
            $release = @{
                tagName     = $tagName
                name        = $tagName
                publishedAt = ''
                jarUrl      = $ossJarUrl
                jarSize     = 0
                jarChecksum = ''
            }
        }
    }
    else {
        $release = Get-LatestRelease
        if (-not $release) {
            Write-Error "Cannot find the latest release. Check your internet connection."
            Write-Host "`nReleases are fetched from GitHub and mirrored to Aliyun OSS." -ForegroundColor DarkGray
            Write-Host "You can also install a specific version:" -ForegroundColor DarkGray
            Write-Host "  .\webminer.ps1 install v0.0.1" -ForegroundColor White
            exit 1
        }
    }

    $success = Install-WebMiner -Release $release
    if (-not $success) { exit 1 }
}

function Invoke-Update {
    $installed = Get-InstalledVersion
    if (-not $installed) {
        Write-Host "[WebMiner] No installation found. Use 'install' to download a release." -ForegroundColor Yellow
        Write-Host "  .\webminer.ps1 install" -ForegroundColor White
        exit 1
    }

    $latest = Get-LatestRelease
    if (-not $latest) {
        Write-Error "Cannot check for updates. Check your internet connection."
        exit 1
    }

    if ($installed -eq $latest.tagName) {
        Write-Host "[WebMiner] Already up to date ($installed)." -ForegroundColor Green
        exit 0
    }

    Write-Host "[WebMiner] Update available: $installed → $($latest.tagName)" -ForegroundColor Cyan
    $success = Install-WebMiner -Release $latest
    if (-not $success) { exit 1 }
}

function Invoke-Version {
    $installed = Get-InstalledVersion

    Write-Host ''
    Write-Host '  WebMiner' -ForegroundColor White
    Write-Host '  --------' -ForegroundColor DarkGray

    if ($installed) {
        $jarSize = if (Test-Path $InstallJar) {
            '{0:N1} MB' -f ((Get-Item $InstallJar).Length / 1MB)
        }
        else {
            '(JAR missing)'
        }
        Write-Host "  Installed : $installed  ($jarSize)" -ForegroundColor Green
        Write-Host "  Location  : $InstallRoot" -ForegroundColor DarkGray
    }
    else {
        Write-Host '  Installed : (none)' -ForegroundColor Yellow
    }

    $latest = Get-LatestRelease
    if ($latest) {
        $latestSize = '{0:N1} MB' -f ($latest.jarSize / 1MB)
        Write-Host "  Latest    : $($latest.tagName)  ($latestSize)" -ForegroundColor Cyan
        Write-Host "  Published : $($latest.publishedAt)" -ForegroundColor DarkGray

        if ($installed -and $installed -ne $latest.tagName) {
            Write-Host ''
            Write-Host "  Update available! Run: .\webminer.ps1 update" -ForegroundColor Yellow
        }
    }
    else {
        Write-Host '  Latest    : (cannot reach GitHub or OSS mirror)' -ForegroundColor DarkGray
    }
    Write-Host ''
}

function Invoke-Uninstall {
    if (-not (Test-Path $InstallRoot)) {
        Write-Host "[WebMiner] No installation found at: $InstallRoot" -ForegroundColor Yellow
        exit 0
    }

    $installed = Get-InstalledVersion
    $versionStr = if ($installed) { "$installed" } else { 'unknown version' }

    Write-Host "[WebMiner] Uninstalling $versionStr ..." -ForegroundColor Cyan
    Remove-Item -Recurse -Force $InstallRoot
    Write-Host "[WebMiner] Removed: $InstallRoot" -ForegroundColor Green
}

# ==================================================================
# Main dispatch
# ==================================================================

# --- Management commands (no Java needed) ---
switch ($Command) {
    'install'      { Invoke-Install -Version $RemainingArgs[0]; exit 0 }
    'update'       { Invoke-Update; exit 0 }
    'version'      { Invoke-Version; exit 0 }
    'uninstall'    { Invoke-Uninstall; exit 0 }
    'run-example'  { Invoke-RunExample }
    # Falls through to the Java-launching section below
}

# --- Run: find Java, find JAR, launch ---
$Java17Home = Find-Java17 -ExplicitHome $JavaHome
Write-Verbose "Java 17 home: $Java17Home"
$env:JAVA_HOME = $Java17Home

$JarPath = Find-WebMinerJar
if (-not $JarPath) {
    Write-Error @"
Cannot find scent-miner.jar.

Install the latest release:
  .\webminer.ps1 install

Or download manually from:
  GitHub : https://github.com/platonai/web-miner/releases
  OSS    : $OSS_BASE_URL/releases/latest/download/scent-miner.jar
"@
    exit 1
}

Write-Verbose "WebMiner JAR: $JarPath"

$exitCode = Invoke-WebMiner -JarPath $JarPath
exit $exitCode
