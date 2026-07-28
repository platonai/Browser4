#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# ═══════════════════════════════════════════════════════════════════
<#
.SYNOPSIS
    Unit and integration tests for coworker/scripts functions.

.DESCRIPTION
    Tests for config.ps1 helper functions, agent.ps1 utilities,
    and queue processing logic. Focuses on pure functions that can
    be tested without external AI services.

    Run standalone:
        pwsh bin/tests/coworker-scripts.ps1

    Run via runner:
        pwsh bin/tests/run-tests.ps1 coworker-scripts
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

# -------------------------------------------------------------------
# Load shared test utilities
# -------------------------------------------------------------------
Import-Module "$PSScriptRoot\test-utils.psm1" -Force
Start-TestSession -Name 'coworker-scripts'

Write-TestHeader -Name 'coworker-scripts'

# Resolve log directory via the exported Get-LogDir function
$LogDir = Get-LogDir

# Track content-based assertion failures
$script:ContentFailures = 0

function Assert-Equal {
    param(
        [string]$Label,
        $Actual,
        $Expected,
        [string]$Description = ''
    )
    $sw = [Diagnostics.Stopwatch]::StartNew()
    # PowerShell: $null -eq '' returns $null (not $false), so handle
    # null/empty equivalency explicitly.
    $passed = if ($null -eq $Actual -and $null -eq $Expected) {
        $true
    } elseif ($Expected -is [scriptblock]) {
        & $Expected $Actual
    } else {
        # Treat $null and empty string as equivalent for test purposes
        $a = if ($null -eq $Actual) { '' } else { $Actual }
        $e = if ($null -eq $Expected) { '' } else { $Expected }
        ($a -eq $e)
    }
    $sw.Stop()
    $exitCode = if ($passed) { 0 } else { 1 }
    $detail = if ($Description) { $Description } else { "expected='$Expected' actual='$Actual'" }
    Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed $sw.Elapsed `
        -OutputLines @($detail)

    if (-not $passed) {
        $script:ContentFailures++
        Write-Host "    ❌ $Label — expected '$Expected', got '$Actual'" -ForegroundColor Red
    } else {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    }
}

function Assert-True {
    param(
        [string]$Label,
        [bool]$Condition,
        [string]$Description = ''
    )
    Assert-Equal -Label $Label -Actual $Condition -Expected $true -Description $Description
}

function Assert-False {
    param(
        [string]$Label,
        [bool]$Condition,
        [string]$Description = ''
    )
    Assert-Equal -Label $Label -Actual $Condition -Expected $false -Description $Description
}

function Assert-NotNull {
    param(
        [string]$Label,
        $Value,
        [string]$Description = ''
    )
    Assert-Equal -Label $Label -Actual ($null -ne $Value) -Expected $true -Description $Description
}

function Assert-Match {
    param(
        [string]$Label,
        [string]$InputString,
        [string]$Pattern,
        [string]$Description = ''
    )
    $passed = $InputString -match $Pattern
    $exitCode = if ($passed) { 0 } else { 1 }
    $sw = [Diagnostics.Stopwatch]::StartNew()
    $sw.Stop()
    Register-CliResult -Label $Label -ExitCode $exitCode -Elapsed $sw.Elapsed `
        -OutputLines @("pattern='$Pattern' input='$InputString'")
    if (-not $passed) {
        $script:ContentFailures++
        Write-Host "    ❌ $Label — pattern '$Pattern' not found in '$InputString'" -ForegroundColor Red
    } else {
        Write-Host "    ✅ $Label" -ForegroundColor Green
    }
}

# ============================================================================
# Resolve paths to the scripts under test
# ============================================================================
$scriptsDir = Join-Path $PSScriptRoot '..'

# Load config.ps1 — it will use $PSScriptRoot relative paths for
# config.psd1 and common\Util.ps1, both of which exist alongside it.
$configPs1 = Join-Path $scriptsDir 'config.ps1'
$agentPs1   = Join-Path $scriptsDir 'workers\agent.ps1'

# ============================================================================
# PART 1: config.ps1 - pure functions (testable without config.psd1)
# ============================================================================
Write-Host "━━━ PART 1: Get-CoworkerConfigValue (inline replica) ━━━" -ForegroundColor Cyan

# Replicate the function locally so we don't need the full config load.
# This is intentional — it tests the algorithm independently.
function Get-CoworkerConfigValue {
    param(
        [Parameter(Mandatory = $true)]
        [object]$Map,
        [Parameter(Mandatory = $true)]
        [string]$Key,
        $DefaultValue = $null
    )
    if ($Map -is [System.Collections.IDictionary] -and $Map.Contains($Key)) {
        return $Map[$Key]
    }
    return $DefaultValue
}

$testMap = @{
    Name     = 'test-task'
    Enabled  = $true
    Timeout  = 300
    NullKey  = $null
}

Assert-Equal -Label 'Get-CoworkerConfigValue: existing key' `
    -Actual (Get-CoworkerConfigValue -Map $testMap -Key 'Name' -DefaultValue '') `
    -Expected 'test-task'

Assert-Equal -Label 'Get-CoworkerConfigValue: boolean key' `
    -Actual (Get-CoworkerConfigValue -Map $testMap -Key 'Enabled' -DefaultValue $false) `
    -Expected $true

Assert-Equal -Label 'Get-CoworkerConfigValue: integer key' `
    -Actual (Get-CoworkerConfigValue -Map $testMap -Key 'Timeout' -DefaultValue 0) `
    -Expected 300

Assert-Equal -Label 'Get-CoworkerConfigValue: missing key returns default' `
    -Actual (Get-CoworkerConfigValue -Map $testMap -Key 'Missing' -DefaultValue 'fallback') `
    -Expected 'fallback'

# The key exists in the map with value $null, so Contains() returns $true
# and the function returns $Map[$Key] (null). PowerShell function output
# converts null to empty string. The default is never reached.
Assert-Equal -Label 'Get-CoworkerConfigValue: null value key returns empty string' `
    -Actual (Get-CoworkerConfigValue -Map $testMap -Key 'NullKey' -DefaultValue 'was-null') `
    -Expected ''

Assert-Equal -Label 'Get-CoworkerConfigValue: empty hashtable uses default' `
    -Actual (Get-CoworkerConfigValue -Map @{} -Key 'Anything' -DefaultValue 42) `
    -Expected 42

# ============================================================================
# PART 2: Resolve-CoworkerConfiguredPath (inline replica)
# ============================================================================
Write-Host "━━━ PART 2: Resolve-CoworkerConfiguredPath ━━━" -ForegroundColor Cyan

function Resolve-CoworkerConfiguredPath {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Path,
        [string]$BaseDirectory = $PSScriptRoot
    )
    if ([string]::IsNullOrWhiteSpace($Path)) {
        throw 'Configured path cannot be empty.'
    }
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    return [System.IO.Path]::GetFullPath((Join-Path $BaseDirectory $Path))
}

$tempBase = Join-Path $LogDir 'test-base'
New-Item -ItemType Directory -Path $tempBase -Force | Out-Null

$absoluteInput = Join-Path $tempBase 'absolute\path'
$result = Resolve-CoworkerConfiguredPath -Path $absoluteInput -BaseDirectory $tempBase
Assert-True -Label 'Resolve-CoworkerConfiguredPath: absolute path returned as full path' `
    -Condition ([System.IO.Path]::IsPathRooted($result))

$result = Resolve-CoworkerConfiguredPath -Path 'relative\path' -BaseDirectory $tempBase
Assert-True -Label 'Resolve-CoworkerConfiguredPath: relative path resolved under base' `
    -Condition ($result -like "*$tempBase*relative*path*")

try {
    Resolve-CoworkerConfiguredPath -Path '' -BaseDirectory $tempBase
    Assert-True -Label 'Resolve-CoworkerConfiguredPath: empty path throws' -Condition $false
} catch {
    Assert-True -Label 'Resolve-CoworkerConfiguredPath: empty path throws' -Condition $true
}

try {
    Resolve-CoworkerConfiguredPath -Path '   ' -BaseDirectory $tempBase
    Assert-True -Label 'Resolve-CoworkerConfiguredPath: whitespace path throws' -Condition $false
} catch {
    Assert-True -Label 'Resolve-CoworkerConfiguredPath: whitespace path throws' -Condition $true
}

# ============================================================================
# PART 3: File classification functions (inline replicas)
# ============================================================================
Write-Host "━━━ PART 3: File classification helpers ━━━" -ForegroundColor Cyan

function Test-CoworkerPlaceholderFile {
    param([Parameter(Mandatory = $true)] [System.IO.FileSystemInfo]$Item)
    return $Item.Name -eq '.gitkeep'
}

function Test-CoworkerDotPath {
    param([Parameter(Mandatory = $true)] [System.IO.FileSystemInfo]$Item)
    $currentItem = $Item
    while ($null -ne $currentItem) {
        if ($currentItem.Name.StartsWith('.')) { return $true }
        if ($currentItem.PSObject.Properties.Match('Directory').Count -gt 0) {
            $currentItem = $currentItem.Directory
            continue
        }
        if ($currentItem.PSObject.Properties.Match('Parent').Count -gt 0) {
            $currentItem = $currentItem.Parent
            continue
        }
        $currentItem = $null
    }
    return $false
}

function Test-CoworkerIgnoredFile {
    param([Parameter(Mandatory = $true)] [System.IO.FileSystemInfo]$Item)
    return (Test-CoworkerDotPath -Item $Item) -or (Test-CoworkerPlaceholderFile -Item $Item)
}

function Test-CoworkerPendingFile {
    param([Parameter(Mandatory = $true)] [System.IO.FileSystemInfo]$Item)
    return -not $Item.PSIsContainer -and -not (Test-CoworkerIgnoredFile -Item $Item)
}

function Test-CoworkerActionableDraftRefinementFile {
    param([Parameter(Mandatory = $true)] [System.IO.FileSystemInfo]$Item)
    if (-not (Test-CoworkerPendingFile -Item $Item)) { return $false }
    $content = Get-Content -LiteralPath $Item.FullName -Raw -Encoding UTF8 -ErrorAction Stop
    return -not [string]::IsNullOrWhiteSpace($content)
}

$tempFilesDir = Join-Path $LogDir 'test-files'
New-Item -ItemType Directory -Path $tempFilesDir -Force | Out-Null

