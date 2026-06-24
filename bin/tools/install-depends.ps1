#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Install or verify system dependencies for building Browser4 from source.

.DESCRIPTION
    Checks for and optionally installs all prerequisites listed in the
    "Build from Source" section of README.md:

      Git, JDK 17+ (21+ recommended, Eclipse Temurin), Maven 3.9+,
      PowerShell 7 (pwsh, on Linux/macOS), Chrome/Chromium,
      Rust (stable), Node.js + pnpm, and platform tools (tar, wget/curl).

    Runs in CHECK-ONLY mode by default.  Pass -Install to actually install
    missing dependencies.

.PARAMETER Install
    Install missing dependencies instead of only reporting them.

.PARAMETER SkipChrome
    Skip Chrome/Chromium installation (useful on headless servers).

.PARAMETER SkipRust
    Skip Rust installation (only needed for CLI builds).

.PARAMETER SkipNode
    Skip Node.js + pnpm installation (only needed for CLI packaging).

.EXAMPLE
    # Check what's missing (no changes made)
    ./install-depends.ps1

.EXAMPLE
    # Install everything
    ./install-depends.ps1 -Install

.EXAMPLE
    # Install everything except Chrome (headless CI)
    ./install-depends.ps1 -Install -SkipChrome
#>

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

param(
    [switch]$Install,
    [switch]$SkipChrome,
    [switch]$SkipRust,
    [switch]$SkipNode
)

$ErrorActionPreference = "Continue"

# ── Platform detection ─────────────────────────────────────────────
$IsWin = $IsWindows -or ($env:OS -eq 'Windows_NT')
$IsLin = $IsLinux
$IsMac = $IsMacOS

# ── Color helpers ──────────────────────────────────────────────────
function Write-OK   { Write-Host "  ✓ $args" -ForegroundColor Green }
function Write-Warn { Write-Host "  ⚠ $args" -ForegroundColor Yellow }
function Write-Fail { Write-Host "  ✗ $args" -ForegroundColor Red }
function Write-Hdr  { Write-Host "`n── $args ──" -ForegroundColor Cyan }

# ── Utility: test if a command exists ──────────────────────────────
function Test-Cmd($name) {
    return [bool](Get-Command $name -ErrorAction SilentlyContinue)
}

# ── Utility: get version string from a command ─────────────────────
function Get-Ver($cmd, [string]$args = '--version') {
    try {
        $out = & $cmd $args 2>&1 | Out-String
        return ($out -split "`n")[0].Trim()
    } catch { return $null }
}

# ── Utility: compare versions (returns $true if $actual >= $required)
function Test-MinVer([string]$actual, [string]$required) {
    if (-not $actual) { return $false }
    # Extract first X.Y.Z-like token
    $rx = [regex]'(\d+)\.(\d+)(?:\.(\d+))?'
    $a = $rx.Match($actual)
    $r = $rx.Match($required)
    if (-not $a.Success -or -not $r.Success) { return $false }
    $aMaj, $aMin, $aPat = [int]$a.Groups[1].Value, [int]$a.Groups[2].Value, [int]('0' + $a.Groups[3].Value)
    $rMaj, $rMin, $rPat = [int]$r.Groups[1].Value, [int]$r.Groups[2].Value, [int]('0' + $r.Groups[3].Value)
    if ($aMaj -ne $rMaj) { return $aMaj -gt $rMaj }
    if ($aMin -ne $rMin) { return $aMin -gt $rMin }
    return $aPat -ge $rPat
}

