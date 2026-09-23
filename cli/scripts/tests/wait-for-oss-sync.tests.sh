#!/usr/bin/env bash
# wait-for-oss-sync.tests.sh
# Test suite for wait-for-oss-sync.sh
# Uses only bash builtins and standard Unix tools -- no external test
# frameworks and no network: `gh` is stubbed on PATH.
#
# Run:
#   bash cli/scripts/tests/wait-for-oss-sync.tests.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_UNDER_TEST="$(cd "$SCRIPT_DIR/.." && pwd)/wait-for-oss-sync.sh"

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

# ---- stub gh ----------------------------------------------------------
#
# The stub answers from files under $FAKE_GH_DIR, so every test reads as a
# description of what GitHub was reporting:
#
#   baseline.tsv        `gh run list --limit 50`  (the pre-dispatch listing)
#   baseline-fail       if present, that listing fails instead
#   discover-<n>.tsv    n-th `gh run list --limit 20` (falls back to
#   discover-last.tsv   ...discover-last.tsv, and to empty output)
#   view-<n>.tsv        n-th `gh run view`           (falls back to
#   view-last.tsv       ...view-last.tsv, and to empty output)
#   dispatch-exit       exit status for `gh workflow run`
#
# Each file holds TSV exactly as the script's own -q filters produce it:
#   run list : <databaseId>\t<createdAt>
#   run view : <status>\t<conclusion>\t<in-progress step>
# The script's own -q filters turn "no value" into "--" rather than leaving the
# field empty (tab is IFS whitespace, so `read` would collapse the run of tabs
# and an empty conclusion would swallow the step name).
# An empty response stands for "gh could not answer", which is what the
# script's `2>/dev/null` turns a failed call into.

STUB_DIR="$(mktemp -d)"
cleanup() { rm -rf "$STUB_DIR"; }
trap cleanup EXIT

cat > "$STUB_DIR/gh" <<'STUB'
#!/usr/bin/env bash
set -euo pipefail

dir="${FAKE_GH_DIR:?FAKE_GH_DIR is required}"
echo "$*" >> "$dir/calls.log"

counter_next() {
    local file="$dir/$1.count" n=0
    if [ -f "$file" ]; then n="$(cat "$file")"; fi
    n=$((n + 1))
    printf '%s' "$n" > "$file"
    printf '%s' "$n"
}

emit() {
    # $1 = base name, $2 = sequence number: numbered response, else the fallback
    local numbered="$dir/$1-$2.tsv" fallback="$dir/$1-last.tsv"
    if [ -f "$numbered" ]; then
        cat "$numbered"
    elif [ -f "$fallback" ]; then
        cat "$fallback"
    fi
    return 0
}

limit=''
prev=''
for arg in "$@"; do
    if [ "$prev" = '--limit' ]; then limit="$arg"; fi
    prev="$arg"
done

case "${1:-} ${2:-}" in
    'run list')
        if [ "$limit" = '50' ]; then
            if [ -f "$dir/baseline-fail" ]; then exit 1; fi
            if [ -f "$dir/baseline.tsv" ]; then cat "$dir/baseline.tsv"; fi
        else
            emit discover "$(counter_next list)"
        fi
        ;;
    'run view')
        emit view "$(counter_next view)"
        ;;
    'workflow run')
        if [ -f "$dir/dispatch-exit" ]; then exit "$(cat "$dir/dispatch-exit")"; fi
        ;;
    *)
        echo "fake gh: unhandled invocation: $*" >&2
        exit 2
        ;;
esac
STUB
chmod +x "$STUB_DIR/gh"

# ---- harness ----------------------------------------------------------

RUN_STATUS=0
RUN_OUTPUT=''

# reset_gh -- empty the stub's response directory (and the call log)
reset_gh() {
    rm -rf "$STUB_DIR/gh-state"
    mkdir -p "$STUB_DIR/gh-state"
    export FAKE_GH_DIR="$STUB_DIR/gh-state"
}

