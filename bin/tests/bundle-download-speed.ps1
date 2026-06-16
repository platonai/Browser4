#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Measure runtime bundle download speed from each available source.

.DESCRIPTION
    Downloads the first 10 MB of the Browser4 runtime bundle from each
    configured mirror and measures throughput in MB/s.  This mirrors the
    speed-test behaviour built into browser4-cli (daemon.rs).

    Tests performed:
      1. GitHub Releases   — primary global CDN
      2. Alibaba Cloud OSS — preferred in China mainland
      3. Proxy ON           — through the detected system / env proxy
      4. Proxy OFF          — direct connection (NO_PROXY=*)

    The proxy tests are skipped when no proxy is detected.

    Each download uses an HTTP Range request (bytes=0-10485759) to keep
    the probe small (~10 MB) regardless of the full bundle size.

.PARAMETER Tag
    Release tag to download (e.g. "v4.11.0").  Default: "latest".

.PARAMETER ProbeMB
    Number of megabytes to download for each speed test (default: 10).

.PARAMETER TimeoutSecs
    Per-source download timeout in seconds (default: 30).

.PARAMETER Source
    Test only one source: "github", "oss", or "all" (default: "all").

.PARAMETER NoProxyTests
    Skip the proxy on/off comparison tests.

.PARAMETER Locate
    Print detection results (platform, asset name, proxy) and exit.

.EXAMPLE
    # Quick test of all sources
    powershell -ExecutionPolicy Bypass -File bundle-download-speed.ps1

.EXAMPLE
    # Test only GitHub
    powershell -ExecutionPolicy Bypass -File bundle-download-speed.ps1 -Source github

.EXAMPLE
    # Test a specific release tag
    powershell -ExecutionPolicy Bypass -File bundle-download-speed.ps1 -Tag v4.11.0

.EXAMPLE
    # Diagnostics only (no download)
    powershell -ExecutionPolicy Bypass -File bundle-download-speed.ps1 -Locate
#>

[CmdletBinding()]
param(
    [string]$Tag = '',
    [int]$ProbeMB = 10,
    [int]$TimeoutSecs = 30,
    [ValidateSet('github', 'oss', 'all')]
    [string]$Source = 'all',
    [switch]$NoProxyTests,
    [switch]$Locate
)

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Platform detection (PS 5.1+ compatible)
# -------------------------------------------------------------------
if ($PSVersionTable.PSVersion.Major -ge 6) {
    $script:OSWin   = $IsWindows
    $script:OSLinux = $IsLinux
    $script:OSMac   = $IsMacOS
} else {
    $script:OSWin   = [System.Environment]::OSVersion.Platform -eq 'Win32NT'
    $script:OSMac   = $false
    $script:OSLinux = $false
}

# -------------------------------------------------------------------
# Output helpers
# -------------------------------------------------------------------
function Write-Banner {
    param([string]$Text, [string]$Color = 'Cyan')
    Write-Host $Text -ForegroundColor $Color
}

function Write-Step {
    param([string]$Text)
    Write-Host "  » $Text" -ForegroundColor Gray
}

function Write-Result {
    param([string]$Text, [string]$Color = 'White')
    Write-Host "    $Text" -ForegroundColor $Color
}

function Write-Success {
    param([string]$Text)
    Write-Host "    ✓ $Text" -ForegroundColor Green
}

function Write-Warn {
    param([string]$Text)
    Write-Host "    ⚠ $Text" -ForegroundColor Yellow
}