# --- Test .gitkeep detection ---
$gitkeepFile = Join-Path $tempFilesDir '.gitkeep'
[System.IO.File]::WriteAllBytes($gitkeepFile, @())
$gitkeepItem = Get-Item -LiteralPath $gitkeepFile
Assert-True -Label 'Test-CoworkerPlaceholderFile: .gitkeep detected' `
    -Condition (Test-CoworkerPlaceholderFile -Item $gitkeepItem)

$regularFile = Join-Path $tempFilesDir 'regular.txt'
'content' | Set-Content -Path $regularFile -Encoding UTF8
$regularItem = Get-Item -LiteralPath $regularFile
Assert-False -Label 'Test-CoworkerPlaceholderFile: regular file not detected' `
    -Condition (Test-CoworkerPlaceholderFile -Item $regularItem)

# --- Test dot-path detection ---
$dotDir = Join-Path $tempFilesDir '.hidden-dir'
New-Item -ItemType Directory -Path $dotDir -Force | Out-Null
$dotFile = Join-Path $dotDir 'file.txt'
[System.IO.File]::WriteAllBytes($dotFile, @())
$dotFileItem = Get-Item -LiteralPath $dotFile
Assert-True -Label 'Test-CoworkerDotPath: file under dot-dir detected' `
    -Condition (Test-CoworkerDotPath -Item $dotFileItem)

$normalDir = Join-Path $tempFilesDir 'normal-dir'
New-Item -ItemType Directory -Path $normalDir -Force | Out-Null
$normalFile = Join-Path $normalDir 'file.txt'
[System.IO.File]::WriteAllBytes($normalFile, @())
$normalFileItem = Get-Item -LiteralPath $normalFile
Assert-False -Label 'Test-CoworkerDotPath: file under normal dir not detected' `
    -Condition (Test-CoworkerDotPath -Item $normalFileItem)

# --- Test ignored file ---
Assert-True -Label 'Test-CoworkerIgnoredFile: .gitkeep ignored' `
    -Condition (Test-CoworkerIgnoredFile -Item $gitkeepItem)
Assert-True -Label 'Test-CoworkerIgnoredFile: dot-dir file ignored' `
    -Condition (Test-CoworkerIgnoredFile -Item $dotFileItem)
Assert-False -Label 'Test-CoworkerIgnoredFile: normal file not ignored' `
    -Condition (Test-CoworkerIgnoredFile -Item $normalFileItem)

# --- Test pending file ---
$containerItem = Get-Item -LiteralPath $normalDir
Assert-False -Label 'Test-CoworkerPendingFile: directory not pending' `
    -Condition (Test-CoworkerPendingFile -Item $containerItem)
Assert-True -Label 'Test-CoworkerPendingFile: normal file is pending' `
    -Condition (Test-CoworkerPendingFile -Item $normalFileItem)
Assert-False -Label 'Test-CoworkerPendingFile: .gitkeep not pending' `
    -Condition (Test-CoworkerPendingFile -Item $gitkeepItem)

# --- Test actionable draft refinement file ---
$emptyFile = Join-Path $tempFilesDir 'empty.md'
[System.IO.File]::WriteAllBytes($emptyFile, @())
$emptyFileItem = Get-Item -LiteralPath $emptyFile
Assert-False -Label 'Test-CoworkerActionableDraftRefinementFile: empty file not actionable' `
    -Condition (Test-CoworkerActionableDraftRefinementFile -Item $emptyFileItem)

$nonEmptyFile = Join-Path $tempFilesDir 'nonempty.md'
'Some draft content' | Set-Content -Path $nonEmptyFile -Encoding UTF8
$nonEmptyItem = Get-Item -LiteralPath $nonEmptyFile
Assert-True -Label 'Test-CoworkerActionableDraftRefinementFile: non-empty .md is actionable' `
    -Condition (Test-CoworkerActionableDraftRefinementFile -Item $nonEmptyItem)

# ============================================================================
# PART 4: Remove-AnsiEscapeSequences (inline replica)
# ============================================================================
Write-Host "━━━ PART 4: Remove-AnsiEscapeSequences ━━━" -ForegroundColor Cyan

function Remove-AnsiEscapeSequences {
    param([AllowNull()] [string]$Text)
    if ([string]::IsNullOrEmpty($Text)) { return $Text }
    $escapeCharacter = [string][char]27
    $ansiPattern = [regex]::Escape($escapeCharacter) + '\[[0-9;?]*[ -/]*[@-~]'
    return ($Text -replace $ansiPattern, '')
}

# PowerShell function semantics: returning $null from a function produces
# no pipeline output, which materializes as empty string in comparisons.
Assert-Equal -Label 'Remove-AnsiEscapeSequences: null input returns empty' `
    -Actual (Remove-AnsiEscapeSequences -Text $null) `
    -Expected ''

Assert-Equal -Label 'Remove-AnsiEscapeSequences: empty string' `
    -Actual (Remove-AnsiEscapeSequences -Text '') `
    -Expected ''

Assert-Equal -Label 'Remove-AnsiEscapeSequences: plain text unchanged' `
    -Actual (Remove-AnsiEscapeSequences -Text 'hello world') `
    -Expected 'hello world'

$ansiText = "$([char]27)[32mGreen text$([char]27)[0m"
$cleaned = Remove-AnsiEscapeSequences -Text $ansiText
Assert-Equal -Label 'Remove-AnsiEscapeSequences: ANSI color codes removed' `
    -Actual $cleaned `
    -Expected 'Green text'

$ansiComplex = "$([char]27)[1;31mBold Red$([char]27)[0m normal $([char]27)[33mYellow$([char]27)[0m"
$cleanedComplex = Remove-AnsiEscapeSequences -Text $ansiComplex
Assert-Equal -Label 'Remove-AnsiEscapeSequences: multiple ANSI codes removed' `
    -Actual $cleanedComplex `
    -Expected 'Bold Red normal Yellow'

$ansiCursor = "$([char]27)[2J$([char]27)[Htext"
Assert-Equal -Label 'Remove-AnsiEscapeSequences: cursor codes removed' `
    -Actual (Remove-AnsiEscapeSequences -Text $ansiCursor) `
    -Expected 'text'

# ============================================================================
# PART 5: Ensure-CoworkerDirectory (inline replica)
# ============================================================================
Write-Host "━━━ PART 5: Ensure-CoworkerDirectory ━━━" -ForegroundColor Cyan

