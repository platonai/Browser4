#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Probe Chrome/Edge CDP endpoints the way `browser4-cli attach --cdp` does, then
    ask the browser-level WebSocket for its targets.

.DESCRIPTION
    Answers the question behind platonai/Browser4#611: when Chrome's built-in
    "Allow remote debugging for this browser instance" toggle is enabled, does the
    endpoint published in DevToolsActivePort expose page targets over the
    browser-level WebSocket even though every /json* path returns 404?

    For every candidate port the script reports

      1. HTTP  GET /json/version  -> reachable? which browser?
      2. HTTP  GET /json          -> how many `page` targets (404 = no HTTP discovery)
      3. WS    ws://127.0.0.1:<port><path>  -> Target.getTargets over the browser-level socket

    Candidate ports come from

      * `<user-data-dir>/DevToolsActivePort` of every running browser process
        (`--user-data-dir=...` on its command line),
      * the conventional user-data directories (Chrome / Edge, default profiles),
      * `--remote-debugging-port=N` on a browser command line,
      * the ports a browser process is listening on.

    Read-only: the script only issues discovery requests and `Target.getTargets`.
    It never navigates, attaches or modifies the browser.

.EXAMPLE
    pwsh ./probe-cdp-endpoints.ps1
    pwsh ./probe-cdp-endpoints.ps1 -Port 9222
    pwsh ./probe-cdp-endpoints.ps1 -Port 9222 -BrowserPath '/devtools/browser/d7504e91-bbf6-44f0-9338-46952523ed7c'
#>
[CmdletBinding()]
param(
    # Probe only this port instead of discovering candidates.
    [int]$Port = 0,
    # Browser-level WebSocket path to use when /json/version returns no webSocketDebuggerUrl.
    [string]$BrowserPath = '',
    # Per-request timeout in seconds.
    [int]$TimeoutSec = 5
)

$ErrorActionPreference = 'Stop'

function Get-BrowserProcessLines {
    if ($IsWindows) {
        $script = "Get-CimInstance Win32_Process | Where-Object { `$_.Name -like '*chrome*' -or `$_.Name -like '*msedge*' } | Select-Object -ExpandProperty CommandLine"
        try { return @(& pwsh -NoProfile -Command $script 2>$null) } catch { return @() }
    }
    try { return @(& ps -e -o args= 2>$null) } catch { return @() }
}

function Get-UserDataDirs {
    param([string[]]$CommandLines)

    $dirs = [System.Collections.Generic.List[string]]::new()
    foreach ($line in $CommandLines) {
        if (-not $line) { continue }
        foreach ($match in [regex]::Matches($line, '--user-data-dir=(?:"([^"]+)"|(\S+))')) {
            $value = if ($match.Groups[1].Success) { $match.Groups[1].Value } else { $match.Groups[2].Value }
            if ($value -and -not $dirs.Contains($value)) { $dirs.Add($value) }
        }
    }

    $defaults = if ($IsWindows) {
        @(
            (Join-Path $env:LOCALAPPDATA 'Google/Chrome/User Data'),
            (Join-Path $env:LOCALAPPDATA 'Microsoft/Edge/User Data')
        )
    } elseif ($IsMacOS) {
        @(
            (Join-Path $HOME 'Library/Application Support/Google/Chrome'),
            (Join-Path $HOME 'Library/Application Support/Microsoft Edge')
        )
    } else {
        @(
            (Join-Path $HOME '.config/google-chrome'),
            (Join-Path $HOME '.config/microsoft-edge')
        )
    }
    foreach ($dir in $defaults) {
        if ($dir -and -not $dirs.Contains($dir)) { $dirs.Add($dir) }
    }
    return $dirs
}

function Read-DevToolsActivePort {
    param([string]$UserDataDir)

    foreach ($file in @((Join-Path $UserDataDir 'DevToolsActivePort'), (Join-Path $UserDataDir 'Default/DevToolsActivePort'))) {
        if (-not (Test-Path -LiteralPath $file)) { continue }
        $lines = @(Get-Content -LiteralPath $file -ErrorAction SilentlyContinue)
        if ($lines.Count -eq 0) { continue }
        $parsed = 0
        if ([int]::TryParse($lines[0].Trim(), [ref]$parsed) -and $parsed -gt 0) {
            return [pscustomobject]@{
                Port = $parsed
                Path = if ($lines.Count -gt 1) { $lines[1].Trim() } else { '' }
                File = $file
            }
        }
    }
    return $null
}

