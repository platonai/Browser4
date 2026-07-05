#!/bin/bash
# ---------------------------------------------------------------------------
# smoke-test-runtime-bundle.sh — Minimal CLI smoke test against a runtime bundle
# ---------------------------------------------------------------------------
# Usage:
#   smoke-test-runtime-bundle.sh <cli-binary> <bundle-archive> [test-port] [timeout-secs]
#
# This script:
#   1. Extracts the platform runtime bundle (.tar.gz or .zip)
#   2. Sets up a local "installed" runtime directory (no network download)
#   3. Starts a Python HTTP server serving a minimal interactive page
#   4. Runs CLI commands: open → goto → snapshot → type → click → get → eval → screenshot → wait → close → kill-all
#   5. Reports pass/fail for each step
#
# Designed for CI (GitHub Actions) but also runs on developer machines.
# ---------------------------------------------------------------------------
set -euo pipefail

CLI_BINARY="${1:?Usage: $0 <cli-binary> <bundle-archive> [test-port] [timeout-secs]}"
BUNDLE_ARCHIVE="${2:?}"
TEST_PORT="${3:-0}"   # 0 = OS picks a free port
TIMEOUT_SECS="${4:-300}"

# ---------------------------------------------------------------------------
# helpers
# ---------------------------------------------------------------------------
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
NC='\033[0m' # No Color

pass()  { echo -e "${GREEN}[PASS]${NC} $*"; }
fail()  { echo -e "${RED}[FAIL]${NC} $*"; }
info()  { echo -e "${YELLOW}[INFO]${NC} $*"; }

cleanup() {
    if [ -n "${HTTP_PID:-}" ] && kill -0 "$HTTP_PID" 2>/dev/null; then
        kill "$HTTP_PID" 2>/dev/null || true
        wait "$HTTP_PID" 2>/dev/null || true
    fi
    if [ -n "${TEMP_DIR:-}" ] && [ -d "$TEMP_DIR" ]; then
        rm -rf "$TEMP_DIR"
    fi
}
trap cleanup EXIT

# Resolve a free TCP port (port 0 not universally supported by Python http.server)
find_free_port() {
    python3 -c '
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
' 2>/dev/null || python -c '
import socket
s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
s.bind(("127.0.0.1", 0))
print(s.getsockname()[1])
s.close()
'
}

# ---------------------------------------------------------------------------
# validate inputs
# ---------------------------------------------------------------------------
if [ ! -f "$CLI_BINARY" ]; then
    fail "CLI binary not found: $CLI_BINARY"
    exit 1
fi
if [ ! -f "$BUNDLE_ARCHIVE" ]; then
    fail "Bundle archive not found: $BUNDLE_ARCHIVE"
    exit 1
fi

if [ "$TEST_PORT" -eq 0 ]; then
    TEST_PORT=$(find_free_port)
fi

# ---------------------------------------------------------------------------
# step 1: set up installed runtime directory
# ---------------------------------------------------------------------------
info "Setting up runtime directory..."

TEMP_DIR=$(mktemp -d)
RUNTIME_DIR="$TEMP_DIR/runtime-data"
VERSIONS_DIR="$RUNTIME_DIR/runtime"          # daemon.rs: RUNTIME_VERSIONS_DIR_NAME
BUNDLE_TAG="v99.99.99-smoke"
BUNDLE_DIR="$VERSIONS_DIR/$BUNDLE_TAG"       # versioned_install_dir(tag)
STATE_DIR="$TEMP_DIR/state"
WORKSPACE_DIR="$TEMP_DIR/workspace"
SERVER_LOG_DIR="$TEMP_DIR/server-logs"

mkdir -p "$BUNDLE_DIR" "$STATE_DIR" "$WORKSPACE_DIR" "$SERVER_LOG_DIR"
EXTRACT_DIR="$TEMP_DIR/extract"
mkdir -p "$EXTRACT_DIR"

