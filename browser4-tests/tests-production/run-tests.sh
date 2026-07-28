#!/bin/bash
# Wrapper that invokes run-tests.ps1 with pwsh.
# All arguments are forwarded to the PowerShell script.
#
# After the test suite completes, if there were failures and an AI CLI
# is available on PATH, the logs are sent for analysis.
# Priority: claude > copilot.

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
LOGS_DIR="$SCRIPT_DIR/logs"

# -------------------------------------------------------------------
# Locale detection (multi-platform: macOS, Linux, Windows Git Bash)
#
# Priority: $BROWSER4_TEST_LOCALE > $LANG > `locale` command > 'en'
# -------------------------------------------------------------------
detect_locale() {
    if [ -n "${BROWSER4_TEST_LOCALE:-}" ]; then
        echo "$BROWSER4_TEST_LOCALE" | cut -c1-2
        return
    fi
    if [ -n "${LANG:-}" ]; then
        echo "$LANG" | cut -d_ -f1 | cut -d. -f1
        return
    fi
    if command -v locale >/dev/null 2>&1; then
        locale 2>/dev/null | grep -E '^LANG=' | cut -d= -f2 | cut -d_ -f1 | cut -d. -f1
        return
    fi
    echo "en"
}

export BROWSER4_TEST_LOCALE
BROWSER4_TEST_LOCALE="$(detect_locale)"

# Build argument list, appending -Locale if the caller didn't already supply it.
# This ensures locale flows through to the PowerShell orchestrator without
# requiring every invocation to spell out -Locale.
ARGS=("$@")
if ! echo "$*" | grep -qiE '(-|/)Locale'; then
    ARGS+=("-Locale" "$BROWSER4_TEST_LOCALE")
fi

# Run the PowerShell test runner
pwsh -NoProfile -File "$SCRIPT_DIR/run-tests.ps1" "${ARGS[@]}"
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
