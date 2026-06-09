#!/usr/bin/env bash
# download-release-assets.sh
# Download all assets from a GitHub release for platonai/Browser4.
#
# Usage:
#   bin/release/download-release-assets.sh [--tag <tag>] [--output-dir <dir>] [--help]
#
# If --tag is omitted, the latest release (including prereleases) is used.

set -euo pipefail

REPO="platonai/Browser4"
OUTPUT_DIR="."
TAG=""
SHOW_HELP=false

usage() {
  cat <<EOF
Usage: $(basename "$0") [--tag TAG] [--output-dir DIR]

Download all release assets from the platonai/Browser4 GitHub repository.

Options:
  --tag TAG         Release tag to download (e.g. v4.11.0).
                    Defaults to the latest release.
  --output-dir DIR  Directory to save downloaded assets (default: current directory).
  --help, -h        Show this help message.

Examples:
  $(basename "$0")                          # Download latest release assets to current dir
  $(basename "$0") --tag v4.10.0            # Download assets for tag v4.10.0
  $(basename "$0") --output-dir ./downloads # Download latest to ./downloads/
EOF
}

# ------------------------------------------
# Parse arguments
# ------------------------------------------
while [[ $# -gt 0 ]]; do
  case "$1" in
    --tag)
      shift
      if [[ -z "${1:-}" ]]; then
        echo "Error: --tag requires a value." >&2
        exit 2
      fi
      TAG="$1"
      shift
      ;;
    --output-dir)
      shift
      if [[ -z "${1:-}" ]]; then
        echo "Error: --output-dir requires a value." >&2
        exit 2
      fi
      OUTPUT_DIR="$1"
      shift
      ;;
    --help|-h)
      usage
      exit 0
      ;;
    *)
      echo "Unknown argument: $1" >&2
      usage
      exit 2
      ;;
  esac
done

# ------------------------------------------
# Prerequisites
# ------------------------------------------
check_command() {
  if ! command -v "$1" >/dev/null 2>&1; then
    echo "Error: '$1' is required but not found in PATH." >&2
    exit 1
  fi
}

check_command curl
check_command jq

# ------------------------------------------
# Resolve the release
# ------------------------------------------
if [[ -n "$TAG" ]]; then
  echo "🔍 Fetching release for tag: $TAG"
  RELEASE_URL="https://api.github.com/repos/$REPO/releases/tags/$TAG"
  RELEASE_JSON=$(curl -sSf -H "Accept: application/vnd.github+json" \
    ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
    "$RELEASE_URL") || {
    echo "Error: Failed to fetch release for tag '$TAG'." >&2
    echo "Make sure the tag exists and you have network access." >&2
    echo "For private repos or rate-limit issues, set GITHUB_TOKEN in your environment." >&2
    exit 1
  }
else
  echo "🔍 Fetching latest release..."
  RELEASE_JSON=$(curl -sSf -H "Accept: application/vnd.github+json" \
    ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
    "https://api.github.com/repos/$REPO/releases?per_page=1") || {
    echo "Error: Failed to fetch latest release." >&2
    echo "For private repos or rate-limit issues, set GITHUB_TOKEN in your environment." >&2
    exit 1
  }
  RELEASE_JSON=$(echo "$RELEASE_JSON" | jq '.[0]')
fi

# Verify we got a valid release
RELEASE_NAME=$(echo "$RELEASE_JSON" | jq -r '.name // .tag_name // empty')
if [[ -z "$RELEASE_NAME" ]]; then
  echo "Error: Could not find a release${TAG:+ for tag '$TAG'}." >&2
  if [[ -z "$TAG" ]]; then
    echo "No releases found in the repository." >&2
  fi
  exit 1
fi

RELEASE_TAG=$(echo "$RELEASE_JSON" | jq -r '.tag_name')
ASSET_COUNT=$(echo "$RELEASE_JSON" | jq -r '.assets | length')

echo "✅ Release found: $RELEASE_NAME (tag: $RELEASE_TAG)"
echo "📦 Assets: $ASSET_COUNT"

if [[ "$ASSET_COUNT" -eq 0 ]]; then
  echo "No assets to download for this release."
  exit 0
fi

# ------------------------------------------
# Create output directory
# ------------------------------------------
mkdir -p "$OUTPUT_DIR"

# ------------------------------------------
# Download assets
# ------------------------------------------
echo ""
echo "⬇️  Downloading to: $(cd "$OUTPUT_DIR" && pwd)"
echo ""

download_count=0
fail_count=0

while IFS= read -r asset; do
  name=$(echo "$asset" | jq -r '.name')
  url=$(echo "$asset" | jq -r '.browser_download_url')
  size=$(echo "$asset" | jq -r '.size')
  size_human=$(numfmt --to=iec --suffix=B "$size" 2>/dev/null || echo "${size} bytes")

  echo "  ⏳ $name ($size_human)"

  if curl -sSfL -o "$OUTPUT_DIR/$name" \
    ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
    "$url"; then
    echo "     ✅ Downloaded"
    ((download_count++)) || true
  else
    echo "     ❌ Failed to download $name" >&2
    ((fail_count++)) || true
  fi
done < <(echo "$RELEASE_JSON" | jq -c '.assets[]')

# ------------------------------------------
# Summary
# ------------------------------------------
echo ""
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "Download complete: $download_count succeeded, $fail_count failed"
echo "Release: $RELEASE_TAG"
echo "Location: $(cd "$OUTPUT_DIR" && pwd)"

if [[ "$fail_count" -gt 0 ]]; then
  echo ""
  echo "⚠️  Some assets failed to download."
  echo "   Retry with GITHUB_TOKEN set if you hit rate limits,"
  echo "   or pass a specific --tag if the release was incomplete."
  exit 1
fi

exit 0
