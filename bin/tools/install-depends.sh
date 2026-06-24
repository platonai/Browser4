#!/usr/bin/env bash
#
# install-depends.sh — Install or verify system dependencies for building Browser4 from source.
#
# Checks for (and with -i, installs) the prerequisites listed in the
# "Build from Source" section of README.md:
#
#   Git, JDK 17+ (21+ recommended, Eclipse Temurin), Maven 3.9+,
#   PowerShell 7 (pwsh, on Linux/macOS), Chrome/Chromium,
#   Rust (stable), Node.js + pnpm, and platform tools (tar, wget/curl).
#
# Runs in CHECK-ONLY mode by default.  Pass -i to install missing deps.
#
# Usage:
#   ./install-depends.sh            # check only
#   ./install-depends.sh -i         # install missing
#   ./install-depends.sh -i -S chrome,rust,node   # skip categories (comma-separated)
#   ./install-depends.sh -h         # help

set -euo pipefail

# ── Colors ─────────────────────────────────────────────────────────
RED='\033[0;31m'; GREEN='\033[0;32m'; YELLOW='\033[1;33m'
CYAN='\033[0;36m'; NC='\033[0m'

# ── Globals ────────────────────────────────────────────────────────
INSTALL=false
SKIP_CHROME=false
SKIP_RUST=false
SKIP_NODE=false
MISSING=0
TOTAL=0
PROJECT_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

# ── Help ───────────────────────────────────────────────────────────
usage() {
    sed -n '2,/^$/ { s/^# //; s/^#$//; p }' "$0"
    exit 0
}

# ── Parse args ─────────────────────────────────────────────────────
while [[ $# -gt 0 ]]; do
    case "$1" in
        -i|--install) INSTALL=true ;;
        -h|--help)    usage ;;
        -S|--skip)
            IFS=',' read -ra CATS <<< "$2"
            for c in "${CATS[@]}"; do
                case "$c" in
                    chrome) SKIP_CHROME=true ;;
                    rust)   SKIP_RUST=true ;;
                    node)   SKIP_NODE=true ;;
                    *) echo "Unknown skip category: $c" >&2; exit 2 ;;
                esac
            done
            shift ;;
        *) echo "Unknown option: $1" >&2; usage ;;
    esac
    shift
done

# ── Platform detection ─────────────────────────────────────────────
OS="linux"
case "$(uname -s)" in
    Darwin)  OS="macos" ;;
    Linux)   OS="linux" ;;
    MINGW*|MSYS*|CYGWIN*) OS="windows" ;;
esac

# ── Package manager ────────────────────────────────────────────────
detect_pkg_manager() {
    if   command -v apt-get &>/dev/null; then echo "apt"
    elif command -v dnf     &>/dev/null; then echo "dnf"
    elif command -v yum     &>/dev/null; then echo "yum"
    elif command -v pacman  &>/dev/null; then echo "pacman"
    elif command -v brew    &>/dev/null; then echo "brew"
    else echo "unknown"; fi
}

PKG=$(detect_pkg_manager)

# ── Install helpers ────────────────────────────────────────────────
pkg_install() {
    case "$PKG" in
        apt)   sudo apt-get install -y "$@" ;;
        dnf)   sudo dnf install -y "$@" ;;
        yum)   sudo yum install -y "$@" ;;
        pacman) sudo pacman -S --noconfirm "$@" ;;
        brew)  brew install "$@" ;;
        *)     echo "No supported package manager found. Install manually: $*" >&2; return 1 ;;
    esac
}

pkg_update() {
    case "$PKG" in
        apt) sudo apt-get update ;;
        *)   : ;;
    esac
}

# ── Version helpers ────────────────────────────────────────────────
# Extract the first X.Y.Z-like version from a string.
extract_version() { echo "$1" | grep -oE '[0-9]+\.[0-9]+(\.[0-9]+)?' | head -1; }

