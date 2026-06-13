# test-utils.psm1
# Shared test utilities for browser4-cli integration tests.
#
# Provides:
#   - CLI invocation wrapping with full stdout/stderr logging to per-script dirs
#   - Per-command status tracking (exit code, elapsed, pass/fail)
#   - Aggregated failure reporting with log paths
#   - Copilot AI analysis on failure (when `copilot` is on PATH)
#
# Dot-source in any test script:
#   Import-Module "$PSScriptRoot\test-utils.psm1" -Force
#
# Then wrap every browser4-cli call with Invoke-TrackedCli or use the
# lower-level Register-CliResult to log results from custom invocations.

# ============================================================================
# Module-level state (script-scoped so it won't leak between tests)
# ============================================================================
$script:TestName        = ''
$script:LogDir          = ''
$script:CommandLogs     = [System.Collections.ArrayList]::new()
$script:Failures        = [System.Collections.ArrayList]::new()
$script:TestStartTime   = Get-Date
$script:CommandIndex    = 0
$script:AiAnalyzer      = $null  # resolved AI CLI: 'claude', 'copilot', or $null

# ============================================================================
# Internal helpers
# ============================================================================

# Resolve the CLI binary once per session; honours $env:BROWSER4_CLI_BIN.
# Returns the path to use for `& ...`.
function Get-CliBin {
    if ($env:BROWSER4_CLI_BIN) {
        return $env:BROWSER4_CLI_BIN
    }
    # Use @(...) to always get an array; npm on Windows installs both
    # browser4-cli.cmd and extensionless browser4-cli — we pick the first.
    $cmds = @(Get-Command 'browser4-cli' -CommandType Application -ErrorAction SilentlyContinue)
    if ($cmds.Count -gt 0) {
        return $cmds[0].Source
    }
    return 'browser4-cli'
}

# Resolve the best available AI analyzer on PATH (cached).
# Priority: claude > copilot
function Get-AiAnalyzer {
    if ($null -ne $script:AiAnalyzer) {
        if ($script:AiAnalyzer -eq 'none') { return $null }
        return $script:AiAnalyzer
    }
    # Prefer claude
    $claude = Get-Command 'claude' -CommandType Application -ErrorAction SilentlyContinue
    if ($null -ne $claude) {
        $script:AiAnalyzer = 'claude'
        Write-Host "  ℹ claude detected on PATH — AI failure analysis will run" -ForegroundColor DarkGray
        return 'claude'
    }
    # Fall back to copilot
    $copilot = Get-Command 'copilot' -CommandType Application -ErrorAction SilentlyContinue
    if ($null -ne $copilot) {
        $script:AiAnalyzer = 'copilot'
        Write-Host "  ℹ copilot detected on PATH — AI failure analysis will run" -ForegroundColor DarkGray
        return 'copilot'
    }
    $script:AiAnalyzer = 'none'
    return $null
}

# Backward-compatible alias
function Test-CopilotAvailable {
    return $null -ne (Get-AiAnalyzer)
}

# Sanitise a string for use in a filename.
function ConvertTo-SafeFileName {
    param([string]$InputString)
    if (-not $InputString) { return 'unnamed' }
    $s = $InputString -replace '[^a-zA-Z0-9_.-]', '_'
    $s = $s -replace '_+', '_'
    $s = $s.Trim('_')
    if ($s.Length -gt 80) { $s = $s.Substring(0, 80) }
    if (-not $s) { return 'unnamed' }
    return $s
}

# ============================================================================
# Public: initialise logging for a test script
# ============================================================================
<#
.SYNOPSIS
    Initialise the per-test log directory and reset counters.
    Call once at the top of each test script.
#>
function Start-TestSession {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Name,

        [string]$LogBaseDir
    )

    if (-not $LogBaseDir) {
        # Default to bin/tests/logs/<name>/
        $LogBaseDir = Join-Path $PSScriptRoot 'logs'
    }

    $timestamp = Get-Date -Format 'yyyyMMdd_HHmmss'
    $script:TestName      = $Name
    $script:LogDir        = Join-Path $LogBaseDir "${Name}_${timestamp}"
    $script:CommandLogs   = [System.Collections.ArrayList]::new()
    $script:Failures      = [System.Collections.ArrayList]::new()
    $script:TestStartTime = Get-Date
    $script:CommandIndex  = 0
    $script:AiAnalyzer    = $null

    $null = New-Item -Path $script:LogDir -ItemType Directory -Force -ErrorAction SilentlyContinue

    Write-Host "`n📁 Log directory: $script:LogDir" -ForegroundColor DarkGray
}

