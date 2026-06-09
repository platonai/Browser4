<#
.SYNOPSIS
  Install browser4-cli — download the native binary and set it up on your PATH.

.DESCRIPTION
  Detects your OS and CPU architecture, downloads the matching native binary from
  GitHub Releases or Alibaba Cloud OSS, installs it to a user-local directory, and
  optionally adds it to your PATH.

  Default install location:
    Windows:  $env:LOCALAPPDATA\Programs\browser4-cli
    (override with -InstallDir)

  Download sources (tried in order unless -Source is specified):
    1. GitHub Releases   — https://github.com/platonai/Browser4
    2. Aliyun OSS        — https://browser4.oss-cn-beijing.aliyuncs.com

.PARAMETER Version
  Release version tag to download (e.g. "v4.11.0" or "v0.1.12-cli").
  Defaults to "latest" which resolves to the most recent stable release.

.PARAMETER InstallDir
  Directory to install the binary into.
  Default: $env:LOCALAPPDATA\Programs\browser4-cli

.PARAMETER Source
  Force a specific download source: "github" or "oss".
  Default: try GitHub first, fall back to OSS.

.PARAMETER AddToPath
  Add the install directory to the current user's PATH environment variable.
  Default: true.

.PARAMETER Silent
  Suppress all non-error output.

.PARAMETER DryRun
  Print what would be done without actually doing it.

.EXAMPLE
  # Quick install — default location, latest version, add to PATH
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1

.EXAMPLE
  # Silent install with a specific version
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -Version "v4.11.0" -Silent

.EXAMPLE
  # Install from OSS only, custom directory
  powershell -ExecutionPolicy Bypass -File install-browser4-cli.ps1 -Source oss -InstallDir "C:\tools\browser4"
#>

[CmdletBinding()]
param(
  [string]$Version = "",
  [string]$InstallDir = "",
  [ValidateSet("github", "oss")]
  [string]$Source = "",
  [bool]$AddToPath = $true,
  [switch]$Silent,
  [switch]$DryRun
)

$ErrorActionPreference = "Stop"

# ──────────────────────────────────────────────
# OS detection (compatible with PS 5.1+)
# ──────────────────────────────────────────────

$script:IsWin = [System.Environment]::OSVersion.Platform -eq "Win32NT"
$script:IsMac = [System.Environment]::OSVersion.Platform -eq "Unix" -and (uname -s 2>$null) -eq "Darwin"
$script:IsLinux = [System.Environment]::OSVersion.Platform -eq "Unix" -and (uname -s 2>$null) -ne "Darwin"

# ──────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────

function Write-Summary {
  param([string]$Message, [string]$Color = "White")
  if (-not $Silent) { Write-Host $Message -ForegroundColor $Color }
}

function Write-Step {
  param([string]$Message)
  if (-not $Silent) { Write-Host "  » $Message" -ForegroundColor Gray }
}

function Write-Check {
  param([string]$Message)
  if (-not $Silent) { Write-Host "    ✓ $Message" -ForegroundColor Green }
}

function Write-WarnMsg {
  param([string]$Message)
  if (-not $Silent) { Write-Host "    ⚠ $Message" -ForegroundColor Yellow }
}

# ──────────────────────────────────────────────
# Detection
# ──────────────────────────────────────────────

function Get-PlatformKey {
  $arch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq [System.Runtime.InteropServices.Architecture]::Arm64) { "arm64" } else { "x64" }

  if ($script:IsWin) {
    return "win32-$arch"
  }
  elseif ($script:IsMac) {
    return "darwin-$arch"
  }
  elseif ($script:IsLinux) {
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
  if ($script:IsWin) {
    return Join-Path $env:LOCALAPPDATA "Programs\browser4-cli"
  }
  elseif ($script:IsLinux -or $script:IsMac) {
    # Prefer ~/.local/bin for user installs
    if (Test-Path "$env:HOME/.local/bin") {
      return "$env:HOME/.local/bin"
    }
    return "$env:HOME/.local/bin"
  }
  throw "Unsupported OS"
}

# ──────────────────────────────────────────────
# Download URLs
# ──────────────────────────────────────────────

$GITHUB_REPO = "platonai/Browser4"
$OSS_BASE = "https://browser4.oss-cn-beijing.aliyuncs.com"

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
    $urls += @{ Url = $ghUrl; Label = "GitHub Releases" }
    $urls += @{ Url = $ossUrl; Label = "Aliyun OSS" }
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
        Write-WarnMsg "Downloaded file too small ($size bytes) — may be an error page"
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

# ──────────────────────────────────────────────
# PATH management
# ──────────────────────────────────────────────

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

# ──────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────

function Main {
  Write-Summary "╔════════════════════════════════════════╗" -Color Cyan
  Write-Summary "║   browser4-cli Installer               ║" -Color Cyan
  Write-Summary "╚════════════════════════════════════════╝" -Color Cyan
  Write-Summary ""

  # Detect platform
  $platformKey = Get-PlatformKey
  $binaryName = Get-BinaryName -PlatformKey $platformKey
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

  # If binary already exists and no version override, skip download
  if ((Test-Path $binaryPath) -and (-not $Version)) {
    Write-Check "Binary already installed: $binaryPath"
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
"@
    }

    # Move from temp to install dir
    if (-not $DryRun) {
      if (Test-Path $binaryPath) { Remove-Item $binaryPath -Force }
      Move-Item $tempFile $binaryPath -Force
    }
    Write-Check "Installed: $binaryPath"
  }

  # On Unix, ensure executable bit
  if (-not $script:IsWin) {
    if (-not $DryRun) {
      try { chmod +x $binaryPath 2>$null } catch { }
    }
  }

  # Add to PATH
  if ($AddToPath -and $script:IsWin) {
    Write-Summary ""
    Add-DirectoryToUserPath -Dir $installDir
  } elseif ($AddToPath -and -not $script:IsWin) {
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
      Write-Summary "✓ browser4-cli installed successfully" -Color Green
      Write-Summary "  Version: $versionOutput"
    } catch {
      Write-Summary "✓ Binary installed at: $binaryPath" -Color Green
      Write-WarnMsg "Could not verify --version (this is normal on first install)"
    }
  } else {
    Write-Summary "[DRY-RUN] Installation plan complete" -Color Yellow
  }

  Write-Summary ""
  Write-Summary "Run 'browser4-cli --help' to get started." -Color Cyan

  if ($script:IsWin) {
    Write-Summary "If the command isn't found, restart your terminal or run:"
    Write-Summary "  `$env:Path = [System.Environment]::GetEnvironmentVariable('Path','User') + ';' + [System.Environment]::GetEnvironmentVariable('Path','Machine')"
  }
}

Main
