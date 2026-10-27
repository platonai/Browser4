$ErrorActionPreference = 'Stop'
$root = "D:\workspace\Browser4\Browser4-4.15-feat"
Set-Location $root
$outDir = Join-Path $root "output\amazon-microwaves-2026-10-26"
$rawDir = Join-Path $outDir "raw"
New-Item -ItemType Directory -Force -Path $rawDir | Out-Null

$session = "amz-mw"
$items = (Get-Content (Join-Path $outDir "bs_list.json") -Raw | ConvertFrom-Json).items | Select-Object -First 20
$extractFile = Join-Path $outDir "extract_product.js"

function Invoke-B4([string[]]$b4args) {
    $out = & "$root\b4w.ps1" --session $session @b4args 2>&1 | Out-String
    return $out
}

function Get-PageUrl([string]$text) {
    $m = [regex]::Match($text, '- Page URL:\s*(\S+)')
    if ($m.Success) { return $m.Groups[1].Value }
    return ''
}

function Swallow-Checkpoint {
    $js = @'
(() => { const h = document.querySelector('#a-autoid-0'); if (!h) return 'no-btn'; const b = h.querySelector('button, input'); if (b) { b.click(); return 'clicked'; } return 'no-target'; })()
'@
    $f = Join-Path $env:TEMP "cp_continue.js"
    Set-Content -Path $f -Value $js -Encoding utf8
    Start-Sleep -Seconds 2
    $r = Invoke-B4 @("eval", "--file", $f)
    return $r
}

$i = 0
foreach ($it in $items) {
    $i++
    $asin = $it.asin
    $dest = Join-Path $rawDir "$asin.json"
    if (Test-Path $dest) { Write-Host "[$i/20] $asin cached, skip"; continue }

    Write-Host "[$i/20] $asin -> goto"
    $url = "https://www.amazon.com/dp/$asin"
    $r = Invoke-B4 @("goto", $url)
    $pageUrl = Get-PageUrl $r
    if ($pageUrl -match 'checkpoint|captcha|signin|sorry') {
        Write-Host "  !! landed on $pageUrl ; trying continue"
        $null = Swallow-Checkpoint
        Start-Sleep -Seconds 4
        $r = Invoke-B4 @("goto", $url)
        $pageUrl = Get-PageUrl $r
    }
    if ($pageUrl -match 'checkpoint|captcha|signin|sorry') {
        Write-Host "  !! still blocked at $pageUrl ; retrying after pause"
        Start-Sleep -Seconds 20
        $r = Invoke-B4 @("goto", $url)
        $pageUrl = Get-PageUrl $r
    }
    if ($pageUrl -match 'checkpoint|captcha|signin|sorry') {
        Write-Host "  !! BLOCKED $asin ($pageUrl)"
        continue
    }

    $sleep = Get-Random -Minimum 2 -Maximum 5
    Start-Sleep -Seconds $sleep
    $out = Invoke-B4 @("eval", "--file", $extractFile)
    $s = $out.IndexOf('{"url"')
    $e = $out.LastIndexOf('}')
    if ($s -lt 0 -or $e -le $s) {
        Write-Host "  !! extract failed, retrying once"
        Start-Sleep -Seconds 5
        $out = Invoke-B4 @("eval", "--file", $extractFile)
        $s = $out.IndexOf('{"url"')
        $e = $out.LastIndexOf('}')
    }
    if ($s -ge 0 -and $e -gt $s) {
        $json = $out.Substring($s, $e - $s + 1)
        try { $null = $json | ConvertFrom-Json } catch { Write-Host "  !! invalid json"; continue }
        Set-Content -Path $dest -Value $json -Encoding utf8
        Write-Host "  ok -> $asin"
    } else {
        Write-Host "  !! no json for $asin"
    }
    $pause = Get-Random -Minimum 3 -Maximum 8
    Start-Sleep -Seconds $pause
}
Write-Host "DONE"
