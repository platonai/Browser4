#!/bin/bash
# ---------------------------------------------------------------------------
# sync-npm-dist-tag.sh -- keep a release dist-tag from going stale
# ---------------------------------------------------------------------------
# Usage:
#   sync-npm-dist-tag.sh --package <name> --version <version> [options]
#
# Options:
#   --package <name>    npm package name, e.g. browser4-cli          (required)
#   --version <version> the version this release published           (required)
#   --tag <dist-tag>    dist-tag to keep current, default: next
#   --attempts <n>      verification polls, default: 5
#   --interval <secs>   seconds between polls, default: 3
#   --dry-run           print what would change; never touch the registry
#   -h, --help          show this help
#
# Why this exists:
#   Prereleases publish with `--tag next`, so `next` moves on every rc.  Stable
#   releases publish to `latest` and used to leave `next` untouched: v4.15.1
#   shipped with `next` still pointing at 4.14.0-rc.9, so
#
#       npm install -g browser4-cli@next
#
#   installed an rc three minors older than `latest`.  The invariant enforced
#   here is:
#
#       <tag> points at the newest published version, prerelease or stable.
#
#   A prerelease release is a no-op: `npm publish --tag next` already put it
#   there.  A stable release moves the tag to itself -- but only forward.  A
#   maintenance patch (4.15.2) must never drag `next` back off a newer
#   prerelease (4.16.0-rc.1) that the upcoming release already published, and
#   that is why the version comparison below exists instead of a blind
#   `npm dist-tag add`.
#
# Exit codes:
#   0  the tag is current: already at the version, moved and verified, or a
#      newer version owns it and was deliberately left alone
#   1  the version is not on npm, or the tag could not be moved/verified
#   2  bad usage
#
# Designed for CI (GitHub Actions) but also runs on developer machines.
# ---------------------------------------------------------------------------
set -euo pipefail

PACKAGE=''
VERSION=''
DIST_TAG='next'
ATTEMPTS=5
INTERVAL=3
DRY_RUN=false

usage() {
    cat <<'USAGE'
Usage: sync-npm-dist-tag.sh --package <name> --version <version> [options]

  --package <name>    npm package name (required), e.g. browser4-cli
  --version <version> version this release published (required)
  --tag <dist-tag>    dist-tag to keep current, default: next
  --attempts <n>      verification polls, default: 5
  --interval <secs>   seconds between polls, default: 3
  --dry-run           print what would change; never touch the registry
  -h, --help          show this help
USAGE
}

die_usage() {
    echo "ERROR: $*" >&2
    usage >&2
    exit 2
}

need_value() {
    # $1 is the option name; $2.. the remaining arguments
    if [ "$#" -lt 2 ] || [ -z "$2" ]; then
        die_usage "$1 requires a value"
    fi
}

while [ "$#" -gt 0 ]; do
    case "$1" in
        --package)
            need_value "$@"
            PACKAGE="$2"
            shift 2
            ;;
        --version)
            need_value "$@"
            VERSION="$2"
            shift 2
            ;;
        --tag)
            need_value "$@"
            DIST_TAG="$2"
            shift 2
            ;;
        --attempts)
            need_value "$@"
            ATTEMPTS="$2"
            shift 2
            ;;
        --interval)
            need_value "$@"
            INTERVAL="$2"
            shift 2
            ;;
        --dry-run)
            DRY_RUN=true
            shift
            ;;
        -h|--help)
            usage
            exit 0
            ;;
        *)
            die_usage "unknown argument: $1"
            ;;
    esac
done

[ -n "$PACKAGE" ] || die_usage "--package is required"
[ -n "$VERSION" ] || die_usage "--version is required"

# Inputs are interpolated into npm commands below, so validate them tightly:
# npm accepts a version spec, and a stray `--tag`/`@` would silently retarget
# something else on the registry.
if ! printf '%s' "$VERSION" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'; then
    die_usage "invalid version: '$VERSION' (expected X.Y.Z or X.Y.Z-prerelease)"
fi
if ! printf '%s' "$DIST_TAG" | grep -Eq '^[a-z][a-z0-9._-]*$'; then
    die_usage "invalid dist-tag: '$DIST_TAG'"
fi
if ! printf '%s' "$ATTEMPTS" | grep -Eq '^[0-9]+$' || [ "$ATTEMPTS" -lt 1 ]; then
    die_usage "invalid --attempts: '$ATTEMPTS' (expected a positive integer)"
fi
if ! printf '%s' "$INTERVAL" | grep -Eq '^[0-9]+$'; then
    die_usage "invalid --interval: '$INTERVAL' (expected an integer number of seconds)"
fi

# A prerelease is published under the tag by npm publish itself; nothing to do.
case "$VERSION" in
    *-*)
        echo "Prerelease $PACKAGE@$VERSION: npm publish --tag $DIST_TAG already points '$DIST_TAG' here, nothing to do"
        exit 0
        ;;
esac

