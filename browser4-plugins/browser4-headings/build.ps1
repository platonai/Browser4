# build.ps1 — Build, verify, and deploy the browser4-headings plugin
#
# Usage:
#   .\build.ps1                  # Build + verify JAR structure
#   .\build.ps1 -DeployDir ..    # Build + copy JAR to a plugins directory
#   .\build.ps1 -RestInstall     # Build + install via REST API
#
param(
    [string]$DeployDir = "",
    [switch]$RestInstall,
    [string]$RestUrl = "http://localhost:8182"
)

$ErrorActionPreference = "Stop"

# Resolve the plugin directory (where this script lives)
$PluginDir = Split-Path -Parent $MyInvocation.MyCommand.Path
Push-Location $PluginDir

try {
    Write-Host "[1/3] Building browser4-headings..." -ForegroundColor Cyan
    mvn package -DskipTests -q
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed (exit code $LASTEXITCODE)"
    }

    # Find the built JAR
    $Jar = Get-ChildItem "target/browser4-headings-*.jar" |
        Where-Object { $_.Name -notmatch "sources|javadoc" } |
        Select-Object -First 1
    if (-not $Jar) {
        throw "No JAR found in target/ after build"
    }
    Write-Host "[1/3] Built: $($Jar.Name)" -ForegroundColor Green

    Write-Host "[2/3] Verifying JAR structure..." -ForegroundColor Cyan
    $jarContents = jar tf $Jar.FullName
    $checks = @(
        @{ Name = "plugin manifest";     Pattern = "META-INF/browser4-plugin.json" },
        @{ Name = "auto-config imports"; Pattern = "META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports" },
        @{ Name = "js resource";         Pattern = "headings/extractHeadings.js" },
        @{ Name = "HeadingsAutoConfiguration";    Pattern = "ai/platon/pulsar/headings/config/HeadingsAutoConfiguration.class" },
        @{ Name = "HeadingsToolExecutor";   Pattern = "ai/platon/pulsar/headings/tools/HeadingsToolExecutor.class" }
    )
    foreach ($check in $checks) {
        if ($jarContents -notcontains $check.Pattern) {
            throw "JAR verification failed: missing $($check.Name) ($($check.Pattern))"
        }
    }
    Write-Host "[2/3] All required entries present in JAR" -ForegroundColor Green

    Write-Host "[3/3] Deploying..." -ForegroundColor Cyan
    if ($DeployDir) {
        $dest = if (Test-Path $DeployDir -PathType Container) { $DeployDir } else { (New-Item -ItemType Directory -Force -Path $DeployDir).FullName }
        Copy-Item $Jar.FullName $dest -Force
        Write-Host "[3/3] Copied to: $dest\$($Jar.Name)" -ForegroundColor Green
        Write-Host "      Restart Browser4 to load the plugin." -ForegroundColor Yellow
    }
    elseif ($RestInstall) {
        $response = curl.exe -s -X POST "$RestUrl/api/plugins/install" -F "file=@$($Jar.FullName)"
        Write-Host "[3/3] REST install response: $response" -ForegroundColor Green
    }
    else {
        Write-Host "[3/3] No deploy target specified." -ForegroundColor Yellow
        Write-Host "      JAR ready at: $($Jar.FullName)" -ForegroundColor Gray
        Write-Host "      To deploy: copy to Browser4's plugins/ dir, or rerun with -DeployDir or -RestInstall" -ForegroundColor Gray
    }
}
finally {
    Pop-Location
}