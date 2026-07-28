#!/usr/bin/env pwsh
<#
.SYNOPSIS
Real-time log monitoring dashboard for the Browser4 project.

.DESCRIPTION
A full-screen TUI dashboard that live-tails Browser4 log files with
color-coded severity levels, health overview, and regex filtering.

Covers all log sources: Kotlin backend (Logback), Rust CLI startup logs,
Coworker task logs, build logs, and test logs.

.PARAMETER RepoRoot
Path to the Browser4 repository root. Default: auto-detected via git.

.PARAMETER TailLines
Number of history lines to show when starting a tail. Default: 200.

.PARAMETER RefreshMs
Milliseconds between UI refresh ticks. Default: 150.

.PARAMETER MaxHealthMB
Maximum total MB per log directory before a warning is shown. Default: 500.

.EXAMPLE
.\bin\tools\watch-logs.ps1
Start the dashboard from the repository root.

.EXAMPLE
.\bin\tools\watch-logs.ps1 -TailLines 500 -RefreshMs 100
Show more history and refresh faster.
#>

[CmdletBinding()]
param(
    [string]$RepoRoot,
    [int]$TailLines = 200,
    [int]$RefreshMs = 150,
    [int]$MaxHealthMB = 500
)

$ErrorActionPreference = 'Continue'

# Detect whether we're in an interactive terminal (can do TUI)
$script:IsInteractive = $false
try {
    $script:IsInteractive = [Environment]::UserInteractive -and
        $null -ne $Host.UI -and
        $null -ne $Host.UI.RawUI -and
        -not [Console]::IsOutputRedirected
} catch { }

# ═══════════════════════════════════════════════════════════════════════════════
# 0. Preamble — encoding and repo root
# ═══════════════════════════════════════════════════════════════════════════════

# Force UTF-8 on all output streams
if ($PSVersionTable.PSVersion.Major -le 5) {
    cmd /c chcp 65001 > $null 2>&1
}
[Console]::OutputEncoding = [System.Text.Encoding]::UTF8

# Resolve repository root
if (-not $RepoRoot) {
    try {
        $RepoRoot = & git rev-parse --show-toplevel 2>$null
    } catch { }
}
if (-not $RepoRoot -or -not (Test-Path $RepoRoot)) {
    $RepoRoot = $PSScriptRoot
    while ($RepoRoot -and -not (Test-Path (Join-Path $RepoRoot '.git'))) {
        $parent = Split-Path $RepoRoot -Parent
        if ($parent -eq $RepoRoot) { break }
        $RepoRoot = $parent
    }
}
Push-Location $RepoRoot

# ═══════════════════════════════════════════════════════════════════════════════
# 1. Configuration — log sources, colours, thresholds
# ═══════════════════════════════════════════════════════════════════════════════

$script:LogSources = [ordered]@{
    '0' = @{ Label = 'git';      Desc = 'Git log (--oneline --graph --all)'; Path = '__git__' }
    '1' = @{ Label = 'pulsar';   Desc = 'Root backend log';            Path = 'logs\pulsar.log' }
    '2' = @{ Label = 'server';   Desc = 'Server / framework';          Path = 'logs\pulsar.s.log' }
    '3' = @{ Label = 'browser';  Desc = 'Browser / CDP operations';    Path = 'logs\pulsar.bs.log' }
    '4' = @{ Label = 'api';      Desc = 'Scrape API tasks';            Path = 'logs\pulsar.api.log' }
    '5' = @{ Label = 'pages';    Desc = 'Page processing';             Path = 'logs\pulsar.pg.log' }
    '6' = @{ Label = 'coworker'; Desc = 'Coworker task runner';        Path = '__coworker__' }
    '7' = @{ Label = 'build';    Desc = 'Spring Boot build output';    Path = '.build\spring-boot.log' }
    '8' = @{ Label = 'startup';  Desc = 'Server startup log';          Path = '__startup__' }
    '9' = @{ Label = 'all';      Desc = 'Combined: pulsar + server + browser'; Path = '__combined__' }
}

$script:SeverityColors = @{
    'FATAL'   = 'DarkRed'
    'ERROR'   = 'Red'
    'SEVERE'  = 'Red'
    'WARN'    = 'Yellow'
    'WARNING' = 'Yellow'
    'INFO'    = 'White'
    'DEBUG'   = 'DarkGray'
    'TRACE'   = 'DarkGray'
}
$script:DefaultColor = 'Gray'

$script:HealthDirs = @(
    @{ Label = 'logs';        Path = 'logs' },
    @{ Label = 'coworker';    Path = "$env:USERPROFILE\.browser4-coworker\tasks\300logs" },
    @{ Label = 'build';       Path = '.build' },
    @{ Label = 'startup';     Path = (Join-Path ([System.IO.Path]::GetTempPath()) 'browser4\browser4-cli') },
    @{ Label = 'maintenance'; Path = 'bin\maintenance\logs' }
)

# ═══════════════════════════════════════════════════════════════════════════════
# 2. State — mutable globals managed by the main loop
# ═══════════════════════════════════════════════════════════════════════════════

