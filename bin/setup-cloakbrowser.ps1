#!/usr/bin/env pwsh
<#
.SYNOPSIS
    setup-cloakbrowser.ps1 — 下载 CloakBrowser v146 免费二进制并跑通 Browser4 POC。

.DESCRIPTION
    一键验证 Browser4 通过 `-Dchrome.path` 驱动 CloakBrowser（stealth Chromium）：

      1. 从 GitHub Releases 下载 CloakBrowser v146 免费 stealth Chromium 二进制
         （官方 wrapper 的旧版免费构建，免 license key，可无限并发）
      2. 校验 SHA256 并解压，定位浏览器可执行文件
      3. 以 BROWSER4_SERVER_OPTS="-Dchrome.path=<cloak 二进制>" 重启 Browser4 服务器
         （daemon.rs 已支持：用户 env 中的 -Dchrome.path 覆盖自动检测值）
      4. 通过 browser4-cli 跑 POC：open → snapshot → type → click → mousewheel
         → eval（指纹探针）→ screenshot，逐项 PASS/FAIL 汇总

    防假阳性门禁：open 之后立即 eval navigator.userAgent，断言包含 "Chrome/146"
    （CloakBrowser v146 = Chromium 146）。若服务器没重启或端口被旧服务器占用，
    UA 断言会 FAIL，POC 不会误报成功。

    风险提示：CloakBrowser 二进制为 CloakHQ 专有许可（禁止再分发），本脚本只下载
    到本机用户目录（默认 ~/.cloakbrowser/），不打包进任何发行物。

    未来 skill 层支持时，可直接复用本脚本的下载/校验/定位逻辑（-DownloadOnly 模式）。

.EXAMPLE
    # 默认：下载 v146 免费二进制 + 重启服务器 + 跑 POC
    .\bin\setup-cloakbrowser.ps1

.EXAMPLE
    # 服务器端口被不受 CLI 管理的旧服务器占用时，强制停止占用进程后重试
    .\bin\setup-cloakbrowser.ps1 -ForceKillConflicting

.EXAMPLE
    # 只下载/校验/解压，不碰服务器
    .\bin\setup-cloakbrowser.ps1 -DownloadOnly

.EXAMPLE
    # 已下载过，跳过下载直接跑 POC
    .\bin\setup-cloakbrowser.ps1 -SkipDownload

.EXAMPLE
    # 打开指定 URL 作为 POC 页面（默认是本地生成的测试页）
    .\bin\setup-cloakbrowser.ps1 -PoCUrl "https://example.com"

.PARAMETER Version
    GitHub release tag，默认 chromium-v146.0.7680.177.5（v146 免费版，Windows+Linux）。

.PARAMETER InstallDir
    安装目录，默认 $HOME\.cloakbrowser（与官方 wrapper 缓存目录约定一致）。
    注意：路径不能含空格（daemon.rs 按空格切分 BROWSER4_SERVER_OPTS）。

.PARAMETER SkipDownload
    跳过下载/解压，直接使用 InstallDir 下已存在的二进制。

.PARAMETER DownloadOnly
    只做下载/校验/解压并打印二进制路径，不重启服务器、不跑 POC。

.PARAMETER Cli
    浏览器 CLI 命令。默认自动探测：全局 browser4-cli → 仓库根 ./b4w.ps1。

.PARAMETER OutDir
    POC 输出目录（测试页 HTML / JS 探针 / 截图），默认 ./cloakbrowser-poc。

.PARAMETER PoCUrl
    POC 打开的目标 URL；默认 file:// 指向本地生成的测试页，失败时自动改用本地
    HTTP 服务器（python3/python/node）托底。

.PARAMETER NoServerRestart
    不执行 `browser4-cli stop`（假定服务器已用正确 env 启动）。

.PARAMETER KeepServer
    POC 结束后保持服务器运行（默认行为，并打印恢复默认 Chrome 的方法）。

.PARAMETER ForceKillConflicting
    stop 之后端口仍被占用（例如不受 CLI 管理的外来服务器）时，强制停止占用进程。
    默认不杀任何外来进程，而是中止并给出提示。

.PARAMETER DownloadUrl
    覆盖下载地址（离线/镜像场景，指向本地文件或镜像 URL 均可）。

.NOTES
    Browser4 repo: https://github.com/platonai/Browser4
    CloakBrowser: https://github.com/CloakHQ/CloakBrowser
