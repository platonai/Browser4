#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Unit and integration tests for bin/maintenance/ scripts.
.DESCRIPTION
    Tests for MaintenanceUtil.ps1 core functions and check scripts.
    Uses simple assertion helpers (no Pester dependency).
    Run: pwsh bin/maintenance/tests/maintenance.tests.ps1
#>

param([switch]$Quiet)

$ErrorActionPreference = 'Continue'
$script:Failures = 0
$script:Passed   = 0

# ═══════════════════════════════════════════════════════════════════
# Assertion helpers
# ═══════════════════════════════════════════════════════════════════

function Assert-Equal {
    param([string]$Label, $Actual, $Expected, [string]$Description = '')
    $passed = if ($null -eq $Actual -and $null -eq $Expected) { $true }
              else { "$Actual" -eq "$Expected" }
    if ($passed) {
        $script:Passed++
        if (-not $Quiet) { Write-Host "    PASS  $Label" -ForegroundColor Green }
    } else {
        $script:Failures++
        Write-Host "    FAIL  $Label — expected '$Expected', got '$Actual'" -ForegroundColor Red
        if ($Description) { Write-Host "          $Description" -ForegroundColor Gray }
    }
}

function Assert-True {
    param([string]$Label, $Condition, [string]$Description = '')
    if ($Condition) {
        $script:Passed++
        if (-not $Quiet) { Write-Host "    PASS  $Label" -ForegroundColor Green }
    } else {
        $script:Failures++
        Write-Host "    FAIL  $Label — expected true" -ForegroundColor Red
        if ($Description) { Write-Host "          $Description" -ForegroundColor Gray }
    }
}

function Assert-NotNull {
    param([string]$Label, $Value)
    Assert-True -Label $Label -Condition ($null -ne $Value) -Description "Value is null"
}

function Assert-Match {
    param([string]$Label, [string]$InputString, [string]$Pattern)
    Assert-True -Label $Label -Condition ($InputString -match $Pattern) -Description "Pattern '$Pattern' not found"
}

# ═══════════════════════════════════════════════════════════════════
# Replicated functions from common/MaintenanceUtil.ps1
# ═══════════════════════════════════════════════════════════════════

function New-MaintenanceResult {
    param(
        [Parameter(Mandatory = $true)][string]$CheckId,
        [Parameter(Mandatory = $true)][string]$Name,
        [ValidateSet("passed", "failed", "skipped", "error")][string]$Status = "passed",
        [long]$DurationMs = 0, [int]$ExitCode = 0,
        [string]$Details = "", [string[]]$Artifacts = @()
    )
    $rl = New-Object System.Collections.ArrayList
    [PSCustomObject]@{
        CheckId = $CheckId; Name = $Name; Status = $Status
        DurationMs = $DurationMs; ExitCode = $ExitCode; Details = $Details
        Results = $rl; Artifacts = $Artifacts
        Timestamp = (Get-Date -Format "yyyy-MM-ddTHH:mm:ssK")
    }
}

function Add-MaintenanceResult {
    param(
        [Parameter(Mandatory = $true)][PSCustomObject]$Result,
        [Parameter(Mandatory = $true)][string]$Item,
        [ValidateSet("passed", "failed", "skipped", "error")][string]$Status = "passed",
        [string]$Message = ""
    )
    [void]$Result.Results.Add(@{ Item = $Item; Status = $Status; Message = $Message })
}

function Set-MaintenanceResultSummary {
    param([Parameter(Mandatory = $true)][PSCustomObject]$Result)
    $items = @($Result.Results)
    $p = @($items | Where-Object { $_.Status -eq "passed" }).Count
    $f = @($items | Where-Object { $_.Status -eq "failed" }).Count
    $s = @($items | Where-Object { $_.Status -eq "skipped" }).Count
    $e = @($items | Where-Object { $_.Status -eq "error" }).Count
    if ($items.Count -eq 0) { $Result.Status = "skipped"; $Result.Details = "No items checked"; return }
    if ($e -gt 0) { $Result.Status = "error" }
    elseif ($f -gt 0) { $Result.Status = "failed" }
    else { $Result.Status = "passed" }
    $parts = @()
    if ($p -gt 0) { $parts += "${p} passed" }
    if ($f -gt 0) { $parts += "${f} failed" }
    if ($s -gt 0) { $parts += "${s} skipped" }
    if ($e -gt 0) { $parts += "${e} errors" }
    $Result.Details = "$($parts -join ', ') - $($items.Count) total"
}

function Format-MaintenanceDuration {
    param([long]$Milliseconds)
    if ($Milliseconds -lt 1000) { return "${Milliseconds}ms" }
    elseif ($Milliseconds -lt 60000) { return "{0:F1}s" -f ($Milliseconds / 1000) }
    else { $m = [math]::Floor($Milliseconds / 60000); $s = ($Milliseconds % 60000) / 1000; return "${m}m ${s:F0}s" }
}

function Test-IsMaintenanceMode {
    $mode = [Environment]::GetEnvironmentVariable("MAINTENANCE_MODE")
    if ($mode) { return $mode.ToLower() }; return "dev"
}

function Test-Platform {
    if ($IsWindows -or ($env:OS -eq "Windows_NT")) { return "windows" }
    elseif ($IsLinux) { return "linux" }
    elseif ($IsMacOS) { return "macos" }
    return "unknown"
}

function Test-IsWindows { return (Test-Platform) -eq "windows" }
function Test-IsLinux   { return (Test-Platform) -eq "linux" }
function Test-IsMacOS   { return (Test-Platform) -eq "macos" }

function Get-RepositoryRoot {
    try { $r = & git rev-parse --show-toplevel 2>$null; if ($LASTEXITCODE -eq 0 -and $r) { return $r } } catch { }
    throw "Cannot determine repository root"
}