function Get-JsonOrNull {
    param([string]$Url, [int]$TimeoutSec)

    try {
        $response = Invoke-WebRequest -Uri $Url -TimeoutSec $TimeoutSec -SkipHttpErrorCheck -UseBasicParsing
        $json = $null
        try { $json = $response.Content | ConvertFrom-Json } catch { }
        return [pscustomobject]@{ Status = [int]$response.StatusCode; Json = $json; Error = '' }
    } catch {
        return [pscustomobject]@{ Status = 0; Json = $null; Error = $_.Exception.Message }
    }
}

function Invoke-CdpWebSocketTargets {
    param([string]$Url, [int]$TimeoutSec)

    $socket = [System.Net.WebSockets.ClientWebSocket]::new()
    $cancel = [System.Threading.CancellationTokenSource]::new([TimeSpan]::FromSeconds($TimeoutSec))
    try {
        $socket.ConnectAsync([uri]$Url, $cancel.Token).GetAwaiter().GetResult()
        $payload = [System.Text.Encoding]::UTF8.GetBytes('{"id":1,"method":"Target.getTargets"}')
        $sendSegment = [System.ArraySegment[byte]]::new($payload)
        $socket.SendAsync($sendSegment, [System.Net.WebSockets.WebSocketMessageType]::Text, $true, $cancel.Token).GetAwaiter().GetResult()

        $buffer = [byte[]]::new(1MB)
        $builder = [System.Text.StringBuilder]::new()
        do {
            $receive = $socket.ReceiveAsync([System.ArraySegment[byte]]::new($buffer), $cancel.Token).GetAwaiter().GetResult()
            [void]$builder.Append([System.Text.Encoding]::UTF8.GetString($buffer, 0, $receive.Count))
        } while (-not $receive.EndOfMessage)

        return [pscustomobject]@{ Connected = $true; Response = $builder.ToString(); Error = '' }
    } catch {
        return [pscustomobject]@{ Connected = $false; Response = ''; Error = $_.Exception.Message }
    } finally {
        try {
            if ($socket.State -eq [System.Net.WebSockets.WebSocketState]::Open) {
                $socket.CloseAsync(
                    [System.Net.WebSockets.WebSocketCloseStatus]::NormalClosure,
                    'done',
                    [System.Threading.CancellationToken]::None).GetAwaiter().GetResult()
            }
        } catch { }
        $socket.Dispose()
        $cancel.Dispose()
    }
}

# ---------------------------------------------------------------- candidates
$candidates = [System.Collections.Generic.List[object]]::new()

if ($Port -gt 0) {
    $candidates.Add([pscustomobject]@{ Port = $Port; Path = $BrowserPath; Source = 'command line' })
} else {
    $commandLines = Get-BrowserProcessLines

    foreach ($dir in (Get-UserDataDirs -CommandLines $commandLines)) {
        $published = Read-DevToolsActivePort -UserDataDir $dir
        if ($published) {
            $candidates.Add([pscustomobject]@{ Port = $published.Port; Path = $published.Path; Source = "DevToolsActivePort ($($published.File))" })
        }
    }

    foreach ($line in $commandLines) {
        if (-not $line) { continue }
        $flag = [regex]::Match($line, '--remote-debugging-port=(\d+)')
        if ($flag.Success -and [int]$flag.Groups[1].Value -gt 0) {
            $candidates.Add([pscustomobject]@{ Port = [int]$flag.Groups[1].Value; Path = ''; Source = '--remote-debugging-port' })
        }
    }

    if ($IsWindows) {
        $browserPids = @(Get-CimInstance Win32_Process |
            Where-Object { $_.CommandLine -match '--remote-debugging-port' -and $_.CommandLine -notmatch '--type=' } |
            Select-Object -ExpandProperty ProcessId)
        foreach ($browserPid in $browserPids) {
            foreach ($listener in @(Get-NetTCPConnection -State Listen -ErrorAction SilentlyContinue |
                    Where-Object { $_.OwningProcess -eq $browserPid })) {
                $candidates.Add([pscustomobject]@{ Port = [int]$listener.LocalPort; Path = ''; Source = "process listener (pid $browserPid)" })
            }
        }
    }
}

