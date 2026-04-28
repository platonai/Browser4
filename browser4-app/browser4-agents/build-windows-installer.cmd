@echo off
REM Build Windows installer for Browser4 Agents
REM This script creates both a portable app-image and Windows EXE installer

setlocal enabledelayedexpansion

echo ========================================
echo Browser4 Agents - Windows Installer Builder
echo ========================================
echo.

REM Check if Java is available
java -version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] Java not found. Please install JDK 17 or later.
    exit /b 1
)

REM Check if jpackage is available
jpackage --version >nul 2>&1
if errorlevel 1 (
    echo [ERROR] jpackage not found. Please ensure you're using JDK 17 or later (not JRE).
    exit /b 1
)

echo [INFO] Java and jpackage found.
echo.

REM Parse command line arguments
set BUILD_INSTALLER=false
set SKIP_TESTS=true

:parse_args
if "%~1"=="" goto end_parse
if /i "%~1"=="--installer" set BUILD_INSTALLER=true
if /i "%~1"=="--with-tests" set SKIP_TESTS=false
shift
goto parse_args
:end_parse

REM Display build configuration
echo Build Configuration:
echo   - Portable App-Image: YES
if "%BUILD_INSTALLER%"=="true" (
    echo   - Windows Installer: YES
    echo   - [WARN] Building installer requires WiX Toolset v3.x
    
    REM Check for WiX
    candle.exe -? >nul 2>&1
    if errorlevel 1 (
        echo   - [ERROR] WiX Toolset not found in PATH
        echo   - Please install from: https://github.com/wixtoolset/wix3/releases
        exit /b 1
    )
    echo   - WiX Toolset: Found
) else (
    echo   - Windows Installer: NO (use --installer flag to enable)
)
echo   - Skip Tests: %SKIP_TESTS%
echo.

REM Build command
set MVN_CMD=..\..\mvnw.cmd clean package -Pwin-jpackage

if "%SKIP_TESTS%"=="true" (
    set MVN_CMD=!MVN_CMD! -DskipTests
)

if "%BUILD_INSTALLER%"=="true" (
    set MVN_CMD=!MVN_CMD! -Djpackage.installer.skip=false
)

echo [INFO] Building Browser4 Agents...
echo [INFO] Command: %MVN_CMD%
echo.

REM Execute build
call %MVN_CMD%

if errorlevel 1 (
    echo.
    echo [ERROR] Build failed!
    exit /b 1
)

echo.
echo ========================================
echo Build completed successfully!
echo ========================================
echo.
echo Output files:
echo   - JAR: target\Browser4.jar
echo   - App-Image: target\jpackage\app-image\Browser4\Browser4.exe

if "%BUILD_INSTALLER%"=="true" (
    echo   - Installer: target\jpackage\dist\Browser4-4.4.0.exe
)

echo.
echo To run the portable version:
echo   target\jpackage\app-image\Browser4\Browser4.exe
echo.

if "%BUILD_INSTALLER%"=="true" (
    echo To install:
    echo   target\jpackage\dist\Browser4-4.4.0.exe
    echo.
)

endlocal
