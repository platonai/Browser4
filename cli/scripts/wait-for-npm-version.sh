#!/bin/bash
# ---------------------------------------------------------------------------
# wait-for-npm-version.sh -- wait until a published version is visible on npm
# ---------------------------------------------------------------------------
# Usage:
#   wait-for-npm-version.sh <package> <version> [timeout-seconds] [poll-interval-seconds]
#
# Defaults: timeout 600 s, poll interval 15 s.
#
# `npm publish` exiting 0 only means the registry ACCEPTED the upload. npm now
# processes publishes asynchronously and answers with
#   "Your package is being processed and may take a few minutes to become available."
# after which the new version stays invisible to `npm view` for an unpredictable
# while (observed on v4.13.18: still missing 41 s after a successful publish,
# i.e. past the whole 5 x 10 s window this script replaces). Verification must
# therefore wait minutes, not seconds -- otherwise a successful publish is
# reported as a failed release job, and everything gated on that job (the
# GitHub release) is skipped.
#
# Every answer that is not the expected version is retried:
#   - 404 / "No match found for version" -> not published yet (expected, quiet)
#   - any other error                   -> transient registry/network problem
#                                          (logged, still retried)
#
# Exit codes:
#   0  the exact version is visible on the registry
#   1  still not visible when the timeout expires, or bad usage
#
# Designed for CI (GitHub Actions) but also runs on developer machines.
# ---------------------------------------------------------------------------
set -euo pipefail

PACKAGE="${1:?Usage: $0 <package> <version> [timeout-seconds] [poll-interval-seconds]}"
VERSION="${2:?Usage: $0 <package> <version> [timeout-seconds] [poll-interval-seconds]}"
TIMEOUT_SECONDS="${3:-600}"
POLL_INTERVAL_SECONDS="${4:-15}"

STDERR_FILE="$(mktemp)"
trap 'rm -f "$STDERR_FILE"' EXIT

started=$SECONDS
attempt=0
published=''
npm_error=''

while :; do
    attempt=$((attempt + 1))

    set +e
    published="$(npm view "$PACKAGE@$VERSION" version 2>"$STDERR_FILE")"
    status=$?
    set -e

    published="$(printf '%s' "$published" | tr -d '[:space:]')"
    npm_error="$(head -n 1 "$STDERR_FILE" 2>/dev/null || true)"
    elapsed=$((SECONDS - started))

    if [ "$status" -eq 0 ] && [ "$published" = "$VERSION" ]; then
        echo "Verified $PACKAGE@$VERSION on npm after ${elapsed}s (attempt $attempt)"
        exit 0
    fi

    remaining=$((TIMEOUT_SECONDS - elapsed))
    if [ "$remaining" -le 0 ]; then
        break
    fi

    if [ "$status" -eq 0 ]; then
        echo "WARN: npm reports '${published:-<empty>}' for $PACKAGE@$VERSION (attempt $attempt, ${elapsed}s elapsed, ${remaining}s left)"
    elif printf '%s' "$npm_error" | grep -qE 'E404|404'; then
        echo "Waiting for $PACKAGE@$VERSION to appear on npm (attempt $attempt, ${elapsed}s elapsed, ${remaining}s left) -- the registry may still be processing the publish"
    else
        echo "WARN: npm view failed (attempt $attempt, ${elapsed}s elapsed, ${remaining}s left): ${npm_error:-<no output>}"
    fi

    # never sleep past the deadline: the total wait stays within the budget
    if [ "$POLL_INTERVAL_SECONDS" -gt "$remaining" ]; then
        sleep "$remaining"
    else
        sleep "$POLL_INTERVAL_SECONDS"
    fi
done

echo "ERROR: unable to verify $PACKAGE@$VERSION on npm within ${TIMEOUT_SECONDS}s after publish" >&2
echo "ERROR: last registry answer: ${npm_error:-<no output>}" >&2
echo "ERROR: the version may still be processing on the registry side; re-check with:" >&2
echo "ERROR:   npm view $PACKAGE@$VERSION version" >&2
echo "ERROR:   https://www.npmjs.com/package/$PACKAGE/v/$VERSION" >&2
exit 1
