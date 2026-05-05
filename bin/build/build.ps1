#!/usr/bin/env pwsh

$ErrorActionPreference = "Stop"

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

function printUsage {
  Write-Host "Usage: build.ps1 [-clean|-test]"
  exit 1
}

# Maven command and options
$MvnCmd = Join-Path $repoRoot '.\mvnw'

# Initialize flags and additional arguments
$PerformClean = $false
$SkipTests = $true

$MvnOptions = @()
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

# Conditionally add Maven options based on flags
if ($PerformClean)
{
  $MvnOptions += 'clean'
}

if ($SkipTests)
{
  $AdditionalMvnArgs += '-DskipTests'
}

# Function to execute Maven command in a given directory
Function Invoke-MavenBuild {
  param([string]$Directory, [Object[]]$BuildArgs)

  Push-Location $Directory
  try {
    .\mvnw @BuildArgs

    if ($LASTEXITCODE -ne 0) {
      throw "Maven command failed in $Directory with exit code $LASTEXITCODE"
    }
  }
  finally {
    Pop-Location
  }
}

Function Invoke-CargoBuild {
  param(
    [string]$Directory,
    [bool]$RunTests
  )

  $cargoCmd = Get-Command cargo -ErrorAction SilentlyContinue
  if (-not $cargoCmd) {
    throw "cargo is not installed or not in PATH"
  }

  Push-Location $Directory
  try {
    if ($RunTests) {
      & cargo test --locked --bin browser4-cli
      if ($LASTEXITCODE -ne 0) {
        throw "Cargo test failed in $Directory with exit code $LASTEXITCODE"
      }
    }

    & cargo build --release --locked
    if ($LASTEXITCODE -ne 0) {
      throw "Cargo build failed in $Directory with exit code $LASTEXITCODE"
    }
  }
  finally {
    Pop-Location
  }
}

Function Copy-Browser4JarToTarget {
  param([string]$RepoRoot)

  $sourceJar = Join-Path $RepoRoot 'browser4-app\browser4-agents\target\Browser4.jar'
  if (-not (Test-Path -LiteralPath $sourceJar)) {
    throw "Browser4.jar not found at $sourceJar"
  }

  $targetDir = Join-Path $RepoRoot 'target'
  $targetJar = Join-Path $targetDir 'Browser4.jar'
  New-Item -ItemType Directory -Path $targetDir -Force | Out-Null
  Copy-Item -LiteralPath $sourceJar -Destination $targetJar -Force
}

# Execute Maven package in the application home directory
$MvnOptions += 'install'

$MvnOptions += $AdditionalMvnArgs
Invoke-MavenBuild -Directory $repoRoot -BuildArgs $MvnOptions
Copy-Browser4JarToTarget -RepoRoot $repoRoot
Invoke-CargoBuild -Directory (Join-Path $repoRoot 'sdks\browser4-cli') -RunTests (-not $SkipTests)