# ---------------------------------------------------------------------------
# Return 0 when the stable version $1 is strictly newer than $2.
# Both are validated versions; $1 never carries a prerelease because the case
# above already returned.
# ---------------------------------------------------------------------------
is_newer() {
    local want="$1"
    local current="$2"
    local want_base="${want%%-*}"
    local current_base="${current%%-*}"
    local current_pre=''
    case "$current" in
        *-*) current_pre="${current#*-}" ;;
    esac

    local w_major w_minor w_patch c_major c_minor c_patch
    IFS=. read -r w_major w_minor w_patch <<< "$want_base"
    IFS=. read -r c_major c_minor c_patch <<< "$current_base"

    if [ "$w_major" -gt "$c_major" ]; then return 0; fi
    if [ "$w_major" -lt "$c_major" ]; then return 1; fi
    if [ "$w_minor" -gt "$c_minor" ]; then return 0; fi
    if [ "$w_minor" -lt "$c_minor" ]; then return 1; fi
    if [ "$w_patch" -gt "$c_patch" ]; then return 0; fi
    if [ "$w_patch" -lt "$c_patch" ]; then return 1; fi

    # Same base: the stable release outranks its own prerelease.
    if [ -n "$current_pre" ]; then return 0; fi
    return 1
}

STDERR_FILE="$(mktemp)"
trap 'rm -f "$STDERR_FILE"' EXIT

# The version must exist before a tag points at it: a typo, or a race with a
# registry still processing the publish, would otherwise repoint the tag at a
# version nobody can install.
if ! npm view "$PACKAGE@$VERSION" version >/dev/null 2>"$STDERR_FILE"; then
    echo "ERROR: $PACKAGE@$VERSION is not visible on npm; refusing to point '$DIST_TAG' at a version that cannot be installed" >&2
    echo "ERROR: last registry answer: $(head -n 1 "$STDERR_FILE" 2>/dev/null || true)" >&2
    echo "ERROR: re-check with: npm view $PACKAGE@$VERSION version" >&2
    exit 1
fi

CURRENT=''
if CURRENT_RAW="$(npm view "$PACKAGE@$DIST_TAG" version 2>/dev/null)"; then
    CURRENT="$(printf '%s' "$CURRENT_RAW" | tr -d '[:space:]')"
fi

if [ "$CURRENT" = "$VERSION" ]; then
    echo "npm dist-tag '$DIST_TAG' already points at $PACKAGE@$VERSION"
    exit 0
fi

if [ -n "$CURRENT" ]; then
    if ! printf '%s' "$CURRENT" | grep -Eq '^[0-9]+\.[0-9]+\.[0-9]+(-[0-9A-Za-z.]+)?$'; then
        echo "ERROR: npm dist-tag '$DIST_TAG' points at '$CURRENT', which is not a version this script understands" >&2
        echo "ERROR: inspect it with: npm view $PACKAGE dist-tags --json" >&2
        exit 1
    fi
    if ! is_newer "$VERSION" "$CURRENT"; then
        echo "npm dist-tag '$DIST_TAG' already points at $PACKAGE@$CURRENT, which is newer than $VERSION; leaving it alone"
        exit 0
    fi
fi

CURRENT_DESC="${CURRENT:-<missing>}"

if [ "$DRY_RUN" = true ]; then
    echo "[dry-run] would run: npm dist-tag add $PACKAGE@$VERSION $DIST_TAG (currently: $CURRENT_DESC)"
    exit 0
fi

echo "Moving npm dist-tag '$DIST_TAG': $CURRENT_DESC -> $VERSION"
npm dist-tag add "$PACKAGE@$VERSION" "$DIST_TAG"

attempt=0
verified=''
while [ "$attempt" -lt "$ATTEMPTS" ]; do
    attempt=$((attempt + 1))
    verified=''
    if VERIFIED_RAW="$(npm view "$PACKAGE@$DIST_TAG" version 2>/dev/null)"; then
        verified="$(printf '%s' "$VERIFIED_RAW" | tr -d '[:space:]')"
    fi
    if [ "$verified" = "$VERSION" ]; then
        echo "Verified npm dist-tag '$DIST_TAG' -> $PACKAGE@$VERSION"
        if [ -n "${GITHUB_STEP_SUMMARY:-}" ]; then
            {
                echo "## npm dist-tag"
                echo ""
                echo "- Package: \`$PACKAGE\`"
                echo "- Dist-tag: \`$DIST_TAG\`"
                echo "- Was: \`$CURRENT_DESC\`"
                echo "- Now: \`$VERSION\`"
            } >> "$GITHUB_STEP_SUMMARY"
        fi
        exit 0
    fi
    if [ "$attempt" -lt "$ATTEMPTS" ] && [ "$INTERVAL" -gt 0 ]; then
        sleep "$INTERVAL"
    fi
done

echo "ERROR: npm dist-tag '$DIST_TAG' still reads '${verified:-<missing>}' after $ATTEMPTS polls (expected $VERSION)" >&2
echo "ERROR: re-check with: npm view $PACKAGE dist-tags --json" >&2
exit 1