# Compare two dotted versions.  Returns 0 if $1 >= $2.
version_ge() {
    local a b
    a="$(extract_version "$1")"
    b="$2"
    if [[ -z "$a" || -z "$b" ]]; then return 1; fi
    IFS=. read -r a1 a2 a3 <<< "$a"
    IFS=. read -r b1 b2 b3 <<< "$b"
    a3="${a3:-0}"; b3="${b3:-0}"
    if (( a1 > b1 )); then return 0; fi
    if (( a1 < b1 )); then return 1; fi
    if (( a2 > b2 )); then return 0; fi
    if (( a2 < b2 )); then return 1; fi
    if (( a3 >= b3 )); then return 0; fi
    return 1
}

# Try a command, return its first line of version output.
try_version() {
    local cmd=$1 arg=${2:---version}
    if command -v "$cmd" &>/dev/null; then
        "$cmd" $arg 2>&1 | head -1 || true
    fi
}

# ── Check + optional install ───────────────────────────────────────
# Usage: check "Label" check_cmd [fix_cmd]
#   check_cmd  — shell expression printing version on success, nothing on failure.
#   fix_cmd    — shell expression to install the missing dependency.
check() {
    local label=$1 check_cmd=$2 fix_cmd=${3:-}
    TOTAL=$((TOTAL + 1))
    local ver
    ver=$(eval "$check_cmd" 2>/dev/null) || true
    if [[ -n "$ver" ]]; then
        echo -e "  ${GREEN}✓${NC} $label  ${ver}"
    else
        MISSING=$((MISSING + 1))
        echo -e "  ${RED}✗${NC} $label  (not found)"
        if $INSTALL && [[ -n "$fix_cmd" ]]; then
            echo "    Installing..."
            if eval "$fix_cmd" 2>/dev/null; then
                local ver2
                ver2=$(eval "$check_cmd" 2>/dev/null) || true
                if [[ -n "$ver2" ]]; then
                    echo -e "    ${GREEN}→ installed:${NC} $ver2"
                    MISSING=$((MISSING - 1))
                else
                    echo -e "    ${YELLOW}→ installation may have failed${NC}"
                fi
            else
                echo -e "    ${RED}→ installation failed${NC}"
            fi
        fi
    fi
}

# ═══════════════════════════════════════════════════════════════════
# Per-dependency install functions  (defined before use — bash is not hoisted)
# ═══════════════════════════════════════════════════════════════════

install_jdk() {
    local JDK_VER=21
    case "$OS" in
        macos)
            brew install --cask "temurin@${JDK_VER}" ;;
        *)
            case "$PKG" in
                apt)
                    pkg_update
                    pkg_install wget apt-transport-https
                    wget -qO - https://packages.adoptium.net/artifactory/api/gpg/key/public | sudo apt-key add -
                    sudo add-apt-repository -y "deb https://packages.adoptium.net/artifactory/deb $(awk -F= '/VERSION_CODENAME/{print$2}' /etc/os-release) main"
                    pkg_update
                    pkg_install "temurin-${JDK_VER}-jdk"
                    ;;
                dnf|yum)
                    pkg_install "temurin-${JDK_VER}-jdk" ;;
                pacman)
                    pkg_install jdk-openjdk ;;
            esac
            ;;
    esac
}

install_pwsh() {
    if [[ "$OS" == "macos" ]]; then
        brew install powershell
    else
        curl -fsSL https://aka.ms/install-powershell.sh | sudo bash
    fi
}

find_chrome() {
    local paths=()
    case "$OS" in
        macos)
            paths=("/Applications/Google Chrome.app/Contents/MacOS/Google Chrome"
                   "/Applications/Chromium.app/Contents/MacOS/Chromium") ;;
        *)
            paths=("/opt/google/chrome/chrome" "/usr/bin/google-chrome"
                   "/usr/bin/chromium-browser" "/usr/bin/chromium") ;;
    esac
    for p in "${paths[@]}"; do
        if [[ -x "$p" ]]; then echo "$p $("$p" --version 2>&1)"; return 0; fi
    done
    # Fallback: try PATH
    for n in google-chrome chromium-browser chromium chrome; do
        if command -v "$n" &>/dev/null; then echo "$n $("$n" --version 2>&1)"; return 0; fi
    done
    return 1
}

