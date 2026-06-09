#!/usr/bin/env bash
# install-browser4-cli.sh
# Download and install the browser4-cli native binary.
#
# Detects OS, CPU architecture, and libc variant (glibc / musl), downloads
# the matching binary from GitHub Releases or Alibaba Cloud OSS, and installs
# it to a user-local directory.
#
# Usage:
#   curl -fsSL https://.../install-browser4-cli.sh | bash
#   curl -fsSL https://.../install-browser4-cli.sh | bash -s -- --silent
#
#   ./install-browser4-cli.sh [OPTIONS]
#
# Options:
#   --version, -v TAG   Release tag (e.g. "v4.11.0"). Default: latest.
#   --install-dir, -d DIR  Install directory (default: ~/.local/bin).
#   --source SRC        Force download source: "github" or "oss".
#   --no-path            Skip adding install dir to PATH.
#   --silent, -s         Suppress non-error output.
#   --dry-run            Print what would be done without doing it.
#   --help, -h           Show this message.

set -euo pipefail

# ──────────────────────────────────────────────
# Globals
# ──────────────────────────────────────────────

GITHUB_REPO="platonai/Browser4"
OSS_BASE="https://browser4.oss-cn-beijing.aliyuncs.com"

VERSION=""
INSTALL_DIR=""
SOURCE=""
ADD_TO_PATH=true
SILENT=false
DRY_RUN=false

# ──────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────

say()    { if [[ "$SILENT" != true ]]; then echo -e "$*"; fi; }
step()   { say "  → $*"; }
ok()     { say "    ✓ $*"; }
warn()   { say "    ⚠ $*" >&2; }
die()    { echo "ERROR: $*" >&2; exit 1; }

color_cyan='\033[0;36m'
color_green='\033[0;32m'
color_yellow='\033[0;33m'
color_reset='\033[0m'

header() {
  if [[ "$SILENT" != true ]]; then
    echo -e "${color_cyan}╔════════════════════════════════════════╗${color_reset}"
    echo -e "${color_cyan}║   browser4-cli Installer               ║${color_reset}"
    echo -e "${color_cyan}╚════════════════════════════════════════╝${color_reset}"
    echo ""
  fi
}

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Download and install the browser4-cli native binary.

Options:
  --version, -v TAG   Release tag (e.g. "v4.11.0"). Default: latest.
  --install-dir, -d DIR  Install directory (default: ~/.local/bin).
  --source SRC        Force source: "github" or "oss" (default: try both).
  --no-path           Skip adding install dir to shell rc file.
  --silent, -s        Suppress non-error output.
  --dry-run           Print what would be done without doing it.
  --help, -h          Show this message.

Examples:
  $(basename "$0")                          # Install latest to ~/.local/bin
  $(basename "$0") --version v4.11.0        # Install specific version
  $(basename "$0") --source oss --silent    # Silent install from Aliyun OSS
  $(basename "$0") --install-dir /usr/local/bin  # System-wide install (needs sudo)
EOF
}

# ──────────────────────────────────────────────
# Argument parsing
# ──────────────────────────────────────────────

while [[ $# -gt 0 ]]; do
  case "$1" in
    --version|-v)
      shift; [[ -z "${1:-}" ]] && die "--version requires a value"
      VERSION="$1"; shift ;;
    --install-dir|-d)
      shift; [[ -z "${1:-}" ]] && die "--install-dir requires a value"
      INSTALL_DIR="$1"; shift ;;
    --source)
      shift; [[ -z "${1:-}" ]] && die "--source requires a value"
      if [[ "$1" != "github" && "$1" != "oss" ]]; then
        die "--source must be 'github' or 'oss'"
      fi
      SOURCE="$1"; shift ;;
    --no-path) ADD_TO_PATH=false; shift ;;
    --silent|-s) SILENT=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown argument: $1 (use --help)";;
  esac
done

# ──────────────────────────────────────────────
# Platform detection
# ──────────────────────────────────────────────

detect_os() {
  case "$(uname -s)" in
    Linux)  echo "linux" ;;
    Darwin) echo "darwin" ;;
    MINGW*|MSYS*|CYGWIN*) echo "win32" ;;
    *) die "Unsupported OS: $(uname -s)" ;;
  esac
}

detect_arch() {
  local arch
  arch=$(uname -m)
  case "$arch" in
    x86_64|amd64) echo "x64" ;;
    aarch64|arm64) echo "arm64" ;;
    *) die "Unsupported architecture: $arch" ;;
  esac
}

