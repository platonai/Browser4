# Generate OpenAPI spec by starting the REST server and fetching /v3/api-docs
param(
    [string]$OutputDir = "target"
)

Write-Host "[openapi] Starting Pulsar REST API to generate OpenAPI spec..." -ForegroundColor Cyan

# Start the REST API in background
$process = Start-Process -FilePath "mvnw.cmd" `
    -ArgumentList "spring-boot:run", "-Dspring.profiles.active=rest", "-Dserver.port=18182" `
    -PassThru `
    -NoNewWindow `
    -RedirectStandardOutput "$OutputDir\spring-boot.log" `
    -RedirectStandardError "$OutputDir\spring-boot-error.log"

# Wait for server to be ready
Write-Host "[openapi] Waiting for server to start on port 18182..."
$maxWait = 60
$waited = 0
$ready = $false

while ($waited -lt $maxWait -and !$ready) {
    Start-Sleep -Seconds 1
    $waited++
    try {
        $response = Invoke-WebRequest -Uri "http://localhost:18182/health" -TimeoutSec 2 -ErrorAction SilentlyContinue
        if ($response.StatusCode -eq 200) {
            $ready = $true
            Write-Host "[openapi] Server ready after $waited seconds" -ForegroundColor Green
        }
    } catch {
        # Server not ready yet
    }
}

if (!$ready) {
    Write-Host "[openapi] ERROR: Server did not start within $maxWait seconds" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    exit 1
}

# Fetch OpenAPI spec
try {
    Write-Host "[openapi] Fetching OpenAPI spec from http://localhost:18182/v3/api-docs..."

    $jsonSpec = Invoke-RestMethod -Uri "http://localhost:18182/v3/api-docs" -ContentType "application/json"
    $jsonPath = Join-Path $OutputDir "openapi.json"
    $jsonSpec | ConvertTo-Json -Depth 100 | Set-Content -Path $jsonPath -Encoding UTF8
    Write-Host "[openapi] Saved JSON spec to $jsonPath" -ForegroundColor Green

    # Try YAML endpoint
    try {
        $yamlSpec = Invoke-RestMethod -Uri "http://localhost:18182/v3/api-docs.yaml"
        $yamlPath = Join-Path $OutputDir "openapi.yaml"
        $yamlSpec | Set-Content -Path $yamlPath -Encoding UTF8
        Write-Host "[openapi] Saved YAML spec to $yamlPath" -ForegroundColor Green
    } catch {
        Write-Host "[openapi] YAML endpoint not available, converting from JSON..." -ForegroundColor Yellow
        # Convert JSON to YAML using PowerShell YAML module if available, or just copy JSON
        Copy-Item $jsonPath "$OutputDir\openapi.yaml"
        Write-Host "[openapi] Copied JSON as YAML (manual conversion may be needed)" -ForegroundColor Yellow
    }

} catch {
    Write-Host "[openapi] ERROR: Failed to fetch OpenAPI spec: $_" -ForegroundColor Red
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    exit 1
} finally {
    # Stop the server
    Write-Host "[openapi] Stopping server..."
    Stop-Process -Id $process.Id -Force -ErrorAction SilentlyContinue
    Write-Host "[openapi] Done!" -ForegroundColor Green
}

