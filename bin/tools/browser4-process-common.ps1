#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

# Match Browser4 started either from fat-jar or Spring Boot launcher main class.
$script:Browser4CmdPattern = '(?i)(Browser4\.jar|\bBrowser4LauncherKt\b)'
$script:Browser4MarkerPidFileName = 'launcher.pid'
$script:Browser4MaxMarkerSearchDepth = 6

function Get-Browser4JavaProcesses {
    Get-CimInstance Win32_Process | Where-Object {
        $_.Name -match '^(java|javaw)\.exe$' -and
        -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and
        $_.CommandLine -match $script:Browser4CmdPattern
    }
}

function Normalize-Browser4ProcessText {
    param([Parameter(Mandatory)][string]$Text)

    $Text.Replace('\\', '/').ToLowerInvariant()
}

function Test-Browser4BrowserProcessName {
    param([Parameter(Mandatory)][string]$Name)

    $normalized = Normalize-Browser4ProcessText -Text $Name
    @('chrome', 'chrome.exe', 'chromium', 'chromium-browser', 'msedge', 'msedge.exe') -contains $normalized
}

function Get-Browser4ChromeProcesses {
    $pidMap = @{}

    foreach ($proc in Get-PulsarChromeProcesses) {
        $pidMap[[string]$proc.ProcessId] = $proc
    }

    foreach ($processId in Get-Browser4ChromePidsFromMarkers) {
        $key = [string]$processId
        if (-not $pidMap.ContainsKey($key)) {
            $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $processId" -ErrorAction SilentlyContinue
            if ($proc) {
                $pidMap[$key] = $proc
            }
        }
    }

    $pidMap.Values | Sort-Object ProcessId
}

function Get-PulsarChromeProcesses {
    # IMPORTANT: Get-CimInstance -Filter uses WQL syntax — operators are
    # OR / AND / NOT (no dashes).  PowerShell-style -or / -and / -not are
    # silently accepted but ALWAYS return zero results.
    Get-CimInstance Win32_Process -Filter "Name = 'chrome.exe' OR Name = 'chromium.exe' OR Name = 'msedge.exe'" -ErrorAction SilentlyContinue | Where-Object {
        -not [string]::IsNullOrWhiteSpace($_.CommandLine) -and (
            $_.CommandLine -match 'PULSAR_CHROME' -or
            $_.CommandLine -match 'browser4[\\/]browser[\\/]chrome' -or
            $_.CommandLine -match 'browser4-apps'
        )
    }
}

function Get-Browser4ChromePidsFromMarkers {
    $entries = Collect-Browser4MarkerEntries -Roots (Get-Browser4MarkerSearchRoots)
    foreach ($entry in $entries) {
        if (Test-Browser4MarkerEntryMatchesProcess -Entry $entry) {
            $entry.Pid
        }
    }
}

function Get-Browser4MarkerSearchRoots {
    $roots = @()

    if ($env:USERPROFILE) {
        $roots += (Join-Path $env:USERPROFILE '.browser4\\browser\\chrome')
    }

    $tempDir = [System.IO.Path]::GetTempPath()
    Get-ChildItem -Path $tempDir -Directory -ErrorAction SilentlyContinue | ForEach-Object {
        if ($_.Name -like 'browser4*') {
            $roots += (Join-Path $_.FullName 'context')
            $roots += (Join-Path $_.FullName 'browser\\chrome')
        }
    }

    $roots |
        Where-Object { Test-Path $_ -PathType Container } |
        Sort-Object -Unique
}

function Collect-Browser4MarkerEntries {
    param([string[]]$Roots)

    $entries = @()
    foreach ($root in $Roots) {
        Collect-Browser4MarkerEntriesUnder -Dir $root -Depth 0 -Entries ([ref]$entries)
    }

    $entries |
        Sort-Object @{ Expression = { $_.Pid } }, @{ Expression = { $_.MarkerDir } } -Unique
}

function Collect-Browser4MarkerEntriesUnder {
    param(
        [Parameter(Mandatory)][string]$Dir,
        [Parameter(Mandatory)][int]$Depth,
        [Parameter(Mandatory)][ref]$Entries
    )

    if ($Depth -gt $script:Browser4MaxMarkerSearchDepth) {
        return
    }

    $children = Get-ChildItem -Path $Dir -ErrorAction SilentlyContinue
    if (-not $children) {
        return
    }

    $subdirs = @()
    $markerFoundInDir = $false

    foreach ($child in $children) {
        if ($child.PSIsContainer) {
            $subdirs += $child.FullName
            continue
        }

        if ($child.Name -eq $script:Browser4MarkerPidFileName) {
            $markerPid = Read-Browser4MarkerPid -Path $child.FullName
            if ($markerPid) {
                $Entries.Value += [pscustomobject]@{
                    Pid = $markerPid
                    MarkerDir = $Dir
                }
            }
            $markerFoundInDir = $true
        }
    }

    if ($markerFoundInDir) {
        return
    }

    foreach ($subdir in $subdirs) {
        Collect-Browser4MarkerEntriesUnder -Dir $subdir -Depth ($Depth + 1) -Entries $Entries
    }
}

function Read-Browser4MarkerPid {
    param([Parameter(Mandatory)][string]$Path)

    $raw = Get-Content -Path $Path -Raw -ErrorAction SilentlyContinue
    if (-not $raw) {
        return $null
    }

    $parsedPid = 0
    if ([int]::TryParse($raw.Trim(), [ref]$parsedPid)) {
        return $parsedPid
    }

    $null
}

function Test-Browser4MarkerEntryMatchesProcess {
    param([Parameter(Mandatory)]$Entry)

    $proc = Get-CimInstance Win32_Process -Filter "ProcessId = $($Entry.Pid)" -ErrorAction SilentlyContinue
    if (-not $proc) {
        return $false
    }

    if (-not (Test-Browser4BrowserProcessName -Name $proc.Name)) {
        return $false
    }

    if ([string]::IsNullOrWhiteSpace($proc.CommandLine)) {
        return $false
    }

    $markerDir = Normalize-Browser4ProcessText -Text $Entry.MarkerDir
    $commandLine = Normalize-Browser4ProcessText -Text $proc.CommandLine
    $commandLine.Contains($markerDir)
}
