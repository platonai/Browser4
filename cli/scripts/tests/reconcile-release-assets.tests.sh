#!/usr/bin/env bash
# reconcile-release-assets.tests.sh
# Test suite for reconcile-release-assets.sh
# Uses only bash builtins and standard Unix tools -- no external test
# frameworks and no network: `gh` is stubbed on PATH.
#
# Run:
#   bash cli/scripts/tests/reconcile-release-assets.tests.sh

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
SCRIPT_UNDER_TEST="$(cd "$SCRIPT_DIR/.." && pwd)/reconcile-release-assets.sh"

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

# ---- fixture: three release assets ------------------------------------

STUB_DIR="$(mktemp -d)"
cleanup() { rm -rf "$STUB_DIR"; }
trap cleanup EXIT

ASSET_DIR="$STUB_DIR/assets"
mkdir -p "$ASSET_DIR"

# make_asset <name> <bytes> -- a real file of an exact size, so the script's
# size comparison runs against real byte counts.
make_asset() {
    local path="$ASSET_DIR/$1" bytes="$2" i=0
    rm -f "$path"
    : > "$path"
    while [ "$i" -lt "$bytes" ]; do
        printf 'x' >> "$path"
        i=$((i + 1))
    done
}

JAR="Browser4.jar"
CLI_BIN="browser4-cli-linux-x64"
BUNDLE="browser4-bundle-runtime-linux-x64.tar.gz"
JAR_BYTES=101
CLI_BIN_BYTES=202
BUNDLE_BYTES=303
make_asset "$JAR" "$JAR_BYTES"
make_asset "$CLI_BIN" "$CLI_BIN_BYTES"
make_asset "$BUNDLE" "$BUNDLE_BYTES"

ASSET_PATHS=("$ASSET_DIR/$JAR" "$ASSET_DIR/$CLI_BIN" "$ASSET_DIR/$BUNDLE")

# ---- stub gh ----------------------------------------------------------

cat > "$STUB_DIR/gh" <<'STUB'
#!/usr/bin/env bash
# Fake `gh release view` / `gh release upload`, driven by the test environment:
#
#   FAKE_GH_STATE   directory holding one file per asset the release "has",
#                   whose content is the size GitHub reports for it
#   FAKE_GH_COUNTS  directory holding the per-command invocation counters
#   FAKE_GH_LOG     append-only command log the assertions read
#   FAKE_GH_MODE    ok | view-fails-once | upload-fails-once |
#                   upload-never-succeeds | release-missing
#
# upload-fails-once and upload-never-succeeds answer with the message the
# v4.14.0-rc.6 release run died on.
set -euo pipefail

state="${FAKE_GH_STATE:?FAKE_GH_STATE is required}"
counts="${FAKE_GH_COUNTS:?FAKE_GH_COUNTS is required}"
log="${FAKE_GH_LOG:?FAKE_GH_LOG is required}"
mode="${FAKE_GH_MODE:?FAKE_GH_MODE is required}"

bump() {
    local file="$counts/$1" count=0
    if [ -f "$file" ]; then count="$(cat "$file")"; fi
    count=$((count + 1))
    printf '%s' "$count" > "$file"
    printf '%s' "$count"
}

command_name="${1:-} ${2:-}"

