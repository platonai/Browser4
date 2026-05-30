#!/usr/bin/env pwsh

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

$buildScript = Join-Path $repoRoot "bin/build/build.ps1"
& $buildScript @args

$SERVER_HOME = Join-Path $repoRoot "browser4-apps/browser4-agents"
Set-Location $SERVER_HOME

../../mvnw spring-boot:run

Set-Location $repoRoot
