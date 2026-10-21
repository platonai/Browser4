$ErrorActionPreference = 'Continue'
$dir = $PSScriptRoot
$chrome = "C:\Program Files\Google\Chrome\Application\chrome.exe"
$udd = Join-Path $env:TEMP ("b4-rawchrome-" + [guid]::NewGuid().ToString('N'))
$port = 9333
$args = @(
  "--headless=new",
  "--remote-debugging-port=$port",
  "--remote-allow-origins=*",
  "--user-data-dir=$udd",
  "--no-first-run",
  "--no-default-browser-check",
  "--disable-extensions",
  "about:blank"
)
Write-Output "Launching raw Chrome with $udd"
$p = Start-Process -FilePath $chrome -ArgumentList $args -PassThru
Start-Sleep -Seconds 3
try {
  node "$dir\raw-cdp-timeline.mjs" "http://127.0.0.1:$port" "https://ipfighter.com/bot-detection"
} finally {
  Stop-Process -Id $p.Id -Force -ErrorAction SilentlyContinue
  Start-Sleep -Seconds 1
  Remove-Item -Recurse -Force $udd -ErrorAction SilentlyContinue
}
