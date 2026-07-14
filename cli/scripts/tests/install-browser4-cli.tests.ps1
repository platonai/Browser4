<#
.SYNOPSIS
  Tests for install-browser4-cli.ps1
  PowerShell 5.1+ only — zero external dependencies.
  Run: powershell -NoProfile -ExecutionPolicy Bypass -File install-browser4-cli.tests.ps1
#>

$ErrorActionPreference = "Stop"
$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$installScript = Join-Path (Split-Path $scriptDir -Parent) "install-browser4-cli.ps1"

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
    # Use -Command to control stderr redirect (2> path must come before script args,
    # which -File would pass as literal arguments to the script).
    $tmpErr = [System.IO.Path]::GetTempFileName()
    $cmd = "& '$installScript' $scriptArgs 2>'$tmpErr'"
    $psi = New-Object System.Diagnostics.ProcessStartInfo
    $psi.FileName = if ($IsWindows -or ($env:OS -eq 'Windows_NT')) { "powershell.exe" } else { "pwsh" }
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
Write-Host " install-browser4-cli.ps1 Test Suite" -ForegroundColor Cyan
Write-Host "============================================" -ForegroundColor Cyan
Write-Host ""

# ── Pre-flight ──
Write-Host "--- Pre-flight ---" -ForegroundColor Cyan

Test "file exists" {
    if (-not (Test-Path $installScript)) { throw "Not found: $installScript" }
}

Test "file is readable" {
    $null = Get-Content $installScript -Raw -ErrorAction Stop
}

Test "no non-ASCII bytes" {
    $bytes = [System.IO.File]::ReadAllBytes($installScript)
    $nonAscii = @($bytes | Where-Object { $_ -gt 127 })
    if ($nonAscii.Count -gt 0) {
        throw "Found $($nonAscii.Count) non-ASCII bytes"
    }
}

Test "AST parses without errors" {
    $parseErrors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $installScript, [ref]$null, [ref]$parseErrors
    )
    if ($parseErrors.Count -gt 0) {
        $msg = ($parseErrors | ForEach-Object { "L$($_.Extent.StartLineNumber): $($_.Message)" }) -join "; "
        throw $msg
    }
}

Write-Host ""

# ── Param block (via AST) ──
Write-Host "--- Param block ---" -ForegroundColor Cyan

$content = Get-Content $installScript -Raw
$parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseInput($content, [ref]$null, [ref]$parseErrors)

Test "param block defines all expected parameters" {
    $expected = @('Version', 'InstallDir', 'Source', 'AddToPath',
                  'Silent', 'DryRun', 'SkipIfInstalled', 'SkipLocal', 'Locate')
    # Extract param block text via AST extents
    $paramAst = $ast.ParamBlock
    if (-not $paramAst) { throw "Could not find param block AST" }
    $txt = $content.Substring($paramAst.Extent.StartOffset, $paramAst.Extent.EndOffset - $paramAst.Extent.StartOffset)
    foreach ($p in $expected) {
        if ($txt -notmatch ('\$' + $p + '\b')) {
            throw "Missing parameter: $p"
        }
    }
}

Test "Source has ValidateSet with empty string" {
    if ($content -notmatch 'ValidateSet\("",\s*"github",\s*"oss"\)') {
        throw "ValidateSet must include empty string for iex compatibility"
    }
}

Write-Host ""

# ── Locate mode ──
Write-Host "--- Locate mode ---" -ForegroundColor Cyan

$ec = 0
$r = RunScript -scriptArgs "-Locate" -exitCode ([ref]$ec)

Test "-Locate exits with code 0" {
    if ($ec -ne 0) { throw "Exit code: $ec" }
}

Test "-Locate shows platform key" {
    if ($r.Output -notmatch 'Platform key') {
        throw "Missing 'Platform key' in: $($r.Output.Substring(0, [Math]::Min(500, $r.Output.Length)))"
    }
}

Test "-Locate shows binary name" {
    if ($r.Output -notmatch 'Binary name') {
        throw "Missing 'Binary name' in output (len=$($r.Output.Length))"
    }
}

Test "-Locate shows download order" {
    if ($r.Output -notmatch 'Download order') {
        throw "Missing 'Download order' in output"
    }
}

Write-Host ""

# ── Download URLs (via -Locate output) ──
Write-Host "--- Download URLs ---" -ForegroundColor Cyan

Test "locate shows correct GitHub latest/download URL" {
    if ($r.Output -notmatch 'github\.com/platonai/Browser4/releases/latest/download/') {
        $lines = ($r.Output -split '\n' | Where-Object { $_ -match 'ownload' }) -join '; '
        throw "GitHub latest URL not found. Download lines: $lines"
    }
}

Test "locate shows correct OSS download/latest URL" {
    if ($r.Output -notmatch 'oss-cn-beijing.*?releases/download/latest/') {
        $lines = ($r.Output -split '\n' | Where-Object { $_ -match 'ownload' }) -join '; '
        throw "OSS latest URL not found. Download lines: $lines"
    }
}

# Test versioned URLs
$ec2 = 0
$r2 = RunScript -scriptArgs "-Version v4.11.0 -Locate" -exitCode ([ref]$ec2)

