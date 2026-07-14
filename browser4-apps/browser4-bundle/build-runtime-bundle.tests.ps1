#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Unit tests for pure functions in build-runtime-bundle.ps1.

.DESCRIPTION
    Extracts pure helper functions from build-runtime-bundle.ps1 via PowerShell's
    AST parser and tests them in isolation — no Maven, no JDK, no network.

    Covers: Remove-ArchiveSuffix, Get-JDKVersion, Get-JavaMajorVersionFromText,
            Get-JlinkCompressValue, Get-BundleMetadataJson, Convert-ToExtendedLengthPath,
            Resolve-InputPath, Write-LaunchScripts, Get-PathSeparator,
            Get-AssetNameForCurrentPlatform, Test-ValidJar, Read-ManifestAttribute,
            Get-DefaultMainClass, and platform-detection functions.

    Run standalone:
        pwsh browser4-apps/browser4-bundle/build-runtime-bundle.tests.ps1

    Run via Pester:
        Invoke-Pester -Path browser4-apps/browser4-bundle/build-runtime-bundle.tests.ps1 -EnableExit
#>

[CmdletBinding()]
param(
    [switch]$Quiet,
    [switch]$Pester  # When invoked via Pester, skip standalone orchestration
)

$ErrorActionPreference = 'Continue'

# ═══════════════════════════════════════════════════════════════════
# Path resolution
# ═══════════════════════════════════════════════════════════════════
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$SourceScript = Join-Path $ScriptDir 'build-runtime-bundle.ps1'

if (-not (Test-Path $SourceScript)) {
    Write-Error "Source script not found: $SourceScript"
    exit 1
}

# ═══════════════════════════════════════════════════════════════════
# Assertion helpers (no-Pester, standalone-safe)
# ═══════════════════════════════════════════════════════════════════
$script:Passed   = 0
$script:Failures = 0

function Assert-Equal {
    param(
        [string]$Label,
        $Actual,
        $Expected,
        [string]$Description = ''
    )
    $passed = if ($null -eq $Actual -and $null -eq $Expected) { $true }
              elseif ($null -eq $Actual -or $null -eq $Expected) { $false }
              elseif ($Actual -is [string] -and $Expected -is [string]) { $Actual -eq $Expected }
              elseif ($Actual -is [array] -and $Expected -is [array]) {
                  ($Actual.Count -eq $Expected.Count) -and
                  (0..($Actual.Count - 1) | ForEach-Object { $Actual[$_] -eq $Expected[$_] }) -notcontains $false
              }
              else { $Actual -eq $Expected }

    if ($passed) {
        $script:Passed++
        if (-not $Quiet) { Write-Host "    ✅ $Label" -ForegroundColor Green }
    } else {
        $script:Failures++
        Write-Host "    ❌ $Label" -ForegroundColor Red
        if ($Expected -is [string] -and $Expected.Length -gt 200) {
            Write-Host "       expected: <long string, $($Expected.Length) chars>" -ForegroundColor DarkGray
        } else {
            Write-Host "       expected: $Expected" -ForegroundColor DarkGray
        }
        if ($Actual -is [string] -and $Actual.Length -gt 200) {
            Write-Host "       actual:   <long string, $($Actual.Length) chars>" -ForegroundColor DarkGray
        } else {
            Write-Host "       actual:   $Actual" -ForegroundColor DarkGray
        }
        if ($Description) { Write-Host "       $Description" -ForegroundColor DarkGray }
    }
}

function Assert-True {
    param(
        [string]$Label,
        [bool]$Condition,
        [string]$Description = ''
    )
    if ($Condition) {
        $script:Passed++
        if (-not $Quiet) { Write-Host "    ✅ $Label" -ForegroundColor Green }
    } else {
        $script:Failures++
        Write-Host "    ❌ $Label — expected true" -ForegroundColor Red
        if ($Description) { Write-Host "       $Description" -ForegroundColor DarkGray }
    }
}

function Assert-NotNull {
    param(
        [string]$Label,
        $Value,
        [string]$Description = ''
    )
    $passed = $null -ne $Value
    if ($passed) {
        $script:Passed++
        if (-not $Quiet) { Write-Host "    ✅ $Label" -ForegroundColor Green }
    } else {
        $script:Failures++
        Write-Host "    ❌ $Label — value is null" -ForegroundColor Red
        if ($Description) { Write-Host "       $Description" -ForegroundColor DarkGray }
    }
}

function Assert-Null {
    param(
        [string]$Label,
        $Value,
        [string]$Description = ''
    )
    $passed = $null -eq $Value
    if ($passed) {
        $script:Passed++
        if (-not $Quiet) { Write-Host "    ✅ $Label" -ForegroundColor Green }
    } else {
        $script:Failures++
        Write-Host "    ❌ $Label — expected null, got '$Value'" -ForegroundColor Red
        if ($Description) { Write-Host "       $Description" -ForegroundColor DarkGray }
    }
}

function Assert-Match {
    param(
        [string]$Label,
        [string]$InputString,
        [string]$Pattern,
        [string]$Description = ''
    )
    $passed = $InputString -match $Pattern
    if ($passed) {
        $script:Passed++
        if (-not $Quiet) { Write-Host "    ✅ $Label" -ForegroundColor Green }
    } else {
        $script:Failures++
        Write-Host "    ❌ $Label — pattern '$Pattern' not found" -ForegroundColor Red
        if ($Description) { Write-Host "       $Description" -ForegroundColor DarkGray }
    }
}

function Assert-Throw {
    param(
        [string]$Label,
        [ScriptBlock]$ScriptBlock,
        [string]$ExpectedMessage = ''
    )
    $threw = $false
    $errorMsg = ''
    try {
        $null = & $ScriptBlock
    } catch {
        $threw = $true
        $errorMsg = $_.Exception.Message
    }
    if ($threw) {
        $script:Passed++
        if (-not $Quiet) { Write-Host "    ✅ $Label" -ForegroundColor Green }
        if ($ExpectedMessage -and $errorMsg -notmatch [regex]::Escape($ExpectedMessage)) {
            Write-Host "       (note: message '$errorMsg' does not contain '$ExpectedMessage')" -ForegroundColor DarkYellow
        }
    } else {
        $script:Failures++
        Write-Host "    ❌ $Label — expected throw, but no exception occurred" -ForegroundColor Red
        if ($ExpectedMessage) { Write-Host "       expected message: $ExpectedMessage" -ForegroundColor DarkGray }
    }
}

