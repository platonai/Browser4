#!/usr/bin/env bash
# -----------------------------------------------------------------------------
# build-native-image.sh — Compile MCPBrowserServer.jar to a native Windows
# executable via GraalVM native-image, optionally compressed with UPX.
#
# Usage:
#   ./build-native-image.sh [OPTIONS]
#
# Options:
#   -m, --mode MODE       Build mode: size (default), quick, default, pgo
#   -o, --output NAME     Output executable name (default: mcp-browser-server)
#   --skip-jar            Skip Maven JAR build (use existing JAR in target/)
#   --skip-upx            Skip UPX compression
#   -x, --upx-mode MODE   UPX compression: ultra (default), best, quick
#   -j, --java-home PATH  GraalVM JAVA_HOME (overrides env var or auto-detect)
#   --vs-base PATH        Visual Studio base dir
#                         (default: "C:/Program Files/Microsoft Visual Studio/2022/Community")
#   --msvc-ver VERSION    MSVC toolchain version (default: 14.43.34808)
#   --sdk-ver VERSION     Windows SDK version (default: 10.0.26100.0)
#   -Xmx SIZE             Heap for the native-image compiler (default: 6g)
#   -M, --mvn-opts OPTS   Extra Maven options (e.g. -T4)
#   -h, --help            Show this help and exit
#
# Build modes:
#   size    -Os +StripDebugInfo (recommended, ~31 MB before UPX)
#   default -O2 (default optimizations, ~52 MB)
#   quick   -Ob (fastest iteration, ~60 MB)
#   pgo     Profile-guided optimization (two-pass build)
#
# Prerequisites:
#   - GraalVM JDK with native-image
#   - Microsoft Visual C++ Build Tools (cl.exe)
#   - Windows SDK
#   - UPX (optional; choco install upx)
#
# Examples:
#   ./build-native-image.sh                           # size-optimized build
#   ./build-native-image.sh -m quick --skip-upx       # fast dev build, no UPX
#   ./build-native-image.sh -m pgo                    # PGO build
#   ./build-native-image.sh --skip-jar --skip-upx     # rebuild native image only
# -----------------------------------------------------------------------------

set -euo pipefail

# ---------------------------------------------------------------------------
# Resolve paths
# ---------------------------------------------------------------------------
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(cd "$SCRIPT_DIR/../.." && pwd)"
REPO_ROOT="$(cd "$PROJECT_DIR/../.." && pwd)"
TARGET_DIR="$PROJECT_DIR/browser4-browser/target"

# ---------------------------------------------------------------------------
# Defaults
# ---------------------------------------------------------------------------
BUILD_MODE="size"
UPX_MODE="ultra"
OUTPUT_NAME="mcp-browser-server"
SKIP_JAR=false
SKIP_UPX=false
JAVA_HEAP="6g"
MAVEN_OPTS=""
VS_BASE="C:/Program Files/Microsoft Visual Studio/2022/Community"
MSVC_VER="14.43.34808"
SDK_VER="10.0.26100.0"

# ---------------------------------------------------------------------------
# Parse arguments
# ---------------------------------------------------------------------------
while [[ $# -gt 0 ]]; do
    case "$1" in
        -m|--mode)
            BUILD_MODE="$2"; shift 2 ;;
        -o|--output)
            OUTPUT_NAME="$2"; shift 2 ;;
        --skip-jar)
            SKIP_JAR=true; shift ;;
        --skip-upx)
            SKIP_UPX=true; shift ;;
        -x|--upx-mode)
            UPX_MODE="$2"; shift 2 ;;
        -j|--java-home)
            export JAVA_HOME="$2"; shift 2 ;;
        --vs-base)
            VS_BASE="$2"; shift 2 ;;
        --msvc-ver)
            MSVC_VER="$2"; shift 2 ;;
        --sdk-ver)
            SDK_VER="$2"; shift 2 ;;
        -Xmx)
            JAVA_HEAP="$2"; shift 2 ;;
        -M|--mvn-opts)
            MAVEN_OPTS="$2"; shift 2 ;;
        -h|--help)
            head -40 "$0" | sed -n 's/^# //p'
            exit 0 ;;
        *)
            echo "Unknown option: $1"
            echo "Use --help for usage."
            exit 1 ;;
    esac
done

