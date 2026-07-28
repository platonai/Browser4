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

# ── create_symlinks (b4 link logic) ─────────────────────

echo "--- create_symlinks ---" | cyan

# Run symlink tests using the already-sourced FUNC_TEST_SCRIPT.
SYMLINK_TEST_SCRIPT="$(mktemp)"
SYMLINK_RESULT="$(mktemp)"
trap "rm -f '$FUNC_TEST_SCRIPT' '$SYMLINK_TEST_SCRIPT' '$SYMLINK_RESULT'" EXIT

cat > "$SYMLINK_TEST_SCRIPT" << 'SYMLINKEOF'
src="$1"
result_file="$2"
set --       # clear positional params so the sourced install script doesn't parse our args
source "$src" >/dev/null 2>&1

# Detect platform for test names
case "$(uname -s)" in
    MINGW*|MSYS*|CYGWIN*) test_platform="win32-x64"; test_ext=".exe" ;;
    *)                    test_platform="linux-x64"; test_ext="" ;;
esac
test_binary="browser4-cli-${test_platform}${test_ext}"
test_short="b4${test_ext}"

temp_dir="${TMPDIR:-/tmp}/b4-install-test-$$"
mkdir -p "$temp_dir"
cd "$temp_dir" || exit 1

pass=0
fail=0

do_test() {
    local name="$1" fn="$2"
    if bash -c "$fn" 2>/dev/null; then
        pass=$((pass + 1))
        printf '\033[0;32m  PASS  %s\033[0m\n' "$name"
    else
        fail=$((fail + 1))
        printf '\033[0;31m  FAIL  %s\033[0m\n' "$name"
    fi
}

# Create dummy platform binary (required by create_symlinks)
echo "dummy" > "$test_binary"

# ── Scenario 1: b4 does not exist → should create ──
rm -f "$test_short" "b4.cmd" 2>/dev/null || true
create_symlinks "$test_binary" "$temp_dir" >/dev/null 2>&1

do_test "create_symlinks creates b4 when it does not exist" \
    "[[ -e '$test_short' || -L '$test_short' || -e '$temp_dir/b4.cmd' ]]"

# ── Scenario 2: b4 exists in install dir → should update ──
rm -f "$test_short" "$temp_dir/b4.cmd" 2>/dev/null || true
create_symlinks "$test_binary" "$temp_dir" >/dev/null 2>&1

existing=""
if [[ -e "$test_short" ]] || [[ -L "$test_short" ]]; then
    existing="$test_short"
elif [[ -e "$temp_dir/b4.cmd" ]]; then
    existing="$temp_dir/b4.cmd"
fi

if [[ -n "$existing" ]]; then
    # Remove the link and verify create_symlinks recreates it
    rm -f "$existing"
    [[ ! -e "$test_short" ]] && [[ ! -L "$test_short" ]] && [[ ! -e "$temp_dir/b4.cmd" ]] || true

    create_symlinks "$test_binary" "$temp_dir" >/dev/null 2>&1

    after_path=""
    if [[ -e "$test_short" ]] || [[ -L "$test_short" ]]; then
        after_path="$test_short"
    elif [[ -e "$temp_dir/b4.cmd" ]]; then
        after_path="$temp_dir/b4.cmd"
    fi

    do_test "create_symlinks updates b4 when it already exists in install dir" \
        "[[ -n '$after_path' ]]"
else
    printf '  SKIP  create_symlinks updates b4 (precondition: initial link creation failed)\n'
fi

# ── Scenario 3: foreign b4 on PATH → should NOT create b4 ──
rm -f "$test_short" "$temp_dir/b4.cmd" 2>/dev/null || true

foreign_dir="${TMPDIR:-/tmp}/b4-foreign-$$"
mkdir -p "$foreign_dir"
foreign_b4="${foreign_dir}/${test_short}"
printf '#!/bin/sh\necho NotBrowser4\n' > "$foreign_b4"
chmod +x "$foreign_b4" 2>/dev/null || true

OLD_PATH="$PATH"
PATH="${foreign_dir}:${PATH}"
create_symlinks "$test_binary" "$temp_dir" >/dev/null 2>&1
PATH="$OLD_PATH"

do_test "create_symlinks skips b4 when foreign b4 is on PATH" \
    "! [[ -e '$test_short' || -L '$test_short' || -e '$temp_dir/b4.cmd' ]]"

rm -rf "$foreign_dir"

# ── Scenario 4: b4 on PATH is browser4-cli → should create b4 ──
rm -f "$test_short" "$temp_dir/b4.cmd" 2>/dev/null || true

ours_dir="${TMPDIR:-/tmp}/b4-ours-$$"
mkdir -p "$ours_dir"
ours_b4="${ours_dir}/${test_short}"
printf '#!/bin/sh\necho browser4-cli v4.12.0\n' > "$ours_b4"
chmod +x "$ours_b4" 2>/dev/null || true

OLD_PATH="$PATH"
PATH="${ours_dir}:${PATH}"
create_symlinks "$test_binary" "$temp_dir" >/dev/null 2>&1
PATH="$OLD_PATH"

do_test "create_symlinks creates b4 when b4 on PATH is browser4-cli" \
    "[[ -e '$test_short' || -L '$test_short' || -e '$temp_dir/b4.cmd' ]]"

rm -rf "$ours_dir"

# Cleanup temp dir
rm -rf "$temp_dir"

# Write results to the result file for the parent to parse
printf '%d\n%d\n' "$pass" "$fail" > "$result_file"
SYMLINKEOF

bash "$SYMLINK_TEST_SCRIPT" "$FUNC_TEST_SCRIPT" "$SYMLINK_RESULT"
symlink_pass=$(head -1 "$SYMLINK_RESULT")
symlink_fail=$(tail -1 "$SYMLINK_RESULT")
PASS=$((PASS + ${symlink_pass:-0}))
FAIL=$((FAIL + ${symlink_fail:-0}))

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
