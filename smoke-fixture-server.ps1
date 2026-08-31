# Tiny fixture HTTP server for the network/HAR smoke test.
$listener = [System.Net.HttpListener]::new()
$listener.Prefixes.Add("http://127.0.0.1:18999/")
$listener.Start()
Write-Output "fixture server listening on 18999"
while ($listener.IsListening) {
    $ctx = $listener.GetContext()
    $path = $ctx.Request.Url.AbsolutePath
    $body = $null
    $status = 200
    $contentType = "text/plain; charset=utf-8"
    switch -Exact ($path) {
        "/api/network-endpoint-ok.json" {
            $body = '{"status":"ok","source":"fixture"}'
            $contentType = "application/json"
        }
        "/api/network-endpoint-missing.json" {
            $status = 404
            $body = '{"error":"not found"}'
            $contentType = "application/json"
        }
        "/network" {
            $body = '<!DOCTYPE html><html><head><meta charset="utf-8"></head><body><pre id="results">x</pre><script>fetch("/api/network-endpoint-ok.json");fetch("/api/network-endpoint-missing.json")</script></body></html>'
            $contentType = "text/html; charset=utf-8"
        }
        default {
            $status = 404
            $body = "not found: $path"
        }
    }
    $bytes = [System.Text.Encoding]::UTF8.GetBytes($body)
    $ctx.Response.StatusCode = $status
    $ctx.Response.ContentType = $contentType
    $ctx.Response.ContentLength64 = $bytes.Length
    $ctx.Response.OutputStream.Write($bytes, 0, $bytes.Length)
    $ctx.Response.Close()
}