install_chrome() {
    case "$OS" in
        macos)
            brew install --cask google-chrome ;;
        *)
            case "$PKG" in
                apt)
                    wget -q https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb
                    sudo dpkg -i google-chrome*.deb; sudo apt-get install -f -y
                    rm -f google-chrome*.deb
                    ;;
                dnf|yum)
                    sudo dnf install -y https://dl.google.com/linux/direct/google-chrome-stable_current_x86_64.rpm || true
                    ;;
                pacman)
                    pkg_install chromium ;;
            esac
            ;;
    esac
}

install_node() {
    case "$OS" in
        macos)
            brew install node@24 ;;
        *)
            if command -v nvm &>/dev/null; then
                nvm install 24
            elif command -v fnm &>/dev/null; then
                fnm install 24
            else
                case "$PKG" in
                    apt)
                        curl -fsSL https://deb.nodesource.com/setup_24.x | sudo -E bash -
                        sudo apt-get install -y nodejs ;;
                    dnf|yum)
                        sudo dnf install -y nodejs ;;
                    pacman)
                        pkg_install nodejs ;;
                esac
            fi
            ;;
    esac
}

install_pnpm() {
    if command -v npm &>/dev/null; then
        npm install -g pnpm
    elif command -v corepack &>/dev/null; then
        corepack enable && corepack prepare pnpm@latest --activate
    else
        curl -fsSL https://get.pnpm.io/install.sh | sh -
    fi
}

# ═══════════════════════════════════════════════════════════════════
# 1. Git
# ═══════════════════════════════════════════════════════════════════
echo -e "${CYAN}── Git ──${NC}"
check "Git" \
    'command -v git &>/dev/null && echo "git $(git --version | head -1)"' \
    'pkg_install git'

# ═══════════════════════════════════════════════════════════════════
# 2. JDK 17+  (Eclipse Temurin recommended; 21+ even better)
# ═══════════════════════════════════════════════════════════════════
echo -e "${CYAN}── JDK  (requires 17+, 21+ recommended) ──${NC}"
check "JDK" \
    'v=$(try_version java); if [[ -n $v ]] && version_ge "$v" 17; then echo "java $v"; fi' \
    'install_jdk'

# ═══════════════════════════════════════════════════════════════════
# 3. Maven 3.9+  (advisory — mvnw wrapper is bundled)
# ═══════════════════════════════════════════════════════════════════
echo -e "${CYAN}── Maven 3.9+  (advisory — mvnw wrapper is bundled) ──${NC}"
check "Maven" \
    'if [[ -f "$PROJECT_ROOT/mvnw" ]]; then echo "(mvnw wrapper available)"; elif command -v mvn &>/dev/null; then v=$(try_version mvn); version_ge "$v" 3.9 && echo "mvn $v"; fi' \
    'pkg_install maven'

# ═══════════════════════════════════════════════════════════════════
# 4. PowerShell 7+ (pwsh) — required on Linux / macOS for jlink
# ═══════════════════════════════════════════════════════════════════
echo -e "${CYAN}── PowerShell 7+ (pwsh)  [required on Linux/macOS for jlink] ──${NC}"
if [[ "$OS" == "windows" ]]; then
    echo -e "  ${GREEN}✓${NC} PowerShell  (Windows built-in — use pwsh 7+ for best results)"
else
    check "pwsh" \
        'v=$(try_version pwsh); if [[ -n $v ]] && version_ge "$v" 7.0; then echo "pwsh $v"; fi' \
        'install_pwsh'
fi

# ═══════════════════════════════════════════════════════════════════
# 5. JDK tools  (jdeps, jlink, jpackage — bundled with JDK 16+)
# ═══════════════════════════════════════════════════════════════════
echo -e "${CYAN}── JDK tools  (jdeps, jlink, jpackage — bundled with JDK) ──${NC}"
check "jdeps"    'command -v jdeps &>/dev/null && echo "jdeps $(jdeps --version 2>&1 | head -1)"'
check "jlink"    'command -v jlink &>/dev/null && echo "jlink $(jlink --version 2>&1 | head -1)"'
check "jpackage" 'command -v jpackage &>/dev/null && echo "jpackage available"'