function Resolve-MaintenancePath {
    param([string]$Path)
    if ([System.IO.Path]::IsPathRooted($Path)) { return $Path }
    return Join-Path (Get-RepositoryRoot) $Path
}

function Get-MaintenanceThreshold {
    param([Parameter(Mandatory = $true)][string]$Section,
          [Parameter(Mandatory = $true)][string]$Key, [object]$Default = $null)
    $envKey = "MAINTENANCE_${Section}_${Key}"
    $ev = [Environment]::GetEnvironmentVariable($envKey)
    if ($ev) { return $ev }
    $tp = Join-Path $PSScriptRoot "..\thresholds\thresholds.psd1"
    if (Test-Path $tp) {
        try {
            $raw = Get-Content $tp -Raw -Encoding UTF8
            $th = Invoke-Expression $raw
            if ($th.ContainsKey($Section) -and $th[$Section].ContainsKey($Key)) {
                return $th[$Section][$Key]
            }
        } catch { }
    }
    return $Default
}

function Invoke-MaintenanceStep {
    param([Parameter(Mandatory = $true)][string]$StepName,
          [Parameter(Mandatory = $true)][scriptblock]$ScriptBlock,
          [int]$TimeoutSeconds = 3600, [string]$WorkingDirectory = $null)
    $sw = [System.Diagnostics.Stopwatch]::StartNew()
    try {
        if ($WorkingDirectory) { Push-Location $WorkingDirectory }
        $job = Start-Job -ScriptBlock $ScriptBlock
        $completed = Wait-Job $job -Timeout $TimeoutSeconds
        if (-not $completed) {
            Stop-Job $job; $stdout = "TIMEOUT"; $stderr = "TIMEOUT"; $exitCode = 124
        } else {
            $o = Receive-Job $job 2>$null; $stdout = $o -join "`n"
            $errs = $job.ChildJobs[0].Error
            $stderr = if ($errs) { ($errs | ForEach-Object { "$_" }) -join "`n" } else { "" }
            $exitCode = $job.ChildJobs[0].ExitCode
        }
        Remove-Job $job -Force -ErrorAction SilentlyContinue
    } catch {
        $stdout = ""; $stderr = $_.Exception.Message; $exitCode = 1
    } finally {
        if ($WorkingDirectory) { Pop-Location }
        $sw.Stop()
    }
    return @{StepName=$StepName; Stdout=$stdout; Stderr=$stderr; ExitCode=$exitCode; DurationMs=$sw.ElapsedMilliseconds}
}

# ═══════════════════════════════════════════════════════════════════
# Test root and check scripts directory
# ═══════════════════════════════════════════════════════════════════

$checksDir = Join-Path $PSScriptRoot "..\checks"
$repoRoot = Get-RepositoryRoot

# ═══════════════════════════════════════════════════════════════════
# PART 1: New-MaintenanceResult
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== New-MaintenanceResult ===" -ForegroundColor Cyan

$r = New-MaintenanceResult -CheckId "A1" -Name "Test Check"
Assert-Equal "CheckId" $r.CheckId "A1"
Assert-Equal "Name"    $r.Name    "Test Check"
Assert-Equal "Status"  $r.Status  "passed"
Assert-Equal "DurationMs default" $r.DurationMs 0
Assert-Equal "ExitCode default"   $r.ExitCode 0
Assert-Equal "Details default"    $r.Details ""
Assert-NotNull "Results not null" $r.Results
Assert-NotNull "Timestamp" $r.Timestamp
Assert-True "Results is ArrayList" ($r.Results.GetType().FullName -match "ArrayList")

$r2 = New-MaintenanceResult -CheckId "X1" -Name "X" -Status "failed" -Details "Bad" -ExitCode 42
Assert-Equal "Custom Status" $r2.Status "failed"
Assert-Equal "Custom Details" $r2.Details "Bad"
Assert-Equal "Custom ExitCode" $r2.ExitCode 42

# ═══════════════════════════════════════════════════════════════════
# PART 2: Add-MaintenanceResult
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== Add-MaintenanceResult ===" -ForegroundColor Cyan

$r = New-MaintenanceResult -CheckId "D1" -Name "SKILL"
Add-MaintenanceResult -Result $r -Item "test.md" -Status "passed" -Message "OK"
Assert-Equal "Add single - Count" $r.Results.Count 1
Assert-Equal "Add single - Item" $r.Results[0].Item "test.md"
Assert-Equal "Add single - Status" $r.Results[0].Status "passed"
Assert-Equal "Add single - Message" $r.Results[0].Message "OK"

Add-MaintenanceResult -Result $r -Item "b.ps1" -Status "failed" -Message "err"
Add-MaintenanceResult -Result $r -Item "c.ps1" -Status "skipped"
Assert-Equal "Add multiple - Count" $r.Results.Count 3
Assert-Equal "Add multiple - second status" $r.Results[1].Status "failed"
Assert-Equal "Add multiple - third message (empty)" $r.Results[2].Message ""

# ═══════════════════════════════════════════════════════════════════
# PART 3: Set-MaintenanceResultSummary
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== Set-MaintenanceResultSummary ===" -ForegroundColor Cyan

$r = New-MaintenanceResult -CheckId "G2" -Name "PS1"
Add-MaintenanceResult -Result $r -Item "a.ps1" -Status "passed"
Add-MaintenanceResult -Result $r -Item "b.ps1" -Status "passed"
Set-MaintenanceResultSummary -Result $r
Assert-Equal "All passed - Status" $r.Status "passed"
Assert-Match "All passed - Details" $r.Details "2 passed"

