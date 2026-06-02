#!/usr/bin/env pwsh
<#
.SYNOPSIS
    Build and smoke-test the Browser4 Docker image locally, mirroring the
    release.yml "build-core-and-docker" job behaviour as closely as possible.

.DESCRIPTION
    1. Maven build (all-modules + asset-standalone)
    2. docker build (Dockerfile) with the same args as CI
    3. docker run with health check
    4. Inspect the JAR inside the container to verify the main class is present
       (this is the key diagnostic for the ClassNotFoundException issue)
    5. Print container logs on failure

.PARAMETER Version
    Tag for the local image (default: "local-test").

.PARAMETER SkipMavenBuild
    Skip the local Maven build step.

.EXAMPLE
    .\test-docker-local.ps1

.EXAMPLE
    .\test-docker-local.ps1 -SkipMavenBuild   # image already built
#>

[CmdletBinding()]
param(
    [string] $Version = "local-test",
    [switch] $SkipMavenBuild
)

$ErrorActionPreference = 'Stop'
$RepoRoot = git rev-parse --show-toplevel
Push-Location $RepoRoot

$ImageName = "browser4:${Version}"
$ContainerName = "browser4-test-local"

# -------------------------------------------------------------------
# 1. Maven build (same as release.yml "Maven Build" step)
# -------------------------------------------------------------------
if (-not $SkipMavenBuild) {
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
    Write-Host "  [1/5] Maven Build (all-modules,asset-standalone)" -ForegroundColor Cyan
    Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

    $mvnCmd = if ($IsWindows) { '.\mvnw.cmd' } else { './mvnw' }
    $mvnArgs = @('package', '-Pall-modules,asset-standalone', '-DskipTests', '-Dmaven.javadoc.skip=true', '-B', '-V')
    & $mvnCmd @mvnArgs
    if ($LASTEXITCODE -ne 0) { throw "Maven build failed" }
    Write-Host "`n✅ Maven build complete`n" -ForegroundColor Green
}

# -------------------------------------------------------------------
# 2. Inspect the standalone JAR on the host (pre-Docker check)
# -------------------------------------------------------------------
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  [2/5] Pre-Docker JAR inspection" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

$hostJarPath = "$RepoRoot/browser4-apps/browser4-standalone/target/Browser4.jar"
if (-not (Test-Path $hostJarPath)) {
    throw "Host JAR not found: $hostJarPath"
}
$hostJarSize = (Get-Item $hostJarPath).Length
Write-Host "Host JAR: $hostJarPath  ($('{0:N0}' -f $hostJarSize) bytes)"

# Extract and show MANIFEST.MF
Add-Type -AssemblyName System.IO.Compression.FileSystem
$zip = [System.IO.Compression.ZipFile]::OpenRead($hostJarPath)
$manifestEntry = $zip.GetEntry('META-INF/MANIFEST.MF')
if ($manifestEntry) {
    $reader = [System.IO.StreamReader]::new($manifestEntry.Open())
    $manifest = $reader.ReadToEnd()
    $reader.Dispose()
    Write-Host "--- MANIFEST.MF ---"
    $manifest -split "`n" | Where-Object { $_ -match 'Main-Class|Start-Class' } | ForEach-Object { Write-Host $_ }
    Write-Host "--- END MANIFEST.MF ---"
} else {
    Write-Host "❌ No MANIFEST.MF in host JAR!" -ForegroundColor Red
}

# Check for the expected class in the JAR
$hasClass = $false
foreach ($entry in $zip.Entries) {
    if ($entry.FullName -match 'Browser4StandaloneApplication') {
        Write-Host "Found in JAR: $($entry.FullName)" -ForegroundColor Green
        $hasClass = $true
    }
}
if (-not $hasClass) {
    Write-Host "❌ Browser4StandaloneApplication* NOT found in host JAR!" -ForegroundColor Red
    Write-Host "Classes matching '*Application*':"
    foreach ($entry in $zip.Entries) {
        if ($entry.FullName -match 'Application.*\.class$') {
            Write-Host "  $($entry.FullName)"
        }
    }
}
$zip.Dispose()
Write-Host ""

# -------------------------------------------------------------------
# 3. Build Docker image
# -------------------------------------------------------------------
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  [3/5] Docker build" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

