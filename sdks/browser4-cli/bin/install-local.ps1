#!/usr/bin/env pwsh

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

function Get-RepoCliVersion {
	param(
		[Parameter(Mandatory = $true)]
		[string]$CargoTomlPath
	)

	$lines = Get-Content -LiteralPath $CargoTomlPath
	$inPackageSection = $false

	foreach ($line in $lines) {
		if ($line -match '^\s*\[package\]\s*$') {
			$inPackageSection = $true
			continue
		}

		if ($inPackageSection -and $line -match '^\s*\[.+\]\s*$') {
			break
		}

		if ($inPackageSection -and $line -match '^\s*version\s*=\s*"([^"]+)"\s*$') {
			return $Matches[1]
		}
	}

	throw "Unable to resolve browser4-cli version from $CargoTomlPath"
}

function Get-InstalledCliVersion {
	$cliCommand = Get-Command browser4-cli -ErrorAction SilentlyContinue
	if ($null -eq $cliCommand) {
		return $null
	}

	try {
		$versionOutput = (& browser4-cli --version 2>$null | Select-Object -First 1)
	}
	catch {
		return $null
	}

	if ([string]::IsNullOrWhiteSpace($versionOutput)) {
		return $null
	}

	if ($versionOutput -match '(\d+\.\d+\.\d+(?:[-+][0-9A-Za-z\.-]+)?)') {
		return $Matches[1]
	}

	return $null
}

function Resolve-RepoRoot {
	$scriptDir = Split-Path -Parent $PSCommandPath
	$rootByLayout = [System.IO.Path]::GetFullPath((Join-Path $scriptDir "..\..\.."))

	if (Test-Path (Join-Path $rootByLayout "mvnw.cmd")) {
		return $rootByLayout
	}

	$rootByGit = (git rev-parse --show-toplevel 2>$null)
	if (-not [string]::IsNullOrWhiteSpace($rootByGit)) {
		return $rootByGit.Trim()
	}

	throw "Unable to resolve repository root from script location or git."
}

$repoRoot = Resolve-RepoRoot
Set-Location -Path $repoRoot

$cliRoot = Join-Path $repoRoot "sdks\browser4-cli"
$cargoTomlPath = Join-Path $cliRoot "Cargo.toml"

$repoCliVersion = Get-RepoCliVersion -CargoTomlPath $cargoTomlPath
$installedCliVersion = Get-InstalledCliVersion

if ([string]::IsNullOrWhiteSpace($installedCliVersion) -or $installedCliVersion -ne $repoCliVersion) {
	Write-Host "Installing browser4-cli $repoCliVersion globally via cargo..."
	Push-Location $cliRoot
	try {
		cargo install --path . --locked
		if ($LASTEXITCODE -ne 0) {
			throw "cargo install failed with exit code $LASTEXITCODE"
		}
	}
	finally {
		Pop-Location
	}
}
else {
	Write-Host "browser4-cli is up to date (version $installedCliVersion)."
}

$userHome = [Environment]::GetFolderPath("UserProfile")
$libDir = Join-Path $userHome ".browser4\lib"
$linkPath = Join-Path $libDir "Browser4.jar"
$jarPath = Join-Path $repoRoot "browser4\browser4-agents\target\Browser4.jar"

if (-not (Test-Path -LiteralPath $jarPath)) {
	throw 'Browser4.jar not found at: ' + $jarPath + '. Build it first (for example: .\mvnw.cmd -q -D"skipTests").'
}

New-Item -ItemType Directory -Path $libDir -Force | Out-Null

if (Test-Path -LiteralPath $linkPath) {
	Remove-Item -LiteralPath $linkPath -Force
}

try {
	New-Item -ItemType SymbolicLink -Path $linkPath -Target $jarPath -Force | Out-Null
}
catch {
	# Fallback for environments where symbolic link creation is restricted.
	$mklinkArgs = "mklink `"$linkPath`" `"$jarPath`""
	cmd /c $mklinkArgs | Out-Null
	if ($LASTEXITCODE -ne 0) {
		throw "Failed to create symlink. Enable Developer Mode or run PowerShell as Administrator."
	}
}

Get-Item -LiteralPath $linkPath | Format-List FullName, LinkType, Target