# -------------------------------------------------------------------
# Runtime bundle asset name resolution (mirrors daemon.rs)
# -------------------------------------------------------------------
function Get-PlatformKey {
    $arch = if ([System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture -eq [System.Runtime.InteropServices.Architecture]::Arm64) {
        'arm64'
    } else {
        'x64'
    }

    if ($script:OSWin)   { return "windows-$arch" }
    if ($script:OSMac)   { return "darwin-$arch" }
    if ($script:OSLinux) {
        # Detect musl
        $isMusl = $false
        try {
            $lddOutput = ldd --version 2>&1
            if ($lddOutput -match 'musl') { $isMusl = $true }
        } catch {
            if ((Test-Path '/lib/ld-musl-x86_64.so.1') -or (Test-Path '/lib/ld-musl-aarch64.so.1')) {
                $isMusl = $true
            }
        }
        $libc = if ($isMusl) { 'musl' } else { '' }
        if ($libc) { return "linux-$libc-$arch" } else { return "linux-$arch" }
    }
    throw 'Unsupported OS. Browser4 CLI supports Windows, macOS, and Linux.'
}

function Get-RuntimeBundleAssetName {
    param([string]$PlatformKey)

    switch -Wildcard ($PlatformKey) {
        'windows-x64'   { return 'browser4-bundle-runtime-windows-x64.zip' }
        'linux-x64'     { return 'browser4-bundle-runtime-linux-x64.tar.gz' }
        'darwin-x64'    { return 'browser4-bundle-runtime-darwin-x64.tar.gz' }
        'darwin-arm64'  { return 'browser4-bundle-runtime-darwin-arm64.tar.gz' }
        default {
            throw "No runtime bundle asset for platform: $PlatformKey"
        }
    }
}

# -------------------------------------------------------------------
# URL construction (mirrors daemon.rs: mirror_download_url)
# -------------------------------------------------------------------
$GITHUB_REPO = 'platonai/Browser4'
$OSS_BASE    = 'https://browser4.oss-cn-beijing.aliyuncs.com'

function Get-MirrorUrls {
    param(
        [string]$AssetName,
        [string]$VersionTag
    )

    $urls = @()

    # --- GitHub ---
    if ($VersionTag) {
        $ghUrl = "https://github.com/$GITHUB_REPO/releases/download/$VersionTag/$AssetName"
    } else {
        $ghUrl = "https://github.com/$GITHUB_REPO/releases/latest/download/$AssetName"
    }
    $urls += @{ Name = 'github'; Label = 'GitHub Releases'; Url = $ghUrl }

    # --- Alibaba Cloud OSS ---
    if ($VersionTag) {
        $ossUrl = "$OSS_BASE/releases/download/$VersionTag/$AssetName"
    } else {
        $ossUrl = "$OSS_BASE/releases/download/latest/$AssetName"
    }
    $urls += @{ Name = 'aliyun-oss'; Label = 'Aliyun OSS'; Url = $ossUrl }

    return $urls
}

# -------------------------------------------------------------------
# Proxy detection
# -------------------------------------------------------------------
function Get-DetectedProxy {
    <#
    .SYNOPSIS
        Detect the current proxy configuration.
        Returns a hashtable with Url and Source, or $null if none found.
    #>

    # 1 — Environment variables (portable, primary on Unix)
    $envVars = @(
        'BROWSER4_CLI_PROXY',
        'https_proxy', 'HTTPS_PROXY',
        'http_proxy', 'HTTP_PROXY',
        'all_proxy', 'ALL_PROXY'
    )
    foreach ($var in $envVars) {
        $val = [System.Environment]::GetEnvironmentVariable($var)
        if ($val -and $val.Trim()) {
            $url = $val.Trim()
            # Ensure scheme
            if ($url -notmatch '^https?://' -and $url -notmatch '^socks5?://') {
                $url = "http://$url"
            }
            return @{ Url = $url; Source = "env:$var" }
        }
    }

    # 2 — Windows system proxy
    if ($script:OSWin) {
        $winProxy = Get-WindowsSystemProxy
        if ($winProxy) {
            return @{ Url = $winProxy; Source = 'WinHTTP/IE' }
        }
    }

    return $null
}

function Get-WindowsSystemProxy {
    <#
    .SYNOPSIS
        Read the Windows system proxy (WinHTTP or IE registry).
    #>

    # 2a — WinHTTP proxy (netsh)
    try {
        $netshOutput = & netsh winhttp show proxy 2>$null
        if ($LASTEXITCODE -eq 0) {
            foreach ($line in $netshOutput) {
                if ($line -match 'Proxy Server\(s\)\s*:\s*(.+)') {
                    $server = $Matches[1].Trim()
                    if ($server -and $server -ne 'direct') {
                        return Ensure-ProxyScheme $server
                    }
                }
            }
        }
    } catch { }

    # 2b — IE proxy via registry
    try {
        $key = 'HKCU:\Software\Microsoft\Windows\CurrentVersion\Internet Settings'
        $proxyEnable = (Get-ItemProperty -Path $key -Name ProxyEnable -ErrorAction SilentlyContinue).ProxyEnable
        if ($proxyEnable -eq 1) {
            $proxyServer = (Get-ItemProperty -Path $key -Name ProxyServer -ErrorAction SilentlyContinue).ProxyServer
            if ($proxyServer) {
                # Parse protocol-specific entries (https=host:port)
                if ($proxyServer -match 'https=([^;]+)') {
                    return Ensure-ProxyScheme $Matches[1].Trim()
                }
                return Ensure-ProxyScheme $proxyServer.Trim()
            }
        }
    } catch { }

    return $null
}

function Ensure-ProxyScheme {
    param([string]$Raw)
    $trimmed = $Raw.Trim()
    if ($trimmed -match '^https?://' -or $trimmed -match '^socks5?://') {
        return $trimmed
    }
    return "http://$trimmed"
}

# -------------------------------------------------------------------
# Locale / China mainland detection (zero-network, mirrors install script)
# -------------------------------------------------------------------
function Test-ChinaLocale {
    # 1 — Locale env vars
    $lang = $env:LC_ALL, $env:LANG, $env:LC_CTYPE, $env:LC_MESSAGES | Where-Object { $_ } | Select-Object -First 1
    if ($lang -and ($lang -match '^zh_CN' -or $lang -match '^zh-CN' -or $lang -match '^Chinese \(Simplified\)_China')) {
        return $true
    }

    # 2 — TZ env var
    $tzEnv = $env:TZ
    if ($tzEnv -and ($tzEnv -match '^Asia/(Shanghai|Chongqing|Urumqi|Harbin)$')) {
        return $true
    }

    # 3 — .NET TimeZoneInfo
    try {
        $tzId = [System.TimeZoneInfo]::Local.Id
        if ($tzId -match '^Asia/(Shanghai|Chongqing|Urumqi|Harbin)$') {
            return $true
        }
    } catch { }

    # 4 — /etc/timezone (Unix)
    if (-not $script:OSWin -and (Test-Path '/etc/timezone')) {
        try {
            $tz = Get-Content '/etc/timezone' -Raw -ErrorAction Stop
            if ($tz -match '^Asia/(Shanghai|Chongqing|Urumqi|Harbin)$') {
                return $true
            }
        } catch { }
    }

    return $false
}

# -------------------------------------------------------------------
# Speed test — download first N MB and measure throughput
# -------------------------------------------------------------------
function Test-DownloadSpeed {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Url,

        [Parameter(Mandatory = $true)]
        [string]$Label,

        [int]$ProbeBytes = 10485760,  # 10 MB

        [int]$TimeoutSecs = 30,

        [string]$ProxyUrl = '',       # When set, use this proxy

        [switch]$NoProxy              # When set, bypass all proxies
    )

    Write-Host ''
    Write-Host ('─' * 60) -ForegroundColor DarkGray
    Write-Banner "Testing: $Label" -Color Yellow
    Write-Step "URL: $Url"

    # Build the web-request parameters
    $params = @{
        Uri             = $Url
        UseBasicParsing = $true
        TimeoutSec      = $TimeoutSecs
        ErrorAction     = 'Stop'
    }

    # Range header — download only the first ProbeBytes
    $rangeHeader = "bytes=0-$($ProbeBytes - 1)"
    $params['Headers'] = @{ 'Range' = $rangeHeader }
    Write-Step "Range: $rangeHeader"

    # Proxy configuration
    if ($NoProxy) {
        Write-Step 'Proxy: DISABLED (NO_PROXY=* enforced)'
        # Temporarily clear proxy env vars for this invocation.
        # Invoke-WebRequest respects NO_PROXY, but we also clear
        # the vars directly to ensure a clean test.
        $origEnv = @{}
        $proxyVars = @(
            'BROWSER4_CLI_PROXY',
            'https_proxy', 'HTTPS_PROXY',
            'http_proxy', 'HTTP_PROXY',
            'all_proxy', 'ALL_PROXY',
            'no_proxy', 'NO_PROXY'
        )
        foreach ($var in $proxyVars) {
            $origEnv[$var] = [System.Environment]::GetEnvironmentVariable($var)
            [System.Environment]::SetEnvironmentVariable($var, '')
        }
        # Set NO_PROXY=* to block proxy usage
        [System.Environment]::SetEnvironmentVariable('NO_PROXY', '*')
        [System.Environment]::SetEnvironmentVariable('no_proxy', '*')
    } elseif ($ProxyUrl) {
        Write-Step "Proxy: $ProxyUrl"
        $params['Proxy'] = $ProxyUrl
        $params['ProxyUseDefaultCredentials'] = $false
    } else {
        Write-Step 'Proxy: system default (from environment)'
    }

    try {
        $sw = [Diagnostics.Stopwatch]::StartNew()
        $response = Invoke-WebRequest @params
        $sw.Stop()

        $statusCode = [int]$response.StatusCode
        $bytesReceived = $response.Content.Length
        $elapsedSecs = $sw.Elapsed.TotalSeconds
        $speedBps = if ($elapsedSecs -gt 0) { $bytesReceived / $elapsedSecs } else { $bytesReceived }
        $speedMBps = $speedBps / 1MB

        # HTTP 206 = Partial Content (Range honoured), 200 = server ignored Range
        if ($statusCode -eq 206 -or $statusCode -eq 200) {
            Write-Success "HTTP $statusCode | $('{0:N2}' -f $speedMBps) MB/s | $('{0:N0}' -f $bytesReceived) bytes in $('{0:F1}' -f ($elapsedSecs * 1000)) ms"

            return [PSCustomObject]@{
                Label          = $Label
                Url            = $Url
                StatusCode     = $statusCode
                BytesReceived  = $bytesReceived
                ElapsedMs      = [Math]::Round($elapsedSecs * 1000, 1)
                SpeedMBps      = [Math]::Round($speedMBps, 2)
                SpeedBps       = [Math]::Round($speedBps, 0)
                ProxyUsed      = if ($NoProxy) { 'none' } elseif ($ProxyUrl) { $ProxyUrl } else { 'system' }
                Error          = $null
            }
        } else {
            Write-Warn "Unexpected HTTP status: $statusCode"
            return [PSCustomObject]@{
                Label          = $Label
                Url            = $Url
                StatusCode     = $statusCode
                BytesReceived  = 0
                ElapsedMs      = 0
                SpeedMBps      = 0
                SpeedBps       = 0
                ProxyUsed      = if ($NoProxy) { 'none' } elseif ($ProxyUrl) { $ProxyUrl } else { 'system' }
                Error          = "HTTP $statusCode"
            }
        }
    } catch {
        $sw.Stop()
        $elapsedMs = [Math]::Round($sw.Elapsed.TotalMilliseconds, 1)
        $errMsg = if ($_.Exception.Response) {
            $statusCode = [int]$_.Exception.Response.StatusCode.value__
            "HTTP $statusCode"
        } elseif ($_.Exception -is [System.TimeoutException]) {
            'Timeout'
        } else {
            $_.Exception.Message -replace '\n', ' ' -replace '\s+', ' '
        }

        # Truncate long error messages
        if ($errMsg.Length -gt 120) { $errMsg = $errMsg.Substring(0, 117) + '...' }

        Write-Warn "Failed (${elapsedMs}ms): $errMsg"

        return [PSCustomObject]@{
            Label          = $Label
            Url            = $Url
            StatusCode     = 0
            BytesReceived  = 0
            ElapsedMs      = $elapsedMs
            SpeedMBps      = 0
            SpeedBps       = 0
            ProxyUsed      = if ($NoProxy) { 'none' } elseif ($ProxyUrl) { $ProxyUrl } else { 'system' }
            Error          = $errMsg
        }
    } finally {
        # Restore original proxy environment
        if ($NoProxy -and $origEnv) {
            foreach ($var in $origEnv.Keys) {
                if ($null -ne $origEnv[$var]) {
                    [System.Environment]::SetEnvironmentVariable($var, $origEnv[$var])
                }
            }
        }
    }
}

