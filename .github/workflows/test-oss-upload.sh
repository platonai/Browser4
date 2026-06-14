#!/usr/bin/env bash
# test-oss-upload.sh — Local equivalent of sync-to-oss.yml for testing OSS uploads
#
# Usage:
#   # Test with a real GitHub release
#   export OSS_ACCESS_KEY_ID="your-key"
#   export OSS_ACCESS_KEY_SECRET="your-secret"
#   ./test-oss-upload.sh --tag v1.2.3
#
#   # Dry run (no actual upload, just validate)
#   ./test-oss-upload.sh --tag v1.2.3 --dry-run
#
#   # Upload to a test prefix (safe, won't overwrite production)
#   ./test-oss-upload.sh --tag v1.2.3 --test-prefix "test/${USER}"
#
#   # Use local assets directory instead of downloading from GitHub
#   ./test-oss-upload.sh --tag v1.2.3 --local-assets ./my-assets
#
#   # Full test with all options
#   ./test-oss-upload.sh --tag v1.2.3 --dry-run --test-prefix "dev" --verbose
#
# Prerequisites:
#   - bash 4+
#   - curl, unzip, jq
#   - gh CLI (authenticated) — only needed when downloading from GitHub
#   - OSS_ACCESS_KEY_ID / OSS_ACCESS_KEY_SECRET env vars (or pass via --key / --secret)

set -euo pipefail

# ── Defaults ──────────────────────────────────────────────────────────
OSS_ENDPOINT="${OSS_ENDPOINT:-oss-cn-beijing.aliyuncs.com}"
OSS_BUCKET="${OSS_BUCKET:-browser4}"
OSSUTIL_VERSION="2.2.1"
OSSUTIL_URL="https://gosspublic.alicdn.com/ossutil/v2/${OSSUTIL_VERSION}/ossutil-${OSSUTIL_VERSION}-linux-amd64.zip"
REPO="${REPO:-browser4-ai/Browser4}"          # GitHub repo for downloading releases
SCRIPTS_DIR="${SCRIPTS_DIR:-cli/scripts}"      # Relative path to CLI installer scripts
WORK_DIR=""                                     # Temp work dir (created on the fly)
OSSUTIL_BIN=""                                  # Path to downloaded ossutil

# ── Options ───────────────────────────────────────────────────────────
TAG_NAME=""
DRY_RUN=false
TEST_PREFIX=""          # e.g. "test/me" → uploads to oss://…/test/me/releases/… instead
LOCAL_ASSETS=""         # Path to local directory with pre-downloaded assets (skip gh download)
VERBOSE=false
OSS_KEY_ID="${OSS_ACCESS_KEY_ID:-}"
OSS_KEY_SECRET="${OSS_ACCESS_KEY_SECRET:-}"

# ── Counters ──────────────────────────────────────────────────────────
ASSETS_UPLOADED=0
ASSETS_FAILED=0
SYMLINKS_CREATED=0
SYMLINKS_FAILED=0
SCRIPTS_UPLOADED=0
SCRIPTS_FAILED=0

