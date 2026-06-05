@echo off
setlocal EnableExtensions

set "SCRIPT_DIR=%~dp0"
set "EXE_PATH=%SCRIPT_DIR%\cli\browser4-cli\target\release\browser4-cli.exe"
set "CARGO_DIR=%SCRIPT_DIR%\cli\browser4-cli"

if not exist "%EXE_PATH%" (
    echo [b4.cmd] browser4-cli not built -- building now...
    pushd "%CARGO_DIR%"
    cargo build --release
    popd
    if not exist "%EXE_PATH%" (
        echo [b4.cmd] ERROR: build failed -- executable still not found: "%EXE_PATH%"
        exit /b 1
    )
)

"%EXE_PATH%" %*
exit /b %ERRORLEVEL%