case "$command_name" in
    "release view")
        printf 'view\n' >> "$log"
        n="$(bump view)"

        if [ "$mode" = "release-missing" ]; then
            echo "release not found" >&2
            exit 1
        fi
        if [ "$mode" = "view-fails-once" ] && [ "$n" -le 1 ]; then
            echo "HTTP 502: Bad Gateway (https://api.github.com/repos/o/r/releases/tags/v1.2.3)" >&2
            exit 1
        fi

        for asset in "$state"/*; do
            [ -e "$asset" ] || continue
            printf '%s %s\n' "$(basename "$asset")" "$(cat "$asset")"
        done
        ;;

    "release upload")
        # Skip the subcommand and the tag; keep the arguments that are real
        # files -- --repo and its value are not.
        shift 2
        shift
        files=()
        for arg in "$@"; do
            [ -f "$arg" ] || continue
            files+=("$arg")
        done

        names=()
        for f in "${files[@]}"; do names+=("${f##*/}"); done
        printf 'upload %s\n' "${names[*]}" >> "$log"

        n="$(bump upload)"
        fail_round=0
        case "$mode" in
            upload-fails-once)
                if [ "$n" -le 1 ]; then fail_round=1; fi
                ;;
            upload-never-succeeds)
                fail_round=1
                ;;
        esac
        if [ "$fail_round" -eq 1 ]; then
            echo "Error creating asset temp dir" >&2
            exit 1
        fi

        for f in "${files[@]}"; do
            printf '%s' "$(wc -c < "$f" | tr -d '[:space:]')" > "$state/${f##*/}"
            echo "Uploaded ${f##*/}"
        done
        ;;

    *)
        echo "fake gh: unhandled command '${command_name}'" >&2
        exit 2
        ;;
esac
STUB
chmod +x "$STUB_DIR/gh"

# ---- runner -----------------------------------------------------------

RUN_STATUS=0
RUN_OUTPUT=''
RUN_STATE=''
RUN_COUNTS=''
RUN_LOG=''

# new_run -- fresh release state, command log and counters for one scenario
new_run() {
    RUN_STATE="$STUB_DIR/state.$$.$RANDOM"
    RUN_COUNTS="$STUB_DIR/counts.$$.$RANDOM"
    RUN_LOG="$STUB_DIR/log.$$.$RANDOM"
    rm -rf "$RUN_STATE" "$RUN_COUNTS" "$RUN_LOG"
    mkdir -p "$RUN_STATE" "$RUN_COUNTS"
    : > "$RUN_LOG"
}

# seed_asset <name> [<bytes>] -- an asset the release already carries, at the
# size GitHub reports for it (defaults to the local size, i.e. a good upload)
seed_asset() {
    local name="$1" bytes="${2:-}"
    if [ -z "$bytes" ]; then
        bytes="$(wc -c < "$ASSET_DIR/$name" | tr -d '[:space:]')"
    fi
    printf '%s' "$bytes" > "$RUN_STATE/$name"
}

# run_script <mode> <timeout> <interval> <settle> [file...]
run_script() {
    local mode="$1" timeout="$2" interval="$3" settle="$4"
    shift 4

    RUN_STATUS=0
    RUN_OUTPUT="$(
        FAKE_GH_MODE="$mode" \
        FAKE_GH_STATE="$RUN_STATE" \
        FAKE_GH_COUNTS="$RUN_COUNTS" \
        FAKE_GH_LOG="$RUN_LOG" \
        PATH="$STUB_DIR:$PATH" \
        bash "$SCRIPT_UNDER_TEST" \
            --tag v1.2.3 \
            --repo owner/repo \
            --timeout "$timeout" \
            --interval "$interval" \
            --settle "$settle" \
            "$@" 2>&1
    )" || RUN_STATUS=$?
}

count_of() {
    local file="$RUN_COUNTS/$1"
    if [ -f "$file" ]; then cat "$file"; else printf '0'; fi
}

upload_calls() { count_of upload; }

# uploaded_names -- every asset name the script asked gh to upload
uploaded_names() {
    sed -n 's/^upload //p' "$RUN_LOG" | tr ' ' '\n' | sed '/^$/d' | LC_ALL=C sort
}

output_has() {
    case "$RUN_OUTPUT" in
        *"$1"*) return 0 ;;
        *) return 1 ;;
    esac
}

# ---- pre-flight -------------------------------------------------------

cyan  "============================================"
cyan  " reconcile-release-assets.sh Test Suite"
cyan  "============================================"
echo ""

dim "--- Pre-flight ---"

