#!/usr/bin/env pwsh
# Minimal fake MCP endpoint used to isolate CLI-side crashes without a browser.
#
# Serves just enough for the CLI's readiness probe:
#   GET  /actuator/health  -> {"status":"UP"}
#   GET  /mcp/tools        -> contains open_session + browser_navigate
#   POST /mcp/call-tool    -> $env:FAKE_MCP_PAYLOAD (default: the empty text
#                             payload the real backend returns for browser_click)
# Every request is appended to %TEMP%/fake-mcp-requests.log.
param(
    [int] $Port = 18077
)

$payloadDefault = if ($env:FAKE_MCP_PAYLOAD) { $env:FAKE_MCP_PAYLOAD } else {
    '{"content":[{"type":"text","text":""}],"isError":false}'
}
# The /mcp/call-tool payload is read per request from this file when it exists, so
# an A/B experiment can flip the payload without restarting the server.
$payloadPath = Join-Path $env:TEMP 'fake-mcp-payload.json'
$logPath = Join-Path $env:TEMP 'fake-mcp-requests.log'
if (Test-Path $logPath) { Remove-Item $logPath }

$listener = New-Object System.Net.HttpListener
$listener.Prefixes.Add("http://127.0.0.1:$Port/")
$listener.Start()
Write-Host "fake MCP listening on http://127.0.0.1:$Port/ (payload file: $payloadPath)"

while ($listener.IsListening) {
    try {
        $context = $listener.GetContext()
    } catch {
        break
    }

    $request = $context.Request
    $reader = New-Object System.IO.StreamReader($request.InputStream)
    $body = $reader.ReadToEnd()
    $reader.Close()

    $path = $request.Url.AbsolutePath
    "$($request.HttpMethod) $path body=$body" | Add-Content -Path $logPath

    $responseBody = switch -Wildcard ($path) {
        '/actuator/health' { '{"status":"UP"}' }
        '/mcp/tools' { '{"tools":[{"name":"open_session"},{"name":"browser_navigate"},{"name":"browser_click"}]}' }
        '/mcp/call-tool' {
            if (Test-Path $payloadPath) { Get-Content $payloadPath -Raw } else { $payloadDefault }
        }
        default { '{}' }
    }

    $bytes = [System.Text.Encoding]::UTF8.GetBytes($responseBody)
    $context.Response.StatusCode = 200
    $context.Response.ContentType = 'application/json'
    $context.Response.ContentLength64 = $bytes.Length
    $context.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $context.Response.OutputStream.Close()
}
