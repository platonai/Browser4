#!/usr/bin/env pwsh

# Call bin/build/build.ps1 with all passed arguments
$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Path
$BuildScript = Join-Path $ScriptDir 'build\build.ps1'
& $BuildScript @args
