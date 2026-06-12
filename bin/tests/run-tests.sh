#!/bin/bash
# Wrapper that invokes run-tests.ps1 with pwsh.
# All arguments are forwarded to the PowerShell script.
#
# After the test suite completes, if there were failures and an AI CLI
# is available on PATH, the logs are sent for analysis.
# Priority: claude > copilot.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGS_DIR="$SCRIPT_DIR/logs"

# Run the PowerShell test runner
pwsh -NoProfile -File "$SCRIPT_DIR/run-tests.ps1" "$@"
EXIT_CODE=$?

# If the test runner failed, try AI analysis (claude first, then copilot)
if [ $EXIT_CODE -ne 0 ]; then
    AI_CLI=""
    if command -v claude &>/dev/null; then
        AI_CLI="claude"
    elif command -v copilot &>/dev/null; then
        AI_CLI="copilot"
    fi

    if [ -n "$AI_CLI" ]; then
        echo ""
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
        echo "  Test suite exited with code $EXIT_CODE"
        echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"

        # Collect the most recent log files
        LOG_FILES=$(find "$LOGS_DIR" -name "*.log" -type f -mmin -120 2>/dev/null | head -30)
        if [ -n "$LOG_FILES" ]; then
            LOG_LIST=$(echo "$LOG_FILES" | while read -r f; do echo "  - $f"; done)
            echo "  Analysing failure logs with $AI_CLI ..."
            $AI_CLI -p "analyze the failures in these browser4-cli test logs and suggest root causes and fixes:
$LOG_LIST"
        else
            echo "  (no recent log files found in $LOGS_DIR)"
        fi
    fi
fi

exit $EXIT_CODE
