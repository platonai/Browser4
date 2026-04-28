#!/usr/bin/env bash

set -e


repoRoot=$(cd "$(dirname "$0")">/dev/null || exit; pwd)
while [[ ! -f "$repoRoot/VERSION" && "$repoRoot" != "/" ]]; do
  repoRoot=$(dirname "$repoRoot")
done
[[ -f "$repoRoot/VERSION" ]] && cd "$repoRoot" || exit

# find out chrome version
CHROME_VERSION="$(google-chrome -version | head -n1 | awk -F '[. ]' '{print $3}')"
if [[ "$CHROME_VERSION" == "" ]]; then
  echo "Google Chrome is not found in your system, you can run bin/install-depends.sh to do it automatically"
  exit
fi

mkdir -p "$repoRoot/target/"
UBERJAR="$repoRoot"/target/Browser4.jar
if [ ! -f "$UBERJAR" ]; then
  SERVER_HOME=$repoRoot/browser4-app/browser4-agents
  cp "$SERVER_HOME"/target/Browser4.jar "$UBERJAR"
fi

java -jar "$UBERJAR"