# ═══════════════════════════════════════════════════════════════════
# Extract function definitions from build-runtime-bundle.ps1 via AST
# ═══════════════════════════════════════════════════════════════════
function Get-FunctionDefinitionsFromScript {
    param([string]$ScriptPath)

    $tokens = $null
    $errors = $null
    $ast = [System.Management.Automation.Language.Parser]::ParseFile(
        $ScriptPath, [ref]$tokens, [ref]$errors
    )

    if ($errors.Count -gt 0) {
        Write-Host "ERROR: Parse errors in $ScriptPath" -ForegroundColor Red
        foreach ($e in $errors) { Write-Host "  $($e.Message)" -ForegroundColor Red }
        throw "Failed to parse $ScriptPath"
    }

    $functionDefs = $ast.FindAll({
        param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst]
    }, $true)

    Write-Host "Extracted $($functionDefs.Count) function definitions from $([System.IO.Path]::GetFileName($ScriptPath))" -ForegroundColor DarkGray
    return ($functionDefs | ForEach-Object { $_.Extent.Text }) -join "`n`n"
}

# Extract and evaluate function definitions
$functionText = Get-FunctionDefinitionsFromScript -ScriptPath $SourceScript
Invoke-Expression $functionText

# Initialize _runtimeInfoAvailable so platform-detection functions
# don't fall back to Windows-only assumptions on Linux/macOS.
# (Mirrors the initialization block in build-runtime-bundle.ps1 lines 149-157.)
$script:_runtimeInfoAvailable = $false
try {
    Add-Type -AssemblyName System.Runtime.InteropServices.RuntimeInformation -ErrorAction Stop
} catch {}
try {
    $null = [System.Runtime.InteropServices.RuntimeInformation]::IsOSPlatform(
        [System.Runtime.InteropServices.OSPlatform]::Windows)
    $script:_runtimeInfoAvailable = $true
} catch {}

# ═══════════════════════════════════════════════════════════════════
# Set up shared test variables
# ═══════════════════════════════════════════════════════════════════
$TestRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("b4-rb-test-" + [System.Guid]::NewGuid().ToString('N').Substring(0, 8))
New-Item -ItemType Directory -Path $TestRoot -Force | Out-Null

function New-TempDir {
    param([string]$Name)
    $path = Join-Path $TestRoot $Name
    New-Item -ItemType Directory -Path $path -Force | Out-Null
    return $path
}

function New-TempFile {
    param([string]$Path, [string]$Content = '')
    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Set-Content -LiteralPath $Path -Value $Content -Encoding UTF8
    return $Path
}

function New-MockReleaseFile {
    param([string]$Directory, [string]$JavaVersion)
    $releasePath = Join-Path $Directory 'release'
    $content = @"
JAVA_VERSION="$JavaVersion"
OS_NAME="Windows"
OS_ARCH="amd64"
SOURCE=".:git:abcdef123456"
"@
    Set-Content -LiteralPath $releasePath -Value $content -Encoding UTF8
    return $Directory
}

Write-Host "Source : $SourceScript" -ForegroundColor DarkGray
Write-Host "Temp   : $TestRoot" -ForegroundColor DarkGray
Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Remove-ArchiveSuffix
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Remove-ArchiveSuffix ━━━" -ForegroundColor Cyan

Assert-Equal -Label 'RAS: .zip suffix' `
    -Actual (Remove-ArchiveSuffix 'browser4-bundle-runtime-windows-x64.zip') `
    -Expected 'browser4-bundle-runtime-windows-x64'

Assert-Equal -Label 'RAS: .tar.gz suffix' `
    -Actual (Remove-ArchiveSuffix 'browser4-bundle-runtime-linux-x64.tar.gz') `
    -Expected 'browser4-bundle-runtime-linux-x64'

Assert-Equal -Label 'RAS: .tar.gz (darwin-arm64)' `
    -Actual (Remove-ArchiveSuffix 'browser4-bundle-runtime-darwin-arm64.tar.gz') `
    -Expected 'browser4-bundle-runtime-darwin-arm64'

Assert-Equal -Label 'RAS: .tar.gz (darwin-x64)' `
    -Actual (Remove-ArchiveSuffix 'browser4-bundle-runtime-darwin-x64.tar.gz') `
    -Expected 'browser4-bundle-runtime-darwin-x64'

Assert-Equal -Label 'RAS: no extension' `
    -Actual (Remove-ArchiveSuffix 'my-bundle') `
    -Expected 'my-bundle'

Assert-Equal -Label 'RAS: empty string' `
    -Actual (Remove-ArchiveSuffix '') `
    -Expected ''

Assert-Equal -Label 'RAS: .zip in middle of name (not at end)' `
    -Actual (Remove-ArchiveSuffix 'my.zip.file.tar.gz') `
    -Expected 'my.zip.file'

Assert-Equal -Label 'RAS: uppercase .ZIP' `
    -Actual (Remove-ArchiveSuffix 'BUNDLE.ZIP') `
    -Expected 'BUNDLE'

Assert-Equal -Label 'RAS: mixed case .Tar.Gz' `
    -Actual (Remove-ArchiveSuffix 'bundle.Tar.Gz') `
    -Expected 'bundle'

Assert-Equal -Label 'RAS: .tar without .gz is NOT stripped' `
    -Actual (Remove-ArchiveSuffix 'bundle.tar') `
    -Expected 'bundle'  # Path.GetFileNameWithoutExtension strips .tar on .NET

# Regression: .tar.gz must be stripped as one unit, not just .gz
Assert-Equal -Label 'RAS: .tar.gz stripped as one unit' `
    -Actual (Remove-ArchiveSuffix 'app.tar.gz') `
    -Expected 'app'

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-JavaMajorVersionFromText
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-JavaMajorVersionFromText ━━━" -ForegroundColor Cyan

# JDK 21 output (the pattern: version "21.0.5")
Assert-Equal -Label 'JMV: JDK 21' `
    -Actual (Get-JavaMajorVersionFromText 'openjdk version "21.0.5" 2024-10-15 LTS') `
    -Expected 21

# JDK 17 output
Assert-Equal -Label 'JMV: JDK 17' `
    -Actual (Get-JavaMajorVersionFromText 'openjdk version "17.0.14" 2025-01-21 LTS') `
    -Expected 17

# JDK 16 output
Assert-Equal -Label 'JMV: JDK 16' `
    -Actual (Get-JavaMajorVersionFromText 'openjdk version "16.0.2" 2021-07-20') `
    -Expected 16

# GraalVM output
Assert-Equal -Label 'JMV: GraalVM 21' `
    -Actual (Get-JavaMajorVersionFromText 'openjdk version "21.0.5" 2024-10-15 LTS (GraalVM CE)') `
    -Expected 21

# Old JDK 8 format (1.8.0_302) — regex captures '1' from 'version "1.8...'
Assert-Equal -Label 'JMV: JDK 8 legacy format captures "1" from "1.8.0_302"' `
    -Actual (Get-JavaMajorVersionFromText 'java version "1.8.0_302"') `
    -Expected 1

