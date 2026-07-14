<#
.SYNOPSIS
  Install browser4-cli -- download the native binary and set it up on your PATH.

.DESCRIPTION
  Detects your OS and CPU architecture, downloads the matching native binary from
  GitHub Releases or Alibaba Cloud OSS, installs it to a user-local directory, and
  optionally adds it to your PATH.

  Default install location:
    Windows:  $env:LOCALAPPDATA\Programs\browser4-cli
    (override with -InstallDir)

  Download sources (auto-selected by locale; use -Source to override):
    Outside China:  1. GitHub Releases  ->  2. Aliyun OSS
    China mainland: 1. Aliyun OSS        ->  2. GitHub Releases
    GitHub Releases -- https://github.com/platonai/Browser4
    Aliyun OSS       -- https://browser4.oss-cn-beijing.aliyuncs.com

.PARAMETER Version
  Release version tag to download (e.g. "v4.11.0" or "v0.1.12-cli").
  Defaults to "latest" which resolves to the most recent stable release.

.PARAMETER InstallDir
  Directory to install the binary into.
  Default: $env:LOCALAPPDATA\Programs\browser4-cli

.PARAMETER Source
  Force a specific download source: "github" or "oss".
  Default (auto): locale-aware -- OSS first in China mainland, GitHub first elsewhere.

.PARAMETER AddToPath
  Add the install directory to the current user's PATH environment variable.
  Default: true.

.PARAMETER Silent
  Suppress all non-error output.

.PARAMETER DryRun
  Print what would be done without actually doing it.

.PARAMETER SkipIfInstalled
  Skip download if the binary already exists at the install path.
  By default the script always downloads the latest version.

.PARAMETER SkipLocal
  Skip checking for a locally-bundled binary alongside the script.
  By default the script looks for the platform binary in its own directory
  before downloading -- use this to force a fresh download.

.PARAMETER Force
  Force reinstallation even if the binary is already installed at the target path.
  Overrides -SkipIfInstalled and bypasses locked-file workarounds.

.PARAMETER Locate
  Print detection results (OS, architecture, script location, China locale)
  and exit without installing. Useful for diagnostics.

.EXAMPLE
  # Quick install -- default location, latest version, add to PATH
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1

.EXAMPLE
  # Silent install with a specific version
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -Version "v4.11.0" -Silent

.EXAMPLE
  # Install from OSS only, custom directory
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -Source oss -InstallDir "C:\tools\browser4"

.EXAMPLE
  # Run diagnostics -- see what the script detects without installing
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -Locate

.EXAMPLE
  # Use a locally-bundled binary (place binary next to the script)
  # The script auto-detects binaries in its own directory
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1

.EXAMPLE
  # Force download even when a local binary exists
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -SkipLocal

.EXAMPLE
  # Skip download if already installed (opt out of default reinstall)
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -SkipIfInstalled

.EXAMPLE
  # For China mainland: OSS is auto-preferred via locale detection,
  # or force it explicitly
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -Source oss
#>

[CmdletBinding()]
param(
    [string]$Version = "",
    [string]$InstallDir = "",
    [ValidateSet("", "github", "oss")]
    [string]$Source = "",
    [bool]$AddToPath = $true,
    [switch]$Silent,
    [switch]$DryRun,
    [switch]$SkipIfInstalled,
    [switch]$SkipLocal,
    [switch]$Force,
    [switch]$Locate
)

$ErrorActionPreference = "Stop"

# ----------------------------------------------
# Script location -- find ourselves on disk
# ----------------------------------------------

# $PSScriptRoot is the directory containing this script (PS 3+).
# Falls back to $MyInvocation for edge cases (dot-sourced, PS 2).
$ScriptDir = if ($PSScriptRoot) {
    $PSScriptRoot
} elseif ($MyInvocation -and $MyInvocation.MyCommand.Path) {
    Split-Path -Parent $MyInvocation.MyCommand.Path
} else {
    $null
}