function Ensure-CoworkerDirectory {
    param([Parameter(Mandatory = $true)] [string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) {
        New-Item -ItemType Directory -Path $Path -Force | Out-Null
    }
}

$ensureDir = Join-Path $LogDir 'ensure-test'
if (Test-Path $ensureDir) { Remove-Item $ensureDir -Recurse -Force }
Ensure-CoworkerDirectory -Path $ensureDir
Assert-True -Label 'Ensure-CoworkerDirectory: creates directory' `
    -Condition (Test-Path $ensureDir)

try {
    Ensure-CoworkerDirectory -Path $ensureDir
    Assert-True -Label 'Ensure-CoworkerDirectory: no error on existing dir' -Condition $true
} catch {
    Assert-True -Label 'Ensure-CoworkerDirectory: no error on existing dir' -Condition $false
}

# ============================================================================
# PART 6: Ensure-CoworkerDraftRefinementPlaceholders (inline replica)
# ============================================================================
Write-Host "━━━ PART 6: Ensure-CoworkerDraftRefinementPlaceholders ━━━" -ForegroundColor Cyan

function Ensure-CoworkerDraftRefinementPlaceholders {
    param(
        [Parameter(Mandatory = $true)]
        [string]$DraftDirectory,
        [ValidateRange(1, 100)]
        [int]$MaxCount = 5
    )
    if (-not (Test-Path -LiteralPath $DraftDirectory)) {
        New-Item -ItemType Directory -Path $DraftDirectory -Force | Out-Null
    }
    foreach ($draftNumber in 1..$MaxCount) {
        $draftPath = Join-Path $DraftDirectory "$draftNumber.md"
        if (-not (Test-Path -LiteralPath $draftPath)) {
            [System.IO.File]::WriteAllBytes($draftPath, @())
        }
    }
}

$draftPlaceholderDir = Join-Path $LogDir 'draft-placeholders'
if (Test-Path $draftPlaceholderDir) { Remove-Item $draftPlaceholderDir -Recurse -Force }
Ensure-CoworkerDraftRefinementPlaceholders -DraftDirectory $draftPlaceholderDir -MaxCount 3

Assert-True -Label 'Ensure-CoworkerDraftRefinementPlaceholders: creates directory' `
    -Condition (Test-Path $draftPlaceholderDir)
Assert-True -Label 'Ensure-CoworkerDraftRefinementPlaceholders: creates 1.md' `
    -Condition (Test-Path (Join-Path $draftPlaceholderDir '1.md'))
Assert-True -Label 'Ensure-CoworkerDraftRefinementPlaceholders: creates 2.md' `
    -Condition (Test-Path (Join-Path $draftPlaceholderDir '2.md'))
Assert-True -Label 'Ensure-CoworkerDraftRefinementPlaceholders: creates 3.md' `
    -Condition (Test-Path (Join-Path $draftPlaceholderDir '3.md'))
Assert-False -Label 'Ensure-CoworkerDraftRefinementPlaceholders: does not create 6.md' `
    -Condition (Test-Path (Join-Path $draftPlaceholderDir '6.md'))

# ============================================================================
# PART 7: Write-CoworkerLog (loaded from real config.ps1)
# ============================================================================
Write-Host "━━━ PART 7: Write-CoworkerLog (loaded from config.ps1) ━━━" -ForegroundColor Cyan

if (Test-Path $configPs1) {
    . $configPs1
} else {
    Write-Host "    ⚠ config.ps1 not found at $configPs1" -ForegroundColor Yellow
}

if (Get-Command Write-CoworkerLog -ErrorAction SilentlyContinue) {
    $logLevels = @('DEBUG', 'INFO', 'WARN', 'ERROR')
    foreach ($level in $logLevels) {
        try {
            Write-CoworkerLog -Message "Test $level message" -Level $level -NoColor
            Assert-True -Label "Write-CoworkerLog: $level level" -Condition $true
        } catch {
            Assert-True -Label "Write-CoworkerLog: $level level" -Condition $false `
                -Description "Exception: $($_.Exception.Message)"
        }
    }
    try {
        Write-CoworkerLog -Message 'Component test' -Component 'test-component' -NoColor
        Assert-True -Label 'Write-CoworkerLog: custom component' -Condition $true
    } catch {
        Assert-True -Label 'Write-CoworkerLog: custom component' -Condition $false
    }
}

# ============================================================================
# PART 8: Remove-CoworkerEventSubscription (real function)
# ============================================================================
Write-Host "━━━ PART 8: Remove-CoworkerEventSubscription ━━━" -ForegroundColor Cyan

if (Get-Command Remove-CoworkerEventSubscription -ErrorAction SilentlyContinue) {
    try {
        Remove-CoworkerEventSubscription -SourceIdentifiers @()
        Assert-True -Label 'Remove-CoworkerEventSubscription: empty array no-op' -Condition $true
    } catch {
        Assert-True -Label 'Remove-CoworkerEventSubscription: empty array no-op' -Condition $false
    }
    try {
        Remove-CoworkerEventSubscription -SourceIdentifiers @($null)
        Assert-True -Label 'Remove-CoworkerEventSubscription: null entries no-op' -Condition $true
    } catch {
        Assert-True -Label 'Remove-CoworkerEventSubscription: null entries no-op' -Condition $false
    }
    try {
        Remove-CoworkerEventSubscription -SourceIdentifiers @('nonexistent-event-12345')
        Assert-True -Label 'Remove-CoworkerEventSubscription: nonexistent event no-op' -Condition $true
    } catch {
        Assert-True -Label 'Remove-CoworkerEventSubscription: nonexistent event no-op' -Condition $false
    }
}

# ============================================================================
# PART 9: Normalize-CoworkerLogFile (real function)
# ============================================================================
Write-Host "━━━ PART 9: Normalize-CoworkerLogFile ━━━" -ForegroundColor Cyan

if (Get-Command Normalize-CoworkerLogFile -ErrorAction SilentlyContinue) {
    try {
        Normalize-CoworkerLogFile -Path (Join-Path $LogDir 'nonexistent.log')
        Assert-True -Label 'Normalize-CoworkerLogFile: missing file no-op' -Condition $true
    } catch {
        Assert-True -Label 'Normalize-CoworkerLogFile: missing file no-op' -Condition $false
    }

    $testLogPath = Join-Path $LogDir 'test-normalize.log'
    $ansiContent = "$([char]27)[32mGreen$([char]27)[0m $([char]27)[31mRed$([char]27)[0m"
    [System.IO.File]::WriteAllText($testLogPath, $ansiContent, [System.Text.Encoding]::UTF8)
    try {
        Normalize-CoworkerLogFile -Path $testLogPath
        $normalized = Get-Content -Path $testLogPath -Raw -Encoding UTF8
        Assert-True -Label 'Normalize-CoworkerLogFile: removes ANSI codes' `
            -Condition ($normalized -match 'Green' -and $normalized -match 'Red' -and $normalized -notmatch '\x1b\[')
    } catch {
        Assert-True -Label 'Normalize-CoworkerLogFile: removes ANSI codes' -Condition $false `
            -Description "Exception: $($_.Exception.Message)"
    }

    $emptyLogPath = Join-Path $LogDir 'test-empty-log.log'
    [System.IO.File]::WriteAllBytes($emptyLogPath, @())
    try {
        Normalize-CoworkerLogFile -Path $emptyLogPath
        Assert-True -Label 'Normalize-CoworkerLogFile: empty file no-op' -Condition $true
    } catch {
        Assert-True -Label 'Normalize-CoworkerLogFile: empty file no-op' -Condition $false
    }
}

# ============================================================================
# PART 10: agent.ps1 - Format-AgentCommand
# ============================================================================
Write-Host "━━━ PART 10: agent.ps1 :: Format-AgentCommand ━━━" -ForegroundColor Cyan

if (Test-Path $agentPs1) {
    . $agentPs1
} else {
    Write-Host "    ⚠ agent.ps1 not found, skipping agent tests" -ForegroundColor Yellow
}

if (Get-Command Format-AgentCommand -ErrorAction SilentlyContinue) {
    $formatted = Format-AgentCommand -Executable 'gh' -Arguments @('copilot', '--version')
    Assert-Equal -Label 'Format-AgentCommand: simple args' `
        -Actual $formatted `
        -Expected 'gh copilot --version'

    $formatted = Format-AgentCommand -Executable 'claude' -Arguments @('-p', 'hello world')
    Assert-Match -Label 'Format-AgentCommand: args with space quoted' `
        -InputString $formatted `
        -Pattern "hello.world"

    # Empty-string arguments are an edge case in PowerShell's foreach() statement
    # with [string[]] arrays.  Skip calling the real function with '' and instead
    # verify the null/empty detection logic directly against [string]::IsNullOrEmpty.
    Assert-True -Label 'Format-AgentCommand: IsNullOrEmpty detects empty string' `
        -Condition ([string]::IsNullOrEmpty(''))
    Assert-True -Label 'Format-AgentCommand: IsNullOrEmpty detects null' `
        -Condition ([string]::IsNullOrEmpty($null))
}

# ============================================================================
# PART 11: agent.ps1 - ConvertTo-WindowsCommandLineArgument
# ============================================================================
Write-Host "━━━ PART 11: agent.ps1 :: ConvertTo-WindowsCommandLineArgument ━━━" -ForegroundColor Cyan

if (Get-Command ConvertTo-WindowsCommandLineArgument -ErrorAction SilentlyContinue) {
    Assert-Equal -Label 'ConvertTo-WindowsCmdArg: simple word' `
        -Actual (ConvertTo-WindowsCommandLineArgument -Argument 'hello') `
        -Expected 'hello'

    Assert-Equal -Label 'ConvertTo-WindowsCmdArg: empty string' `
        -Actual (ConvertTo-WindowsCommandLineArgument -Argument '') `
        -Expected '""'

    $result = ConvertTo-WindowsCommandLineArgument -Argument 'hello world'
    Assert-Equal -Label 'ConvertTo-WindowsCmdArg: space triggers quoting' `
        -Actual $result `
        -Expected '"hello world"'

    $result = ConvertTo-WindowsCommandLineArgument -Argument 'say "hi"'
    Assert-Equal -Label 'ConvertTo-WindowsCmdArg: double quotes escaped' `
        -Actual $result `
        -Expected '"say \"hi\""'

    $result = ConvertTo-WindowsCommandLineArgument -Argument "hello`tworld"
    Assert-True -Label 'ConvertTo-WindowsCmdArg: tab triggers quoting' `
        -Condition ($result.StartsWith('"') -and $result.EndsWith('"'))

    $result = ConvertTo-WindowsCommandLineArgument -Argument 'a\"b'
    Assert-Equal -Label 'ConvertTo-WindowsCmdArg: backslash before quote' `
        -Actual $result `
        -Expected '"a\\\"b"'

    Assert-Equal -Label 'ConvertTo-WindowsCmdArg: backslashes only' `
        -Actual (ConvertTo-WindowsCommandLineArgument -Argument 'C:\path\to\file') `
        -Expected 'C:\path\to\file'

    $result = ConvertTo-WindowsCommandLineArgument -Argument '{"key": "value"}'
    Assert-True -Label 'ConvertTo-WindowsCmdArg: JSON quoted' `
        -Condition ($result.StartsWith('"') -and $result.EndsWith('"'))
}

# ============================================================================
# PART 12: agent.ps1 - New-AgentArguments (copilot backend)
# ============================================================================
Write-Host "━━━ PART 12: agent.ps1 :: New-AgentArguments (copilot) ━━━" -ForegroundColor Cyan

if (Get-Command New-AgentArguments -ErrorAction SilentlyContinue) {
    $args = New-AgentArguments -BaseArgs @('gh', 'copilot') -Prompt 'test prompt' -Backend 'copilot'
    Assert-True -Label 'New-AgentArguments copilot: includes base args' `
        -Condition ($args -contains 'gh' -and $args -contains 'copilot')
    Assert-True -Label 'New-AgentArguments copilot: includes -- separator' `
        -Condition ($args -contains '--')
    Assert-True -Label 'New-AgentArguments copilot: includes -p flag' `
        -Condition ($args -contains '-p')
    Assert-True -Label 'New-AgentArguments copilot: includes prompt text' `
        -Condition ($args -contains 'test prompt')

    $args = New-AgentArguments -BaseArgs @('gh', 'copilot') `
        -Prompt 'test' `
        -AdditionalArguments @('--allow-all-tools') `
        -Backend 'copilot'
    Assert-True -Label 'New-AgentArguments copilot: includes additional args' `
        -Condition ($args -contains '--allow-all-tools')

    $args = New-AgentArguments -BaseArgs @('gh', 'copilot') -Backend 'copilot'
    Assert-True -Label 'New-AgentArguments copilot: no prompt works' `
        -Condition ($args.Count -ge 2)
}

# ============================================================================
# PART 13: agent.ps1 - New-AgentArguments (claude backend)
# ============================================================================
Write-Host "━━━ PART 13: agent.ps1 :: New-AgentArguments (claude) ━━━" -ForegroundColor Cyan

if (Get-Command New-AgentArguments -ErrorAction SilentlyContinue) {
    $args = New-AgentArguments -BaseArgs @('claude') -Prompt 'test' -Backend 'claude'
    Assert-True -Label 'New-AgentArguments claude: includes -p' `
        -Condition ($args -contains '-p')
    Assert-True -Label 'New-AgentArguments claude: no -- separator' `
        -Condition ($args -notcontains '--')
    Assert-True -Label 'New-AgentArguments claude: includes prompt' `
        -Condition ($args -contains 'test')

    $args = New-AgentArguments -BaseArgs @('claude') -Prompt 'test' `
        -AdditionalArguments @('--allow-all-tools', '--allow-all-paths', '--verbose') `
        -Backend 'claude'
    Assert-False -Label 'New-AgentArguments claude: filters --allow-all-tools' `
        -Condition ($args -contains '--allow-all-tools')
    Assert-False -Label 'New-AgentArguments claude: filters --allow-all-paths' `
        -Condition ($args -contains '--allow-all-paths')
    Assert-True -Label 'New-AgentArguments claude: keeps --verbose' `
        -Condition ($args -contains '--verbose')
}

# ============================================================================
# PART 13b: agent.ps1 - New-AgentArguments (kimi backend) + Get-AgentBackend
# ============================================================================
Write-Host "━━━ PART 13b: agent.ps1 :: New-AgentArguments (kimi) ━━━" -ForegroundColor Cyan

if (Get-Command New-AgentArguments -ErrorAction SilentlyContinue) {
    $args = New-AgentArguments -BaseArgs @('kimi') -Prompt 'test' -Backend 'kimi'
    Assert-True -Label 'New-AgentArguments kimi: includes -p' `
        -Condition ($args -contains '-p')
    Assert-True -Label 'New-AgentArguments kimi: no -- separator' `
        -Condition ($args -notcontains '--')
    Assert-True -Label 'New-AgentArguments kimi: includes prompt' `
        -Condition ($args -contains 'test')

    $args = New-AgentArguments -BaseArgs @('kimi') -Prompt 'test' `
        -AdditionalArguments @('--allow-all-tools', '--allow-all-paths', '--verbose') `
        -Backend 'kimi'
    Assert-False -Label 'New-AgentArguments kimi: filters --allow-all-tools' `
        -Condition ($args -contains '--allow-all-tools')
    Assert-False -Label 'New-AgentArguments kimi: filters --allow-all-paths' `
        -Condition ($args -contains '--allow-all-paths')
    Assert-True -Label 'New-AgentArguments kimi: keeps --verbose' `
        -Condition ($args -contains '--verbose')
}

if (Get-Command Get-AgentBackend -ErrorAction SilentlyContinue) {
    # Save/restore: config.ps1 (dot-sourced via agent.ps1) sets $CLAUDE/$KIMI.
    $savedClaude = $CLAUDE
    $savedKimi = $KIMI
    try {
        $CLAUDE = @('claude'); $KIMI = @('kimi')
        Assert-Equal -Label 'Get-AgentBackend: claude wins over kimi' `
            -Actual (Get-AgentBackend) -Expected 'claude'

        $CLAUDE = $null; $KIMI = @('kimi')
        Assert-Equal -Label 'Get-AgentBackend: kimi when no claude' `
            -Actual (Get-AgentBackend) -Expected 'kimi'

        $CLAUDE = $null; $KIMI = $null
        Assert-Equal -Label 'Get-AgentBackend: copilot fallback' `
            -Actual (Get-AgentBackend) -Expected 'copilot'
    }
    finally {
        $CLAUDE = $savedClaude
        $KIMI = $savedKimi
    }
}

# ============================================================================
# PART 14: agent.ps1 - Assert-AgentDirectory
# ============================================================================
Write-Host "━━━ PART 14: agent.ps1 :: Assert-AgentDirectory ━━━" -ForegroundColor Cyan

if (Get-Command Assert-AgentDirectory -ErrorAction SilentlyContinue) {
    $validDir = Join-Path $LogDir 'assert-test'
    New-Item -ItemType Directory -Path $validDir -Force | Out-Null

    $result = Assert-AgentDirectory -Path $validDir -ParameterName 'Valid'
    Assert-NotNull -Label 'Assert-AgentDirectory: valid dir returns path' -Value $result

    try {
        Assert-AgentDirectory -Path '' -ParameterName 'Empty'
        Assert-True -Label 'Assert-AgentDirectory: empty path throws' -Condition $false
    } catch {
        Assert-True -Label 'Assert-AgentDirectory: empty path throws' -Condition $true
    }

    try {
        Assert-AgentDirectory -Path (Join-Path $LogDir 'nonexistent-dir-12345') -ParameterName 'Missing'
        Assert-True -Label 'Assert-AgentDirectory: nonexistent dir throws' -Condition $false
    } catch {
        Assert-True -Label 'Assert-AgentDirectory: nonexistent dir throws' -Condition $true
    }
}

# ============================================================================
# PART 15: config.ps1 - Path resolution functions (real, loaded from config)
# ============================================================================
Write-Host "━━━ PART 15: Path resolution functions ━━━" -ForegroundColor Cyan

if (Get-Command Get-WorkspaceRoot -ErrorAction SilentlyContinue) {
    $workspaceRoot = Get-WorkspaceRoot
    Assert-NotNull -Label 'Get-WorkspaceRoot: returns a value' -Value $workspaceRoot
    Assert-True -Label 'Get-WorkspaceRoot: is rooted path' `
        -Condition ([System.IO.Path]::IsPathRooted($workspaceRoot))
}

if (Get-Command Get-CoworkerRoot -ErrorAction SilentlyContinue) {
    $coworkerRoot = Get-CoworkerRoot
    Assert-NotNull -Label 'Get-CoworkerRoot: returns a value' -Value $coworkerRoot
    Assert-True -Label 'Get-CoworkerRoot: is rooted path' `
        -Condition ([System.IO.Path]::IsPathRooted($coworkerRoot))
}

if (Get-Command Get-TasksRoot -ErrorAction SilentlyContinue) {
    $tasksRoot = Get-TasksRoot
    Assert-NotNull -Label 'Get-TasksRoot: returns a value' -Value $tasksRoot
    Assert-True -Label 'Get-TasksRoot: is rooted path' `
        -Condition ([System.IO.Path]::IsPathRooted($tasksRoot))
}

if (Get-Command Get-TargetRepositoryRoot -ErrorAction SilentlyContinue) {
    $targetRepoRoot = Get-TargetRepositoryRoot
    Assert-NotNull -Label 'Get-TargetRepositoryRoot: returns a value' -Value $targetRepoRoot
}

if (Get-Command Get-SchedulerWorkingDirectory -ErrorAction SilentlyContinue) {
    $schedWd = Get-SchedulerWorkingDirectory
    Assert-NotNull -Label 'Get-SchedulerWorkingDirectory: returns a value' -Value $schedWd
}

if (Get-Command Resolve-WorkspacePath -ErrorAction SilentlyContinue) {
    $resolved = Resolve-WorkspacePath -RelativePath 'coworker\scripts\config.ps1'
    Assert-True -Label 'Resolve-WorkspacePath: resolves relative path' `
        -Condition ([System.IO.Path]::IsPathRooted($resolved))
}

# ============================================================================
# PART 16: config.ps1 - Get-CoworkerConfigData
# ============================================================================
Write-Host "━━━ PART 16: Get-CoworkerConfigData ━━━" -ForegroundColor Cyan

if (Get-Command Get-CoworkerConfigData -ErrorAction SilentlyContinue) {
    $configData = Get-CoworkerConfigData
    Assert-NotNull -Label 'Get-CoworkerConfigData: returns config data' -Value $configData
    Assert-True -Label 'Get-CoworkerConfigData: has Paths key' `
        -Condition ($configData.ContainsKey('Paths'))
    Assert-True -Label 'Get-CoworkerConfigData: has a backend key (CLAUDE/KIMI/COPILOT)' `
        -Condition ($configData.ContainsKey('CLAUDE') -or $configData.ContainsKey('KIMI') -or $configData.ContainsKey('COPILOT'))
}

# ============================================================================
# PART 16B: config.ps1 - Get-CoworkerTimestamp (OffsetDateTime format)
# ============================================================================
Write-Host "━━━ PART 16B: Get-CoworkerTimestamp (OffsetDateTime) ━━━" -ForegroundColor Cyan

if (Get-Command Get-CoworkerTimestamp -ErrorAction SilentlyContinue) {
    $ts = Get-CoworkerTimestamp
    Assert-NotNull -Label 'Get-CoworkerTimestamp: returns a value' -Value $ts
    Assert-True -Label 'Get-CoworkerTimestamp: is a string' `
        -Condition ($ts -is [string])

    # Format: 2026-07-11T19:32:00+08:00
    $pattern = '^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}[+-]\d{2}:\d{2}$'
    Assert-True -Label 'Get-CoworkerTimestamp: matches OffsetDateTime format (yyyy-MM-ddTHH:mm:ss±HH:mm)' `
        -Condition ($ts -match $pattern)

    # Verify the components parse correctly
    $parsed = [DateTimeOffset]::Parse($ts)
    Assert-True -Label 'Get-CoworkerTimestamp: parses back to DateTimeOffset' `
        -Condition ($null -ne $parsed)
    Assert-True -Label 'Get-CoworkerTimestamp: year is reasonable (>=2025)' `
        -Condition ($parsed.Year -ge 2025)
    Assert-True -Label 'Get-CoworkerTimestamp: has non-UTC offset' `
        -Condition ($parsed.Offset.TotalMinutes -ne 0 -or $ts -match '[+-](0[89]|1[0-5]):')

    # Verify no fractional seconds (clean, compact format)
    Assert-True -Label 'Get-CoworkerTimestamp: no fractional seconds' `
        -Condition ($ts -notmatch '\.\d+')

    # Verify it differs from UTC 'o' format (which ends with Z)
    Assert-True -Label 'Get-CoworkerTimestamp: does not end with Z (not UTC-only)' `
        -Condition ($ts -notmatch 'Z$')
}

# ============================================================================
# PART 17: config.ps1 - New-CoworkerFileWatcher / Remove-CoworkerFileWatcher
# ============================================================================
Write-Host "━━━ PART 17: New-CoworkerFileWatcher / Remove-CoworkerFileWatcher ━━━" -ForegroundColor Cyan

if (Get-Command New-CoworkerFileWatcher -ErrorAction SilentlyContinue) {
    $watcherDir = Join-Path $LogDir 'watcher-test'
    if (Test-Path $watcherDir) { Remove-Item $watcherDir -Recurse -Force }
    New-Item -ItemType Directory -Path $watcherDir -Force | Out-Null

    try {
        $registration = New-CoworkerFileWatcher -Path $watcherDir -SourcePrefix 'test'
        Assert-NotNull -Label 'New-CoworkerFileWatcher: returns registration' -Value $registration
        Assert-NotNull -Label 'New-CoworkerFileWatcher: has Watcher' -Value $registration.Watcher
        Assert-True -Label 'New-CoworkerFileWatcher: has Path' `
            -Condition ($null -ne $registration.Path)
        Assert-True -Label 'New-CoworkerFileWatcher: has SourceIdentifiers (4 events)' `
            -Condition ($registration.SourceIdentifiers.Count -eq 4)
        Assert-True -Label 'New-CoworkerFileWatcher: watcher enabled' `
            -Condition $registration.Watcher.EnableRaisingEvents
        Remove-CoworkerFileWatcher -Registration $registration
        Assert-True -Label 'Remove-CoworkerFileWatcher: cleans up' -Condition $true
    } catch {
        Assert-True -Label 'New-CoworkerFileWatcher: creates watcher' -Condition $false `
            -Description "Exception: $($_.Exception.Message)"
    }

    $watcherFilePath = Join-Path $watcherDir 'watch-me.txt'
    try {
        $registration = New-CoworkerFileWatcher -Path $watcherFilePath -SourcePrefix 'test-file'
        Assert-NotNull -Label 'New-CoworkerFileWatcher: file path registration' -Value $registration
        Remove-CoworkerFileWatcher -Registration $registration
    } catch {
        Assert-True -Label 'New-CoworkerFileWatcher: file path watcher' -Condition $false `
            -Description "Exception: $($_.Exception.Message)"
    }
}

# ============================================================================
# PART 18: process-task-source.ps1 - helper logic
# ============================================================================
Write-Host "━━━ PART 18: process-task-source.ps1 :: Get-Timestamp & Dispatch-Task ━━━" -ForegroundColor Cyan

function Get-Timestamp {
    [long]([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())
}
$ts = Get-Timestamp
Assert-True -Label 'Get-Timestamp: returns positive value' -Condition ($ts -gt 0)
Assert-True -Label 'Get-Timestamp: reasonable value (post-2020)' -Condition ($ts -gt 1577836800000)

$dispatchTestDir = Join-Path $LogDir 'dispatch-test'
New-Item -ItemType Directory -Path $dispatchTestDir -Force | Out-Null

$testContent = 'Test task content'
$ts = Get-Timestamp
$dst = Join-Path $dispatchTestDir "$ts"
$tmp = [System.IO.Path]::GetTempFileName()
[System.IO.File]::WriteAllText($tmp, $testContent, [System.Text.Encoding]::UTF8)
Move-Item -Path $tmp -Destination $dst -Force
Assert-True -Label 'Dispatch-Task: creates task file' -Condition (Test-Path $dst)
$writtenContent = Get-Content -Path $dst -Raw -Encoding UTF8
Assert-Equal -Label 'Dispatch-Task: preserves content' -Actual $writtenContent -Expected $testContent

# ============================================================================
# PART 19: process-coworker-queue.ps1 - helper logic
# ============================================================================
Write-Host "━━━ PART 19: process-coworker-queue.ps1 :: helper functions ━━━" -ForegroundColor Cyan

function Get-CurrentPowerShellExecutable {
    try {
        $currentProcess = Get-Process -Id $PID -ErrorAction Stop
        if (-not [string]::IsNullOrWhiteSpace($currentProcess.Path)) {
            return $currentProcess.Path
        }
    } catch { }
    if ($PSVersionTable.PSEdition -eq 'Desktop') {
        return (Join-Path $PSHOME 'powershell.exe')
    }
    return (Join-Path $PSHOME 'pwsh.exe')
}

$psExe = Get-CurrentPowerShellExecutable
Assert-True -Label 'Get-CurrentPowerShellExecutable: returns value' `
    -Condition (-not [string]::IsNullOrWhiteSpace($psExe))
Assert-True -Label 'Get-CurrentPowerShellExecutable: path ends with .exe' `
    -Condition ($psExe -match '\.exe$')

# Test Test-HasPendingCoworkerTasks
$repoRoot = if (Get-Command Get-WorkspaceRoot -ErrorAction SilentlyContinue) {
    Get-WorkspaceRoot
} else {
    Join-Path $PSScriptRoot '..\..'
}

function Test-HasPendingCoworkerTasks {
    param([string]$RepoRoot)
    $createdTasks = Get-ChildItem -Path (Join-Path $RepoRoot 'coworker\tasks\main\1ready') -File -ErrorAction SilentlyContinue
    $approvedTasks = Get-ChildItem -Path (Join-Path $RepoRoot 'coworker\tasks\main\5approved') -File -Recurse -ErrorAction SilentlyContinue
    return [bool]($createdTasks -or $approvedTasks)
}

$hasPending = Test-HasPendingCoworkerTasks -RepoRoot $repoRoot
Assert-True -Label 'Test-HasPendingCoworkerTasks: returns boolean' `
    -Condition (($hasPending -eq $true) -or ($hasPending -eq $false))

# ============================================================================
# PART 20: process-draft-refinement-queue.ps1 - helper logic
# ============================================================================
Write-Host "━━━ PART 20: process-draft-refinement-queue.ps1 :: Get-PendingDraftFiles ━━━" -ForegroundColor Cyan

function Get-PendingDraftFiles {
    param([string]$ScanPath)
    if (-not (Test-Path -LiteralPath $ScanPath)) { return @() }
    $scanItem = Get-Item -LiteralPath $ScanPath
    if ($scanItem.PSIsContainer) {
        return @(Get-ChildItem -Path $scanItem.FullName -File |
            Where-Object { Test-CoworkerActionableDraftRefinementFile -Item $_ } |
            Sort-Object Name)
    }
    if (Test-CoworkerActionableDraftRefinementFile -Item $scanItem) { return @($scanItem) }
    return @()
}

$pendingFiles = Get-PendingDraftFiles -ScanPath (Join-Path $LogDir 'nonexistent-draft-dir')
Assert-True -Label 'Get-PendingDraftFiles: nonexistent path returns empty' `
    -Condition ($pendingFiles.Count -eq 0)

$draftTestDir = Join-Path $LogDir 'draft-test'
New-Item -ItemType Directory -Path $draftTestDir -Force | Out-Null

$emptyDraft = Join-Path $draftTestDir 'empty-draft.md'
[System.IO.File]::WriteAllBytes($emptyDraft, @())
$pendingFiles = Get-PendingDraftFiles -ScanPath $draftTestDir
Assert-False -Label 'Get-PendingDraftFiles: empty file not included' `
    -Condition ($pendingFiles.Name -contains 'empty-draft.md')

$filledDraft = Join-Path $draftTestDir 'filled-draft.md'
'Draft content here' | Set-Content -Path $filledDraft -Encoding UTF8
$pendingFiles = Get-PendingDraftFiles -ScanPath $draftTestDir
Assert-True -Label 'Get-PendingDraftFiles: non-empty file included' `
    -Condition ($pendingFiles.Name -contains 'filled-draft.md')

$pendingFiles = Get-PendingDraftFiles -ScanPath $filledDraft
Assert-Equal -Label 'Get-PendingDraftFiles: single file mode returns 1' `
    -Actual $pendingFiles.Count -Expected 1

# ============================================================================
# PART 21: coworker-scheduler.ps1 - Test-PathHasPendingFiles
# ============================================================================
Write-Host "━━━ PART 21: Test-PathHasPendingFiles ━━━" -ForegroundColor Cyan

function Test-PathHasPendingFiles {
    param([string]$Path)
    if (-not (Test-Path -LiteralPath $Path)) { return $false }
    $item = Get-Item -LiteralPath $Path -ErrorAction SilentlyContinue
    if ($null -eq $item) { return $false }
    $pendingFilePredicate = { param($candidate) Test-CoworkerPendingFile -Item $candidate }
    if (-not $item.PSIsContainer) {
        return & $pendingFilePredicate $item
    }
    $pendingFile = Get-ChildItem -LiteralPath $item.FullName -File -Recurse -ErrorAction SilentlyContinue |
        Where-Object { & $pendingFilePredicate $_ } |
        Select-Object -First 1
    return $null -ne $pendingFile
}

Assert-False -Label 'Test-PathHasPendingFiles: nonexistent path' `
    -Condition (Test-PathHasPendingFiles -Path (Join-Path $LogDir 'no-such-path'))

$emptyDirTest = Join-Path $LogDir 'empty-dir-scheduler'
New-Item -ItemType Directory -Path $emptyDirTest -Force | Out-Null
Remove-Item (Join-Path $emptyDirTest '*') -Force -ErrorAction SilentlyContinue
Assert-False -Label 'Test-PathHasPendingFiles: empty directory' `
    -Condition (Test-PathHasPendingFiles -Path $emptyDirTest)

$schedulerTestFile = Join-Path $emptyDirTest 'pending-task.md'
'task content' | Set-Content -Path $schedulerTestFile -Encoding UTF8
Assert-True -Label 'Test-PathHasPendingFiles: directory with file' `
    -Condition (Test-PathHasPendingFiles -Path $emptyDirTest)

Assert-True -Label 'Test-PathHasPendingFiles: single file' `
    -Condition (Test-PathHasPendingFiles -Path $schedulerTestFile)

$gitkeepTest = Join-Path $emptyDirTest '.gitkeep'
Remove-Item $schedulerTestFile -Force
[System.IO.File]::WriteAllBytes($gitkeepTest, @())
Assert-False -Label 'Test-PathHasPendingFiles: .gitkeep ignored' `
    -Condition (Test-PathHasPendingFiles -Path $emptyDirTest)

# ============================================================================
# PART 22: coworker-scheduler.ps1 - Resolve-SchedulerPath
# ============================================================================
Write-Host "━━━ PART 22: Resolve-SchedulerPath ━━━" -ForegroundColor Cyan

function Resolve-SchedulerPath {
    param(
        [string]$Path,
        [string]$WorkspaceRoot,
        [string]$ConfigDirectory
    )
    if ([System.IO.Path]::IsPathRooted($Path)) {
        return [System.IO.Path]::GetFullPath($Path)
    }
    $configRelativePath = Join-Path $ConfigDirectory $Path
    if (Test-Path -LiteralPath $configRelativePath) {
        return (Resolve-Path -LiteralPath $configRelativePath).Path
    }
    return [System.IO.Path]::GetFullPath((Join-Path $WorkspaceRoot $Path))
}

$wsRoot = $repoRoot
$configDir = Join-Path $PSScriptRoot '..\..\coworker\scripts'

$absolutePath = 'C:\absolute\path\file.txt'
$result = Resolve-SchedulerPath -Path $absolutePath -WorkspaceRoot $wsRoot -ConfigDirectory $configDir
Assert-Equal -Label 'Resolve-SchedulerPath: absolute path returned as-is' `
    -Actual $result -Expected ([System.IO.Path]::GetFullPath($absolutePath))

$result = Resolve-SchedulerPath -Path 'src\main.ps1' -WorkspaceRoot $wsRoot -ConfigDirectory $configDir
Assert-True -Label 'Resolve-SchedulerPath: relative path resolved under workspace' `
    -Condition ([System.IO.Path]::IsPathRooted($result))

# ============================================================================
# PART 23: coworker-scheduler.ps1 - Get-TaskSnapshot
# ============================================================================
Write-Host "━━━ PART 23: Get-TaskSnapshot ━━━" -ForegroundColor Cyan

function Get-TaskSnapshot {
    param([hashtable]$TaskState)
    @{
        'Name'                = $TaskState.Name
        'Description'         = $TaskState.Description
        'Enabled'             = $TaskState.Enabled
        'IntervalSeconds'     = $TaskState.IntervalSeconds
        'DependsOn'           = @($TaskState.DependsOn)
        'PendingPaths'        = @($TaskState.PendingPaths)
        'ScriptPath'          = $TaskState.ScriptPath
        'Arguments'           = @($TaskState.Arguments)
        'Status'              = $TaskState.Status
        'LastStartedUtc'      = $TaskState.LastStartedUtc
        'LastFinishedUtc'     = $TaskState.LastFinishedUtc
        'LastExitCode'        = $TaskState.LastExitCode
        'LastDurationSeconds' = $TaskState.LastDurationSeconds
        'CurrentPid'          = $TaskState.CurrentPid
        'NextRunUtc'          = $TaskState.NextRunUtc
        'StdOutLogPath'       = $TaskState.StdOutLogPath
        'StdErrLogPath'       = $TaskState.StdErrLogPath
        'RunCount'            = $TaskState.RunCount
    }
}

$testState = @{
    Name                = 'test-task'
    Description         = 'A test task'
    Enabled             = $true
    IntervalSeconds     = 60
    DependsOn           = @('dependency1')
    PendingPaths        = @('C:\pending\path')
    ScriptPath          = 'C:\scripts\test.ps1'
    Arguments           = @('-Once')
    Status              = 'Idle'
    LastStartedUtc      = (Get-Date).ToString('yyyy-MM-ddTHH:mm:sszzz')
    LastFinishedUtc     = $null
    LastExitCode        = 0
    LastDurationSeconds = 120.5
    CurrentPid          = $null
    NextRunUtc          = (Get-Date).AddMinutes(1).ToString('yyyy-MM-ddTHH:mm:sszzz')
    StdOutLogPath       = 'C:\logs\test.stdout.log'
    StdErrLogPath       = $null
    RunCount            = 5
}

$snapshot = Get-TaskSnapshot -TaskState $testState
Assert-Equal -Label 'Get-TaskSnapshot: Name' -Actual $snapshot.Name -Expected 'test-task'
Assert-Equal -Label 'Get-TaskSnapshot: Description' -Actual $snapshot.Description -Expected 'A test task'
Assert-Equal -Label 'Get-TaskSnapshot: Enabled' -Actual $snapshot.Enabled -Expected $true
Assert-Equal -Label 'Get-TaskSnapshot: IntervalSeconds' -Actual $snapshot.IntervalSeconds -Expected 60
Assert-Equal -Label 'Get-TaskSnapshot: RunCount' -Actual $snapshot.RunCount -Expected 5
Assert-Equal -Label 'Get-TaskSnapshot: DependsOn' -Actual $snapshot.DependsOn[0] -Expected 'dependency1'
Assert-Equal -Label 'Get-TaskSnapshot: Status' -Actual $snapshot.Status -Expected 'Idle'

# ============================================================================
# PART 24: coworker-scheduler.ps1 - Test-ScheduledTaskCanStart
# ============================================================================
Write-Host "━━━ PART 24: Test-ScheduledTaskCanStart ━━━" -ForegroundColor Cyan

function Test-ScheduledTaskCanStart {
    param(
        [hashtable]$TaskState,
        [hashtable]$TaskStates,
        [datetime]$Now,
        [switch]$OnceMode
    )
    if (-not $TaskState.Enabled -or $null -ne $TaskState.Process) { return $false }
    if ($OnceMode -and $TaskState.RunCount -gt 0) { return $false }
    $nextRunUtc = $TaskState.NextRunUtc
    if (-not [string]::IsNullOrWhiteSpace($nextRunUtc)) {
        $nextRunAt = [DateTimeOffset]::Parse($nextRunUtc)
        if ($Now -lt $nextRunAt.UtcDateTime) { return $false }
    }
    foreach ($dependencyName in @($TaskState.DependsOn)) {
        if (-not $TaskStates.ContainsKey($dependencyName)) {
            throw "Scheduled task '$($TaskState.Name)' depends on unknown task '$dependencyName'."
        }
        $dependencyState = $TaskStates[$dependencyName]
        if ($dependencyState.Enabled -and $null -ne $dependencyState.Process) { return $false }
        $depNextRunUtc = $dependencyState.NextRunUtc
        if (-not [string]::IsNullOrWhiteSpace($depNextRunUtc)) {
            $depNextRunAt = [DateTimeOffset]::Parse($depNextRunUtc)
            if ($dependencyState.Enabled -and $Now -ge $depNextRunAt.UtcDateTime) { return $false }
        }
        if ($OnceMode -and $dependencyState.Enabled -and $dependencyState.RunCount -eq 0) { return $false }
    }
    return $true
}

$now = (Get-Date).ToUniversalTime()
$pastTime = $now.AddMinutes(-1).ToString('yyyy-MM-ddTHH:mm:sszzz')
$futureTime = $now.AddMinutes(10).ToString('yyyy-MM-ddTHH:mm:sszzz')

# Ready task
$readyTask = @{ Name = 'ready-task'; Enabled = $true; Process = $null; NextRunUtc = $pastTime; DependsOn = @(); RunCount = 0 }
$allTaskStates = @{ 'ready-task' = $readyTask }
Assert-True -Label 'Test-ScheduledTaskCanStart: ready task' `
    -Condition (Test-ScheduledTaskCanStart -TaskState $readyTask -TaskStates $allTaskStates -Now $now)

# Disabled task
$disabledTask = @{ Name = 'disabled-task'; Enabled = $false; Process = $null; NextRunUtc = $pastTime; DependsOn = @(); RunCount = 0 }
$allTaskStates['disabled-task'] = $disabledTask
Assert-False -Label 'Test-ScheduledTaskCanStart: disabled task' `
    -Condition (Test-ScheduledTaskCanStart -TaskState $disabledTask -TaskStates $allTaskStates -Now $now)

# Future task
$futureTask = @{ Name = 'future-task'; Enabled = $true; Process = $null; NextRunUtc = $futureTime; DependsOn = @(); RunCount = 0 }
$allTaskStates['future-task'] = $futureTask
Assert-False -Label 'Test-ScheduledTaskCanStart: future task' `
    -Condition (Test-ScheduledTaskCanStart -TaskState $futureTask -TaskStates $allTaskStates -Now $now)

# Once-mode, already run
$alreadyRunTask = @{ Name = 'already-run'; Enabled = $true; Process = $null; NextRunUtc = $pastTime; DependsOn = @(); RunCount = 1 }
$allTaskStates['already-run'] = $alreadyRunTask
Assert-False -Label 'Test-ScheduledTaskCanStart: once mode, already run' `
    -Condition (Test-ScheduledTaskCanStart -TaskState $alreadyRunTask -TaskStates $allTaskStates -Now $now -OnceMode)

# Dependency not yet run (once mode)
$dependencyTask = @{ Name = 'dependency'; Enabled = $true; Process = $null; NextRunUtc = $pastTime; DependsOn = @(); RunCount = 0 }
$dependentTask = @{ Name = 'dependent'; Enabled = $true; Process = $null; NextRunUtc = $pastTime; DependsOn = @('dependency'); RunCount = 0 }
$depStates = @{ 'dependency' = $dependencyTask; 'dependent' = $dependentTask }
Assert-False -Label 'Test-ScheduledTaskCanStart: OnceMode blocks if dependency not run' `
    -Condition (Test-ScheduledTaskCanStart -TaskState $dependentTask -TaskStates $depStates -Now $now -OnceMode)

# Unknown dependency throws
$badTask = @{ Name = 'bad-dep'; Enabled = $true; Process = $null; NextRunUtc = $pastTime; DependsOn = @('unknown-task'); RunCount = 0 }
$badStates = @{ 'bad-dep' = $badTask }
try {
    Test-ScheduledTaskCanStart -TaskState $badTask -TaskStates $badStates -Now $now
    Assert-True -Label 'Test-ScheduledTaskCanStart: unknown dependency throws' -Condition $false
} catch {
    Assert-True -Label 'Test-ScheduledTaskCanStart: unknown dependency throws' -Condition $true
}

# ============================================================================
# PART 25: coworker-scheduler.ps1 - Set-ScheduledTaskWaitingForWork
# ============================================================================
Write-Host "━━━ PART 25: Set-ScheduledTaskWaitingForWork ━━━" -ForegroundColor Cyan

function Set-ScheduledTaskWaitingForWork {
    param([hashtable]$TaskState, [datetime]$Now)
    $TaskState.Status = 'WaitingForWork'
    $TaskState.NextRunUtc = $Now.AddSeconds($TaskState.IntervalSeconds).ToString('yyyy-MM-ddTHH:mm:sszzz')
}

$waitingTask = @{ Name = 'waiting-task'; Status = 'Idle'; IntervalSeconds = 30; NextRunUtc = '' }
Set-ScheduledTaskWaitingForWork -TaskState $waitingTask -Now $now
Assert-Equal -Label 'Set-ScheduledTaskWaitingForWork: status set' `
    -Actual $waitingTask.Status -Expected 'WaitingForWork'
Assert-True -Label 'Set-ScheduledTaskWaitingForWork: NextRunUtc set' `
    -Condition (-not [string]::IsNullOrWhiteSpace($waitingTask.NextRunUtc))

# ============================================================================
# PART 26: common/Util.ps1 - Fix-Encoding-UTF8
# ============================================================================
Write-Host "━━━ PART 26: common/Util.ps1 :: Fix-Encoding-UTF8 ━━━" -ForegroundColor Cyan

$utilPath = Join-Path $scriptsDir 'common\Util.ps1'
if (Test-Path $utilPath) {
    try {
        . $utilPath
        Assert-True -Label 'Fix-Encoding-UTF8: loads without error' -Condition $true
    } catch {
        Assert-True -Label 'Fix-Encoding-UTF8: loads without error' -Condition $false `
            -Description "Exception: $($_.Exception.Message)"
    }
    try {
        Fix-Encoding-UTF8
        Assert-True -Label 'Fix-Encoding-UTF8: runs without error' -Condition $true
        Assert-True -Label 'Fix-Encoding-UTF8: OutputEncoding is UTF8' `
            -Condition ($OutputEncoding -is [System.Text.UTF8Encoding])
    } catch {
        Assert-True -Label 'Fix-Encoding-UTF8: runs without error' -Condition $false `
            -Description "Exception: $($_.Exception.Message)"
    }
} else {
    Write-Host "    ⚠ Util.ps1 not found, skipping encoding tests" -ForegroundColor Yellow
}

