#!/usr/bin/env pwsh

param(
    [string]$RemoteUser = "vincent",
    [string]$RemoteHost = "platonai.cn",
    [string]$RemoteBaseDir = "~/platonic.fun/repo/ai/platon/pulsar/"
)

$repoRoot = (git rev-parse --show-toplevel 2>$null)
Set-Location $repoRoot

$MvnCmd = Join-Path $repoRoot '.\mvnw.cmd'

$Version=(Get-Content "$repoRoot/VERSION" -TotalCount 1) -replace "-SNAPSHOT", ""

# If browser4-app/browser4-agents/target/Browser4.jar exists, copy it to remote
$PulsarRPAPath = "$repoRoot/browser4-app/browser4-agents/target/Browser4.jar"

if (Test-Path $PulsarRPAPath) {
    Write-Host "Browser4.jar exists"
} else {
    Write-Warning "Browser4.jar does not exist"

    # Build browser4-app/browser4-agents/
    Set-Location $repoRoot/pulsar-app
    & $MvnCmd -pl browser4-app/browser4-agents/ clean package -DskipTests
    Set-Location $repoRoot
}

$VersionedJarRemoteFullPath = "${RemoteBaseDir}Browser4-${Version}.jar"
$JarRemoteFullPath = "${RemoteBaseDir}Browser4.jar"
$DestinationPath = "${RemoteUser}@${RemoteHost}:$VersionedJarRemoteFullPath"

Write-Host "Copying $PulsarRPAPath to $DestinationPath..."

try {
    # Check if SSH connection works
    $testResult = Invoke-Expression "ssh ${RemoteUser}@${RemoteHost} 'echo Connection test'"
    if ($LASTEXITCODE -ne 0) {
        throw "SSH connection test failed"
    }

    # Ensure destination directory exists
    $mkdirResult = Invoke-Expression "ssh ${RemoteUser}@${RemoteHost} 'mkdir -p $RemoteBaseDir'"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create remote directory"
    }

    # Copy the file
    $copyResult = Invoke-Expression "scp $PulsarRPAPath $DestinationPath"
    if ($LASTEXITCODE -ne 0) {
        throw "SCP command failed with exit code $LASTEXITCODE"
    }

    Write-Host "File copied successfully to $DestinationPath" -ForegroundColor Green

    # Create a symbolic link to the latest version
    $linkResult = Invoke-Expression "ssh ${RemoteUser}@${RemoteHost} 'ln -sf $VersionedJarRemoteFullPath $JarRemoteFullPath'"
    if ($LASTEXITCODE -ne 0) {
        throw "Failed to create symbolic link"
    }
    Write-Host "Symbolic link created successfully" -ForegroundColor Green

    # List the remote directory contents
    # e.g. ssh vincent@platonai.cn ls -l ~/platonic.fun/repo/ai/platon/pulsar/
    $listResult = Invoke-Expression "ssh ${RemoteUser}@${RemoteHost} 'ls -l $RemoteBaseDir'"

    Write-Host "Done."
    Write-Host "Assets are available at: https://static.platonai.cn/repo/ai/platon/pulsar/"
}
catch {
    Write-Error "Error: $_"
    exit 1
}
