#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Avoid Windows-only env vars ($env:TEMP) — use $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# - Paths: use Join-Path / Split-Path; never bake \ or / as literal.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
  Tests for webminer.ps1 — cross-platform compatibility and URL construction.
  PowerShell 7+ only (script uses pwsh shebang and $IsWindows).
  Run: pwsh -NoProfile -ExecutionPolicy Bypass -File webminer.tests.ps1
#>

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$webminerScript = Join-Path (Split-Path $scriptDir -Parent) "webminer.ps1"

$pass = 0
$fail = 0

function Test($name, [ScriptBlock]$block) {
    try {
        $null = & $block
        $script:pass++
        Write-Host "  PASS  $name" -ForegroundColor Green
    } catch {
        $script:fail++
        Write-Host "  FAIL  $name" -ForegroundColor Red
        Write-Host "        $($_.Exception.Message)" -ForegroundColor Red
    }
}

function RunScript([string]$scriptArgs, [ref]$exitCode) {
    $tmpErr = [System.IO.Path]::GetTempFileName()
    $cmd = "& '$webminerScript' $scriptArgs 2>'$tmpErr'"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = if ($IsWindows -or ($env:OS -eq 'Windows_NT')) { "pwsh.exe" } else { "pwsh" }
    $psi.Arguments = "-NoProfile -ExecutionPolicy Bypass -Command `"$cmd`""
    $psi.RedirectStandardOutput = $true
    $psi.UseShellExecute = $false
    $psi.CreateNoWindow = $true
    $proc = [System.Diagnostics.Process]::Start($psi)
    $out = $proc.StandardOutput.ReadToEnd()
    $proc.WaitForExit()
    $exitCode.Value = $proc.ExitCode
    $err = if (Test-Path $tmpErr) { Get-Content $tmpErr -Raw -ErrorAction SilentlyContinue; Remove-Item $tmpErr -Force -ErrorAction SilentlyContinue } else { "" }
    return [PSCustomObject]@{ Output = $out; Error = $err; ExitCode = $proc.ExitCode }
}

Write-Host "============================================" -ForegroundColor Cyan
Write-Host " webminer.ps1 Test Suite" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ── Pre-flight ──
Write-Host "--- Pre-flight ---" -ForegroundColor Cyan

Test "file exists" {
    if (-not (Test-Path $webminerScript)) { throw "Not found: $webminerScript" }
}

Test "AST parses without errors" {
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $webminerScript, [ref]$null, [ref]$parseErrors
    )
    if ($parseErrors.Count -gt 0) {
        $msg = ($parseErrors | ForEach-Object { "L$($_.Extent.StartLineNumber): $($_.Message)" }) -join "; "
        throw $msg
    }
}

Write-Host ""

# ── Cross-platform constants (AST scan) ──
Write-Host "--- Cross-platform constants ---" -ForegroundColor Cyan

$content = Get-Content $webminerScript -Raw

Test "defines platform-aware `$HomeDir" {
    if ($content -notmatch '\$HomeDir\s*=\s*if\s*\(\$IsWindows\)') {
        throw "Missing cross-platform `$HomeDir definition"
    }
}

Test "falls back to `$env:HOME on non-Windows" {
    if ($content -notmatch '\$env:HOME') {
        throw "Missing `$env:HOME fallback for Linux/macOS"
    }
}

Test "defines `$TempDir with env:TMPDIR and /tmp fallback" {
    if ($content -notmatch '\$env:TMPDIR') {
        throw "Missing `$env:TMPDIR fallback in `$TempDir definition"
    }
    if ($content -notmatch '/tmp') {
        throw "Missing /tmp fallback in `$TempDir definition"
    }
}

Test "defines platform-aware `$JavaExeName" {
    if ($content -notmatch '\$JavaExeName\s*=\s*if\s*\(\$IsWindows\)') {
        throw "Missing platform-aware `$JavaExeName definition"
    }
}

Test "defines platform-aware `$SevenZipName" {
    if ($content -notmatch '\$SevenZipName\s*=\s*if\s*\(\$IsWindows\)') {
        throw "Missing platform-aware `$SevenZipName definition"
    }
}