# gh_respond <file> [<content>] -- write one stub response file
gh_respond() {
    local file="$1"
    shift
    if [ $# -gt 0 ]; then
        printf '%s\n' "$*" > "$FAKE_GH_DIR/$file"
    else
        : > "$FAKE_GH_DIR/$file"
    fi
}

# run_wait [<extra args>...] -- run the script on a 4 s / 1 s budget, so a
# "never finishes" case costs ~4 s instead of the 2700 s default.
run_wait() {
    RUN_STATUS=0
    RUN_OUTPUT="$(
        FAKE_GH_DIR="$FAKE_GH_DIR" \
        PATH="$STUB_DIR:$PATH" \
        bash "$SCRIPT_UNDER_TEST" \
            --tag v9.9.9 --repo acme/browser4 \
            --timeout 4 --interval 1 --discovery-timeout 2 "$@" 2>&1
    )" || RUN_STATUS=$?
}

# ---- pre-flight -------------------------------------------------------

cyan  "============================================"
cyan  " wait-for-oss-sync.sh Test Suite"
cyan  "============================================"
echo ""

dim "--- Pre-flight ---"

test "file exists" bash -c "[[ -f '$SCRIPT_UNDER_TEST' ]]"
test "starts with shebang" bash -c "head -c 20 '$SCRIPT_UNDER_TEST' | grep -q '^#!/bin/bash'"
test "bash syntax check passes" bash -c "bash -n '$SCRIPT_UNDER_TEST' 2>&1"
test "no non-ASCII bytes" bash -c "! grep -Pn '[^\\x00-\\x7F]' '$SCRIPT_UNDER_TEST' >/dev/null 2>&1"
test "--help exits 0 and prints usage" bash -c "bash '$SCRIPT_UNDER_TEST' --help 2>&1 | grep -q 'Usage:'"

echo ""

# ---- behaviour --------------------------------------------------------

# The happy path, and the shape of the success message the workflows rely on.
test_run_that_completes_successfully() {
    reset_gh
    gh_respond baseline.tsv "$(printf '111\t2026-09-20T15:20:51Z')"
    gh_respond discover-last.tsv "$(printf '222\t2026-09-23T13:05:09Z')"
    gh_respond view-1.tsv "$(printf 'in_progress\t--\tUpload to OSS')"
    gh_respond view-last.tsv "$(printf 'completed\tsuccess\t--')"

    run_wait
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [[ "$RUN_OUTPUT" == *"Watching sync-to-oss.yml run 222"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"completed successfully"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"actions/runs/222"* ]] || return 1
    grep -q 'workflow run sync-to-oss.yml' "$FAKE_GH_DIR/calls.log" || return 1
}

# The v4.13.17/v4.13.21 defect: the freshly dispatched run is not indexed yet,
# so `gh run list` answers with the PREVIOUS release's run -- which completed
# successfully.  Adopting it (the old inline loop did: first non-empty answer
# won) reports the CDN as updated without waiting for anything.  Ours must win
# even though the stale run is the more attractive answer.
test_never_adopts_the_previous_runs_success() {
    reset_gh
    gh_respond baseline.tsv "$(printf '111\t2026-09-20T15:20:51Z')"
    # both runs visible: 111 (before we dispatched, already green) and 222 (ours)
    gh_respond discover-last.tsv "$(printf '222\t2026-09-23T13:05:09Z\n111\t2026-09-20T15:20:51Z')"
    gh_respond view-last.tsv "$(printf 'completed\tfailure\t--')"

    run_wait
    [ "$RUN_STATUS" -eq 1 ] || return 1
    # ours is named as the verdict ...
    [[ "$RUN_OUTPUT" == *"run 222 concluded 'failure'"* ]] || return 1
    # ... and the stale run's success was never claimed
    [[ "$RUN_OUTPUT" != *"completed successfully"* ]] || return 1
}

