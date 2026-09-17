#!/bin/bash
# ---------------------------------------------------------------------------
# reconcile-release-assets.sh -- make a GitHub release carry exactly the
# assets the build produced, retrying GitHub's transient upload failures
# ---------------------------------------------------------------------------
# Usage:
#   reconcile-release-assets.sh --tag <tag> [--repo <owner/name>] \
#       [--timeout <seconds>] [--interval <seconds>] [--settle <seconds>] \
#       <file>...
#   reconcile-release-assets.sh --tag <tag> [options] --files-from <path>
#
# Defaults: timeout 600 s, interval 30 s, settle 5 s.
#
# GitHub's release upload endpoint answers with a transient 5xx a few times a
# month: "Error creating asset temp dir", "Error saving asset", "Unicorn!".
# v4.14.0-rc.6 (release.yml run 35265014949) failed exactly this way -- the
# "Publish GitHub release" job uploaded 6 of 11 assets and then died on
#   ##[error]Error creating asset temp dir
# which skipped the artifact attestation, the release verification and the
# OSS sync: the whole release pipeline was lost to a server-side blip, with a
# half-populated GitHub release left behind.
#
# GitHub does not say which upload failed, and a failed upload can leave a
# partial asset behind, so re-running the whole upload is both slow and not
# obviously correct. This script instead treats the release as the source of
# truth and converges on it:
#
#   1. list the assets the release already carries (name and size),
#   2. upload every local file that is missing or whose size differs, using
#      `gh release upload --clobber` (which also replaces a partial asset),
#   3. re-check, and repeat until the release matches the local files or the
#      time budget runs out.
#
# It is idempotent: a release that already matches its files costs one API
# call and no upload. Assets are matched by file name, which is the name the
# publishing action uses for them. A release that does not exist is reported
# as a failure rather than recreated here -- that is the release-creation
# path, which the publishing action creates (and retries) with the workflow's
# metadata.
#
# Exit codes:
#   0  the release carries every given file with the local byte size
#   1  still missing/mismatched when the budget expired, the release does not
#      exist, a given file does not exist, or bad usage
#
# Designed for CI (GitHub Actions) but also runs on developer machines.
# Requires `gh`, authenticated via GH_TOKEN/GITHUB_TOKEN or `gh auth login`.
# ---------------------------------------------------------------------------
set -euo pipefail

TAG=''
REPO=''
TIMEOUT_SECONDS=600
POLL_INTERVAL_SECONDS=30
SETTLE_SECONDS=5
FILES_FROM=''
FILES=()

usage() {
    local code="${1:-1}"
    {
        echo "Usage: $0 --tag <tag> [--repo <owner/name>] [--timeout <seconds>]"
        echo "          [--interval <seconds>] [--settle <seconds>]"
        echo "          [--files-from <path>] <file>..."
    } >&2
    exit "$code"
}

while [ $# -gt 0 ]; do
    case "${1:-}" in
        --tag)
            TAG="${2:-}"
            shift 2 || usage
            ;;
        --repo)
            REPO="${2:-}"
            shift 2 || usage
            ;;
        --timeout)
            TIMEOUT_SECONDS="${2:-}"
            shift 2 || usage
            ;;
        --interval)
            POLL_INTERVAL_SECONDS="${2:-}"
            shift 2 || usage
            ;;
        --settle)
            SETTLE_SECONDS="${2:-}"
            shift 2 || usage
            ;;
        --files-from)
            FILES_FROM="${2:-}"
            shift 2 || usage
            ;;
        -h|--help)
            usage 0
            ;;
        --)
            shift
            while [ $# -gt 0 ]; do
                FILES+=("$1")
                shift
            done
            ;;
        -*)
            echo "ERROR: unknown option '$1'" >&2
            usage
            ;;
        *)
            FILES+=("$1")
            shift
            ;;
    esac
done

[ -n "$TAG" ] || { echo "ERROR: --tag is required" >&2; usage; }
case "$TIMEOUT_SECONDS" in ''|*[!0-9]*) echo "ERROR: --timeout must be a whole number of seconds" >&2; usage ;; esac
case "$POLL_INTERVAL_SECONDS" in ''|*[!0-9]*) echo "ERROR: --interval must be a whole number of seconds" >&2; usage ;; esac
case "$SETTLE_SECONDS" in ''|*[!0-9]*) echo "ERROR: --settle must be a whole number of seconds" >&2; usage ;; esac
[ "$POLL_INTERVAL_SECONDS" -gt 0 ] || { echo "ERROR: --interval must be at least 1 s" >&2; usage; }

if [ -n "$FILES_FROM" ]; then
    if [ ! -f "$FILES_FROM" ]; then
        echo "ERROR: --files-from '$FILES_FROM' does not exist" >&2
        exit 1
    fi
    # Newline-separated paths: the shape the publishing action's `files` input
    # and this repository's release asset manifest already use.
    while IFS= read -r manifest_line || [ -n "$manifest_line" ]; do
        manifest_line="${manifest_line%$'\r'}"
        [ -n "$manifest_line" ] || continue
        FILES+=("$manifest_line")
    done < "$FILES_FROM"
fi

if [ "${#FILES[@]}" -eq 0 ]; then
    echo "ERROR: no asset files given" >&2
    usage
fi

for f in "${FILES[@]}"; do
    if [ -z "$f" ]; then
        echo "ERROR: empty asset file argument (is the asset list a real path list?)" >&2
        exit 1
    fi
    if [ ! -f "$f" ]; then
        echo "ERROR: asset file '$f' does not exist" >&2
        exit 1
    fi