$script:State = @{
    Mode           = 'overview'   # 'overview' | 'tail' | 'filter-prompt'
    SourceKey      = '1'
    Paused         = $false
    Filter         = $null        # regex or $null
    Lines          = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
    DisplayBuffer  = [System.Collections.ArrayList]::new()  # persists across renders
    TailRunspace   = $null
    TailPowerShell = $null
    TailHandle     = $null
    Running        = $true
    NeedsRedraw    = $true
    WindowWidth    = 0
    WindowHeight   = 0
    HeaderHeight   = 8            # title (1) + blank (1) + 4-5 health dirs + divider (1)
    StatusHeight   = 1
    FilterPrompt  = ''
    GitDetail     = $false       # toggle compact/detailed git log
    ScrollLine    = -1           # -1 = auto-follow bottom; >= 0 = pinned top-line index
}

# ═══════════════════════════════════════════════════════════════════════════════
# 3. Console utility functions
# ═══════════════════════════════════════════════════════════════════════════════

function Get-ConsoleSize {
    try {
        $script:State.WindowWidth  = $Host.UI.RawUI.WindowSize.Width
        $script:State.WindowHeight = $Host.UI.RawUI.WindowSize.Height
    } catch {
        try {
            $script:State.WindowWidth  = [Console]::WindowWidth
            $script:State.WindowHeight = [Console]::WindowHeight
        } catch {
            $script:State.WindowWidth  = 120
            $script:State.WindowHeight = 40
        }
    }
    # Recalculate header height based on found health dirs
    $found = 0
    foreach ($hd in $script:HealthDirs) {
        $full = Resolve-LogPath -RelativePath $hd.Path
        if ($full -and (Test-Path $full)) { $found++ }
    }
    $script:State.HeaderHeight = 3 + $found + 1  # title + blank + dirs + divider
    if ($script:State.HeaderHeight -lt 5) { $script:State.HeaderHeight = 5 }
}

function Write-At {
    param([int]$X, [int]$Y, [string]$Text, [ConsoleColor]$Fg, [ConsoleColor]$Bg)
    if (-not $script:IsInteractive) { return }
    if ($X -ge $script:State.WindowWidth -or $Y -ge $script:State.WindowHeight) { return }
    try { [Console]::SetCursorPosition($X, $Y) } catch { return }
    if ($PSBoundParameters.ContainsKey('Fg')) { [Console]::ForegroundColor = $Fg }
    if ($PSBoundParameters.ContainsKey('Bg')) { [Console]::BackgroundColor = $Bg }
    $maxLen = $script:State.WindowWidth - $X
    if ($Text.Length -gt $maxLen) { $Text = $Text.Substring(0, $maxLen) }
    [Console]::Write($Text.PadRight($maxLen))
    [Console]::ResetColor()
}

function Write-Line {
    param([int]$Y, [string]$Text, [ConsoleColor]$Fg)
    Write-At -X 0 -Y $Y -Text $Text -Fg $Fg
}

function Clear-Below {
    param([int]$FromY)
    $blank = ' ' * $script:State.WindowWidth
    for ($y = $FromY; $y -lt $script:State.WindowHeight; $y++) {
        Write-At -X 0 -Y $y -Text $blank
    }
}

function Draw-Divider {
    param([int]$Y)
    $line = [string]::new([char]0x2500, $script:State.WindowWidth)
    Write-At -X 0 -Y $Y -Text $line -Fg DarkGray
}

function Draw-Box {
    param([int]$Left, [int]$Top, [int]$Width, [int]$Height, [ConsoleColor]$Fg = 'DarkGray')
    $h = [char]0x2500
    $v = [char]0x2502
    $tl = [char]0x250C; $tr = [char]0x2510
    $bl = [char]0x2514; $br = [char]0x2518
    Write-At -X $Left -Y $Top -Text ($tl + [string]::new($h, $Width - 2) + $tr) -Fg $Fg
    for ($y = $Top + 1; $y -lt $Top + $Height - 1; $y++) {
        Write-At -X $Left -Y $y -Text $v -Fg $Fg
        Write-At -X ($Left + $Width - 1) -Y $y -Text $v -Fg $Fg
    }
    Write-At -X $Left -Y ($Top + $Height - 1) -Text ($bl + [string]::new($h, $Width - 2) + $br) -Fg $Fg
}

# ═══════════════════════════════════════════════════════════════════════════════
# 4. Log health scanner
# ═══════════════════════════════════════════════════════════════════════════════

function Resolve-LogPath {
    param([string]$RelativePath)
    if (-not $RelativePath) { return $null }
    if ([System.IO.Path]::IsPathRooted($RelativePath)) {
        return $RelativePath
    }
    try {
        return [System.IO.Path]::GetFullPath((Join-Path $RepoRoot $RelativePath))
    } catch {
        return $null
    }
}