# The same guard while our run is still queued behind the stale one.
test_waits_for_our_run_when_only_stale_runs_are_listed() {
    reset_gh
    gh_respond baseline.tsv "$(printf '111\t2026-09-20T15:20:51Z')"
    gh_respond discover-1.tsv "$(printf '111\t2026-09-20T15:20:51Z')"
    gh_respond discover-2.tsv "$(printf '111\t2026-09-20T15:20:51Z')"
    gh_respond discover-last.tsv "$(printf '222\t2026-09-23T13:05:09Z\n111\t2026-09-20T15:20:51Z')"
    gh_respond view-last.tsv "$(printf 'completed\tsuccess\t--')"

    # a generous discovery budget: the point is that it keeps looking, not
    # that it finds the run on any particular poll
    run_wait --discovery-timeout 20
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [[ "$RUN_OUTPUT" == *"Watching sync-to-oss.yml run 222"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"Waiting for the sync-to-oss.yml run to appear"* ]] || return 1
}

# A dispatch that never becomes a run must be reported, not waited out.
test_fails_when_no_new_run_appears() {
    reset_gh
    gh_respond baseline.tsv "$(printf '111\t2026-09-20T15:20:51Z')"
    gh_respond discover-last.tsv "$(printf '111\t2026-09-20T15:20:51Z')"

    run_wait
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [[ "$RUN_OUTPUT" == *"no new run appeared within 2s"* ]] || return 1
}

# The v4.13.21 failure itself: the sync stalls, the budget expires.  The
# message has to name the run, the step it is stuck on and how to re-check it
# -- the old step timeout reported none of that.
test_reports_the_stuck_run_when_the_budget_expires() {
    reset_gh
    gh_respond baseline.tsv "$(printf '111\t2026-09-20T15:20:51Z')"
    gh_respond discover-last.tsv "$(printf '222\t2026-09-23T13:05:09Z')"
    gh_respond view-last.tsv "$(printf 'in_progress\t--\tUpload to OSS')"

    run_wait
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [[ "$RUN_OUTPUT" == *"was still in_progress (step: Upload to OSS) after 4s"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"actions/runs/222"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"gh run view 222 --repo acme/browser4"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"gh workflow run sync-to-oss.yml --repo acme/browser4 -f tag_name=v9.9.9"* ]] || return 1
    # the progress line names the stuck step too, so the log shows where it sat
    [[ "$RUN_OUTPUT" == *"status=in_progress, step=Upload to OSS"* ]] || return 1
}

test_reports_a_failed_run() {
    reset_gh
    gh_respond baseline.tsv ''
    gh_respond discover-last.tsv "$(printf '222\t2026-09-23T13:05:09Z')"
    gh_respond view-last.tsv "$(printf 'completed\tfailure\t--')"

    run_wait
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [[ "$RUN_OUTPUT" == *"concluded 'failure'"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"actions/runs/222"* ]] || return 1
}

test_reports_a_cancelled_run_as_a_failure() {
    reset_gh
    gh_respond baseline.tsv ''
    gh_respond discover-last.tsv "$(printf '222\t2026-09-23T13:05:09Z')"
    gh_respond view-last.tsv "$(printf 'completed\tcancelled\t--')"

    run_wait
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [[ "$RUN_OUTPUT" == *"concluded 'cancelled'"* ]] || return 1
}

# An unreadable poll (rate limit, network blip) must not abort the wait: the
# stub answers nothing for the first view call, then reports our run done.
test_survives_an_unreadable_poll() {
    reset_gh
    gh_respond baseline.tsv ''
    gh_respond discover-last.tsv "$(printf '222\t2026-09-23T13:05:09Z')"
    gh_respond view-1.tsv ''
    gh_respond view-last.tsv "$(printf 'completed\tsuccess\t--')"

    run_wait
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [[ "$RUN_OUTPUT" == *"completed successfully"* ]] || return 1
}

