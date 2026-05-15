#!/bin/bash
set -euo pipefail

# Build browser4 for all platforms using Docker
# Usage: ./scripts/build-all-platforms.sh

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_ROOT="$(dirname "$SCRIPT_DIR")"
OUTPUT_DIR="$PROJECT_ROOT/bin"
SKIP_DOCKER_BUILD=false
DRY_RUN=false

while [[ $# -gt 0 ]]; do
    case "$1" in
        --skip-docker-build)
            SKIP_DOCKER_BUILD=true
            ;;
        --dry-run)
            DRY_RUN=true
            ;;
        *)
            echo "Unknown argument: $1" >&2
            exit 1
            ;;
    esac
    shift
done

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

echo -e "${YELLOW}Building browser4 for all platforms...${NC}"
echo ""

# Ensure output directory exists
mkdir -p "$OUTPUT_DIR"

run_cmd() {
    if [ "$DRY_RUN" = true ]; then
        echo "[DRY-RUN] $*"
    else
        "$@"
    fi
}

if ! command -v docker >/dev/null 2>&1; then
    echo "Docker CLI not found in PATH. Install Docker and retry." >&2
    exit 1
fi

# Build the Docker image if needed
if [ "$SKIP_DOCKER_BUILD" = false ]; then
    echo -e "${YELLOW}Building Docker cross-compilation image...${NC}"
    run_cmd docker build -t browser4-builder -f "$PROJECT_ROOT/docker/Dockerfile.build" "$PROJECT_ROOT"
fi

# Function to build for a target
build_target() {
    local target=$1
    local output_name=$2

    echo -e "${YELLOW}Building for ${target}...${NC}"

    run_cmd docker run --rm \
        -v "$PROJECT_ROOT/cli:/build" \
        -v "$OUTPUT_DIR:/output" \
        browser4-builder \
        -c "cargo zigbuild --release --target ${target} && cp /build/target/${target}/release/browser4-cli* /output/${output_name} && chmod +x /output/${output_name} 2>/dev/null || true"

    if [ "$DRY_RUN" = true ]; then
        echo -e "${YELLOW}[DRY-RUN] Skipping artifact verification for ${output_name}${NC}"
        return 0
    fi

    if [ -f "$OUTPUT_DIR/$output_name" ]; then
        echo -e "${GREEN}✓ Built ${output_name}${NC}"
    else
        echo -e "${RED}✗ Failed to build ${output_name}${NC}"
        return 1
    fi
}

# Build for each platform
# Linux x64
build_target "x86_64-unknown-linux-gnu" "browser4-cli-linux-x64"

# Linux ARM64
build_target "aarch64-unknown-linux-gnu" "browser4-cli-linux-arm64"

# Windows x64
build_target "x86_64-pc-windows-gnu" "browser4-cli-win32-x64.exe"

# macOS x64 (via zig for cross-compilation)
build_target "x86_64-apple-darwin" "browser4-cli-darwin-x64"

# macOS ARM64 (via zig for cross-compilation)
build_target "aarch64-apple-darwin" "browser4-cli-darwin-arm64"

# Linux musl x64 (Alpine)
build_target "x86_64-unknown-linux-musl" "browser4-cli-linux-musl-x64"

# Linux musl ARM64 (Alpine)
build_target "aarch64-unknown-linux-musl" "browser4-cli-linux-musl-arm64"

echo ""
echo -e "${GREEN}Build complete!${NC}"
echo ""
echo "Binaries are in: $OUTPUT_DIR"

if [ "$DRY_RUN" = true ]; then
    echo "[DRY-RUN] Skipping artifact listing."
else
    ls -la "$OUTPUT_DIR"/browser4-cli-*
fi