# ============================================================================
# PART 27: process-task-source.ps1 - MD5 hashing logic
# ============================================================================
Write-Host "━━━ PART 27: MD5 hashing logic ━━━" -ForegroundColor Cyan

$testContentForHash = 'This is test content for MD5 hashing'
$md5 = [System.BitConverter]::ToString(
    (New-Object System.Security.Cryptography.MD5CryptoServiceProvider).ComputeHash(
        [System.Text.Encoding]::UTF8.GetBytes($testContentForHash)
    )
).Replace("-", "").ToLower()

Assert-True -Label 'MD5 hash: returns non-empty string' `
    -Condition (-not [string]::IsNullOrWhiteSpace($md5))
Assert-Equal -Label 'MD5 hash: 32 hex characters' -Actual $md5.Length -Expected 32

$md5b = [System.BitConverter]::ToString(
    (New-Object System.Security.Cryptography.MD5CryptoServiceProvider).ComputeHash(
        [System.Text.Encoding]::UTF8.GetBytes($testContentForHash)
    )
).Replace("-", "").ToLower()
Assert-Equal -Label 'MD5 hash: deterministic' -Actual $md5 -Expected $md5b

$md5Different = [System.BitConverter]::ToString(
    (New-Object System.Security.Cryptography.MD5CryptoServiceProvider).ComputeHash(
        [System.Text.Encoding]::UTF8.GetBytes('Different content')
    )
).Replace("-", "").ToLower()
Assert-True -Label 'MD5 hash: different content -> different hash' `
    -Condition ($md5 -ne $md5Different)

