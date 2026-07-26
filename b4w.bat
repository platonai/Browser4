@echo off
REM b4w.bat — Command Prompt (cmd.exe) wrapper for browser4-cli.
REM
REM This script is the recommended way to run browser4-cli from Command Prompt
REM on Windows.  Running `b4w.ps1` directly from cmd.exe passes arguments
REM through to PowerShell, but common PowerShell parameters like -i
REM (-InformationAction) and -v (-Verbose) may be consumed by the PowerShell
REM parameter binder before they reach the CLI binary.
REM
REM By using the --% stop-parsing token, PowerShell treats all subsequent
REM arguments as literal strings, preventing flag interception.
REM
REM Usage: b4w [args...]          (same as b4w.ps1 [args...])

set "SCRIPT_DIR=%~dp0"
if "%~1"=="" (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%b4w.ps1"
) else (
    pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%b4w.ps1" --% %*
)