function Get-LogHealth {
    $results = [System.Collections.ArrayList]::new()
    foreach ($hd in $script:HealthDirs) {
        $full = $hd.Path
        if (-not [System.IO.Path]::IsPathRooted($full)) {
            $full = Join-Path $RepoRoot $full
        }
        if (-not (Test-Path $full)) { continue }

        $totalSize = 0
        $fileCount = 0
        $newestAge = [TimeSpan]::MaxValue
        $now = Get-Date
        try {
            $files = Get-ChildItem $full -Recurse -File -ErrorAction SilentlyContinue
            if ($files) {
                foreach ($f in $files) {
                    $totalSize += $f.Length
                    $fileCount++
                    $age = $now - $f.LastWriteTimeUtc
                    if ($age -lt $newestAge) { $newestAge = $age }
                }
            }
        } catch { }

        $totalMB = [math]::Round($totalSize / 1MB, 1)
        $ratio = if ($MaxHealthMB -gt 0) { [math]::Min($totalMB / $MaxHealthMB, 1.0) } else { 0 }
        $barLen = 10
        $filled = [math]::Floor($ratio * $barLen)
        $bar = ('█' * $filled) + ('░' * ($barLen - $filled))
        $status = if ($ratio -ge 0.9) { 'WARN' } elseif ($ratio -ge 0.7) { 'WATCH' } else { 'OK' }
        $ageStr = if ($newestAge -eq [TimeSpan]::MaxValue) { '--' }
                  elseif ($newestAge.TotalMinutes -lt 1) { 'now' }
                  elseif ($newestAge.TotalHours -lt 1) { "$([math]::Floor($newestAge.TotalMinutes))m ago" }
                  else { "$([math]::Floor($newestAge.TotalHours))h ago" }

        [void]$results.Add(@{
            Label     = $hd.Label
            FileCount = $fileCount
            TotalMB   = $totalMB
            Bar       = $bar
            Status    = $status
            NewestAge = $ageStr
            Path      = $full
        })
    }
    return $results
}

# ═══════════════════════════════════════════════════════════════════════════════
# 5. File discovery for dynamic paths
# ═══════════════════════════════════════════════════════════════════════════════

function Get-LatestCoworkerLog {
    $coworkerRoot = Join-Path $env:USERPROFILE '.browser4-coworker\tasks\300logs'
    if (-not (Test-Path $coworkerRoot)) { return $null }
    try {
        $newest = Get-ChildItem $coworkerRoot -Recurse -File -Filter '*.log' -ErrorAction SilentlyContinue |
            Where-Object { $_.Name -notmatch '\.std(out|err)$' } |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        return $newest?.FullName
    } catch { return $null }
}

function Get-LatestStartupLog {
    $startupRoot = Join-Path ([System.IO.Path]::GetTempPath()) 'browser4\browser4-cli'
    if (-not (Test-Path $startupRoot)) { return $null }
    try {
        $newest = Get-ChildItem $startupRoot -File -Filter 'browser4-server-*.log' -ErrorAction SilentlyContinue |
            Sort-Object LastWriteTimeUtc -Descending |
            Select-Object -First 1
        return $newest?.FullName
    } catch { return $null }
}

function Resolve-SourcePath {
    param([string]$Key)
    $src = $script:LogSources[$Key]
    if (-not $src) { return @() }
    switch ($src.Path) {
        '__git__'     { return @('__git__') }
        '__coworker__' { $p = Get-LatestCoworkerLog; if ($p) { return @($p) } else { return @() } }
        '__startup__'   { $p = Get-LatestStartupLog;   if ($p) { return @($p) } else { return @() } }
        '__combined__'  {
            $paths = @()
            foreach ($k in @('1','2','3')) {
                $sp = Resolve-SourcePath -Key $k
                if ($sp) { $paths += $sp }
            }
            return $paths
        }
        default {
            $p = Join-Path $RepoRoot $src.Path
            return @($p)
        }
    }
}

function Get-SourceLabel {
    param([string]$Key)
    $src = $script:LogSources[$Key]
    if (-not $src) { return 'unknown' }
    return $src.Label
}

# ═══════════════════════════════════════════════════════════════════════════════
# 6. Log tail — background runspace
# ═══════════════════════════════════════════════════════════════════════════════