# ═══════════════════════════════════════════════════════════════════
# 6. Chrome / Chromium
# ═══════════════════════════════════════════════════════════════════
echo -e "${CYAN}── Chrome / Chromium  (latest) ──${NC}"
if $SKIP_CHROME; then
    echo -e "  ${YELLOW}⚠${NC} Skipped (--skip chrome)"
else
    check "Chrome" 'find_chrome' 'install_chrome'
fi

# ═══════════════════════════════════════════════════════════════════
# 7. Rust  (stable, edition 2021 — only needed for CLI builds)
# ═══════════════════════════════════════════════════════════════════
echo -e "${CYAN}── Rust  (stable — only needed for CLI build) ──${NC}"
if $SKIP_RUST; then
    echo -e "  ${YELLOW}⚠${NC} Skipped (--skip rust)"
else
    check "Rust" \
        'command -v rustc &>/dev/null && echo "$(rustc --version)"' \
        'curl --proto "=https" --tlsv1.2 -sSf https://sh.rustup.rs | sh -s -- -y'
fi

# ═══════════════════════════════════════════════════════════════════
# 8. Node.js 24+ + pnpm 10+  — only needed for CLI packaging
# ═══════════════════════════════════════════════════════════════════
echo -e "${CYAN}── Node.js + pnpm  (only needed for CLI packaging) ──${NC}"
if $SKIP_NODE; then
    echo -e "  ${YELLOW}⚠${NC} Skipped (--skip node)"
else
    check "Node.js" \
        'v=$(try_version node); [[ -n $v ]] && version_ge "$v" 24.0 && echo "node $v"' \
        'install_node'
    check "pnpm" \
        'v=$(try_version pnpm); [[ -n $v ]] && version_ge "$v" 10.0 && echo "pnpm $v"' \
        'install_pnpm'
fi

# ═══════════════════════════════════════════════════════════════════
# 9. Platform tools  (tar, wget/curl on Linux; tar on macOS)
# ═══════════════════════════════════════════════════════════════════
echo -e "${CYAN}── Platform tools ──${NC}"
case "$OS" in
    macos)
        check "tar" 'command -v tar &>/dev/null && echo "(built-in)"' ;;
    linux)
        check "tar"       'command -v tar &>/dev/null && echo "$(tar --version 2>&1 | head -1)"' 'pkg_install tar'
        check "curl/wget" 'command -v curl &>/dev/null && echo "$(curl --version 2>&1 | head -1)" || { command -v wget &>/dev/null && echo "$(wget --version 2>&1 | head -1)"; }' 'pkg_install curl'
        ;;
esac

# ═══════════════════════════════════════════════════════════════════
# Linux-only: WSL dbus workaround
# ═══════════════════════════════════════════════════════════════════
if [[ "$OS" == "linux" ]] && uname -r | grep -qi microsoft; then
    echo -e "${CYAN}── WSL dbus workaround ──${NC}"
    if command -v dbus-daemon &>/dev/null; then
        if ! pgrep -x dbus-daemon &>/dev/null; then
            echo "  Starting dbus service..."
            sudo service dbus start 2>/dev/null || true
        fi
        echo -e "  ${GREEN}✓${NC} dbus"
    else
        echo -e "  ${YELLOW}⚠${NC} dbus not found (may cause issues on WSL)"
        echo "    Install with: sudo apt-get install -y dbus"
    fi
fi

# ═══════════════════════════════════════════════════════════════════
# Summary
# ═══════════════════════════════════════════════════════════════════
echo -e "\n${CYAN}========================================${NC}"
if [[ $MISSING -eq 0 ]]; then
    echo -e "${GREEN}All $TOTAL prerequisites satisfied.${NC}"
else
    echo -e "${YELLOW}$MISSING of $TOTAL prerequisite(s) missing.${NC}"
    if ! $INSTALL; then
        echo -e "${CYAN}Re-run with -i to install missing dependencies.${NC}"
    fi
fi
echo -e "${CYAN}========================================${NC}"
exit $(( MISSING > 0 ? 1 : 0 ))
