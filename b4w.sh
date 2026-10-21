#!/bin/bash

# b4w.sh — Git Bash / Linux / macOS wrapper for browser4-cli.
#
# This script is the recommended way to run browser4-cli from Git Bash on
# Windows, for one concrete reason: it exports MSYS2_ARG_CONV_EXCL='*' before
# spawning pwsh, so argument values that begin with '/' (e.g. -olp "/product/",
# --path /, --selector "/div") reach the CLI verbatim instead of being
# rewritten into Windows paths by MSYS path conversion.
#
# ./b4w.ps1 works from Git Bash too — it inherits the invoking shell's working
# directory, and relative file arguments (--seed-file, --sql @file, -o) resolve
# against it.  Any '/'-leading value, however, needs the conversion disabled
# first; b4w.ps1 detects the rewritten form and prints a de-converted command
# to re-run (or use this wrapper, which never needs the workaround).
#
# Usage: ./b4w.sh [args...]          (same as ./b4w.ps1 [args...])
#
# Shell selection guide:
#   b4w.sh   — Git Bash / Linux / macOS (the Git Bash default: disables MSYS
#              path conversion and forwards args verbatim for pwsh)
#   b4w.ps1  — any shell, and the primary entry point on Windows PowerShell
#              (uses manual arg parsing to avoid -o/-i/-v interception)
#   b4w.bat  — cmd.exe (uses --% stop-parsing to avoid flag interception)
#
# Historical note (retired): an earlier version of this header warned that
# invoking ./b4w.ps1 directly from Git Bash "may cause the shell working
# directory to reset to the user home directory after each command".  That no
# longer reproduces: b4w.ps1 captures its own CWD and restores it on exit, and
# a child process cannot change its parent shell's directory anyway.  Verified
# on Git Bash (Git for Windows) + PowerShell 7 by comparing `pwd` before and
# after `./b4w.ps1 <command>` and by resolving a relative path afterwards.
# Keep only the conversion caveat above; if a CWD reset is ever seen again,
# record the exact Git Bash + PowerShell versions with the report.

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
