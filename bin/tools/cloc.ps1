<#
.SYNOPSIS
    Count lines of Kotlin and Java code using cloc.

.DESCRIPTION
    Optionally checks out a Git ref, then runs cloc to count Kotlin and Java
    lines of code. Outputs a single line in the format:

        [ref] [YYYY/MM/DD] Kotlin=NNNNN,Java=NNNNN

    The ref and date prefix are omitted when the ref is not provided and the
    commit date cannot be determined.

.PARAMETER Ref
    An optional Git ref (branch, tag, or commit) to check out before counting.
    When provided, it is included in the output prefix.

.EXAMPLE
    ./cloc.ps1
    # Kotlin=24501,Java=18320

.EXAMPLE
    ./cloc.ps1 main
    # main 2026/06/30 Kotlin=24501,Java=18320

.NOTES
    Requires cloc (https://github.com/AlDanial/cloc) and Git to be on PATH.
#>

[CmdletBinding()]
Param(
    [Parameter(Position = 0)]
    [string] $Ref
)

$ErrorActionPreference = 'Stop'

# ── Helpers ──────────────────────────────────────────────────────────

function Get-CommitDate {
    try {
        $date = git --no-pager log -1 '--date=format:%Y/%m/%d' '--format=%ad' 2>$null
        if ($date) { return $date.Trim() }
    } catch { }
    return ''
}

function Invoke-CLoc {
    param([string[]] $Languages)

    # Prefer JSON output for reliable parsing; fall back to plain text.
    $json = $null
    try {
        $raw = & cloc --json --include-lang=($Languages -join ',') . 2>$null
        if ($raw) {
            $json = $raw | ConvertFrom-Json
        }
    } catch { }

    if ($json) {
        $result = @{}
        foreach ($lang in $Languages) {
            $entry = $json.PSObject.Properties | Where-Object { $_.Name -eq $lang }
            if ($entry -and $entry.Value.code) {
                $result[$lang] = $entry.Value.code
            }
        }
        return $result
    }

    # Fallback: parse plain-text cloc output (e.g. "Kotlin  42 10 5 27")
    $result = @{}
    try {
        $output = & cloc . 2>$null
    } catch {
        return $result
    }

    foreach ($line in $output) {
        if ($line -notmatch '^\s*(Kotlin|Java)\b') { continue }
        $parts = -split ($line -replace '\s+', ' ').Trim()
        if ($parts.Count -ge 5) {
            $result[$parts[0]] = [int] $parts[4]
        }
    }
    return $result
}

# ── Main ─────────────────────────────────────────────────────────────

# Optionally check out the requested ref.
if ($Ref) {
    try {
        git checkout $Ref 2>$null | Out-Null
    } catch { }
}

$date   = Get-CommitDate
$counts = Invoke-CLoc -Languages 'Kotlin', 'Java'

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

# Emit a single output line.
$line = ''
if ($prefixParts.Count -gt 0) {
    $line += ($prefixParts -join ' ') + ' '
}
$line += ($countParts -join ',')

Write-Output $line
