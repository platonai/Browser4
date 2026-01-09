#!/usr/bin/env pwsh

# 🔍 Find the first parent directory containing the VERSION file
$AppHome=(Get-Item -Path $MyInvocation.MyCommand.Path).Directory
while ($AppHome -ne $null -and !(Test-Path "$AppHome/VERSION")) {
  $AppHome = Split-Path -Parent $AppHome
}
Set-Location $AppHome

function printUsage {
  Write-Host "Usage: maven-deploy.ps1 [-clean|-test]"
  exit 1
}

# Maven command and options
$MvnCmd = Join-Path $AppHome '.\mvnw.cmd'

# Initialize flags and additional arguments
$PerformClean = $false
$SkipTests = $true

$AdditionalMvnArgs = @()

# Parse command-line arguments
foreach ($Arg in $args)
{
  switch ($Arg)
  {
    '-clean' {
      $PerformClean = $true;
    }
    { '-t', '-test' } {
      $SkipTests = $false;
    }
    { $_ -in "-h", "-help", "--help" } {
      printUsage
    }
    { $_ -in "-*", "--*" } {
      printUsage
    }
    Default {
      $AdditionalMvnArgs += $Arg
    }
  }
}

Write-Host "Deploy the project ..."
Write-Host "Changing version ..."

$SNAPSHOT_VERSION = Get-Content "$AppHome\VERSION" -TotalCount 1
$VERSION =$SNAPSHOT_VERSION -replace "-SNAPSHOT", ""
$VERSION | Set-Content "$AppHome\VERSION"

# Replace SNAPSHOT version with the release version
@('llm-config.md', 'README.zh.md', 'pom.xml') | ForEach-Object {
  Get-ChildItem -Path "$AppHome" -Depth 5 -Filter $_ -Recurse | ForEach-Object {
    (Get-Content $_.FullName) -replace $SNAPSHOT_VERSION, $VERSION | Set-Content $_.FullName
  }
}

if ($PerformClean) {
  & $MvnCmd clean
  if ($LastExitCode -ne 0) {
    exit $LastExitCode
  }
}

if ($SkipTests) {
  & $MvnCmd deploy -P deploy,release -DskipTests
} else {
  & $MvnCmd deploy -P deploy,release
}

$exitCode =$LastExitCode
if ($exitCode -eq 0) {
  Write-Host "Build successfully"
} else {
  exit $exitCode
}

# The following commands are commented out to avoid accidental execution
# mvn nexus-staging:close -P deploy
# mvn nexus-staging:release -P deploy

# Build browser4/browser4-agents/ but do not deploy the artifacts
$PulsarAppPath = Join-Path $AppHome 'browser4/browser4-agents/'
if (Test-Path $PulsarAppPath) {
  Set-Location $PulsarAppPath
  & $MvnCmd clean install -DskipTests
  if ($LastExitCode -ne 0) {
    exit $LastExitCode
  }
} else {
  Write-Host "browser4/browser4-agents/ not found, skipping build."
}

$exitCode =$LastExitCode
if ($exitCode -eq 0) {
  Write-Host "Build successfully"
} else {
  exit $exitCode
}

Set-Location $AppHome

Write-Host "Artifacts are staged remotely, you should close and release the staging manually:"
Write-Host "https://oss.sonatype.org/#stagingRepositories"
Write-Host "Hit the following link to check if the artifacts are synchronized to the maven center: "
Write-Host "https://repo1.maven.org/maven2/ai/platon/pulsar"
