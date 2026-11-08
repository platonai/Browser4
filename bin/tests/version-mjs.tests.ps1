#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Avoid Windows-only cmdlets; keep to core PowerShell.
# ═══════════════════════════════════════════════════════════════════

<#
.SYNOPSIS
    Unit tests for the pom version checks in bin/version.mjs (`check`).

.DESCRIPTION
    Regression guard for the 2026-11-08 nightly failure on main:

        [ERROR] Failed to execute goal on project browser4-browser:
        Could not resolve dependencies ... ai.platon.pulsar:browser4-behavior-api:jar:4.16.4-SNAPSHOT
            Could not find artifact ... in Central

    `browser4-core/browser4-behavior-api` and `...-default` declared
    `<parent><version>4.16.1-SNAPSHOT</version>`, while every other pom was at
    4.16.4-SNAPSHOT: the modules were added on main, so the version bump that
    ran on the 4.16.x maintenance branch never touched them, and the merge into
    main left them behind.  Maven then built those modules as 4.16.1 and the
    sibling depending on `${project.version}` (4.16.4) could not resolve them.

    `node bin/version.mjs check` only compared the root pom's <version>, so the
    drift passed every gate and only Maven noticed — three minutes into the run.

    The fixtures below pin the behavior it must have:
      * an inherited <version> is fine when the in-repo parent matches;
      * a stale in-repo parent version FAILS, with the parent named;
      * a <version> inside <dependencies> is not the project version;
      * a pom outside the reactor (<modules>) is not checked.

    Run standalone:
        pwsh bin/tests/version-mjs.tests.ps1
#>

[CmdletBinding()]
param()

$ErrorActionPreference = 'Continue'

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$RepoRoot = Split-Path -Parent (Split-Path -Parent $ScriptDir)
$VersionScript = Join-Path $RepoRoot 'bin' 'version.mjs'
$ExpectedVersion = '4.16.4-SNAPSHOT'
$StaleVersion = '4.16.1-SNAPSHOT'

$script:Failures = 0

function Write-Check([string]$Name, [bool]$Ok, [string]$Detail = '') {
    if ($Ok) {
        Write-Host "  [PASS] $Name" -ForegroundColor Green
    } else {
        Write-Host "  [FAIL] $Name" -ForegroundColor Red
        if ($Detail) { Write-Host "         $Detail" -ForegroundColor DarkGray }
        $script:Failures++
    }
}

if (-not (Test-Path $VersionScript)) {
    Write-Host "FATAL: version.mjs not found at $VersionScript" -ForegroundColor Red
    exit 1
}

if (-not (Get-Command node -ErrorAction SilentlyContinue)) {
    Write-Host 'SKIP: node is not installed — bin/version.mjs cannot run.' -ForegroundColor Yellow
    exit 0
}

