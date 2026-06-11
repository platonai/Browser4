#!/usr/bin/env bash
# trigger-cli-release.sh
# Trigger the browser4-cli release workflow (release-cli.yml).
#
# Two modes:
#   Tag mode (default): creates and pushes a v{version}-cli tag, which triggers
#     release-cli.yml via the on.push.tags trigger.
#   Dispatch mode (--dispatch): uses `gh workflow run` to trigger the workflow
#     directly without creating a tag.
#
# Usage:
#   ./bin/release/trigger-cli-release.sh                  # tag mode, current version
#   ./bin/release/trigger-cli-release.sh --dry-run        # dry_run tag
#   ./bin/release/trigger-cli-release.sh --dispatch       # workflow_dispatch mode
#   ./bin/release/trigger-cli-release.sh -v 0.2.0         # override version
#
# Options:
#   -v, --version VERSION    CLI version to release (default: from cli/VERSION-CLI)
#   --dry-run                Tag as v{version}-cli_dry_run.N
#   --dispatch               Use `gh workflow run` instead of creating a tag
#   --skip-binary-build      Skip building CLI binaries (dispatch only)
#   --ref REF                Branch/tag/commit to run from (dispatch only)
#   --repo REPO              GitHub owner/repo (default: from git remote)
#   -y, --yes                Skip confirmation prompts
#   -h, --help               Show this help

set -euo pipefail

# ──────────────────────────────────────────────
# Resolve repository root
# ──────────────────────────────────────────────
REPO_ROOT=$(cd "$(dirname "$0")/../.." 2>/dev/null && pwd)
if [[ ! -f "$REPO_ROOT/cli/VERSION-CLI" ]]; then
  REPO_ROOT=$(git rev-parse --show-toplevel 2>/dev/null || true)
fi
if [[ -z "${REPO_ROOT:-}" || ! -f "$REPO_ROOT/cli/VERSION-CLI" ]]; then
  echo "ERROR: could not locate repository root (looked for cli/VERSION-CLI)" >&2
  exit 1
fi
cd "$REPO_ROOT"

# ──────────────────────────────────────────────
# Parse arguments
# ──────────────────────────────────────────────
VERSION=""
DRY_RUN="false"
DISPATCH="false"
SKIP_BINARY_BUILD="false"
REF=""
REPO=""
YES="false"

usage() {
  sed -n '1,/^$/p' "$0" | tail -n +2
  exit 0
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    -v|--version)
      VERSION="$2"; shift 2 ;;
    --version=*)
      VERSION="${1#*=}"; shift ;;
    --dry-run)
      DRY_RUN="true"; shift ;;
    --dispatch)
      DISPATCH="true"; shift ;;
    --skip-binary-build)
      SKIP_BINARY_BUILD="true"; shift ;;
    --ref)
      REF="$2"; shift 2 ;;
    --ref=*)
      REF="${1#*=}"; shift ;;
    --repo)
      REPO="$2"; shift 2 ;;
    --repo=*)
      REPO="${1#*=}"; shift ;;
    -y|--yes)
      YES="true"; shift ;;
    -h|--help)
      usage ;;
    *)
      echo "Unknown option: $1" >&2
      usage ;;
  esac
done

# ──────────────────────────────────────────────
# Resolve version (cli/VERSION-CLI is the single source of truth)
# ──────────────────────────────────────────────
if [[ -z "$VERSION" ]]; then
  VERSION=$(head -n 1 "$REPO_ROOT/cli/VERSION-CLI" | tr -d '[:space:]')
  if [[ -z "$VERSION" ]]; then
    echo "ERROR: cli/VERSION-CLI is empty" >&2
    exit 1
  fi
fi