# ── Help ──────────────────────────────────────────────────────────────
usage() {
  cat <<'EOF'
Usage:  test-oss-upload.sh --tag <tag> [options]

Options:
  --tag <tag>          Release tag to sync (e.g. v1.2.3). Required.
  --dry-run            Validate everything but skip actual OSS uploads.
  --test-prefix <p>    Upload under this prefix instead of the root
                       (e.g. "test/staging" → oss://b/…/test/staging/releases/…).
                       Useful for testing without touching production data.
  --local-assets <dir> Use files from this local directory instead of
                       downloading from GitHub.
  --repo <owner/name>  GitHub repository (default: browser4-ai/Browser4).
  --endpoint <url>     OSS endpoint (default: oss-cn-beijing.aliyuncs.com).
  --bucket <name>      OSS bucket (default: browser4).
  --key <id>           OSS Access Key ID (or set OSS_ACCESS_KEY_ID env var).
  --secret <s>         OSS Access Key Secret (or set OSS_ACCESS_KEY_SECRET env var).
  --verbose            Print extra debug information.
  --help               Show this message.
EOF
  exit 0
}

# ── Parse args ────────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)            TAG_NAME="$2"; shift 2 ;;
    --dry-run)        DRY_RUN=true; shift ;;
    --test-prefix)    TEST_PREFIX="$2"; shift 2 ;;
    --local-assets)   LOCAL_ASSETS="$2"; shift 2 ;;
    --repo)           REPO="$2"; shift 2 ;;
    --endpoint)       OSS_ENDPOINT="$2"; shift 2 ;;
    --bucket)         OSS_BUCKET="$2"; shift 2 ;;
    --key)            OSS_KEY_ID="$2"; shift 2 ;;
    --secret)         OSS_KEY_SECRET="$2"; shift 2 ;;
    --verbose)        VERBOSE=true; shift ;;
    --help)           usage ;;
    *)                echo "Unknown option: $1"; usage ;;
  esac
done

# ── Validate ──────────────────────────────────────────────────────────
if [[ -z "$TAG_NAME" ]]; then
  echo "❌ --tag is required"
  usage
fi

if [[ "$DRY_RUN" == false ]]; then
  if [[ -z "$OSS_KEY_ID" ]]; then
    echo "❌ OSS_ACCESS_KEY_ID not set. Pass --key or export OSS_ACCESS_KEY_ID."
    exit 1
  fi
  if [[ -z "$OSS_KEY_SECRET" ]]; then
    echo "❌ OSS_ACCESS_KEY_SECRET not set. Pass --secret or export OSS_ACCESS_KEY_SECRET."
    exit 1
  fi
fi

# ── Helpers ───────────────────────────────────────────────────────────
green()  { echo -e "\033[0;32m$*\033[0m"; }
yellow() { echo -e "\033[0;33m$*\033[0m"; }
red()    { echo -e "\033[0;31m$*\033[0m"; }
bold()   { echo -e "\033[1m$*\033[0m"; }

banner() {
  echo ""
  echo "=================================================="
  echo "  $*"
  echo "=================================================="
}

oss_cp() {
  local src="$1" dest="$2"
  if [[ "$DRY_RUN" == true ]]; then
    echo "  [DRY-RUN] ossutil cp \"$src\" \"$dest\""
    return 0
  fi
  "$OSSUTIL_BIN" cp "$src" "$dest" \
    -e "$OSS_ENDPOINT" \
    --retry-times 3
}

oss_symlink() {
  local symlink="$1" target="$2"
  if [[ "$DRY_RUN" == true ]]; then
    echo "  [DRY-RUN] ossutil symlink put \"$symlink\" \"$target\""
    return 0
  fi
  "$OSSUTIL_BIN" symlink put "$symlink" "$target" \
    -e "$OSS_ENDPOINT" \
    --retry-times 3
}

compute_oss_path() {
  # Build the OSS base path respecting --test-prefix
  local base="oss://${OSS_BUCKET}"
  if [[ -n "$TEST_PREFIX" ]]; then
    base="${base}/${TEST_PREFIX}"
  fi
  echo "$base"
}

# ── Create work directory early (needed for both assets download & ossutil)
WORK_DIR=$(mktemp -d)
trap "rm -rf '$WORK_DIR'" EXIT

# ── Step 1: Banner ────────────────────────────────────────────────────
banner "OSS Upload Test — $(date '+%Y-%m-%d %H:%M:%S')"
echo ""
bold "Configuration:"
echo "  Tag:          ${TAG_NAME}"
echo "  Repo:         ${REPO}"
echo "  Endpoint:     ${OSS_ENDPOINT}"
echo "  Bucket:       ${OSS_BUCKET}"
echo "  Test prefix:  ${TEST_PREFIX:-<none>}"
echo "  Dry run:      ${DRY_RUN}"
echo "  Local assets: ${LOCAL_ASSETS:-<download from GitHub>}"
echo "  Work dir:     ${WORK_DIR}"
echo ""