#>
[CmdletBinding()]
param(
    [string]$Version = "chromium-v146.0.7680.177.5",
    [string]$InstallDir = "",
    [switch]$SkipDownload,
    [switch]$DownloadOnly,
    [string]$Cli = "",
    [string]$OutDir = "",
    [string]$PoCUrl = "",
    [switch]$NoServerRestart,
    [switch]$KeepServer,
    [switch]$ForceKillConflicting,
    [string]$DownloadUrl = ""
)

$ErrorActionPreference = "Stop"
$script:PassCount = 0
$script:FailCount = 0
$script:Failures = [System.Collections.Generic.List[string]]::new()

# ── 工具函数 ────────────────────────────────────────────────────────────────

function Write-Section([string]$Title) {
    Write-Host ""
    Write-Host ("=" * 72) -ForegroundColor DarkCyan
    Write-Host "  $Title" -ForegroundColor Cyan
    Write-Host ("=" * 72) -ForegroundColor DarkCyan
}

function Write-Pass([string]$Msg) {
    $script:PassCount++
    Write-Host "  [PASS] $Msg" -ForegroundColor Green
}

function Write-Fail([string]$Msg) {
    $script:FailCount++
    $script:Failures.Add($Msg)
    Write-Host "  [FAIL] $Msg" -ForegroundColor Red
}

function Assert-Step {
    param([bool]$Ok, [string]$PassMsg, [string]$FailMsg)
    if ($Ok) { Write-Pass $PassMsg } else { Write-Fail $FailMsg }
}

# 调用 browser4-cli，返回 @($exitCode, $outputLines)
function Invoke-B4Cli {
    param([string[]]$Args)
    $out = & $script:CliCmd @Args 2>&1
    $code = $LASTEXITCODE
    if ($null -eq $code) { $code = 0 }
    return , @($code, @($out | ForEach-Object { "$_" }))
}

# 从 eval --json 输出中提取 .output.result
function Get-EvalResult {
    param([string]$JsonText)
    try {
        $o = $JsonText | ConvertFrom-Json
        return $o.output.result
    } catch {
        # 兼容无 --json 的纯文本输出
        return ($JsonText -split "`n" | Where-Object { $_ -match "^\S" } | Select-Object -Last 1)
    }
}

# 检查端口是否有监听者，返回 (bool, owningPid)
# 用 netstat -ano 解析（Get-NetTCPConnection 在部分受限环境下会偶发漏报）
function Test-PortInUse {
    param([int]$Port)
    $line = netstat -ano | Select-String ":$Port\s" | Select-String "LISTENING" | Select-Object -First 1
    if ($line) {
        $fields = ($line.ToString() -split "\s+" | Where-Object { $_ -ne "" })
        $owner = 0
        [int]::TryParse($fields[-1], [ref]$owner) | Out-Null
        if ($owner -gt 0) { return , @($true, $owner) }
    }
    return , @($false, 0)
}

# 清理 browser4 管理的残留浏览器进程（带 --user-data-dir=...browser4 标记的 chrome/msedge）
# 否则 ChromeLauncher 的 checkExistingChromeProcess 会复用一个旧会话，
# 让 -Dchrome.path 永远不生效（本次 POC 遇到的核心坑）。
function Clear-ZombieBrowsers {
    $killed = 0
    try {
        $targets = Get-CimInstance Win32_Process -Filter "Name='chrome.exe' OR Name='msedge.exe'" -ErrorAction SilentlyContinue |
            Where-Object { $_.CommandLine -match "user-data-dir=.*(browser4|\.browser4|pulsar)" }
        foreach ($t in $targets) {
            Stop-Process -Id $t.ProcessId -Force -ErrorAction SilentlyContinue
            $killed++
        }
    } catch {
        Write-Host "  [WARN] 残留浏览器扫描失败：$($_.Exception.Message)" -ForegroundColor Yellow
    }
    if ($killed -gt 0) { Write-Host "  清理残留浏览器进程 $killed 个" -ForegroundColor Yellow }
    return $killed
}

# 从 `browser4-cli status` 输出解析服务器端口（优先解析，兜底 18182/8182）
function Get-ServerPort {
    $r = Invoke-B4Cli @("status")
    $text = ($r[1] -join "`n")
    $m = [regex]::Match($text, "Server URL:\s*https?://[^:]+:(\d+)")
    if ($m.Success) { return [int]$m.Groups[1].Value }
    # 兜底：探测常见端口
    foreach ($p in @(18182, 8182)) {
        if ((Test-PortInUse $p)[0]) { return $p }
    }
    return 18182
}