Test "defines platform-aware `$CurlExeName" {
    if ($content -notmatch '\$CurlExeName\s*=\s*if\s*\(\$IsWindows\)') {
        throw "Missing platform-aware `$CurlExeName definition"
    }
}

Write-Host ""

# ── OSS mirror constants ──
Write-Host "--- OSS mirror constants ---" -ForegroundColor Cyan

Test "defines `$OSS_BASE_URL for Aliyun OSS" {
    if ($content -notmatch '\$OSS_BASE_URL\s*=\s*''https://web-miner\.oss-cn-beijing\.aliyuncs\.com''') {
        throw "Missing or malformed `$OSS_BASE_URL"
    }
}

Test "defines `$OSS_LATEST_JSON" {
    if ($content -notmatch '\$OSS_LATEST_JSON\s*=\s*"\$OSS_BASE_URL/releases/latest-release\.json"') {
        throw "Missing or malformed `$OSS_LATEST_JSON"
    }
}

Test "defines `$OSS_LATEST_DOWNLOAD" {
    if ($content -notmatch '\$OSS_LATEST_DOWNLOAD\s*=\s*"\$OSS_BASE_URL/releases/latest/download"') {
        throw "Missing or malformed `$OSS_LATEST_DOWNLOAD"
    }
}

Write-Host ""

# ── No hardcoded platform assumptions outside definitions ──
Write-Host "--- No hardcoded platform assumptions ---" -ForegroundColor Cyan

# Extract the script body after the constants block (after line ~81 which ends the constants)
# We want to check that java.exe, 7z.exe, curl.exe only appear in the definition block
$lines = $content -split '\r?\n'
$defBlockEnd = 0
for ($i = 0; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -match '^\$CurlExeName\s*=') {
        $defBlockEnd = $i + 1
        break
    }
}

$bodyLines = $lines[$defBlockEnd..($lines.Count - 1)] -join "`n"

Test "no bare java.exe outside definition block" {
    # Only allowed via $JavaExeName variable reference
    if ($bodyLines -match '(?<!\$)java\.exe') {
        # Check it's not in a comment
        $matches = [regex]::Matches($bodyLines, '(?<!\$)java\.exe')
        $nonComment = @($matches | Where-Object {
            $lineNum = $_.Index
            $line = $bodyLines.Substring(0, [Math]::Min($bodyLines.Length, $lineNum + 1)).Split("`n")[-1]
            $line.TrimStart() -notmatch '^#'
        })
        if ($nonComment.Count -gt 0) {
            throw "Found $($nonComment.Count) hardcoded java.exe reference(s) outside definition block"
        }
    }
}

Test "no bare `$env:USERPROFILE outside definition block" {
    if ($bodyLines -match '\$env:USERPROFILE') {
        throw "`$env:USERPROFILE used outside constant definition block — use `$HomeDir instead"
    }
}

Test "no bare `$env:TEMP outside definition block" {
    if ($bodyLines -match '\$env:TEMP') {
        throw "`$env:TEMP used outside constant definition block — use `$TempDir instead"
    }
}

Test "curl.exe only in definition block" {
    if ($bodyLines -match 'curl\.exe' -and $bodyLines -notmatch '\$CurlExeName') {
        throw "curl.exe used outside definition block — use `$CurlExeName instead"
    }
}

Write-Host ""

# ── Functions via dot-source ──
Write-Host "--- Functions ---" -ForegroundColor Cyan

# Extract the function + constant content for dot-sourcing without executing
# the main dispatch.  We neuter exit statements so dot-sourcing can't kill the
# test process, then truncate at the main dispatch section.

# Remove shebang line
$funcContent = $content -replace '^#!/usr/bin/env pwsh\r?\n', ''
# Remove $ErrorActionPreference = 'Stop' so it doesn't affect the test harness
$funcContent = $funcContent -replace '\$ErrorActionPreference\s*=\s*''Stop''', ''
# Replace any "exit 0" or "exit 1" with "return" so dot-sourcing can't kill us
$funcContent = $funcContent -replace '\bexit 0\b', 'return'
$funcContent = $funcContent -replace '\bexit 1\b', 'return'
$funcContent = $funcContent -replace '\bexit\b', 'return'

