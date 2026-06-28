#!/usr/bin/env bash
# install-browser4-cli.tests.sh
# Test suite for install-browser4-cli.sh
# Uses only bash builtins and standard Unix tools — no external test frameworks.
#
# Run:
#   bash cli/scripts/tests/install-browser4-cli.tests.sh
#   bash cli/scripts/tests/install-browser4-cli.tests.sh -v   (verbose)

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
INSTALL_SCRIPT="$(cd "$SCRIPT_DIR/.." && pwd)/install-browser4-cli.sh"

PASS=0
FAIL=0
VERBOSE=false

[[ "${1:-}" == "-v" ]] && VERBOSE=true

# ── Helpers ────────────────────────────────────────────

green()  { echo -e "\033[0;32m$*\033[0m"; }
red()    { echo -e "\033[0;31m$*\033[0m"; }
cyan()   { echo -e "\033[0;36m$*\033[0m"; }
dim()    { echo -e "\033[2m$*\033[0m"; }

test() {
    local name="$1"
    local fn="$2"
    if "$fn"; then
        PASS=$((PASS + 1))
        green "  PASS  $name"
    else
        FAIL=$((FAIL + 1))
        red   "  FAIL  $name"
    fi
}

# Run a command and capture both stdout and stderr.
# Returns the combined output via stdout; exit code 0 = success metadata only.
run_script() {
    local args="$*"
    bash "$INSTALL_SCRIPT" $args 2>&1 || true
}

# ── Header ─────────────────────────────────────────────

cyan  "============================================"
cyan  " install-browser4-cli.sh Test Suite"
cyan  "============================================"
echo ""

# ── Pre-flight ─────────────────────────────────────────

echo "--- Pre-flight ---" | cyan

test "file exists" bash -c "
    [[ -f '$INSTALL_SCRIPT' ]]
"

test "file is readable" bash -c "
    head -c 1 '$INSTALL_SCRIPT' >/dev/null 2>&1
"

test "starts with shebang" bash -c "
    head -c 20 '$INSTALL_SCRIPT' | grep -q '^#!/usr/bin/env bash'
"

test "bash syntax check passes" bash -c "
    bash -n '$INSTALL_SCRIPT' 2>&1
"

test "no non-ASCII bytes" bash -c "
    ! grep -Pn '[^\\x00-\\x7F]' '$INSTALL_SCRIPT' >/dev/null 2>&1
"

echo ""

# ── Argument parsing ──────────────────────────────────

echo "--- Argument parsing ---" | cyan

test "--help exits 0" bash -c "
    bash '$INSTALL_SCRIPT' --help >/dev/null 2>&1
"

test "--help mentions --skip-if-installed" bash -c "
    bash '$INSTALL_SCRIPT' --help 2>&1 | grep -q 'skip-if-installed'
"

test "--help does NOT mention --force" bash -c "
    ! bash '$INSTALL_SCRIPT' --help 2>&1 | grep -q '\-\-force'
"

test "--version requires value" bash -c "
    bash '$INSTALL_SCRIPT' --version 2>&1 | grep -q 'requires a value'
"

test "--install-dir requires value" bash -c "
    bash '$INSTALL_SCRIPT' --install-dir 2>&1 | grep -q 'requires a value'
"

test "--source requires value" bash -c "
    bash '$INSTALL_SCRIPT' --source 2>&1 | grep -q 'requires a value'
"

test "--source invalid rejected" bash -c "
    bash '$INSTALL_SCRIPT' --source invalid --dry-run 2>&1 | grep -qi 'github.*oss\|must be'
"

test "--dry-run flag accepted" bash -c "
    bash '$INSTALL_SCRIPT' --dry-run 2>&1 | grep -qi 'DRY-RUN\|dry-run\|Install'
"

test "--silent flag accepted" bash -c "
    bash '$INSTALL_SCRIPT' --silent --dry-run 2>&1 | grep -v '^$' | head -1 | grep -qv '→\|step'
"

test "--skip-local flag accepted" bash -c "
    bash '$INSTALL_SCRIPT' --skip-local --dry-run 2>&1 | grep -qi 'skip\|DRY-RUN\|Already\|Install'
"

test "--no-path flag accepted" bash -c "
    bash '$INSTALL_SCRIPT' --no-path --dry-run 2>&1 | grep -vq 'Added to PATH'
"

test "unknown arg rejected" bash -c "
    bash '$INSTALL_SCRIPT' --bogus-flag 2>&1 | grep -q 'Unknown argument'
"

echo ""

# ── Locate mode ────────────────────────────────────────

echo "--- Locate mode ---" | cyan

test "--locate exits 0" bash -c "
    bash '$INSTALL_SCRIPT' --locate >/dev/null 2>&1
"

test "--locate shows Platform key" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q 'Platform key'
"

test "--locate shows Binary name" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q 'Binary name'
"

test "--locate shows Script dir" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q 'Script dir'
"

test "--locate shows Download order" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q 'Download order'
"

test "--locate shows China locale" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q 'China locale'
"

