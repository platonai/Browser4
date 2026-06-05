@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "EXE_PATH=%SCRIPT_DIR%\cli\browser4-cli\target\release\browser4-cli.exe"

if not exist "%EXE_PATH%" (
	echo [b4.cmd] ERROR: executable not found: "%EXE_PATH%"
	echo [b4.cmd] Run: cargo build --release ^(in cli\browser4-cli^)
	exit /b 1
)

"%EXE_PATH%" %*
exit /b %ERRORLEVEL%