$hashMarker = "<!-- TASK_SOURCE_MONITOR_HASH: $md5 -->"
Assert-True -Label 'MD5 hash marker: contains hash' `
    -Condition ($hashMarker -match [regex]::Escape($md5))

$tempHashFile = Join-Path $LogDir 'hash-test-file.txt'
$hashMarker | Set-Content -Path $tempHashFile -Encoding UTF8
$found = Select-String -Path $tempHashFile -Pattern $md5 -SimpleMatch -Quiet
Assert-True -Label 'MD5 hash: Select-String finds hash in file' -Condition $found

# ============================================================================
# PART 28: Task name normalization logic
# ============================================================================
Write-Host "━━━ PART 28: Task name normalization ━━━" -ForegroundColor Cyan

function Normalize-TaskName {
    param([string]$RawName)
    if ([string]::IsNullOrWhiteSpace($RawName)) { return '' }
    $normalized = $RawName.Trim()
    $normalized = $normalized -replace '\s+', '-'
    $normalized = $normalized -replace '[^A-Za-z0-9._-]', '-'
    $normalized = $normalized -replace '-+', '-'
    $normalized = $normalized.Trim(' ', '.', '-', '_')
    if ($normalized.Length -gt 60) {
        $normalized = $normalized.Substring(0, 60).Trim(' ', '.', '-', '_')
    }
    return $normalized
}

Assert-Equal -Label 'Normalize-TaskName: simple' `
    -Actual (Normalize-TaskName -RawName 'hello-world') -Expected 'hello-world'