# Cut off everything at the main dispatch section
$dispatchMarker = '# --- Management commands (no Java needed) ---'
$idx = $funcContent.IndexOf($dispatchMarker)
if ($idx -gt 0) {
    $funcContent = $funcContent.Substring(0, $idx)
}

# The top-level constants reference $IsWindows. When dot-sourced in a test scope,
# these should resolve correctly. We need to ensure the variables exist in scope.
& {
    # Pre-define variables that would be set by the constants
    $REPO_OWNER = 'platonai'
    $REPO_NAME  = 'web-miner'
    $GITHUB_API_LATEST = "https://api.github.com/repos/$REPO_OWNER/$REPO_NAME/releases/latest"
    $OSS_BASE_URL = 'https://web-miner.oss-cn-beijing.aliyuncs.com'
    $OSS_LATEST_JSON = "$OSS_BASE_URL/releases/latest-release.json"
    $OSS_LATEST_DOWNLOAD = "$OSS_BASE_URL/releases/latest/download"

    # Write the extracted content to a temp file and dot-source it.
    # This avoids ScriptBlock-scope issues: $PSCommandPath is set to the
    # temp file path, and functions dot-sourced from a real file are
    # visible in the caller's scope.
    $tempScript = Join-Path ([System.IO.Path]::GetTempPath()) "webminer-funcs-$(Get-Random).ps1"
    try {
        # Pre-neuter the $HelpRequested check so it won't fire (we have no args).
        # The assignment spans multiple lines — use (?s) for single-line mode.
        $fixedContent = $funcContent -replace '(?s)\$HelpRequested\s*=\s*\(.*?\)', '$HelpRequested = $false  # neutered for dot-source tests'
        $fixedContent | Out-File -FilePath $tempScript -Encoding utf8

        # Dot-source the temp file — suppress all output to avoid help text noise
        . $tempScript *>$null
    } finally {
        Remove-Item $tempScript -Force -ErrorAction SilentlyContinue
    }

    # ── Get-OssJarUrl ──
    Test "Get-OssJarUrl 'latest' returns latest download URL" {
        $url = Get-OssJarUrl -TagName 'latest'
        if ($url -ne 'https://web-miner.oss-cn-beijing.aliyuncs.com/releases/latest/download/scent-miner.jar') {
            throw "Unexpected URL: $url"
        }
    }

    Test "Get-OssJarUrl with specific tag returns versioned URL" {
        $url = Get-OssJarUrl -TagName 'v0.0.7'
        if ($url -ne 'https://web-miner.oss-cn-beijing.aliyuncs.com/releases/download/v0.0.7/scent-miner.jar') {
            throw "Unexpected URL: $url"
        }
    }

    Test "Get-OssJarUrl with v0.1.0 tag" {
        $url = Get-OssJarUrl -TagName 'v0.1.0'
        if ($url -notmatch 'releases/download/v0\.1\.0/scent-miner\.jar$') {
            throw "Unexpected URL: $url"
        }
    }

    # ── Get-OssSha256Url ──
    Test "Get-OssSha256Url 'latest' returns latest sha256 URL" {
        $url = Get-OssSha256Url -TagName 'latest'
        if ($url -ne 'https://web-miner.oss-cn-beijing.aliyuncs.com/releases/latest/download/scent-miner.jar.sha256') {
            throw "Unexpected URL: $url"
        }
    }

    Test "Get-OssSha256Url with specific tag returns versioned sha256 URL" {
        $url = Get-OssSha256Url -TagName 'v0.0.7'
        if ($url -ne 'https://web-miner.oss-cn-beijing.aliyuncs.com/releases/download/v0.0.7/scent-miner.jar.sha256') {
            throw "Unexpected URL: $url"
        }
    }

    # ── Get-InstalledVersion ──
    Test "Get-InstalledVersion returns null when no installation exists" {
        $oldVersionFile = $VersionFile
        try {
            # Point to a non-existent path
            Set-Variable -Name VersionFile -Scope 1 -Value (Join-Path ([System.IO.Path]::GetTempPath()) 'nonexistent-version.txt')
            $ver = Get-InstalledVersion
            if ($ver -ne $null) { throw "Expected null, got: $ver" }
        } finally {
            Set-Variable -Name VersionFile -Scope 1 -Value $oldVersionFile
        }
    }

    Test "Get-InstalledVersion reads version from file" {
        $oldVersionFile = $VersionFile
        $tmpVersion = Join-Path ([System.IO.Path]::GetTempPath()) "webminer-test-version-$(Get-Random).txt"
        try {
            Set-Variable -Name VersionFile -Scope 1 -Value $tmpVersion
            'v0.0.7' | Out-File -FilePath $tmpVersion -Encoding utf8 -NoNewline
            $ver = Get-InstalledVersion
            if ($ver -ne 'v0.0.7') { throw "Expected v0.0.7, got: $ver" }
        } finally {
            Set-Variable -Name VersionFile -Scope 1 -Value $oldVersionFile
            Remove-Item $tmpVersion -Force -ErrorAction SilentlyContinue
        }
    }

    # ── $HomeDir resolution ──
    Test "`$HomeDir is non-empty" {
        if ([string]::IsNullOrEmpty($HomeDir)) {
            throw "`$HomeDir is empty"
        }
    }

    Test "`$HomeDir exists as a directory" {
        if (-not (Test-Path $HomeDir -PathType Container)) {
            throw "`$HomeDir '$HomeDir' does not exist"
        }
    }

    # ── $TempDir resolution ──
    Test "`$TempDir is non-empty" {
        if ([string]::IsNullOrEmpty($TempDir)) {
            throw "`$TempDir is empty"
        }
    }

    # ── Platform-aware binary names ──
    Test "`$JavaExeName matches platform (.exe only on Windows)" {
        if ($IsWindows) {
            if ($JavaExeName -ne 'java.exe') { throw "Expected java.exe on Windows, got: $JavaExeName" }
        } else {
            if ($JavaExeName -ne 'java') { throw "Expected java on non-Windows, got: $JavaExeName" }
        }
    }

    Test "`$SevenZipName matches platform (.exe only on Windows)" {
        if ($IsWindows) {
            if ($SevenZipName -ne '7z.exe') { throw "Expected 7z.exe on Windows, got: $SevenZipName" }
        } else {
            if ($SevenZipName -ne '7z') { throw "Expected 7z on non-Windows, got: $SevenZipName" }
        }
    }

    Test "`$CurlExeName matches platform (.exe only on Windows)" {
        if ($IsWindows) {
            if ($CurlExeName -ne 'curl.exe') { throw "Expected curl.exe on Windows, got: $CurlExeName" }
        } else {
            if ($CurlExeName -ne 'curl') { throw "Expected curl on non-Windows, got: $CurlExeName" }
        }
    }

    # ── Find-7Zip: returns something or null (but never throws) ──
    Test "Find-7Zip does not throw" {
        try {
            $result = Find-7Zip
            # $null is fine (7z not installed), non-null is fine (found it)
        } catch {
            throw "Find-7Zip threw unexpectedly: $_"
        }
    }

    # ── InstallRoot resolution ──
    Test "`$InstallRoot is inside `$HomeDir" {
        # Use -like with wildcard; avoids regex-escaping backslash issues
        if ($InstallRoot -notlike "$HomeDir*") {
            throw "`$InstallRoot '$InstallRoot' does not start with `$HomeDir '$HomeDir'"
        }
    }

    Test "`$InstallRoot ends with .scent\webminer or .scent/webminer" {
        if ($InstallRoot -notmatch '[\\/]\.scent[\\/]webminer$') {
            throw "`$InstallRoot '$InstallRoot' does not end with .scent/webminer"
        }
    }

    # ── OSS constants resolved correctly ──
    Test "`$OSS_BASE_URL is Aliyun OSS Beijing" {
        if ($OSS_BASE_URL -ne 'https://web-miner.oss-cn-beijing.aliyuncs.com') {
            throw "Unexpected OSS_BASE_URL: $OSS_BASE_URL"
        }
    }

    Test "`$OSS_LATEST_JSON is relative to BASE_URL" {
        if ($OSS_LATEST_JSON -ne "$OSS_BASE_URL/releases/latest-release.json") {
            throw "Unexpected OSS_LATEST_JSON: $OSS_LATEST_JSON"
        }
    }

    Test "`$OSS_LATEST_DOWNLOAD is relative to BASE_URL" {
        if ($OSS_LATEST_DOWNLOAD -ne "$OSS_BASE_URL/releases/latest/download") {
            throw "Unexpected OSS_LATEST_DOWNLOAD: $OSS_LATEST_DOWNLOAD"
        }
    }
}

