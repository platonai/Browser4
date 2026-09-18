#!/usr/bin/env bash
# wait-for-npm-version.tests.sh
# Test suite for wait-for-npm-version.sh
# Uses only bash builtins and standard Unix tools -- no external test
# frameworks and no network: `npm` is stubbed on PATH.
#
# Run:
#   bash cli/scripts/tests/wait-for-npm-version.tests.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_UNDER_TEST="$(cd "$SCRIPT_DIR/.." && pwd)/wait-for-npm-version.sh"

PASS=0
FAIL=0

green()  { echo -e "\033[0;32m$*\033[0m"; }
red()    { echo -e "\033[0;31m$*\033[0m"; }
cyan()   { echo -e "\033[0;36m$*\033[0m"; }
dim()    { echo -e "\033[2m$*\033[0m"; }

# test <name> <command...>
# The whole command is forwarded: `test "x" bash -c "..."` must not drop the
# `-c "..."` part, or a bare `bash` would exit 0 and the assertion would pass
# vacuously.
test() {
    local name="$1"
    shift
    if "$@"; then
        PASS=$((PASS + 1))
        green "  PASS  $name"
    else
        FAIL=$((FAIL + 1))
        red   "  FAIL  $name"
    fi
}

# ---- stub npm ---------------------------------------------------------

STUB_DIR="$(mktemp -d)"
cleanup() { rm -rf "$STUB_DIR"; }
trap cleanup EXIT

cat > "$STUB_DIR/npm" <<'STUB'
#!/usr/bin/env bash
# Fake `npm view <package>@<version> version`, driven by the test environment:
#   FAKE_NPM_MODE       available | available-after:<n> | unavailable |
#                       network-error-once | wrong-version
#   FAKE_NPM_VERSION    version reported on success
#   FAKE_NPM_COUNT_FILE file counting the invocations
set -euo pipefail

count_file="${FAKE_NPM_COUNT_FILE:?FAKE_NPM_COUNT_FILE is required}"
count=0
if [ -f "$count_file" ]; then count="$(cat "$count_file")"; fi
count=$((count + 1))
printf '%s' "$count" > "$count_file"

not_found() {
    echo "npm error code E404" >&2
    echo "npm error 404 No match found for version ${FAKE_NPM_VERSION:?FAKE_NPM_VERSION is required}" >&2
    exit 1
}

mode="${FAKE_NPM_MODE:?FAKE_NPM_MODE is required}"
case "$mode" in
    available)
        printf '%s\n' "$FAKE_NPM_VERSION"
        ;;
    available-after:*)
        threshold="${mode#available-after:}"
        if [ "$count" -ge "$threshold" ]; then
            printf '%s\n' "$FAKE_NPM_VERSION"
        else
            not_found
        fi
        ;;
    unavailable)
        not_found
        ;;
    wrong-version)
        printf '%s\n' "0.0.1"
        ;;
    network-error-once)
        if [ "$count" -ge 2 ]; then
            printf '%s\n' "$FAKE_NPM_VERSION"
        else
            echo "npm error network request to https://registry.npmjs.org failed" >&2
            exit 1
        fi
        ;;
    *)
        echo "fake npm: unknown FAKE_NPM_MODE '$mode'" >&2
        exit 2
        ;;
esac
STUB
chmod +x "$STUB_DIR/npm"

# run_wait <mode> <timeout-seconds> <poll-interval-seconds> <package> <version>
# Sets RUN_STATUS, RUN_OUTPUT (stdout+stderr) and RUN_COUNT (npm invocations).
RUN_STATUS=0
RUN_OUTPUT=''
RUN_COUNT=0

run_wait() {
    local mode="$1" timeout="$2" interval="$3" package="$4" version="$5"
    local count_file="$STUB_DIR/count.$$.$RANDOM"
    rm -f "$count_file"

    RUN_STATUS=0
    RUN_OUTPUT="$(
        FAKE_NPM_MODE="$mode" \
        FAKE_NPM_VERSION="$version" \
        FAKE_NPM_COUNT_FILE="$count_file" \
        PATH="$STUB_DIR:$PATH" \
        bash "$SCRIPT_UNDER_TEST" "$package" "$version" "$timeout" "$interval" 2>&1
    )" || RUN_STATUS=$?

    RUN_COUNT=0
    if [ -f "$count_file" ]; then RUN_COUNT="$(cat "$count_file")"; fi
}

