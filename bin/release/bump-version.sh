#!/usr/bin/env bash
#
# Bumps the project version based on the specified part (major, minor, or patch).
#
# This script automates the process of updating the project version. It reads the current version from the VERSION file,
# increments the specified part (major, minor, or patch), and then updates the version number in all relevant files,
# including pom.xml, READMEs, and the VERSION file itself. Finally, it commits the changes to Git.
#
# Usage:
#   ./bump-version.sh <major|minor|patch>
#
# Example:
#   ./bump-version.sh patch   # Bumps the patch version (e.g., 1.2.3 -> 1.2.4)
#   ./bump-version.sh minor   # Bumps the minor version (e.g., 1.2.3 -> 1.3.0)
#   ./bump-version.sh major   # Bumps the major version (e.g., 1.2.3 -> 2.0.0)
#

set -e

# Check for argument
if [ -z "$1" ]; then
    echo "Error: Missing argument. Please specify 'major', 'minor', or 'patch'."
    echo "Usage: $0 <major|minor|patch>"
    exit 1
fi

PART=$1
if [[ "$PART" != "major" && "$PART" != "minor" && "$PART" != "patch" ]]; then
    echo "Error: Invalid argument '$PART'. Please use 'major', 'minor', or 'patch'."
    echo "Usage: $0 <major|minor|patch>"
    exit 1
fi

# Find the project root directory
SCRIPT_DIR=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
APP_HOME=$(cd "$SCRIPT_DIR" && git rev-parse --show-toplevel)

if [ -z "$APP_HOME" ]; then
    echo "Error: Could not determine project root. Make sure you are in a git repository."
    exit 1
fi

cd "$APP_HOME"
echo "Project root is: $APP_HOME"

# Ensure we are not on the master/main branch
CURRENT_BRANCH=$(git rev-parse --abbrev-ref HEAD)
if [[ "$CURRENT_BRANCH" == "master" || "$CURRENT_BRANCH" == "main" ]]; then
    echo "You are on the '$CURRENT_BRANCH' branch. Please switch to a feature branch before running this script."
    exit 1
fi

# Get current version
SNAPSHOT_VERSION=$(cat "$APP_HOME/VERSION")
VERSION=$(echo "$SNAPSHOT_VERSION" | sed 's/-SNAPSHOT//')

# Parse version components
IFS='.' read -r -a version_parts <<< "$VERSION"
major=${version_parts[0]}
minor=${version_parts[1]}
patch=${version_parts[2]}

# Calculate the next version
case "$PART" in
    major)
        major=$((major + 1))
        minor=0
        patch=0
        ;;
    minor)
        minor=$((minor + 1))
        patch=0
        ;;
    patch)
        patch=$((patch + 1))
        ;;
esac

NEXT_VERSION="$major.$minor.$patch"
NEXT_SNAPSHOT_VERSION="$NEXT_VERSION-SNAPSHOT"

echo "Current version: $SNAPSHOT_VERSION"
echo "New version: $NEXT_SNAPSHOT_VERSION"

# Update VERSION file
echo "$NEXT_SNAPSHOT_VERSION" > "$APP_HOME/VERSION"

# Update pom.xml files using Maven
if ! ./mvnw versions:set -DnewVersion="$NEXT_SNAPSHOT_VERSION" -DprocessAllModules -DgenerateBackupPoms=false; then
    echo "Maven versions:set command failed. Reverting VERSION file."
    echo "$SNAPSHOT_VERSION" > "$APP_HOME/VERSION"
    exit 1
fi

# Update root pom.xml's git tag
# Use a different delimiter for sed since the replacement contains slashes
sed -i "s|<tag>v$VERSION</tag>|<tag>v$NEXT_VERSION</tag>|g" "$APP_HOME/pom.xml"

# Files containing the version number to upgrade
VERSION_AWARE_FILES=(
    "$APP_HOME/README.md"
    "$APP_HOME/README.zh.md"
)

# Replace version numbers in files
for F in "${VERSION_AWARE_FILES[@]}"; do
    if [ -f "$F" ]; then
        # Use a temporary file for sed to work on both Linux and macOS
        tmp_file=$(mktemp)
        sed "s/$SNAPSHOT_VERSION/$NEXT_SNAPSHOT_VERSION/g; s/v[0-9]\+\.[0-9]\+\.[0-9]\+/v$NEXT_VERSION/g" "$F" > "$tmp_file"
        mv "$tmp_file" "$F"
    fi
done

# Commit changes
COMMENT="Bump version to v$NEXT_VERSION"
echo "Ready to commit with comment: <$COMMENT>"
read -p "Are you sure to continue? [Y/n] " -n 1 -r
echo
if [[ $REPLY =~ ^[Yy]$ ]]; then
    git add .
    git commit -m "$COMMENT"
    git push
    echo "Version bumped to $NEXT_VERSION and changes pushed to remote."
else
    echo "Operation cancelled. Run 'git checkout .' to revert changes."
fi

