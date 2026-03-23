#!/usr/bin/env bash
#
# release-cli.sh — Tag a new browser4-cli release and push to remote.
#
# Usage:
#   ./bin/release/release-cli.sh <version>
#
# Example:
#   ./bin/release/release-cli.sh 0.2.0
#   ./bin/release/release-cli.sh 0.2.0-rc.1
#
# The script:
#   1. Validates the version string.
#   2. Updates the version in sdks/browser4-cli/package.json.
#   3. Creates and pushes a git tag `cli-v<version>`.
#   4. The tag push triggers the `publish-cli.yml` GitHub Actions workflow,
#      which builds, tests, publishes to npm, and creates a GitHub release.
#
set -euo pipefail

# ── Helpers ───────────────────────────────────────────────────────────────────

die() { echo "❌ $*" >&2; exit 1; }

# ── Arguments ─────────────────────────────────────────────────────────────────

VERSION="${1:-}"
[[ -n "$VERSION" ]] || die "Usage: $0 <version>  (e.g. 0.2.0 or 0.2.0-rc.1)"

if ! [[ "$VERSION" =~ ^[0-9]+\.[0-9]+\.[0-9]+(-[a-zA-Z0-9._-]+)?$ ]]; then
  die "Invalid version '$VERSION'. Expected format: X.Y.Z or X.Y.Z-<pre-release>"
fi

TAG="cli-v${VERSION}"

# ── Project root ──────────────────────────────────────────────────────────────

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
APP_HOME="$(git -C "$SCRIPT_DIR" rev-parse --show-toplevel)"
CLI_DIR="$APP_HOME/sdks/browser4-cli"

[[ -f "$CLI_DIR/package.json" ]] || die "sdks/browser4-cli/package.json not found."

# ── Check working tree ────────────────────────────────────────────────────────

cd "$APP_HOME"
if ! git diff --quiet || ! git diff --cached --quiet; then
  die "Uncommitted changes detected. Commit or stash them before releasing."
fi

# ── Duplicate tag guard ───────────────────────────────────────────────────────

if git rev-parse "$TAG" >/dev/null 2>&1; then
  die "Tag '$TAG' already exists locally."
fi
if git ls-remote --tags origin "$TAG" | grep -q "$TAG"; then
  die "Tag '$TAG' already exists on remote."
fi

# ── Bump version in package.json ─────────────────────────────────────────────

echo "📝 Updating sdks/browser4-cli/package.json → $VERSION"
# Use node to rewrite the version field cleanly
node -e "
  const fs = require('fs');
  const p = '$CLI_DIR/package.json';
  const pkg = JSON.parse(fs.readFileSync(p, 'utf8'));
  pkg.version = '$VERSION';
  fs.writeFileSync(p, JSON.stringify(pkg, null, 2) + '\n');
  console.log('  version: ' + pkg.version);
"

# Also update the version constant in src/version.ts
VERSION_FILE="$CLI_DIR/src/version.ts"
if [[ -f "$VERSION_FILE" ]]; then
  sed -i "s/export const VERSION = '.*';/export const VERSION = '$VERSION';/" "$VERSION_FILE"
  echo "📝 Updated src/version.ts → $VERSION"
fi

# ── Commit version bump ───────────────────────────────────────────────────────

git add "$CLI_DIR/package.json" "$VERSION_FILE" 2>/dev/null || true
if ! git diff --cached --quiet; then
  git commit -m "chore(cli): bump version to $VERSION"
  git push
  echo "✅ Version bump committed and pushed."
else
  echo "ℹ️  No version changes to commit (already at $VERSION)."
fi

# ── Create and push tag ───────────────────────────────────────────────────────

echo "🏷️  Creating tag $TAG …"
git tag -a "$TAG" -m "Release browser4-cli $VERSION"
git push origin "$TAG"

echo ""
echo "✅ Tag '$TAG' pushed. The publish-cli workflow will now:"
echo "   1. Build and test the CLI"
echo "   2. Publish @platonai/browser4-cli@$VERSION to npm"
echo "   3. Create a GitHub Release at https://github.com/platonai/Browser4/releases/tag/$TAG"