test "file exists" bash -c "[ -f '$SCRIPT_UNDER_TEST' ]"
test "starts with shebang" bash -c "head -c 20 '$SCRIPT_UNDER_TEST' | grep -q '^#!/bin/bash'"
test "bash syntax check passes" bash -c "bash -n '$SCRIPT_UNDER_TEST' 2>&1"
# Byte-oriented on purpose: `grep -P` is unavailable in some environments and
# decodes the file through the host locale.
test "no non-ASCII bytes" bash -c "! LC_ALL=C grep -q '[^[:print:][:space:]]' '$SCRIPT_UNDER_TEST'"

echo ""

# ---- behaviour --------------------------------------------------------

# The common case: the publishing action already uploaded everything. The
# script must cost one listing call, no upload, and say so.
test_verifies_a_complete_release_without_uploading() {
    new_run
    seed_asset "$JAR"
    seed_asset "$CLI_BIN"
    seed_asset "$BUNDLE"
    run_script ok 20 1 0 "${ASSET_PATHS[@]}"
    [ "$RUN_STATUS" -eq 0 ] || return 1
    output_has "Verified 3 asset(s) on release v1.2.3" || return 1
    [ "$(upload_calls)" -eq 0 ] || return 1
    [ "$(count_of view)" -eq 1 ] || return 1
}

# The v4.14.0-rc.6 failure: some assets never made it. Only those are sent
# again -- re-uploading the whole set is what makes the job slow.
test_uploads_only_the_missing_assets() {
    new_run
    seed_asset "$JAR"
    run_script ok 20 1 0 "${ASSET_PATHS[@]}"
    [ "$RUN_STATUS" -eq 0 ] || return 1
    [ "$(uploaded_names)" = "$(printf '%s\n%s\n' "$BUNDLE" "$CLI_BIN" | LC_ALL=C sort)" ] || return 1
    [ "$(count_of view)" -ge 2 ] || return 1
}

# A failed upload can leave a partial asset behind: size, not presence, decides.
test_repairs_a_partial_asset_by_size() {
    new_run
    seed_asset "$JAR"
    seed_asset "$CLI_BIN"
    seed_asset "$BUNDLE" 7
    run_script ok 20 1 0 "${ASSET_PATHS[@]}"
    [ "$RUN_STATUS" -eq 0 ] || return 1
    output_has "size mismatch: the release has 7 bytes, the file has 303" || return 1
    [ "$(uploaded_names)" = "$BUNDLE" ] || return 1
    [ "$(cat "$RUN_STATE/$BUNDLE")" = "$BUNDLE_BYTES" ] || return 1
}

# The CI failure itself: GitHub answers "Error creating asset temp dir". The
# round must be retried instead of failing the release.
test_survives_the_transient_upload_error() {
    new_run
    run_script upload-fails-once 20 1 0 "${ASSET_PATHS[@]}"
    [ "$RUN_STATUS" -eq 0 ] || return 1
    output_has "Error creating asset temp dir" || return 1
    output_has "WARN: gh release upload failed" || return 1
    output_has "Verified 3 asset(s) on release v1.2.3" || return 1
    [ "$(upload_calls)" -ge 2 ] || return 1
    [ "$(cat "$RUN_STATE/$BUNDLE")" = "$BUNDLE_BYTES" ] || return 1
}

# The listing API is just as capable of a 5xx as the upload endpoint.
test_survives_a_transient_listing_error() {
    new_run
    seed_asset "$JAR"
    seed_asset "$CLI_BIN"
    seed_asset "$BUNDLE"
    run_script view-fails-once 20 1 0 "${ASSET_PATHS[@]}"
    [ "$RUN_STATUS" -eq 0 ] || return 1
    output_has "WARN: could not list the assets of release v1.2.3" || return 1
    output_has "Verified 3 asset(s) on release v1.2.3" || return 1
}

# A missing release is the creation path, not the upload path: say so instead
# of recreating it here with metadata the workflow did not pass.
test_fails_when_the_release_does_not_exist() {
    new_run
    run_script release-missing 20 1 0 "${ASSET_PATHS[@]}"
    [ "$RUN_STATUS" -eq 1 ] || return 1
    output_has "release v1.2.3 does not exist" || return 1
    output_has "release not found" || return 1
}