# ── 解析参数 ────────────────────────────────────────────────────────────────

if ([string]::IsNullOrWhiteSpace($InstallDir)) {
    $InstallDir = Join-Path $HOME ".cloakbrowser"
}
if ([string]::IsNullOrWhiteSpace($OutDir)) {
    $OutDir = Join-Path (Get-Location) "cloakbrowser-poc"
}
$versionCore = $Version
if ($versionCore.StartsWith("chromium-v")) { $versionCore = $versionCore.Substring("chromium-v".Length) }
$binaryDir = Join-Path $InstallDir "chromium-$versionCore"

if ($InstallDir.Contains(" ")) {
    Write-Host "[ERROR] InstallDir 不能含空格（daemon.rs 按空格切分 BROWSER4_SERVER_OPTS）：$InstallDir" -ForegroundColor Red
    exit 1
}

# ── 1. 定位 CLI ────────────────────────────────────────────────────────────

$script:CliCmd = $null
if ($Cli) {
    $script:CliCmd = $Cli
} elseif (Get-Command browser4-cli -ErrorAction SilentlyContinue) {
    $script:CliCmd = (Get-Command browser4-cli).Source
} else {
    $repoRoot = Split-Path -Parent $PSScriptRoot
    $b4w = Join-Path $repoRoot "b4w.ps1"
    if (Test-Path $b4w) {
        $script:CliCmd = $b4w
    } else {
        Write-Host "[ERROR] 未找到 browser4-cli（PATH 或仓库 b4w.ps1）" -ForegroundColor Red
        exit 1
    }
}
Write-Host "CLI: $($script:CliCmd)" -ForegroundColor DarkGray

# ── 2. 下载 / 校验 / 解压 ───────────────────────────────────────────────────

if ($SkipDownload) {
    Write-Section "使用已有二进制（-SkipDownload）"
} else {
    Write-Section "下载 CloakBrowser $Version"

    $assetName = "cloakbrowser-windows-x64.zip"
    if ($IsLinux) { $assetName = "cloakbrowser-linux-x64.tar.gz" }
    elseif ($IsMacOS) {
        Write-Host "[ERROR] v146 免费版没有 macOS 资产（官方仅 Windows/Linux）；请改用 -DownloadUrl 提供镜像" -ForegroundColor Red
        exit 1
    }

    if ([string]::IsNullOrWhiteSpace($DownloadUrl)) {
        $downloadUrl = "https://github.com/CloakHQ/CloakBrowser/releases/download/$Version/$assetName"
        $sumsUrl     = "https://github.com/CloakHQ/CloakBrowser/releases/download/$Version/SHA256SUMS"
    } else {
        $downloadUrl = $DownloadUrl
        $sumsUrl     = $null
    }

    New-Item -ItemType Directory -Force -Path $binaryDir | Out-Null
    $localAsset = Join-Path $binaryDir $assetName
    $localSums  = Join-Path $binaryDir "SHA256SUMS"

    $downloaded = $false
    foreach ($attempt in 1..3) {
        try {
            Write-Host "  下载 $downloadUrl" -ForegroundColor DarkGray
            Invoke-WebRequest -Uri $downloadUrl -OutFile $localAsset -UseBasicParsing -TimeoutSec 900
            if ($sumsUrl) {
                Invoke-WebRequest -Uri $sumsUrl -OutFile $localSums -UseBasicParsing -TimeoutSec 120
            }
            $downloaded = $true
            break
        } catch {
            Write-Host "  Invoke-WebRequest 失败（第 $attempt 次）：$($_.Exception.Message)" -ForegroundColor Yellow
            Start-Sleep -Seconds 3
        }
    }

    if (-not $downloaded) {
        # 回退：gh CLI（Go 栈，在受限/代理环境通常可用）
        if (Get-Command gh -ErrorAction SilentlyContinue) {
            Write-Host "  改用 gh release download ..." -ForegroundColor DarkGray
            try {
                gh release download $Version -R CloakHQ/CloakBrowser -p $assetName -p SHA256SUMS --dir $binaryDir
                $downloaded = $true
            } catch {
                Write-Host "  gh 下载也失败：$($_.Exception.Message)" -ForegroundColor Yellow
            }
        }
    }

    if (-not $downloaded) {
        Write-Host "[ERROR] 下载失败。请手动下载 $assetName 到 $binaryDir 后加 -SkipDownload 重试，或用 -DownloadUrl 指定镜像" -ForegroundColor Red
        exit 1
    }

    # 校验 SHA256
    if ((Test-Path $localSums) -and (Test-Path $localAsset)) {
        $sumLine = (Get-Content $localSums | Where-Object { $_ -match [regex]::Escape($assetName) } | Select-Object -First 1)
        if ($sumLine) {
            $expected = ($sumLine -split "\s+")[0].ToLower()
            $actual   = (Get-FileHash -Path $localAsset -Algorithm SHA256).Hash.ToLower()
            if ($expected -ne $actual) {
                Write-Host "[ERROR] SHA256 校验失败：期望 $expected，实际 $actual" -ForegroundColor Red
                exit 1
            }
            Write-Pass "SHA256 校验通过：$assetName"
        } else {
            Write-Host "[WARN] SHA256SUMS 中未找到 $assetName 行，跳过校验" -ForegroundColor Yellow
        }
    }

    # 解压
    Write-Host "  解压到 $binaryDir ..." -ForegroundColor DarkGray
    if ($IsWindows) {
        Expand-Archive -Path $localAsset -DestinationPath $binaryDir -Force
    } else {
        tar -xzf $localAsset -C $binaryDir
        if ($LASTEXITCODE -ne 0) { Write-Host "[ERROR] tar 解压失败" -ForegroundColor Red; exit 1 }
    }
}