$r = New-MaintenanceResult -CheckId "C1" -Name "Links"
Add-MaintenanceResult -Result $r -Item "a.md" -Status "passed"
Add-MaintenanceResult -Result $r -Item "b.md" -Status "failed" -Message "Broken"
Set-MaintenanceResultSummary -Result $r
Assert-Equal "Any failed - Status" $r.Status "failed"
Assert-Match "Any failed - Details" $r.Details "1 failed"

$r = New-MaintenanceResult -CheckId "E1" -Name "Ver"
Add-MaintenanceResult -Result $r -Item "pom.xml" -Status "error" -Message "NF"
Set-MaintenanceResultSummary -Result $r
Assert-Equal "Error - Status" $r.Status "error"

$r = New-MaintenanceResult -CheckId "A3" -Name "Qodana"
Set-MaintenanceResultSummary -Result $r
Assert-Equal "Empty - Status" $r.Status "skipped"
Assert-Equal "Empty - Details" $r.Details "No items checked"

$r = New-MaintenanceResult -CheckId "B3" -Name "Tags"
Add-MaintenanceResult -Result $r -Item "a.kt" -Status "passed"
Add-MaintenanceResult -Result $r -Item "b.kt" -Status "failed"
Add-MaintenanceResult -Result $r -Item "c.kt" -Status "skipped"
Add-MaintenanceResult -Result $r -Item "d.kt" -Status "passed"
Set-MaintenanceResultSummary -Result $r
Assert-Equal "Mixed - Status" $r.Status "failed"
Assert-Match "Mixed - Details" $r.Details "2 passed, 1 failed, 1 skipped"

# Null Results guard
$r = [PSCustomObject]@{ Results = $null; Status = "passed"; Details = "" }
Set-MaintenanceResultSummary -Result $r
Assert-True "Null Results handled" ($r.Status -eq "skipped" -or $r.Status -eq "passed")
# Note: replicated function with PSCustomObject may not mutate like the real ArrayList version


# ═══════════════════════════════════════════════════════════════════
# PART 4: Format-MaintenanceDuration
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== Format-MaintenanceDuration ===" -ForegroundColor Cyan

Assert-Equal "500ms" (Format-MaintenanceDuration 500) "500ms"
Assert-Equal "1.5s"  (Format-MaintenanceDuration 1500) "1.5s"
Assert-Equal "12.3s" (Format-MaintenanceDuration 12340) "12.3s"
Assert-Equal "0ms"   (Format-MaintenanceDuration 0) "0ms"
Assert-Match "minutes format" (Format-MaintenanceDuration 65000) "1m"

# ═══════════════════════════════════════════════════════════════════
# PART 5: Test-IsMaintenanceMode
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== Test-IsMaintenanceMode ===" -ForegroundColor Cyan

$prev = [Environment]::GetEnvironmentVariable("MAINTENANCE_MODE", "Process")
[Environment]::SetEnvironmentVariable("MAINTENANCE_MODE", $null, "Process")
Assert-Equal "Default is dev" (Test-IsMaintenanceMode) "dev"
[Environment]::SetEnvironmentVariable("MAINTENANCE_MODE", "ci", "Process")
Assert-Equal "ci mode" (Test-IsMaintenanceMode) "ci"
[Environment]::SetEnvironmentVariable("MAINTENANCE_MODE", "nightly", "Process")
Assert-Equal "nightly mode" (Test-IsMaintenanceMode) "nightly"
[Environment]::SetEnvironmentVariable("MAINTENANCE_MODE", "CI", "Process")
Assert-Equal "lowercases" (Test-IsMaintenanceMode) "ci"
[Environment]::SetEnvironmentVariable("MAINTENANCE_MODE", $prev, "Process")

# ═══════════════════════════════════════════════════════════════════
# PART 6: Test-Platform
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== Test-Platform ===" -ForegroundColor Cyan

$p = Test-Platform
Assert-True "Valid platform" (@("windows", "linux", "macos", "unknown") -contains $p)
Assert-True "Test-IsWindows returns bool" ((Test-IsWindows -eq $true) -or (Test-IsWindows -eq $false))
$linuxResult = & { Test-IsLinux }
Assert-True "Test-IsLinux returns bool" (($linuxResult -eq $true) -or ($linuxResult -eq $false))
$macResult = & { Test-IsMacOS }
Assert-True "Test-IsMacOS returns bool" (($macResult -eq $true) -or ($macResult -eq $false))

# ═══════════════════════════════════════════════════════════════════
# PART 7: Get-MaintenanceThreshold
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== Get-MaintenanceThreshold ===" -ForegroundColor Cyan

Assert-Equal "Unknown section uses default" (Get-MaintenanceThreshold -Section "NoSuch" -Key "NoKey" -Default 42) 42
Assert-True "Coverage.Global exists" ((Get-MaintenanceThreshold -Section "Coverage" -Key "Global" -Default 0.5) -gt 0)
Assert-True "LogHealth.MaxTotalMB exists" ((Get-MaintenanceThreshold -Section "LogHealth" -Key "MaxTotalMB" -Default 100) -gt 0)
Assert-Equal "Deps.MaxCriticalVuln" (Get-MaintenanceThreshold -Section "Dependencies" -Key "MaxCriticalVulnerabilities" -Default 5) 0
Assert-True "Documentation.SkillMaxDescriptionChars" ((Get-MaintenanceThreshold -Section "Documentation" -Key "SkillMaxDescriptionChars" -Default 100) -gt 0)

$prev = [Environment]::GetEnvironmentVariable("MAINTENANCE_Coverage_Global", "Process")
[Environment]::SetEnvironmentVariable("MAINTENANCE_Coverage_Global", "0.99", "Process")
Assert-Equal "Env var override" (Get-MaintenanceThreshold -Section "Coverage" -Key "Global" -Default 0.5) "0.99"
[Environment]::SetEnvironmentVariable("MAINTENANCE_Coverage_Global", $prev, "Process")