# ============================================================================
# Public: run a CLI command and capture everything
# ============================================================================
<#
.SYNOPSIS
    Execute a browser4-cli command, log stdout/stderr to file, track status.

.DESCRIPTION
    Runs `browser4-cli <Arguments>` (honouring $env:BROWSER4_CLI_BIN), merges
    stdout and stderr, writes the full transcript to a timestamped log file,
    and records pass/fail status.  The command output is returned to the
    caller for further inspection.

.PARAMETER Arguments
    Arguments to pass to browser4-cli.

.PARAMETER Label
    Human-readable label for this command (shown in status lines and used in
    the log filename).  Defaults to "cli <Arguments>".

.PARAMETER ExpectedExitCode
    Exit code that constitutes success (default: 0).  Use -1 to always PASS
    regardless of exit code.

.PARAMETER PassThruOnly
    When set, the function still logs, but always reports PASS regardless of
    exit code.  Useful for commands where a non-zero exit is expected/benign
    (e.g. `close` with no session open).
#>
function Invoke-TrackedCli {
    [CmdletBinding()]
    param(
        [Parameter(Mandatory=$true, Position=0)]
        [string[]]$Arguments,

        [string]$Label = '',

        [int]$ExpectedExitCode = 0,

        [switch]$PassThruOnly
    )

    $script:CommandIndex++
    $idx = $script:CommandIndex

    $cliBin = Get-CliBin
    $cmdLabel = if ($Label) { $Label } else { "browser4-cli $($Arguments -join ' ')" }
    $safeName = ConvertTo-SafeFileName -InputString ($Arguments -join '_')
    $logName = "cmd_{0:D4}_{1}.log" -f $idx, $safeName
    $logFile = Join-Path $script:LogDir $logName

    $sw = [Diagnostics.Stopwatch]::StartNew()
    $startTime = Get-Date

    # --- Execute ---
    $output = @()
    $exitCode = 0
    try {
        $raw = & $cliBin @Arguments 2>&1
        $exitCode = $LASTEXITCODE
        if ($raw) {
            $output = @($raw | ForEach-Object { "$_" })
        }
    } catch {
        $output = @("EXCEPTION: $($_.Exception.Message)")
        $exitCode = -99
    }

    $sw.Stop()
    $endTime = Get-Date
    $elapsed = $sw.Elapsed

    # --- Determine status ---
    if ($PassThruOnly) {
        $passed = $true
    } else {
        $passed = ($exitCode -eq $ExpectedExitCode)
    }
    $status = if ($passed) { 'PASS' } else { 'FAIL' }

    # --- Write log file ---
    $outputText = $output -join "`n"
    $logEntry = @"
================================================================================
COMMAND     : $cmdLabel
CLI BINARY  : $cliBin
STARTED     : $($startTime.ToString('yyyy-MM-dd HH:mm:ss.fff'))
FINISHED    : $($endTime.ToString('yyyy-MM-dd HH:mm:ss.fff'))
ELAPSED     : $('{0:F1}' -f $elapsed.TotalSeconds)s
EXIT CODE   : $exitCode
EXPECTED    : $ExpectedExitCode
STATUS      : $status
================================================================================
STDOUT / STDERR:
$outputText
================================================================================
"@
    try {
        $logEntry | Set-Content -Path $logFile -Encoding UTF8
    } catch {
        Write-Warning "Could not write log file: $logFile"
    }

    # --- Track ---
    $statusObj = [PSCustomObject]@{
        Index     = $idx
        Command   = $cmdLabel
        ExitCode  = $exitCode
        Expected  = $ExpectedExitCode
        Elapsed   = $elapsed.TotalSeconds
        Status    = $status
        Passed    = $passed
        LogFile   = $logFile
        StartTime = $startTime
    }
    $null = $script:CommandLogs.Add($statusObj)

    if (-not $passed) {
        $null = $script:Failures.Add($statusObj)
    }

    # --- Console feedback ---
    $icon = if ($passed) { '✅' } else { '❌' }
    $color = if ($passed) { 'Green' } else { 'Red' }
    Write-Host "  $icon $status $('{0:F1}s' -f $elapsed.TotalSeconds)  $cmdLabel" -ForegroundColor $color
    if (-not $passed) {
        Write-Host "       exit=$exitCode expected=$ExpectedExitCode  📄 $logFile" -ForegroundColor Red
    }

    # Emit the output object so callers can pipe/inspect
    # We output the lines as strings so they behave like normal CLI output.
    $output
}