function Start-LogTail {
    param([string[]]$Paths, [string]$Prefix)

    Stop-LogTail
    $script:State.ScrollLine = -1

    $queue = $script:State.Lines
    $tailLines = $script:TailLines

    $script:TailBody = {
        param($P, $Q, $N, $Pref)
        $enc = [System.Text.UTF8Encoding]::new($false)

        # Helper: tail a single file
        function Watch-File {
            param($FilePath, $Label)
            if (-not (Test-Path -LiteralPath $FilePath)) { return }

            # Read tail first
            try {
                $reader = [System.IO.StreamReader]::new($FilePath, $enc)
                $allLines = [System.Collections.ArrayList]::new()
                while ($null -ne ($l = $reader.ReadLine())) {
                    [void]$allLines.Add($l)
                    if ($allLines.Count -gt $N) { $allLines.RemoveAt(0) }
                }
                $reader.Close()
                $reader.Dispose()
                foreach ($l in $allLines) {
                    if ($Label) { $Q.Enqueue("[$Label] $l") } else { $Q.Enqueue($l) }
                }
            } catch { }

            # Watch for new lines
            $watcher = $null
            $fs = $null
            try {
                $dir = [System.IO.Path]::GetDirectoryName($FilePath)
                $fn  = [System.IO.Path]::GetFileName($FilePath)
                $watcher = [System.IO.FileSystemWatcher]::new($dir, $fn)
                $watcher.EnableRaisingEvents = $false
                $watcher.NotifyFilter = [System.IO.NotifyFilters]::Size -bor [System.IO.NotifyFilters]::LastWrite

                $lastLength = (Get-Item -LiteralPath $FilePath -ErrorAction SilentlyContinue)?.Length ?? 0
                $watcher.EnableRaisingEvents = $true

                while ($true) {
                    $changed = $watcher.WaitForChanged('Changed', 500)
                    if ($changed.TimedOut) { continue }
                    try {
                        $info = Get-Item -LiteralPath $FilePath -ErrorAction SilentlyContinue
                        if (-not $info) { break }
                        $newLen = $info.Length
                        if ($newLen -lt $lastLength) {
                            # File was truncated / rotated
                            $lastLength = 0
                        }
                        if ($newLen -gt $lastLength) {
                            $fs = [System.IO.FileStream]::new($FilePath, [System.IO.FileMode]::Open, [System.IO.FileAccess]::Read, [System.IO.FileShare]::ReadWrite)
                            $fs.Seek($lastLength, [System.IO.SeekOrigin]::Begin) | Out-Null
                            $sr = [System.IO.StreamReader]::new($fs, $enc)
                            while ($null -ne ($l = $sr.ReadLine())) {
                                if ($Label) { $Q.Enqueue("[$Label] $l") } else { $Q.Enqueue($l) }
                            }
                            $sr.Dispose()
                            $lastLength = $fs.Position
                            $fs.Close()
                            $fs.Dispose()
                            $fs = $null
                        }
                    } catch { }
                }
            } catch { }
            finally {
                if ($fs) { try { $fs.Close(); $fs.Dispose() } catch { } }
                if ($watcher) { try { $watcher.EnableRaisingEvents = $false; $watcher.Dispose() } catch { } }
            }
        }

        # Helper: poll git log (compact or detailed)
        function Watch-Git {
            param($Repo, $NumEntries, $Detail)
            $lastHash = ''
            while ($true) {
                try {
                    if ($Detail) {
                        # Detailed: full commit messages with author, date
                        $lines = & git -C $Repo log --format='commit %H%d%nAuthor: %an <%ae>%nDate:   %ad%n%n    %B%n' --date=local --all --max-count=$NumEntries 2>$null
                        $separator = "──── git log detail (all branches, last $NumEntries) — $(Get-Date -Format 'HH:mm:ss') ────"
                    } else {
                        $lines = & git -C $Repo log --oneline --graph --all --decorate -$NumEntries 2>$null
                        $separator = "──── git log (all branches, last $NumEntries) — $(Get-Date -Format 'HH:mm:ss') ────"
                    }
                    if (-not $lines) { Start-Sleep -Seconds 5; continue }
                    $Q.Enqueue('')
                    $Q.Enqueue($separator)
                    $newFirst = ''
                    $pushed = $false
                    foreach ($l in $lines) {
                        if (-not $newFirst) { $newFirst = $l }
                        if ($Detail) {
                            # Color-code each line type
                            if ($l -match '^commit ') {
                                $Q.Enqueue("GIT_HASH $l")
                            } elseif ($l -match '^Author:') {
                                $Q.Enqueue("GIT_AUTHOR $l")
                            } elseif ($l -match '^Date:') {
                                $Q.Enqueue("GIT_DATE $l")
                            } elseif ($l -match '^\s') {
                                $Q.Enqueue("GIT_BODY $l")
                            } elseif ($l -eq '') {
                                $Q.Enqueue("GIT_BLANK")
                            } else {
                                # Body paragraph — indent to match git log style
                                $Q.Enqueue("GIT_BODY     $l")
                            }
                        } else {
                            $Q.Enqueue("GIT $l")
                        }
                        $pushed = $true
                    }
                    if ($newFirst -eq $lastHash -and $pushed) {
                        $Q.Enqueue('  (no new commits since last poll)')
                    }
                    if ($newFirst) { $lastHash = $newFirst }
                } catch { }
                Start-Sleep -Seconds 10
            }
        }

        # Dispatch: git log or file tail
        if ($P.Count -eq 1 -and ($P[0] -eq '__git__' -or $P[0] -eq '__git_detail__')) {
            $detail = ($P[0] -eq '__git_detail__')
            Watch-Git -Repo $Pref -NumEntries $N -Detail $detail
        }
        elseif ($P.Count -eq 1) {
            Watch-File -FilePath $P[0] -Label ''
        } else {
            # Multi-file tail — each file in its own runspace, all sharing the queue
            $labels = @('pulsar', 'server', 'browser')
            for ($i = 0; $i -lt $P.Count; $i++) {
                Watch-File -FilePath $P[$i] -Label $labels[$i]
            }
        }
    }

    $ps = [PowerShell]::Create()
    $rs = [RunspaceFactory]::CreateRunspace()
    $rs.Open()
    $ps.Runspace = $rs
    [void]$ps.AddScript($script:TailBody)
    [void]$ps.AddParameter('P', $Paths)
    [void]$ps.AddParameter('Q', $queue)
    [void]$ps.AddParameter('N', $TailLines)
    [void]$ps.AddParameter('Pref', $Prefix)

    $script:State.TailPowerShell = $ps
    $script:State.TailRunspace   = $rs
    $script:State.TailHandle     = $ps.BeginInvoke()
}

function Stop-LogTail {
    if ($script:State.TailPowerShell) {
        try {
            $script:State.TailPowerShell.Stop()
            $script:State.TailPowerShell.Dispose()
        } catch { }
        $script:State.TailPowerShell = $null
    }
    if ($script:State.TailRunspace) {
        try { $script:State.TailRunspace.Close(); $script:State.TailRunspace.Dispose() } catch { }
        $script:State.TailRunspace = $null
    }
    $script:State.TailHandle = $null
    # Clear the queue
    $q = $script:State.Lines
    $drain = $null
    while ($q.TryDequeue([ref]$drain)) { }
    $script:State.Lines = [System.Collections.Concurrent.ConcurrentQueue[string]]::new()
    $script:State.DisplayBuffer = [System.Collections.ArrayList]::new()
}

# ═══════════════════════════════════════════════════════════════════════════════
# 7. Colorizer — apply severity-based colours to a log line
# ═══════════════════════════════════════════════════════════════════════════════