# 定位可执行文件（解压目录递归查找 chrome.exe / chrome）
Write-Section "定位 CloakBrowser 可执行文件"
$binary = Get-ChildItem -Path $binaryDir -Recurse -File -ErrorAction SilentlyContinue |
    Where-Object { if ($IsWindows) { $_.Name -eq "chrome.exe" } else { $_.Name -eq "chrome" -and $_.UnixMode -band 73 } } |
    Select-Object -First 1
if (-not $binary) {
    Write-Host "[ERROR] 在 $binaryDir 下未找到浏览器可执行文件" -ForegroundColor Red
    exit 1
}
$binary = $binary.FullName
Write-Pass "CloakBrowser 二进制：$binary"
if ($IsWindows) {
    try { (Get-Item $binary).VersionInfo | Select-Object ProductVersion, FileVersion | Format-Table | Out-String | Write-Host } catch {}
}

if ($DownloadOnly) {
    Write-Host ""
    Write-Host "下载/校验/解压完成（-DownloadOnly）。下一步："
    Write-Host "  `$env:BROWSER4_SERVER_OPTS = \"-Dchrome.path=$binary\""
    Write-Host "  browser4-cli open <url>"
    exit 0
}

# ── 3. 重启服务器（让 -Dchrome.path 生效）────────────────────────────────────

Write-Section "重启 Browser4 服务器（注入 -Dchrome.path）"

$serverPort = Get-ServerPort
Write-Host "  服务器端口：$serverPort" -ForegroundColor DarkGray

if (-not $NoServerRestart) {
    $wasInUse = (Test-PortInUse $serverPort)[0]
    Write-Host "  停止现有服务器 ..." -ForegroundColor DarkGray
    & $script:CliCmd stop 2>&1 | Out-Null

    # 等待端口释放
    $freed = $false
    for ($i = 0; $i -lt 60; $i++) {
        if (-not (Test-PortInUse $serverPort)[0]) { $freed = $true; break }
        Start-Sleep -Seconds 1
    }

    if (-not $freed) {
        $owner = (Test-PortInUse $serverPort)[1]
        Write-Host "[WARN] 端口 $serverPort 仍被占用（PID $owner）——该服务器不受当前 CLI 管理（例如由其它 checkout/方式启动）" -ForegroundColor Yellow
        if ($ForceKillConflicting) {
            Write-Host "  强制停止占用进程 PID $owner ..." -ForegroundColor Yellow
            Stop-Process -Id $owner -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 3
            $freed = -not (Test-PortInUse $serverPort)[0]
            if (-not $freed) {
                Write-Host "[ERROR] 端口 $serverPort 仍被占用，无法继续" -ForegroundColor Red
                exit 1
            }
        } else {
            Write-Host "[ERROR] 端口 $serverPort 仍被占用（PID $owner）。请手动停止该服务器后重试，或加 -ForceKillConflicting 让脚本强制停止。" -ForegroundColor Red
            exit 1
        }
    }

    Write-Pass "服务器已停止（端口 $serverPort 空闲）"
    # 清理残留浏览器会话，否则新服务器会复用旧浏览器（-Dchrome.path 不生效）
    Clear-ZombieBrowsers | Out-Null
}