# ============================================================================
# Public: register a CLI result from an external invocation
# ============================================================================
<#
.SYNOPSIS
    Log a CLI command result when the invocation was done outside
    Invoke-TrackedCli (e.g. via Start-Process or a scriptblock).

.PARAMETER Label
    Human-readable label.

.PARAMETER ExitCode
    The observed exit code.

.PARAMETER OutputLines
    Array of captured stdout/stderr lines.

.PARAMETER Elapsed
    How long the command took.

.PARAMETER ExpectedExitCode
    Exit code considered a pass (default: 0).
#>
function Register-CliResult {
    param(
        [Parameter(Mandatory=$true)]
        [string]$Label,

        [int]$ExitCode,

        [string[]]$OutputLines,

        [timespan]$Elapsed,

        [int]$ExpectedExitCode = 0
    )

    $script:CommandIndex++
    $idx = $script:CommandIndex

    $safeName = ConvertTo-SafeFileName -InputString $Label
    $logName = "cmd_{0:D4}_{1}.log" -f $idx, $safeName
    $logFile = Join-Path $script:LogDir $logName

    $passed = ($ExitCode -eq $ExpectedExitCode)
    $status = if ($passed) { 'PASS' } else { 'FAIL' }

    $outputText = ($OutputLines -join "`n") | Out-String

    $logEntry = @"
================================================================================
COMMAND     : $Label
CLI BINARY  : $(Get-CliBin)
ELAPSED     : $('{0:F1}' -f $Elapsed.TotalSeconds)s
EXIT CODE   : $ExitCode
EXPECTED    : $ExpectedExitCode
STATUS      : $status
================================================================================
STDOUT / STDERR:
$outputText
================================================================================
"@
    try {
        $logEntry | Set-Content -Path $logFile -Encoding UTF8
    } catch {
        Write-Warning "Could not write log file: $logFile"
    }

    $statusObj = [PSCustomObject]@{
        Index     = $idx
        Command   = $Label
        ExitCode  = $ExitCode
        Expected  = $ExpectedExitCode
        Elapsed   = $Elapsed.TotalSeconds
        Status    = $status
        Passed    = $passed
        LogFile   = $logFile
        StartTime = Get-Date
    }
    $null = $script:CommandLogs.Add($statusObj)

    if (-not $passed) {
        $null = $script:Failures.Add($statusObj)
    }

    $statusObj
}

# ============================================================================
# Public: getters
# ============================================================================
function Get-LogDir {
    return $script:LogDir
}

function Get-FailureCount {
    return $script:Failures.Count
}

function Get-CommandCount {
    return $script:CommandLogs.Count
}

function Get-PassCount {
    return ($script:CommandLogs | Where-Object { $_.Passed }).Count
}

function Get-FailureLogPaths {
    return @($script:Failures | ForEach-Object { $_.LogFile })
}

function Get-AllLogPaths {
    return @($script:CommandLogs | ForEach-Object { $_.LogFile })
}

# ============================================================================
# Public: AI failure analysis (prefers claude, falls back to copilot)
# ============================================================================
<#
.SYNOPSIS
    Invoke the best available AI CLI to analyse test failures.
    Priority: `claude -p "..."` > `copilot -p "..."`.
    Log paths are collected from tracked failures automatically.
    Call during Finish-TestSession (which calls this automatically)
    or directly when you have custom log paths.