done

if ! command -v gh >/dev/null 2>&1; then
    echo "ERROR: gh is not installed -- https://cli.github.com" >&2
    exit 1
fi

STDERR_FILE="$(mktemp)"
trap 'rm -f "$STDERR_FILE"' EXIT

# --repo is appended to the subcommand (gh declares it per command), so the two
# call sites below branch instead of building an argument array that `set -u`
# and old bash versions disagree about while it is empty.
list_release_assets() {
    if [ -n "$REPO" ]; then
        gh release view "$TAG" --repo "$REPO" --json assets --jq '.assets[] | "\(.name) \(.size)"'
    else
        gh release view "$TAG" --json assets --jq '.assets[] | "\(.name) \(.size)"'
    fi
}

upload_assets() {
    if [ -n "$REPO" ]; then
        gh release upload "$TAG" "$@" --repo "$REPO" --clobber
    else
        gh release upload "$TAG" "$@" --clobber
    fi
}

local_size_of() {
    wc -c < "$1" | tr -d '[:space:]'
}

# remote_size_of <name> -- size the release reports for <name>; returns 1 when
# the release does not carry that asset. Asset names may contain spaces, so the
# size is read as the last field of "name size".
remote_size_of() {
    local wanted="$1" line name size
    while IFS= read -r line; do
        [ -n "$line" ] || continue
        name="${line% *}"
        size="${line##* }"
        if [ "$name" = "$wanted" ]; then
            printf '%s' "$size"
            return 0
        fi
    done <<< "$remote_listing"
    return 1
}

started=$SECONDS
attempt=0
remote_listing=''
gh_error=''

while :; do
    attempt=$((attempt + 1))
    upload_status=1

    set +e
    remote_listing="$(list_release_assets 2>"$STDERR_FILE")"
    status=$?
    set -e

    gh_error="$(head -n 1 "$STDERR_FILE" 2>/dev/null || true)"
    elapsed=$((SECONDS - started))

    if [ "$status" -eq 0 ]; then
        NEEDED=()
        REASONS=()
        for f in "${FILES[@]}"; do
            name="${f##*/}"
            local_size="$(local_size_of "$f")"
            if remote_size="$(remote_size_of "$name")"; then
                if [ "$remote_size" = "$local_size" ]; then
                    continue
                fi
                reason="size mismatch: the release has $remote_size bytes, the file has $local_size"
            else
                reason="missing from the release"
            fi
            NEEDED+=("$f")
            REASONS+=("$reason")
        done

        if [ "${#NEEDED[@]}" -eq 0 ]; then
            echo "Verified ${#FILES[@]} asset(s) on release $TAG after ${elapsed}s (attempt $attempt)"
            exit 0
        fi

        echo "Uploading ${#NEEDED[@]} of ${#FILES[@]} asset(s) to release $TAG (attempt $attempt, ${elapsed}s elapsed):"
        for i in "${!NEEDED[@]}"; do
            echo "  - ${NEEDED[$i]##*/}: ${REASONS[$i]}"
        done

        set +e
        upload_assets "${NEEDED[@]}" 2>&1 | sed 's/^/    /'
        upload_status="${PIPESTATUS[0]}"
        set -e

        if [ "$upload_status" -ne 0 ]; then
            echo "WARN: gh release upload failed (exit $upload_status) -- GitHub's upload endpoint returns a transient 5xx a few times a month ('Error creating asset temp dir', 'Error saving asset', 'Unicorn!'); retrying"
        fi
    else
        if printf '%s' "$gh_error" | grep -qiE 'not found|HTTP 404'; then
            echo "ERROR: release $TAG does not exist -- the publishing action failed before it could create the release" >&2
            echo "ERROR: last GitHub answer: ${gh_error:-<no output>}" >&2
            exit 1
        fi
        echo "WARN: could not list the assets of release $TAG (attempt $attempt, ${elapsed}s elapsed): ${gh_error:-<no output>}"
    fi

    remaining=$((TIMEOUT_SECONDS - elapsed))
    if [ "$remaining" -le 0 ]; then
        break
    fi

    # A finished upload round can stay invisible to the listing API for a
    # moment; a short settle avoids re-uploading a file the release already
    # has. A failed round waits the full interval before trying again.
    if [ "$status" -eq 0 ] && [ "$upload_status" -eq 0 ]; then
        delay="$SETTLE_SECONDS"
    else
        delay="$POLL_INTERVAL_SECONDS"
    fi
    # never sleep past the deadline: the total wait stays within the budget
    if [ "$delay" -gt "$remaining" ]; then
        sleep "$remaining"
    elif [ "$delay" -gt 0 ]; then
        sleep "$delay"
    fi
done

echo "ERROR: release $TAG still does not match the build output after ${TIMEOUT_SECONDS}s" >&2
for i in "${!NEEDED[@]}"; do
    echo "ERROR:   - ${NEEDED[$i]##*/}: ${REASONS[$i]}" >&2
done
if [ -n "$gh_error" ]; then
    echo "ERROR: last GitHub answer: ${gh_error}" >&2
fi
echo "ERROR: GitHub's release upload endpoint returns transient 5xx responses" >&2
echo "ERROR: ('Error creating asset temp dir', 'Error saving asset', 'Unicorn!')." >&2
echo "ERROR: Re-run the failed jobs of this workflow to retry the upload." >&2
exit 1