# Extract archive (supports both .tar.gz and .zip)
case "$BUNDLE_ARCHIVE" in
    *.tar.gz)
        tar xzf "$BUNDLE_ARCHIVE" -C "$EXTRACT_DIR"
        ;;
    *.zip)
        # On Windows (Git Bash / MSYS2), Python is a native Windows program.
        # MSYS2 auto-converts command-line *arguments* that look like Unix
        # paths, but it cannot see paths embedded inside a -c string literal.
        # Pass the paths as sys.argv entries so they are correctly translated
        # to Windows-native paths before Python receives them.
        python3 -c "
import zipfile, sys
with zipfile.ZipFile(sys.argv[1], 'r') as z:
    z.extractall(sys.argv[2])
" "$BUNDLE_ARCHIVE" "$EXTRACT_DIR" 2>/dev/null || unzip -q "$BUNDLE_ARCHIVE" -d "$EXTRACT_DIR"
        ;;
    *)
        fail "Unsupported archive format: $BUNDLE_ARCHIVE (expected .tar.gz or .zip)"
        exit 1
        ;;
esac

# Find the bundle root (the directory containing runtime-bundle.json)
BUNDLE_ROOT=$(find "$EXTRACT_DIR" -name 'runtime-bundle.json' -maxdepth 5 | head -1 | xargs dirname 2>/dev/null || true)
if [ -z "$BUNDLE_ROOT" ] || [ ! -d "$BUNDLE_ROOT" ]; then
    fail "Could not find runtime-bundle.json in extracted archive"
    find "$EXTRACT_DIR" -maxdepth 3 -type d | while read -r d; do info "  $d"; done
    exit 1
fi
info "Bundle root: $BUNDLE_ROOT"