function New-FixturePom {
    param(
        [string]$Path,
        [string]$ArtifactId,
        [string]$ParentArtifactId,
        [string]$ParentVersion,
        [string]$OwnVersion,
        [string[]]$Modules = @(),
        [string]$DependencyVersion,
        # "artifactId@version" entries, emitted as dependencyManagement pins the
        # way the PDK BOM pins in-repo artifacts.
        [string[]]$PinnedInRepoDependency = @()
    )

    $parent = ''
    if ($ParentArtifactId) {
        $parent = @"
    <parent>
        <groupId>ai.platon.pulsar</groupId>
        <artifactId>$ParentArtifactId</artifactId>
        <version>$ParentVersion</version>
    </parent>
"@
    }

    $ownVersionBlock = if ($OwnVersion) { "    <version>$OwnVersion</version>`n" } else { '' }

    $modulesBlock = ''
    if ($Modules.Count -gt 0) {
        $entries = ($Modules | ForEach-Object { "        <module>$_</module>" }) -join "`n"
        $modulesBlock = "    <modules>`n$entries`n    </modules>`n"
    }

    $dependencyBlock = ''
    if ($DependencyVersion) {
        $dependencyBlock = @"
    <dependencies>
        <dependency>
            <groupId>org.example</groupId>
            <artifactId>some-lib</artifactId>
            <version>$DependencyVersion</version>
        </dependency>
    </dependencies>
"@
    }

    $pinnedBlock = ''
    if ($PinnedInRepoDependency.Count -gt 0) {
        $pins = ($PinnedInRepoDependency | ForEach-Object {
            $artifact, $version = $_ -split '@', 2
            @"
            <dependency>
                <groupId>ai.platon.pulsar</groupId>
                <artifactId>$artifact</artifactId>
                <version>$version</version>
            </dependency>
"@
        }) -join "`n"
        $pinnedBlock = "    <dependencyManagement>`n        <dependencies>`n$pins`n        </dependencies>`n    </dependencyManagement>`n"
    }

    $content = @"
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0">
    <modelVersion>4.0.0</modelVersion>
$parent
    <artifactId>$ArtifactId</artifactId>
$ownVersionBlock
    <name>$ArtifactId</name>
$modulesBlock$pinnedBlock$dependencyBlock
</project>
"@

    $dir = Split-Path -Parent $Path
    if (-not (Test-Path $dir)) { New-Item -ItemType Directory -Path $dir -Force | Out-Null }
    Set-Content -Path $Path -Value $content -Encoding utf8NoBOM
}

function Invoke-VersionCheck {
    param([string]$FixtureRoot)

    $previous = $env:BROWSER4_VERSION_REPO_ROOT
    $env:BROWSER4_VERSION_REPO_ROOT = $FixtureRoot
    try {
        $output = & node $VersionScript check 2>&1 | ForEach-Object { "$_" }
        return [pscustomobject]@{
            ExitCode = $LASTEXITCODE
            Output   = ($output -join "`n")
        }
    } finally {
        if ($null -eq $previous) {
            Remove-Item Env:\BROWSER4_VERSION_REPO_ROOT -ErrorAction SilentlyContinue
        } else {
            $env:BROWSER4_VERSION_REPO_ROOT = $previous
        }
    }
}

$fixtureRoot = Join-Path ([System.IO.Path]::GetTempPath()) ("b4-version-fixture-" + [guid]::NewGuid().ToString('N'))
New-Item -ItemType Directory -Path $fixtureRoot -Force | Out-Null