# ── Step 2: Resolve release ───────────────────────────────────────────
banner "Step 2/7: Resolving Release"

if [[ -n "$LOCAL_ASSETS" ]]; then
  ASSETS_DIR="$LOCAL_ASSETS"
  ASSETS_COUNT=$(find "$ASSETS_DIR" -maxdepth 1 -type f | wc -l | tr -d ' ')
  echo "Using local assets from: $ASSETS_DIR"
  echo "Assets found: ${ASSETS_COUNT}"
else
  # Validate gh CLI is available
  if ! command -v gh &>/dev/null; then
    echo "❌ 'gh' CLI not found. Install it or use --local-assets to provide files."
    exit 1
  fi

  # Verify the release exists on GitHub
  echo "Fetching release info for tag: ${TAG_NAME}..."
  RELEASE_JSON=$(gh release view "$TAG_NAME" --repo "$REPO" --json name,url,assets,tagName --jq '.' 2>&1) || {
    echo "❌ Failed to fetch release '${TAG_NAME}' from ${REPO}"
    echo "   ${RELEASE_JSON}"
    exit 1
  }

  RELEASE_URL=$(echo "$RELEASE_JSON" | jq -r '.url')
  RELEASE_NAME=$(echo "$RELEASE_JSON" | jq -r '.name')
  ASSETS_COUNT=$(echo "$RELEASE_JSON" | jq '.assets | length')

  echo "Release:  ${RELEASE_NAME}"
  echo "URL:      ${RELEASE_URL}"
  echo "Assets:   ${ASSETS_COUNT}"

  # ── Step 3: Download assets ─────────────────────────────────────────
  banner "Step 3/7: Downloading Release Assets"

  ASSETS_DIR="${WORK_DIR}/release-assets"
  mkdir -p "$ASSETS_DIR"

  gh release download "$TAG_NAME" --repo "$REPO" --dir "$ASSETS_DIR"

  echo ""
  echo "Assets downloaded to ${ASSETS_DIR}:"
  ls -lh "$ASSETS_DIR"
fi

# ── Step 4: Install ossutil ───────────────────────────────────────────
banner "Step 4/7: Installing ossutil v${OSSUTIL_VERSION}"

OSSUTIL_BIN="${WORK_DIR}/ossutil"

if [[ "$DRY_RUN" == true ]] && [[ ! -f "$OSSUTIL_BIN" ]]; then
  echo "[DRY-RUN] Would download and extract ossutil"
else
  echo "Downloading: ${OSSUTIL_URL}"
  curl -sL "$OSSUTIL_URL" -o "${WORK_DIR}/ossutil.zip"
  unzip -q -o "${WORK_DIR}/ossutil.zip" -d "$WORK_DIR"
  # The zip extracts to ossutil-<version>-linux-amd64/ossutil (64-bit binary)
  # ossutil v2 also has a 32-bit variant; try both
  OSSUTIL_DIR=$(find "$WORK_DIR" -maxdepth 1 -type d -name "ossutil-*" | head -1)
  if [[ -n "$OSSUTIL_DIR" && -f "${OSSUTIL_DIR}/ossutil" ]]; then
    mv "${OSSUTIL_DIR}/ossutil" "$OSSUTIL_BIN"
  elif [[ -f "${WORK_DIR}/ossutil" ]]; then
    : # Already in place
  else
    echo "❌ Could not find ossutil binary in extracted contents"
    echo "Contents of work dir:"
    ls -la "$WORK_DIR"
    exit 1
  fi
  chmod +x "$OSSUTIL_BIN"
  echo ""
  "$OSSUTIL_BIN" --version
fi

# ── Step 5: Upload assets ─────────────────────────────────────────────
banner "Step 5/7: Uploading Release Assets to OSS"

OSS_BASE=$(compute_oss_path)
OSS_ASSETS_PREFIX="${OSS_BASE}/releases/download/${TAG_NAME}"

echo "Destination prefix: ${OSS_ASSETS_PREFIX}"