# JDK 11
Assert-Equal -Label 'JMV: JDK 11' `
    -Actual (Get-JavaMajorVersionFromText 'openjdk version "11.0.25" 2024-10-15 LTS') `
    -Expected 11

# Non-matching text
Assert-Null -Label 'JMV: garbage input' `
    -Value (Get-JavaMajorVersionFromText 'some random text without version')

# Empty string
Assert-Null -Label 'JMV: empty string' `
    -Value (Get-JavaMajorVersionFromText '')

# Multi-line output (stderr merged with stdout)
$multiLine = @"
Picked up JAVA_TOOL_OPTIONS: -Xmx256m
openjdk version "21.0.5" 2024-10-15 LTS
OpenJDK Runtime Environment (build 21.0.5+11-LTS)
OpenJDK 64-Bit Server VM (build 21.0.5+11-LTS, mixed mode, sharing)
"@
Assert-Equal -Label 'JMV: multi-line output' `
    -Actual (Get-JavaMajorVersionFromText $multiLine) `
    -Expected 21

# Version with build number in the format: version "21.0.5+11"
Assert-Equal -Label 'JMV: version with build number' `
    -Actual (Get-JavaMajorVersionFromText 'openjdk version "21.0.5+11"') `
    -Expected 21

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-JlinkCompressValue
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-JlinkCompressValue ━━━" -ForegroundColor Cyan

Assert-Equal -Label 'JLC: JDK 21 → zip-9' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 21) `
    -Expected 'zip-9'

Assert-Equal -Label 'JLC: JDK 22 → zip-9' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 22) `
    -Expected 'zip-9'

Assert-Equal -Label 'JLC: JDK 23 → zip-9' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 23) `
    -Expected 'zip-9'

Assert-Equal -Label 'JLC: JDK 20 → 2' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 20) `
    -Expected '2'

Assert-Equal -Label 'JLC: JDK 17 → 2' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 17) `
    -Expected '2'

Assert-Equal -Label 'JLC: JDK 16 → 2' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 16) `
    -Expected '2'

Assert-Equal -Label 'JLC: JDK 21 (boundary, exactly at threshold)' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 21) `
    -Expected 'zip-9'

Assert-Equal -Label 'JLC: JDK 20 (boundary, just below threshold)' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 20) `
    -Expected '2'

Assert-Equal -Label 'JLC: null version → 2' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion $null) `
    -Expected '2'

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-JDKVersion
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-JDKVersion ━━━" -ForegroundColor Cyan

$jdkTestDir = New-TempDir 'jdk-test'

# Standard JDK 21 release file
$jdk21Dir = Join-Path $jdkTestDir 'jdk-21'
New-Item -ItemType Directory -Path $jdk21Dir -Force | Out-Null
New-MockReleaseFile -Directory $jdk21Dir -JavaVersion '21.0.5'
$ver21 = Get-JDKVersion -jdkHome $jdk21Dir
Assert-NotNull -Label 'JDKV: JDK 21 detected' -Value $ver21
Assert-Equal -Label 'JDKV: JDK 21 major' -Actual $ver21.Major -Expected 21
Assert-Equal -Label 'JDKV: JDK 21 minor' -Actual $ver21.Minor -Expected 0
Assert-Equal -Label 'JDKV: JDK 21 build' -Actual $ver21.Build -Expected 5

# JDK 17 with full version
$jdk17Dir = Join-Path $jdkTestDir 'jdk-17'
New-Item -ItemType Directory -Path $jdk17Dir -Force | Out-Null
New-MockReleaseFile -Directory $jdk17Dir -JavaVersion '17.0.14'
$ver17 = Get-JDKVersion -jdkHome $jdk17Dir
Assert-Equal -Label 'JDKV: JDK 17 major' -Actual $ver17.Major -Expected 17
Assert-Equal -Label 'JDKV: JDK 17 minor' -Actual $ver17.Minor -Expected 0
Assert-Equal -Label 'JDKV: JDK 17 build' -Actual $ver17.Build -Expected 14

# JDK 16 (minimum supported)
$jdk16Dir = Join-Path $jdkTestDir 'jdk-16'
New-Item -ItemType Directory -Path $jdk16Dir -Force | Out-Null
New-MockReleaseFile -Directory $jdk16Dir -JavaVersion '16.0.2'
$ver16 = Get-JDKVersion -jdkHome $jdk16Dir
Assert-Equal -Label 'JDKV: JDK 16 major' -Actual $ver16.Major -Expected 16

# JDK with build number (e.g. -ea, +7)
$jdkBuildDir = Join-Path $jdkTestDir 'jdk-build'
New-Item -ItemType Directory -Path $jdkBuildDir -Force | Out-Null
New-MockReleaseFile -Directory $jdkBuildDir -JavaVersion '21.0.5+7'
$verBuild = Get-JDKVersion -jdkHome $jdkBuildDir
Assert-Equal -Label 'JDKV: JDK with build number major' -Actual $verBuild.Major -Expected 21

# No release file
$emptyDir = New-TempDir 'empty-jdk'
Assert-Null -Label 'JDKV: missing release file → null' `
    -Value (Get-JDKVersion -jdkHome $emptyDir)

# Malformed release file
$badDir = New-TempDir 'bad-jdk'
Set-Content -LiteralPath (Join-Path $badDir 'release') -Value 'not a valid release file' -Encoding UTF8
Assert-Null -Label 'JDKV: malformed release file → null' `
    -Value (Get-JDKVersion -jdkHome $badDir)

# Single-digit version (JDK 17)
$jdk17bDir = Join-Path $jdkTestDir 'jdk-17b'
New-Item -ItemType Directory -Path $jdk17bDir -Force | Out-Null
New-MockReleaseFile -Directory $jdk17bDir -JavaVersion '17'
$ver17b = Get-JDKVersion -jdkHome $jdk17bDir
Assert-Equal -Label 'JDKV: single-digit JDK 17 → version 17.0.0' `
    -Actual "$($ver17b.Major).$($ver17b.Minor).$($ver17b.Build)" `
    -Expected '17.0.0'

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-BundleMetadataJson
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-BundleMetadataJson ━━━" -ForegroundColor Cyan

$metaModules = @('java.base', 'java.desktop', 'java.logging', 'jdk.crypto.ec')
$metaJson = Get-BundleMetadataJson -assetName 'test-bundle.zip' -modules $metaModules -mainClass 'com.example.Main'
Assert-NotNull -Label 'BMJ: returns non-null' -Value $metaJson

$metaObj = $metaJson | ConvertFrom-Json
Assert-Equal -Label 'BMJ: assetName' -Actual $metaObj.assetName -Expected 'test-bundle.zip'
Assert-Equal -Label 'BMJ: mainClass' -Actual $metaObj.mainClass -Expected 'com.example.Main'
Assert-Equal -Label 'BMJ: runtimeDirectoryName' -Actual $metaObj.runtimeDirectoryName -Expected 'runtime'
Assert-Equal -Label 'BMJ: libDirectoryName' -Actual $metaObj.libDirectoryName -Expected 'lib'
Assert-Equal -Label 'BMJ: pluginsDirectoryName' -Actual $metaObj.pluginsDirectoryName -Expected 'plugins'
Assert-Equal -Label 'BMJ: modules count' -Actual $metaObj.modules.Count -Expected 4
Assert-Equal -Label 'BMJ: modules[0]' -Actual $metaObj.modules[0] -Expected 'java.base'
Assert-Equal -Label 'BMJ: modules[3]' -Actual $metaObj.modules[3] -Expected 'jdk.crypto.ec'
Assert-True -Label 'BMJ: builtAtUtc is not empty' `
    -Condition (-not [string]::IsNullOrWhiteSpace($metaObj.builtAtUtc))