<#
.SYNOPSIS
  Search for a pre-downloaded binary near the script (bundled/sideload install).
  Returns the full path if found, $null otherwise.
#>
function Find-LocalBinary {
    param([string]$BinaryName)

    if (-not $ScriptDir) { return $null }

    $localPath = Join-Path $ScriptDir $BinaryName
    if (Test-Path $localPath -PathType Leaf) {
        $size = (Get-Item $localPath).Length
        if ($size -gt 102400) {  # > 100 KB minimum
            return $localPath
        }
    }
    return $null
}

<#
.SYNOPSIS
  Check whether a local binary is usable by querying its version.
  Returns $true if --version executes successfully, $false otherwise.
#>
function Test-LocalBinary {
    param([string]$Path)

    if (-not $Path -or -not (Test-Path $Path)) { return $false }

    try {
        $null = & $Path --version 2>&1
        return ($LASTEXITCODE -eq 0)
    } catch {
        return $false
    }
}

# ----------------------------------------------
# OS detection (compatible with PS 5.1+)
# ----------------------------------------------

# Avoid assigning to $IsLinux / $IsWindows / $IsMacOS directly --
# they are read-only automatic variables in PowerShell 7+.
if ($PSVersionTable.PSVersion.Major -ge 6) {
    $script:OSWin   = $IsWindows
    $script:OSLinux = $IsLinux
    $script:OSMac   = $IsMacOS
} else {
    $script:OSWin   = [System.Environment]::OSVersion.Platform -eq "Win32NT"
    $script:OSMac   = $false
    $script:OSLinux = $false
}

# ----------------------------------------------
# Helpers
# ----------------------------------------------

function Write-Summary {
    param([string]$Message, [string]$Color = "White")
    if (-not $Silent) { Write-Host $Message -ForegroundColor $Color }
}

function Write-Step {
    param([string]$Message)
    if (-not $Silent) { Write-Host "  >> $Message" -ForegroundColor Gray }
}

function Write-Check {
    param([string]$Message)
    if (-not $Silent) { Write-Host "    [v] $Message" -ForegroundColor Green }
}

function Write-WarnMsg {
    param([string]$Message)
    if (-not $Silent) { Write-Host "    [!] $Message" -ForegroundColor Yellow }
}

# ----------------------------------------------
# Detection
# ----------------------------------------------

function Get-PlatformKey {
    $arch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq [System.Runtime.InteropServices.Architecture]::Arm64) { "arm64" } else { "x64" }

    if ($script:OSWin) {
        return "win32-$arch"
    }
    elseif ($script:OSMac) {
        return "darwin-$arch"
    }
    elseif ($script:OSLinux) {
        # Detect musl
        $isMusl = $false
        try {
            $lddOutput = ldd --version 2>&1
            if ($lddOutput -match "musl") { $isMusl = $true }
        } catch {
            if ((Test-Path "/lib/ld-musl-x86_64.so.1") -or (Test-Path "/lib/ld-musl-aarch64.so.1")) {
                $isMusl = $true
            }
        }
        $libc = if ($isMusl) { "musl" } else { "" }
        if ($libc) { return "linux-$libc-$arch" } else { return "linux-$arch" }
    }
    else {
        throw "Unsupported OS. Browser4 CLI supports Windows, macOS, and Linux."
    }
}

function Get-BinaryName {
    param([string]$PlatformKey)
    return "browser4-cli-$PlatformKey" + $(if ($PlatformKey.StartsWith("win32")) { ".exe" } else { "" })
}

function Get-DefaultInstallDir {
    if ($script:OSWin) {
        return Join-Path $env:LOCALAPPDATA "Programs\browser4-cli"
    }
    elseif ($script:OSLinux -or $script:OSMac) {
        # Prefer ~/.local/bin for user installs
        if (Test-Path "$env:HOME/.local/bin") {
            return "$env:HOME/.local/bin"
        }
        return "$env:HOME/.local/bin"
    }
    throw "Unsupported OS"
}

