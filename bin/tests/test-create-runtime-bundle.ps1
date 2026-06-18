#!/usr/bin/env pwsh

# ═══════════════════════════════════════════════════════════════════
# CROSS-PLATFORM: This script must run on Linux, macOS, and Windows.
# - Use $IsWindows / $IsLinux / $IsMacOS for platform detection.
# - Use "($IsWindows -or $env:OS -eq 'Windows_NT')" for PS 5.1 compat.
# - Windows-only env vars ($env:TEMP) need $env:TMPDIR fallback.
# - Guard "chcp" and other Windows-only commands behind platform checks.
# ═══════════════════════════════════════════════════════════════════


$ErrorActionPreference = 'Stop'
$RepoRoot = git rev-parse --show-toplevel
Push-Location $RepoRoot

$coreArgs = @('install', '-Passet-bundle', '-pl', 'browser4-apps/browser4-bundle', '-am', '-DskipTests', '-Dmaven.javadoc.skip=true')

$mvnCmd = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
& $mvnCmd @coreArgs
if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
