@echo off
setlocal EnableExtensions

rem Run Browser4 via a PowerShell launcher to ensure UTF-8 console output.
rem Priority: PowerShell 7+ (pwsh) -> Windows PowerShell (powershell.exe)

set "SCRIPT_DIR=%~dp0"

where pwsh >nul 2>&1
if %ERRORLEVEL% EQU 0 (
  pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%Browser4.ps1" %*
  exit /b %ERRORLEVEL%
)

where powershell >nul 2>&1
if %ERRORLEVEL% EQU 0 (
  powershell -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%Browser4.ps1" %*
  exit /b %ERRORLEVEL%
)

echo Neither 'pwsh' nor 'powershell' was found on PATH.
echo Please install PowerShell, or run "%SCRIPT_DIR%Browser4.exe" directly.
exit /b 9009