# ----------------------------------------------
# China mainland locale detection (zero-network)
# ----------------------------------------------

<#
.SYNOPSIS
  Detect whether the current system is likely in China mainland.
  Uses only local env vars and .NET APIs -- no network calls.
#>
function Test-ChinaLocale {
    # 1 -- Locale env vars
    $lang = $env:LC_ALL, $env:LANG, $env:LC_CTYPE, $env:LC_MESSAGES | Where-Object { $_ } | Select-Object -First 1
    if ($lang -and ($lang -match '^zh_CN' -or $lang -match '^zh-CN' -or $lang -match '^Chinese \(Simplified\)_China')) {
        return $true
    }

    # 2 -- TZ env var
    $tzEnv = $env:TZ
    if ($tzEnv -and ($tzEnv -match '^Asia/(Shanghai|Chongqing|Urumqi|Harbin)$')) {
        return $true
    }

    # 3 -- .NET TimeZoneInfo (works on Windows and Unix PowerShell 7+)
    try {
        $tzId = [System.TimeZoneInfo]::Local.Id
        if ($tzId -match '^Asia/(Shanghai|Chongqing|Urumqi|Harbin)$') {
            return $true
        }
    } catch {
        # TimeZoneInfo not available (unlikely on PS 5.1+ but guard anyway)
    }

    # 4 -- /etc/timezone (PowerShell on Linux/macOS)
    if (-not $script:OSWin -and (Test-Path '/etc/timezone')) {
        try {
            $tz = Get-Content '/etc/timezone' -Raw -ErrorAction Stop
            if ($tz -match '^Asia/(Shanghai|Chongqing|Urumqi|Harbin)$') {
                return $true
            }
        } catch {
            # Permission or read error -- skip
        }
    }

    return $false
}

# ----------------------------------------------
# Download URLs
# ----------------------------------------------

$GITHUB_REPO = "platonai/Browser4"
$OSS_BASE = "https://browser4.oss-cn-beijing.aliyuncs.com"
$script:ChinaDetected = $false

function Get-DownloadUrls {
    param([string]$BinaryName, [string]$VersionTag)

    $urls = @()

    $ghBase = "https://github.com/$GITHUB_REPO/releases/download"
    if ($VersionTag) {
        $ghUrl = "$ghBase/$VersionTag/$BinaryName"
        $ossUrl = "$OSS_BASE/releases/download/$VersionTag/$BinaryName"
    } else {
        # Use 'latest' redirect for GitHub, 'latest' symlink for OSS
        $ghUrl = "https://github.com/$GITHUB_REPO/releases/latest/download/$BinaryName"
        $ossUrl = "$OSS_BASE/releases/download/latest/$BinaryName"
    }

    if ($Source -eq "github") {
        $urls += @{ Url = $ghUrl; Label = "GitHub Releases" }
    } elseif ($Source -eq "oss") {
        $urls += @{ Url = $ossUrl; Label = "Aliyun OSS" }
    } else {
        if ($script:ChinaDetected) {
            $urls += @{ Url = $ossUrl; Label = "Aliyun OSS" }
            $urls += @{ Url = $ghUrl; Label = "GitHub Releases" }
        } else {
            $urls += @{ Url = $ghUrl; Label = "GitHub Releases" }
            $urls += @{ Url = $ossUrl; Label = "Aliyun OSS" }
        }
    }

    return $urls
}

