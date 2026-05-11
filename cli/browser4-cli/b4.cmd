@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "EXE_PATH=%SCRIPT_DIR%target\release\browser4-cli.exe"

if not exist "%EXE_PATH%" (
	echo [browser4-cli.cmd] ERROR: executable not found: "%EXE_PATH%"
	echo [browser4-cli.cmd] Run: cargo build --release ^(in sdks\browser4-cli^)
	exit /b 1
)

"%EXE_PATH%" %*
exit /b %ERRORLEVEL%