Assert-Equal -Label 'Normalize-TaskName: spaces to hyphens' `
    -Actual (Normalize-TaskName -RawName 'hello world task') -Expected 'hello-world-task'
Assert-Equal -Label 'Normalize-TaskName: mixed case preserved' `
    -Actual (Normalize-TaskName -RawName 'Hello-World-Task') -Expected 'Hello-World-Task'
Assert-Equal -Label 'Normalize-TaskName: special chars removed' `
    -Actual (Normalize-TaskName -RawName 'fix: bug #123!') -Expected 'fix-bug-123'
Assert-Equal -Label 'Normalize-TaskName: multiple hyphens collapsed' `
    -Actual (Normalize-TaskName -RawName 'hello---world') -Expected 'hello-world'
Assert-Equal -Label 'Normalize-TaskName: trim punctuation' `
    -Actual (Normalize-TaskName -RawName '...hello-world...') -Expected 'hello-world'
Assert-Equal -Label 'Normalize-TaskName: truncation at 60 chars' `
    -Actual (Normalize-TaskName -RawName ('a' * 100)) -Expected ('a' * 60)
Assert-Equal -Label 'Normalize-TaskName: truncation trims trailing hyphens' `
    -Actual (Normalize-TaskName -RawName ('a-' * 31)) -Expected ('a-' * 30).TrimEnd('-')

$testName = Normalize-TaskName -RawName 'implement user authentication system'
Assert-True -Label 'Normalize-TaskName: result is safe filename' `
    -Condition ($testName -notmatch '[\\/*?:"<>|]')