#>
function Invoke-CopilotAnalysis {
    param(
        [string[]]$LogPaths,
        [string]$ExtraPrompt = ''
    )

    $analyzer = Get-AiAnalyzer
    if (-not $analyzer) {
        Write-Host "  ⚠ Neither claude nor copilot found on PATH — skipping AI failure analysis" -ForegroundColor Yellow
        return $null
    }

    $paths = if ($LogPaths -and $LogPaths.Count -gt 0) {
        $LogPaths
    } else {
        Get-FailureLogPaths
    }

    if ($paths.Count -eq 0) {
        Write-Host "  ℹ No failure logs to analyse" -ForegroundColor DarkGray
        return $null
    }

    $logList = ($paths | ForEach-Object { "  - $_" }) -join "`n"
    $prompt = "analyze the failures, here are the logs:`n$logList"
    if ($ExtraPrompt) {
        $prompt = "$ExtraPrompt`n$prompt"
    }

    Write-Host "`n🤖 Running failure analysis with $analyzer ..." -ForegroundColor Magenta
    Write-Host "   Prompt: $prompt" -ForegroundColor DarkGray

    try {
        $analysis = & $analyzer -p $prompt 2>&1
        $analysisText = ($analysis | Out-String).Trim()

        # Save analysis to file
        $analysisFile = Join-Path $script:LogDir "${analyzer}-analysis.txt"
        $analysisText | Set-Content -Path $analysisFile -Encoding UTF8

        $header = "─── ${analyzer^^} ANALYSIS ─────────────────────────────────────"
        Write-Host "`n$header" -ForegroundColor Magenta
        Write-Host $analysisText
        Write-Host "──────────────────────────────────────────────────────────" -ForegroundColor Magenta
        Write-Host "  📄 Analysis saved to: $analysisFile" -ForegroundColor DarkGray

        return $analysisText
    } catch {
        Write-Host "  ⚠ $analyzer invocation failed: $($_.Exception.Message)" -ForegroundColor Yellow
        return $null
    }
}

# ============================================================================
# Public: print final report and set exit code
# ============================================================================
<#
.SYNOPSIS
    Print a summary of all tracked commands, report failures with log paths,
    invoke copilot analysis if failures exist, and return the appropriate
    exit code.

.DESCRIPTION
    Call this at the end of every test script.  It prints:
      - Total / passed / failed counts
      - Elapsed wall-clock time
      - Log directory path
      - For each failure: the command, exit code, and log file path
      - Copilot analysis (if available and failures present)

    Returns 0 if all commands passed, 1 otherwise (suitable for `exit`).
#>
function Finish-TestSession {
    [CmdletBinding()]
    param(
        [string]$ExtraCopilotPrompt = ''
    )

    $total  = $script:CommandLogs.Count
    $passed = (Get-PassCount)
    $failed = (Get-FailureCount)
    $elapsed = (Get-Date) - $script:TestStartTime

    Write-Host "`n"
    Write-Host '══════════════════════════════════════════════════════════' -ForegroundColor Cyan
    Write-Host "  TEST REPORT: $script:TestName" -ForegroundColor Cyan
    Write-Host '══════════════════════════════════════════════════════════' -ForegroundColor Cyan
    Write-Host "  Log directory : $script:LogDir"
    Write-Host "  CLI binary    : $(Get-CliBin)"
    if (Test-Path (Get-CliBin)) {
        try { $ver = & (Get-CliBin) --version 2>&1; Write-Host "  CLI version   : $($ver -join ' ')" -ForegroundColor DarkGray } catch {}
    }
    Write-Host "  Started       : $($script:TestStartTime.ToString('yyyy-MM-dd HH:mm:ss'))"
    Write-Host "  Finished      : $((Get-Date).ToString('yyyy-MM-dd HH:mm:ss'))"
    Write-Host ("  Elapsed       : {0:hh\:mm\:ss}" -f $elapsed)
    Write-Host "  ─────────────────────────────────────────────"
    Write-Host "  Commands      : $total"
    Write-Host "  Passed        : $passed" -ForegroundColor $(if ($passed -gt 0) { 'Green' } else { 'DarkGray' })
    Write-Host "  Failed        : $failed" -ForegroundColor $(if ($failed -gt 0) { 'Red' } else { 'DarkGray' })

    # Per-command detail table
    if ($total -gt 0) {
        Write-Host "`n  ── COMMAND DETAIL ──" -ForegroundColor DarkGray
        foreach ($cmd in $script:CommandLogs) {
            $icon = if ($cmd.Passed) { '✅' } else { '❌' }
            $color = if ($cmd.Passed) { 'Green' } else { 'Red' }
            Write-Host ("  $icon exit={0,-4} {1,7:F1}s  {2}" -f $cmd.ExitCode, $cmd.Elapsed, $cmd.Command) -ForegroundColor $color
        }
    }

    # Failure details with log paths
    if ($failed -gt 0) {
        Write-Host "`n  ── FAILURE DETAILS ──" -ForegroundColor Red
        foreach ($f in $script:Failures) {
            Write-Host "  ❌ $($f.Command)" -ForegroundColor Red
            Write-Host "     Exit code : $($f.ExitCode) (expected $($f.Expected))" -ForegroundColor Red
            Write-Host "     Elapsed   : $('{0:F1}s' -f $f.Elapsed)" -ForegroundColor Red
            Write-Host "     Log file  : $($f.LogFile)" -ForegroundColor DarkGray
            Write-Host ''
        }

        # Copilot analysis
        Invoke-CopilotAnalysis -ExtraPrompt $ExtraCopilotPrompt
    } else {
        Write-Host "`n  ✅ ALL $total COMMANDS PASSED" -ForegroundColor Green
    }

    Write-Host '══════════════════════════════════════════════════════════' -ForegroundColor Cyan
    Write-Host ''

    return $(if ($failed -eq 0) { 0 } else { 1 })
}

