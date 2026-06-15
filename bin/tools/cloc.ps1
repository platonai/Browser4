#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════

Param(
    [Parameter(Position=0)]
    [string]$Ref
)

# Checkout the specified ref if provided (suppress stderr like bash '2>/dev/null')
if ($Ref) {
    try {
        git checkout $Ref 2>$null | Out-Null
    } catch {
        # Ignore checkout errors to mimic silent behavior
    }
}

# Get last commit date in the same format as the bash script (YYYY/MM/DD)
# Use --no-pager to avoid interactive pager in some environments
$commitDate = try {
    (git --no-pager log -1 "--date=format:%Y/%m/%d" "--format=%ad" | Select-Object -First 1)
} catch {
    ''
}

# Print ref and date without newline, matching: echo -n "$1 <date> "
$prefix = @()
if ($Ref) { $prefix += $Ref }
if ($commitDate) { $prefix += $commitDate }
if ($prefix.Count -gt 0) {
    Write-Host -NoNewline ("{0} " -f ($prefix -join ' '))
}

# Run cloc and process output
# Expect lines like: "Kotlin  files blank comment code"; we extract language (1st) and code (5th)
$clocOutput = $null
try {
    $clocOutput = & cloc . 2>$null
} catch {
    $clocOutput = $null
}

if (-not $clocOutput) {
    # If cloc is missing or failed, still end the line
    Write-Host
    return
}

# Filter Kotlin and Java (avoid JavaScript via word boundary)
$filtered = $clocOutput | Where-Object { $_ -match '^(\s*)?(Kotlin|Java)\b' }

$pairs = @()
foreach ($line in $filtered) {
    $norm = ($line -replace '\s+', ' ').Trim()
    if (-not $norm) { continue }
    $parts = $norm -split ' '
    if ($parts.Length -ge 5) {
        $lang = $parts[0]
        $code = $parts[4]
        $pairs += ("{0}={1}" -f $lang, $code)
    }
}

Write-Host ($pairs -join ',')

