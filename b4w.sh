#!/bin/bash

# b4w.sh — Git Bash / Linux / macOS wrapper for browser4-cli.
#
# This script is the recommended way to run browser4-cli from Git Bash on
# Windows.  Running `./b4w.ps1` directly from Git Bash may cause the shell
# working directory to reset to the user home directory after each command
# (a side-effect of how PowerShell inherits and reports CWD from bash).
# This wrapper avoids that by using `exec pwsh` with individually-quoted
# arguments, which also prevents dash-prefixed flags from being consumed
# as PowerShell parameter names.
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

# When PowerShell receives arguments from bash, dash-prefixed flags like
# --sql, --stdout, -v can be misinterpreted as PowerShell parameter names
# rather than literal CLI arguments.  This causes errors such as:
#   snapshot -v 0 --stdout  →  Unknown command: 'snapshot-0'
#   swarm query --sql @q.sql →  Missing required argument: <url>
#
# To prevent this, we wrap every argument in PowerShell single quotes
# before passing it to pwsh.  PowerShell treats single-quoted tokens as
# literal string values — no variable expansion, no escape processing
# (except '' for a literal single quote).  This safely handles arguments
# containing spaces, double quotes, dollar signs, and backticks.
#
# Prior approach (double-quote wrapping with \" escaping) broke on JSON
# values like '{"lang":"en"}' because \" inside -Command interacts
# destructively with PowerShell's command-line parser.
#
# Workaround for direct ./b4w.ps1 users in Git Bash:
#   ./b4w.ps1 "swarm" "query" "--sql" "@query.sql" "--seed-file" "./urls.txt"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
# On Git Bash (MSYS2/Cygwin), pwd produces Unix-style paths like
# /d/workspace/... that pwsh cannot resolve.  Translate to a
# Windows-compatible path (e.g. D:/workspace/...).
if command -v cygpath >/dev/null 2>&1; then
    SCRIPT_DIR="$(cygpath -w "$SCRIPT_DIR")"
fi
ARGS=""
for arg in "$@"; do
    # Wrap each argument in PowerShell single quotes so that special
    # characters (spaces, double quotes, $, backticks) are treated
    # literally.  PowerShell single-quoted strings only recognise ''
    # as an escape (for a literal single quote), so we escape any
    # embedded single quotes before wrapping.
    safe="${arg//\'/\'\'}"
    ARGS="$ARGS '$safe'"
done

if [ -z "$ARGS" ]; then
    exec pwsh -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_DIR/b4w.ps1"
else
    # Use -Command with the call operator (&) so PowerShell evaluates the
    # individually single-quoted arguments as string literals.
    exec pwsh -NoProfile -ExecutionPolicy Bypass -Command "& '$SCRIPT_DIR/b4w.ps1' $ARGS"
fi
