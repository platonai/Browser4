# ═══════════════════════════════════════════════════════════════════
# test-session.psm1 — cross-run persistable test-session state
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Persistable cross-run record of every test session executed in the repository.

.DESCRIPTION
    Maintains a single JSON file (target/test-session.json) that records the
    last result, log paths, aggregate pass/fail counts, and rolling history
    for each test type.  System environment is captured once and reused.

    Test types are keyed by category:
      - "ps"                — all PowerShell *.tests.ps1 files (bin/test.ps1 ps)
      - "cli"               — Rust browser4-cli tests (bin/test.ps1 cli)
      - "rws:scenarios"     — real-world scenario test suite
      - "maven:fast"        — Maven fast unit tests
      - "maven:it"          — Maven integration tests
      - "maven:e2e"         — Maven end-to-end tests
      - "maven:rest"        — Maven REST module tests
      - "maven:skills"      — skills-focused agentic tests
      - "maven:mcp"         — MCP-focused agentic tests
      - "maven:<test-name>" — arbitrary test-utils session name

    Callers import this module and call Update-TestSessionResult at the end of
    each test run.  The dependency is soft — if the module is absent, tests
    still run normally.
#>

# ═══════════════════════════════════════════════════════════════════
# Module-level constants
# ═══════════════════════════════════════════════════════════════════

$script:MaxHistory = 5
$script:SchemaVersion = 1

# ═══════════════════════════════════════════════════════════════════
# Public: resolve the session file path
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Return the canonical path to the session JSON file.

.DESCRIPTION
    Resolves from the provided $RepoRoot, defaulting to git rev-parse when
    omitted.  The file lives at <repo-root>/target/test-session.json.

.PARAMETER RepoRoot
    Absolute path to the repository root.
#>
function Get-TestSessionPath {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,

        [string]$SessionPath = ''
    )
    if ($SessionPath) {
        if ([System.IO.Path]::IsPathRooted($SessionPath)) {
            return $SessionPath
        }
        return Join-Path $RepoRoot $SessionPath
    }
    return Join-Path $RepoRoot 'target' 'test-session.json'
}

# ═══════════════════════════════════════════════════════════════════
# Internal: build an empty skeleton
# ═══════════════════════════════════════════════════════════════════
function New-SessionSkeleton {
    param([string]$RepoRoot)

    # Resolve git metadata best-effort
    $branch = ''
    $commit = ''
    try {
        $branch = (git -C $RepoRoot rev-parse --abbrev-ref HEAD 2>$null) -replace '\s+', ''
    } catch { }
    try {
        $commit = (git -C $RepoRoot rev-parse --short HEAD 2>$null) -replace '\s+', ''
    } catch { }

    return [PSCustomObject]@{
        version    = $script:SchemaVersion
        generatedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
        repository = [PSCustomObject]@{
            root   = $RepoRoot
            branch = $branch
            commit = $commit
        }
        system     = [PSCustomObject]@{
            os          = ''
            osVersion   = ''
            pwshVersion = ''
            javaVersion = ''
            rustVersion = ''
            capturedAt  = ''
        }
        tests      = @{ }
    }
}