function Get-LineColor {
    param([string]$Line)
    # Git log lines have special prefix
    if ($Line -match '^GIT ') {
        $l = $Line.Substring(4)
        if ($l -match '\(') {
            if     ($l -match '\(HEAD')   { return 'Cyan' }
            elseif ($l -match '\(tag:')   { return 'Yellow' }
            elseif ($l -match '\(origin') { return 'Green' }
        }
        if    ($l -match '^\*') { return 'White' }
        elseif ($l -match '^\|') { return 'DarkGray' }
        elseif ($l -match '^[\\/]') { return 'DarkGray' }
        return 'DarkGray'
    }
    # Git detail lines (commit/author/date/body)
    if ($Line -match '^GIT_HASH ')   { return 'Yellow' }
    if ($Line -match '^GIT_AUTHOR ') { return 'Cyan' }
    if ($Line -match '^GIT_DATE ')   { return 'DarkCyan' }
    if ($Line -match '^GIT_BODY ')   { return 'White' }
    if ($Line -match '^GIT_BLANK')   { return 'White' }  # render blank lines as empty space
    # Git separator line
    if ($Line -match '^──── git log') { return 'DarkCyan' }
    if ($Line -match 'no new commits') { return 'DarkGray' }
    # Standard severity-based colorization
    foreach ($sev in $script:SeverityColors.Keys) {
        if ($Line -match "\b${sev}\b") {
            return $script:SeverityColors[$sev]
        }
    }
    return $script:DefaultColor
}

function Write-ColorizedLine {
    param([int]$Y, [string]$Line)
    $maxW = $script:State.WindowWidth
    # Strip internal color-marker prefixes before display
    if ($Line -eq 'GIT_BLANK') { $display = '' }
    else { $display = $Line -replace '^GIT_(HASH|AUTHOR|DATE|BODY) ', '' }
    $display = $display -replace '^GIT ', ''
    if ($display.Length -gt $maxW) {
        $display = $display.Substring(0, $maxW)
    }
    $color = Get-LineColor -Line $Line
    Write-At -X 0 -Y $Y -Text $display -Fg $color
}

# ═══════════════════════════════════════════════════════════════════════════════
# 8. Rendering — draw each region
# ═══════════════════════════════════════════════════════════════════════════════

function Draw-Header {
    Get-ConsoleSize
    $w = $script:State.WindowWidth

    # Title bar
    $title = "  Browser4 Log Dashboard"
    $clock = (Get-Date).ToString('yyyy-MM-dd HH:mm:ss')
    $modeStr = if ($script:State.Mode -eq 'filter-prompt') { 'FILTER' }
               elseif ($script:State.Mode -eq 'overview') { 'OVERVIEW' }
               else { Get-SourceLabel -Key $script:State.SourceKey }
    $pausedStr = if ($script:State.Paused) { ' [PAUSED]' } else { '' }
    $right = "${modeStr}${pausedStr}  ${clock}"
    $leftPad = $w - $right.Length - 2
    if ($leftPad -lt 10) { $leftPad = 10 }
    $titleLine = $title + (' ' * ($leftPad - $title.Length)) + $right
    Write-At -X 0 -Y 0 -Text $titleLine -Fg White -Bg DarkBlue

    # Blank line
    Clear-Below -FromY 1
    Write-At -X 0 -Y 1 -Text (' ' * $w)

    # Health summary
    $health = Get-LogHealth
    $y = 2
    foreach ($h in $health) {
        $sizeLabel = "$($h.TotalMB) MB".PadLeft(8)
        $line = "  $($h.Label.PadRight(11)) $($h.FileCount.ToString().PadLeft(3)) files  ${sizeLabel}  [$($h.Bar)]  $($h.Status)  ($($h.NewestAge))"
        $color = switch ($h.Status) {
            'WARN'  { 'Red' }
            'WATCH' { 'Yellow' }
            default { 'DarkGray' }
        }
        Write-At -X 0 -Y $y -Text $line -Fg $color
        $y++
    }
    $script:State.HeaderHeight = $y + 1
    Draw-Divider -Y ($y)
}

function Draw-StatusBar {
    $y = $script:State.WindowHeight - 1
    $filterInfo = if ($script:State.Filter) { " Filter: $($script:State.Filter)" } else { '' }
    $gitToggle = if ($script:State.Mode -eq 'tail' -and $script:State.SourceKey -eq '0') {
        if ($script:State.GitDetail) { ' [g]compact' } else { ' [g]detail' }
    } else { '' }
    $scrollInfo = ''
    if ($script:State.Mode -eq 'tail' -and $script:State.ScrollLine -ge 0) {
        $bodyH = $script:State.WindowHeight - $script:State.HeaderHeight - $script:State.StatusHeight
        $total = $script:State.DisplayBuffer.Count
        $pct = if ($total -gt 0) { [math]::Round(100 * ($script:State.ScrollLine + $bodyH) / [Math]::Max($total, 1)) } else { 0 }
        $scrollInfo = "  ${pct}%"
    }
    $statusText = "[0-9]source  [g]it-log${gitToggle}  [o]verview  [f]ilter  [Space]pause  [c]lear  [q]uit${filterInfo}${scrollInfo}"
    Write-At -X 0 -Y $y -Text $statusText -Fg White -Bg DarkBlue
}

