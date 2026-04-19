#!/usr/bin/env pwsh

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"

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