# ============================================================================
# Public: quick helper for simple scripts — run a CLI command and assert exit
# ============================================================================
<#
.SYNOPSIS
    Convenience function: run a CLI command, check exit code, return output.
    Combines Invoke-TrackedCli with an assertion.
#>
function Invoke-CliAssert {
    param(
        [string[]]$Arguments,
        [string]$Label = '',
        [int]$ExpectedExitCode = 0
    )
    $output = Invoke-TrackedCli -Arguments $Arguments -Label $Label -ExpectedExitCode $ExpectedExitCode
    return $output
}

# ============================================================================
# Log a test-script start marker (used by the orchestrator)
# ============================================================================
function Write-TestHeader {
    param([string]$Name)
    Write-Host "`n══════════════════════════════════════════════════════════" -ForegroundColor Cyan
    Write-Host "  TEST: $Name" -ForegroundColor Cyan
    Write-Host "  TIME: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')" -ForegroundColor Cyan
    Write-Host "══════════════════════════════════════════════════════════" -ForegroundColor Cyan
}

# ============================================================================
# Locale-aware test URL support
# ============================================================================
<#
.SYNOPSIS
    Curated set of 20 test URLs (10 global + 10 Chinese websites) categorised
    by purpose, site group, display name, and a snapshot keyword for assertions.

    Each entry in the en / zh arrays is a PSCustomObject:
      .url     — full URL (string)
      .purpose — semantic category: portal, reference, tech-news, tech-community,
                  product, ecommerce, simple, qa, social, video
      .site    — domain group for multi-page navigation tests (string)
      .name    — human-readable label (string)
      .keyword — substring expected in a page snapshot after load (string,
                 typically ASCII for en, Unicode for zh)
#>