# ═══════════════════════════════════════════════════════════════════
# Main
# ═══════════════════════════════════════════════════════════════════

Write-Banner '╔══════════════════════════════════════════════════╗' -Color Cyan
Write-Banner '║   Browser4 Runtime Bundle Download Speed Test   ║' -Color Cyan
Write-Banner '╚══════════════════════════════════════════════════╝' -Color Cyan
Write-Host ''

# --- Detection ---
$platformKey = Get-PlatformKey
try {
    $assetName = Get-RuntimeBundleAssetName -PlatformKey $platformKey
} catch {
    Write-Host "ERROR: $_" -ForegroundColor Red
    Write-Host "This script only supports: windows-x64, linux-x64, darwin-x64, darwin-arm64" -ForegroundColor Red
    exit 1
}

$normalizedTag = if ($Tag) { $Tag } else { 'latest' }
$probeBytes = [Math]::Min($ProbeMB * 1MB, 100MB)  # Cap at 100 MB
$chinaDetected = Test-ChinaLocale
$detectedProxy = Get-DetectedProxy

Write-Step "Platform       : $platformKey"
Write-Step "Asset name     : $assetName"
Write-Step "Release tag    : $normalizedTag"
Write-Step "Probe size     : $ProbeMB MB ($probeBytes bytes)"
Write-Step "Timeout        : ${TimeoutSecs}s per source"
Write-Step "China locale   : $chinaDetected"
if ($detectedProxy) {
    Write-Step "Detected proxy : $($detectedProxy.Url) (from $($detectedProxy.Source))"
} else {
    Write-Step 'Detected proxy : none'
}