Write-Host ""

# ── Smoke tests (run actual script) ──
Write-Host "--- Smoke tests ---" -ForegroundColor Cyan

Test "--help exits with code 0" {
    $ec = 0; $r = RunScript -scriptArgs "--help" -exitCode ([ref]$ec)
    if ($ec -ne 0) { throw "Exit code: $ec, stderr: $($r.Error)" }
}

Test "--help output mentions WebMiner" {
    $ec = 0; $r = RunScript -scriptArgs "--help" -exitCode ([ref]$ec)
    if ($r.Output -notmatch 'WebMiner') {
        throw "Help output missing 'WebMiner': $($r.Output.Substring(0, [Math]::Min(300, $r.Output.Length)))"
    }
}

Test "--help output mentions install command" {
    $ec = 0; $r = RunScript -scriptArgs "--help" -exitCode ([ref]$ec)
    if ($r.Output -notmatch 'install') {
        throw "Help output missing 'install'"
    }
}

Test "--help output shows OSS mirror mention" {
    $ec = 0; $r = RunScript -scriptArgs "--help" -exitCode ([ref]$ec)
    if ($r.Output -notmatch 'OSS mirror') {
        throw "Help output missing 'OSS mirror' fallback mention: $($r.Output.Substring(0, [Math]::Min(500, $r.Output.Length)))"
    }
}