$script:TestUrlStore = @{
    'en' = @(
        [PSCustomObject]@{ url = 'https://www.wikipedia.org/';                    purpose = 'portal';          site = 'wikipedia';     name = 'Wikipedia Main';      keyword = 'wikipedia' }
        [PSCustomObject]@{ url = 'https://en.wikipedia.org/wiki/Web_browser';     purpose = 'reference';       site = 'wikipedia';     name = 'Web Browser';         keyword = 'browser' }
        [PSCustomObject]@{ url = 'https://en.wikipedia.org/wiki/Internet';        purpose = 'reference';       site = 'wikipedia';     name = 'Internet';            keyword = 'Internet' }
        [PSCustomObject]@{ url = 'https://news.ycombinator.com/';                 purpose = 'tech-news';       site = 'hackernews';    name = 'HN Front';            keyword = 'ycombinator' }
        [PSCustomObject]@{ url = 'https://news.ycombinator.com/newest';           purpose = 'tech-news';       site = 'hackernews';    name = 'HN Newest';           keyword = 'ycombinator' }
        [PSCustomObject]@{ url = 'https://github.com/';                           purpose = 'tech-community';  site = 'github';        name = 'GitHub Home';         keyword = 'github' }
        [PSCustomObject]@{ url = 'https://github.com/explore';                    purpose = 'tech-community';  site = 'github';        name = 'GitHub Explore';      keyword = 'github' }
        [PSCustomObject]@{ url = 'https://www.amazon.com/dp/B08PP5MSVB';          purpose = 'product';         site = 'amazon';        name = 'Amazon Product';      keyword = 'Amazon' }
        [PSCustomObject]@{ url = 'https://example.com/';                          purpose = 'simple';          site = 'example';       name = 'Example';             keyword = 'example' }
        [PSCustomObject]@{ url = 'https://stackoverflow.com/';                    purpose = 'qa';              site = 'stackoverflow'; name = 'Stack Overflow';      keyword = 'stackoverflow' }
    )
    'zh' = @(
        [PSCustomObject]@{ url = 'https://www.baidu.com/';                        purpose = 'portal';          site = 'baidu';         name = 'Baidu';               keyword = '百度' }
        [PSCustomObject]@{ url = 'https://www.oschina.net/';                     purpose = 'tech-community';  site = 'oschina';       name = 'OSChina';             keyword = '开源中国' }
        [PSCustomObject]@{ url = 'https://www.bilibili.com/';                     purpose = 'video';           site = 'bilibili';      name = 'Bilibili';            keyword = '哔哩哔哩' }
        [PSCustomObject]@{ url = 'https://www.163.com/';                          purpose = 'portal';          site = '163';           name = 'NetEase';             keyword = '网易' }
        [PSCustomObject]@{ url = 'https://www.jd.com/';                           purpose = 'ecommerce';       site = 'jd';            name = 'JD.com';              keyword = '京东' }
        [PSCustomObject]@{ url = 'https://www.csdn.net/';                         purpose = 'tech-community';  site = 'csdn';          name = 'CSDN';                keyword = 'CSDN' }
        [PSCustomObject]@{ url = 'https://www.douban.com/';                       purpose = 'social';          site = 'douban';        name = 'Douban';              keyword = '豆瓣' }
        [PSCustomObject]@{ url = 'https://www.sina.com.cn/';                      purpose = 'portal';          site = 'sina';          name = 'Sina';                keyword = '新浪' }
        [PSCustomObject]@{ url = 'https://www.runoob.com/';                       purpose = 'reference';       site = 'runoob';        name = 'Runoob Tutorials';    keyword = '菜鸟' }
        [PSCustomObject]@{ url = 'https://www.hua.com/flower/';                   purpose = 'ecommerce';       site = 'hua';           name = 'Hua Flower';          keyword = '花' }
    )
}

<#
.SYNOPSIS
    Resolve the effective test locale.

.DESCRIPTION
    Priority chain (highest first):
      1. Explicit -Locale parameter
      2. $env:BROWSER4_TEST_LOCALE environment variable
      3. [System.Globalization.CultureInfo]::CurrentCulture.TwoLetterISOLanguageName
      4. Hard fallback: 'en'

    The returned value is always a lowercase two-letter ISO-639-1 code
    (e.g. 'en', 'zh', 'ja').

.PARAMETER Locale
    Explicit locale override (accepts full culture names like 'zh-CN' — the
    first two characters are extracted).
#>
function Get-TestLocale {
    [CmdletBinding()]
    param(
        [string]$Locale = ''
    )

    if ($Locale) {
        $normalized = $Locale.ToLower()
        if ($normalized.Length -ge 2) { $normalized = $normalized.Substring(0, 2) }
        return $normalized
    }

    if ($env:BROWSER4_TEST_LOCALE) {
        $normalized = $env:BROWSER4_TEST_LOCALE.ToLower()
        if ($normalized.Length -ge 2) { $normalized = $normalized.Substring(0, 2) }
        return $normalized
    }

    try {
        $ci = [System.Globalization.CultureInfo]::CurrentCulture
        return $ci.TwoLetterISOLanguageName.ToLower()
    } catch {
        return 'en'
    }
}

<#
.SYNOPSIS
    Return the set of test URL entries for a given locale, optionally filtered.

.DESCRIPTION
    Returns zero or more PSCustomObject entries from the curated URL store.
    Each entry has: .url, .purpose, .site, .name, .keyword.

.PARAMETER Locale
    Two-letter locale code (e.g. 'en', 'zh').  Auto-detected when omitted.