# ═══════════════════════════════════════════════════════════════════
# Public: read the session file (or return an empty skeleton)
# ═══════════════════════════════════════════════════════════════════
function Read-TestSession {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,

        [string]$SessionPath = ''
    )

    $path = Get-TestSessionPath -RepoRoot $RepoRoot -SessionPath $SessionPath

    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) {
        return New-SessionSkeleton -RepoRoot $RepoRoot
    }

    try {
        $json = Get-Content -LiteralPath $path -Raw -Encoding UTF8 -ErrorAction Stop
        $obj  = $json | ConvertFrom-Json -ErrorAction Stop

        # Coerce tests from PSCustomObject to hashtable for easier upsert
        if ($obj.tests -is [PSCustomObject]) {
            $ht = @{ }
            foreach ($prop in $obj.tests.PSObject.Properties) {
                $ht[$prop.Name] = $prop.Value
            }
            $obj.tests = $ht
        }

        # Ensure required top-level fields exist (migration from older schemas)
        if (-not $obj.version)    { $obj | Add-Member -NotePropertyName 'version'    -NotePropertyValue $script:SchemaVersion -Force }
        if (-not $obj.generatedAt) { $obj | Add-Member -NotePropertyName 'generatedAt' -NotePropertyValue (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ') -Force }
        if (-not $obj.repository)  { $obj | Add-Member -NotePropertyName 'repository'  -NotePropertyValue ([PSCustomObject]@{ root = $RepoRoot; branch = ''; commit = '' }) -Force }
        if (-not $obj.system)      { $obj | Add-Member -NotePropertyName 'system'      -NotePropertyValue ([PSCustomObject]@{ os = ''; osVersion = ''; pwshVersion = ''; javaVersion = ''; rustVersion = ''; capturedAt = '' }) -Force }
        if (-not $obj.tests)       { $obj | Add-Member -NotePropertyName 'tests'       -NotePropertyValue (@{ }) -Force }

        return $obj
    } catch {
        Write-Warning "test-session: Could not parse $path — starting fresh. Error: $($_.Exception.Message)"
        return New-SessionSkeleton -RepoRoot $RepoRoot
    }
}

# ═══════════════════════════════════════════════════════════════════
# Public: write the session object back to disk
# ═══════════════════════════════════════════════════════════════════
function Write-TestSession {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,

        [Parameter(Mandatory = $true)]
        $Session,

        [string]$SessionPath = ''
    )

    $path = Get-TestSessionPath -RepoRoot $RepoRoot -SessionPath $SessionPath
    $dir  = Split-Path -Parent $path

    if (-not (Test-Path -LiteralPath $dir -PathType Container)) {
        $null = New-Item -Path $dir -ItemType Directory -Force -ErrorAction Stop
    }

    # Trim history per test type
    foreach ($key in $Session.tests.Keys) {
        $entry = $Session.tests[$key]
        if ($entry.history -and $entry.history.Count -gt $script:MaxHistory) {
            $entry.history = @($entry.history | Select-Object -Last $script:MaxHistory)
        }
    }

    $Session.generatedAt = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')

    $json = $Session | ConvertTo-Json -Depth 6 -Compress
    $json | Set-Content -LiteralPath $path -Encoding UTF8
}

# ═══════════════════════════════════════════════════════════════════
# Public: capture system environment (runs once)
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Capture OS, pwsh, Java, and Rust versions into the session.
    Skips silently if the system block already has a capturedAt timestamp.

.PARAMETER RepoRoot
    Repository root path.

.PARAMETER Force
    Re-capture even if already captured.
#>
function Update-TestSessionSystem {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,

        [switch]$Force,

        [string]$SessionPath = ''
    )

    $session = Read-TestSession -RepoRoot $RepoRoot -SessionPath $SessionPath

    if (-not $Force -and $session.system.capturedAt) {
        return
    }

    # ── OS ──────────────────────────────────────────────────────────
    $os = ''
    $osVersion = ''
    try {
        $isWin = (Get-Variable -Name 'IsWindows' -ErrorAction SilentlyContinue) -and $IsWindows
        if ($isWin -or ($env:OS -eq 'Windows_NT')) {
            $os = 'Windows'
            $osVersion = [System.Environment]::OSVersion.VersionString
        } elseif ($IsLinux) {
            $os = 'Linux'
            try { $osVersion = (uname -r 2>$null) -replace '\s+', '' } catch { }
        } elseif ($IsMacOS) {
            $os = 'macOS'
            try { $osVersion = (sw_vers -productVersion 2>$null) -replace '\s+', '' } catch { }
        } else {
            $os = [System.Environment]::OSVersion.Platform.ToString()
            $osVersion = [System.Environment]::OSVersion.VersionString
        }
    } catch { }

    # ── PowerShell ──────────────────────────────────────────────────
    $pwshVersion = ''
    try {
        $pwshVersion = $PSVersionTable.PSVersion.ToString()
    } catch { }

    # ── Java ────────────────────────────────────────────────────────
    $javaVersion = ''
    try {
        $javaOut = & java -version 2>&1 | Select-Object -First 1
        if ($javaOut -match 'version\s+"?([\d._]+)"?') {
            $javaVersion = $Matches[1]
        } elseif ($javaOut -match 'version\s+"?([^"]+)"?') {
            $javaVersion = ($javaOut -split '\s+')[-1] -replace '"', ''
        }
    } catch { }

    # ── Rust ────────────────────────────────────────────────────────
    $rustVersion = ''
    try {
        $rustOut = & rustc --version 2>&1
        if ($rustOut -match 'rustc\s+(\S+)') {
            $rustVersion = $Matches[1]
        }
    } catch { }

    $session.system = [PSCustomObject]@{
        os          = $os
        osVersion   = $osVersion
        pwshVersion = $pwshVersion
        javaVersion = $javaVersion
        rustVersion = $rustVersion
        capturedAt  = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    }

    Write-TestSession -RepoRoot $RepoRoot -Session $session -SessionPath $SessionPath
}

# ═══════════════════════════════════════════════════════════════════
# Public: record a test result
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Record the outcome of a test type run in the persistent session.

.PARAMETER RepoRoot
    Repository root path.