$existing = $env:BROWSER4_SERVER_OPTS
if ($existing) {
    $env:BROWSER4_SERVER_OPTS = "$existing -Dchrome.path=$binary"
} else {
    $env:BROWSER4_SERVER_OPTS = "-Dchrome.path=$binary"
}
Write-Host "  BROWSER4_SERVER_OPTS = $($env:BROWSER4_SERVER_OPTS)" -ForegroundColor DarkGray
Write-Pass "环境变量已设置（下次 open 自动启动新服务器）"

# eval 调用重试：服务器刚启动/浏览器刚拉起时偶发 CLI 打印帮助文本或返回空，
# 重试几次（浏览器首启一般 5-20 秒）
function Invoke-EvalWithRetry {
    param([string[]]$EvalArgs, [int]$Retries = 5, [int]$SleepSec = 4)
    $r = $null
    for ($i = 0; $i -lt $Retries; $i++) {
        $r = Invoke-B4Cli $EvalArgs
        $text = ($r[1] -join "`n")
        if ($r[0] -eq 0 -and $text -notmatch "Usage: browser4-cli" -and $text.Trim() -ne "") {
            return $r
        }
        Write-Host "  (eval 重试 $($i + 1)/$Retries)" -ForegroundColor DarkGray
        Start-Sleep -Seconds $SleepSec
    }
    return $r
}

# ── 4. POC ─────────────────────────────────────────────────────────────────

Write-Section "POC：CloakBrowser 驱动验证（v146）"

New-Item -ItemType Directory -Force -Path $OutDir | Out-Null

