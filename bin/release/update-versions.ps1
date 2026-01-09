#!/usr/bin/env pwsh

# 🔍 Find the first parent directory containing the VERSION file
$AppHome=(Get-Item -Path $MyInvocation.MyCommand.Path).Directory
while ($AppHome -ne $null -and !(Test-Path "$AppHome/VERSION")) {
  $AppHome = Split-Path -Parent $AppHome
}
Set-Location $AppHome

Write-Host "Deploy the project ..."
Write-Host "Changing version ..."

$SNAPSHOT_VERSION = Get-Content "$AppHome\VERSION" -TotalCount 1
$VERSION =$SNAPSHOT_VERSION -replace "-SNAPSHOT", ""
$VERSION | Set-Content "$AppHome\VERSION"

# Replace SNAPSHOT version with the release version
@('pom.xml', 'llm-config.md', 'README.md', 'README.zh.md') | ForEach-Object {
  Get-ChildItem -Path "$AppHome" -Depth 2 -Filter $_ -Recurse | ForEach-Object {
    (Get-Content $_.FullName) -replace $SNAPSHOT_VERSION, $VERSION | Set-Content $_.FullName
  }
}
