#!/usr/bin/env bash
set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
RUN="$SCRIPT_DIR/run.sh"

BRANCH=$(git rev-parse --abbrev-ref HEAD 2>/dev/null || echo "main")
REMOTE_URL=$(git remote get-url origin 2>/dev/null || echo "https://github.com/platonai/browser4.git")

echo "Syncing branch '$BRANCH' on Windows instance..."

"$RUN" "
cd C:\browser4
git remote set-url origin '$REMOTE_URL'
git fetch origin
git checkout -B '$BRANCH' 'origin/$BRANCH'
git log -1 --oneline
"

echo ""
echo "Branch synced. Rebuilding..."

"$RUN" "
cd C:\browser4
cargo build --release --manifest-path cli\Cargo.toml
Write-Host 'Build complete.'
"