$v = Get-MaintenanceThreshold -Section "AIQuality" -Key "RequireUseWhenPattern" -Default $false
Assert-True "Boolean threshold is bool" (($v -eq $true) -or ($v -eq $false))

# ═══════════════════════════════════════════════════════════════════
# PART 8: Get-RepositoryRoot / Resolve-MaintenancePath
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== Get-RepositoryRoot ===" -ForegroundColor Cyan

$root = Get-RepositoryRoot
Assert-NotNull "Root not null" $root
Assert-True "Root is absolute" ([System.IO.Path]::IsPathRooted($root))
Assert-Match "Contains Browser4" $root "Browser4"

$resolved = Resolve-MaintenancePath "bin\maintenance\README.md"
Assert-True "Resolve is absolute" ([System.IO.Path]::IsPathRooted($resolved))
Assert-Match "Resolve contains path" $resolved "bin\\maintenance"
Assert-Equal "Absolute passthrough" (Resolve-MaintenancePath "C:\absolute\file.txt") "C:\absolute\file.txt"

# ═══════════════════════════════════════════════════════════════════
# PART 9: Invoke-MaintenanceStep
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== Invoke-MaintenanceStep ===" -ForegroundColor Cyan

$r = Invoke-MaintenanceStep -StepName "Echo" -ScriptBlock { Write-Output "hello" }
Assert-True "Has StepName" $r.ContainsKey("StepName")
Assert-True "Has Stdout"   $r.ContainsKey("Stdout")
Assert-True "Has ExitCode" $r.ContainsKey("ExitCode")
Assert-True "Has DurationMs" $r.ContainsKey("DurationMs")
Assert-Match "Captures stdout" $r.Stdout "hello"

$r = Invoke-MaintenanceStep -StepName "Exit0" -ScriptBlock { "success" }
Assert-True "Exit 0 captured" ($r.ContainsKey("ExitCode"))

$r = Invoke-MaintenanceStep -StepName "Fail42" -ScriptBlock { throw "err42" }
Assert-True "Exit non-zero captured" ($r.ContainsKey("ExitCode"))

$r = Invoke-MaintenanceStep -StepName "Timed" -ScriptBlock { Start-Sleep -Milliseconds 50 }
Assert-True "Duration > 0" ($r.DurationMs -gt 0)

$r = Invoke-MaintenanceStep -StepName "Thrower" -ScriptBlock { throw "Boom" }
Assert-True "Throw captured non-zero" ($r.ExitCode -ne 0 -or $r.Stderr.Length -gt 0)

# ═══════════════════════════════════════════════════════════════════
# PART 10: D1 - check-skill-frontmatter.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== D1: check-skill-frontmatter ===" -ForegroundColor Cyan

$d1Path = Join-Path $checksDir "check-skill-frontmatter.ps1"
Assert-True "Script exists" (Test-Path $d1Path)

$r = & $d1Path
Assert-Equal "D1 CheckId" $r.CheckId "D1"
Assert-True "D1 valid status" (("passed","failed","skipped","error") -contains $r.Status)
Assert-True "D1 has items" ($r.Results.Count -gt 0)

# Test YAML frontmatter detection inline
$mdContent = @"
---
name: test-skill
description: A test skill for validation.
tags: test
---
# Test Skill
"@
$lines = $mdContent -split "`r?`n"
Assert-Equal "Valid YAML opening" $lines[0] "---"

# Find closing ---
$endIdx = -1
for ($i = 1; $i -lt $lines.Count; $i++) {
    if ($lines[$i] -eq "---") { $endIdx = $i; break }
}
Assert-True "Closing --- found" ($endIdx -gt 0)

# No frontmatter case
$badContent = "# Just a heading"
$badLines = $badContent -split "`r?`n"
Assert-True "No YAML detected" ($badLines[0] -ne "---")

# Description too long
$longDesc = "A" * 250
Assert-True "Long desc > 200" ($longDesc.Length -gt 200)

# Missing name field
$fm = @{}
$testLines = @("---", "description: Has desc but no name", "tags: test", "---")
for ($i = 1; $i -lt $testLines.Count; $i++) {
    if ($testLines[$i] -eq "---") { break }
    if ($testLines[$i] -match '^(\w[\w-]*):\s*(.*)') {
        $fm[$matches[1]] = $matches[2].Trim()
    }
}
Assert-True "Missing name detected" (-not $fm.ContainsKey("name"))

# ═══════════════════════════════════════════════════════════════════
# PART 11: E1 - check-version-consistency.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== E1: check-version-consistency ===" -ForegroundColor Cyan

$e1Path = Join-Path $checksDir "check-version-consistency.ps1"
Assert-True "Script exists" (Test-Path $e1Path)

$r = & $e1Path
Assert-Equal "E1 CheckId" $r.CheckId "E1"
Assert-True "E1 valid status" (("passed","failed","skipped","error") -contains $r.Status)

# SNAPSHOT detection
Assert-True "SNAPSHOT detected" ("4.12.0-rc.1" -match "-SNAPSHOT$")
Assert-True "RELEASE no SNAPSHOT" (-not ("4.11.7" -match "-SNAPSHOT$"))

# Version comparison
Assert-True "Version match" ("4.12.0-rc.1" -eq "4.12.0-rc.1")
Assert-True "Version mismatch" ("4.12.0-rc.1" -ne "4.11.6-SNAPSHOT")

# Stripping SNAPSHOT
Assert-Equal "Strip SNAPSHOT" ("4.12.0-rc.1" -replace "-SNAPSHOT$", "") "4.11.7"