for asset in "$ASSETS_DIR"/*; do
  [[ -f "$asset" ]] || continue
  asset_name=$(basename "$asset")
  oss_path="${OSS_ASSETS_PREFIX}/${asset_name}"

  echo ""
  echo "Uploading: ${asset_name} → ${oss_path}"
  if oss_cp "$asset" "$oss_path"; then
    ((ASSETS_UPLOADED++)) || true
    green "  ✓ ${asset_name}"
  else
    ((ASSETS_FAILED++)) || true
    red "  ✗ Failed: ${asset_name}"
  fi
done

echo ""
bold "Assets: ${ASSETS_UPLOADED} uploaded, ${ASSETS_FAILED} failed"

if [[ $ASSETS_FAILED -gt 0 ]]; then
  red "❌ ${ASSETS_FAILED} asset(s) failed to upload"
  # Don't exit yet — continue with remaining steps so we get a full picture
fi

# ── Step 6: Upload CLI installer scripts ──────────────────────────────
banner "Step 6/7: Uploading CLI Installer Scripts"

OSS_BASE=$(compute_oss_path)
OSS_SCRIPTS_PREFIX="${OSS_BASE}/scripts"
OSS_VERSIONED_PREFIX="${OSS_BASE}/releases/download/${TAG_NAME}"

echo "Scripts convenience prefix: ${OSS_SCRIPTS_PREFIX}"
echo "Scripts versioned prefix:   ${OSS_VERSIONED_PREFIX}"

# Determine project root — try git root, fall back to script-relative
SCRIPTS_ROOT="${GITHUB_WORKSPACE:-$(git rev-parse --show-toplevel 2>/dev/null || dirname "$(dirname "$(dirname "$(readlink -f "$0")")")")}"
SCRIPTS_FULL_DIR="${SCRIPTS_ROOT}/${SCRIPTS_DIR}"

if [[ ! -d "$SCRIPTS_FULL_DIR" ]]; then
  yellow "⚠  Scripts directory not found: ${SCRIPTS_FULL_DIR}"
  yellow "   Skipping CLI script uploads. Check SCRIPTS_DIR or run from repo root."
else
  for script in install-browser4-cli.ps1 install-browser4-cli.sh; do
    src="${SCRIPTS_FULL_DIR}/${script}"

    if [[ ! -f "$src" ]]; then
      red "  ✗ Script not found: $src"
      ((SCRIPTS_FAILED++)) || true
      continue
    fi

    # 1) Upload to convenience URL (unversioned — always latest)
    dest_latest="${OSS_SCRIPTS_PREFIX}/${script}"
    echo ""
    echo "Uploading: ${script} → ${dest_latest}"
    if oss_cp "$src" "$dest_latest"; then
      green "  ✓ ${script} → scripts/"
      if [[ "$DRY_RUN" == false ]]; then
        echo "  URL: https://${OSS_BUCKET}.${OSS_ENDPOINT}/${TEST_PREFIX:+${TEST_PREFIX}/}scripts/${script}"
      fi
    else
      red "  ✗ Failed to upload ${script} to scripts/"
      ((SCRIPTS_FAILED++)) || true
      continue
    fi

    # 2) Upload to versioned release path
    dest_versioned="${OSS_VERSIONED_PREFIX}/${script}"
    echo "Uploading: ${script} → ${dest_versioned}"
    if oss_cp "$src" "$dest_versioned"; then
      green "  ✓ ${script} → releases/download/${TAG_NAME}/"
      if [[ "$DRY_RUN" == false ]]; then
        echo "  URL: https://${OSS_BUCKET}.${OSS_ENDPOINT}/${TEST_PREFIX:+${TEST_PREFIX}/}releases/download/${TAG_NAME}/${script}"
      fi
    else
      red "  ✗ Failed to upload ${script} to versioned path"
      ((SCRIPTS_FAILED++)) || true
      continue
    fi

    # 3) Copy into assets dir so Step 7 symlink loop picks it up
    if [[ "$DRY_RUN" == true ]]; then
      echo "  [DRY-RUN] Would stage ${script} in assets dir for latest symlink"
    else
      cp "$src" "${ASSETS_DIR}/${script}"
      echo "Staged ${script} in assets dir for latest symlink"
    fi

    ((SCRIPTS_UPLOADED++)) || true
  done
fi

echo ""
bold "CLI scripts: ${SCRIPTS_UPLOADED} uploaded, ${SCRIPTS_FAILED} failed"

if [[ $SCRIPTS_FAILED -gt 0 ]]; then
  red "❌ ${SCRIPTS_FAILED} CLI installer script(s) failed"
fi

# ── Step 7: Create symlinks ───────────────────────────────────────────
banner "Step 7/7: Creating Latest Symlinks"

OSS_SYMLINK_PREFIX="${OSS_BASE}/releases/latest/download"

echo "Symlink prefix: ${OSS_SYMLINK_PREFIX}"

for asset in "$ASSETS_DIR"/*; do
  [[ -f "$asset" ]] || continue
  asset_name=$(basename "$asset")

  target="${OSS_BASE}/releases/download/${TAG_NAME}/${asset_name}"
  symlink="${OSS_SYMLINK_PREFIX}/${asset_name}"

  echo ""
  echo "Symlink: ${symlink} → ${target}"
  if oss_symlink "$symlink" "$target"; then
    ((SYMLINKS_CREATED++)) || true
    green "  ✓ ${asset_name}"
  else
    ((SYMLINKS_FAILED++)) || true
    red "  ✗ Failed: ${asset_name}"
  fi
done

echo ""
bold "Symlinks: ${SYMLINKS_CREATED} created, ${SYMLINKS_FAILED} failed"

if [[ $SYMLINKS_FAILED -gt 0 ]]; then
  red "❌ ${SYMLINKS_FAILED} symlink(s) failed"
fi

# ── Summary ───────────────────────────────────────────────────────────
banner "Sync Summary"

TOTAL_SUCCESS=$(( ASSETS_UPLOADED + SYMLINKS_CREATED + SCRIPTS_UPLOADED ))
TOTAL_FAILED=$(( ASSETS_FAILED + SYMLINKS_FAILED + SCRIPTS_FAILED ))

cat <<EOF

  Release:         ${TAG_NAME}
  OSS Endpoint:    ${OSS_ENDPOINT}
  OSS Bucket:      ${OSS_BUCKET}
  Test prefix:     ${TEST_PREFIX:-<none>}
  Dry run:         ${DRY_RUN}
  ──────────────────────────────────────────
  Assets uploaded:    ${ASSETS_UPLOADED} / $((ASSETS_UPLOADED + ASSETS_FAILED))
  Symlinks created:   ${SYMLINKS_CREATED} / $((SYMLINKS_CREATED + SYMLINKS_FAILED))
  CLI scripts:        ${SCRIPTS_UPLOADED} / $((SCRIPTS_UPLOADED + SCRIPTS_FAILED))
  ──────────────────────────────────────────
  Total succeeded:    ${TOTAL_SUCCESS}
  Total failed:       ${TOTAL_FAILED}

  OSS paths:
    Assets:   ${OSS_ASSETS_PREFIX}/
    Latest:   ${OSS_SYMLINK_PREFIX}/
    Scripts (convenience):  ${OSS_SCRIPTS_PREFIX}/
    Scripts (versioned):   ${OSS_VERSIONED_PREFIX}/

EOF

if [[ "$DRY_RUN" == true ]]; then
  yellow "🏁 Dry run completed — no files were uploaded."
else
  green "🚀 Upload test completed!"
fi

# In verbose mode, cancel the auto-cleanup trap so the work dir survives
if [[ "$VERBOSE" == true ]]; then
  trap - EXIT
  echo "Work directory kept for inspection: ${WORK_DIR}"
fi

# Exit with failure if anything went wrong
if [[ $TOTAL_FAILED -gt 0 ]]; then
  red "❌ ${TOTAL_FAILED} operation(s) failed. Scroll up for details."
  exit 1
fi
