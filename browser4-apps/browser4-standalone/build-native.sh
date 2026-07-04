#!/bin/bash
# Build a GraalVM native image for browser4-standalone.
#
# Prerequisites:
#   - JAVA_HOME must point to a GraalVM JDK (21+) with native-image installed.
#     Install native-image via:  gu install native-image
#   - The browser4-standalone module and its dependencies must already be
#     installed in the local Maven repository:
#       mvn install -Passet-standalone -DskipTests
#
# Usage:
#   ./build-native.sh              # build with defaults
#   ./build-native.sh -DskipTests  # pass extra Maven flags
#
# The native image is written to:
#   target/browser4-standalone     (Linux/macOS)
#   target/browser4-standalone.exe (Windows)
#
# The build may take 5-15 minutes and requires at least 8 GB of free RAM.

set -e

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/../.." && pwd)"

# --- Validate JAVA_HOME ------------------------------------------------
if [ -z "$JAVA_HOME" ]; then
    echo "ERROR: JAVA_HOME is not set."
    echo "       JAVA_HOME must point to a GraalVM JDK (21+) with native-image."
    echo "       Install native-image via:  gu install native-image"
    exit 1
fi

if [ ! -f "$JAVA_HOME/bin/native-image" ] && [ ! -f "$JAVA_HOME/bin/native-image.exe" ]; then
    echo "ERROR: native-image not found in JAVA_HOME/bin"
    echo "       Install it via:  gu install native-image"
    exit 1
fi

echo "Using GraalVM JDK: $JAVA_HOME"
"$JAVA_HOME/bin/native-image" --version

# --- Build -------------------------------------------------------------
cd "$REPO_ROOT"

echo ""
echo "Building native image for browser4-standalone..."
echo "This may take 5-15 minutes."
echo ""

mvn -Pnative -Passet-standalone clean package -DskipTests "$@"

# --- Report ------------------------------------------------------------
echo ""
echo "======================================================================"
echo "Native image built successfully."
echo ""
if [ -f "$SCRIPT_DIR/target/browser4-standalone" ]; then
    ls -lh "$SCRIPT_DIR/target/browser4-standalone"
    echo ""
    echo "Run it with:  $SCRIPT_DIR/target/browser4-standalone"
elif [ -f "$SCRIPT_DIR/target/browser4-standalone.exe" ]; then
    ls -lh "$SCRIPT_DIR/target/browser4-standalone.exe"
    echo ""
    echo "Run it with:  $SCRIPT_DIR\\target\\browser4-standalone.exe"
fi