# --- Locate mode: print diagnostics and exit ---
if ($Locate) {
    Write-Host ''
    Write-Banner '─── Mirror URLs ───' -Color Cyan
    $urls = Get-MirrorUrls -AssetName $assetName -VersionTag $normalizedTag
    foreach ($entry in $urls) {
        Write-Step "$($entry.Label): $($entry.Url)"
    }

    # Test DNS resolution
    Write-Host ''
    Write-Banner '─── Connectivity check ───' -Color Cyan
    $hosts = @(
        @{ Host = 'github.com';            Label = 'GitHub' },
        @{ Host = 'browser4.oss-cn-beijing.aliyuncs.com'; Label = 'Aliyun OSS' }
    )
    foreach ($h in $hosts) {
        try {
            $ip = [System.Net.Dns]::GetHostAddresses($h.Host) | Select-Object -First 1
            Write-Success "$($h.Label): resolved to $($ip.IPAddressToString)"
        } catch {
            Write-Warn "$($h.Label): DNS resolution failed"
        }
    }

    exit 0
}

# --- Run speed tests ---
$results = [System.Collections.ArrayList]::new()

# Build the test plan based on -Source
$mirrors = Get-MirrorUrls -AssetName $assetName -VersionTag $normalizedTag
$testPlan = [System.Collections.ArrayList]::new()

