#!/usr/bin/env bash
set -euo pipefail

# -------------------------------------------------------------------
# Swarm agents lifecycle test (bash version).
#
# All CLI invocations log output to a timestamped directory under
# bin/tests/logs/.  On failure, log paths are printed and an AI CLI
# is invoked for analysis (priority: claude > copilot).
# -------------------------------------------------------------------

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

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

TEST_LOCALE="$(detect_locale)"

# Select a locale-appropriate test URL (simple / portal page).
case "$TEST_LOCALE" in
    zh)
        SWARM_URL="https://www.baidu.com/"
        ;;
    *)
        SWARM_URL="https://example.com/"
        ;;
esac

# --- Logging setup ---
TIMESTAMP=$(date +'%Y%m%d_%H%M%S')
LOG_DIR="$SCRIPT_DIR/logs/swarm-agents-sh_${TIMESTAMP}"
mkdir -p "$LOG_DIR"

CMD_INDEX=0
declare -a CMD_LOGS=()
declare -a FAILED_LOGS=()

# Wrapper: run a browser4-cli command, log everything, track pass/fail.
run_cli() {
    local label="$1"
    shift
    CMD_INDEX=$((CMD_INDEX + 1))
    local idx
    idx=$(printf "%04d" "$CMD_INDEX")
    local safe_name
    safe_name=$(echo "$*" | sed 's/[^a-zA-Z0-9_.-]/_/g' | sed 's/__*/_/g' | head -c 80)
    local log_file="$LOG_DIR/cmd_${idx}_${safe_name}.log"

    local start_ts
    start_ts=$(date +'%Y-%m-%d %H:%M:%S')

    {
        echo "================================================================================"
        echo "COMMAND     : $label"
        echo "ARGS        : $*"
        echo "STARTED     : $start_ts"
        echo "================================================================================"
        echo "STDOUT / STDERR:"
    } > "$log_file"

    local exit_code=0
    browser4-cli "$@" >> "$log_file" 2>&1 || exit_code=$?

    local end_ts
    end_ts=$(date +'%Y-%m-%d %H:%M:%S')

    {
        echo ""
        echo "================================================================================"
        echo "FINISHED    : $end_ts"
        echo "EXIT CODE   : $exit_code"
        echo "STATUS      : $([ $exit_code -eq 0 ] && echo 'PASS' || echo 'FAIL')"
        echo "================================================================================"
    } >> "$log_file"

    CMD_LOGS+=("$log_file")
    if [ $exit_code -ne 0 ]; then
        FAILED_LOGS+=("$log_file")
        echo "  ❌ FAIL (exit=$exit_code) $label  📄 $log_file"
    else
        echo "  ✅ PASS $label"
    fi

    return $exit_code
}

# Run even if a command fails (we handle failures manually).
set +e

echo "════════════════════════════════════════════════════════"
echo "  TEST: swarm-agents (bash)"
echo "  Locale: $TEST_LOCALE"
echo "  Swarm URL: $SWARM_URL"
echo "  Logs: $LOG_DIR"
echo "════════════════════════════════════════════════════════"
echo ""

# --- Open session ---
echo "━━━ Opening session ━━━"
run_cli "open" open

echo ""
echo "━━━ Creating swarm session ━━━"
run_cli "swarm create" swarm create

echo ""
echo "━━━ Submitting swarm task ━━━"
OUTPUT=$(browser4-cli swarm submit "$SWARM_URL" 2>&1)
SUBMIT_EXIT=$?
echo "$OUTPUT"

# Also log the submit output
SUBMIT_LOG="$LOG_DIR/cmd_$(printf "%04d" $((CMD_INDEX + 1)))_swarm_submit.log"
{
    echo "================================================================================"
    echo "COMMAND     : swarm submit $SWARM_URL"
    echo "EXIT CODE   : $SUBMIT_EXIT"
    echo "================================================================================"
    echo "$OUTPUT"
} > "$SUBMIT_LOG"
CMD_INDEX=$((CMD_INDEX + 1))
CMD_LOGS+=("$SUBMIT_LOG")
if [ $SUBMIT_EXIT -ne 0 ]; then
    FAILED_LOGS+=("$SUBMIT_LOG")
    echo "  ❌ FAIL (exit=$SUBMIT_EXIT) swarm submit  📄 $SUBMIT_LOG"