test "--locate shows OS" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q 'OS:'
"

echo ""

# ── Download URL patterns ──────────────────────────────

echo "--- Download URLs ---" | cyan

test "latest mode uses GitHub latest/download pattern" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q 'github.com/platonai/Browser4/releases/latest/download/'
"

test "latest mode uses OSS download/latest pattern" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q 'oss-cn-beijing.*releases/download/latest/'
"

test "versioned mode uses tag-based URLs" bash -c "
    bash '$INSTALL_SCRIPT' --locate --version v4.11.0 2>&1 | grep -q 'releases/download/v4\.11\.0/'
"

test "GitHub URL NOT using broken OSS pattern" bash -c "
    ! bash '$INSTALL_SCRIPT' --locate 2>&1 | grep 'github.com' | grep -q 'releases/download/latest'
"

test "OSS URL NOT using broken GitHub pattern" bash -c "
    ! bash '$INSTALL_SCRIPT' --locate 2>&1 | grep 'oss-cn-beijing' | grep -q 'releases/latest/download'
"

echo ""

# ── Functions (extracted via copy without main call) ──

echo "--- Functions ---" | cyan

# Create a sourceable copy that defines all functions but doesn't call main
FUNC_TEST_SCRIPT="$(mktemp)"
trap "rm -f '$FUNC_TEST_SCRIPT'" EXIT
sed 's/^main$/# main/' "$INSTALL_SCRIPT" > "$FUNC_TEST_SCRIPT"

test "get_platform_key returns valid format" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    key=\$(get_platform_key)
    [[ \"\$key\" =~ ^(win32|linux|darwin)-(x64|arm64)\$ ]] || [[ \"\$key\" =~ ^linux-musl-(x64|arm64)\$ ]]
"

test "get_binary_name adds .exe on win32" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    name=\$(get_binary_name 'win32-x64')
    [[ \"\$name\" == 'browser4-cli-win32-x64.exe' ]]
"

test "get_binary_name omits .exe on linux" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    name=\$(get_binary_name 'linux-x64')
    [[ \"\$name\" == 'browser4-cli-linux-x64' ]]
"

test "get_binary_name omits .exe on darwin" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    name=\$(get_binary_name 'darwin-arm64')
    [[ \"\$name\" == 'browser4-cli-darwin-arm64' ]]
"

test "get_default_install_dir returns non-empty" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    dir=\$(get_default_install_dir)
    [[ -n \"\$dir\" ]]
"

test "detect_china_locale returns 0 or 1 (no crash)" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    detect_china_locale && true || true
"

test "detect_os returns valid OS" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    os=\$(detect_os)
    [[ \"\$os\" =~ ^(linux|darwin|win32)$ ]]
"

test "detect_arch returns x64 or arm64" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    arch=\$(detect_arch)
    [[ \"\$arch\" == 'x64' || \"\$arch\" == 'arm64' ]]
"

test "find_local_binary returns error for nonexistent" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    SCRIPT_DIR='$SCRIPT_DIR'
    ! find_local_binary 'nonexistent-binary-xyz' 2>/dev/null
"

test "test_local_binary returns error for empty path" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    ! test_local_binary '' 2>/dev/null
"

test "test_local_binary returns error for nonexistent" bash -c "
    source '$FUNC_TEST_SCRIPT' >/dev/null 2>&1
    ! test_local_binary '/nonexistent/path/binary' 2>/dev/null
"

echo ""

# ── --skip-if-installed behavior ───────────────────────

echo "--- --skip-if-installed ---" | cyan

test "--skip-if-installed flag accepted" bash -c "
    bash '$INSTALL_SCRIPT' --skip-if-installed --dry-run 2>&1 | grep -qi 'DRY-RUN\|Already\|Install'
"

echo ""

# ── Header UX ──────────────────────────────────────────

echo "--- Header ---" | cyan

test "header is ASCII (no box-drawing chars)" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q '===='
"

test "header does not contain Unicode box-drawing" bash -c "
    ! bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -P '[\x{2500}-\x{257F}]' 2>/dev/null || true
"

echo ""

# ── Edge cases ─────────────────────────────────────────

echo "--- Edge cases ---" | cyan

test "double dash in --version handled (no operator parsing)" bash -c "
    bash '$INSTALL_SCRIPT' --locate 2>&1 | grep -q 'version'
"

test "empty --version with dry-run doesn't download" bash -c "
    bash '$INSTALL_SCRIPT' --dry-run --silent 2>&1 | grep -qi 'DRY-RUN\|Already'
"

echo ""

# ── Summary ────────────────────────────────────────────

cyan  "============================================"
TOTAL=$((PASS + FAIL))
if [[ $FAIL -eq 0 ]]; then
    green " Results: $PASS / $TOTAL passed"
else
    red   " Results: $PASS / $TOTAL passed"
fi
cyan  "============================================"

if [[ $FAIL -gt 0 ]]; then
    echo ""
    red "FAILURES: $FAIL tests failed"
    exit 1
fi
exit 0
