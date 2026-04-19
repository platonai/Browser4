#!/usr/bin/env bash

# =============================================================================
# run-e2e-tests.sh - Use Case Based End-to-End Test Suite
# =============================================================================
# This script reads use case files from browser4-tests/browser4-tests-common/src/main/resources/e2e/scenarios/happy_path/use-cases/
# and executes them against the Browser4 server using the api/commands/plain endpoint.
#
# Each use case file contains:
#   - Task description (lines starting with #)
#   - Task content (the actual steps for the agent)
#
# The script sends each task to the server and validates the response
# using appropriate assertions based on task type.
# =============================================================================

repoRoot=$(git rev-parse --show-toplevel 2>null)
cd "$repoRoot" || exit 1

set -e

# =============================================================================
# Configuration
# =============================================================================
readonly API_BASE="${API_BASE:-http://localhost:8182}"
readonly COMMAND_ENDPOINT="$API_BASE/api/commands/plain?async=1"
readonly COMMAND_STATUS_BASE="$API_BASE/api/commands"
readonly USE_CASES_DIR="$repoRoot/browser4-tests/browser4-tests-common/src/main/resources/e2e/scenarios/happy_path/use-cases"
readonly TEST_RESULTS_DIR="./target/test-results/use-cases"
readonly TIMESTAMP="$(date '+%Y%m%d_%H%M%S')"
readonly LOG_FILE="${TEST_RESULTS_DIR}/use_case_tests_${TIMESTAMP}.log"

# Timeout settings (seconds)
# Per-task timeout defaults to 3 minutes; suite timeout defaults to 20 minutes
readonly TASK_TIMEOUT="${TASK_TIMEOUT:-180}"
readonly TOTAL_TIMEOUT="${TOTAL_TIMEOUT:-1200}"

# Minimum success rate for overall pass (percentage, integer)
readonly MIN_SUCCESS_RATE="${MIN_SUCCESS_RATE:-50}"

# Test selection (comma-separated list of test numbers or "all")
TEST_SELECTION="${TEST_SELECTION:-all}"

# How many tests to run (applies after selection/filtering). 0 means all.
TEST_COUNT="${TEST_COUNT:-0}"

# Execution order: random | sequential
EXECUTION_ORDER="${EXECUTION_ORDER:-random}"

# Verbose mode
VERBOSE="${VERBOSE:-false}"

# Skip server check
SKIP_SERVER_CHECK="${SKIP_SERVER_CHECK:-false}"

# Level filter (comma-separated); default runs Simple tasks
LEVEL_FILTER="${LEVEL_FILTER:-Simple}"

# Default task note appended to every task
readonly DEFAULT_TASK_NOTE="If you exceed either 30 steps or 10 minutes, you must complete the task immediately."

# =============================================================================
# Colors
# =============================================================================
readonly RED='\033[0;31m'
readonly GREEN='\033[0;32m'
readonly YELLOW='\033[1;33m'
readonly BLUE='\033[0;34m'
readonly PURPLE='\033[0;35m'
readonly CYAN='\033[0;36m'
readonly BOLD='\033[1m'
readonly NC='\033[0m'

# =============================================================================
# Counters
# =============================================================================
TOTAL_TESTS=0
EXECUTED_TESTS=0
PASSED_TESTS=0
FAILED_TESTS=0
SKIPPED_TESTS=0
TIMED_OUT_TESTS=0
LEVEL_FILTERS=()

# =============================================================================
# Utility Functions
# =============================================================================

log() {
    local timestamp
    timestamp=$(date '+%Y-%m-%d %H:%M:%S')
    echo -e "[$timestamp] $1" | tee -a "$LOG_FILE"
}

vlog() {
    [[ "$VERBOSE" == "true" ]] && log "${CYAN}[VERBOSE]${NC} $1"
}

record_response_if_changed() {
    local content="$1"
    local file="$2"
    local last_ref="$3"
    local label="${4:-}"
    local last_value="${!last_ref-}"

    if [[ "$content" != "$last_value" ]]; then
        local timestamp
        timestamp=$(date '+%Y-%m-%d %H:%M:%S')
        if [[ -n "$label" ]]; then
            printf "[%s] [%s] %s\n" "$timestamp" "$label" "$content" >> "$file"
        else
            printf "[%s] %s\n" "$timestamp" "$content" >> "$file"
        fi
        printf -v "$last_ref" '%s' "$content"
        return 0
    fi

    return 1
}