# Uploads that never take effect must fail the job, with the offenders named.
test_fails_when_uploads_never_take_effect() {
    new_run
    run_script upload-never-succeeds 2 1 0 "${ASSET_PATHS[@]}"
    [ "$RUN_STATUS" -eq 1 ] || return 1
    output_has "still does not match the build output after 2s" || return 1
    output_has "  - $BUNDLE: missing from the release" || return 1
    output_has "Re-run the failed jobs of this workflow" || return 1
}

# ... and it must give up on time rather than sleep past its budget.
test_respects_the_time_budget() {
    local started elapsed
    new_run
    started=$SECONDS
    run_script upload-never-succeeds 2 1 0 "${ASSET_PATHS[@]}"
    elapsed=$((SECONDS - started))
    [ "$RUN_STATUS" -eq 1 ] || return 1
    [ "$elapsed" -le 10 ] || return 1
}

# The release workflow hands over the manifest it already builds: one asset
# path per line, with blank lines and CRLF tolerated.
test_reads_a_newline_separated_manifest() {
    local manifest="$STUB_DIR/manifest.txt"
    {
        printf '%s\r\n' "${ASSET_PATHS[0]}"
        printf '\n'
        printf '%s\n%s\n' "${ASSET_PATHS[1]}" "${ASSET_PATHS[2]}"
    } > "$manifest"

    new_run
    seed_asset "$JAR"
    seed_asset "$CLI_BIN"
    seed_asset "$BUNDLE"
    run_script ok 20 1 0 --files-from "$manifest"
    [ "$RUN_STATUS" -eq 0 ] || return 1
    output_has "Verified 3 asset(s) on release v1.2.3" || return 1
    [ "$(upload_calls)" -eq 0 ] || return 1
}

test_rejects_a_missing_asset_file() {
    new_run
    run_script ok 20 1 0 "$ASSET_DIR/not-built.tar.gz"
    [ "$RUN_STATUS" -eq 1 ] || return 1
    output_has "does not exist" || return 1
    [ "$(count_of view)" -eq 0 ] || return 1
}

test_requires_a_tag() {
    local output status
    output="$(PATH="$STUB_DIR:$PATH" bash "$SCRIPT_UNDER_TEST" "${ASSET_PATHS[@]}" 2>&1)" && status=0 || status=$?
    [ "$status" -ne 0 ] || return 1
    [[ "$output" == *"--tag is required"* ]] || return 1
    [[ "$output" == *"Usage:"* ]] || return 1
}

test_requires_asset_files() {
    local output status
    output="$(PATH="$STUB_DIR:$PATH" bash "$SCRIPT_UNDER_TEST" --tag v1.2.3 2>&1)" && status=0 || status=$?
    [ "$status" -ne 0 ] || return 1
    [[ "$output" == *"no asset files given"* ]] || return 1
    [[ "$output" == *"Usage:"* ]] || return 1
}

dim "--- Behaviour ---"

test "complete release: verified, nothing uploaded" test_verifies_a_complete_release_without_uploading
test "uploads only the missing assets" test_uploads_only_the_missing_assets
test "repairs a partial asset by size" test_repairs_a_partial_asset_by_size
test "survives the transient upload error that failed v4.14.0-rc.6" test_survives_the_transient_upload_error
test "survives a transient listing error" test_survives_a_transient_listing_error
test "fails when the release does not exist" test_fails_when_the_release_does_not_exist
test "fails when uploads never take effect" test_fails_when_uploads_never_take_effect
test "respects the time budget" test_respects_the_time_budget
test "reads a newline-separated manifest" test_reads_a_newline_separated_manifest
test "rejects a missing asset file" test_rejects_a_missing_asset_file
test "requires a tag" test_requires_a_tag
test "requires asset files" test_requires_asset_files

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