# ── Counter ────────────────────────────────────────────────────────
$missing = 0
$total   = 0
function Check($label, [scriptblock]$check, [scriptblock]$fix) {
    $script:total++
    $ok, $ver = & $check
    if ($ok) {
        Write-OK "$label $ver"
    } else {
        $script:missing++
        if ($ver) { Write-Fail "$label  (found: $ver)" }
        else      { Write-Fail "$label  (not found)" }
        if ($Install -and $fix) {
            Write-Host "    Installing..."
            & $fix
            $ok2, $ver2 = & $check
            if ($ok2) { Write-OK "  → installed: $ver2"; $script:missing-- }
            else      { Write-Fail "  → installation may have failed" }
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# 1. Git
# ═══════════════════════════════════════════════════════════════════
Write-Hdr "Git"
Check "Git" {
    if (Test-Cmd git) {
        $v = Get-Ver git
        return $true, $v
    }
    return $false, $null
} {
    if ($IsWin)  { winget install --id Git.Git -e --source winget 2>$null }
    elseif ($IsMac) { brew install git 2>$null }
    else {
        if   (Test-Cmd apt-get) { sudo apt-get install -y git }
        elseif (Test-Cmd dnf)   { sudo dnf install -y git }
        elseif (Test-Cmd pacman){ sudo pacman -S --noconfirm git }
    }
}

# ═══════════════════════════════════════════════════════════════════
# 2. JDK 17+  (Eclipse Temurin recommended; 21+ even better)
# ═══════════════════════════════════════════════════════════════════
Write-Hdr "JDK  (requires 17+, 21+ recommended)"
Check "JDK" {
    if (Test-Cmd java) {
        $v = Get-Ver java
        $ok = Test-MinVer $v '17'
        if ($ok) { return $true, $v } else { return $false, $v }
    }
    return $false, $null
} {
    $jdkVer = '21'  # install Temurin 21 (LTS)
    if ($IsWin) {
        winget install --id EclipseAdoptium.Temurin.21.JDK -e --source winget 2>$null
        # Refresh PATH for this session
        $env:Path = [System.Environment]::GetEnvironmentVariable("Path","Machine") + ";" +
                    [System.Environment]::GetEnvironmentVariable("Path","User")
    }
    elseif ($IsMac) {
        brew install --cask temurin@$jdkVer 2>$null
    }
    else {
        if (Test-Cmd apt-get) {
            sudo apt-get install -y wget apt-transport-https
            wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo apt-key add -
            sudo add-apt-repository -y "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/VERSION_CODENAME/{print$2}' /etc/os-release) main"
            sudo apt-get update && sudo apt-get install -y temurin-${jdkVer}-jdk
        }
        elseif (Test-Cmd dnf) {
            sudo dnf install -y temurin-${jdkVer}-jdk 2>$null
        }
        elseif (Test-Cmd pacman) {
            # Arch: use jdk-openjdk or jdk-temurin from AUR
            sudo pacman -S --noconfirm jdk-openjdk 2>$null
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# 3. Maven 3.9+  (the project ships mvnw, so this is advisory)
# ═══════════════════════════════════════════════════════════════════
Write-Hdr "Maven 3.9+  (advisory — mvnw wrapper is bundled)"
Check "Maven" {
    # Prefer the wrapper — this check is purely informational
    $mvnw = if ($IsWin) { '.\mvnw.cmd' } else { './mvnw' }
    $projRoot = Resolve-Path "$PSScriptRoot\..\.."
    if (Test-Path (Join-Path $projRoot $mvnw)) {
        return $true, "(mvnw wrapper available)"
    }
    if (Test-Cmd mvn) {
        $v = Get-Ver mvn
        $ok = Test-MinVer $v '3.9'
        if ($ok) { return $true, $v } else { return $false, $v }
    }
    return $false, $null
} {
    if ($IsWin)  { winget install --id Apache.Maven.3 -e --source winget 2>$null }
    elseif ($IsMac) { brew install maven 2>$null }
    else {
        if   (Test-Cmd apt-get) { sudo apt-get install -y maven }
        elseif (Test-Cmd dnf)   { sudo dnf install -y maven }
        elseif (Test-Cmd pacman){ sudo pacman -S --noconfirm maven }
    }
}

# ═══════════════════════════════════════════════════════════════════
# 4. PowerShell 7+ (pwsh) — required on Linux / macOS for jlink
# ═══════════════════════════════════════════════════════════════════
Write-Hdr "PowerShell 7+ (pwsh)  [required on Linux/macOS for jlink]"
if ($IsWin) {
    # pwsh itself is running this script, so it's already available
    $pwshVer = $PSVersionTable.PSVersion.ToString()
    if ([version]$PSVersionTable.PSVersion -ge [version]'7.0') {
        Write-OK "PowerShell  $pwshVer (this session)"
    } else {
        Write-Warn "PowerShell  running Windows PowerShell $pwshVer — install pwsh 7+ via winget:"
        Write-Warn "  winget install --id Microsoft.PowerShell -e --source winget"
    }
} else {
    Check "pwsh" {
        if (Test-Cmd pwsh) {
            $v = Get-Ver pwsh '-Command $PSVersionTable.PSVersion.ToString()'
            $ok = Test-MinVer $v '7.0'
            if ($ok) { return $true, $v } else { return $false, $v }
        }
        return $false, $null
    } {
        if ($IsMac) {
            brew install powershell 2>$null
        } else {
            # Official Microsoft install script
            curl -fsSL https://aka.ms/install-powershell.sh | sudo bash
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# 5. jdeps / jlink / jpackage  — bundled with JDK 16+
# ═══════════════════════════════════════════════════════════════════
Write-Hdr "JDK tools  (jdeps, jlink, jpackage — bundled with JDK)"
Check "jdeps" {
    if (Test-Cmd jdeps) {
        $v = Get-Ver jdeps  # jdeps --version prints to stderr
        $v2 = (& jdeps --version 2>&1 | Out-String).Trim()
        return $true, ($v2 -split "`n")[0].Trim()
    }
    return $false, $null
} {
    # These come with the JDK — nothing to install separately.
    # The JDK step above should have installed them.
    Write-Warn "jdeps not found. Ensure JDK 17+ is installed and JAVA_HOME/bin is on PATH."
}
Check "jlink" {
    if (Test-Cmd jlink) { return $true, (Get-Ver jlink) }
    return $false, $null
} {}
Check "jpackage" {
    if (Test-Cmd jpackage) { return $true, (Get-Ver jpackage) }
    return $false, $null
} {}

# ═══════════════════════════════════════════════════════════════════
# 6. Chrome / Chromium
# ═══════════════════════════════════════════════════════════════════
Write-Hdr "Chrome / Chromium  (latest)"
if ($SkipChrome) {
    Write-Warn "Skipped (--SkipChrome)"
} else {
    # Search paths from README.md auto-detection table
    $chromePaths = @()
    if ($IsWin) {
        $chromePaths = @(
            "${env:ProgramFiles}\Google\Chrome\Application\chrome.exe",
            "${env:ProgramFiles(x86)}\Google\Chrome\Application\chrome.exe"
        )
    } elseif ($IsMac) {
        $chromePaths = @(
            '/Applications/Google Chrome.app/Contents/MacOS/Google Chrome',
            '/Applications/Chromium.app/Contents/MacOS/Chromium'
        )
    } else {
        $chromePaths = @('/opt/google/chrome/chrome', '/usr/bin/google-chrome',
                         '/usr/bin/chromium-browser', '/usr/bin/chromium')
    }
    Check "Chrome" {
        $found = $chromePaths | Where-Object { Test-Path $_ } | Select-Object -First 1
        if ($found) {
            $v = try { (& $found --version 2>&1 | Out-String).Trim() } catch { 'unknown' }
            return $true, "$v  ($found)"
        }
        # Also check PATH
        $names = @('google-chrome', 'chromium-browser', 'chromium', 'chrome')
        foreach ($n in $names) {
            if (Test-Cmd $n) {
                $v = Get-Ver $n
                return $true, $v
            }
        }
        return $false, $null
    } {
        if ($IsWin) {
            winget install --id Google.Chrome -e --source winget 2>$null
        }
        elseif ($IsMac) {
            brew install --cask google-chrome 2>$null
        }
        else {
            if (Test-Cmd apt-get) {
                wget -q https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
                sudo dpkg -i google-chrome*.deb; sudo apt-get install -f -y
                rm -f google-chrome*.deb
            }
            elseif (Test-Cmd dnf) {
                sudo dnf install -y https://dl.google.com/linux/direct/google-chrome-stable_current_x86_64.rpm 2>$null
            }
            elseif (Test-Cmd pacman) {
                # chromium is in community
                sudo pacman -S --noconfirm chromium 2>$null
            }
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# 7. Rust  (stable, edition 2021) — only needed for CLI builds
# ═══════════════════════════════════════════════════════════════════
Write-Hdr "Rust  (stable — only needed for CLI build)"
if ($SkipRust) {
    Write-Warn "Skipped (--SkipRust)"
} else {
    Check "Rust" {
        if (Test-Cmd rustc) {
            $v = Get-Ver rustc
            return $true, $v
        }
        return $false, $null
    } {
        if ($IsWin) {
            winget install --id Rustlang.Rustup -e --source winget 2>$null
        }
        elseif ($IsMac) {
            curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y 2>$null
        }
        else {
            curl --proto '=https' --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y 2>$null
        }
        # Add cargo to PATH for this session
        $cargoHome = if ($env:CARGO_HOME) { $env:CARGO_HOME }
                     else { Join-Path $HOME '.cargo' }
        $env:Path = "$cargoHome\bin;$env:Path"
    }
}

# ═══════════════════════════════════════════════════════════════════
# 8. Node.js 24+ + pnpm 10+  — only needed for CLI packaging
# ═══════════════════════════════════════════════════════════════════
Write-Hdr "Node.js + pnpm  (only needed for CLI packaging)"
if ($SkipNode) {
    Write-Warn "Skipped (--SkipNode)"
} else {
    Check "Node.js" {
        if (Test-Cmd node) {
            $v = Get-Ver node
            $ok = Test-MinVer $v '24.0'
            if ($ok) { return $true, $v } else { return $false, "$v (need 24+)" }
        }
        return $false, $null
    } {
        if ($IsWin) {
            winget install --id OpenJS.NodeJS.LTS -e --source winget 2>$null
        }
        elseif ($IsMac) {
            brew install node@24 2>$null
        }
        else {
            # Use NodeSource or nvm. Try nvm first for flexibility.
            if (Test-Cmd nvm) {
                nvm install 24 2>$null
            } elseif (Test-Cmd fnm) {
                fnm install 24 2>$null
            } else {
                curl -fsSL https://deb.nodesource.com/setup_24.x | sudo -E bash -
                if (Test-Cmd apt-get) { sudo apt-get install -y nodejs }
                elseif (Test-Cmd dnf) { sudo dnf install -y nodejs }
            }
        }
    }
    Check "pnpm" {
        if (Test-Cmd pnpm) {
            $v = Get-Ver pnpm
            $ok = Test-MinVer $v '10.0'
            if ($ok) { return $true, $v } else { return $false, "$v (need 10+)" }
        }
        return $false, $null
    } {
        if (Test-Cmd npm) {
            npm install -g pnpm 2>$null
        } elseif (Test-Cmd corepack) {
            corepack enable && corepack prepare pnpm@latest --activate
        } else {
            # PowerShell-based install as fallback
            Invoke-WebRequest -Uri https://get.pnpm.io/install.ps1 | Invoke-Expression
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# 9. Platform tools  (tar, wget/curl on Linux; tar on macOS)
# ═══════════════════════════════════════════════════════════════════
Write-Hdr "Platform tools"
if ($IsWin) {
    # tar and curl ship with Windows 10 1803+; check anyway
    Check "tar" {
        if (Test-Cmd tar) { return $true, (Get-Ver tar) }
        return $false, $null
    } {}
    Check "curl" {
        if (Test-Cmd curl) { return $true, (Get-Ver curl) }
        return $false, $null
    } {}
} elseif ($IsMac) {
    Check "tar" {
        if (Test-Cmd tar) { return $true, "(built-in)" }
        return $false, $null
    } {}
} else {
    Check "tar" {
        if (Test-Cmd tar) { return $true, (Get-Ver tar) }
        return $false, $null
    } {
        if   (Test-Cmd apt-get) { sudo apt-get install -y tar }
        elseif (Test-Cmd dnf)   { sudo dnf install -y tar }
    }
    Check "curl/wget" {
        if ((Test-Cmd curl) -or (Test-Cmd wget)) {
            $t = if (Test-Cmd curl) { "curl $(Get-Ver curl)" } else { "wget $(Get-Ver wget)" }
            return $true, $t
        }
        return $false, $null
    } {
        if   (Test-Cmd apt-get) { sudo apt-get install -y curl }
        elseif (Test-Cmd dnf)   { sudo dnf install -y curl }
    }
}

# ═══════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════
Write-Host "`n========================================" -ForegroundColor Cyan
if ($missing -eq 0) {
    Write-Host "All $total prerequisites satisfied." -ForegroundColor Green
} else {
    Write-Host "$missing of $total prerequisite(s) missing." -ForegroundColor Yellow
    if (-not $Install) {
        Write-Host "Re-run with -Install to install missing dependencies." -ForegroundColor Cyan
    }
}
Write-Host "========================================" -ForegroundColor Cyan
exit (0, [math]::Min($missing, 1))[$missing -gt 0]