foreach ($mirror in $mirrors) {
    if ($Source -eq 'all' -or $Source -eq $mirror.Name) {
        # Direct download (system proxy)
        $null = $testPlan.Add(@{
            Mirror    = $mirror
            ProxyUrl  = ''
            NoProxy   = $false
            Suffix    = ''
        })

        # If a proxy is detected and --no-proxy-tests not set, add proxy on/off tests
        if ($detectedProxy -and -not $NoProxyTests) {
            # Through detected proxy
            $null = $testPlan.Add(@{
                Mirror    = $mirror
                ProxyUrl  = $detectedProxy.Url
                NoProxy   = $false
                Suffix    = ' (via proxy)'
            })
            # Proxy bypass (NO_PROXY=*)
            $null = $testPlan.Add(@{
                Mirror    = $mirror
                ProxyUrl  = ''
                NoProxy   = $true
                Suffix    = ' (proxy off)'
            })
        }
    }
}

# Show the test plan
Write-Host ''
Write-Banner "─── Running $($testPlan.Count) speed test(s) ───" -Color Cyan

foreach ($plan in $testPlan) {
    $label = "$($plan.Mirror.Label)$($plan.Suffix)"
    $result = Test-DownloadSpeed `
        -Url $plan.Mirror.Url `
        -Label $label `
        -ProbeBytes $probeBytes `
        -TimeoutSecs $TimeoutSecs `
        -ProxyUrl $plan.ProxyUrl `
        -NoProxy:$plan.NoProxy

    if ($result) {
        $null = $results.Add($result)
    }
}

# --- Summary table ---
Write-Host ''
Write-Banner '═══ Results ═══' -Color Cyan
Write-Host ''