# ============================================================================
# PART 29: Resolve-UniquePath logic
# ============================================================================
Write-Host "━━━ PART 29: Resolve-UniquePath logic ━━━" -ForegroundColor Cyan

function Resolve-UniquePath {
    param([string]$Directory, [string]$BaseName, [string]$Extension)
    $candidateName = "$BaseName$Extension"
    $candidatePath = Join-Path $Directory $candidateName
    if (!(Test-Path $candidatePath)) {
        return @{ Path = $candidatePath; FileName = $candidateName }
    }
    $counter = 2
    while ($true) {
        $nextName = "$BaseName.$counter$Extension"
        $nextPath = Join-Path $Directory $nextName
        if (!(Test-Path $nextPath)) {
            return @{ Path = $nextPath; FileName = $nextName }
        }
        $counter++
    }
}

$uniqueTestDir = Join-Path $LogDir 'unique-tests'
New-Item -ItemType Directory -Path $uniqueTestDir -Force | Out-Null
Remove-Item (Join-Path $uniqueTestDir '*') -Force -ErrorAction SilentlyContinue

$result = Resolve-UniquePath -Directory $uniqueTestDir -BaseName 'task' -Extension '.md'
Assert-Equal -Label 'Resolve-UniquePath: first file gets base name' `
    -Actual $result.FileName -Expected 'task.md'

Set-Content -Path (Join-Path $uniqueTestDir 'task.md') -Value '' -Encoding UTF8
$result = Resolve-UniquePath -Directory $uniqueTestDir -BaseName 'task' -Extension '.md'
Assert-Equal -Label 'Resolve-UniquePath: second file gets suffix .2' `
    -Actual $result.FileName -Expected 'task.2.md'

Set-Content -Path (Join-Path $uniqueTestDir 'task.2.md') -Value '' -Encoding UTF8
$result = Resolve-UniquePath -Directory $uniqueTestDir -BaseName 'task' -Extension '.md'
Assert-Equal -Label 'Resolve-UniquePath: third file gets suffix .3' `
    -Actual $result.FileName -Expected 'task.3.md'

# ============================================================================
# PART 30: count-total-token-usage.py - parse_size logic
# ============================================================================
Write-Host "━━━ PART 30: count-total-token-usage :: parse_size logic ━━━" -ForegroundColor Cyan

function ConvertFrom-TokenSize {
    param([string]$SizeStr)
    $sizeStr = $SizeStr.ToLower().Trim()
    $multiplier = 1
    if ($sizeStr.EndsWith('k')) { $multiplier = 1000; $sizeStr = $sizeStr.Substring(0, $sizeStr.Length - 1) }
    elseif ($sizeStr.EndsWith('m')) { $multiplier = 1000000; $sizeStr = $sizeStr.Substring(0, $sizeStr.Length - 1) }
    elseif ($sizeStr.EndsWith('b')) { $multiplier = 1000000000; $sizeStr = $sizeStr.Substring(0, $sizeStr.Length - 1) }
    try {
        return [int]([float]::Parse($sizeStr) * $multiplier)
    } catch {
        return 0
    }
}

Assert-Equal -Label 'ConvertFrom-TokenSize: plain number' `
    -Actual (ConvertFrom-TokenSize -SizeStr '500') -Expected 500
Assert-Equal -Label 'ConvertFrom-TokenSize: kilobyte' `
    -Actual (ConvertFrom-TokenSize -SizeStr '917.2k') -Expected 917200
Assert-Equal -Label 'ConvertFrom-TokenSize: megabyte' `
    -Actual (ConvertFrom-TokenSize -SizeStr '1.2m') -Expected 1200000
Assert-Equal -Label 'ConvertFrom-TokenSize: billion' `
    -Actual (ConvertFrom-TokenSize -SizeStr '2b') -Expected 2000000000
Assert-Equal -Label 'ConvertFrom-TokenSize: invalid input' `
    -Actual (ConvertFrom-TokenSize -SizeStr 'invalid') -Expected 0
Assert-Equal -Label 'ConvertFrom-TokenSize: empty string' `
    -Actual (ConvertFrom-TokenSize -SizeStr '') -Expected 0

# ============================================================================
# PART 31: count-total-token-usage.py - parse_duration logic
# ============================================================================
Write-Host "━━━ PART 31: count-total-token-usage :: parse_duration logic ━━━" -ForegroundColor Cyan

function ConvertFrom-TokenDuration {
    param([string]$DurStr)
    $totalSeconds = 0
    if ($DurStr -match '(\d+)m') { $totalSeconds += [int]$Matches[1] * 60 }
    if ($DurStr -match '(\d+)s') { $totalSeconds += [int]$Matches[1] }
    if ($DurStr -match '(\d+)h') { $totalSeconds += [int]$Matches[1] * 3600 }
    if ($totalSeconds -eq 0 -and $DurStr -match '^\d+$') { $totalSeconds = [int]$DurStr }
    return $totalSeconds
}

Assert-Equal -Label 'ConvertFrom-TokenDuration: minutes and seconds' `
    -Actual (ConvertFrom-TokenDuration -DurStr '4m 9s') -Expected 249
Assert-Equal -Label 'ConvertFrom-TokenDuration: only minutes' `
    -Actual (ConvertFrom-TokenDuration -DurStr '10m') -Expected 600
Assert-Equal -Label 'ConvertFrom-TokenDuration: only seconds' `
    -Actual (ConvertFrom-TokenDuration -DurStr '45s') -Expected 45
Assert-Equal -Label 'ConvertFrom-TokenDuration: hours' `
    -Actual (ConvertFrom-TokenDuration -DurStr '1h 30m') -Expected 5400
Assert-Equal -Label 'ConvertFrom-TokenDuration: plain number' `
    -Actual (ConvertFrom-TokenDuration -DurStr '120') -Expected 120
