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
# To prevent this, we quote every argument individually before passing
# it to pwsh.  PowerShell treats quoted tokens as string values, never
# as parameter bindings.
#
# Workaround for direct ./b4w.ps1 users in Git Bash:
#   ./b4w.ps1 "swarm" "query" "--sql" "@query.sql" "--seed-file" "./urls.txt"

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
ARGS=""
for arg in "$@"; do
    # Escape any double-quote characters inside the argument
    safe="${arg//\"/\\\"}"
    ARGS="$ARGS \"$safe\""
done

if [ -z "$ARGS" ]; then
    exec pwsh -NoProfile -ExecutionPolicy Bypass -File "$SCRIPT_DIR/b4w.ps1"
else
    # Use -Command with the call operator (&) so PowerShell evaluates the
    # individually-quoted arguments as string literals.
    exec pwsh -NoProfile -ExecutionPolicy Bypass -Command "& '$SCRIPT_DIR/b4w.ps1' $ARGS"
fi