try {
    Write-Host "`n=== POM fixture: $fixtureRoot ===" -ForegroundColor Cyan

    Set-Content -Path (Join-Path $fixtureRoot 'VERSION') -Value $ExpectedVersion -Encoding utf8NoBOM

    New-FixturePom -Path (Join-Path $fixtureRoot 'pom.xml') `
        -ArtifactId 'browser4' `
        -OwnVersion $ExpectedVersion `
        -Modules @('core')

    # Inherits its version from the (matching) root pom.
    New-FixturePom -Path (Join-Path $fixtureRoot 'core' 'pom.xml') `
        -ArtifactId 'browser4-core' `
        -ParentArtifactId 'browser4' `
        -ParentVersion $ExpectedVersion `
        -Modules @('leaf')

    # Inherits, carries a dependency version, and is not in any <modules> list.
    New-FixturePom -Path (Join-Path $fixtureRoot 'core' 'leaf' 'pom.xml') `
        -ArtifactId 'browser4-leaf' `
        -ParentArtifactId 'browser4' `
        -ParentVersion $ExpectedVersion `
        -DependencyVersion '1.2.3'

    # Not part of the reactor: a template/generated pom may legitimately carry
    # another version and must not fail the check.
    New-FixturePom -Path (Join-Path $fixtureRoot 'samples' 'generated' 'pom.xml') `
        -ArtifactId 'generated-sample' `
        -ParentArtifactId 'browser4' `
        -ParentVersion $StaleVersion

    $result = Invoke-VersionCheck -FixtureRoot $fixtureRoot
    Write-Check -Name 'consistent reactor passes' -Ok ($result.ExitCode -eq 0) -Detail $result.Output
    Write-Check -Name 'dependency <version> is not read as the project version' `
        -Ok (-not ($result.Output -match '1\.2\.3')) -Detail $result.Output
    Write-Check -Name 'pom outside the reactor is not checked' `
        -Ok (-not ($result.Output -match 'generated-sample')) -Detail $result.Output

    # Reproduce the nightly drift: the leaf's parent version is stale.
    New-FixturePom -Path (Join-Path $fixtureRoot 'core' 'leaf' 'pom.xml') `
        -ArtifactId 'browser4-leaf' `
        -ParentArtifactId 'browser4' `
        -ParentVersion $StaleVersion -DependencyVersion '1.2.3'

    $result = Invoke-VersionCheck -FixtureRoot $fixtureRoot
    Write-Check -Name 'stale in-repo parent version fails the check' -Ok ($result.ExitCode -ne 0) -Detail $result.Output
    Write-Check -Name 'failure names the parent and both versions' `
        -Ok (($result.Output -match "parent browser4 is $StaleVersion") -and ($result.Output -match "expected $ExpectedVersion")) `
        -Detail $result.Output

    # A pom that carries its own version must still be compared.
    New-FixturePom -Path (Join-Path $fixtureRoot 'core' 'leaf' 'pom.xml') `
        -ArtifactId 'browser4-leaf' `
        -ParentArtifactId 'browser4' `
        -ParentVersion $ExpectedVersion `
        -OwnVersion $StaleVersion

    $result = Invoke-VersionCheck -FixtureRoot $fixtureRoot
    Write-Check -Name 'own <version> mismatch fails the check' -Ok ($result.ExitCode -ne 0) -Detail $result.Output

    # The Docker-build flavor of the same drift: browser4-pdk-bom kept pinning
    # the behavior artifacts at the old version, so every plugin inheriting the
    # BOM resolved a 4.16.1 jar that does not exist.
    New-FixturePom -Path (Join-Path $fixtureRoot 'core' 'pom.xml') `
        -ArtifactId 'browser4-core' `
        -ParentArtifactId 'browser4' `
        -ParentVersion $ExpectedVersion `
        -Modules @('leaf') `
        -PinnedInRepoDependency @("browser4-leaf@$StaleVersion")

    $result = Invoke-VersionCheck -FixtureRoot $fixtureRoot
    Write-Check -Name 'stale in-repo dependency pin fails the check' -Ok ($result.ExitCode -ne 0) -Detail $result.Output
    Write-Check -Name 'failure names the pinned artifact and version' `
        -Ok ($result.Output -match "dependency pinned to browser4-leaf:$StaleVersion") `
        -Detail $result.Output

    # Reset the leaf to a consistent state, then pin its artifact at the right
    # version: the same pom must pass.
    New-FixturePom -Path (Join-Path $fixtureRoot 'core' 'leaf' 'pom.xml') `
        -ArtifactId 'browser4-leaf' `
        -ParentArtifactId 'browser4' `
        -ParentVersion $ExpectedVersion `
        -DependencyVersion '1.2.3'

    New-FixturePom -Path (Join-Path $fixtureRoot 'core' 'pom.xml') `
        -ArtifactId 'browser4-core' `
        -ParentArtifactId 'browser4' `
        -ParentVersion $ExpectedVersion `
        -Modules @('leaf') `
        -PinnedInRepoDependency @("browser4-leaf@$ExpectedVersion")

    $result = Invoke-VersionCheck -FixtureRoot $fixtureRoot
    Write-Check -Name 'in-repo pin at the project version passes' -Ok ($result.ExitCode -eq 0) -Detail $result.Output
} finally {
    Remove-Item -Recurse -Force $fixtureRoot -ErrorAction SilentlyContinue
}

Write-Host ''
if ($script:Failures -gt 0) {
    Write-Host "FAILED: $($script:Failures) check(s)" -ForegroundColor Red
    exit 1
}

Write-Host 'All version.mjs pom checks passed.' -ForegroundColor Green
exit 0