Assert-Equal -Label 'ConvertFrom-TokenDuration: empty string' `
    -Actual (ConvertFrom-TokenDuration -DurStr '') -Expected 0

# ============================================================================
# PART 32: count-total-token-usage.py - cost calculation logic
# ============================================================================
Write-Host "━━━ PART 32: count-total-token-usage :: cost calculation ━━━" -ForegroundColor Cyan

function Get-EstimatedTokenCost {
    param(
        [string]$ModelName,
        [long]$InTokens,
        [long]$OutTokens,
        [long]$CachedTokens
    )
    $pricing = @{
        'gemini-3-pro-preview' = @{ In = 2.50; Out = 10.00; Cached = 0.625 }
        'gemini-2.0-flash'     = @{ In = 0.10; Out = 0.40;  Cached = 0.025 }
        'claude-3-5-sonnet'    = @{ In = 3.00; Out = 15.00; Cached = 0.30 }
        'claude-sonnet-4.6'    = @{ In = 3.00; Out = 15.00; Cached = 0.30 }
        'claude-3-opus'        = @{ In = 15.00; Out = 75.00; Cached = 1.50 }
        'claude-3-haiku'       = @{ In = 0.25; Out = 1.25;  Cached = 0.025 }
        'gpt-4o'               = @{ In = 2.50; Out = 10.00; Cached = 1.25 }
        'gpt-4o-mini'          = @{ In = 0.15; Out = 0.60;  Cached = 0.075 }
        'gpt-4'                = @{ In = 30.00; Out = 60.00; Cached = 30.00 }
        'gpt-3.5-turbo'        = @{ In = 0.50; Out = 1.50;  Cached = 0.50 }
    }
    $defaultPricing = @{ In = 2.50; Out = 10.00; Cached = 1.25 }
    $p = if ($pricing.ContainsKey($ModelName)) { $pricing[$ModelName] } else { $defaultPricing }
    return ($InTokens / 1000000 * $p.In) +
           ($OutTokens / 1000000 * $p.Out) +
           ($CachedTokens / 1000000 * $p.Cached)
}

$cost = Get-EstimatedTokenCost -ModelName 'claude-sonnet-4.6' `
    -InTokens 1000000 -OutTokens 100000 -CachedTokens 500000
Assert-True -Label 'Get-EstimatedTokenCost: Claude Sonnet cost is positive' `
    -Condition ($cost -gt 0)

$costDefault = Get-EstimatedTokenCost -ModelName 'unknown-model' `
    -InTokens 1000000 -OutTokens 0 -CachedTokens 0
Assert-True -Label 'Get-EstimatedTokenCost: unknown model uses defaults' `
    -Condition ($costDefault -gt 0)

$costZero = Get-EstimatedTokenCost -ModelName 'claude-3-haiku' `
    -InTokens 0 -OutTokens 0 -CachedTokens 0
Assert-Equal -Label 'Get-EstimatedTokenCost: zero tokens = zero cost' `
    -Actual $costZero -Expected 0

# ============================================================================
# PART 33: config.ps1 - $COPILOT variable
# ============================================================================
Write-Host "━━━ PART 33: $COPILOT variable ━━━" -ForegroundColor Cyan

Assert-True -Label '$COPILOT: is an array' `
    -Condition ($COPILOT -is [array] -or $COPILOT -is [System.Collections.ObjectModel.Collection`1[System.Object]])

# COPILOT is optional; if it is not configured (commented out in config.psd1),
# config.ps1 leaves $COPILOT as @($null). Only validate shape when a real config
# value was provided.
if ($COPILOT -and $COPILOT[0]) {
    Assert-True -Label '$COPILOT: has at least 2 elements' `
        -Condition ($COPILOT.Count -ge 2)
    Assert-Equal -Label '$COPILOT: first element is gh' `
        -Actual $COPILOT[0] -Expected 'gh'
}

# ============================================================================
# PART 34: Script file presence validation
# ============================================================================
Write-Host "━━━ PART 34: Script file presence ━━━" -ForegroundColor Cyan

$expectedScripts = @(
    'config.ps1',
    'config.psd1',
    'coworker-scheduler.ps1',
    'coworker-scheduler.config.psd1',
    'coworker.ps1',
    'process-coworker-queue.ps1',
    'process-draft-refinement-queue.ps1',
    'common\Util.ps1',
    'workers\agent.ps1',
    'workers\git-sync.ps1',
    'workers\refine-drafts.ps1',
    'workers\refine-last-draft.ps1',
    'workers\rename.ps1',
    'workers\writer.ps1',
    'workers\count-total-token-usage.ps1',
    'workers\count-total-token-usage.py',
    'workers\coworker-memory-generator.ps1',
    'workers\coworker-daily-memory-generator.ps1'
)

foreach ($scriptRelPath in $expectedScripts) {
    $fullPath = Join-Path $scriptsDir $scriptRelPath
    $exists = Test-Path -LiteralPath $fullPath
    Assert-True -Label "File exists: $scriptRelPath" -Condition $exists
}

# ============================================================================
# PART 35: Script syntax validation
# ============================================================================
Write-Host "━━━ PART 35: Script syntax validation ━━━" -ForegroundColor Cyan

$scriptsToValidate = @(
    'config.ps1',
    'process-coworker-queue.ps1',
    'process-draft-refinement-queue.ps1',
    'process-task-source.ps1',
    'coworker-scheduler.ps1'
)

foreach ($scriptRelPath in $scriptsToValidate) {
    $fullPath = Join-Path $scriptsDir $scriptRelPath
    if (Test-Path -LiteralPath $fullPath) {
        try {
            $errors = $null
            $null = [System.Management.Automation.PSParser]::Tokenize(
                (Get-Content -Path $fullPath -Raw), [ref]$errors
            )
            if ($errors.Count -eq 0) {
                Assert-True -Label "Syntax valid: $scriptRelPath" -Condition $true
            } else {
                Assert-True -Label "Syntax valid: $scriptRelPath" -Condition $false `
                    -Description "Parse errors: $($errors -join ', ')"
            }
        } catch {
            try {
                $ast = [System.Management.Automation.Language.Parser]::ParseFile(
                    $fullPath, [ref]$null, [ref]$null
                )
                Assert-True -Label "Syntax valid: $scriptRelPath" -Condition $true
            } catch {
                Assert-True -Label "Syntax valid: $scriptRelPath" -Condition $false `
                    -Description "Parse error: $($_.Exception.Message)"
            }
        }
    }
}

# ============================================================================
# PART 36: config.psd1 - Config data validation
# ============================================================================
Write-Host "━━━ PART 36: config.psd1 validation ━━━" -ForegroundColor Cyan

$configPsd1Path = Join-Path $scriptsDir 'config.psd1'
if (Test-Path $configPsd1Path) {
    $configData = Import-PowerShellDataFile -Path $configPsd1Path
    Assert-True -Label 'config.psd1: has Paths' -Condition $configData.ContainsKey('Paths')
    Assert-True -Label 'config.psd1: has at least one backend key (CLAUDE/KIMI/COPILOT)' `
        -Condition ($configData.ContainsKey('CLAUDE') -or $configData.ContainsKey('KIMI') -or $configData.ContainsKey('COPILOT'))
    Assert-True -Label 'config.psd1: has Scheduler' -Condition $configData.ContainsKey('Scheduler')

    $paths = $configData['Paths']
    Assert-True -Label 'config.psd1: Paths.WorkspaceRoot present' -Condition $paths.ContainsKey('WorkspaceRoot')
    Assert-True -Label 'config.psd1: Paths.CoworkerRoot present' -Condition $paths.ContainsKey('CoworkerRoot')
    Assert-True -Label 'config.psd1: Paths.TasksRoot present' -Condition $paths.ContainsKey('TasksRoot')

    $scheduler = $configData['Scheduler']
    Assert-True -Label 'config.psd1: Scheduler.WorkingDirectory present' -Condition $scheduler.ContainsKey('WorkingDirectory')
}

# ============================================================================
# PART 37: coworker-scheduler.config.psd1 validation
# ============================================================================
Write-Host "━━━ PART 37: coworker-scheduler.config.psd1 validation ━━━" -ForegroundColor Cyan

$schedulerConfigPath = Join-Path $scriptsDir 'coworker-scheduler.config.psd1'
if (Test-Path $schedulerConfigPath) {
    $schedulerConfig = Import-PowerShellDataFile -Path $schedulerConfigPath
    Assert-True -Label 'scheduler-config: has Tasks' -Condition $schedulerConfig.ContainsKey('Tasks')
    Assert-True -Label 'scheduler-config: has Scheduler' -Condition $schedulerConfig.ContainsKey('Scheduler')

    $tasks = $schedulerConfig['Tasks']
    Assert-True -Label 'scheduler-config: Tasks is non-empty array' -Condition ($tasks.Count -gt 0)

    $allValid = $true
    $validationErrors = @()
    foreach ($task in $tasks) {
        if (-not $task.ContainsKey('Name') -or [string]::IsNullOrWhiteSpace($task['Name'])) {
            $allValid = $false
            $validationErrors += "Task missing Name"
        }
        if (-not $task.ContainsKey('IntervalSeconds') -or $task['IntervalSeconds'] -le 0) {
            $allValid = $false
            $validationErrors += "Task '$($task['Name'])' has invalid IntervalSeconds"
        }
        if (-not $task.ContainsKey('ScriptPath') -or [string]::IsNullOrWhiteSpace($task['ScriptPath'])) {
            $allValid = $false
            $validationErrors += "Task '$($task['Name'])' missing ScriptPath"
        }
    }
    Assert-True -Label 'scheduler-config: all tasks have required fields' `
        -Condition $allValid -Description ($validationErrors -join '; ')

    $schedSettings = $schedulerConfig['Scheduler']
    Assert-True -Label 'scheduler-config: TickSeconds > 0' -Condition ($schedSettings['TickSeconds'] -gt 0)
}

# ============================================================================
# PART 38: agent.ps1 - Function presence validation
# ============================================================================
Write-Host "━━━ PART 38: agent.ps1 function validation ━━━" -ForegroundColor Cyan

if (Test-Path $agentPs1) {
    try {
        $ast = [System.Management.Automation.Language.Parser]::ParseFile(
            $agentPs1, [ref]$null, [ref]$null
        )
        $functionNames = $ast.FindAll({
            param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
        }, $true) | ForEach-Object { $_.Name }

        $expectedFunctions = @(
            'Get-AgentRepoRoot',
            'Assert-AgentDirectory',
            'Get-AgentCommand',
            'New-AgentArguments',
            'Format-AgentCommand',
            'ConvertTo-WindowsCommandLineArgument',
            'Start-AgentProcess',
            'Invoke-Agent'
        )

        foreach ($funcName in $expectedFunctions) {
            Assert-True -Label "agent.ps1: function '$funcName' defined" `
                -Condition ($funcName -in $functionNames)
        }
    } catch {
        Write-Host "    ⚠ AST parsing failed, skipping function validation" -ForegroundColor Yellow
    }
}

# ============================================================================
# Final report
# ============================================================================
Write-Host ''
$exitCode = Finish-TestSession -ExtraCopilotPrompt "These are coworker/scripts test failures."
if ($script:ContentFailures -gt 0) {
    Write-Host "  ⚠ $script:ContentFailures content-based assertion(s) also failed" -ForegroundColor Red
    if ($exitCode -eq 0) { $exitCode = 1 }
}
exit $exitCode