# The 'o' format specifier produces ISO 8601 but ConvertTo-Json may
# serialize DateTime with culture-specific formatting depending on PS version.
$builtAt = $metaObj.builtAtUtc
Assert-True -Label 'BMJ: builtAtUtc contains year 2026' `
    -Condition ($builtAt -match '2026') `
    -Description "Actual value: $builtAt"

# Edge: empty modules list
$metaEmpty = Get-BundleMetadataJson -assetName 'empty.zip' -modules @() -mainClass 'com.example.Empty' | ConvertFrom-Json
Assert-Equal -Label 'BMJ: empty modules array' -Actual $metaEmpty.modules.Count -Expected 0

# Edge: empty main class
$metaNoMain = Get-BundleMetadataJson -assetName 'no-main.zip' -modules @('java.base') -mainClass '' | ConvertFrom-Json
Assert-Equal -Label 'BMJ: empty mainClass preserved' -Actual $metaNoMain.mainClass -Expected ''

# Edge: special characters in assetName
$metaSpecial = Get-BundleMetadataJson -assetName 'browser4-bundle-runtime-windows-x64.zip' -modules @('java.base') -mainClass 'com.example.Main' | ConvertFrom-Json
Assert-Equal -Label 'BMJ: standard asset name' `
    -Actual $metaSpecial.assetName `
    -Expected 'browser4-bundle-runtime-windows-x64.zip'

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Convert-ToExtendedLengthPath
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Convert-ToExtendedLengthPath ━━━" -ForegroundColor Cyan

# These tests are meaningful on Windows; on Linux/macOS the function
# is a pass-through, so assertions adapt accordingly.
$isWin = $IsWindows -or ($env:OS -eq 'Windows_NT')