function Invoke-Download {
    param([string]$Url, [string]$OutFile, [string]$Label)

    Write-Step "Trying $Label..."
    Write-Step "URL: $Url"

    if ($DryRun) {
        Write-Check "[DRY-RUN] Would download to: $OutFile"
        return $true
    }

    try {
        $ProgressPreference = if ($Silent) { "SilentlyContinue" } else { "Continue" }

        # Use Invoke-WebRequest with progress bar
        Invoke-WebRequest -Uri $Url -OutFile $OutFile -UseBasicParsing -ErrorAction Stop

        if (Test-Path $OutFile) {
            $size = (Get-Item $OutFile).Length
            if ($size -gt 102400) {  # > 100 KB minimum
                Write-Check "Downloaded $( [math]::Round($size / 1MB, 1) ) MB"
                return $true
            } else {
                Write-WarnMsg "Downloaded file too small ($size bytes) -- may be an error page"
                Remove-Item $OutFile -Force -ErrorAction SilentlyContinue
                return $false
            }
        }
        Write-WarnMsg "Download appeared to succeed but file not found"
        return $false
    } catch {
        Write-WarnMsg "Failed: $($_.Exception.Message)"
        return $false
    }
}

# ----------------------------------------------
# Symlinks
# ----------------------------------------------

function New-PlatformLink {
    param([string]$LinkPath, [string]$TargetName, [string]$DisplayName)

    # Try symbolic link first (works on Unix; on Windows needs Admin or Developer Mode)
    try {
        New-Item -ItemType SymbolicLink -Path $LinkPath -Target $TargetName -Force -ErrorAction Stop | Out-Null
        Write-Check "Created symlink: $DisplayName -> $TargetName"
        return $true
    } catch {
        # Symbolic link failed -- try hard link on Windows
    }

    # Try hard link (Windows, same volume)
    if ($script:OSWin) {
        try {
            $targetPath = Join-Path (Split-Path $LinkPath -Parent) $TargetName
            New-Item -ItemType HardLink -Path $LinkPath -Target $targetPath -Force -ErrorAction Stop | Out-Null
            Write-Check "Created hard link: $DisplayName -> $TargetName"
            return $true
        } catch {
            # Hard link also failed -- create a .cmd wrapper as last resort
        }

        # Last resort: .cmd wrapper that forwards all arguments
        try {
            $wrapperContent = '@"%~dp0' + $TargetName + '" %*'
            Set-Content -Path ($LinkPath -replace '\.exe$', '.cmd') -Value $wrapperContent -Force -ErrorAction Stop
            Write-Check "Created wrapper: " + (Split-Path ($LinkPath -replace '\.exe$', '.cmd') -Leaf) + " -> $TargetName"
            return $true
        } catch {
            Write-WarnMsg "Could not create link for $DisplayName (may need admin privileges)"
            return $false
        }
    }

    Write-WarnMsg "Could not create link for $DisplayName"
    return $false
}

function New-Symlinks {
    param([string]$BinaryName, [string]$InstallDir, [string]$PlatformKey)

    $ext = if ($PlatformKey.StartsWith("win32")) { ".exe" } else { "" }

    # 1) Always: browser4-cli -> browser4-cli-<platform>
    $linkName = "browser4-cli$ext"
    $linkPath = Join-Path $InstallDir $linkName

    if ($DryRun) {
        Write-Step "[DRY-RUN] Would create link: $linkName -> $BinaryName"
    } else {
        New-PlatformLink -LinkPath $linkPath -TargetName $BinaryName -DisplayName $linkName
    }

    # 2) Only if no conflict: b4 -> browser4-cli-<platform>
    $shortName = "b4$ext"
    $shortPath = Join-Path $InstallDir $shortName

    # Check if b4 is already on PATH (conflict with another tool)
    $existingCmd = Get-Command b4 -ErrorAction SilentlyContinue
    if ($existingCmd) {
        Write-WarnMsg "Skipping short link '$shortName': 'b4' already found on PATH ($($existingCmd.Source))"
        return
    }

    # Check if b4 already exists in the install directory
    if (Test-Path $shortPath) {
        Write-WarnMsg "Skipping short link '$shortName': already exists in $InstallDir"
        return
    }

    # Also check b4.cmd if on Windows (wrapper fallback)
    if ($script:OSWin) {
        $shortCmdPath = Join-Path $InstallDir "b4.cmd"
        if (Test-Path $shortCmdPath) {
            Write-WarnMsg "Skipping short link '$shortName': 'b4.cmd' already exists in $InstallDir"
            return
        }
    }

    if ($DryRun) {
        Write-Step "[DRY-RUN] Would create link: $shortName -> $BinaryName"
    } else {
        New-PlatformLink -LinkPath $shortPath -TargetName $BinaryName -DisplayName $shortName
    }
}

