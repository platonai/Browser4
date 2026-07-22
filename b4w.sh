#!/bin/bash

. "$(dirname "$0")/bin/tools/install-powershell.sh"

echo "It is strongly recommended to launch \`pwsh\` and run the .ps1 commands directly within the \`pwsh\` terminal."
echo ""

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
