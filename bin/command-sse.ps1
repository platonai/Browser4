#!/usr/bin/env pwsh

# Find the first parent directory containing the VERSION file
$AppHome=(Get-Item -Path $MyInvocation.MyCommand.Path).Directory
while ($AppHome -ne $null -and !(Test-Path "$AppHome/VERSION")) {
  $AppHome = Split-Path -Parent $AppHome
}
Set-Location $AppHome

# 设置错误处理
$ErrorActionPreference = "Stop"

# 自然语言命令内容
$COMMAND = @'
    Go to https://www.amazon.com/dp/B08PP5MSVB

    After browser launch:
      - clear browser cookies
      - go to https://www.amazon.com/
      - wait for 5 seconds
      - click the first product link
    After page load: scroll to the middle.

    Summarize the product.
    Extract: product name, price, ratings.
    Find all links containing /dp/.
'@

# API 接口
$API_BASE = "http://localhost:8182"
$COMMAND_ENDPOINT = "$API_BASE/api/commands/plain?mode=async"

# 发送命令
Write-Host "Sending command to server..."

try {
  $response = Invoke-RestMethod -Uri $COMMAND_ENDPOINT -Method POST -Body $COMMAND -ContentType "text/plain"
  $COMMAND_ID = $response
} catch {
  Write-Error "Error: Failed to get command ID from server. $_"
  exit 1
}

# 检查 command ID 是否有效
if ([string]::IsNullOrWhiteSpace($COMMAND_ID)) {
  Write-Error "Error: Failed to get command ID from server."
  exit 1
}

Write-Host "Command ID: $COMMAND_ID"

# SSE 监听地址
$SSE_URL = "$API_BASE/api/commands/$COMMAND_ID/stream"
$RESULT_URL = "$API_BASE/api/commands/$COMMAND_ID/result"

Write-Host "Connecting to SSE stream..."

# 创建 HTTP 客户端用于 SSE
$httpClient = New-Object System.Net.Http.HttpClient
$httpClient.Timeout = [System.TimeSpan]::FromMinutes(30)

# 设置 SSE 请求头
$request = New-Object System.Net.Http.HttpRequestMessage([System.Net.Http.HttpMethod]::Get, $SSE_URL)
$request.Headers.Add("Accept", "text/event-stream")
$request.Headers.Add("Cache-Control", "no-cache")

# 清理函数
function Cleanup {
  Write-Host "Cleaning up resources..."
  if ($httpClient) {
    $httpClient.Dispose()
  }
  if ($response) {
    $response.Dispose()
  }
  if ($stream) {
    $stream.Dispose()
  }
  if ($reader) {
    $reader.Dispose()
  }
}

# 获取最终结果的函数
function Get-FinalResult {
  param($CommandId)

  Write-Host "`n=== FETCHING FINAL RESULT ===" -ForegroundColor Green

  try {
    $result = Invoke-RestMethod -Uri $RESULT_URL -Method GET -ContentType "application/json"

    Write-Host "`n=== FINAL RESULT ===" -ForegroundColor Yellow
    Write-Host "Command ID: $CommandId" -ForegroundColor Cyan
    Write-Host "Timestamp: $(Get-Date -Format 'yyyy-MM-dd HH:mm:ss') UTC" -ForegroundColor Cyan
    Write-Host "Status: COMPLETED" -ForegroundColor Green

    # 如果结果是 JSON，尝试格式化输出
    if ($result -is [string]) {
      try {
        $jsonResult = $result | ConvertFrom-Json
        Write-Host "`nStructured Result:" -ForegroundColor Magenta
        $jsonResult | ConvertTo-Json -Depth 10 | Write-Host
      } catch {
        Write-Host "`nRaw Result:" -ForegroundColor Magenta
        Write-Host $result
      }
    } else {
      Write-Host "`nResult Object:" -ForegroundColor Magenta
      $result | ConvertTo-Json -Depth 10 | Write-Host
    }

    Write-Host "`n=== END OF RESULT ===" -ForegroundColor Yellow

  } catch {
    Write-Warning "Failed to fetch final result: $_"
    Write-Host "You can manually check the result at: $RESULT_URL"
  }
}

# 注册清理函数
try {
  # 发送请求并获取响应流
  $response = $httpClient.SendAsync($request, [System.Net.Http.HttpCompletionOption]::ResponseHeadersRead).Result

  if (-not $response.IsSuccessStatusCode) {
    throw "HTTP request failed with status: $($response.StatusCode)"
  }

  $stream = $response.Content.ReadAsStreamAsync().Result
  $reader = New-Object System.IO.StreamReader($stream)

  # SSE 主循环
  $isDone = $false
  $lastUpdate = ""
  $TIMEOUT_SECONDS = 900  # 15 minutes
  $START_TIME = Get-Date

  Write-Host "Reading SSE stream..."

  while (-not $reader.EndOfStream -and -not $isDone) {
    $line = $reader.ReadLine()

    # Check timeout
    $ELAPSED = ((Get-Date) - $START_TIME).TotalSeconds
    if ($ELAPSED -gt $TIMEOUT_SECONDS) {
      Write-Error "ERROR: SSE stream timed out after $TIMEOUT_SECONDS seconds without receiving done signal"
      exit 1
    }

    # 跳过空行或注释
    if ([string]::IsNullOrWhiteSpace($line) -or $line.StartsWith(":")) {
      continue
    }

    # 提取 data 字段
    if ($line.StartsWith("data:")) {
      $data = $line.Substring(5).Trim()

      # 避免重复打印相同的更新
      if ($data -ne $lastUpdate) {
        Write-Host "SSE update: $data" -ForegroundColor Gray
        $lastUpdate = $data
      }

      # 检查是否已完成
      if ($data -match '"done"\s*:\s*true') {
        $isDone = $true
        Write-Host "`nReceived done signal, preparing to exit..." -ForegroundColor Green
        Write-Host "Task completed! Fetching final result..." -ForegroundColor Green
        Start-Sleep -Seconds 2  # 等待服务器完全处理完成

        # 获取并打印最终结果
        Get-FinalResult -CommandId $COMMAND_ID
        break
      }
    }

    # 添加小延迟以避免过度占用 CPU
    Start-Sleep -Milliseconds 50
  }

  if (-not $isDone) {
    Write-Warning "SSE stream ended but task may not be completed."
    Write-Host "Attempting to fetch result anyway..."
    Get-FinalResult -CommandId $COMMAND_ID
  }

} catch {
  Write-Error "Error during SSE processing: $_"
  Write-Host "Attempting to fetch any available result..."
  Get-FinalResult -CommandId $COMMAND_ID
} finally {
  Cleanup
}

Write-Host "`nFinished command-sse.ps1 script." -ForegroundColor Green