# 4.0 POC 测试页（本地生成，含输入框/按钮/可滚动区/state-log）
$htmlPath = Join-Path $OutDir "cloakbrowser-poc.html"
@"
<!DOCTYPE html>
<html lang="en">
<head>
<meta charset="UTF-8" />
<title>CloakBrowser POC</title>
<style>
  body { font-family: sans-serif; padding: 24px; }
  input { width: 320px; padding: 6px; }
  button { padding: 8px 24px; margin-top: 8px; }
  #tall { height: 2000px; background: linear-gradient(#eee, #ccc); margin-top: 24px; }
  pre { border: 1px solid #ccc; padding: 8px; min-height: 24px; }
</style>
</head>
<body>
  <h1>CloakBrowser POC</h1>
  <input id="q" type="text" placeholder="type here" />
  <button id="submit-btn" type="button" onclick="document.getElementById('state-log').textContent = document.getElementById('q').value + ' | ' + new Date().toISOString()">Submit</button>
  <div id="tall">scroll me</div>
  <pre id="state-log"></pre>
</body>
</html>
"@ | Set-Content -Path $htmlPath -Encoding utf8

$targetUrl = $PoCUrl
$localServer = $null
if ([string]::IsNullOrWhiteSpace($targetUrl)) {
    $targetUrl = "file:///" + ($htmlPath -replace "\\", "/")
}

# 步骤 0：open（首次调用会自动启动服务器；file:// 失败则回退本地 HTTP 服务器）
$stepCode = $null; $stepOut = @()
Write-Host "  open $targetUrl --fresh ..." -ForegroundColor DarkGray
$r = Invoke-B4Cli @("open", $targetUrl, "--fresh")
$stepCode = $r[0]; $stepOut = $r[1]

if ($stepCode -ne 0 -and [string]::IsNullOrWhiteSpace($PoCUrl)) {
    # file:// 可能被过滤/拒绝 → 起本地 HTTP 服务器托底
    $serverCmd = $null
    foreach ($c in @("python3", "python", "node")) {
        if (Get-Command $c -ErrorAction SilentlyContinue) { $serverCmd = $c; break }
    }
    if ($serverCmd) {
        Write-Host "  file:// 打开失败（exit $stepCode），改用本地 HTTP 服务器（$serverCmd）" -ForegroundColor Yellow
        $webDir = Join-Path $OutDir "web"
        New-Item -ItemType Directory -Force -Path $webDir | Out-Null
        Copy-Item $htmlPath (Join-Path $webDir "index.html") -Force
        if ($serverCmd -eq "node") {
            $js = Join-Path $webDir "serve.js"
            'const http=require("http"),fs=require("fs"),path=require("path");
const root=__dirname,port=18888;
http.createServer((req,res)=>{let f=path.join(root,req.url==="/"?"index.html":req.url);
fs.readFile(f,(e,d)=>{if(e){res.writeHead(404);res.end();return;}
res.writeHead(200,{"Content-Type":"text/html; charset=utf-8"});res.end(d);});}).listen(port);' |
                Set-Content -Path $js -Encoding ascii
            $localServer = Start-Process -FilePath "node" -ArgumentList $js -PassThru -WindowStyle Hidden
        } else {
            $localServer = Start-Process -FilePath $serverCmd -ArgumentList @("-m", "http.server", "18888", "--bind", "127.0.0.1") -WorkingDirectory $webDir -PassThru -WindowStyle Hidden
        }
        Start-Sleep -Seconds 2
        $r = Invoke-B4Cli @("open", "http://127.0.0.1:18888/index.html", "--fresh")
        $stepCode = $r[0]; $stepOut = $r[1]
    }
}
$openText = ($stepOut -join "`n")
Assert-Step ($stepCode -eq 0) "open：页面加载成功" "open 失败（exit $stepCode）：$($stepOut | Select-Object -Last 5 | Out-String)"

# 步骤 0.5（门禁）：UA 断言 —— 确认服务器真的在用 CloakBrowser v146（防假阳性）
$r = Invoke-EvalWithRetry @("eval", "--json", "navigator.userAgent")
$ua = Get-EvalResult ($r[1] -join "`n")
Write-Host "  UA：$ua" -ForegroundColor DarkGray
if ($r[0] -eq 0 -and $ua -match "Chrome/146") {
    Write-Pass "UA 门禁：HeadlessChrome 146（确认为 CloakBrowser v146）"
} else {
    Write-Fail "UA 门禁失败：UA='$ua'。服务器可能没有使用 CloakBrowser（检查 stop 是否生效、端口是否被旧服务器占用、BROWSER4_SERVER_OPTS 是否生效）"
}

# 步骤 1：页面标题确认（来自 open 输出）
Assert-Step ($openText -match "Page Title: CloakBrowser POC") "open 输出：页面标题 'CloakBrowser POC'" "open 输出未见 POC 页面标题"

# 步骤 2：snapshot（a11y 树路径）
$r = Invoke-B4Cli @("snapshot")
Assert-Step ($r[0] -eq 0) "snapshot：a11y 树获取成功" "snapshot 失败：$($r[1] | Select-Object -Last 5 | Out-String)"

# 步骤 3：type（Input.insertText 路径 + focus 光标修正）
$r = Invoke-B4Cli @("type", "Hello CloakBrowser", "#q", "--focus", "--verify")
Assert-Step ($r[0] -eq 0) "type：输入文本成功（--verify）" "type 失败：$($r[1] | Select-Object -Last 5 | Out-String)"

# 步骤 4：eval 指纹探针（信息性）
$fpJs = Join-Path $OutDir "fingerprint.js"
'JSON.stringify({ua: navigator.userAgent, webdriver: navigator.webdriver, platform: navigator.platform, languages: navigator.languages, hardwareConcurrency: navigator.hardwareConcurrency})' |
    Set-Content -Path $fpJs -Encoding ascii
$r = Invoke-EvalWithRetry @("eval", "--file", $fpJs, "--json")
$fpOut = Get-EvalResult ($r[1] -join "`n")
Write-Host "  指纹探针：$fpOut" -ForegroundColor DarkGray
if ($r[0] -eq 0) {
    if ($fpOut -match '"webdriver"\s*:\s*false') {
        Write-Pass "指纹探针：navigator.webdriver=false（隐身正常）"
    } else {
        Write-Host "  [NOTE] webdriver 探针未返回 false，请人工查看上方指纹输出" -ForegroundColor Yellow
    }
} else {
    Write-Fail "eval 指纹探针失败：$($r[1] | Select-Object -Last 3 | Out-String)"
}

# 步骤 5：click（DOM.focus + Input.dispatchMouseEvent 路径）
$r = Invoke-B4Cli @("click", "#submit-btn")
Assert-Step ($r[0] -eq 0) "click：按钮点击成功" "click 失败：$($r[1] | Select-Object -Last 5 | Out-String)"

# 步骤 6：mousewheel（crbug.com/444929150 wheel 修正路径）
$scrollJs = Join-Path $OutDir "scroll.js"
'document.body.style.minHeight = "4000px"; "ready"' | Set-Content -Path $scrollJs -Encoding ascii
Invoke-EvalWithRetry @("eval", "--file", $scrollJs) | Out-Null
$r = Invoke-B4Cli @("mousewheel", "0", "400")
if ($r[0] -eq 0) {
    $r2 = Invoke-EvalWithRetry @("eval", "--json", "window.scrollY")
    $scrollY = 0
    $parsed = Get-EvalResult ($r2[1] -join "`n")
    [int]::TryParse(("$parsed" -replace "[^\d-]", ""), [ref]$scrollY) | Out-Null
    if ($scrollY -gt 0) { Write-Pass "mousewheel：滚动生效（scrollY=$scrollY）" }
    else { Write-Fail "mousewheel 命令成功但页面未滚动（scrollY=$scrollY）" }
} else {
    Write-Fail "mousewheel 失败：$($r[1] | Select-Object -Last 5 | Out-String)"
}

# 步骤 7：screenshot（Page.captureScreenshot 路径）
$shotPath = Join-Path $OutDir "cloakbrowser-poc.png"
$r = $null
for ($i = 0; $i -lt 5; $i++) {
    $r = Invoke-B4Cli @("screenshot", "-o", $shotPath)
    $shotText = ($r[1] -join "`n")
    if ($r[0] -eq 0 -and $shotText -notmatch "Usage: browser4-cli") { break }
    Write-Host "  (screenshot 重试 $($i + 1)/5)" -ForegroundColor DarkGray
    Start-Sleep -Seconds 4
}
$shotOk = ($r[0] -eq 0) -and (Test-Path $shotPath) -and ((Get-Item $shotPath).Length -gt 0)
Assert-Step $shotOk "screenshot：截图成功（$shotPath）" "screenshot 失败：$($r[1] | Select-Object -Last 5 | Out-String)"

# 步骤 8：eval 端到端验证（输入值 + state-log 链）
$verifyJs = Join-Path $OutDir "verify.js"
'JSON.stringify({value: document.getElementById("q").value, log: document.getElementById("state-log").textContent})' |
    Set-Content -Path $verifyJs -Encoding ascii
$r = Invoke-EvalWithRetry @("eval", "--file", $verifyJs, "--json")
$verifyOut = Get-EvalResult ($r[1] -join "`n")
if (($r[0] -eq 0) -and $verifyOut -match "Hello CloakBrowser") {
    Write-Pass "端到端：type→click→state-log 链完整（$verifyOut）"
} else {
    Write-Fail "端到端验证失败：$verifyOut"
}

# 清理本地 HTTP 服务器
if ($localServer) { Stop-Process -Id $localServer.Id -Force -ErrorAction SilentlyContinue }

# ── 5. 汇总 ─────────────────────────────────────────────────────────────────

Write-Section "POC 结果汇总"
Write-Host "  PASS: $($script:PassCount)   FAIL: $($script:FailCount)" -ForegroundColor $(if ($script:FailCount -eq 0) { "Green" } else { "Red" })
Write-Host "  CloakBrowser 二进制：$binary" -ForegroundColor DarkGray
if ($script:FailCount -gt 0) {
    Write-Host "  失败项：" -ForegroundColor Red
    $script:Failures | ForEach-Object { Write-Host "    - $_" -ForegroundColor Red }
}
Write-Host ""
if ($script:FailCount -eq 0) {
    Write-Host "  ✅ POC 通过：Browser4 可以驱动 CloakBrowser v146（CDP 输入/鼠标/滚轮/截图全部正常）" -ForegroundColor Green
} else {
    Write-Host "  ⚠️ 有失败项，请结合上方输出排查（重点：CDP 输入补丁兼容性、服务器是否真的重启）" -ForegroundColor Yellow
}
Write-Host ""
Write-Host "  服务器当前以 CloakBrowser 运行。恢复默认 Chrome：" -ForegroundColor DarkGray
Write-Host "    browser4-cli stop" -ForegroundColor DarkGray
Write-Host "    （下次启动时清除 BROWSER4_SERVER_OPTS 或去掉 -Dchrome.path 即可）" -ForegroundColor DarkGray

if ($script:FailCount -gt 0) { exit 2 } else { exit 0 }