# ---------------------------------------------------------------------------
# Colour helpers
# ---------------------------------------------------------------------------
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
CYAN='\033[0;36m'
RED='\033[0;31m'
NC='\033[0m' # No Color

log()  { echo -e "${GREEN}[INFO]${NC}  $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC}  $*"; }
err()  { echo -e "${RED}[ERROR]${NC} $*"; }
step() { echo -e "${CYAN}[STEP]${NC}  $*"; }

# ---------------------------------------------------------------------------
# Banner
# ---------------------------------------------------------------------------
echo ""
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║       MCP Browser Server — Native Image Build Script        ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
log "Build mode:   ${BUILD_MODE}"
log "Output name:  ${OUTPUT_NAME}.exe"
log "UPX:          $([ "$SKIP_UPX" = true ] && echo 'skipped' || echo "${UPX_MODE}")"
log "Target dir:   ${TARGET_DIR}"
echo ""

# ---------------------------------------------------------------------------
# Step 1 — Build the shaded JAR
# ---------------------------------------------------------------------------
if [ "$SKIP_JAR" = false ]; then
    step "Building shaded JAR with Maven ..."
    (
        cd "$REPO_ROOT"
        # shellcheck disable=SC2086
        mvn package -pl browser4-core/browser4-browser -am -DskipTests $MAVEN_OPTS
    )
    log "JAR build complete."
    echo ""
else
    log "Skipping JAR build (--skip-jar)."
fi

JAR_PATH="$TARGET_DIR/MCPBrowserServer.jar"
if [ ! -f "$JAR_PATH" ]; then
    err "JAR not found at $JAR_PATH"
    err "Run without --skip-jar to build it first."
    exit 1
fi
log "JAR: $JAR_PATH"

# ---------------------------------------------------------------------------
# Step 2 — Locate / verify GraalVM
# ---------------------------------------------------------------------------
step "Checking GraalVM ..."

if [ -z "${JAVA_HOME:-}" ]; then
    # Auto-detect: try common locations
    for candidate in \
        "/d/Program Files/Java/graalvm-jdk-25.0.3+9.1" \
        "/c/Program Files/Java/graalvm-jdk-25" \
        "/d/Program Files/Java/graalvm-jdk-24+36.1" \
        "/c/Program Files/Java/graalvm-jdk-24" \
        "/c/Program Files/Java/graalvm-jdk-22" \
        ; do
        if [ -x "$candidate/bin/native-image.cmd" ] || [ -x "$candidate/bin/native-image" ]; then
            export JAVA_HOME="$candidate"
            break
        fi
    done
    if [ -z "${JAVA_HOME:-}" ]; then
        err "Could not auto-detect GraalVM. Set JAVA_HOME or pass --java-home."
        exit 1
    fi
fi

log "JAVA_HOME: $JAVA_HOME"
export PATH="$JAVA_HOME/bin:$PATH"

if ! command -v native-image.cmd &>/dev/null && ! command -v native-image &>/dev/null; then
    err "native-image not found in JAVA_HOME/bin."
    err "Make sure you have GraalVM installed with the native-image component."
    err "Install: gu install native-image"
    exit 1
fi

NATIVE_IMAGE_CMD="native-image.cmd"
command -v "$NATIVE_IMAGE_CMD" &>/dev/null || NATIVE_IMAGE_CMD="native-image"

log "native-image found: $(command -v "$NATIVE_IMAGE_CMD")"
echo ""

# ---------------------------------------------------------------------------
# Step 3 — Set up MSVC environment (Windows)
# ---------------------------------------------------------------------------
step "Setting up MSVC environment ..."

# Build all the path/include/lib variables
MSVC="$VS_BASE/VC/Tools/MSVC/$MSVC_VER"
WIN_KITS="C:/Program Files (x86)/Windows Kits/10"

export PATH="$MSVC/bin/Hostx64/x64:$PATH"
export PATH="$WIN_KITS/bin/$SDK_VER/x64:$PATH"
export INCLUDE="$MSVC/include;$WIN_KITS/Include/$SDK_VER/ucrt;$WIN_KITS/Include/$SDK_VER/shared;$WIN_KITS/Include/$SDK_VER/um;$WIN_KITS/Include/$SDK_VER/winrt"
export LIB="$MSVC/lib/x64;$WIN_KITS/Lib/$SDK_VER/ucrt/x64;$WIN_KITS/Lib/$SDK_VER/um/x64"