function Draw-OverviewBody {
    $top = $script:State.HeaderHeight
    $w = $script:State.WindowWidth
    Clear-Below -FromY $top

    $y = $top
    Write-At -X 0 -Y $y -Text "  Available Log Sources:" -Fg Cyan
    $y += 2

    foreach ($kv in $script:LogSources.GetEnumerator()) {
        $key = $kv.Key
        $src = $kv.Value
        $paths = Resolve-SourcePath -Key $key
        $isGit = ($src.Path -eq '__git__')
        $status = if ($isGit) {
            # Git is available if git command works
            try { & git -C $RepoRoot rev-parse --git-dir 2>$null | Out-Null; '(active)' } catch { '(offline)' }
        } elseif ($paths.Count -gt 0 -and $paths[0] -and (Test-Path -LiteralPath $paths[0])) {
            try {
                $len = (Get-Item -LiteralPath $paths[0]).Length
                $sz = if ($len -gt 1MB) { "$([math]::Round($len/1MB,1)) MB" }
                      elseif ($len -gt 1KB) { "$([math]::Round($len/1KB,1)) KB" }
                      else { "$len B" }
                "(active — $sz)"
            } catch { '(active)' }
        } else { '(offline)' }
        $color = if ($status -match 'offline') { 'DarkGray' } else { 'White' }
        Write-At -X 4 -Y $y -Text "[$key] $($src.Label.PadRight(8)) — $($src.Desc) $status" -Fg $color
        $y++
        if ($y -ge $script:State.WindowHeight - 2) { break }
    }
    $y += 1
    Write-At -X 0 -Y $y -Text "  Press [0-9] to tail a source, [g] for git log, [o] for overview, [q] to quit." -Fg DarkGray
}

function Draw-TailBody {
    $top = $script:State.HeaderHeight
    $bottom = $script:State.WindowHeight - $script:State.StatusHeight
    $bodyHeight = $bottom - $top
    if ($bodyHeight -lt 2) { return }

    # Drain queue into persistent display buffer
    $buf = $script:State.DisplayBuffer
    $item = $null
    while ($script:State.Lines.TryDequeue([ref]$item)) {
        [void]$buf.Add($item)
    }

    # Keep a generous history for scrolling (10x screen height)
    $maxBuf = [Math]::Max($bodyHeight * 10, 500)
    while ($buf.Count -gt $maxBuf) {
        $buf.RemoveAt(0)
    }

    # Build filtered list (full, not truncated)
    $filtered = [System.Collections.ArrayList]::new()
    foreach ($line in $buf) {
        if ($script:State.Filter) {
            try { if ($line -notmatch $script:State.Filter) { continue } } catch { }
        }
        [void]$filtered.Add($line)
    }

    # Clamp scroll position
    $autoLine = [Math]::Max(0, $filtered.Count - $bodyHeight)  # bottom-following position

    if (-not $script:State.Paused -and $script:State.ScrollLine -eq -1) {
        # Unpaused and not scrolled: always follow the bottom
        $script:State.ScrollLine = $autoLine
    } elseif (-not $script:State.Paused) {
        # Unpaused but was previously scrolled: snap back to bottom
        $script:State.ScrollLine = $autoLine
    } else {
        # Paused: pin the view at current position, clamp to valid range
        if ($script:State.ScrollLine -gt $autoLine) {
            $script:State.ScrollLine = $autoLine
        }
        if ($script:State.ScrollLine -lt 0) {
            $script:State.ScrollLine = 0
        }
    }

    # Visible window starting at ScrollLine
    $start = $script:State.ScrollLine
    if ($start + $bodyHeight -gt $filtered.Count) {
        $start = [Math]::Max(0, $filtered.Count - $bodyHeight)
        $script:State.ScrollLine = $start
    }
    $count = [Math]::Min($bodyHeight, $filtered.Count - $start)
    $visible = if ($count -gt 0) { $filtered.GetRange($start, $count) } else { @() }

    Clear-Below -FromY $top

    $y = $top
    foreach ($line in $visible) {
        Write-ColorizedLine -Y $y -Line $line
        $y++
    }
    # Clear remaining lines
    while ($y -lt $bottom) {
        Write-At -X 0 -Y $y -Text (' ' * $script:State.WindowWidth)
        $y++
    }
}

function Draw-FilterPrompt {
    $top = $script:State.HeaderHeight
    $w = $script:State.WindowWidth
    Clear-Below -FromY $top

    $promptY = $top + 2
    Write-At -X 4 -Y $promptY -Text 'Filter regex (Enter to apply, empty to clear):' -Fg Cyan
    $inputY = $promptY + 2
    Write-At -X 4 -Y $inputY -Text '> ' -Fg Yellow
    Write-At -X 6 -Y $inputY -Text $script:State.FilterPrompt.PadRight($w - 10) -Fg White
    $cursorX = 6 + $script:State.FilterPrompt.Length
    Write-At -X $cursorX -Y $inputY -Text ' ' -Bg White
    [Console]::SetCursorPosition($cursorX, $inputY)
}

# ═══════════════════════════════════════════════════════════════════════════════
# 9. Input handling
# ═══════════════════════════════════════════════════════════════════════════════

