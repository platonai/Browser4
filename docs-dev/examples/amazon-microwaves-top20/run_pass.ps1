param(
    [string]$ExtractFile = "output\amazon-microwaves-2026-10-26\extract_product.js",
    [string]$RawDirName = "raw",
    [switch]$SkipExisting
)
$ErrorActionPreference = 'Stop'
$root = "D:\workspace\Browser4\Browser4-4.15-feat"
Set-Location $root
$outDir = Join-Path $root "output\amazon-microwaves-2026-10-26"
$rawDir = Join-Path $outDir $RawDirName
New-Item -ItemType Directory -Force -Path $rawDir | Out-Null
$extractFile = Join-Path $root $ExtractFile
$session = "amz-mw"
$items = (Get-Content (Join-Path $outDir "bs_list.json") -Raw | ConvertFrom-Json).items | Select-Object -First 20

function Invoke-B4([string[]]$b4args) {
    return (& "$root\b4w.ps1" --session $session @b4args 2>&1 | Out-String)
}
function Get-PageUrl([string]$text) {
    $m = [regex]::Match($text, '- Page URL:\s*(\S+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}
function Swallow-Checkpoint {
    $f = Join-Path $env:TEMP "cp_continue2.js"
    Set-Content -Path $f -Value "(() => { const h = document.querySelector('#a-autoid-0'); if (!h) return 'no-btn'; const b = h.querySelector('button, input'); if (b) { b.click(); return 'clicked'; } return 'no-target'; })()" -Encoding utf8
    Start-Sleep -Seconds 2
    return (Invoke-B4 @("eval", "--file", $f))
}

$i = 0
foreach ($it in $items) {
    $i++
    $asin = $it.asin
    $dest = Join-Path $rawDir "$asin.json"
    if ($SkipExisting -and (Test-Path $dest)) { Write-Host "[$i/20] $asin cached, skip"; continue }
    Write-Host "[$i/20] $asin"
    $url = "https://www.amazon.com/dp/$asin"
    $r = Invoke-B4 @("goto", $url)
    $pageUrl = Get-PageUrl $r
    if ($pageUrl -match 'checkpoint|captcha|signin|sorry') {
        Write-Host "  !! landed on $pageUrl ; continue-click"
        $null = Swallow-Checkpoint
        Start-Sleep -Seconds 4
        $r = Invoke-B4 @("goto", $url); $pageUrl = Get-PageUrl $r
    }
    if ($pageUrl -match 'checkpoint|captcha|signin|sorry') {
        Write-Host "  !! still blocked ($pageUrl) ; pause 25s and retry"
        Start-Sleep -Seconds 25
        $r = Invoke-B4 @("goto", $url); $pageUrl = Get-PageUrl $r
    }
    if ($pageUrl -match 'checkpoint|captcha|signin|sorry') { Write-Host "  !! BLOCKED $asin"; continue }

    Start-Sleep -Seconds (Get-Random -Minimum 2 -Maximum 4)
    $out = Invoke-B4 @("eval", "--file", $extractFile)
    $s = $out.IndexOf('{"url"'); $e = $out.LastIndexOf('}')
    if ($s -lt 0) {
        Write-Host "  !! retry extract"
        Start-Sleep -Seconds 4
        $out = Invoke-B4 @("eval", "--file", $extractFile)
        $s = $out.IndexOf('{"url"'); $e = $out.LastIndexOf('}')
    }
    if ($s -ge 0 -and $e -gt $s) {
        $json = $out.Substring($s, $e - $s + 1)
        try { $null = $json | ConvertFrom-Json } catch { Write-Host "  !! invalid json"; continue }
        Set-Content -Path $dest -Value $json -Encoding utf8
        Write-Host "  ok -> $asin"
    } else { Write-Host "  !! no json for $asin" }
    Start-Sleep -Seconds (Get-Random -Minimum 2 -Maximum 5)
}
Write-Host "PASS DONE"
