<#
.SYNOPSIS
    build — build script

.DESCRIPTION
    Build script for build.

.PARAMETER Verbose
    Show detailed output.
#>
param(
    [switch]$Verbose
)

$ErrorActionPreference = "Stop"

Write-Host "[build] Starting build..."

# TODO: Implement build logic
# Example for build:
#   & mvn -pl browser4-plugins/build -am compile -DskipTests
# Example for deploy:
#   Copy-Item "target/*.jar" "$env:BROWSER4_HOME/plugins/" -Force

if ($LASTEXITCODE -ne 0) {
    Write-Error "[build] build failed with exit code $LASTEXITCODE"
    exit 1
}

Write-Host "[build] build completed successfully."