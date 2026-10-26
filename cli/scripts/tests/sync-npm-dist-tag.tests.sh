#!/usr/bin/env bash
# sync-npm-dist-tag.tests.sh
# Test suite for sync-npm-dist-tag.sh
# Uses only bash builtins and standard Unix tools -- no external test
# frameworks and no network: `npm` is stubbed on PATH.
#
# Run:
#   bash cli/scripts/tests/sync-npm-dist-tag.tests.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_UNDER_TEST="$(cd "$SCRIPT_DIR/.." && pwd)/sync-npm-dist-tag.sh"

PASS=0
FAIL=0

green()  { echo -e "\033[0;32m$*\033[0m"; }
red()    { echo -e "\033[0;31m$*\033[0m"; }
cyan()   { echo -e "\033[0;36m$*\033[0m"; }
dim()    { echo -e "\033[2m$*\033[0m"; }

# test <name> <command...>
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
#
# State model:
#   $FAKE_NPM_STATE_DIR/tag_<tag>   current target of a dist-tag
#   $FAKE_NPM_STATE_DIR/calls.log   every invocation, one line per call
#
# Environment:
#   FAKE_NPM_PUBLISHED              the one version that exists on the registry
#   FAKE_NPM_IGNORE_ADD=1           accept `dist-tag add` but leave state alone
#   FAKE_NPM_ADD_FAILS=1            make `dist-tag add` fail

STUB_DIR="$(mktemp -d)"
WORK_DIR="$(mktemp -d)"
cleanup() { rm -rf "$STUB_DIR" "$WORK_DIR"; }
trap cleanup EXIT

cat > "$STUB_DIR/npm" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

state_dir="${FAKE_NPM_STATE_DIR:?FAKE_NPM_STATE_DIR is required}"
printf '%s\n' "$*" >> "$state_dir/calls.log"

cmd="${1:-}"
case "$cmd" in
    view)
        spec="${2:?view needs a package spec}"
        ref="${spec##*@}"
        # A version read succeeds only for the version the test published.
        if [ -n "${FAKE_NPM_PUBLISHED:-}" ] && [ "$ref" = "$FAKE_NPM_PUBLISHED" ]; then
            printf '%s\n' "$ref"
            exit 0
        fi
        # A tag read succeeds only when the tag exists.
        if [ -f "$state_dir/tag_$ref" ]; then
            cat "$state_dir/tag_$ref"
            exit 0
        fi
        echo "npm error code E404" >&2
        echo "npm error 404 No match found for version $ref" >&2
        exit 1
        ;;
    dist-tag)
        action="${2:?dist-tag needs an action}"
        spec="${3:?dist-tag needs a package spec}"
        tag="${4:?dist-tag needs a tag name}"
        [ "$action" = "add" ] || { echo "unsupported dist-tag action: $action" >&2; exit 1; }
        if [ "${FAKE_NPM_ADD_FAILS:-0}" = "1" ]; then
            echo "npm error code E500" >&2
            echo "npm error registry exploded" >&2
            exit 1
        fi
        if [ "${FAKE_NPM_IGNORE_ADD:-0}" != "1" ]; then
            printf '%s' "${spec##*@}" > "$state_dir/tag_$tag"
        fi
        echo "+$tag: $spec"
        ;;
    *)
        echo "unsupported npm command: $cmd" >&2
        exit 1
        ;;
esac
STUB
chmod +x "$STUB_DIR/npm"

# ---- helpers ----------------------------------------------------------

# make_state [published-version]
make_state() {
    STATE="$(mktemp -d "$WORK_DIR/state.XXXXXX")"
    export FAKE_NPM_STATE_DIR="$STATE"
    export FAKE_NPM_PUBLISHED="${1:-4.15.1}"
    unset FAKE_NPM_IGNORE_ADD FAKE_NPM_ADD_FAILS 2>/dev/null || true
}

seed_tag() {
    printf '%s' "$2" > "$STATE/tag_$1"
}

tag_value() {
    cat "$STATE/tag_$1" 2>/dev/null || true
}

calls() {
    cat "$STATE/calls.log" 2>/dev/null || true
}

run_sync() {
    RUN_OUTPUT="$(PATH="$STUB_DIR:$PATH" bash "$SCRIPT_UNDER_TEST" "$@" 2>&1)" && RUN_STATUS=0 || RUN_STATUS=$?
}

made_change() {
    printf '%s' "$(calls)" | grep -q 'dist-tag add'
}

# ---- pre-flight -------------------------------------------------------

cyan  "============================================"
cyan  " sync-npm-dist-tag.sh Test Suite"
cyan  "============================================"
echo ""

dim "--- Pre-flight ---"

test "file exists" bash -c "[[ -f '$SCRIPT_UNDER_TEST' ]]"
test "starts with bash shebang" bash -c "head -c 20 '$SCRIPT_UNDER_TEST' | grep -q '^#!/bin/bash'"
test "bash syntax check passes" bash -c "bash -n '$SCRIPT_UNDER_TEST' 2>&1"
test "no non-ASCII bytes" bash -c "! grep -Pn '[^\\x00-\\x7F]' '$SCRIPT_UNDER_TEST' >/dev/null 2>&1"

echo ""

# ---- behaviour --------------------------------------------------------

# The bug this script exists for: v4.15.1 shipped while `next` still pointed at
# 4.14.0-rc.9, so the prerelease-to-stable transition must move the tag.
test_moves_a_stale_prerelease_tag() {
    make_state 4.15.1
    seed_tag next 4.14.0-rc.9

    run_sync --package browser4-cli --version 4.15.1 --interval 0
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [ "$(tag_value next)" = "4.15.1" ] || return 1
    printf '%s' "$(calls)" | grep -q '^dist-tag add browser4-cli@4.15.1 next$' || return 1
    [[ "$RUN_OUTPUT" == *"Verified npm dist-tag 'next' -> browser4-cli@4.15.1"* ]] || return 1
}