# Verify cl.exe is reachable
if ! command -v cl.exe &>/dev/null; then
    warn "cl.exe not found on PATH."
    warn "MSVC:  $MSVC/bin/Hostx64/x64"
    warn "SDK:   $WIN_KITS/bin/$SDK_VER/x64"
    warn ""
    warn "If you are in Git Bash, try running this script from a Developer Command Prompt,"
    warn "or adjust --vs-base / --msvc-ver / --sdk-ver."
    warn "Continuing anyway — native-image may still find it via registry ..."
else
    log "cl.exe found: $(command -v cl.exe)"
fi

# Check for ucrt headers
UCRT_HEADER="$WIN_KITS/Include/$SDK_VER/ucrt/stdio.h"
if [ ! -f "$UCRT_HEADER" ]; then
    warn "UCRT header not found at $UCRT_HEADER"
    warn "If native-image fails with 'Cannot open include file: stdio.h',"
    warn "check your --sdk-ver or Windows SDK installation."
else
    log "Windows SDK headers found."
fi
echo ""

# ---------------------------------------------------------------------------
# Step 4 — Compose native-image flags based on build mode
# ---------------------------------------------------------------------------
step "Composing native-image flags for mode: ${BUILD_MODE} ..."

# Common flags for all modes
COMMON_FLAGS=(
    -jar "$JAR_PATH"
    -o "$TARGET_DIR/${OUTPUT_NAME}.exe"
    --no-fallback
    -march=compatibility
    "-J-Xmx${JAVA_HEAP}"
)

MODE_FLAGS=()
case "$BUILD_MODE" in
    size)
        MODE_FLAGS=(-Os -H:+StripDebugInfo)
        ;;
    default)
        # -O2 is the default, no extra flags needed
        ;;
    quick)
        MODE_FLAGS=(-Ob)
        ;;
    pgo)
        PGODir="$TARGET_DIR/pgo"
        mkdir -p "$PGODir"

        INSTRUMENTED_OUT="${OUTPUT_NAME}-instrumented.exe"
        PROFILE_FILE="$PGODir/default.iprof"

        # -------- Pass 1: Instrumented build --------
        step "PGO Pass 1/2 — Building instrumented image ..."
        "$NATIVE_IMAGE_CMD" \
            -jar "$JAR_PATH" \
            -o "$TARGET_DIR/$INSTRUMENTED_OUT" \
            --pgo-instrument \
            -march=compatibility \
            "-J-Xmx${JAVA_HEAP}"

        # -------- Collect profiling data --------
        step "PGO Profiling — Running instrumented binary to collect profile data ..."
        (
            cd "$TARGET_DIR"
            "./${INSTRUMENTED_OUT}" &
            SERVER_PID=$!
            sleep 3

            # Hit a few endpoints to exercise the code paths
            curl -sf -X POST http://localhost:8182/mcp/call-tool \
                -H "Content-Type: application/json" \
                -d '{"tool":"open_session","arguments":{}}' || warn "open_session curl failed"
            curl -sf http://localhost:8182/mcp/tools || warn "tools curl failed"

            kill "$SERVER_PID" 2>/dev/null || true
            wait "$SERVER_PID" 2>/dev/null || true
        )

        # Move iprof to pgo dir
        if [ -f "$TARGET_DIR/default.iprof" ]; then
            mv "$TARGET_DIR/default.iprof" "$PROFILE_FILE"
            log "Profile saved: $PROFILE_FILE"
        else
            warn "default.iprof was not generated — PGO build will fall back to a non-PGO build."
            MODE_FLAGS=(-Os -H:+StripDebugInfo)
            warn "Falling back to 'size' mode flags."
        fi

        if [ -f "$PROFILE_FILE" ]; then
            MODE_FLAGS=("--pgo=$PROFILE_FILE")
        fi
        ;;
    *)
        err "Unknown build mode: $BUILD_MODE"
        err "Valid modes: size, default, quick, pgo"
        exit 1
        ;;
esac

# For non-pgo modes, assemble the flags
if [ "$BUILD_MODE" != "pgo" ]; then
    FULL_FLAGS=("${COMMON_FLAGS[@]}" "${MODE_FLAGS[@]}")