function Handle-Input {
    param([ConsoleKeyInfo]$Key)

    if ($script:State.Mode -eq 'filter-prompt') {
        Handle-FilterInput -Key $Key
        return
    }

    # Ctrl-combos for scrolling (for keyboards without PgUp/PgDn)
    $ctrl = ($Key.Modifiers -band [ConsoleModifiers]::Control) -eq [ConsoleModifiers]::Control
    if ($ctrl -and $script:State.Mode -eq 'tail') {
        $bodyH = $script:State.WindowHeight - $script:State.HeaderHeight - $script:State.StatusHeight
        $autoLine = [Math]::Max(0, $script:State.DisplayBuffer.Count - $bodyH)
        $page = [Math]::Max(1, $bodyH / 2)
        $fullPage = [Math]::Max(1, $bodyH - 2)
        switch ($Key.Key) {
            'U' { $script:State.Paused = $true; $script:State.ScrollLine = [Math]::Max(0, $script:State.ScrollLine - [int]$page) }
            'D' { $script:State.ScrollLine += [int]$page; if ($script:State.ScrollLine -ge $autoLine) { $script:State.ScrollLine = -1; $script:State.Paused = $false } }
            'B' { $script:State.Paused = $true; $script:State.ScrollLine = [Math]::Max(0, $script:State.ScrollLine - [int]$fullPage) }
            'F' { $script:State.ScrollLine += [int]$fullPage; if ($script:State.ScrollLine -ge $autoLine) { $script:State.ScrollLine = -1; $script:State.Paused = $false } }
        }
        return
    }

    switch ($Key.Key) {
        { $_ -ge 'D0' -and $_ -le 'D9' } {
            $num = [string][int]($_ - 'D0')
            $paths = Resolve-SourcePath -Key $num
            if ($paths.Count -gt 0 -and $paths[0]) {
                $script:State.Mode = 'tail'
                $script:State.SourceKey = $num
                $script:State.Paused = $false
                $script:State.Filter = $null
                # Git source needs the repo root as its working directory
                $prefix = if ($num -eq '0') { $RepoRoot } else { Get-SourceLabel -Key $num }
                Start-LogTail -Paths $paths -Prefix $prefix
            }
        }
        'G' {
            # Shortcut for git log. Pressing g again toggles compact/detailed.
            $alreadyGit = ($script:State.Mode -eq 'tail' -and $script:State.SourceKey -eq '0')
            $script:State.Mode = 'tail'
            $script:State.SourceKey = '0'
            $script:State.Paused = $false
            $script:State.Filter = $null
            if ($alreadyGit) {
                $script:State.GitDetail = -not $script:State.GitDetail
            } else {
                $script:State.GitDetail = $false
            }
            $sentinel = if ($script:State.GitDetail) { '__git_detail__' } else { '__git__' }
            Start-LogTail -Paths @($sentinel) -Prefix $RepoRoot
        }
        'O' {
            $script:State.Mode = 'overview'
            Stop-LogTail
            $script:State.Paused = $false
            $script:State.Filter = $null
        }
        'F' {
            Enter-FilterMode
        }
        'Spacebar' {
            if ($script:State.Mode -eq 'tail') {
                $script:State.Paused = -not $script:State.Paused
                if (-not $script:State.Paused) {
                    $script:State.ScrollLine = -1  # resume auto-follow
                }
            }
        }
        'UpArrow' {
            if ($script:State.Mode -eq 'tail') {
                $script:State.Paused = $true
                $script:State.ScrollLine = [Math]::Max(0, $script:State.ScrollLine - 1)
            }
        }
        'DownArrow' {
            if ($script:State.Mode -eq 'tail') {
                $bodyH = $script:State.WindowHeight - $script:State.HeaderHeight - $script:State.StatusHeight
                $autoLine = [Math]::Max(0, $script:State.DisplayBuffer.Count - $bodyH)
                $script:State.ScrollLine += 1
                if ($script:State.ScrollLine -ge $autoLine) {
                    $script:State.ScrollLine = -1  # reached bottom, resume following
                    $script:State.Paused = $false
                }
            }
        }
        'PageUp' {
            if ($script:State.Mode -eq 'tail') {
                $script:State.Paused = $true
                $page = [Math]::Max(1, ($script:State.WindowHeight - $script:State.HeaderHeight - $script:State.StatusHeight) / 2)
                $script:State.ScrollLine = [Math]::Max(0, $script:State.ScrollLine - [int]$page)
            }
        }
        'PageDown' {
            if ($script:State.Mode -eq 'tail') {
                $bodyH = $script:State.WindowHeight - $script:State.HeaderHeight - $script:State.StatusHeight
                $autoLine = [Math]::Max(0, $script:State.DisplayBuffer.Count - $bodyH)
                $page = [Math]::Max(1, $bodyH / 2)
                $script:State.ScrollLine += [int]$page
                if ($script:State.ScrollLine -ge $autoLine) {
                    $script:State.ScrollLine = -1
                    $script:State.Paused = $false
                }
            }
        }
        'Home' {
            if ($script:State.Mode -eq 'tail') {
                $script:State.Paused = $true
                $script:State.ScrollLine = 0
            }
        }
        'End' {
            if ($script:State.Mode -eq 'tail') {
                $script:State.ScrollLine = -1
                $script:State.Paused = $false
            }
        }
        'C' {
            # Clear queue and display buffer
            $q = $script:State.Lines
            $drain = $null
            while ($q.TryDequeue([ref]$drain)) { }
            $script:State.DisplayBuffer = [System.Collections.ArrayList]::new()
            $script:State.ScrollLine = -1
        }
        'Q' {
            $script:State.Running = $false
        }
        'Escape' {
            if ($script:State.Mode -eq 'tail') {
                $script:State.Mode = 'overview'
                Stop-LogTail
                $script:State.Filter = $null
            }
        }
    }
}