if ($isWin) {
    # Normal path gets \\?\ prefix
    $normalPath = 'C:\Program Files\Java\jdk-21'
    $extended = Convert-ToExtendedLengthPath $normalPath
    Assert-True -Label 'CTEL: normal path gets \\?\ prefix' `
        -Condition ($extended.StartsWith('\\?\'))

    # Already extended path is unchanged
    $alreadyExtended = '\\?\C:\Program Files\Java'
    $result = Convert-ToExtendedLengthPath $alreadyExtended
    Assert-Equal -Label 'CTEL: already-extended path unchanged' `
        -Actual $result -Expected $alreadyExtended

    # UNC path gets \\?\UNC\ prefix
    $uncPath = '\\server\share\path'
    $extendedUnc = Convert-ToExtendedLengthPath $uncPath
    Assert-True -Label 'CTEL: UNC path gets \\?\UNC\ prefix' `
        -Condition ($extendedUnc.StartsWith('\\?\UNC\'))

    # Null/empty passthrough — PowerShell [string] cast converts $null to ''
    Assert-Equal -Label 'CTEL: null → empty string (PS [string] cast)' `
        -Actual (Convert-ToExtendedLengthPath $null) -Expected ''
    Assert-Equal -Label 'CTEL: empty → empty' `
        -Actual (Convert-ToExtendedLengthPath '') -Expected ''

    # Relative path → becomes absolute with prefix
    $relativePath = 'some\relative\path'
    $extendedRel = Convert-ToExtendedLengthPath $relativePath
    Assert-True -Label 'CTEL: relative path gets prefix' `
        -Condition ($extendedRel.StartsWith('\\?\'))

    # Path with trailing spaces (preserved)
    $trailingSpacePath = 'C:\temp\dir '
    $extended2 = Convert-ToExtendedLengthPath $trailingSpacePath
    Assert-True -Label 'CTEL: path with trailing space works' `
        -Condition ($extended2.StartsWith('\\?\'))
}

if (-not $isWin) {
    # On Linux/macOS, the function returns the path unchanged
    Assert-Equal -Label 'CTEL: Linux returns path unchanged' `
        -Actual (Convert-ToExtendedLengthPath '/usr/lib/jvm/jdk-21') `
        -Expected '/usr/lib/jvm/jdk-21'
    Assert-Equal -Label 'CTEL: null passthrough on Linux' `
        -Actual (Convert-ToExtendedLengthPath $null) -Expected $null
}

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Resolve-InputPath
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Resolve-InputPath ━━━" -ForegroundColor Cyan

$baseDir = if ($isWin) { 'C:\workspace\browser4' } else { '/home/user/browser4' }

# Null/empty passthrough — PowerShell [string] cast converts $null to ''
Assert-Equal -Label 'RIP: null → empty string (PS [string] cast)' `
    -Actual (Resolve-InputPath -path $null -baseDirectory $baseDir) -Expected ''
Assert-Equal -Label 'RIP: empty → empty' `
    -Actual (Resolve-InputPath -path '' -baseDirectory $baseDir) -Expected ''

# Absolute path
$absPath = if ($isWin) { 'C:\Program Files\Java\jdk-21' } else { '/usr/lib/jvm/jdk-21' }
$resolved = Resolve-InputPath -path $absPath -baseDirectory $baseDir
Assert-True -Label 'RIP: absolute path is resolved' `
    -Condition ($resolved -match [regex]::Escape($absPath))

# Relative path
$relPath = if ($isWin) { 'target\runtime-bundle' } else { 'target/runtime-bundle' }
$resolvedRel = Resolve-InputPath -path $relPath -baseDirectory $baseDir
Assert-True -Label 'RIP: relative path joined with base' `
    -Condition ($resolvedRel -match [regex]::Escape($relPath))

# Dot path (current directory)
$resolvedDot = Resolve-InputPath -path '.' -baseDirectory $baseDir
Assert-NotNull -Label 'RIP: dot path resolves' -Value $resolvedDot

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-PathSeparator
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-PathSeparator ━━━" -ForegroundColor Cyan

$sep = Get-PathSeparator
if ($isWin) {
    Assert-Equal -Label 'GPS: Windows → semicolon' -Actual $sep -Expected ';'
} else {
    Assert-Equal -Label 'GPS: Unix → colon' -Actual $sep -Expected ':'
}

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Write-LaunchScripts
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Write-LaunchScripts ━━━" -ForegroundColor Cyan

$launchTestDir = New-TempDir 'launch-scripts'
$mainClass = 'ai.platon.pulsar.apps.Browser4BundleApplicationKt'
Write-LaunchScripts -bundleDirectory $launchTestDir -mainClass $mainClass

# Check bin/ directory exists
$binDir = Join-Path $launchTestDir 'bin'
Assert-True -Label 'WLS: bin/ directory created' `
    -Condition (Test-Path $binDir -PathType Container)

# Check start.sh
$startSh = Join-Path $binDir 'start.sh'
Assert-True -Label 'WLS: start.sh created' `
    -Condition (Test-Path $startSh -PathType Leaf)

$shContent = Get-Content -LiteralPath $startSh -Raw
Assert-Match -Label 'WLS: start.sh has shebang' `
    -InputString $shContent -Pattern '^#!/bin/bash'
Assert-Match -Label 'WLS: start.sh contains main class' `
    -InputString $shContent -Pattern ([regex]::Escape($mainClass))
Assert-Match -Label 'WLS: start.sh references runtime/bin/java' `
    -InputString $shContent -Pattern 'runtime/bin/java'
Assert-Match -Label 'WLS: start.sh uses lib/\* classpath' `
    -InputString $shContent -Pattern 'lib/\*'
Assert-Match -Label 'WLS: start.sh references plugins/\*' `
    -InputString $shContent -Pattern 'plugins/\*'
Assert-Match -Label 'WLS: start.sh has set -e' `
    -InputString $shContent -Pattern 'set -e'
# Verify no Windows-style CRLF in shebang line (first line only)
$firstLine = ($shContent -split "`n")[0]
Assert-True -Label 'WLS: start.sh shebang line has no CR' `
    -Condition ($firstLine -notmatch "`r")

# Check start.bat
$startBat = Join-Path $binDir 'start.bat'
Assert-True -Label 'WLS: start.bat created' `
    -Condition (Test-Path $startBat -PathType Leaf)

$batContent = Get-Content -LiteralPath $startBat -Raw
Assert-Match -Label 'WLS: start.bat has @echo off' `
    -InputString $batContent -Pattern '@echo off'
Assert-Match -Label 'WLS: start.bat contains main class' `
    -InputString $batContent -Pattern ([regex]::Escape($mainClass))
Assert-Match -Label 'WLS: start.bat references runtime\bin\java' `
    -InputString $batContent -Pattern 'runtime\\bin\\java'
Assert-Match -Label 'WLS: start.bat uses lib\\\* classpath' `
    -InputString $batContent -Pattern 'lib\\\*'
Assert-Match -Label 'WLS: start.bat references plugins\\\*' `
    -InputString $batContent -Pattern 'plugins\\\*'

# Different main class
$launchTestDir2 = New-TempDir 'launch-scripts2'
Write-LaunchScripts -bundleDirectory $launchTestDir2 -mainClass 'com.example.MyApp'
$shContent2 = Get-Content -LiteralPath (Join-Path $launchTestDir2 'bin/start.sh') -Raw
Assert-Match -Label 'WLS: custom main class in start.sh' `
    -InputString $shContent2 -Pattern 'com.example.MyApp'

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Test-ValidJar
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Test-ValidJar ━━━" -ForegroundColor Cyan

# Create a minimal valid ZIP/JAR file
$jarTestDir = New-TempDir 'jar-test'

# Valid JAR (minimal ZIP with PK signature)
$validJar = Join-Path $jarTestDir 'valid.jar'
# A minimal valid ZIP file: PK\x03\x04 + central directory + end record
$zipBytes = [byte[]]@(
    0x50, 0x4B, 0x03, 0x04,  # Local file header signature
    0x0A, 0x00,              # Version needed
    0x00, 0x00,              # General purpose bit flag
    0x00, 0x00,              # Compression method (stored)
    0x00, 0x00,              # Last mod time
    0x00, 0x00,              # Last mod date
    0x00, 0x00, 0x00, 0x00, # CRC-32
    0x00, 0x00, 0x00, 0x00, # Compressed size
    0x00, 0x00, 0x00, 0x00, # Uncompressed size
    0x00, 0x00,              # File name length
    0x00, 0x00,              # Extra field length
    # Central directory
    0x50, 0x4B, 0x01, 0x02,  # Central directory header
    0x0A, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    # End of central directory
    0x50, 0x4B, 0x05, 0x06,  # End of central directory signature
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
    0x00, 0x00, 0x00, 0x00, 0x00, 0x00
)
# Use .NET for binary write (Set-Content -Encoding Byte not available on all PS versions)
[System.IO.File]::WriteAllBytes($validJar, $zipBytes)

Assert-True -Label 'TVJ: valid ZIP is detected as valid JAR' `
    -Condition (Test-ValidJar $validJar)

# Invalid JAR (not a ZIP file)
$invalidJar = Join-Path $jarTestDir 'invalid.jar'
Set-Content -LiteralPath $invalidJar -Value 'This is not a valid JAR file' -Encoding UTF8
Assert-True -Label 'TVJ: invalid file returns false' `
    -Condition (-not (Test-ValidJar $invalidJar))

# Non-existent JAR — function catches the exception and returns $false
$missingJar = Join-Path $jarTestDir 'missing.jar'
Assert-True -Label 'TVJ: missing file returns false (catches internally)' `
    -Condition (-not (Test-ValidJar $missingJar))

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Read-ManifestAttribute
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Read-ManifestAttribute ━━━" -ForegroundColor Cyan

# Create a valid JAR with a META-INF/MANIFEST.MF entry
$manifestTestDir = New-TempDir 'manifest-test'

function New-JarWithManifest {
    param([string]$OutputPath, [string]$ManifestContent)

    Add-Type -AssemblyName System.IO.Compression.FileSystem

    # Create the JAR in a temp file first
    $tmp = $OutputPath + '.tmp'
    $archive = [System.IO.Compression.ZipFile]::Open($tmp, 'Create')
    try {
        $entry = $archive.CreateEntry('META-INF/MANIFEST.MF')
        $stream = $entry.Open()
        $writer = [System.IO.StreamWriter]::new($stream)
        try {
            $writer.Write($manifestContent)
        } finally {
            $writer.Dispose()
            $stream.Dispose()
        }
    } finally {
        $archive.Dispose()
    }

    if (Test-Path $OutputPath) { Remove-Item $OutputPath -Force }
    Rename-Item -LiteralPath $tmp -NewName (Split-Path -Leaf $OutputPath) -Force
}

# JAR with Main-Class
$jarWithMain = Join-Path $manifestTestDir 'with-main.jar'
$manifestMainClass = @"
Manifest-Version: 1.0
Created-By: Maven JAR Plugin 3.3.0
Main-Class: com.example.Application
"@
New-JarWithManifest -OutputPath $jarWithMain -ManifestContent $manifestMainClass
Assert-Equal -Label 'RMA: reads Main-Class' `
    -Actual (Read-ManifestAttribute -jarPath $jarWithMain -attributeName 'Main-Class') `
    -Expected 'com.example.Application'

# JAR with Start-Class (Spring Boot convention)
$jarWithStart = Join-Path $manifestTestDir 'with-start.jar'
$manifestStartClass = @"
Manifest-Version: 1.0
Start-Class: com.example.SpringApp
Main-Class: org.springframework.boot.loader.JarLauncher
"@
New-JarWithManifest -OutputPath $jarWithStart -ManifestContent $manifestStartClass
Assert-Equal -Label 'RMA: reads Start-Class' `
    -Actual (Read-ManifestAttribute -jarPath $jarWithStart -attributeName 'Start-Class') `
    -Expected 'com.example.SpringApp'
Assert-Equal -Label 'RMA: reads Main-Class alongside Start-Class' `
    -Actual (Read-ManifestAttribute -jarPath $jarWithStart -attributeName 'Main-Class') `
    -Expected 'org.springframework.boot.loader.JarLauncher'

# JAR with continuation lines (folded manifest values)
$jarWithFolded = Join-Path $manifestTestDir 'with-folded.jar'
$manifestFolded = @"
Manifest-Version: 1.0
Class-Path: lib/dependency-a.jar
 lib/dependency-b.jar
 lib/dependency-c.jar
Main-Class: com.example.Main
"@
New-JarWithManifest -OutputPath $jarWithFolded -ManifestContent $manifestFolded
# Continuation lines are joined by Substring(1), stripping the leading space.
# The actual concatenation is: 'lib/dependency-a.jar' + 'lib/dependency-b.jar' (no space).
Assert-Equal -Label 'RMA: handles folded Class-Path (joins continuation lines)' `
    -Actual (Read-ManifestAttribute -jarPath $jarWithFolded -attributeName 'Class-Path') `
    -Expected 'lib/dependency-a.jarlib/dependency-b.jarlib/dependency-c.jar'

# JAR without the requested attribute
Assert-Null -Label 'RMA: missing attribute returns null' `
    -Value (Read-ManifestAttribute -jarPath $jarWithMain -attributeName 'NonExistent')

# JAR without META-INF/MANIFEST.MF
$jarNoManifest = Join-Path $manifestTestDir 'no-manifest.jar'
# Create minimal valid ZIP without MANIFEST.MF
$tmp2 = $jarNoManifest + '.tmp'
$archive2 = [System.IO.Compression.ZipFile]::Open($tmp2, 'Create')
try {
    $entry = $archive2.CreateEntry('some/other/file.txt')
    $s = $entry.Open()
    $w = [System.IO.StreamWriter]::new($s)
    try { $w.Write('content') } finally { $w.Dispose(); $s.Dispose() }
} finally { $archive2.Dispose() }
if (Test-Path $jarNoManifest) { Remove-Item $jarNoManifest -Force }
Rename-Item -LiteralPath $tmp2 -NewName (Split-Path -Leaf $jarNoManifest) -Force
Assert-Null -Label 'RMA: JAR without manifest returns null' `
    -Value (Read-ManifestAttribute -jarPath $jarNoManifest -attributeName 'Main-Class')

# Manifest attribute with trailing spaces
$jarWithSpace = Join-Path $manifestTestDir 'with-space.jar'
$manifestSpace = @"
Manifest-Version: 1.0
Main-Class: com.example.SpacedApp
"@
New-JarWithManifest -OutputPath $jarWithSpace -ManifestContent $manifestSpace
Assert-Equal -Label 'RMA: trims trailing spaces from attribute value' `
    -Actual (Read-ManifestAttribute -jarPath $jarWithSpace -attributeName 'Main-Class') `
    -Expected 'com.example.SpacedApp'

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-DefaultMainClass
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-DefaultMainClass ━━━" -ForegroundColor Cyan

# JAR with Start-Class (Spring Boot) → prefer Start-Class
Assert-Equal -Label 'DMC: Start-Class takes priority' `
    -Actual (Get-DefaultMainClass -jarPath $jarWithStart) `
    -Expected 'com.example.SpringApp'

# JAR with only Main-Class
Assert-Equal -Label 'DMC: falls back to Main-Class' `
    -Actual (Get-DefaultMainClass -jarPath $jarWithMain) `
    -Expected 'com.example.Application'

# JAR without any standard attribute → use Browser4 default
Assert-Equal -Label 'DMC: no attribute → Browser4 default' `
    -Actual (Get-DefaultMainClass -jarPath $jarNoManifest) `
    -Expected 'ai.platon.pulsar.apps.Browser4BundleApplicationKt'

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-JdepsClassPath
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-JdepsClassPath ━━━" -ForegroundColor Cyan

$jdepsTestDir = New-TempDir 'jdeps-cp-test'

# Directory with JARs
$jdepsLibDir = Join-Path $jdepsTestDir 'lib'
New-Item -ItemType Directory -Path $jdepsLibDir -Force | Out-Null

# Create a valid JAR file
$jcpJar1 = Join-Path $jdepsLibDir 'test-lib.jar'
[System.IO.File]::WriteAllBytes($jcpJar1, $zipBytes)

$jcpResult = Get-JdepsClassPath -libDirectory $jdepsLibDir
Assert-NotNull -Label 'JCP: returns non-null for populated lib' -Value $jcpResult
Assert-Match -Label 'JCP: uses wildcard classpath (*)' `
    -InputString $jcpResult -Pattern 'lib/\*'
Assert-Match -Label 'JCP: uses forward slashes' `
    -InputString $jcpResult -Pattern '/'

# Empty directory
$jdepsEmptyDir = New-TempDir 'jdeps-empty'
Assert-Null -Label 'JCP: empty dir returns null' `
    -Value (Get-JdepsClassPath -libDirectory $jdepsEmptyDir)

# Directory with only invalid JARs
$jdepsBadDir = New-TempDir 'jdeps-bad'
$badJar = Join-Path $jdepsBadDir 'bad.jar'
Set-Content -LiteralPath $badJar -Value 'not a jar' -Encoding UTF8
Assert-Null -Label 'JCP: only invalid JARs → null' `
    -Value (Get-JdepsClassPath -libDirectory $jdepsBadDir)

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Get-AssetNameForCurrentPlatform
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Get-AssetNameForCurrentPlatform ━━━" -ForegroundColor Cyan

$assetName = Get-AssetNameForCurrentPlatform
Assert-NotNull -Label 'GAN: returns non-null' -Value $assetName

if ($isWin) {
    Assert-Equal -Label 'GAN: Windows → .zip' `
        -Actual $assetName -Expected 'browser4-bundle-runtime-windows-x64.zip'
} elseif ($IsLinux) {
    Assert-Equal -Label 'GAN: Linux → .tar.gz' `
        -Actual $assetName -Expected 'browser4-bundle-runtime-linux-x64.tar.gz'
} elseif ($IsMacOS) {
    Assert-True -Label 'GAN: macOS → .tar.gz' `
        -Condition ($assetName -match 'darwin')
    Assert-True -Label 'GAN: macOS → platform-specific name' `
        -Condition ($assetName -match 'arm64' -or $assetName -match 'x64')
}

# Validate the overall format
Assert-True -Label 'GAN: starts with browser4-bundle-runtime' `
    -Condition ($assetName.StartsWith('browser4-bundle-runtime'))
Assert-True -Label 'GAN: contains OS identifier' `
    -Condition ($assetName -match 'windows|linux|darwin')

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Platform detection functions
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Platform detection ━━━" -ForegroundColor Cyan

$isWinResult = Get-IsWindows
$isLinuxResult = Get-IsLinux
$isMacResult = Get-IsMacOS
$archResult = Get-OSArchitecture

# Exactly one platform must be true
$platformCount = [int]$isWinResult + [int]$isLinuxResult + [int]$isMacResult
Assert-Equal -Label 'PLAT: exactly one platform is true' -Actual $platformCount -Expected 1

# Architecture must be a known value
Assert-True -Label 'PLAT: architecture is known' `
    -Condition ($archResult -match 'X64|X86|Arm|Arm64')

# Platform-specific assertions
if ($isWin) {
    Assert-True -Label 'PLAT: Windows detection matches environment' `
        -Condition $isWinResult
}
if ($IsLinux) {
    Assert-True -Label 'PLAT: Linux detection matches environment' `
        -Condition $isLinuxResult
}
if ($IsMacOS) {
    Assert-True -Label 'PLAT: macOS detection matches environment' `
        -Condition $isMacResult
}

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Remove-IfExists (integration with real temp directory)
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Remove-IfExists ━━━" -ForegroundColor Cyan

$rieTestDir = New-TempDir 'remove-if-exists'

# Remove existing directory
$rieSubDir = Join-Path $rieTestDir 'to-remove'
New-Item -ItemType Directory -Path $rieSubDir -Force | Out-Null
New-TempFile (Join-Path $rieSubDir 'file.txt') 'content'
Remove-IfExists $rieSubDir
Assert-True -Label 'RIE: removes existing directory' `
    -Condition (-not (Test-Path $rieSubDir))

# Remove non-existent path (should not throw)
try {
    Remove-IfExists (Join-Path $rieTestDir 'nonexistent')
    Assert-True -Label 'RIE: non-existent path does not throw' -Condition $true
} catch {
    Assert-True -Label 'RIE: non-existent path does not throw' -Condition $false
}

# Remove single file
$rieFile = Join-Path $rieTestDir 'single-file.txt'
New-TempFile $rieFile 'content'
Remove-IfExists $rieFile
Assert-True -Label 'RIE: removes single file' `
    -Condition (-not (Test-Path $rieFile))

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Ensure-CleanDirectory
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Ensure-CleanDirectory ━━━" -ForegroundColor Cyan

$ecdTestDir = New-TempDir 'ensure-clean'

# Create fresh directory
$ecdFresh = Join-Path $ecdTestDir 'fresh'
Ensure-CleanDirectory $ecdFresh
Assert-True -Label 'ECD: creates directory' `
    -Condition (Test-Path $ecdFresh -PathType Container)

# Clean existing directory with content
$ecdExisting = Join-Path $ecdTestDir 'existing'
New-Item -ItemType Directory -Path $ecdExisting -Force | Out-Null
New-TempFile (Join-Path $ecdExisting 'old-file.txt') 'old content'
New-Item -ItemType Directory -Path (Join-Path $ecdExisting 'subdir') -Force | Out-Null
Ensure-CleanDirectory $ecdExisting
Assert-True -Label 'ECD: directory still exists after cleaning' `
    -Condition (Test-Path $ecdExisting -PathType Container)
$remaining = Get-ChildItem -Path $ecdExisting -ErrorAction SilentlyContinue
Assert-Equal -Label 'ECD: directory is empty after cleaning' `
    -Actual $remaining.Count -Expected 0

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Remove-SafeRuntimePayload
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Remove-SafeRuntimePayload ━━━" -ForegroundColor Cyan

$payloadTestDir = New-TempDir 'runtime-payload'
$runtimeRoot = Join-Path $payloadTestDir 'runtime'
New-Item -ItemType Directory -Path $runtimeRoot -Force | Out-Null

# Create a structure mimicking a jlink output
$binDir = Join-Path $runtimeRoot 'bin'
$libDir = Join-Path $runtimeRoot 'lib'
New-Item -ItemType Directory -Path $binDir -Force | Out-Null
New-Item -ItemType Directory -Path $libDir -Force | Out-Null

# Files that SHOULD be removed
$shouldRemove = @(
    'bin/jdeps.exe', 'bin/jlink.exe', 'bin/javac.exe', 'bin/jpackage.exe'
)
foreach ($f in $shouldRemove) {
    New-TempFile (Join-Path $runtimeRoot $f) 'dummy'
}

# Files that should NOT be removed
$shouldKeep = @(
    'bin/java.exe', 'bin/server.dll', 'lib/modules'
)
foreach ($f in $shouldKeep) {
    $keepPath = Join-Path $runtimeRoot $f
    $keepDir = Split-Path -Parent $keepPath
    if (-not (Test-Path $keepDir)) { New-Item -ItemType Directory -Path $keepDir -Force | Out-Null }
    New-TempFile $keepPath 'keep me'
}

Remove-SafeRuntimePayload $runtimeRoot

foreach ($f in $shouldRemove) {
    Assert-True -Label "RSRP: removed $f" `
        -Condition (-not (Test-Path (Join-Path $runtimeRoot $f)))
}