docker build -t $ImageName -f Dockerfile .
if ($LASTEXITCODE -ne 0) { throw "Docker build failed" }
Write-Host "`n✅ Docker build complete`n" -ForegroundColor Green

# -------------------------------------------------------------------
# 4. Inspect JAR inside the built image
# -------------------------------------------------------------------
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  [4/5] Inspect JAR inside Docker image" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

# Extract the JAR from the builder stage
docker run --rm $ImageName sh -c "ls -la /app/app.jar && jar tf /app/app.jar | grep -i 'Browser4Standalone\|Browser4Application'" 2>&1
Write-Host ""

# Extract and print MANIFEST.MF from inside the image
Write-Host "--- MANIFEST.MF (from image) ---"
docker run --rm $ImageName sh -c "jar xf /app/app.jar META-INF/MANIFEST.MF && cat META-INF/MANIFEST.MF" 2>&1 | Select-String 'Main-Class|Start-Class'
Write-Host "--- END MANIFEST.MF ---"
Write-Host ""

# -------------------------------------------------------------------
# 5. Run container and wait for health
# -------------------------------------------------------------------
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan
Write-Host "  [5/5] Start container & health check" -ForegroundColor Cyan
Write-Host "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━" -ForegroundColor Cyan

# Clean up any previous test container
docker rm -f $ContainerName 2>$null

Write-Host "Starting container ..."
docker run -d `
    --name $ContainerName `
    -p 8182:8182 `
    -e JAVA_OPTS="-Xms256M -Xmx2G -XX:+UseG1GC" `
    --health-cmd="curl -f http://localhost:8182/actuator/health || exit 1" `
    --health-interval=5s `
    --health-timeout=5s `
    --health-retries=10 `
    $ImageName

# Quick check: did the container crash immediately?
Start-Sleep -Seconds 3
$earlyStatus = docker inspect --format='{{.State.Status}}' $ContainerName 2>$null
Write-Host "Container status after 3s: $earlyStatus"

if ($earlyStatus -eq 'exited' -or $earlyStatus -eq 'dead') {
    $exitCode = docker inspect --format='{{.State.ExitCode}}' $ContainerName
    Write-Host "❌ Container exited immediately (exit code: $exitCode)" -ForegroundColor Red
    Write-Host "--- container logs ---"
    docker logs $ContainerName
    docker rm -f $ContainerName 2>$null
    Pop-Location
    exit 1
}

Write-Host "Waiting for healthy status (up to 120s) ..."
$healthy = $false
for ($i = 1; $i -le 24; $i++) {
    Start-Sleep -Seconds 5
    $status = docker inspect --format='{{.State.Status}}' $ContainerName 2>$null
    $health = docker inspect --format='{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' $ContainerName 2>$null
    Write-Host "  [$i/24] status=$status health=$health"

    if ($status -eq 'exited' -or $status -eq 'dead') {
        $exitCode = docker inspect --format='{{.State.ExitCode}}' $ContainerName
        Write-Host "❌ Container exited (exit code: $exitCode)" -ForegroundColor Red
        Write-Host "--- container logs ---"
        docker logs $ContainerName
        docker rm -f $ContainerName 2>$null
        Pop-Location
        exit 1
    }

    if ($health -eq 'healthy') {
        $healthy = $true
        break
    }
}

if (-not $healthy) {
    Write-Host "❌ Container did not become healthy within 120s" -ForegroundColor Red
    Write-Host "--- container logs ---"
    docker logs $ContainerName
    docker rm -f $ContainerName 2>$null
    Pop-Location
    exit 1
}

Write-Host "`n✅ Container is healthy!" -ForegroundColor Green

# Quick smoke test
Write-Host "`nSmoke test: checking /actuator/health ..."
try {
    $healthResponse = Invoke-RestMethod -Uri "http://localhost:8182/actuator/health" -TimeoutSec 5
    Write-Host "Health response: $($healthResponse | ConvertTo-Json)" -ForegroundColor Green
} catch {
    Write-Host "⚠ Health endpoint not reachable: $_" -ForegroundColor Yellow
}

# Cleanup
Write-Host "`nCleaning up test container ..."
docker rm -f $ContainerName 2>$null

Write-Host "`n✅ All checks passed!" -ForegroundColor Green
Pop-Location