Test "-Version shows tag-based URLs" {
    if ($r2.Output -notmatch 'releases/download/v4\.11\.0/') {
        throw "Versioned URL not in output"
    }
}

Write-Host ""

# ── Parameter acceptance ──
Write-Host "--- Parameter acceptance ---" -ForegroundColor Cyan

Test "-SkipIfInstalled flag accepted" {
    $ec3 = 0; $r3 = RunScript -scriptArgs "-SkipIfInstalled -DryRun" -exitCode ([ref]$ec3)
    if ($ec3 -ne 0) { throw "Exit code: $ec3, output: $($r3.Output)" }
}

Test "-SkipLocal flag accepted" {
    $ec4 = 0; $r4 = RunScript -scriptArgs "-SkipLocal -DryRun" -exitCode ([ref]$ec4)
    if ($ec4 -ne 0) { throw "Exit code: $ec4, output: $($r4.Output)" }
}

Test "-Force rejected (replaced by -SkipIfInstalled)" {
    $ec5 = 0; $r5 = RunScript -scriptArgs "-Force -DryRun" -exitCode ([ref]$ec5)
    if ($r5.Error -notmatch 'Force') {
        throw "-Force should be rejected, got: $($r5.Error)"
    }
}

Test "-Source oss accepted" {
    $ec6 = 0; $r6 = RunScript -scriptArgs "-Source oss -DryRun" -exitCode ([ref]$ec6)
    if ($ec6 -ne 0) { throw "Exit code: $ec6, output: $($r6.Output)" }
}

Test "-Source github accepted" {
    $ec7 = 0; $r7 = RunScript -scriptArgs "-Source github -DryRun" -exitCode ([ref]$ec7)
    if ($ec7 -ne 0) { throw "Exit code: $ec7, output: $($r7.Output)" }
}

Test "-Source invalid rejected" {
    $ec8 = 0; $r8 = RunScript -scriptArgs "-Source invalid -DryRun" -exitCode ([ref]$ec8)
    if ($r8.Error -notmatch 'Source|invalid|parameter') {
        throw "-Source invalid should be rejected, got: $($r8.Error)"
    }
}

Test "-Silent flag accepted" {
    $ec9 = 0; $r9 = RunScript -scriptArgs "-Silent -DryRun" -exitCode ([ref]$ec9)
    if ($ec9 -ne 0) { throw "Exit code: $ec9, output: $($r9.Output)" }
}

Test "-Version flag accepted" {
    $ec10 = 0; $r10 = RunScript -scriptArgs "-Version v4.11.0 -DryRun" -exitCode ([ref]$ec10)
    if ($ec10 -ne 0) { throw "Exit code: $ec10, output: $($r10.Output)" }
}

Write-Host ""

# ── Functions via dot-source ──
Write-Host "--- Functions ---" -ForegroundColor Cyan

# Strip trailing Main call and dot-source for function-level tests
$scriptContent = Get-Content $installScript -Raw
$scriptContent = $scriptContent -replace '\r?\nMain\s*$', ''
$scriptContent = $scriptContent -replace '\$ErrorActionPreference\s*=\s*"Stop"', ''
$sb = [ScriptBlock]::Create($scriptContent)

& {
    # Suppress output
    $Silent = $true
    $DryRun = $false
    $SkipLocal = $false
    $Locate = $false
    $Source = ""
    $Version = ""
    $InstallDir = ""
    $AddToPath = $true

    . $sb

    Test "Get-PlatformKey returns valid format" {
        $key = Get-PlatformKey
        if ($key -notmatch '^(win32|linux|darwin)-(x64|arm64)$' -and
            $key -notmatch '^linux-musl-(x64|arm64)$') {
            throw "Unexpected platform key: $key"
        }
    }

    Test "Get-BinaryName includes .exe on win32" {
        $name = Get-BinaryName -PlatformKey "win32-x64"
        if ($name -ne "browser4-cli-win32-x64.exe") { throw "Got: $name" }
    }

    Test "Get-BinaryName excludes .exe on linux" {
        $name = Get-BinaryName -PlatformKey "linux-x64"
        if ($name -ne "browser4-cli-linux-x64") { throw "Got: $name" }
    }

    Test "Get-BinaryName excludes .exe on darwin" {
        $name = Get-BinaryName -PlatformKey "darwin-arm64"
        if ($name -ne "browser4-cli-darwin-arm64") { throw "Got: $name" }
    }

    Test "Get-DefaultInstallDir returns non-empty" {
        $dir = Get-DefaultInstallDir
        if ([string]::IsNullOrEmpty($dir)) { throw "Empty install dir" }
    }

    Test "Test-ChinaLocale returns [bool]" {
        $result = Test-ChinaLocale
        if ($result -isnot [bool]) { throw "Expected [bool], got $($result.GetType())" }
    }

    Test "Find-LocalBinary returns null for non-existent" {
        $result = Find-LocalBinary -BinaryName "nonexistent-file-xyz.exe"
        if ($result -ne $null) { throw "Expected null, got: $result" }
    }

    Test "Test-LocalBinary returns false for empty string" {
        if (Test-LocalBinary -Path "") { throw "Should be false" }
    }

    Test "Test-LocalBinary returns false for null" {
        if (Test-LocalBinary -Path $null) { throw "Should be false" }
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