foreach ($f in $shouldKeep) {
    Assert-True -Label "RSRP: kept $f" `
        -Condition (Test-Path (Join-Path $runtimeRoot $f))
}

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Edge cases and boundary conditions
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Edge cases & boundaries ━━━" -ForegroundColor Cyan

# Get-JlinkCompressValue with very high version
Assert-Equal -Label 'EDGE: JLC with JDK 999 → zip-9' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 999) -Expected 'zip-9'

# Get-JlinkCompressValue with version 0
Assert-Equal -Label 'EDGE: JLC with version 0 → 2' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion 0) -Expected '2'

# Get-JlinkCompressValue with negative version
Assert-Equal -Label 'EDGE: JLC with negative version → 2' `
    -Actual (Get-JlinkCompressValue -javaMajorVersion -1) -Expected '2'

# Remove-ArchiveSuffix with only extension
Assert-Equal -Label 'EDGE: RAS with .zip only' `
    -Actual (Remove-ArchiveSuffix '.zip') -Expected ''

# Remove-ArchiveSuffix with only .tar.gz
Assert-Equal -Label 'EDGE: RAS with .tar.gz only' `
    -Actual (Remove-ArchiveSuffix '.tar.gz') -Expected ''

# Remove-ArchiveSuffix with double suffix
Assert-Equal -Label 'EDGE: RAS with .zip.zip → strip one .zip' `
    -Actual (Remove-ArchiveSuffix 'bundle.zip.zip') -Expected 'bundle.zip'

# Remove-ArchiveSuffix with embedded archive name
Assert-Equal -Label 'EDGE: RAS with path-like name' `
    -Actual (Remove-ArchiveSuffix 'releases/browser4-bundle-runtime-windows-x64.zip') `
    -Expected 'releases/browser4-bundle-runtime-windows-x64'

# Get-JavaMajorVersionFromText with JDK 8 nested version string — regex captures '1'
Assert-Equal -Label 'EDGE: JMV with "1.8.0_302" captures major version 1' `
    -Actual (Get-JavaMajorVersionFromText 'java version "1.8.0_302"') `
    -Expected 1

# Get-BundleMetadataJson module dedup (caller handles dedup, but JSON should be valid)
$dupModules = @('java.base', 'java.base', 'java.desktop')
$metaDup = Get-BundleMetadataJson -assetName 'dup.zip' -modules $dupModules -mainClass 'com.example.Main' | ConvertFrom-Json
Assert-Equal -Label 'EDGE: duplicates preserved in JSON (called handles dedup)' `
    -Actual $metaDup.modules.Count -Expected 3  # Raw modules passed through

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# TESTS: Integration — Write-LaunchScripts + validate structure
# ═══════════════════════════════════════════════════════════════════
Write-Host "━━━ Integration: bundle structure ━━━" -ForegroundColor Cyan

$intTestDir = New-TempDir 'integration'
$intBundleDir = Join-Path $intTestDir 'my-bundle'
New-Item -ItemType Directory -Path $intBundleDir -Force | Out-Null

# Create minimal bundle structure
$intRuntimeDir = Join-Path $intBundleDir 'runtime'
$intBinDir = Join-Path $intRuntimeDir 'bin'
$intLibDir = Join-Path $intBundleDir 'lib'
$intPluginsDir = Join-Path $intBundleDir 'plugins'
foreach ($d in @($intRuntimeDir, $intBinDir, $intLibDir, $intPluginsDir)) {
    New-Item -ItemType Directory -Path $d -Force | Out-Null
}

# Write launch scripts
Write-LaunchScripts -bundleDirectory $intBundleDir -mainClass 'com.example.IntApp'

# Write metadata
$intMeta = Get-BundleMetadataJson -assetName 'integration-test.zip' `
    -modules @('java.base', 'java.desktop') `
    -mainClass 'com.example.IntApp'
Set-Content -LiteralPath (Join-Path $intBundleDir 'runtime-bundle.json') -Value $intMeta -Encoding UTF8

# Verify complete structure
$checks = @(
    @{ Label = 'int: runtime/ dir';      Path = $intRuntimeDir; Type = 'Container' },
    @{ Label = 'int: lib/ dir';          Path = $intLibDir; Type = 'Container' },
    @{ Label = 'int: plugins/ dir';      Path = $intPluginsDir; Type = 'Container' },
    @{ Label = 'int: bin/ dir';          Path = (Join-Path $intBundleDir 'bin'); Type = 'Container' },
    @{ Label = 'int: start.sh';          Path = (Join-Path $intBundleDir 'bin/start.sh'); Type = 'Leaf' },
    @{ Label = 'int: start.bat';         Path = (Join-Path $intBundleDir 'bin/start.bat'); Type = 'Leaf' },
    @{ Label = 'int: runtime-bundle.json'; Path = (Join-Path $intBundleDir 'runtime-bundle.json'); Type = 'Leaf' }
)
foreach ($check in $checks) {
    Assert-True -Label $check.Label `
        -Condition (Test-Path -LiteralPath $check.Path -PathType $check.Type)
}

# Validate JSON can be parsed
$parsed = Get-Content -LiteralPath (Join-Path $intBundleDir 'runtime-bundle.json') -Raw | ConvertFrom-Json
Assert-Equal -Label 'int: parsed metadata mainClass' `
    -Actual $parsed.mainClass -Expected 'com.example.IntApp'
Assert-Equal -Label 'int: parsed metadata runtimeDirectoryName' `
    -Actual $parsed.runtimeDirectoryName -Expected 'runtime'

Write-Host ''

# ═══════════════════════════════════════════════════════════════════
# Cleanup
# ═══════════════════════════════════════════════════════════════════
try {
    Remove-Item -LiteralPath $TestRoot -Recurse -Force -ErrorAction SilentlyContinue
} catch {
    Write-Host "  Warning: Could not clean up $TestRoot" -ForegroundColor DarkYellow
}

# ═══════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════
$total = $script:Passed + $script:Failures
Write-Host ''
Write-Host "========================================" -ForegroundColor $(if ($script:Failures -eq 0) { 'Green' } else { 'Red' })
Write-Host "  Test Results: $($script:Passed) passed, $($script:Failures) failed ($total total)" `
    -ForegroundColor $(if ($script:Failures -eq 0) { 'Green' } else { 'Red' })
Write-Host "========================================" -ForegroundColor $(if ($script:Failures -eq 0) { 'Green' } else { 'Red' })

if ($script:Failures -gt 0) {
    Write-Host "`nSource: $SourceScript" -ForegroundColor DarkGray
    exit 1
}
exit 0
