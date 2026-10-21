$ErrorActionPreference = 'SilentlyContinue'
$cx = "D:\Users\pereg\AppData\Local\Temp\browser4-pereg\context\groups\named\PULSAR_CHROME\cx.9f2a347d-b530-42c8-88dd-232590fc14f8\PULSAR_CHROME"
Write-Output "== cx profile Extensions dirs =="
Get-ChildItem "$cx\Default\Extensions" -Directory | ForEach-Object { $_.Name }
Write-Output "== cx Secure Preferences extension ids =="
$raw = [System.IO.File]::ReadAllText("$cx\Default\Secure Preferences")
if ($raw -match '"extensions":\s*\{') {
  $sp = $raw | ConvertFrom-Json
  $sp.extensions.settings.PSObject.Properties.Name | ForEach-Object { Write-Output $_ }
}