# Validate version format
if [[ ! "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9.]+)?$ ]]; then
  echo "ERROR: invalid version format: $VERSION" >&2
  echo "Expected: X.Y.Z or X.Y.Z-suffix.N" >&2
  exit 1
fi

# ──────────────────────────────────────────────
# Check npm registry — local version must be higher than what's published
# ──────────────────────────────────────────────
PACKAGE_NAME="browser4-cli"
echo "Checking npm registry for $PACKAGE_NAME ..."

NPM_VERSION=""
if NPM_VERSION=$(npm view "$PACKAGE_NAME" version 2>/dev/null | head -1 | tr -d '[:space:]'); then
  if [[ -n "$NPM_VERSION" && "$NPM_VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+ ]]; then
    echo "  Local:  $VERSION"
    echo "  npm:    $NPM_VERSION"

    # Extract base X.Y.Z for comparison
    LOCAL_BASE="${VERSION%%-*}"
    NPM_BASE="${NPM_VERSION%%-*}"

    # Sortable comparison using sort -V
    HIGHEST=$(printf '%s\n%s\n' "$LOCAL_BASE" "$NPM_BASE" | sort -V | tail -1)

    if [[ "$LOCAL_BASE" == "$NPM_BASE" ]]; then
      echo "WARNING: Local version ($VERSION) matches the published npm version ($NPM_VERSION)."
      echo "The npm publish step will be skipped unless the version has changed."
      if [[ "$YES" != "true" ]]; then
        read -r -p "Continue anyway? (y/N): " CONFIRM
        [[ "$CONFIRM" =~ ^[Yy]$ ]] || { echo "Cancelled."; exit 0; }
      fi
    elif [[ "$HIGHEST" != "$LOCAL_BASE" ]]; then
      echo "ERROR: Local version ($VERSION) is LOWER than the published npm version ($NPM_VERSION)." >&2
      echo "Bump cli/VERSION-CLI to a version higher than $NPM_VERSION before releasing." >&2
      exit 1
    else
      echo "  ✓ Local version is newer than npm"
    fi
  else
    echo "  No published version found on npm (first release?)"
  fi
else
  echo "  No published version found on npm (first release?)"
fi

# ──────────────────────────────────────────────
# Sync version to dependent files (package.json, Cargo.toml, Cargo.lock)
# ──────────────────────────────────────────────
echo ""
echo "Syncing version from cli/VERSION-CLI to all dependent files ..."
SYNC_SCRIPT="$REPO_ROOT/cli/scripts/sync-version.js"
if [[ -f "$SYNC_SCRIPT" ]]; then
  node "$SYNC_SCRIPT" || echo "WARNING: sync-version.js exited with code $?"
else
  echo "WARNING: sync-version.js not found at $SYNC_SCRIPT — skipping sync"
fi

# ──────────────────────────────────────────────
# Resolve repository
# ──────────────────────────────────────────────
if [[ -z "$REPO" ]]; then
  REMOTE_URL=$(git config --get remote.origin.url 2>/dev/null || echo "")
  if [[ "$REMOTE_URL" =~ github\.com[:/](.+?)(\.git)?$ ]]; then
    REPO="${BASH_REMATCH[1]}"
  elif [[ -n "${GITHUB_REPOSITORY:-}" ]]; then
    REPO="$GITHUB_REPOSITORY"
  else
    echo "ERROR: could not determine GitHub repository. Use --repo owner/name" >&2
    exit 1
  fi
fi

# ──────────────────────────────────────────────
# Build tag name (tag mode only)
# ──────────────────────────────────────────────
if [[ "$DRY_RUN" == "true" ]]; then
  # Auto-increment dry_run counter for this version
  EXISTING_DRY_RUNS=$(git tag -l "v${VERSION}-cli_dry_run.*" 2>/dev/null | sort -t. -k4 -n | tail -1)
  if [[ -n "$EXISTING_DRY_RUNS" ]]; then
    LAST_NUM="${EXISTING_DRY_RUNS##*.}"
    NEXT_NUM=$((LAST_NUM + 1))
  else
    NEXT_NUM=1
  fi
  TAG="v${VERSION}-cli_dry_run.${NEXT_NUM}"
else
  TAG="v${VERSION}-cli"
fi

# ──────────────────────────────────────────────
# Check for existing tag (tag mode)
# ──────────────────────────────────────────────
if [[ "$DISPATCH" != "true" ]]; then
  if git rev-parse --verify "refs/tags/$TAG" >/dev/null 2>&1; then
    echo "⚠️  Tag '$TAG' already exists (points to $(git rev-parse --short "refs/tags/$TAG"))."
    if [[ "$YES" != "true" ]]; then
      read -r -p "Delete existing tag and recreate? (y/N): " CONFIRM
      [[ "$CONFIRM" =~ ^[Yy]$ ]] || { echo "Cancelled."; exit 0; }
    fi
    git tag -d "$TAG"
    git push origin --delete "$TAG" 2>/dev/null || true
    echo "Deleted existing tag: $TAG"
  fi
fi

# ──────────────────────────────────────────────
# Display summary and confirm
# ──────────────────────────────────────────────
echo ""
echo "══════════════════════════════════════════════"
echo "  browser4-cli Release"
echo "══════════════════════════════════════════════"
echo ""
echo "  Repository:   $REPO"
echo "  CLI Version:  $VERSION"
echo "  Mode:         $([ "$DISPATCH" = "true" ] && echo "workflow_dispatch" || echo "tag push")"
echo "  Dry Run:      $DRY_RUN"
if [[ "$DISPATCH" != "true" ]]; then
  echo "  Tag:          $TAG"
  echo "  Current ref:  $(git rev-parse --short HEAD) ($(git rev-parse --abbrev-ref HEAD))"
fi
if [[ "$DISPATCH" == "true" ]]; then
  echo "  Skip build:   $SKIP_BINARY_BUILD"
  [[ -n "$REF" ]] && echo "  Ref:          $REF"
fi
echo ""

if [[ "$YES" != "true" ]]; then
  read -r -p "Proceed? (y/N): " CONFIRM
  [[ "$CONFIRM" =~ ^[Yy]$ ]] || { echo "Cancelled."; exit 0; }
  echo ""
fi

# ──────────────────────────────────────────────
# Execute
# ──────────────────────────────────────────────
if [[ "$DISPATCH" == "true" ]]; then
  # ── workflow_dispatch mode ──
  echo "▶ Triggering release-cli.yml via workflow_dispatch ..."
  echo ""

  DISPATCH_ARGS=()
  DISPATCH_ARGS+=("--repo" "$REPO")
  DISPATCH_ARGS+=("--ref" "${REF:-$(git rev-parse --abbrev-ref HEAD)}")

  INPUTS=()
  [[ "$DRY_RUN" == "true" ]]           && INPUTS+=("dry_run=true")
  [[ "$SKIP_BINARY_BUILD" == "true" ]] && INPUTS+=("skip_binary_build=true")

  if [[ ${#INPUTS[@]} -gt 0 ]]; then
    DISPATCH_ARGS+=("-f" "${INPUTS[@]}")
  fi

  gh workflow run release-cli.yml "${DISPATCH_ARGS[@]}"

  echo ""
  echo "✓ Workflow dispatched."
  echo "  Monitor: gh run list --repo $REPO --workflow release-cli.yml"

else
  # ── Tag push mode ──
  echo "▶ Creating tag: $TAG ..."

  git tag -a "$TAG" -m "browser4-cli v${VERSION}$([ "$DRY_RUN" = "true" ] && echo " (dry-run)")"
  echo "  Created local tag."

  echo "▶ Pushing tag to origin ..."
  git push origin "$TAG"

  echo ""
  echo "✓ Tag '$TAG' pushed to origin."
  echo "  Workflow:  https://github.com/$REPO/actions/workflows/release-cli.yml"
  echo "  Release:   https://github.com/$REPO/releases/tag/$TAG (once complete)"
fi

echo ""
echo "Done."