# ----------------------------------------------
# Helper: replace a binary that may be locked (i.e. currently running)
# ----------------------------------------------

function Set-BinaryFile {
  param(
    [Parameter(Mandatory=$true)] [string]$TargetPath,
    [Parameter(Mandatory=$true)] [string]$SourcePath,
    [Parameter(Mandatory=$false)] [switch]$Move  # $true = move temp file, $false = copy
  )

  if (Test-Path $TargetPath) {
    # Remove any stale .old from a previous upgrade
    $oldPath = "$TargetPath.old"
    try {
      if (Test-Path $oldPath) { Remove-Item $oldPath -Force -ErrorAction Stop }
    } catch { }

    try {
      Remove-Item $TargetPath -Force -ErrorAction Stop
    } catch {
      # The binary is locked (likely the currently-running process).
      # On Windows we can rename a running executable -- move the old binary
      # out of the way, place the new one alongside, and clean up the old one
      # on the next install/upgrade.
      try {
        Move-Item $TargetPath $oldPath -Force -ErrorAction Stop
        Write-WarnMsg "Existing binary is locked (it may be running)."
        Write-WarnMsg "The old copy will be cleaned up on the next install or upgrade."
      } catch {
        throw "Cannot replace '$TargetPath' -- it is locked and cannot be renamed. Close all browser4-cli processes and try again."
      }
    }
  }

  # Place the new binary at the target path
  if ($Move) {
    Move-Item $SourcePath $TargetPath -Force -ErrorAction Stop
  } else {
    Copy-Item $SourcePath $TargetPath -Force -ErrorAction Stop
  }
}

# ----------------------------------------------
# PATH management
# ----------------------------------------------

function Add-DirectoryToUserPath {
    param([string]$Dir)

    $dirResolved = (Resolve-Path $Dir -ErrorAction SilentlyContinue).Path
    if (-not $dirResolved) { $dirResolved = $Dir }

    # Read current user PATH
    $currentUserPath = [System.Environment]::GetEnvironmentVariable("Path", [System.EnvironmentVariableTarget]::User)
    $paths = if ($currentUserPath) { $currentUserPath -split ";" | Where-Object { $_ } } else { @() }

    # Check if already present
    $normalized = $paths | ForEach-Object { $rp = Resolve-Path $_ -ErrorAction SilentlyContinue; if ($rp) { $rp.Path } else { $_ } }
    if ($normalized -contains $dirResolved) {
        Write-Check "Already in user PATH: $Dir"
        return
    }

    if ($DryRun) {
        Write-Check "[DRY-RUN] Would add to user PATH: $Dir"
        return
    }

    $newPath = if ($currentUserPath) { "$currentUserPath;$Dir" } else { $Dir }
    [System.Environment]::SetEnvironmentVariable("Path", $newPath, [System.EnvironmentVariableTarget]::User)
    Write-Check "Added to user PATH: $Dir"

    # Also update current session
    $env:Path = "$env:Path;$Dir"
}

# ----------------------------------------------
# Main
# ----------------------------------------------