# ---- pre-flight -------------------------------------------------------

cyan  "============================================"
cyan  " wait-for-npm-version.sh Test Suite"
cyan  "============================================"
echo ""

dim "--- Pre-flight ---"

test "file exists" bash -c "[[ -f '$SCRIPT_UNDER_TEST' ]]"
test "starts with shebang" bash -c "head -c 20 '$SCRIPT_UNDER_TEST' | grep -q '^#!/bin/bash'"
test "bash syntax check passes" bash -c "bash -n '$SCRIPT_UNDER_TEST' 2>&1"
test "no non-ASCII bytes" bash -c "! grep -Pn '[^\\x00-\\x7F]' '$SCRIPT_UNDER_TEST' >/dev/null 2>&1"

echo ""

# ---- behaviour --------------------------------------------------------

test_version_visible_immediately() {
    run_wait available 30 1 browser4-cli 1.2.3
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [[ "$RUN_OUTPUT" == *"Verified browser4-cli@1.2.3 on npm"* ]] || return 1
    [ "$RUN_COUNT" -eq 1 ] || return 1
}

test_waits_until_the_version_appears() {
    run_wait "available-after:3" 30 1 browser4-cli 1.2.3
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [[ "$RUN_OUTPUT" == *"Verified browser4-cli@1.2.3 on npm"* ]] || return 1
    [ "$RUN_COUNT" -ge 3 ] || return 1
}

# Discrimination test for the CI failure this script fixes: the registry
# reports the version only on the 7th call -- beyond the 5 attempts the old
# inline loop allowed. A 3 s budget reproduces the old give-up, a 30 s budget
# rides it out.
test_keeps_polling_past_the_old_five_attempt_window() {
    run_wait "available-after:7" 3 1 browser4-cli 1.2.3
    [ "$RUN_STATUS" -eq 1 ] || return 1

    run_wait "available-after:7" 30 1 browser4-cli 1.2.3
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [ "$RUN_COUNT" -ge 7 ] || return 1
    [[ "$RUN_OUTPUT" == *"Verified browser4-cli@1.2.3 on npm"* ]] || return 1
}

test_fails_when_the_version_never_appears() {
    run_wait unavailable 3 1 browser4-cli 1.2.3
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [[ "$RUN_OUTPUT" == *"unable to verify browser4-cli@1.2.3 on npm within 3s"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"E404"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"npm view browser4-cli@1.2.3 version"* ]] || return 1
}

test_tolerates_a_transient_registry_error() {
    run_wait network-error-once 30 1 browser4-cli 1.2.3
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [[ "$RUN_OUTPUT" == *"WARN: npm view failed"* ]] || return 1
}

test_reports_a_mismatched_version_and_keeps_waiting() {
    run_wait wrong-version 3 1 browser4-cli 1.2.3
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [[ "$RUN_OUTPUT" == *"npm reports '0.0.1'"* ]] || return 1
}

test_requires_both_arguments() {
    local output status
    output="$(PATH="$STUB_DIR:$PATH" bash "$SCRIPT_UNDER_TEST" 2>&1)" && status=0 || status=$?
    [ "$status" -ne 0 ] || return 1
    [[ "$output" == *"Usage:"* ]] || return 1
}

dim "--- Behaviour ---"

test "version already visible: verifies on the first poll" test_version_visible_immediately
test "waits until the registry reports the version" test_waits_until_the_version_appears
test "keeps polling past the old 5-attempt window" test_keeps_polling_past_the_old_five_attempt_window
test "fails when the version never appears" test_fails_when_the_version_never_appears
test "tolerates a transient registry error" test_tolerates_a_transient_registry_error
test "reports a mismatched version and keeps waiting" test_reports_a_mismatched_version_and_keeps_waiting
test "requires both arguments" test_requires_both_arguments

# ---- summary ----------------------------------------------------------

echo ""
cyan "============================================"
if [ "$FAIL" -eq 0 ]; then
    green " All $PASS tests passed"
else
    red   " $FAIL of $((PASS + FAIL)) tests failed"
fi
cyan "============================================"

[ "$FAIL" -eq 0 ]