detect_libc() {
  # Only relevant on Linux
  if [[ "$(uname -s)" != "Linux" ]]; then
    echo ""
    return
  fi

  # Check for musl
  if ldd --version 2>&1 | grep -qi musl; then
    echo "musl"
  elif [[ -f /lib/ld-musl-x86_64.so.1 ]] || [[ -f /lib/ld-musl-aarch64.so.1 ]]; then
    echo "musl"
  else
    echo ""
  fi
}

get_platform_key() {
  local os arch libc
  os=$(detect_os)
  arch=$(detect_arch)

  if [[ "$os" == "linux" ]]; then
    libc=$(detect_libc)
    if [[ -n "$libc" ]]; then
      echo "linux-${libc}-${arch}"
    else
      echo "linux-${arch}"
    fi
  else
    echo "${os}-${arch}"
  fi
}

get_binary_name() {
  local platform_key="$1"
  local ext=""
  if [[ "$platform_key" == win32-* ]]; then
    ext=".exe"
  fi
  echo "browser4-cli-${platform_key}${ext}"
}

get_default_install_dir() {
  local dir="${HOME}/.local/bin"
  # Prefer existing dir
  if [[ -d "$dir" ]]; then
    echo "$dir"
    return
  fi
  # Check if ~/bin exists
  if [[ -d "${HOME}/bin" ]]; then
    echo "${HOME}/bin"
    return
  fi
  echo "$dir"
}

# ──────────────────────────────────────────────
# Download
# ──────────────────────────────────────────────

check_commands() {
  if ! command -v curl >/dev/null 2>&1; then
    die "'curl' is required but not found. Install curl and retry."
  fi
}

get_download_urls() {
  local binary_name="$1"
  local version_tag="$2"
  local urls=()

  if [[ -n "$version_tag" ]]; then
    local gh_url="https://github.com/${GITHUB_REPO}/releases/download/${version_tag}/${binary_name}"
    local oss_url="${OSS_BASE}/releases/download/${version_tag}/${binary_name}"
  else
    # Use 'latest' redirect for GitHub, 'latest' symlink for OSS
    local gh_url="https://github.com/${GITHUB_REPO}/releases/latest/download/${binary_name}"
    local oss_url="${OSS_BASE}/releases/download/latest/${binary_name}"
  fi

  case "$SOURCE" in
    github) urls+=("GitHub Releases|${gh_url}") ;;
    oss)    urls+=("Aliyun OSS|${oss_url}") ;;
    *)
      urls+=("GitHub Releases|${gh_url}")
      urls+=("Aliyun OSS|${oss_url}")
      ;;
  esac

  printf '%s\n' "${urls[@]}"
}

download_file() {
  local url="$1"
  local dest="$2"
  local label="$3"

  step "Trying ${label}..."
  step "URL: $url"

  if [[ "$DRY_RUN" == true ]]; then
    ok "[DRY-RUN] Would download to: $dest"
    return 0
  fi

  local http_code
  http_code=$(curl -sSfL -w "%{http_code}" -o "$dest" \
    ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
    "$url" 2>/dev/null) || true

  if [[ "$http_code" == "200" ]] || [[ "$http_code" == "302" ]]; then
    local size
    size=$(stat -c%s "$dest" 2>/dev/null || stat -f%z "$dest" 2>/dev/null || echo 0)

    # Sanity check: binary must be > 100 KB
    if [[ "$size" -gt 102400 ]]; then
      local size_mb
      size_mb=$(awk "BEGIN { printf \"%.1f\", $size / 1048576 }")
      ok "Downloaded ${size_mb} MB"
      return 0
    fi

    warn "Downloaded file too small (${size} bytes) — may be an error page"
    rm -f "$dest"
    return 1
  fi

  warn "Failed: HTTP ${http_code}"
  rm -f "$dest"
  return 1
}

# ──────────────────────────────────────────────
# PATH management
# ──────────────────────────────────────────────

