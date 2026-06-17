#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Unit tests for ConvertTo-WindowsCmdArg defined in test-utils.psm1.

.DESCRIPTION
    Validates the Windows command-line argument escaping function against the
    MSDN CommandLineToArgvW specification.  Covers empty strings, simple
    strings, embedded spaces, double quotes, backslash runs, trailing
    backslashes, and the exact multi-line task description pattern that
    caused the agent-run-page-visit test failure.

    Run standalone:
        pwsh bin/tests/test-utils-helpers.ps1

    Run via runner:
        pwsh bin/tests/run-tests.ps1 test-utils-helpers
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'test-utils-helpers'

Write-TestHeader -Name 'test-utils-helpers'

# -------------------------------------------------------------------
# Test helper: assert function returns expected value
# -------------------------------------------------------------------
function Assert-Returns {
    param(
        [string]$Label,
        $Actual,
        $Expected,
        [string]$Description = ''
    )
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $passed = ($Actual -eq $Expected) -or
              ($null -eq $Actual -and $null -eq $Expected)
    $sw.Stop()
    $exitCode = if ($passed) { 0 } else { 1 }
    $detail = if ($Description) { $Description } else { "expected=$Expected actual=$Actual" }
    Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed $sw.Elapsed `
        -OutputLines @($detail)

    if (-not $passed) {
        Write-Host "    ❌ $Label — expected '$Expected', got '$Actual'" -ForegroundColor Red
    } else {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    }
}

# ═══════════════════════════════════════════════════════════════════
# TESTS: ConvertTo-WindowsCmdArg — basic cases
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Basic: no escaping needed ━━━" -ForegroundColor Cyan

$result = ConvertTo-WindowsCmdArg 'hello'
Assert-Returns -Label 'simple word returned as-is' -Actual $result -Expected 'hello'

$result = ConvertTo-WindowsCmdArg 'hello123'
Assert-Returns -Label 'alphanumeric returned as-is' -Actual $result -Expected 'hello123'

$result = ConvertTo-WindowsCmdArg 'a-b_c.d'
Assert-Returns -Label 'special-but-not-whitespace chars returned as-is' -Actual $result -Expected 'a-b_c.d'

Write-Host "━━━ Basic: empty string ━━━" -ForegroundColor Cyan

$result = ConvertTo-WindowsCmdArg ''
Assert-Returns -Label 'empty string → quoted empty string' -Actual $result -Expected '""'

Write-Host "━━━ Basic: strings with spaces ━━━" -ForegroundColor Cyan

$result = ConvertTo-WindowsCmdArg 'hello world'
Assert-Returns -Label 'one space → quoted' -Actual $result -Expected '"hello world"'

$result = ConvertTo-WindowsCmdArg 'hello  world'
Assert-Returns -Label 'double space → quoted (preserved)' -Actual $result -Expected '"hello  world"'

$result = ConvertTo-WindowsCmdArg ' leading'
Assert-Returns -Label 'leading space → quoted' -Actual $result -Expected '" leading"'

$result = ConvertTo-WindowsCmdArg 'trailing '
Assert-Returns -Label 'trailing space → quoted' -Actual $result -Expected '"trailing "'

Write-Host "━━━ Basic: tab characters ━━━" -ForegroundColor Cyan

$result = ConvertTo-WindowsCmdArg "hello`tworld"
$expectedTab = "`"hello`tworld`""
Assert-Returns -Label 'tab → quoted' -Actual $result -Expected $expectedTab

# ═══════════════════════════════════════════════════════════════════
# TESTS: ConvertTo-WindowsCmdArg — double quotes
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Double quotes ━━━" -ForegroundColor Cyan

$result = ConvertTo-WindowsCmdArg 'say "hi"'
Assert-Returns -Label 'double quotes → escaped' -Actual $result -Expected '"say \"hi\""'

$result = ConvertTo-WindowsCmdArg '"hello"'
Assert-Returns -Label 'leading/trailing double quotes → escaped' -Actual $result -Expected '"\"hello\""'

$result = ConvertTo-WindowsCmdArg 'a"b'
Assert-Returns -Label 'mid-word double quote → escaped' -Actual $result -Expected '"a\"b"'

$result = ConvertTo-WindowsCmdArg '"""'
Assert-Returns -Label 'three consecutive double quotes' -Actual $result -Expected '"\"\"\""'

# ═══════════════════════════════════════════════════════════════════
# TESTS: ConvertTo-WindowsCmdArg — backslash handling
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Backslashes ━━━" -ForegroundColor Cyan

# Backslashes alone (no spaces, no quotes) → no quoting needed
$result = ConvertTo-WindowsCmdArg 'C:\path\to\file'
Assert-Returns -Label 'backslashes without spaces → no quoting' -Actual $result -Expected 'C:\path\to\file'

# Backslash before double quote
$result = ConvertTo-WindowsCmdArg 'a\"b'
# CommandLineToArgvW: a\"b → a (backslash escapes quote, quote starts quoted section, b is literal)
# Wait, that's for parsing. For CONSTRUCTION:
# We want the resolved arg to be: a\"b
# To get \" inside a quoted string, we need \\\"  (two for backslash, one for quote escape)
# Actually: 'a\"b' in PowerShell is a literal: a\"b
# Input: a \ " b (4 chars)
# Output should be: "a\\\"b"
#   - 'a' → 'a'
#   - '\' → backslash before quote, so double it: '\\'
#   - '"' → '\"'
#   - 'b' → 'b'
Assert-Returns -Label 'backslash before double quote (rule: 2N+1)' -Actual $result -Expected '"a\\\"b"'

# Trailing backslashes without spaces or quotes → no quoting needed.
# CommandLineToArgvW treats lone backslashes as literal outside quotes.
$result = ConvertTo-WindowsCmdArg 'trail\'
Assert-Returns -Label 'trailing backslash (no spaces) → as-is' -Actual $result -Expected 'trail\'

$result = ConvertTo-WindowsCmdArg 'trail\\'
Assert-Returns -Label 'two trailing backslashes (no spaces) → as-is' -Actual $result -Expected 'trail\\'

# Trailing backslash WITH a space → quoting is required, but no doubling.
# The backslash is followed by a SPACE, not the closing quote, so it is
# a literal backslash — no doubling needed.
$result = ConvertTo-WindowsCmdArg 'trail\ '
Assert-Returns -Label 'backslash then space → quoted (no doubling)' -Actual $result -Expected '"trail\ "'

# Backslash-only argument
$result = ConvertTo-WindowsCmdArg '\'
Assert-Returns -Label 'single backslash (no spaces) → as-is' -Actual $result -Expected '\'

# Backslash-only with space → needs quoting, trailing backslash doubled
$result = ConvertTo-WindowsCmdArg '\ \'
# '\ \' has a trailing backslash before closing quote → must be doubled.
# Correct: "\ \\"  (inside: backslash, space, double-backslash → literal \ + "")
Assert-Returns -Label 'backslashes separated by space → quoted' -Actual $result -Expected '"\ \\"'

# ═══════════════════════════════════════════════════════════════════
# TESTS: ConvertTo-WindowsCmdArg — real-world patterns
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Real-world patterns ━━━" -ForegroundColor Cyan

# Exact pattern from agent-run-page-visit: multi-line task description
$taskDescription = @"
Visit https://www.baidu.com/
Summarize the product.
"@
$result = ConvertTo-WindowsCmdArg $taskDescription
# The output must contain double quotes so CommandLineToArgvW treats it
# as one argument even with embedded spaces and newlines.
Assert-Returns -Label 'multi-line task description starts with quote' -Actual $result.StartsWith('"') -Expected $true
Assert-Returns -Label 'multi-line task description ends with quote' -Actual $result.EndsWith('"') -Expected $true
Assert-Returns -Label 'multi-line task description contains Visit' -Actual ($result -match 'Visit') -Expected $true
Assert-Returns -Label 'multi-line task description contains baidu' -Actual ($result -match 'baidu') -Expected $true
Assert-Returns -Label 'multi-line task description contains Summarize' -Actual ($result -match 'Summarize') -Expected $true
Assert-Returns -Label 'multi-line task description has newlines preserved' -Actual ($result -match "`n") -Expected $true

# URL with query parameters (common CLI argument)
$result = ConvertTo-WindowsCmdArg 'https://example.com/path?a=1&b=2'
Assert-Returns -Label 'URL without spaces → as-is' -Actual $result -Expected 'https://example.com/path?a=1&b=2'

# Command with flag-like argument containing spaces
$result = ConvertTo-WindowsCmdArg '--message=hello world'
Assert-Returns -Label 'flag=value with space → quoted' -Actual $result -Expected '"--message=hello world"'

# JSON string (common CLI argument)
$result = ConvertTo-WindowsCmdArg '{"key": "value"}'
Assert-Returns -Label 'JSON string → quoted' -Actual $result -Expected '"{\"key\": \"value\"}"'
# Wait — the input is: {"key": "value"}
# In this string: { " k e y " :   " v a l u e " }
# Let me verify: the input has double quotes already. They need escaping.
# { → {
# " → \"
# k → k
# ...
# So output: "{\"key\": \"value\"}"
# Actually: { is not special, " needs \", space means quoting needed
# Correct: "{\"key\": \"value\"}"

# ═══════════════════════════════════════════════════════════════════
# TESTS: ConvertTo-WindowsCmdArg — edge cases
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Edge cases ━━━" -ForegroundColor Cyan

# Only spaces
$result = ConvertTo-WindowsCmdArg '   '
Assert-Returns -Label 'only spaces → quoted' -Actual $result -Expected '"   "'

# Single double quote — needs quoting (it IS a double quote), and the
# quote itself must be escaped: "\""  (open, backslash-escaped quote, close)
$result = ConvertTo-WindowsCmdArg '"'
Assert-Returns -Label 'single double quote → escaped and quoted' -Actual $result -Expected '"\""'

# Mixed spaces and backslashes
$result = ConvertTo-WindowsCmdArg 'a\ b'
# Input: a \ ' ' b
# Backslash not before quote or at end, space triggers quoting
# Output: "a\ b"
Assert-Returns -Label 'backslash-space → quoted with preserved backslash' -Actual $result -Expected '"a\ b"'

# ═══════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════
Write-Host ''
$exitCode = Finish-TestSession
exit $exitCode