if ($results.Count -eq 0) {
    Write-Warn 'No results collected.'
    exit 1
}

# Determine column widths
$maxLabelLen  = ($results | ForEach-Object { $_.Label.Length } | Measure-Object -Maximum).Maximum
$maxLabelLen  = [Math]::Max($maxLabelLen, 5)
$labelCol     = [Math]::Max($maxLabelLen + 2, 30)

# Header
$headerFmt = "{0,-${labelCol}} {1,>8} {2,>12} {3,>12} {4,>12} {5,>8}"
$rowFmt    = "{0,-${labelCol}} {1,8} {2,12:N2} {3,12:N0} {4,12:N1} {5,8}"

Write-Host ($headerFmt -f 'Source', 'HTTP', 'MB/s', 'Bytes', 'ms', 'Proxy') -ForegroundColor White
Write-Host ('─' * ($labelCol + 8 + 12 + 12 + 12 + 8 + 5)) -ForegroundColor DarkGray

foreach ($r in $results) {
    $color = if ($r.Error) { 'Red' } else { 'White' }
    $proxyTag = switch ($r.ProxyUsed) {
        'none'   { 'OFF' }
        'system' { 'auto' }
        default  { 'ON' }
    }

    if ($r.Error) {
        Write-Host ($rowFmt -f $r.Label, ('ERR' + $r.StatusCode), $r.SpeedMBps, $r.BytesReceived, $r.ElapsedMs, $proxyTag) -ForegroundColor Red
        Write-Result "      Error: $($r.Error)" -Color DarkGray
    } else {
        Write-Host ($rowFmt -f $r.Label, $r.StatusCode, $r.SpeedMBps, $r.BytesReceived, $r.ElapsedMs, $proxyTag) -ForegroundColor $color
    }
}

Write-Host ''

# --- Ranking ---
$successResults = $results | Where-Object { -not $_.Error } | Sort-Object -Property SpeedMBps -Descending
if ($successResults.Count -gt 0) {
    Write-Banner 'Ranking (fastest first):' -Color Cyan
    $rank = 1
    foreach ($r in $successResults) {
        $medal = switch ($rank) {
            1 { '🥇' }
            2 { '🥈' }
            3 { '🥉' }
            default { " ${rank}." }
        }
        Write-Host "  $medal $($r.Label) — $('{0:N2}' -f $r.SpeedMBps) MB/s" -ForegroundColor White
        $rank++
    }
}

# --- Recommendations ---
Write-Host ''
Write-Banner '─── Recommendations ───' -Color Cyan

if ($chinaDetected) {
    Write-Host '  💡 You are in China mainland. Aliyun OSS is recommended for speed.'
    if ($successResults) {
        $best = $successResults[0]
        Write-Host "  → Fastest mirror: $($best.Label) ($('{0:N2}' -f $best.SpeedMBps) MB/s)"
    }
} else {
    Write-Host '  💡 You are outside China mainland. GitHub Releases is the default.'
    if ($successResults) {
        $best = $successResults[0]
        Write-Host "  → Fastest mirror: $($best.Label) ($('{0:N2}' -f $best.SpeedMBps) MB/s)"
    }
}

if ($detectedProxy) {
    Write-Host "  🔒 Proxy detected: $($detectedProxy.Url)"
    $proxyResults = $results | Where-Object { $_.ProxyUsed -ne 'none' -and $_.ProxyUsed -ne 'system' }
    $noProxyResults = $results | Where-Object { $_.ProxyUsed -eq 'none' }
    if ($proxyResults.Count -gt 0 -and $noProxyResults.Count -gt 0) {
        $proxySpeed = ($proxyResults | Measure-Object -Property SpeedMBps -Average).Average
        $noProxySpeed = ($noProxyResults | Measure-Object -Property SpeedMBps -Average).Average
        if ($proxySpeed -gt 0 -and $noProxySpeed -gt 0) {
            if ($proxySpeed -gt $noProxySpeed) {
                Write-Host '  → Proxy is FASTER than direct connection.'
            } else {
                Write-Host '  → Proxy is SLOWER than direct connection — consider bypassing for downloads.'
            }
        }
    }
}

Write-Host ''
Write-Host 'Done.' -ForegroundColor Green
exit 0
