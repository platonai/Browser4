#!/bin/bash

set -euo pipefail


repoRoot=$(cd "$(dirname "$0")">/dev/null || exit 1; pwd)
while [[ ! -f "$repoRoot/VERSION" && "$repoRoot" != "/" ]]; do
  repoRoot=$(dirname "$repoRoot")
done
[[ -f "$repoRoot/VERSION" ]] && cd "$repoRoot" || exit 1

function print_usage {
  echo "Usage: build.sh [-clean] [-test] [maven-args...]"
  echo ""
  echo "Options:"
  echo "  -clean      Clean before building"
  echo "  -test       Run tests (by default tests are skipped)"
  echo "  -pl         Build only specified modules (Maven argument)"
  echo "  -am         Build required modules (Maven argument)"
  echo "  -amd        Build dependent modules (Maven argument)"
  echo "  -D*         Pass system properties to Maven"
  echo ""
  echo "Examples:"
  echo "  build.sh -clean -test"
  echo "  build.sh -clean -test -pl :browser4-tests"
  echo "  build.sh -DskipTests=false"
  exit 1
}

# Maven command and options
MvnCmd="./mvnw"

# Validate Maven wrapper exists and is executable
if [[ ! -x "$repoRoot/mvnw" ]]; then
    echo "Error: Maven wrapper not found or not executable at $repoRoot/mvnw"
    exit 1
fi

# Initialize flags and additional arguments
PerformClean=false
SkipTests=true

MvnOptions=()
AdditionalMvnArgs=()

# Parse command-line arguments
for Arg in "$@"; do
  case $Arg in
    -clean)
      PerformClean=true
      ;;
    -t|-test)
      SkipTests=false
      ;;
    -h|-help|--help)
      print_usage
      ;;
    # Allow Maven-specific arguments to pass through
    -pl|-am|-amd|-f|-file|-gs|-gt|-s|-settings|-Dmaven.*|-DskipTests*|-Dtest*)
      AdditionalMvnArgs+=("$Arg")
      ;;
    *)
      AdditionalMvnArgs+=("$Arg")
      ;;
  esac
done

# Conditionally add Maven options based on flags
if $PerformClean; then
  MvnOptions+=("clean")
fi

if $SkipTests; then
  AdditionalMvnArgs+=("-DskipTests")
fi

# Function to execute Maven command in a given directory
function invokeMavenBuild {
  local Directory=$1
  shift
  local MvnOptions=("$@")

  pushd "$Directory" > /dev/null || exit 1

  "$MvnCmd" "${MvnOptions[@]}"

  popd > /dev/null || exit 1
}

function invokeCargoBuild {
  local Directory=$1
  local RunTests=$2

  if ! command -v cargo >/dev/null 2>&1; then
    echo "Error: cargo is not installed or not in PATH"
    exit 1
  fi

  pushd "$Directory" > /dev/null || exit 1

  if [[ "$RunTests" == "true" ]]; then
    cargo test --locked --bin browser4-cli
  fi

  cargo build --release --locked

  popd > /dev/null || exit 1
}

function copyBrowser4JarToTarget {
  local sourceJar="$repoRoot/browser4-apps/browser4-agents/target/Browser4.jar"
  local targetDir="$repoRoot/target"
  local targetJar="$targetDir/Browser4.jar"

  if [[ ! -f "$sourceJar" ]]; then
    echo "Error: Browser4.jar not found at $sourceJar"
    exit 1
  fi

  mkdir -p "$targetDir"
  cp "$sourceJar" "$targetJar"
}

# Execute Maven package in the application home directory
MvnOptions+=("install")

MvnOptions+=("${AdditionalMvnArgs[@]}")
invokeMavenBuild "$repoRoot" "${MvnOptions[@]}"
copyBrowser4JarToTarget
invokeCargoBuild "$repoRoot/cli/browser4-cli" "$([[ "$SkipTests" == "true" ]] && echo false || echo true)"
