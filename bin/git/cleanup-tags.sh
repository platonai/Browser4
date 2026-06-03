#!/usr/bin/env bash
# Delete all local git tags containing the given patterns.
set -euo pipefail

PATTERNS=("dry_run" "ci")

for pattern in "${PATTERNS[@]}"; do
  tags=$(git tag | grep -i "$pattern" || true)
  if [ -n "$tags" ]; then
    echo "$tags" | xargs git tag -d
  fi
done