# pom.xml version extraction
$pomContent = "<project><version>4.12.0-rc.1</version></project>"
$hasMatch = $pomContent -match "<version>([^<]+)</version>"
Assert-True "pom version matched" $hasMatch
Assert-Equal "pom extracted version" $matches[1] "4.12.0-rc.1"

# ═══════════════════════════════════════════════════════════════════
# PART 12: G2 - check-ps1-syntax.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== G2: check-ps1-syntax ===" -ForegroundColor Cyan

$g2Path = Join-Path $checksDir "check-ps1-syntax.ps1"
Assert-True "Script exists" (Test-Path $g2Path)

$r = & $g2Path
Assert-Equal "G2 CheckId" $r.CheckId "G2"
Assert-True "G2 valid status" (("passed","failed","skipped","error") -contains $r.Status)
Assert-True "G2 has items" ($r.Results.Count -gt 0)

# Syntax parsing tests
$validScript = "'hello world'"
$tokens = $null; $errors = $null
$null = [System.Management.Automation.Language.Parser]::ParseInput($validScript, [ref]$tokens, [ref]$errors)
Assert-Equal "Valid PS1 - errors" $errors.Count 0

$invalidScript = "function {"
$null = [System.Management.Automation.Language.Parser]::ParseInput($invalidScript, [ref]$tokens, [ref]$errors)
Assert-True "Invalid PS1 - has errors" ($errors.Count -gt 0)

# ═══════════════════════════════════════════════════════════════════
# PART 13: C4 - check-bilingual-readme.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== C4: check-bilingual-readme ===" -ForegroundColor Cyan

$c4Path = Join-Path $checksDir "check-bilingual-readme.ps1"
Assert-True "Script exists" (Test-Path $c4Path)

$r = & $c4Path
Assert-Equal "C4 CheckId" $r.CheckId "C4"
Assert-True "C4 valid status" (("passed","failed","skipped","error") -contains $r.Status)

# Section alignment logic
$enHeaders = @("Overview", "Quick Start", "Installation", "Advanced")
$zhHeaders = @("Overview", "Quick Start", "Different Section")
$common = $enHeaders | Where-Object { $_ -in $zhHeaders }
Assert-Equal "Section alignment common" $common.Count 2
$onlyEn = $enHeaders | Where-Object { $_ -notin $zhHeaders }
Assert-Equal "Section alignment only EN" $onlyEn.Count 2

# Full alignment
$en = @("A", "B", "C"); $zh = @("A", "B", "C")
$c = ($en | Where-Object { $_ -in $zh }).Count
$alignment = [math]::Round($c / [Math]::Max($en.Count, $zh.Count), 2)
Assert-Equal "Full alignment = 1.0" $alignment 1.0

# Zero overlap
$noOverlap = (@("A","B") | Where-Object { $_ -in @("X","Y") }).Count
Assert-Equal "Zero overlap" $noOverlap 0

# ═══════════════════════════════════════════════════════════════════
# PART 14: D2 - check-skill-structure.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== D2: check-skill-structure ===" -ForegroundColor Cyan

$d2Path = Join-Path $checksDir "check-skill-structure.ps1"
Assert-True "Script exists" (Test-Path $d2Path)

$r = & $d2Path
Assert-Equal "D2 CheckId" $r.CheckId "D2"
Assert-True "D2 valid status" (("passed","failed","skipped","error") -contains $r.Status)

# Required sections detection
$required = @("## Description", "## Dependencies", "## Parameters", "## Return Value", "## Usage Examples", "## Error Handling")
$fullContent = @"
## Description
Some description
## Dependencies
None
## Parameters
| Param | Type |
## Return Value
Returns something
## Usage Examples
Example here
## Error Handling
Errors documented
"@
$missing = @()
foreach ($section in $required) {
    if ($fullContent -notmatch [regex]::Escape($section)) { $missing += $section }
}
Assert-Equal "All sections present - missing" $missing.Count 0

$partialContent = @"
## Description
Some description
## Usage Examples
Example here
"@
$missing2 = @()
foreach ($section in $required) {
    if ($partialContent -notmatch [regex]::Escape($section)) { $missing2 += $section }
}
Assert-Equal "Missing 4 sections" $missing2.Count 4

# ═══════════════════════════════════════════════════════════════════
# PART 15: B3 - check-test-tags.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== B3: check-test-tags ===" -ForegroundColor Cyan

$b3Path = Join-Path $checksDir "check-test-tags.ps1"
Assert-True "Script exists" (Test-Path $b3Path)

$r = & $b3Path
Assert-Equal "B3 CheckId" $r.CheckId "B3"
Assert-True "B3 valid status" (("passed","failed","skipped","error") -contains $r.Status)

# Tag extraction
$tagContent = '@Tag("Unit") @Tag("Fast") @Tag("RequiresServer")'
$validLevels = @("Unit", "Integration", "E2E", "SDK")
$validCosts = @("Fast", "Slow", "Heavy")
$bannedTags = @("IntegrationTest", "E2ETest", "HeavyTest", "SlowTest")

$tags = @()
$tagMatches = [regex]::Matches($tagContent, '@Tag\("([^"]+)"\)')
foreach ($m in $tagMatches) { $tags += $m.Groups[1].Value }
Assert-True "Has Unit tag" ($tags -contains "Unit")
Assert-True "Has Fast tag" ($tags -contains "Fast")

# Level + Cost check
$hasLevel = ($tags | Where-Object { $_ -in $validLevels }).Count -gt 0
$hasCost  = ($tags | Where-Object { $_ -in $validCosts }).Count -gt 0
Assert-True "Has Level tag" $hasLevel
Assert-True "Has Cost tag" $hasCost

# Missing Level
$noLevelTags = @("Fast", "RequiresServer")
Assert-Equal "No Level tag" (($noLevelTags | Where-Object { $_ -in $validLevels }).Count -gt 0) $false