fi

# Parse task ID
TASK_ID=$(echo "$OUTPUT" | sed -nE 's/.*Task ID:[[:space:]]*([^[:space:]]+).*/\1/p' | head -n 1)
if [ -z "$TASK_ID" ]; then
    echo "❌ Unable to parse Task ID from swarm submit output" >&2
    echo "Output:" >&2
    echo "$OUTPUT" >&2
    TASK_ID="unknown"
fi
echo "Task ID: $TASK_ID"

echo ""
echo "━━━ Polling swarm status (task: $TASK_ID) ━━━"

DONE=false
for attempt in 1 2 3 4 5 6 7 8 9 10 11 12 13 14 15; do
    STATUS_OUTPUT=$(browser4-cli swarm status "$TASK_ID" 2>&1)
    STATUS_EXIT=$?
    echo "  Status poll $attempt: $STATUS_OUTPUT"

    # Log each status poll
    STATUS_LOG="$LOG_DIR/cmd_$(printf "%04d" $((CMD_INDEX + 1)))_swarm_status_${attempt}.log"
    {
        echo "================================================================================"
        echo "COMMAND     : swarm status $TASK_ID (poll $attempt)"
        echo "EXIT CODE   : $STATUS_EXIT"
        echo "================================================================================"
        echo "$STATUS_OUTPUT"
    } > "$STATUS_LOG"
    CMD_INDEX=$((CMD_INDEX + 1))
    CMD_LOGS+=("$STATUS_LOG")
    if [ $STATUS_EXIT -ne 0 ]; then
        FAILED_LOGS+=("$STATUS_LOG")
    fi

    if echo "$STATUS_OUTPUT" | grep -qE '"done"[[:space:]]*:[[:space:]]*true' || \
       echo "$STATUS_OUTPUT" | grep -qE '"isDone"[[:space:]]*:[[:space:]]*true' || \
       echo "$STATUS_OUTPUT" | grep -qE 'status:[[:space:]]*done'; then
        echo "  Task $TASK_ID is done."
        DONE=true
        break
    fi
    sleep 3
done

if [ "$DONE" != true ]; then
    echo "⚠ Task $TASK_ID did not complete within 15 polls"
fi

echo ""
echo "━━━ Cleanup ━━━"
run_cli "close" close

# --- Report ---
echo ""
echo "════════════════════════════════════════════════════════"
echo "  TEST REPORT: swarm-agents (bash)"
echo "════════════════════════════════════════════════════════"
echo "  Log directory : $LOG_DIR"
echo "  Total commands: ${#CMD_LOGS[@]}"
echo "  Passed        : $((${#CMD_LOGS[@]} - ${#FAILED_LOGS[@]}))"
echo "  Failed        : ${#FAILED_LOGS[@]}"

if [ ${#FAILED_LOGS[@]} -gt 0 ]; then
    echo ""
    echo "  ── FAILURE DETAILS ──"
    for f in "${FAILED_LOGS[@]}"; do
        echo "  ❌ Log: $f"
    done

    # --- AI analysis (prefer claude, fall back to copilot) ---
    AI_CLI=""
    if command -v claude &>/dev/null; then
        AI_CLI="claude"
    elif command -v copilot &>/dev/null; then
        AI_CLI="copilot"
    fi

    if [ -n "$AI_CLI" ]; then
        echo ""
        echo "🤖 Running failure analysis with $AI_CLI ..."
        LOG_LIST=$(printf "  - %s\n" "${FAILED_LOGS[@]}")
        $AI_CLI -p "analyze the failures in these browser4-cli test logs and suggest root causes and fixes:
$LOG_LIST"
        echo ""
        echo "  📄 Analysis saved in log directory: $LOG_DIR"
    else
        echo "  ⚠ Neither claude nor copilot found on PATH — skipping AI failure analysis"
    fi

    exit 1
else
    echo ""
    echo "  ✅ ALL COMMANDS PASSED"
    exit 0
fi
