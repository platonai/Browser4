#!/usr/bin/env pwsh

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

# Import common utility script
. $repoRoot\bin\common\Util.ps1

Fix-Encoding-UTF8

& (Join-Path $repoRoot "bin/build/build.ps1") @args

& (Join-Path $repoRoot "bin/browser4.ps1") @args
