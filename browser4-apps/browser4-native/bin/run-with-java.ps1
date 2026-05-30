#!/usr/bin/env pwsh

function Write-Info([string]$Message) {
    Write-Host "[INFO] $Message" -ForegroundColor Green
}

function Ensure-Command([string]$CommandName, [string]$Hint) {
    if (-not (Get-Command $CommandName -ErrorAction SilentlyContinue)) {
        Write-Err "$CommandName not found. $Hint"
        exit 1
    }
}

$env:JAVA_HOME = "D:\Program Files\Java\graalvm-jdk-25.0.3+9.1"

if ($env:JAVA_HOME) {
    $env:Path = "$env:JAVA_HOME\bin;$env:Path"
    Write-Info "JAVA_HOME = $env:JAVA_HOME"
} else {
    Write-Warn "JAVA_HOME is not set; current java/native-image from PATH will be used."
}

Ensure-Command -CommandName java -Hint "Install GraalVM JDK and/or set -JavaHome."
Ensure-Command -CommandName native-image -Hint "Install GraalVM Native Image component."

$javaVersion = (& cmd.exe /c "java -version 2>&1" | Select-Object -First 1).Trim()
$nativeVersion = (& cmd.exe /c "native-image --version 2>&1" | Select-Object -First 1).Trim()
Write-Info "Java: $javaVersion"
Write-Info "native-image: $nativeVersion"

Set-Location "D:\workspace\Browser4\Browser4-native";
.\mvnw.cmd clean install -Pall-modules -DskipTests -am
# .\mvnw.cmd -f browser4-apps/browser4-native/pom.xml package -DskipTests -am
.\mvnw.cmd -f browser4-apps/browser4-native/pom.xml spring-boot:process-aot -DskipTests | Out-String