Test "no-args exits with code 0 (prints help)" {
    $ec = 0; $r = RunScript -scriptArgs "" -exitCode ([ref]$ec)
    if ($ec -ne 0) { throw "Exit code: $ec, stderr: $($r.Error)" }
}

Test "version command exits with code 0" {
    $ec = 0; $r = RunScript -scriptArgs "version" -exitCode ([ref]$ec)
    if ($ec -ne 0) { throw "Exit code: $ec, stderr: $($r.Error)" }
}

Test "version command shows WebMiner header" {
    $ec = 0; $r = RunScript -scriptArgs "version" -exitCode ([ref]$ec)
    if ($r.Output -notmatch 'WebMiner') {
        throw "Version output missing 'WebMiner': $($r.Output.Substring(0, [Math]::Min(300, $r.Output.Length)))"
    }
}

Test "version command shows Installed line" {
    $ec = 0; $r = RunScript -scriptArgs "version" -exitCode ([ref]$ec)
    if ($r.Output -notmatch 'Installed') {
        throw "Version output missing 'Installed': $($r.Output.Substring(0, [Math]::Min(300, $r.Output.Length)))"
    }
}

Test "uninstall without installation exits with code 0 (idempotent)" {
    $ec = 0; $r = RunScript -scriptArgs "uninstall" -exitCode ([ref]$ec)
    if ($ec -ne 0) { throw "Exit code: $ec, stderr: $($r.Error)" }
}

Test "install without args shows error (no version and offline)" {
    $ec = 0; $r = RunScript -scriptArgs "install" -exitCode ([ref]$ec)
    # Should exit non-zero because it can't reach GitHub / OSS to find latest
    if ($ec -eq 0) {
        # If it succeeded (network available), that's also acceptable
        Write-Host "        (install succeeded — network available, found latest release)" -ForegroundColor DarkGray
    }
    # Either outcome is valid: 0 if network works, non-0 if offline
    # The key assertion is that it doesn't crash with a PS error
    if ($r.Error -match 'Cannot bind argument|ParserError|RuntimeException') {
        throw "install crashed: $($r.Error)"
    }
}

Write-Host ""

# ── Summary ──
Write-Host "============================================" -ForegroundColor Cyan
$total = $pass + $fail
Write-Host " Results: $pass / $total passed" -ForegroundColor $(if ($fail -eq 0) { "Green" } else { "Red" })
Write-Host "============================================" -ForegroundColor Cyan

if ($fail -gt 0) { exit 1 }
exit 0