ensure_directories() {
    mkdir -p "$TEST_RESULTS_DIR"
}

level_matches_filter() {
    local level="$1"
    local level_lc
    level_lc=$(echo "$level" | tr '[:upper:]' '[:lower:]')
    for lf in "${LEVEL_FILTERS[@]}"; do
        [[ "$lf" == "all" ]] && return 0
        if [[ "$level_lc" == "$lf" ]]; then
            return 0
        fi
    done
    return 1
}

# Fisher-Yates shuffle to avoid external dependencies like shuf
shuffle_array() {
    local arr=("$@")
    local shuffled=()
    local i rand
    while ((${#arr[@]})); do
        rand=$((RANDOM % ${#arr[@]}))
        shuffled+=("${arr[rand]}")
        arr=("${arr[@]:0:rand}" "${arr[@]:rand+1}")
    done
    printf '%s\n' "${shuffled[@]}"
}

check_server() {
    log "${BLUE}[INFO]${NC} Checking Browser4 server at $API_BASE..."
    if curl -s --connect-timeout 5 --max-time 10 "$API_BASE/actuator/health" >/dev/null 2>&1; then
        log "${GREEN}[SUCCESS]${NC} Browser4 server is healthy and responding"
        return 0
    elif curl -s --connect-timeout 5 --max-time 10 "$API_BASE/" >/dev/null 2>&1; then
        log "${YELLOW}[WARNING]${NC} Server responding but health check endpoint unavailable"
        return 0
    else
        log "${RED}[ERROR]${NC} Browser4 server not accessible at $API_BASE"
        log "${CYAN}[HINT]${NC} Start Browser4 with:"
        log "    ${BOLD}java -DOPENROUTER_API_KEY=\${OPENROUTER_API_KEY} -jar Browser4.jar${NC}"
        return 1
    fi
}

# =============================================================================
# Use Case File Processing
# =============================================================================

# Extract task description (comment lines) from use case file
# Returns up to MAX_DESCRIPTION_LINES lines of description (typically: title, level, type, description)
extract_description() {
    local file="$1"
    local max_lines=5  # Sufficient to capture title, level, type, description, and note
    grep '^#' "$file" | sed 's/^# *//' | head -"$max_lines"
}

# Extract task level from use case file
extract_level() {
    local file="$1"
    local level_line
    level_line=$(grep -i '^# Level:' "$file" | head -1)
    if [[ -n "$level_line" ]]; then
        echo "$level_line" | sed 's/^# Level: *//'
    else
        echo "Simple"  # Default level
    fi
}

# Extract task content (non-comment lines) from use case file
extract_task_content() {
    local file="$1"
    grep -v '^#' "$file" | grep -v '^[[:space:]]*$'
}

# Get timeout based on task level
get_timeout_for_level() {
    echo "$TASK_TIMEOUT"
}

# =============================================================================
# Assertion Functions
# =============================================================================

# Basic assertion: HTTP status code is 2xx or 3xx
assert_http_success() {
    local http_status="$1"
    [[ "$http_status" =~ ^[23][0-9][0-9]$ ]]
}

# Assert response contains expected content patterns
assert_response_has_content() {
    local response_file="$1"
    local min_length="${2:-10}"

    if [[ ! -f "$response_file" ]]; then
        return 1
    fi

    local response_content_length
    response_content_length=$(wc -c < "$response_file")
    [[ "$response_content_length" -gt "$min_length" ]]
}

# Assert response is valid JSON (when expected)
assert_valid_json() {
    local response_file="$1"

    if command -v jq &>/dev/null; then
        jq empty "$response_file" 2>/dev/null
    else
        # Fallback: check for JSON-like structure
        grep -qE '^\s*[\[{]' "$response_file"
    fi
}

# Assert response indicates task completion
assert_task_completed() {
    local response_file="$1"

    # Check for common completion indicators
    if grep -qiE '"(status|state)"[[:space:]]*:[[:space:]]*"(completed|done|success|finished)"' "$response_file" 2>/dev/null; then
        return 0
    fi

    # Check for result/data presence
    if grep -qiE '"(result|data|output|summary)"[[:space:]]*:' "$response_file" 2>/dev/null; then
        return 0
    fi

    # Check for non-empty response with substantial content
    # Minimum 50 bytes ensures there's meaningful content beyond empty JSON/error stubs
    local min_substantial_content=50
    local response_content_length
    response_content_length=$(wc -c < "$response_file")
    [[ "$response_content_length" -gt "$min_substantial_content" ]]
}

# =============================================================================
# Test Execution
# =============================================================================

# Execute a single use case test
run_use_case_test() {
    local use_case_file="$1"
    local test_number="$2"
    local total_tests="$3"

    local filename
    filename=$(basename "$use_case_file")
    local test_name="${filename%.txt}"

    TOTAL_TESTS=$((TOTAL_TESTS + 1))

    log ""
    log "===================================================================="
    log "${PURPLE}[TEST $test_number/$total_tests]${NC} ${BOLD}$test_name${NC}"
    log "--------------------------------------------------------------------"

    # Extract task metadata
    local description
    description=$(extract_description "$use_case_file")
    local level
    level=$(extract_level "$use_case_file")
    local task_content
    task_content=$(extract_task_content "$use_case_file")
    local task_payload="$task_content"
    if [[ -n "$DEFAULT_TASK_NOTE" ]]; then
        task_payload="${task_payload}"$'\n\n'"$DEFAULT_TASK_NOTE"
    fi
    local timeout
    timeout=$(get_timeout_for_level "$level")

    log "${CYAN}[DESCRIPTION]${NC}"
    echo "$description" | while read -r line; do
        log "    $line"
    done
    log "${CYAN}[LEVEL]${NC} $level"
    log "${CYAN}[TIMEOUT]${NC} ${timeout}s"

    if [[ "$VERBOSE" == "true" ]]; then
        log "${CYAN}[TASK CONTENT]${NC}"
        echo "$task_payload" | while read -r line; do
            log "    $line"
        done
    fi

    # Prepare result files
    local status_file="${TEST_RESULTS_DIR}/${test_name}_status.log"
    local result_file="${TEST_RESULTS_DIR}/${test_name}_result.json"
    : > "$status_file"
    local last_status_content=""

    # Start time tracking
    local start_time
    start_time=$(date '+%Y-%m-%d %H:%M:%S %Z')
    local start_epoch
    start_epoch=$(date +%s)
    log "${BLUE}[INFO]${NC} Start time: $start_time"

    # Execute the command
    log "${BLUE}[INFO]${NC} Sending task to server using async plain command..."

    set +e
    local submit_output
    submit_output=$(curl -s -w '|%{http_code}' \
        --connect-timeout 5 \
        --max-time 15 \
        -X POST \
        -H "Content-Type: text/plain" \
        --data "$task_payload" \
        "$COMMAND_ENDPOINT")
    local submit_exit=$?
    set -e

    local submit_http="${submit_output##*|}"
    local command_id="${submit_output%|*}"
    command_id="${command_id%$'\n'}"
    command_id="${command_id%$'\r'}"
    command_id="${command_id%\"}"
    command_id="${command_id#\"}"
    record_response_if_changed "$submit_output" "$status_file" last_status_content "submit" || true

    local test_passed=false
    local test_result=""

    if [[ $submit_exit -ne 0 ]]; then
        FAILED_TESTS=$((FAILED_TESTS + 1))
        EXECUTED_TESTS=$((EXECUTED_TESTS + 1))
        log "${RED}[FAIL]${NC} ❌ Failed to submit command (exit code: $submit_exit)"
        log "${BLUE}[INFO]${NC} End time: $(date '+%Y-%m-%d %H:%M:%S %Z') (Duration: $(( $(date +%s) - start_epoch ))s)"
        log "===================================================================="
        return 1
    fi

    if [[ -z "$command_id" ]] || ! assert_http_success "$submit_http"; then
        FAILED_TESTS=$((FAILED_TESTS + 1))
        EXECUTED_TESTS=$((EXECUTED_TESTS + 1))
        log "${RED}[FAIL]${NC} ❌ Invalid response from server (HTTP: $submit_http, id: '$command_id')"
        log "${BLUE}[INFO]${NC} End time: $(date '+%Y-%m-%d %H:%M:%S %Z') (Duration: $(( $(date +%s) - start_epoch ))s)"
        log "===================================================================="
        return 1
    fi

    log "${BLUE}[INFO]${NC} Command ID: $command_id"

    local status_url="$COMMAND_STATUS_BASE/$command_id/status"
    local result_url="$COMMAND_STATUS_BASE/$command_id/result"
    local last_change_epoch
    last_change_epoch=$(date +%s)
    local stale_intervals=0

    while true; do
        local now_epoch
        now_epoch=$(date +%s)
        if (( now_epoch - start_epoch >= timeout )); then
            TIMED_OUT_TESTS=$((TIMED_OUT_TESTS + 1))
            EXECUTED_TESTS=$((EXECUTED_TESTS + 1))
            test_result="TIMEOUT"
            log "${YELLOW}[TIMEOUT]${NC} Task exceeded per-test timeout (${timeout}s); aborting"
            break
        fi
        set +e
        local status_output
        status_output=$(curl -s -w '|%{http_code}' --max-time "$timeout" "$status_url")
        local status_exit=$?
        set -e

        if [[ $status_exit -ne 0 ]]; then
            FAILED_TESTS=$((FAILED_TESTS + 1))
            EXECUTED_TESTS=$((EXECUTED_TESTS + 1))
            test_result="FAIL"
            log "${RED}[FAIL]${NC} ❌ Failed to query status (exit code: $status_exit)"
            break
        fi

        local status_http="${status_output##*|}"
        local status_body="${status_output%|*}"

        if record_response_if_changed "$status_body" "$status_file" last_status_content "status"; then
            last_change_epoch=$(date +%s)
            stale_intervals=0
        else
            if (( now_epoch - last_change_epoch >= 30 )); then
                stale_intervals=$((stale_intervals + 1))
                last_change_epoch=$now_epoch
                log "${YELLOW}[WAIT]${NC} Status unchanged for $((stale_intervals * 30))s (id: $command_id)"
            fi
        fi

        if ! assert_http_success "$status_http"; then
            FAILED_TESTS=$((FAILED_TESTS + 1))
            EXECUTED_TESTS=$((EXECUTED_TESTS + 1))
            test_result="FAIL"
            log "${RED}[FAIL]${NC} ❌ Status HTTP error: $status_http"
            break
        fi

        # if there is a line contains "isDone" and "true", then the task is completed
        if [[ "$status_body" =~ isDone.*true ]] || [[ "$status_body" =~ done.*true ]]; then
            log "${BLUE}[INFO]${NC} Task completed, fetching result..."
            set +e
            local result_output
            result_output=$(curl -s -w '|%{http_code}' --max-time "$timeout" "$result_url")
            local result_exit=$?
            set -e

            if [[ $result_exit -ne 0 ]]; then
                FAILED_TESTS=$((FAILED_TESTS + 1))
                EXECUTED_TESTS=$((EXECUTED_TESTS + 1))
                test_result="FAIL"
                log "${RED}[FAIL]${NC} ❌ Failed to fetch result (exit code: $result_exit)"
            else
                local result_http="${result_output##*|}"
                local result_body="${result_output%|*}"
                record_response_if_changed "$result_body" "$status_file" last_status_content "result" || true
                if assert_http_success "$result_http"; then
                    printf "%s\n" "$result_body" > "$result_file"
                    PASSED_TESTS=$((PASSED_TESTS + 1))
                    EXECUTED_TESTS=$((EXECUTED_TESTS + 1))
                    test_result="PASS"
                    test_passed=true
                    log "${GREEN}[PASS]${NC} ✅ Result saved to $result_file"
                else
                    FAILED_TESTS=$((FAILED_TESTS + 1))
                    EXECUTED_TESTS=$((EXECUTED_TESTS + 1))
                    test_result="FAIL"
                    log "${RED}[FAIL]${NC} ❌ Result HTTP error: $result_http"
                fi
            fi
            break
        fi

        if (( stale_intervals >= 3 )); then
            TIMED_OUT_TESTS=$((TIMED_OUT_TESTS + 1))
            EXECUTED_TESTS=$((EXECUTED_TESTS + 1))
            test_result="TIMEOUT"
            log "${YELLOW}[TIMEOUT]${NC} Status not updated for 90 seconds, marking as timeout"
            break
        fi

        sleep 1
    done

    local end_time
    end_time=$(date '+%Y-%m-%d %H:%M:%S %Z')
    local end_epoch
    end_epoch=$(date +%s)
    local duration=$((end_epoch - start_epoch))

    log "${BLUE}[INFO]${NC} End time: $end_time (Duration: ${duration}s)"
    log "===================================================================="

    return $([ "$test_passed" == "true" ] && echo 0 || echo 1)
}

# =============================================================================
# Main Test Runner
# =============================================================================

run_all_tests() {
    log "${BLUE}[INFO]${NC} ${BOLD}Starting Use Case Test Suite${NC}"
    log "${BLUE}[INFO]${NC} API Endpoint: $COMMAND_ENDPOINT"
    log "${BLUE}[INFO]${NC} Use Cases Directory: $USE_CASES_DIR"
    log "${BLUE}[INFO]${NC} Level filter: $LEVEL_FILTER"

    LEVEL_FILTERS=()
    IFS=',' read -ra level_filter_values <<< "$LEVEL_FILTER"
    for lf in "${level_filter_values[@]}"; do
        lf=$(echo "$lf" | tr -d ' ')
        lf=$(echo "$lf" | tr '[:upper:]' '[:lower:]')
        [[ -n "$lf" ]] && LEVEL_FILTERS+=("$lf")
    done
    if (( ${#LEVEL_FILTERS[@]} == 0 )); then
        LEVEL_FILTERS=("simple")
    fi

    # Get list of use case files
    use_case_files=()
    while IFS= read -r -d '' file; do
        use_case_files+=("$file")
    done < <(find "$USE_CASES_DIR" -name "*.txt" -type f -print0 | sort -z)

    total_files=${#use_case_files[@]}

    if [[ $total_files -eq 0 ]]; then
        log "${RED}[ERROR]${NC} No use case files found in $USE_CASES_DIR"
        exit 1
    fi

    log "${BLUE}[INFO]${NC} Found $total_files use case files"

    # Build list of tests to run
    tests_to_run=()
    if [[ "$TEST_SELECTION" == "all" ]]; then
        tests_to_run=("${use_case_files[@]}")
    else
        # Parse comma-separated test numbers
        IFS=',' read -ra selected_numbers <<< "$TEST_SELECTION"
        for num in "${selected_numbers[@]}"; do
            # Trim whitespace
            num=$(echo "$num" | tr -d ' ')
            # Find matching file
            for file in "${use_case_files[@]}"; do
                if [[ "$(basename "$file")" == "$num"* ]]; then
                    tests_to_run+=("$file")
                    break
                fi
            done
        done
    fi

    # Filter by level
    if ((${#tests_to_run[@]})); then
        filtered_tests=()
        for file in "${tests_to_run[@]}"; do
            lvl=$(extract_level "$file")
            # Check if level matches filter (set +e to prevent exit on non-zero)
            set +e
            level_matches_filter "$lvl"
            match_rc=$?
            set -e
            if [[ $match_rc -eq 0 ]]; then
                filtered_tests+=("$file")
            else
                SKIPPED_TESTS=$((SKIPPED_TESTS + 1))
                vlog "${YELLOW}[SKIP]${NC} Skipping $(basename "$file") due to level '$lvl'"
            fi
        done
        tests_to_run=("${filtered_tests[@]}")
    fi

    total_selected=${#tests_to_run[@]}
    count_label="$TEST_COUNT"
    [[ "$TEST_COUNT" == "0" ]] && count_label="all"
    log "${BLUE}[INFO]${NC} Tests selected: $total_selected | Order: $EXECUTION_ORDER | Count: $count_label"

    if ! [[ "$TEST_COUNT" =~ ^[0-9]+$ ]] || [[ "$TEST_COUNT" -lt 0 ]]; then
        log "${RED}[ERROR]${NC} TEST_COUNT must be a non-negative integer (got: $TEST_COUNT)"
        exit 1
    fi

    # Apply ordering
    if [[ "$EXECUTION_ORDER" == "random" ]]; then
        mapfile -t tests_to_run < <(shuffle_array "${tests_to_run[@]}")
    fi

    # Apply limit
    if (( TEST_COUNT > 0 && ${#tests_to_run[@]} > TEST_COUNT )); then
        tests_to_run=("${tests_to_run[@]:0:TEST_COUNT}")
    fi

    total_selected=${#tests_to_run[@]}
    log "${BLUE}[INFO]${NC} Tests to execute after limiting: $total_selected"

    # Run tests with suite-level timeout
    suite_start_epoch=$(date +%s)
    test_counter=0
    for use_case_file in "${tests_to_run[@]}"; do
        now=$(date +%s)
        if (( now - suite_start_epoch >= TOTAL_TIMEOUT )); then
            log "${YELLOW}[TIMEOUT]${NC} Suite timeout (${TOTAL_TIMEOUT}s) reached; skipping remaining tests"
            break
        fi
        test_counter=$((test_counter + 1))
        set +e
        run_use_case_test "$use_case_file" "$test_counter" "$total_selected"
        test_rc=$?
        set -e
        if (( test_rc != 0 )); then
            log "${YELLOW}[INFO]${NC} Continuing to next test despite failure (exit code: $test_rc)"
        fi
    done
}

print_summary() {
    local success_rate=0
    if [[ $EXECUTED_TESTS -gt 0 ]]; then
        success_rate=$(( PASSED_TESTS * 100 / EXECUTED_TESTS ))
    fi

    log ""
    log "===================================================================="
    log "${BLUE}[FINAL SUMMARY]${NC} ${BOLD}Use Case Test Results${NC}"
    log "===================================================================="
    log "${BLUE}Test Session:${NC} $(date '+%Y-%m-%d %H:%M:%S')"
    log "${BLUE}Server:${NC} $API_BASE"
    log "${BLUE}Use Cases Directory:${NC} $USE_CASES_DIR"
    log "--------------------------------------------------------------------"
    log "${BLUE}Total Tests:${NC} $TOTAL_TESTS"
    log "${BLUE}Executed:${NC} $EXECUTED_TESTS"
    log "${GREEN}Passed:${NC} $PASSED_TESTS"
    log "${RED}Failed:${NC} $FAILED_TESTS"
    log "${YELLOW}Timed Out:${NC} $TIMED_OUT_TESTS"
    log "${YELLOW}Skipped:${NC} $SKIPPED_TESTS"
    log "${BLUE}Success Rate:${NC} ${success_rate}% (minimum required: ${MIN_SUCCESS_RATE}%)"
    log "${BLUE}Log File:${NC} $LOG_FILE"
    log "${BLUE}Results Directory:${NC} $TEST_RESULTS_DIR"
    log "===================================================================="

    if [[ $EXECUTED_TESTS -eq 0 ]]; then
        log "${YELLOW}[WARNING]${NC} No tests were executed"
        exit 1
    fi

    if [[ $success_rate -ge $MIN_SUCCESS_RATE ]]; then
        log "${GREEN}[SUCCESS]${NC} Overall result: PASS 🎉"
        exit 0
    else
        log "${RED}[FAILURE]${NC} Overall result: FAIL (success rate below ${MIN_SUCCESS_RATE}%)"
        exit 1
    fi
}

usage() {
    cat << EOF
Usage: $0 [OPTIONS]

Run use case based end-to-end tests against Browser4 server.

OPTIONS:
    -u, --url URL           Browser4 base URL (default: http://localhost:8182)
    -t, --test SELECTION    Test selection (comma-separated numbers or "all", default: all)
                            Examples: "01,02,03" or "01-ecommerce" or "all"
    -n, --count N           Number of tests to run after selection (default: all)
    -o, --order MODE        Execution order: random | sequential (default: random)
    -l, --level LEVELS      Comma-separated task levels to run (default: Simple; use "all" for all levels)
    --task-timeout SEC      Per-test timeout in seconds (default: 180)
    --total-timeout SEC     Total suite timeout in seconds (default: 1200)
    -s, --skip-server       Skip server connectivity check
    -v, --verbose           Enable verbose output
    -h, --help              Show this help message

ENVIRONMENT VARIABLES:
    API_BASE                Browser4 base URL
    TASK_TIMEOUT            Per-test timeout in seconds (default: 180)
    TOTAL_TIMEOUT           Total suite timeout in seconds (default: 1200)
    MIN_SUCCESS_RATE        Minimum success rate to pass (default: 50)
    TEST_SELECTION          Same as --test option
    TEST_COUNT              Same as --count option (0 or unset means all)
    EXECUTION_ORDER         Same as --order option (default: random)
    LEVEL_FILTER            Comma-separated task levels to run (default: Simple; use "all" for all levels)
    VERBOSE                 Same as --verbose option

EXAMPLES:
    $0                              # Run 3 random tests from all use cases
    $0 -u http://localhost:8080     # Use custom server URL
    $0 -t "01,02,03"               # Run specific tests
    $0 -t "01-ecommerce"           # Run test by name prefix
    $0 -n 5 -o sequential           # Run first 5 tests in order
    $0 --verbose                    # Enable verbose logging
    $0 --skip-server -v             # Skip server check, verbose mode

USE CASE FILES:
    Use case files are located in: browser4-tests/browser4-tests-common/src/main/resources/e2e/scenarios/happy_path/use-cases/
    Each file should contain:
        - Comment lines (starting with #) for description
        - Task steps for the agent to execute

EOF
}

# =============================================================================
# Argument Parsing
# =============================================================================

parse_args() {
    while [[ $# -gt 0 ]]; do
        case $1 in
            -u|--url)
                API_BASE="$2"
                shift 2
                ;;
            -t|--test)
                TEST_SELECTION="$2"
                shift 2
                ;;
            -n|--count)
                TEST_COUNT="$2"
                shift 2
                ;;
            -o|--order)
                EXECUTION_ORDER="$2"
                shift 2
                ;;
            -l|--level)
                LEVEL_FILTER="$2"
                shift 2
                ;;
            --task-timeout)
                TASK_TIMEOUT="$2"
                shift 2
                ;;
            --total-timeout)
                TOTAL_TIMEOUT="$2"
                shift 2
                ;;
            -s|--skip-server)
                SKIP_SERVER_CHECK="true"
                shift
                ;;
            -v|--verbose)
                VERBOSE="true"
                shift
                ;;
            -h|--help)
                usage
                exit 0
                ;;
            *)
                echo "Unknown option: $1"
                usage
                exit 1
                ;;
        esac
    done
}

# =============================================================================
# Main Entry Point
# =============================================================================

main() {
    ensure_directories

    log "${BLUE}[INFO]${NC} ${BOLD}Browser4 Use Case Test Suite${NC}"
    log "${BLUE}[INFO]${NC} Timestamp: $(date '+%Y-%m-%d %H:%M:%S')"
    log "${BLUE}[INFO]${NC} Server URL: $API_BASE"
    log "${BLUE}[INFO]${NC} Verbose Mode: $VERBOSE"

    # Check for curl
    if ! command -v curl &>/dev/null; then
        log "${RED}[ERROR]${NC} curl command not found. Please install curl."
        exit 1
    fi

    # Check server
    if [[ "$SKIP_SERVER_CHECK" != "true" ]]; then
        if ! check_server; then
            log "${YELLOW}[WARNING]${NC} Use --skip-server to bypass server check"
            exit 1
        fi
    fi

    # Check use cases directory
    if [[ ! -d "$USE_CASES_DIR" ]]; then
        log "${RED}[ERROR]${NC} Use cases directory not found: $USE_CASES_DIR"
        exit 1
    fi

    run_all_tests
    print_summary
}

# Handle interruption
trap 'log "\n${YELLOW}[INFO]${NC} Tests interrupted by user"; exit 130' INT

parse_args "$@"
main
