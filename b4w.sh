#!/bin/bash

# b4w.sh — Git Bash / Linux / macOS wrapper for browser4-cli.
#
# This script is the recommended way to run browser4-cli from Git Bash on
# Windows.  Running `./b4w.ps1` directly from Git Bash may cause the shell
# working directory to reset to the user home directory after each command
# (a side-effect of how PowerShell inherits and reports CWD from bash).
# This wrapper avoids that by using `exec pwsh -File`, passing the original
# arguments through verbatim ("$@").
#
# Usage: ./b4w.sh [args...]          (same as ./b4w.ps1 [args...])
#
# Shell selection guide:
#   b4w.sh   — Git Bash / Linux / macOS (auto-quotes args for pwsh safety)
#   b4w.ps1  — PowerShell (direct, now uses manual arg parsing to avoid
#              -o/-i/-v interception; preferred on Windows where pwsh is the
#              primary shell)
#   b4w.bat  — cmd.exe (uses --% stop-parsing to avoid flag interception)

. "$(dirname "$0")/bin/tools/install-powershell.sh"

# Argument handling:
#
# b4w.sh delegates to b4w.ps1 with `pwsh -File`, forwarding each argument as
# its own argv element ("$@").  b4w.ps1 deliberately declares NO param()
# block, so PowerShell collects every token (including dash-prefixed flags
# like --sql, --stdout, -v and -i) in $args and passes them to the CLI
# untouched — no single-quote wrapping or Invoke-Expression dance is needed,
# unlike the older `pwsh -Command "& script 'a' 'b'"` approach this script
# previously used.
#
# `pwsh -File` also makes the PowerShell process exit with the exact exit
# code that b4w.ps1 propagates from browser4-cli (usage errors exit 2, tool
# failures exit 1), so `./b4w.sh ...; echo $?` and &&-chains see the real
# CLI status.  (`pwsh -Command "& script ..."` collapses every nonzero
# script exit code to 1, which is why it is not used here.)
#
# MSYS2 path conversion: when Git Bash spawns a native executable (pwsh),
# arguments that begin with '/' are silently rewritten into Windows paths
# (e.g. "/product/" becomes "C:/Program Files/Git/product/").  Quoting does
# NOT stop this conversion, so pattern-like values such as
#   crawl <url> -olp "/product/"
# would reach the backend mangled and silently filter out every link.
# MSYS2_ARG_CONV_EXCL='*' disables conversion for every argument of the
# child process (MSYS_NO_PATHCONV=1 is the equivalent older spelling).

if [ -n "$MSYS2_ARG_CONV_EXCL" ]; then
    # Respect a user/global override if one is already set.
    :
else
    export MSYS2_ARG_CONV_EXCL='*'
fi

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# On Git Bash (MSYS2/Cygwin), pwd produces Unix-style paths like
# /d/workspace/... that pwsh cannot resolve.  Translate to a
# Windows-compatible path (e.g. D:/workspace/...).
if command -v cygpath >/dev/null 2>&1; then
    SCRIPT_DIR="$(cygpath -w "$SCRIPT_DIR")"
fi

if [ "$#" -eq 0 ]; then
    exec pwsh -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_DIR/b4w.ps1"
else
    exec pwsh -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_DIR/b4w.ps1" "$@"
fi