$seen = [System.Collections.Generic.HashSet[int]]::new()
$candidates = @($candidates | Where-Object { $seen.Add([int]$_.Port) })

if ($candidates.Count -eq 0) {
    Write-Host 'No CDP candidate found: no browser is running with remote debugging (checked DevToolsActivePort, process flags and listeners).' -ForegroundColor Yellow
    exit 1
}

# ------------------------------------------------------------------- probing
$pageTargetsFound = 0
foreach ($candidate in $candidates) {
    $port = [int]$candidate.Port
    Write-Host ''
    Write-Host "=== port $port  [$($candidate.Source)] ===" -ForegroundColor Cyan

    $version = Get-JsonOrNull -Url "http://127.0.0.1:$port/json/version" -TimeoutSec $TimeoutSec
    if ($version.Status -eq 0) {
        Write-Host "  GET /json/version : no HTTP answer ($($version.Error))"
    } else {
        Write-Host "  GET /json/version : HTTP $($version.Status)  browser=$($version.Json.Browser)"
    }

    $list = Get-JsonOrNull -Url "http://127.0.0.1:$port/json" -TimeoutSec $TimeoutSec
    $pageCount = if ($list.Json) { @($list.Json | Where-Object { $_.type -eq 'page' }).Count } else { 0 }
    if ($list.Status -eq 0) {
        Write-Host "  GET /json         : no HTTP answer ($($list.Error))"
    } else {
        Write-Host "  GET /json         : HTTP $($list.Status)  page targets=$pageCount"
    }

    $wsPath = $BrowserPath
    if (-not $wsPath -and $version.Json.webSocketDebuggerUrl) {
        $wsPath = ([uri]$version.Json.webSocketDebuggerUrl).AbsolutePath
    }
    if (-not $wsPath -and $candidate.Path) { $wsPath = $candidate.Path }
    if (-not $wsPath) { $wsPath = '/devtools/browser' }

    $wsUrl = "ws://127.0.0.1:$port$wsPath"
    $probe = Invoke-CdpWebSocketTargets -Url $wsUrl -TimeoutSec $TimeoutSec
    if (-not $probe.Connected) {
        Write-Host "  WS  $wsUrl" -NoNewline
        Write-Host " : connect failed ($($probe.Error))" -ForegroundColor DarkYellow
        continue
    }

    $targetTypes = @{}
    $wsPageTargets = 0
    try {
        $message = $probe.Response | ConvertFrom-Json
        foreach ($target in @($message.result.targetInfos)) {
            $type = if ($target.type) { $target.type } else { 'unknown' }
            $targetTypes[$type] = 1 + [int]($targetTypes[$type] ?? 0)
            if ($type -eq 'page') { $wsPageTargets++ }
        }
    } catch {
        Write-Host "  WS  $wsUrl : connected, but the response could not be parsed: $($probe.Response)"
        continue
    }

    $summary = ($targetTypes.GetEnumerator() | Sort-Object Name | ForEach-Object { "$($_.Key)=$($_.Value)" }) -join ' '
    Write-Host "  WS  Target.getTargets over browser-level socket : $wsPageTargets page target(s)  [$summary]" -ForegroundColor Green
    if ($wsPageTargets -gt 0) { $pageTargetsFound += $wsPageTargets }
}

# ------------------------------------------------------------------- verdict
Write-Host ''
if ($pageTargetsFound -eq 0) {
    Write-Host 'Verdict: no endpoint exposed a page target — not even over the browser-level WebSocket.' -ForegroundColor Yellow
    Write-Host '         `attach --extension` is the supported path for such a browser.'
    exit 2
}

Write-Host "Verdict: $pageTargetsFound page target(s) are reachable over a browser-level WebSocket." -ForegroundColor Green
Write-Host '         A browser whose /json* paths answer 404 (Chrome built-in remote debugging) can therefore'
Write-Host '         still be driven over CDP, but only by a client that attaches to page sessions through the'
Write-Host '         browser-level socket — which is what platonai/Browser4#611 asks for.'
exit 0
