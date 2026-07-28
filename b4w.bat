@echo off
REM b4w.bat — CMD entry point for browser4-cli
REM
REM Uses PowerShell's --% stop-parsing token to prevent PowerShell from
REM consuming short flags like -i (-InformationAction) and -v (-Verbose).
REM
REM Usage: b4w.bat <command> [args...]
REM   b4w.bat snapshot -v 0     -- works correctly in CMD
REM   b4w.bat snapshot -i        -- works correctly in CMD
REM
REM For Git Bash, use ./b4w.sh instead. For PowerShell directly, use ./b4w.ps1
REM and pass conflicting flags after -- (e.g. ./b4w.ps1 -- snapshot -i).

setlocal
set "SCRIPT_DIR=%~dp0"
pwsh -NoProfile -ExecutionPolicy Bypass -File "%SCRIPT_DIR%b4w.ps1" --% %*
exit /b %ERRORLEVEL%