function Main {
    Write-Summary "==========================================" -Color Cyan
    Write-Summary "    browser4-cli Installer" -Color Cyan
    Write-Summary "==========================================" -Color Cyan
    Write-Summary ""

    # Auto-detect China mainland locale when no explicit source is given
    if (-not $Source) {
        $script:ChinaDetected = Test-ChinaLocale
        if ($script:ChinaDetected) {
            Write-Step "China mainland locale detected: preferring Aliyun OSS mirror."
        }
    }

    # Detect platform
    $platformKey = Get-PlatformKey
    $binaryName = Get-BinaryName -PlatformKey $platformKey

    # -- Locate mode: print diagnostics and exit --
    if ($Locate) {
        Write-Summary "--- Locate / diagnostics ---" -Color Cyan
        Write-Summary ""
        Write-Step "Script dir:       $ScriptDir"
        Write-Step "Platform key:     $platformKey"
        Write-Step "Binary name:      $binaryName"
        Write-Step "Default install:  $(Get-DefaultInstallDir)"
        Write-Step "China locale:     $script:ChinaDetected"
        Write-Step "Source override:  $(if ($Source) { $Source } else { 'auto' })"
        Write-Step "OS:               $(if ($script:OSWin) { 'Windows' } elseif ($script:OSMac) { 'macOS' } elseif ($script:OSLinux) { 'Linux' } else { 'Unknown' })"

        # Check for local binary
        $localPath = Find-LocalBinary -BinaryName $binaryName
        if ($localPath) {
            $localOk = Test-LocalBinary -Path $localPath
            Write-Step "Local binary:     $localPath $(if ($localOk) { '(valid)' } else { '(present but --version failed)' })"
        } else {
            Write-Step "Local binary:     not found alongside script"
        }

        # Check for already-installed binary
        $defaultDir = Get-DefaultInstallDir
        $existingPath = Join-Path $defaultDir $binaryName
        if (Test-Path $existingPath) {
            Write-Step "Already installed: $existingPath"
        } else {
            Write-Step "Already installed: not found at $defaultDir"
        }

        # Show download URLs that would be tried
        $urls = Get-DownloadUrls -BinaryName $binaryName -VersionTag $Version
        Write-Summary ""
        Write-Step "Download order:"
        foreach ($entry in $urls) {
            Write-Step "  $($entry.Label): $($entry.Url)"
        }

        Write-Summary ""
        return
    }

    Write-Step "Platform:  $platformKey"
    Write-Step "Binary:    $binaryName"

    # Determine install directory
    $installDir = if ($InstallDir) { $InstallDir } else { Get-DefaultInstallDir }
    Write-Step "Install:   $installDir"
    Write-Summary ""

    if (-not (Test-Path $installDir)) {
        if (-not $DryRun) {
            New-Item -ItemType Directory -Path $installDir -Force | Out-Null
        }
        Write-Step "Created directory: $installDir"
    }

    $binaryPath = Join-Path $installDir $binaryName

    # -- Local binary discovery (bundled/sideload) --
    $useLocalBinary = $false
    if (-not $SkipLocal) {
        $localBinaryPath = Find-LocalBinary -BinaryName $binaryName
        if ($localBinaryPath) {
            Write-Step "Found local binary alongside script: $(Split-Path $localBinaryPath -Leaf)"
            if (Test-LocalBinary -Path $localBinaryPath) {
                Write-Check "Local binary verified (--version OK)"
                $useLocalBinary = $true
            } else {
                Write-WarnMsg "Local binary found but --version check failed -- will download instead"
            }
        }
    } elseif ($SkipLocal) {
        Write-Step "Skipping local binary check (-SkipLocal)"
    }

    # Skip download only when --skip-if-installed is set and binary already exists,
    # unless --force overrides it.
    if ((Test-Path $binaryPath) -and (-not $Version) -and $SkipIfInstalled -and (-not $Force) -and (-not $useLocalBinary)) {
        Write-Check "Binary already installed: $binaryPath"
    } elseif ($useLocalBinary) {
        # Copy local binary to install dir
        if (-not $DryRun) {
            Set-BinaryFile -TargetPath $binaryPath -SourcePath $localBinaryPath
        }
        Write-Check "Installed (local): $binaryPath"
    } else {
        # Build download URLs
        $urls = Get-DownloadUrls -BinaryName $binaryName -VersionTag $Version
        if (-not $urls -or $urls.Count -eq 0) {
            throw "No download URLs configured"
        }

        $downloaded = $false
        $tempFile = [System.IO.Path]::GetTempFileName()

        foreach ($entry in $urls) {
            if (Invoke-Download -Url $entry.Url -OutFile $tempFile -Label $entry.Label) {
                $downloaded = $true
                break
            }
        }

        if (-not $downloaded) {
            if (Test-Path $tempFile) { Remove-Item $tempFile -Force }
            throw @"
Could not download browser4-cli binary.

Tried:
$($urls | ForEach-Object { "  - $($_.Label): $($_.Url)" } | Out-String)

Please check:
  - Network connectivity
  - The version/tag exists: $Version
  - For GitHub rate limits, set GITHUB_TOKEN environment variable
  - If you have a local copy, place it alongside this script and re-run
"@
        }

        # Move from temp to install dir
        if (-not $DryRun) {
            Set-BinaryFile -TargetPath $binaryPath -SourcePath $tempFile -Move
        }
        Write-Check "Installed: $binaryPath"
    }

    # On Unix, ensure executable bit
    if (-not $script:OSWin) {
        if (-not $DryRun) {
            try { chmod +x $binaryPath 2>$null } catch { }
        }
    }

    # Create symlinks (browser4-cli -> platform binary, b4 if no conflict)
    Write-Summary ""
    New-Symlinks -BinaryName $binaryName -InstallDir $installDir -PlatformKey $platformKey

    # Add to PATH
    if ($AddToPath -and $script:OSWin) {
        Write-Summary ""
        Add-DirectoryToUserPath -Dir $installDir
    } elseif ($AddToPath -and -not $script:OSWin) {
        Write-Summary ""
        $shellRc = if (Test-Path "$env:HOME/.zshrc") { "$env:HOME/.zshrc" } elseif (Test-Path "$env:HOME/.bashrc") { "$env:HOME/.bashrc" } elseif (Test-Path "$env:HOME/.bash_profile") { "$env:HOME/.bash_profile" } else { "$env:HOME/.profile" }
        $pathLine = "export PATH=""$installDir`:`$PATH"""
        if (-not $DryRun) {
            if (-not (Select-String -Path $shellRc -Pattern [regex]::Escape($installDir) -ErrorAction SilentlyContinue)) {
                Add-Content -Path $shellRc -Value ""
                Add-Content -Path $shellRc -Value "# browser4-cli"
                Add-Content -Path $shellRc -Value $pathLine
                Write-Check "Added to PATH in $shellRc"
            } else {
                Write-Check "PATH entry already in $shellRc"
            }
        } else {
            Write-Check "[DRY-RUN] Would add to $shellRc"
        }
    }

    # Verify
    Write-Summary ""
    if (-not $DryRun) {
        try {
            $versionOutput = & $binaryPath --version 2>&1
            Write-Summary "[v] browser4-cli installed successfully" -Color Green
            Write-Summary "  Version: $versionOutput"
        } catch {
            Write-Summary "[v] Binary installed at: $binaryPath" -Color Green
            Write-WarnMsg "Could not verify --version (this is normal on first install)"
        }
    } else {
        Write-Summary "[DRY-RUN] Installation plan complete" -Color Yellow
    }

    Write-Summary ""
    Write-Summary "Run 'browser4-cli --help' to get started." -Color Cyan

    if ($script:OSWin) {
        Write-Summary "If the command isn't found, restart your terminal or run:"
        Write-Summary "  `$env:Path = [System.Environment]::GetEnvironmentVariable('Path','User') + ';' + [System.Environment]::GetEnvironmentVariable('Path','Machine')"
    }
}

Main
