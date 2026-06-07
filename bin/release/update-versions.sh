#!/usr/bin/env bash

# 🔍 Find the first parent directory containing the VERSION file
repoRoot=$(cd "$(dirname "$0")">/dev/null || exit; pwd)
while [[ ! -f "$repoRoot/VERSION" && "$repoRoot" != "/" ]]; do
  repoRoot=$(dirname "$repoRoot")
done
[[ -f "$repoRoot/VERSION" ]] && cd "$repoRoot" || exit

echo "Deploy the project ..."
echo "Changing version ..."

SNAPSHOT_VERSION=$(head -n 1 "$repoRoot/VERSION")
VERSION=${SNAPSHOT_VERSION/-SNAPSHOT/}
echo "$VERSION" > "$repoRoot/VERSION"

# Replace SNAPSHOT version with the release version
for FILE_PATTERN in 'pom.xml' 'llm-config.md' 'README.md' 'README.zh.md'; do
  find "$repoRoot" -maxdepth 8 -name "$FILE_PATTERN" -type f | while read FILE; do
    sed -i "s/$SNAPSHOT_VERSION/$VERSION/g" "$FILE"
  done
done

# Sync the CLI version from the VERSION file to package.json and Cargo.toml
if [ -f "$repoRoot/cli/scripts/sync-version.js" ]; then
  echo "Syncing CLI version metadata..."
  node "$repoRoot/cli/scripts/sync-version.js"
fi