else
    FULL_FLAGS=("${COMMON_FLAGS[@]}" "${MODE_FLAGS[@]}")
fi

echo ""
log "Native-image command:"
echo "  $NATIVE_IMAGE_CMD ${FULL_FLAGS[*]}"
echo ""

# ---------------------------------------------------------------------------
# Step 5 — Run native-image
# ---------------------------------------------------------------------------
step "Building native image (this may take 1-2 minutes) ..."
START_TIME=$SECONDS

(
    cd "$TARGET_DIR"
    "$NATIVE_IMAGE_CMD" "${FULL_FLAGS[@]}"
)

ELAPSED=$((SECONDS - START_TIME))
MIN=$((ELAPSED / 60))
SEC=$((ELAPSED % 60))
log "Native image built in ${MIN}m ${SEC}s."
echo ""

NATIVE_EXE="$TARGET_DIR/${OUTPUT_NAME}.exe"
if [ ! -f "$NATIVE_EXE" ]; then
    err "Native image was not produced at $NATIVE_EXE"
    exit 1
fi

NATIVE_SIZE=$(stat --format=%s "$NATIVE_EXE" 2>/dev/null || echo "unknown")
if [ "$NATIVE_SIZE" != "unknown" ]; then
    NATIVE_MB=$(echo "scale=1; $NATIVE_SIZE / 1048576" | bc 2>/dev/null || echo "?")
    log "Native image size: ${NATIVE_MB} MB ($NATIVE_SIZE bytes)"
else
    log "Native image: $NATIVE_EXE"
fi

# ---------------------------------------------------------------------------
# Step 6 — UPX compression (optional)
# ---------------------------------------------------------------------------
if [ "$SKIP_UPX" = false ]; then
    if ! command -v upx &>/dev/null; then
        warn "UPX not found. Skipping compression."
        warn "Install via: choco install upx  or  scoop install upx"
    else
        step "Compressing with UPX (mode: ${UPX_MODE}) ..."

        BACKUP_EXE="${NATIVE_EXE}.uncompressed"
        cp "$NATIVE_EXE" "$BACKUP_EXE"

        case "$UPX_MODE" in
            ultra)
                log "Running upx --ultra-brute --compress-icons=3 (may take ~2 min) ..."
                UPX_START=$SECONDS
                upx --ultra-brute --compress-icons=3 -f "$NATIVE_EXE"
                UPX_ELAPSED=$((SECONDS - UPX_START))
                ;;
            best)
                log "Running upx --best --lzma ..."
                UPX_START=$SECONDS
                upx --best --lzma -f "$NATIVE_EXE"
                UPX_ELAPSED=$((SECONDS - UPX_START))
                ;;
            quick)
                log "Running upx -9 --lzma ..."
                UPX_START=$SECONDS
                upx -9 --lzma -f "$NATIVE_EXE"
                UPX_ELAPSED=$((SECONDS - UPX_START))
                ;;
            *)
                warn "Unknown UPX mode: $UPX_MODE, using --best --lzma"
                upx --best --lzma -f "$NATIVE_EXE"
                ;;
        esac

        FINAL_SIZE=$(stat --format=%s "$NATIVE_EXE" 2>/dev/null || echo "0")
        FINAL_MB=$(echo "scale=1; $FINAL_SIZE / 1048576" | bc 2>/dev/null || echo "?")

        if [ -n "${UPX_ELAPSED:-}" ]; then
            log "UPX compression done in ${UPX_ELAPSED}s."
        fi
        log "Compressed size: ${FINAL_MB} MB"

        # Verify the compressed binary is still valid
        log "Verifying compressed binary ..."
        upx -t "$NATIVE_EXE" 2>/dev/null && log "Verification OK." || warn "Verification failed — backup at $BACKUP_EXE"
        echo ""
    fi
fi

# ---------------------------------------------------------------------------
# Done
# ---------------------------------------------------------------------------
echo "╔══════════════════════════════════════════════════════════════╗"
echo "║                    Build Complete                           ║"
echo "╚══════════════════════════════════════════════════════════════╝"
echo ""
log "Executable: $NATIVE_EXE"
echo ""
log "Quick test:"
echo "  \"$NATIVE_EXE\" &"
echo "  curl -s http://localhost:8182/mcp/tools | head -c 200"
echo ""