test_fails_when_the_dispatch_itself_fails() {
    reset_gh
    gh_respond baseline.tsv ''
    printf '1' > "$FAKE_GH_DIR/dispatch-exit"

    run_wait
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [[ "$RUN_OUTPUT" == *"Could not dispatch sync-to-oss.yml"* ]] || return 1
}

# With the baseline listing unavailable the run is identified by creation time
# alone -- a run created after the dispatch is still accepted ...
test_falls_back_to_creation_time_when_the_baseline_listing_fails() {
    reset_gh
    : > "$FAKE_GH_DIR/baseline-fail"
    gh_respond discover-last.tsv "$(printf '222\t2099-01-01T00:00:00Z')"
    gh_respond view-last.tsv "$(printf 'completed\tsuccess\t--')"

    run_wait
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [[ "$RUN_OUTPUT" == *"could not list existing sync-to-oss.yml runs"* ]] || return 1
    [[ "$RUN_OUTPUT" == *"Watching sync-to-oss.yml run 222"* ]] || return 1
}

# ... and a run from before the dispatch is not, even with no id baseline.
test_creation_time_fallback_still_refuses_a_stale_run() {
    reset_gh
    : > "$FAKE_GH_DIR/baseline-fail"
    gh_respond discover-last.tsv "$(printf '111\t2020-01-01T00:00:00Z')"
    gh_respond view-last.tsv "$(printf 'completed\tsuccess\t--')"

    run_wait
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [[ "$RUN_OUTPUT" == *"no new run appeared"* ]] || return 1
}

test_requires_the_tag_argument() {
    local output status
    output="$(PATH="$STUB_DIR:$PATH" bash "$SCRIPT_UNDER_TEST" --repo acme/browser4 2>&1)" && status=0 || status=$?
    [ "$status" -ne 0 ] || return 1
    [[ "$output" == *"Usage:"* ]] || return 1
    [[ "$output" == *"--tag is required"* ]] || return 1
}

test_rejects_an_unknown_argument() {
    local output status
    output="$(PATH="$STUB_DIR:$PATH" bash "$SCRIPT_UNDER_TEST" --tag v1 --nonsense 2>&1)" && status=0 || status=$?
    [ "$status" -ne 0 ] || return 1
    [[ "$output" == *"unknown argument '--nonsense'"* ]] || return 1
}

test_rejects_a_non_numeric_timeout() {
    local output status
    output="$(PATH="$STUB_DIR:$PATH" bash "$SCRIPT_UNDER_TEST" --tag v1 --timeout soon 2>&1)" && status=0 || status=$?
    [ "$status" -ne 0 ] || return 1
    [[ "$output" == *"--timeout must be a whole number"* ]] || return 1
}

dim "--- Behaviour ---"

test "run completes successfully" test_run_that_completes_successfully
test "never adopts the previous run's success" test_never_adopts_the_previous_runs_success
test "waits for our run when only stale runs are listed" test_waits_for_our_run_when_only_stale_runs_are_listed
test "fails when no new run appears" test_fails_when_no_new_run_appears
test "reports the stuck run when the budget expires" test_reports_the_stuck_run_when_the_budget_expires
test "reports a failed run" test_reports_a_failed_run
test "reports a cancelled run as a failure" test_reports_a_cancelled_run_as_a_failure
test "survives an unreadable poll" test_survives_an_unreadable_poll
test "fails when the dispatch itself fails" test_fails_when_the_dispatch_itself_fails
test "falls back to creation time when the baseline listing fails" test_falls_back_to_creation_time_when_the_baseline_listing_fails
test "creation-time fallback still refuses a stale run" test_creation_time_fallback_still_refuses_a_stale_run
test "requires the tag argument" test_requires_the_tag_argument
test "rejects an unknown argument" test_rejects_an_unknown_argument
test "rejects a non-numeric timeout" test_rejects_a_non_numeric_timeout

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
