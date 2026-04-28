#!/usr/bin/env pwsh

function Fix-Encoding-UTF8 {
    $Encoding = "UTF8"
    # 设置错误处理
    $ErrorActionPreference = "Stop"

    # Windows PowerShell 5.1 uses the active console code page for host rendering.
    # Switch it to UTF-8 to avoid mojibake when printing Chinese text.
    try {
        if ($IsWindows -or $env:OS -eq "Windows_NT") {
            cmd /c chcp 65001 > $null
        }
    } catch {
        # Best-effort only; continue even if code page can't be changed.
    }

    # 强制 PowerShell 使用 UTF-8 输出
    $OutputEncoding = [Console]::OutputEncoding = [System.Text.Encoding]::UTF8
    [Console]::InputEncoding  = [System.Text.Encoding]::UTF8

    # 强制 Maven 使用 UTF-8（尤其是 exec:java）
    $env:MAVEN_OPTS = "-Dfile.encoding=$Encoding"
    $env:JAVA_TOOL_OPTIONS = "-Dfile.encoding=$Encoding"
}

Fix-Encoding-UTF8

& (Join-Path $PSScriptRoot "Browser4.exe") @args