test_leaves_a_current_tag_alone() {
    make_state 4.15.1
    seed_tag next 4.15.1

    run_sync --package browser4-cli --version 4.15.1 --interval 0
    [ "$RUN_STATUS" -eq 0 ] || return 1
    made_change && return 1
    [[ "$RUN_OUTPUT" == *"already points at browser4-cli@4.15.1"* ]] || return 1
    return 0
}

test_adds_a_missing_tag() {
    make_state 4.15.1

    run_sync --package browser4-cli --version 4.15.1 --interval 0
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [ "$(tag_value next)" = "4.15.1" ] || return 1
    made_change || return 1
}

test_prerelease_is_a_noop() {
    make_state 4.16.0-rc.1

    run_sync --package browser4-cli --version 4.16.0-rc.1 --interval 0
    [ "$RUN_STATUS" -eq 0 ] || return 1
    made_change && return 1
    [[ "$RUN_OUTPUT" == *"nothing to do"* ]] || return 1
    return 0
}

test_refuses_a_version_missing_on_npm() {
    make_state 4.15.0

    run_sync --package browser4-cli --version 4.15.1 --interval 0
    [ "$RUN_STATUS" -eq 1 ] || return 1
    made_change && return 1
    [[ "$RUN_OUTPUT" == *"not visible on npm"* ]] || return 1
    return 0
}

test_does_not_move_back_from_a_newer_rc() {
    make_state 4.15.2
    seed_tag next 4.16.0-rc.1

    run_sync --package browser4-cli --version 4.15.2 --interval 0
    [ "$RUN_STATUS" -eq 0 ] || return 1
    made_change && return 1
    [ "$(tag_value next)" = "4.16.0-rc.1" ] || return 1
    [[ "$RUN_OUTPUT" == *"newer than 4.15.2"* ]] || return 1
}

test_does_not_move_back_from_a_newer_stable() {
    make_state 4.15.2
    seed_tag next 4.16.0

    run_sync --package browser4-cli --version 4.15.2 --interval 0
    [ "$RUN_STATUS" -eq 0 ] || return 1
    made_change && return 1
    [ "$(tag_value next)" = "4.16.0" ] || return 1
    return 0
}

test_moves_its_own_rc_to_the_stable_release() {
    make_state 4.15.1
    seed_tag next 4.15.1-rc.2

    run_sync --package browser4-cli --version 4.15.1 --interval 0
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [ "$(tag_value next)" = "4.15.1" ] || return 1
    made_change || return 1
}

test_fails_when_the_registry_does_not_reflect_the_move() {
    make_state 4.15.1
    seed_tag next 4.14.0-rc.9
    export FAKE_NPM_IGNORE_ADD=1

    run_sync --package browser4-cli --version 4.15.1 --attempts 3 --interval 0
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [[ "$RUN_OUTPUT" == *"still reads '4.14.0-rc.9' after 3 polls"* ]] || return 1
}

test_dry_run_changes_nothing() {
    make_state 4.15.1
    seed_tag next 4.14.0-rc.9

    run_sync --package browser4-cli --version 4.15.1 --dry-run --interval 0
    [ "$RUN_STATUS" -eq 0 ] || return 1
    made_change && return 1
    [ "$(tag_value next)" = "4.14.0-rc.9" ] || return 1
    [[ "$RUN_OUTPUT" == *"[dry-run]"* ]] || return 1
}

test_rejects_missing_arguments() {
    make_state 4.15.1

    run_sync
    [ "$RUN_STATUS" -eq 2 ] || return 1
    [[ "$RUN_OUTPUT" == *"Usage:"* ]] || return 1

    run_sync --package browser4-cli
    [ "$RUN_STATUS" -eq 2 ] || return 1
}

test_rejects_an_invalid_version() {
    make_state 4.15.1

    run_sync --package browser4-cli --version latest
    [ "$RUN_STATUS" -eq 2 ] || return 1
    made_change && return 1
    [[ "$RUN_OUTPUT" == *"invalid version"* ]] || return 1
    return 0
}

test_rejects_an_invalid_dist_tag() {
    make_state 4.15.1

    run_sync --package browser4-cli --version 4.15.1 --tag 'next; rm -rf /'
    [ "$RUN_STATUS" -eq 2 ] || return 1
    made_change && return 1
    [[ "$RUN_OUTPUT" == *"invalid dist-tag"* ]] || return 1
    return 0
}

dim "--- Behaviour ---"

test "moves a stale prerelease tag forward" test_moves_a_stale_prerelease_tag
test "leaves a current tag alone" test_leaves_a_current_tag_alone
test "adds a missing tag" test_adds_a_missing_tag
test "prerelease release is a no-op" test_prerelease_is_a_noop
test "refuses a version missing on npm" test_refuses_a_version_missing_on_npm
test "does not move back from a newer rc" test_does_not_move_back_from_a_newer_rc
test "does not move back from a newer stable" test_does_not_move_back_from_a_newer_stable
test "moves its own rc to the stable release" test_moves_its_own_rc_to_the_stable_release
test "fails when the registry does not reflect the move" test_fails_when_the_registry_does_not_reflect_the_move
test "dry-run changes nothing" test_dry_run_changes_nothing
test "rejects missing arguments" test_rejects_missing_arguments
test "rejects an invalid version" test_rejects_an_invalid_version
test "rejects an invalid dist-tag" test_rejects_an_invalid_dist_tag

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
