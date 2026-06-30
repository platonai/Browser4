<#
.SYNOPSIS
    Count lines of Kotlin and Java code using cloc.

.DESCRIPTION
    Optionally checks out a Git ref, then runs cloc to count Kotlin and Java
    lines of code. Outputs a single line in the format:

        [ref] [YYYY/MM/DD] Kotlin=NNNNN,Java=NNNNN

    The ref and date prefix are omitted when the ref is not provided and the
    commit date cannot be determined.

    Progress (a spinner with elapsed time) is written to stderr so stdout
    stays machine-readable. Use -Quiet to suppress all progress output.

.PARAMETER Ref
    An optional Git ref (branch, tag, or commit) to check out before counting.
    When provided, it is included in the output prefix.

.PARAMETER Quiet
    Suppress progress messages. Only the result line is written to stdout.

.EXAMPLE
    ./cloc.ps1
    # [spinner] Kotlin=24501,Java=18320

.EXAMPLE
    ./cloc.ps1 main
    # [spinner] main 2026/06/30 Kotlin=24501,Java=18320

.EXAMPLE
    ./cloc.ps1 -Quiet
    # Kotlin=24501,Java=18320

.NOTES
    Requires cloc (https://github.com/AlDanial/cloc) and Git to be on PATH.
#>

[CmdletBinding()]
Param(
    [Parameter(Position = 0)]
    [string] $Ref,

    [switch] $Quiet
)

$ErrorActionPreference = 'Stop'

# ═══════════════════════════════════════════════════════════════════════
# Helpers
# ═══════════════════════════════════════════════════════════════════════

function Get-RepoRoot {
    try {
        $root = git rev-parse --show-toplevel 2>$null
        if ($root) { return $root.Trim() }
    } catch { }
    # Fall back to current directory if not in a git repo.
    return (Get-Location).Path
}

function Get-CommitDate {
    try {
        $date = git --no-pager log -1 '--date=format:%Y/%m/%d' '--format=%ad' 2>$null
        if ($date) { return $date.Trim() }
    } catch { }
    return ''
}

function Invoke-CLoc {
    param([string[]] $Languages, [string] $Path)

    # Prefer JSON output for reliable parsing; fall back to plain text.
    try {
        $raw = & cloc --json --include-lang=($Languages -join ',') $Path 2>$null
        if ($LASTEXITCODE -eq 0 -and $raw) {
            # &-capture produces an array of lines; ConvertFrom-Json needs a single string.
            $json = ($raw -join "`n") | ConvertFrom-Json
            $result = @{}
            foreach ($lang in $Languages) {
                $entry = $json.PSObject.Properties | Where-Object { $_.Name -eq $lang }
                if ($entry -and $null -ne $entry.Value.code) {
                    $result[$lang] = $entry.Value.code
                }
            }
            return $result
        }
    } catch { }

    # Fallback: parse plain-text cloc output (e.g. "Kotlin  42 10 5 27").
    $result = @{}
    try {
        $output = & cloc $Path 2>$null
    } catch {
        return $result
    }

    foreach ($line in $output) {
        if ($line -notmatch '^\s*(Kotlin|Java)\b') { continue }
        $norm  = ($line -replace '\s+', ' ').Trim()
        $parts = -split $norm
        if ($parts.Count -ge 5) {
            $result[$parts[0]] = [int] $parts[4]
        }
    }
    return $result
}

# ── Spinner (runs in a background runspace so it animates while cloc blocks) ──

function Start-Spinner {
    param([string] $Message)

    $state = @{
        Stopwatch = [System.Diagnostics.Stopwatch]::StartNew()
        Chars     = [char[]] @('⠋', '⠙', '⠹', '⠸', '⠼', '⠴', '⠦', '⠧', '⠇', '⠏')
        Index     = 0
        Running   = $true
    }

    $runspace = [RunspaceFactory]::CreateRunspace()
    $runspace.ApartmentState = 'STA'
    $runspace.Open()
    $runspace.SessionStateProxy.SetVariable('state', $state)
    $runspace.SessionStateProxy.SetVariable('message', $Message)

    $ps = [PowerShell]::Create()
    $ps.Runspace = $runspace
    [void] $ps.AddScript({
        while ($state.Running) {
            $c  = $state.Chars[$state.Index % $state.Chars.Length]
            $el = [math]::Floor($state.Stopwatch.Elapsed.TotalSeconds)
            [Console]::Error.Write("`r  {0} {1} ({2}s)   " -f $c, $message, $el)
            $state.Index++
            [Threading.Thread]::Sleep(100)
        }
    })
    $handle = $ps.BeginInvoke()

    return @{
        Stopwatch = $state.Stopwatch
        Runspace  = $runspace
        PS        = $ps
        Handle    = $handle
    }
}

function Stop-Spinner {
    param($State)
    if (-not $State) { return }
    try {
        $State.Runspace.SessionStateProxy.GetVariable('state').Running = $false
        [Threading.Thread]::Sleep(150)
        $State.PS.EndInvoke($State.Handle)
        $State.PS.Dispose()
        $State.Runspace.Dispose()
        $State.Stopwatch.Stop()
        # Clear the spinner line.
        [Console]::Error.Write("`r" + ' ' * 60 + "`r")
    } catch { }
}

# ═══════════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════════

# Resolve repo root before any checkout, so we always count the right tree.
$repoRoot = Get-RepoRoot

# Optionally check out the requested ref.
if ($Ref) {
    try {
        git checkout $Ref 2>$null | Out-Null
    } catch { }
}

$date = Get-CommitDate

# Show spinner while cloc runs.
$spinner = if (-not $Quiet) { Start-Spinner -Message 'Counting lines of code' }
try {
    $counts = Invoke-CLoc -Languages 'Kotlin', 'Java' -Path $repoRoot
} finally {
    Stop-Spinner -State $spinner
}

# Emit elapsed time after spinner is cleared.
if (-not $Quiet -and $spinner) {
    $elapsed = [math]::Round($spinner.Stopwatch.Elapsed.TotalSeconds, 1)
    Write-Host "  Done in ${elapsed}s" -ForegroundColor DarkGray
}

# Build the prefix: "ref YYYY/MM/DD"
$prefixParts = @()
if ($Ref)  { $prefixParts += $Ref }
if ($date) { $prefixParts += $date }

# Build the counts segment: "Kotlin=NNNNN,Java=NNNNN"
$countParts = @()
foreach ($lang in @('Kotlin', 'Java')) {
    $n = if ($counts.ContainsKey($lang)) { $counts[$lang] } else { 0 }
    $countParts += "${lang}=${n}"
}

# Emit a single result line to stdout.
$line = ''
if ($prefixParts.Count -gt 0) {
    $line += ($prefixParts -join ' ') + ' '
}
$line += ($countParts -join ',')

Write-Output $line