# Copy bundle contents into the versioned install directory
cp -r "$BUNDLE_ROOT"/* "$BUNDLE_DIR/" 2>/dev/null || cp -r "$BUNDLE_ROOT"/. "$BUNDLE_DIR/"

# Write minimal installation metadata (daemon.rs: BROWSER4_INSTALL_METADATA_FILE_NAME)
cat > "$BUNDLE_DIR/browser4-installation.json" <<META
{"tag":"$BUNDLE_TAG","asset_name":"smoke-test","download_url":"local","installed_at":"2026-01-01T00:00:00Z"}
META

# Write current.tag marker (daemon.rs: CURRENT_TAG_FILE_NAME)
echo "$BUNDLE_TAG" > "$VERSIONS_DIR/current.tag"

# On Unix, make the bundled java executable
if [ -f "$BUNDLE_DIR/runtime/bin/java" ]; then
    chmod +x "$BUNDLE_DIR/runtime/bin/java" 2>/dev/null || true
fi

info "Runtime directory: $RUNTIME_DIR"
info "Bundle version dir: $BUNDLE_DIR"

# ---------------------------------------------------------------------------
# step 2: start test page server
# ---------------------------------------------------------------------------
info "Starting test HTTP server on port $TEST_PORT..."

TEST_PAGE="$TEMP_DIR/test.html"
cat > "$TEST_PAGE" <<'HTML'
<!DOCTYPE html>
<html><head><meta charset="utf-8"><title>Smoke Test</title></head><body>
<h1>Browser4 Smoke Test</h1>
<input id="input1" type="text" value="">
<button id="btn1" onclick="document.getElementById('input1').value='clicked'">Click Me</button>
<span id="output">ready</span>
</body></html>
HTML

python3 -m http.server "$TEST_PORT" --bind 127.0.0.1 > /dev/null 2>&1 &
HTTP_PID=$!
TEST_URL="http://127.0.0.1:$TEST_PORT/test.html"

# Wait for HTTP server to be ready
for i in $(seq 1 10); do
    if curl -s -o /dev/null "$TEST_URL" 2>/dev/null; then
        break
    fi
    sleep 0.5
done

# ---------------------------------------------------------------------------
# step 3: determine server URL (let CLI auto-start the server)
# ---------------------------------------------------------------------------
# The CLI auto-starts the Browser4 server on the first command that needs it.
# We DON'T pass --server so the CLI manages the server lifecycle itself.
# This tests the full "CLI starts jlink JRE server" code path.

SERVER_PORT=$(find_free_port)
SERVER_URL="http://127.0.0.1:$SERVER_PORT"

run_cli() {
    # Set env vars that the CLI checks:
    #   BROWSER4_RUNTIME_DIR  → where the installed runtime lives
    #   BROWSER4_CLI_STATE_DIR → where CLI session state is persisted
    #   BROWSER4_SERVER_LOG_DIR → where server startup logs go
    BROWSER4_CLI_STATE_DIR="$STATE_DIR" \
    BROWSER4_RUNTIME_DIR="$RUNTIME_DIR" \
    BROWSER4_SERVER_LOG_DIR="$SERVER_LOG_DIR" \
    "$CLI_BINARY" --server "$SERVER_URL" "$@"
}

FAILED_STEPS=0
TOTAL_STEPS=0

check_step() {
    local label="$1"
    shift
    TOTAL_STEPS=$((TOTAL_STEPS + 1))
    echo ""
    info "--- $label ---"
    if run_cli "$@" 2>&1; then
        pass "$label"
    else
        fail "$label"
        FAILED_STEPS=$((FAILED_STEPS + 1))
        # On first failure, dump the server log if available
        if [ "$FAILED_STEPS" -eq 1 ]; then
            if [ -d "$SERVER_LOG_DIR" ] && [ -n "$(ls -A "$SERVER_LOG_DIR" 2>/dev/null)" ]; then
                info "--- server log (last 80 lines) ---"
                find "$SERVER_LOG_DIR" -type f -name '*.log' -exec tail -80 {} \; 2>/dev/null || true
            fi
            # Also check the temp dir for server logs (default location)
            local default_log_dir="${TMPDIR:-/tmp}/browser4/browser4-cli/tmp/cli"
            if [ -d "$default_log_dir" ] && [ -n "$(ls -A "$default_log_dir" 2>/dev/null)" ]; then
                info "--- default server log (last 80 lines) ---"
                find "$default_log_dir" -type f -name '*.log' -exec tail -80 {} \; 2>/dev/null || true
            fi
        fi
    fi
}

# ---------------------------------------------------------------------------
# step 4: run CLI commands
# ---------------------------------------------------------------------------

# 4a. open — starts the server, opens a browser session, navigates to the page
check_step "open" open "$TEST_URL" --profile-mode SEQUENTIAL --interact-level FASTEST

# 4b. goto — navigate to the page (should be a no-op or refresh)
check_step "goto" goto "$TEST_URL"

# 4c. type — fill the input field
check_step "type" type "hello-smoke" "#input1"

# 4d. click — click the button
check_step "click" click "#btn1"

# 4e. snapshot — capture page accessibility tree
check_step "snapshot" snapshot

# 4f. get text — extract text content from an element
check_step "get text" get text "#output"

# 4g. eval — execute JavaScript on the page
check_step "eval" eval "document.title"

# 4h. screenshot — capture page screenshot
check_step "screenshot" screenshot

# 4i. wait — wait for an element to be present
check_step "wait" wait "#output"

# 4j. close — close the active session
check_step "close" close

# 4k. kill-all — stop the server and clean up
check_step "kill-all" kill-all

# ---------------------------------------------------------------------------
# step 5: report
# ---------------------------------------------------------------------------
echo ""
echo "========================================"
if [ "$FAILED_STEPS" -eq 0 ]; then
    pass "SMOKE TEST PASSED ($TOTAL_STEPS/$TOTAL_STEPS steps OK)"
    exit 0
else
    fail "SMOKE TEST FAILED ($FAILED_STEPS/$TOTAL_STEPS steps failed)"
    info "Server logs at: $SERVER_LOG_DIR"
    info "Temp directory: $TEMP_DIR"
    exit 1
fi