.PARAMETER Site
    When provided, return only entries whose .site matches this value.

.PARAMETER Purpose
    When provided, return only entries whose .purpose matches this value.

.PARAMETER IncludeTimestamp
    Append ?b4_stress=<unix-timestamp> to every URL so each run produces
    fresh results (used by stress / swarm seed-file tests).

.EXAMPLE
    # All 10 English URLs
    Get-TestUrlSet -Locale en

.EXAMPLE
    # Chinese tech-community URLs only
    Get-TestUrlSet -Locale zh -Purpose tech-community

.EXAMPLE
    # All URLs for auto-detected locale with cache-busting timestamps
    Get-TestUrlSet -IncludeTimestamp
#>
function Get-TestUrlSet {
    [CmdletBinding()]
    param(
        [string]$Locale = '',
        [string]$Site = '',
        [string]$Purpose = '',
        [switch]$IncludeTimestamp
    )

    $resolvedLocale = Get-TestLocale -Locale $Locale

    if (-not $script:TestUrlStore.ContainsKey($resolvedLocale)) {
        Write-Warning "Get-TestUrlSet: no URL store for locale '$resolvedLocale'; falling back to 'en'"
        $resolvedLocale = 'en'
    }

    $entries = $script:TestUrlStore[$resolvedLocale]

    if ($Site)    { $entries = @($entries | Where-Object { $_.site -eq $Site }) }
    if ($Purpose) { $entries = @($entries | Where-Object { $_.purpose -eq $Purpose }) }

    if ($IncludeTimestamp) {
        $stamp = [DateTimeOffset]::UtcNow.ToUnixTimeSeconds()
        $entries = @($entries | ForEach-Object {
            $u = $_.url
            $sep = if ($u -match '\?') { '&' } else { '?' }
            [PSCustomObject]@{
                url     = "$u${sep}b4_stress=$stamp"
                purpose = $_.purpose
                site    = $_.site
                name    = $_.name
                keyword = $_.keyword
            }
        })
    }

    return $entries
}

<#
.SYNOPSIS
    Convenience wrapper: return a single test URL string for a given purpose
    and locale.

.DESCRIPTION
    Calls Get-TestUrlSet with the given -Purpose and -Locale, picks the first
    match.  When no entry matches the purpose, falls back to the first URL in
    the resolved locale and emits a warning.

.PARAMETER Purpose
    Semantic category to select (e.g. 'product', 'ecommerce', 'simple').
    Defaults to 'product'.

.PARAMETER Locale
    Two-letter locale code.  Auto-detected when omitted.

.EXAMPLE
    # Product page for auto-detected locale
    Get-TestUrl -Purpose product

.EXAMPLE
    # Chinese ecommerce site
    Get-TestUrl -Purpose ecommerce -Locale zh
#>
function Get-TestUrl {
    [CmdletBinding()]
    param(
        [Parameter(Position = 0)]
        [string]$Purpose = 'product',

        [string]$Locale = ''
    )

    $resolvedLocale = Get-TestLocale -Locale $Locale
    $entry = Get-TestUrlSet -Locale $resolvedLocale -Purpose $Purpose | Select-Object -First 1

    if (-not $entry) {
        if (-not $script:TestUrlStore.ContainsKey($resolvedLocale)) { $resolvedLocale = 'en' }
        $entry = $script:TestUrlStore[$resolvedLocale] | Select-Object -First 1
        Write-Warning "Get-TestUrl: no URL for purpose='$Purpose' in locale '$resolvedLocale'; using '$($entry.name)'"
    }

    return $entry.url
}

# Export functions
Export-ModuleMember -Function @(
    'Start-TestSession',
    'Invoke-TrackedCli',
    'Register-CliResult',
    'Invoke-CopilotAnalysis',
    'Finish-TestSession',
    'Invoke-CliAssert',
    'Get-LogDir',
    'Get-FailureCount',
    'Get-CommandCount',
    'Get-PassCount',
    'Get-FailureLogPaths',
    'Get-AllLogPaths',
    'Test-CopilotAvailable',
    'Get-AiAnalyzer',
    'Get-CliBin',
    'Write-TestHeader',
    'Get-TestLocale',
    'Get-TestUrlSet',
    'Get-TestUrl'
)