.PARAMETER TestKey
    Test type key (e.g. "ps", "cli", "maven:fast", "rws:scenarios").

.PARAMETER Status
    "pass" or "fail".

.PARAMETER ExitCode
    Observed exit code.

.PARAMETER DurationSec
    Total wall-clock duration in seconds.

.PARAMETER LogDir
    Optional log directory path for this run.

.PARAMETER FailureReport
    Optional path to a structured failure report (e.g. failures.json).

.PARAMETER PerFileResults
    Optional array of per-file results.  Each entry is a hashtable with:
      path       — relative file path
      status     — "pass" or "fail"
      exitCode   — (optional) per-file exit code
      durationSec — per-file duration
#>
function Update-TestSessionResult {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory = $true)]
        [string]$RepoRoot,

        [Parameter(Mandatory = $true)]
        [string]$TestKey,

        [Parameter(Mandatory = $true)]
        [ValidateSet('pass', 'fail')]
        [string]$Status,

        [Parameter(Mandatory = $true)]
        [int]$ExitCode,

        [Parameter(Mandatory = $true)]
        [double]$DurationSec,

        [string]$LogDir = '',

        [string]$FailureReport = '',

        [object[]]$PerFileResults = @(),

        [string]$SessionPath = ''
    )

    $session = Read-TestSession -RepoRoot $RepoRoot -SessionPath $SessionPath

    $now = (Get-Date).ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ssZ')
    $existing = $session.tests[$TestKey]

    # Build the current result entry
    $result = [PSCustomObject]@{
        lastStatus        = $Status
        lastExitCode      = $ExitCode
        lastRunAt         = $now
        lastDurationSec   = $DurationSec
        totalRuns         = if ($existing -and $existing.totalRuns) { $existing.totalRuns + 1 } else { 1 }
        totalPasses       = if ($existing -and $existing.totalPasses) { $existing.totalPasses + $(if ($Status -eq 'pass') { 1 } else { 0 }) } else { $(if ($Status -eq 'pass') { 1 } else { 0 }) }
        totalFailures     = if ($existing -and $existing.totalFailures) { $existing.totalFailures + $(if ($Status -eq 'fail') { 1 } else { 0 }) } else { $(if ($Status -eq 'fail') { 1 } else { 0 }) }
        lastLogDir        = if ($LogDir) { $LogDir } else { '' }
        lastFailureReport = if ($FailureReport) { $FailureReport } else { $null }
    }

    # ── Per-file results (ps tests only) ────────────────────────────
    if ($PerFileResults.Count -gt 0) {
        $totalFiles  = $PerFileResults.Count
        $passedFiles = ($PerFileResults | Where-Object { $_.status -eq 'pass' }).Count
        $failedFiles = ($PerFileResults | Where-Object { $_.status -ne 'pass' }).Count

        $fileEntries = @($PerFileResults | ForEach-Object {
            $entry = [PSCustomObject]@{
                path        = $_.path
                status      = $_.status
                durationSec = $_.durationSec
            }
            if ($_.exitCode) { $entry | Add-Member -NotePropertyName 'exitCode' -NotePropertyValue ([int]$_.exitCode) }
            $entry
        })

        $result | Add-Member -NotePropertyName 'lastResults' -NotePropertyValue ([PSCustomObject]@{
            totalFiles  = $totalFiles
            passedFiles = $passedFiles
            failedFiles = $failedFiles
            files       = $fileEntries
        })
    }

    # ── History ─────────────────────────────────────────────────────
    $historyEntry = [PSCustomObject]@{
        status      = $Status
        exitCode    = $ExitCode
        durationSec = $DurationSec
        runAt       = $now
    }

    if ($existing -and $existing.history) {
        $history = [System.Collections.ArrayList]::new()
        foreach ($h in $existing.history) { [void]$history.Add($h) }
        [void]$history.Add($historyEntry)
        if ($history.Count -gt $script:MaxHistory) {
            $history = $history | Select-Object -Last $script:MaxHistory
        }
        $result | Add-Member -NotePropertyName 'history' -NotePropertyValue @($history)
    } else {
        $result | Add-Member -NotePropertyName 'history' -NotePropertyValue @($historyEntry)
    }

    $session.tests[$TestKey] = $result
    Write-TestSession -RepoRoot $RepoRoot -Session $session -SessionPath $SessionPath
}

# ═══════════════════════════════════════════════════════════════════
# Exports
# ═══════════════════════════════════════════════════════════════════
Export-ModuleMember -Function @(
    'Get-TestSessionPath',
    'Read-TestSession',
    'Write-TestSession',
    'Update-TestSessionSystem',
    'Update-TestSessionResult'
)