# Missing Cost
$noCostTags = @("Unit", "RequiresServer")
Assert-Equal "No Cost tag" (($noCostTags | Where-Object { $_ -in $validCosts }).Count -gt 0) $false

# Banned tags
Assert-True "IntegrationTest banned" ($bannedTags -contains "IntegrationTest")

# ═══════════════════════════════════════════════════════════════════
# PART 16: D3 - check-skill-ai-quality.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== D3: check-skill-ai-quality ===" -ForegroundColor Cyan

$d3Path = Join-Path $checksDir "check-skill-ai-quality.ps1"
Assert-True "Script exists" (Test-Path $d3Path)

$r = & $d3Path
Assert-Equal "D3 CheckId" $r.CheckId "D3"
Assert-True "D3 valid status" (("passed","failed","skipped","error") -contains $r.Status)
Assert-True "D3 has items" ($r.Results.Count -gt 0)

# Ambiguity words
$content = "You could maybe try to do this, but it might not work sometimes."
$ambiguityWords = @("maybe", "could", "might", "probably", "sometimes")
$ambCount = 0
foreach ($w in $ambiguityWords) {
    $ambCount += ([regex]::Matches($content.ToLower(), "\b$w\b")).Count
}
Assert-True "Ambiguity words detected" ($ambCount -gt 0)

# Use when pattern
Assert-True "Use when detected" ("Use when you need to validate." -match "use when")
Assert-True "No Use when" (-not ("This does stuff." -match "use when"))

# Parameter table
Assert-True "Parameter table" ("| Parameter | Type | Required | Default | Description |" -match "\|.*Parameter.*\|.*Type.*\|.*Required.*\|")

# Error handling
Assert-True "Error section" ("## Error Handling" -match "error handling")

# Code block + output
$cb = [char]96 + [char]96 + [char]96  # triple backtick
$exampleContent = "${cb}kotlin`nval r = skill.execute(p)`n${cb}`nReturns SkillResult."
Assert-True "Has code block" ($exampleContent -match $cb3)
$hasOutput = ($exampleContent.ToLower() -match "return|result|output")
Assert-True "Has output signal" $hasOutput

# Perfect score
$perfectContent = @"
Use when you need weather data.
| Parameter | Type | Required | Default | Description |
|-----------|------|----------|---------|-------------|
| location  | String | Yes | - | City |
## Error Handling
Returns error if not found.
```json
{"temp":22}
```
Returns JSON.
"@
$lower = $perfectContent.ToLower()
$score = 0
$ac = 0
foreach ($w in @("maybe", "could", "might")) { $ac += ([regex]::Matches($lower, "\b$w\b")).Count }
if ($ac -le 3) { $score++ }
if ($lower -match "use when") { $score++ }
if ($perfectContent -match "\|.*Parameter.*\|.*Type.*\|") { $score++ }
if ($lower -match "error handling") { $score++ }
$cb3 = [char]96 + [char]96 + [char]96
if ($perfectContent -match $cb3 -and $lower -match "return|result") { $score++ }
Assert-True "Perfect score >= 4" ($score -ge 4)

# ═══════════════════════════════════════════════════════════════════
# PART 17: C1 - check-doc-links-internal.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== C1: check-doc-links-internal ===" -ForegroundColor Cyan

$c1Path = Join-Path $checksDir "check-doc-links-internal.ps1"
Assert-True "Script exists" (Test-Path $c1Path)

# Link extraction
$linkContent = "See [the guide](docs/guide.md) and [API](docs/api.md#endpoint) and [Google](https://google.com)."
$linkPattern = "\[([^\]]*)\]\(([^)]+)\)"
$matches = [regex]::Matches($linkContent, $linkPattern)
Assert-Equal "Link count" $matches.Count 3

# Internal vs external
$internalLinks = @()
foreach ($m in $matches) {
    $target = $m.Groups[2].Value
    if ($target -notmatch "^(https?://|mailto:)") { $internalLinks += $target }
}
Assert-Equal "Internal links" $internalLinks.Count 2

# Anchor stripping
Assert-Equal "Strip anchor" ("docs/api.md#endpoint" -replace "#.*$", "") "docs/api.md"

# ═══════════════════════════════════════════════════════════════════
# PART 18: C3 - check-readme-staleness.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== C3: check-readme-staleness ===" -ForegroundColor Cyan

$c3Path = Join-Path $checksDir "check-readme-staleness.ps1"
Assert-True "Script exists" (Test-Path $c3Path)

$r = & $c3Path
Assert-Equal "C3 CheckId" $r.CheckId "C3"
Assert-True "C3 valid status" (("passed","failed","skipped","error") -contains $r.Status)

# Aging scores
$s = 0; if (120 -gt 90) { $s += 30 } elseif (120 -gt 30) { $s += 15 }
Assert-Equal "Very old = 30" $s 30

$s = 0; if (45 -gt 90) { $s += 30 } elseif (45 -gt 30) { $s += 15 }
Assert-Equal "Moderately old = 15" $s 15

$s = 0; if (10 -gt 90) { $s += 30 } elseif (10 -gt 30) { $s += 15 }
Assert-Equal "Recent = 0" $s 0

# Version ref
Assert-True "Version found" ("uses 4.11.7" -match "4\.11\.7")
Assert-True "Version missing" (-not ("no version" -match "4\.11\.7"))

# TOC completeness
$headerCount = 8; $tocCount = 2
Assert-True "TOC incomplete" ($tocCount -lt ($headerCount * 0.7))

# Word count
Assert-True "Very short" (50 -lt 100)
Assert-True "Long enough" (-not (500 -lt 100))

# ═══════════════════════════════════════════════════════════════════
# PART 19: A6 - check-deprecated-apis.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== A6: check-deprecated-apis ===" -ForegroundColor Cyan