function Enter-FilterMode {
    $script:State.Mode = 'filter-prompt'
    $script:State.FilterPrompt = if ($script:State.Filter) { $script:State.Filter } else { '' }
}

function Handle-FilterInput {
    param([ConsoleKeyInfo]$Key)
    switch ($Key.Key) {
        'Enter' {
            $script:State.Filter = if ($script:State.FilterPrompt.Trim() -eq '') { $null }
                                  else { $script:State.FilterPrompt.Trim() }
            $script:State.FilterPrompt = ''
            $script:State.Mode = 'tail'
            # Clear display buffer — filter changes what's visible
            $script:State.DisplayBuffer = [System.Collections.ArrayList]::new()
        }
        'Escape' {
            $script:State.FilterPrompt = ''
            $script:State.Mode = 'tail'
        }
        'Backspace' {
            if ($script:State.FilterPrompt.Length -gt 0) {
                $script:State.FilterPrompt = $script:State.FilterPrompt.Substring(0, $script:State.FilterPrompt.Length - 1)
            }
        }
        default {
            if ($Key.KeyChar -match '[\x20-\x7E]') {
                $script:State.FilterPrompt += $Key.KeyChar
            }
        }
    }
}

# ═══════════════════════════════════════════════════════════════════════════════
# 10. Main loop
# ═══════════════════════════════════════════════════════════════════════════════

function Invoke-Dashboard {
    # Hide cursor (only works in interactive terminals)
    try { [Console]::CursorVisible = $false } catch { }

    # Ctrl+C handler — restore console and exit cleanly
    try {
        [Console]::CancelKeyPress.Add({
            $script:State.Running = $false
            try { [Console]::CursorVisible = $true } catch { }
            [Console]::ResetColor()
            try { [Console]::Clear() } catch { }
            Write-Host 'Dashboard stopped.' -ForegroundColor Yellow
            [Environment]::Exit(0)
        })
    } catch { }

    try {
        Get-ConsoleSize
        $lastHealthRefresh = [DateTime]::MinValue
        $healthRefreshInterval = [TimeSpan]::FromSeconds(5)

        while ($script:State.Running) {
            # Refresh health every 5 seconds
            $now = Get-Date
            if ($now - $lastHealthRefresh -gt $healthRefreshInterval) {
                $script:State.NeedsRedraw = $true
                $lastHealthRefresh = $now
            }

            # Render
            if ($script:State.NeedsRedraw) {
                Get-ConsoleSize
                Draw-Header
                Draw-StatusBar
                $script:State.NeedsRedraw = $false
            }

            if ($script:State.Mode -eq 'filter-prompt') {
                Draw-FilterPrompt
            } elseif ($script:State.Mode -eq 'overview') {
                Draw-OverviewBody
            } else {
                Draw-TailBody
            }

            # Poll input (non-blocking)
            $pollMs = $script:RefreshMs
            $elapsed = 0
            while ($elapsed -lt $pollMs) {
                try {
                    if ($Host.UI.RawUI.KeyAvailable) {
                        $keyInfo = [Console]::ReadKey($true)
                        if ($keyInfo.Key -eq 'Q') {
                            $script:State.Running = $false
                            break
                        }
                        Handle-Input -Key $keyInfo
                        $script:State.NeedsRedraw = $true
                        break
                    }
                } catch { }
                Start-Sleep -Milliseconds 20
                $elapsed += 20
            }

            # Redraw tail area continuously (new lines stream in)
            if ($script:State.Mode -eq 'tail' -and -not $script:State.Paused) {
                $script:State.NeedsRedraw = $true
            }
        }
    }
    finally {
        Restore-Console
    }
}

function Restore-Console {
    Stop-LogTail
    try { [Console]::CursorVisible = $true } catch { }
    [Console]::ResetColor()
    try { [Console]::Clear() } catch { }
    Pop-Location
}

# ═══════════════════════════════════════════════════════════════════════════════
# 11. Entry point
# ═══════════════════════════════════════════════════════════════════════════════

if ($script:IsInteractive) {
    Write-Host "Browser4 Log Dashboard starting..." -ForegroundColor Cyan
    Write-Host "  Repo: $RepoRoot" -ForegroundColor DarkGray
    Write-Host "  Press [q] to quit, [0-9] to tail a log, [g] for git log, [o] for overview" -ForegroundColor DarkGray
    Write-Host ""
    Start-Sleep -Seconds 1
    Invoke-Dashboard
} else {
    # Non-interactive fallback: tail pulsar.log like a plain `tail -f`
    Write-Host "Browser4 Log Dashboard (non-interactive mode)" -ForegroundColor Cyan
    Write-Host "  Streaming logs/pulsar.log — press Ctrl+C to stop" -ForegroundColor DarkGray
    $logPath = Join-Path $RepoRoot 'logs\pulsar.log'
    if (-not (Test-Path $logPath)) {
        Write-Host "  (log file not found at $logPath — waiting for it)" -ForegroundColor Yellow
    }
    try {
        Get-Content -Path $logPath -Wait -Tail $TailLines -Encoding UTF8 -ErrorAction SilentlyContinue |
            ForEach-Object {
                $color = Get-LineColor -Line $_
                Write-Host $_ -ForegroundColor $color
            }
    } catch {
        Write-Host "Log stream ended: $_" -ForegroundColor Yellow
    } finally {
        Pop-Location
    }
}
