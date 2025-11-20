@echo off
REM Quick script to generate OpenAPI spec and SDKs for Pulsar REST API
REM Usage: generate-sdk.bat

echo.
echo ========================================
echo Pulsar REST API - SDK Generation
echo ========================================
echo.

set SPEC_FILE=pulsar-rest-sdk\openapi.yaml

REM Check if server is already running
curl -s http://localhost:8182/health >nul 2>&1
if %ERRORLEVEL% EQU 0 (
    echo [INFO] Server is already running on port 8182
    goto FETCH_SPEC
)

echo [1/3] Starting REST API server...
echo       This may take 30-60 seconds...
start /B "Pulsar REST" mvnw.cmd -q -pl pulsar-rest spring-boot:run >logs\sdk-server.log 2>&1

REM Wait for server to start
set /a WAIT=0
:WAIT_LOOP
timeout /t 2 /nobreak >nul
set /a WAIT=%WAIT%+2
curl -s http://localhost:8182/health >nul 2>&1
if %ERRORLEVEL% NEQ 0 (
    if %WAIT% LSS 60 (
        echo       Waiting for server... %WAIT%s
        goto WAIT_LOOP
    ) else (
        echo [ERROR] Server did not start within 60 seconds
        echo         Check logs\sdk-server.log for details
        goto END
    )
)

:FETCH_SPEC
echo.
echo [2/3] Fetching OpenAPI specification...
curl -s http://localhost:8182/v3/api-docs.yaml -o %SPEC_FILE%
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] Failed to fetch OpenAPI spec
    goto END
)
echo       Saved to %SPEC_FILE%

echo.
echo [3/3] Generating SDK clients...
mvnw.cmd -q -pl pulsar-rest-sdk clean generate-sources
if %ERRORLEVEL% NEQ 0 (
    echo [ERROR] SDK generation failed
    goto END
)

echo.
echo ========================================
echo SUCCESS! SDKs generated:
echo ========================================
echo   Kotlin:     pulsar-rest-sdk\target\generated-sources\kotlin\
echo   Java:       pulsar-rest-sdk\target\generated-sources\java\
echo   TypeScript: pulsar-rest-sdk\target\generated-sources\typescript\
echo.
echo To publish TypeScript SDK:
echo   cd pulsar-rest-sdk\target\generated-sources\typescript
echo   npm install ^&^& npm run build ^&^& npm publish
echo.

:END
REM Ask if user wants to stop the server
set /p STOP="Stop the REST API server? (Y/N): "
if /i "%STOP%"=="Y" (
    echo Stopping server...
    taskkill /FI "WINDOWTITLE eq Pulsar REST*" /F >nul 2>&1
    echo Server stopped
)

echo.
echo Done!