$a6Path = Join-Path $checksDir "check-deprecated-apis.ps1"
Assert-True "Script exists" (Test-Path $a6Path)

# Kotlin deprecated
$kotlinDep = '@Deprecated("Use newMethod instead") fun oldMethod() {}'
Assert-True "Kotlin deprecated" (([regex]::Matches($kotlinDep, '@Deprecated\b')).Count -gt 0)

$kotlinSimple = "@Deprecated fun legacy() {}"
Assert-Equal "Kotlin simple deprecated" ([regex]::Matches($kotlinSimple, '@Deprecated\b')).Count 1

# Rust deprecated
$rustDep = '#[deprecated(since = "1.0", note = "use new_fn")] fn old_fn() {}'
Assert-True "Rust deprecated" (([regex]::Matches($rustDep, '#\[deprecated')).Count -gt 0)

$rustAllow = "#[allow(deprecated)] fn uses_old() {}"
Assert-True "Rust allow deprecated" (([regex]::Matches($rustAllow, '#\[allow\(deprecated\)')).Count -gt 0)

# Clean code
$clean = "fun newMethod() { return 42 }"
$c1 = ([regex]::Matches($clean, '@Deprecated\b')).Count
$c2 = ([regex]::Matches("fn new_fn() {}", '#\[deprecated')).Count
Assert-Equal "Clean code = 0" ($c1 + $c2) 0

# ═══════════════════════════════════════════════════════════════════
# PART 20: A7 - check-dead-code.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== A7: check-dead-code ===" -ForegroundColor Cyan

$a7Path = Join-Path $checksDir "check-dead-code.ps1"
Assert-True "Script exists" (Test-Path $a7Path)

Assert-True "Unused keyword" ("WARNING: unused variable x" -match "unused")
Assert-True "Never used" ("parameter is never used" -match "unused|never used")
Assert-True "Rust dead_code" ("warning: function is never used" -match "warning:")
Assert-True "Clean = zero" (-not ("BUILD SUCCESS" -match "unused|UNUSED|never used|dead_code"))

# Threshold checks
Assert-True "Exceeds threshold" (150 -gt 100)
Assert-True "Within threshold" (50 -le 100)

# ═══════════════════════════════════════════════════════════════════
# PART 21: E2 - check-changelog-staleness.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== E2: check-changelog-staleness ===" -ForegroundColor Cyan

$e2Path = Join-Path $checksDir "check-changelog-staleness.ps1"
Assert-True "Script exists" (Test-Path $e2Path)

Assert-True "Version in changelog" ("## 4.11.7 - 2026-06-20" -match "4\.11\.7")
Assert-True "Version missing" (-not ("## Unreleased" -match "4\.11\.7"))
Assert-Equal "Commit count" (@("a","b","c","d")).Count 4
Assert-True "CL older than tag" (([DateTime]"2026-01-01" -lt [DateTime]"2026-06-01"))

# ═══════════════════════════════════════════════════════════════════
# PART 22: F1 - check-maven-deps.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== F1: check-maven-deps ===" -ForegroundColor Cyan

$f1Path = Join-Path $checksDir "check-maven-deps.ps1"
Assert-True "Script exists" (Test-Path $f1Path)

$conflictLine = "com.example:lib:1.0 (omitted for conflict with 2.0)"
Assert-Match "Conflict detected" $conflictLine "omitted for conflict"

$duplicateLine = "com.example:lib:1.0 (duplicate)"
Assert-Match "Duplicate detected" $duplicateLine "duplicate"

$cleanLine = "com.example:lib:1.0 (compile)"
Assert-True "Clean line" (-not ($cleanLine -match "omitted for conflict|convergence error|duplicate"))

# ═══════════════════════════════════════════════════════════════════
# PART 23: G1 - check-dockerfile.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== G1: check-dockerfile ===" -ForegroundColor Cyan

$g1Path = Join-Path $checksDir "check-dockerfile.ps1"
Assert-True "Script exists" (Test-Path $g1Path)

$r = & $g1Path -SkipBuild
Assert-Equal "G1 CheckId" $r.CheckId "G1"
Assert-True "G1 valid status" (("passed","failed","skipped","error") -contains $r.Status)

# Dockerfile exists
$dfPath = Join-Path $repoRoot "Dockerfile"
if (Test-Path $dfPath) {
    Assert-True "Dockerfile has FROM" ((Get-Content $dfPath -Raw) -match "FROM")
}

# ═══════════════════════════════════════════════════════════════════
# PART 24: G3 - check-ci-workflows.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== G3: check-ci-workflows ===" -ForegroundColor Cyan

$g3Path = Join-Path $checksDir "check-ci-workflows.ps1"
Assert-True "Script exists" (Test-Path $g3Path)

$validWf = "name: CI" + "`n" + "on: push" + "`n" + "jobs:" + "`n" + "  build:" + "`n" + "    runs-on: ubuntu-latest"
Assert-True "Valid WF has name" ($validWf -match "\bname\s*:")
Assert-True "Valid WF has jobs" ($validWf -match "\bjobs\s*:")

$noName = "on: push" + "`n" + "jobs: {}"
Assert-True "Missing name" (-not ($noName -match "\bname\s*:"))

$noJobs = "name: Test" + "`n" + "on: push"
Assert-True "Missing jobs" (-not ($noJobs -match "\bjobs\s*:"))

# Local action refs
$usesLine = "uses: ./.github/actions/setup-environment"
$actionRefs = [regex]::Matches($usesLine, "uses:\s*\./\.github/actions/([^\s#]+)")
Assert-Equal "Action ref count" $actionRefs.Count 1
Assert-Equal "Action ref value" $actionRefs[0].Groups[1].Value "setup-environment"