add_to_shell_rc() {
  local dir="$1"

  if [[ "$DRY_RUN" == true ]]; then
    ok "[DRY-RUN] Would add to shell rc: $dir"
    return
  fi

  # Pick the best rc file
  local rc_file=""
  for candidate in "$HOME/.zshrc" "$HOME/.bashrc" "$HOME/.bash_profile" "$HOME/.profile"; do
    if [[ -f "$candidate" ]]; then
      rc_file="$candidate"
      break
    fi
  done

  if [[ -z "$rc_file" ]]; then
    rc_file="$HOME/.profile"
  fi

  if grep -qF "$dir" "$rc_file" 2>/dev/null; then
    ok "PATH entry already in $rc_file"
    return
  fi

  {
    echo ""
    echo "# browser4-cli"
    echo "export PATH=\"$dir:\$PATH\""
  } >> "$rc_file"

  ok "Added to PATH in $rc_file"
  say "    Reload with: source $rc_file"
}

# ──────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────

main() {
  check_commands
  header

  # Detect platform
  local platform_key binary_name
  platform_key=$(get_platform_key)
  binary_name=$(get_binary_name "$platform_key")

  step "Platform:  $platform_key"
  step "Binary:    $binary_name"

  # Install directory
  if [[ -z "$INSTALL_DIR" ]]; then
    INSTALL_DIR=$(get_default_install_dir)
  fi
  step "Install:   $INSTALL_DIR"
  say ""

  # Ensure install directory exists
  if [[ ! -d "$INSTALL_DIR" ]]; then
    if [[ "$DRY_RUN" != true ]]; then
      mkdir -p "$INSTALL_DIR"
    fi
    step "Created directory: $INSTALL_DIR"
  fi

  local binary_path="${INSTALL_DIR}/${binary_name}"

  # Download if needed
  if [[ -f "$binary_path" ]] && [[ -z "$VERSION" ]]; then
    ok "Binary already installed: $binary_path"
  else
    local urls
    IFS=$'\n' read -r -d '' -a urls < <(get_download_urls "$binary_name" "$VERSION" && printf '\0')

    if [[ ${#urls[@]} -eq 0 ]]; then
      die "No download URLs configured"
    fi

    local downloaded=false
    local tmpfile
    tmpfile=$(mktemp)

    # Ensure cleanup
    cleanup() { rm -f "$tmpfile"; }
    trap cleanup EXIT

    for entry in "${urls[@]}"; do
      local label url
      label="${entry%%|*}"
      url="${entry#*|}"

      if download_file "$url" "$tmpfile" "$label"; then
        downloaded=true
        break
      fi
    done

    if [[ "$downloaded" != true ]]; then
      local tried_msg="Could not download browser4-cli binary."$'\n'$'\n'"Tried:"$'\n'
      for entry in "${urls[@]}"; do
        local l="${entry%%|*}" u="${entry#*|}"
        tried_msg+="  - ${l}: ${u}"$'\n'
      done
      tried_msg+=$'\n'"Please check:"$'\n'
      tried_msg+="  - Network connectivity"$'\n'
      tried_msg+="  - The version/tag exists: ${VERSION:-latest}"$'\n'
      tried_msg+="  - For GitHub rate limits, set GITHUB_TOKEN environment variable"
      die "$tried_msg"
    fi

    # Move from temp to install dir
    if [[ "$DRY_RUN" != true ]]; then
      rm -f "$binary_path"
      mv "$tmpfile" "$binary_path"
      chmod +x "$binary_path"
    fi
    ok "Installed: $binary_path"

    trap - EXIT
  fi

  # Ensure executable
  if [[ "$DRY_RUN" != true ]] && [[ -f "$binary_path" ]]; then
    chmod +x "$binary_path" 2>/dev/null || true
  fi

  # Add to PATH
  if [[ "$ADD_TO_PATH" == true ]]; then
    say ""
    add_to_shell_rc "$INSTALL_DIR"
  fi

  # Verify
  say ""
  if [[ "$DRY_RUN" != true ]]; then
    if version_output=$("$binary_path" --version 2>&1); then
      echo -e "${color_green}✓ browser4-cli installed successfully${color_reset}"
      say "  Version: $version_output"
    else
      echo -e "${color_green}✓ Binary installed at: $binary_path${color_reset}"
      warn "Could not verify --version (this is normal on first install)"
    fi
  else
    echo -e "${color_yellow}[DRY-RUN] Installation plan complete${color_reset}"
  fi

  say ""
  echo -e "${color_cyan}Run 'browser4-cli --help' to get started.${color_reset}"

  # If ADD_TO_PATH was used, remind about sourcing
  if [[ "$ADD_TO_PATH" == true ]] && [[ "$DRY_RUN" != true ]]; then
    say ""
    say "To use immediately, run:"
    say "  export PATH=\"$INSTALL_DIR:\$PATH\""
  fi
}

main
