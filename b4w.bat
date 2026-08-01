@echo off
REM b4w.bat - CMD entry point for browser4-cli.
REM Pass all arguments through to b4w.ps1 unchanged.
setlocal
set "SCRIPT_DIR=%~dp0"
pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%b4w.ps1" %*
exit /b %ERRORLEVEL%