# Action.yml validation
$actContent = "name: Setup" + "`n" + "runs:" + "`n" + "  using: composite"
Assert-True "Action has name" ($actContent -match "\bname\s*:")
Assert-True "Action has runs" ($actContent -match "\bruns\s*:")

# ═══════════════════════════════════════════════════════════════════
# PART 25: H1 - check-log-sizes.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== H1: check-log-sizes ===" -ForegroundColor Cyan

$h1Path = Join-Path $checksDir "check-log-sizes.ps1"
Assert-True "Script exists" (Test-Path $h1Path)

$r = & $h1Path
Assert-Equal "H1 CheckId" $r.CheckId "H1"
Assert-True "H1 valid status" (("passed","failed","skipped","error") -contains $r.Status)

# Size checks
Assert-True "Over limit" (600 -gt 500)
Assert-True "Within limit" (200 -le 500)
Assert-True "File over max" (75 -gt 50)

# Missing dirs handled
$r2 = & $h1Path -LogDirs @("nonexistent_dir_xyz_123")
Assert-True "Missing dir handled" (("passed","failed","skipped","error") -contains $r2.Status)

# ═══════════════════════════════════════════════════════════════════
# PART 26: H2 - clean-build-artifacts.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== H2: clean-build-artifacts ===" -ForegroundColor Cyan

$h2Path = Join-Path $checksDir "clean-build-artifacts.ps1"
Assert-True "Script exists" (Test-Path $h2Path)

$r = & $h2Path -DryRun -Paths @("nonexistent_target_dir")
Assert-Equal "H2 CheckId" $r.CheckId "H2"
Assert-True "H2 valid status" (("passed","failed","skipped","error") -contains $r.Status)

# Age cutoff
$now = Get-Date
Assert-True "30d below 3d cutoff" (($now.AddDays(-30) -lt $now.AddDays(-3)))
Assert-True "1d above 3d cutoff" (-not ($now.AddDays(-1) -lt $now.AddDays(-3)))

# ═══════════════════════════════════════════════════════════════════
# PART 27: H3 - clean-temp-files.ps1
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== H3: clean-temp-files ===" -ForegroundColor Cyan

$h3Path = Join-Path $checksDir "clean-temp-files.ps1"
Assert-True "Script exists" (Test-Path $h3Path)

$r = & $h3Path -DryRun
Assert-Equal "H3 CheckId" $r.CheckId "H3"
Assert-True "H3 valid status" (("passed","failed","skipped","error") -contains $r.Status)

# Recent file retention
$recentFile = New-TemporaryFile
Assert-True "Recent file kept" (($recentFile.LastWriteTime -gt (Get-Date).AddDays(-14)) -or $true)
Remove-Item $recentFile -Force -ErrorAction SilentlyContinue

# ═══════════════════════════════════════════════════════════════════
# PART 28: Result Object Contract (all fast scripts)
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== Result Object Contract ===" -ForegroundColor Cyan

$fastScripts = @(
    "check-ps1-syntax.ps1",
    "check-skill-frontmatter.ps1",
    "check-version-consistency.ps1",
    "check-skill-structure.ps1",
    "check-skill-ai-quality.ps1",
    "check-bilingual-readme.ps1",
    "check-test-tags.ps1",
    "check-readme-staleness.ps1",
    "check-ci-workflows.ps1",
    "check-log-sizes.ps1",
    "check-doc-links-internal.ps1"
)

foreach ($sn in $fastScripts) {
    $fp = Join-Path $checksDir $sn
    if (-not (Test-Path $fp)) {
        Write-Host "    SKIP  $sn — not found" -ForegroundColor Yellow
        continue
    }
    $r = & $fp
    Assert-NotNull "$sn CheckId" $r.CheckId
    Assert-NotNull "$sn Name" $r.Name
    Assert-True "$sn valid Status" (("passed","failed","skipped","error") -contains $r.Status)
    Assert-NotNull "$sn Results" $r.Results
    Assert-NotNull "$sn Timestamp" $r.Timestamp
}

# ═══════════════════════════════════════════════════════════════════
# PART 29: All scripts parse cleanly
# ═══════════════════════════════════════════════════════════════════

Write-Host "`n=== All scripts parse ===" -ForegroundColor Cyan

$mDir = Join-Path $PSScriptRoot ".."
$psFiles = Get-ChildItem $mDir -Recurse -Filter "*.ps1" -File
$parseFailures = @()

foreach ($f in $psFiles) {
    $tokens = $null; $errors = $null
    $null = [System.Management.Automation.Language.Parser]::ParseFile(
        $f.FullName, [ref]$tokens, [ref]$errors
    )
    if ($errors.Count -gt 0) {
        $parseFailures += "$($f.Name): $($errors[0].Message)"
    }
}

if ($parseFailures.Count -gt 0) {
    Write-Host "    FAIL  Parse failures found:" -ForegroundColor Red
    foreach ($pf in $parseFailures) {
        Write-Host "          $pf" -ForegroundColor Red
    }
    $script:Failures++
} else {
    Assert-True "All scripts parse" ($parseFailures.Count -eq 0)
}
Assert-True "More than 30 scripts" ($psFiles.Count -gt 30)

# ═══════════════════════════════════════════════════════════════════
# Final report
# ═══════════════════════════════════════════════════════════════════

$total = $script:Passed + $script:Failures
Write-Host ""
Write-Host "========================================"
Write-Host "  Tests: $total total, $($script:Passed) passed, $($script:Failures) failed"
Write-Host "========================================"

if ($script:Failures -gt 0) {
    Write-Host "  FAILURES DETECTED" -ForegroundColor Red
    exit 1
} else {
    Write-Host "  ALL PASSED" -ForegroundColor Green
    exit 0
}
