#!/usr/bin/env pwsh


$ErrorActionPreference = 'Stop'
$RepoRoot = git rev-parse --show-toplevel
Push-Location $RepoRoot

$coreArgs = @('install', '-Passet-bundle', '-pl', 'browser4-apps/browser4-bundle', '-am', '-DskipTests', '-Dmaven.javadoc.skip=true')

$mvnCmd = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
& $mvnCmd @coreArgs
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
