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
#                       Default (auto): locale-aware — OSS first for China mainland.
#   --no-path            Skip adding install dir to PATH.
#   --skip-local         Skip checking for a locally-bundled binary.
#   --locate             Print detection results and exit (no install).
#   --silent, -s         Suppress non-error output.
#   --dry-run            Print what would be done without doing it.
#   --skip-if-installed  Skip download if binary already exists at install path.
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
SKIP_IF_INSTALLED=false
SKIP_LOCAL=false
LOCATE_MODE=false
CHINA_DETECTED=false
SCRIPT_DIR=""

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

# ──────────────────────────────────────────────
# Script location — find ourselves on disk
# ──────────────────────────────────────────────

detect_script_dir() {
  # BASH_SOURCE works even when sourced; prefer it over $0.
  if [[ -n "${BASH_SOURCE[0]:-}" ]] && [[ "${BASH_SOURCE[0]}" != "bash" ]] && [[ "${BASH_SOURCE[0]}" != *stdin* ]]; then
    SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
  elif [[ -n "${0:-}" ]] && [[ "$0" != "bash" ]] && [[ "$0" != "-bash" ]] && [[ -f "$0" ]]; then
    SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"
  fi
  # If piped via curl | bash, SCRIPT_DIR stays empty — no local binaries available.
}

# Search for a pre-downloaded binary near the script (bundled/sideload install).
# Echoes the full path on success; returns non-zero when not found.
find_local_binary() {
  local binary_name="$1"
  if [[ -z "$SCRIPT_DIR" ]]; then
    return 1
  fi
  local local_path="${SCRIPT_DIR}/${binary_name}"
  if [[ -f "$local_path" ]]; then
    local size
    size=$(stat -c%s "$local_path" 2>/dev/null || stat -f%z "$local_path" 2>/dev/null || echo 0)
    if [[ "$size" -gt 102400 ]]; then  # > 100 KB minimum
      echo "$local_path"
      return 0
    fi
  fi
  return 1
}

# Check whether a local binary is usable by querying its version.
# Returns 0 if --version executes successfully, non-zero otherwise.
test_local_binary() {
  local path="$1"
  if [[ -z "$path" ]] || [[ ! -f "$path" ]]; then
    return 1
  fi
  "$path" --version >/dev/null 2>&1
}

usage() {
  cat <<EOF
Usage: $(basename "$0") [OPTIONS]

Download and install the browser4-cli native binary.

Options:
  --version, -v TAG   Release tag (e.g. "v4.11.0"). Default: latest.
  --install-dir, -d DIR  Install directory (default: ~/.local/bin).
  --source SRC        Force source: "github" or "oss".
                      Default (auto): OSS first for China mainland, GitHub first elsewhere.
  --no-path           Skip adding install dir to shell rc file.
  --skip-local        Skip checking for a locally-bundled binary alongside this script.
  --locate            Print detection results and exit without installing.
  --silent, -s        Suppress non-error output.
  --dry-run           Print what would be done without doing it.
  --skip-if-installed Skip download if binary already exists at install path.
  --help, -h          Show this message.

Examples:
  $(basename "$0")                          # Install latest to ~/.local/bin
  $(basename "$0") --version v4.11.0        # Install specific version
  $(basename "$0") --source oss --silent    # Silent install from Aliyun OSS
  $(basename "$0") --install-dir /usr/local/bin  # System-wide install (needs sudo)
  $(basename "$0") --locate                 # Run diagnostics (no install)
  $(basename "$0") --skip-local             # Force download, ignore bundled binary
  $(basename "$0") --source oss             # Force Aliyun OSS (China mainland)
  $(basename "$0") --skip-if-installed      # Skip download if already installed
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
    --skip-local) SKIP_LOCAL=true; shift ;;
    --locate) LOCATE_MODE=true; shift ;;
    --silent|-s) SILENT=true; shift ;;
    --dry-run) DRY_RUN=true; shift ;;
    --skip-if-installed) SKIP_IF_INSTALLED=true; shift ;;
    --help|-h) usage; exit 0 ;;
    *) die "Unknown argument: $1 (use --help)";;
  esac
done

# ──────────────────────────────────────────────
# China mainland locale detection (zero-network)
# ──────────────────────────────────────────────

detect_china_locale() {
  # 1 — Locale env vars
  local lang
  lang="${LC_ALL:-${LANG:-${LC_CTYPE:-${LC_MESSAGES:-}}}}"
  case "$lang" in
    zh_CN*|zh-CN*|"Chinese (Simplified)_China"*) return 0 ;;
  esac

  # 2 — TZ env var
  case "${TZ:-}" in
    Asia/Shanghai|Asia/Chongqing|Asia/Urumqi|Asia/Harbin) return 0 ;;
  esac

  # 3 — /etc/timezone
  if [[ -f /etc/timezone ]]; then
    local tz
    tz=$(cat /etc/timezone 2>/dev/null || true)
    case "$tz" in
      Asia/Shanghai|Asia/Chongqing|Asia/Urumqi|Asia/Harbin) return 0 ;;
    esac
  fi

  return 1
}

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

  # Check for musl via ldd --version (guarded against missing ldd)
  if command -v ldd >/dev/null 2>&1; then
    if ldd --version 2>&1 | grep -qi musl; then
      echo "musl"
      return
    fi
  fi

  # Check for musl loader — covers common architectures
  # x86_64, aarch64, armhf (32-bit ARM), i386, riscv64, s390x, ppc64le, mips64
  local musl_loader
  for musl_loader in \
    /lib/ld-musl-x86_64.so.1 \
    /lib/ld-musl-aarch64.so.1 \
    /lib/ld-musl-armhf.so.1 \
    /lib/ld-musl-i386.so.1 \
    /lib/ld-musl-riscv64.so.1 \
    /lib/ld-musl-s390x.so.1 \
    /lib/ld-musl-ppc64le.so.1 \
    /lib/ld-musl-mips64.so.1 \
    /lib/ld-musl-mipsel.so.1; do
    if [[ -f "$musl_loader" ]]; then
      echo "musl"
      return
    fi
  done

  echo ""
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
  local missing=()
  for cmd in curl mktemp stat awk grep ln mkdir chmod; do
    if ! command -v "$cmd" >/dev/null 2>&1; then
      missing+=("$cmd")
    fi
  done
  if [[ ${#missing[@]} -gt 0 ]]; then
    die "Required command(s) not found: ${missing[*]}. Install them and retry."
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
      if [[ "$CHINA_DETECTED" == true ]]; then
        urls+=("Aliyun OSS|${oss_url}")
        urls+=("GitHub Releases|${gh_url}")
      else
        urls+=("GitHub Releases|${gh_url}")
        urls+=("Aliyun OSS|${oss_url}")
      fi
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

  local curl_stderr
  curl_stderr=$(mktemp)
  local http_code curl_exit

  http_code=$(curl -sSfL -w "%{http_code}" -o "$dest" \
    ${GITHUB_TOKEN:+-H "Authorization: Bearer $GITHUB_TOKEN"} \
    "$url" 2>"$curl_stderr")
  curl_exit=$?

  if [[ $curl_exit -ne 0 ]]; then
    local stderr_msg
    stderr_msg=$(<"$curl_stderr")
    rm -f "$curl_stderr" "$dest"
    if [[ -n "$stderr_msg" ]]; then
      warn "curl error (exit ${curl_exit}): ${stderr_msg}"
    else
      warn "curl failed with exit code ${curl_exit} (no stderr output)"
    fi
    return 1
  fi
  rm -f "$curl_stderr"

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

  # Ensure the file ends with a newline before appending, so the new
  # content isn't glued to the last existing line.
  if [[ -s "$rc_file" ]]; then
    local last_byte
    last_byte=$(tail -c1 "$rc_file" 2>/dev/null || true)
    if [[ -n "$last_byte" ]]; then
      echo "" >> "$rc_file"
    fi
  fi

  # Use a lock file to prevent concurrent append races (best-effort).
  local lock_file="${rc_file}.browser4-install.lock"
  local lock_fd=9
  # Wait up to 10 seconds for another install process to release the lock.
  local waited=0
  while ! (umask 0002 && command -v flock >/dev/null 2>&1 && flock -n 9 2>/dev/null); do
    if [[ $waited -ge 10 ]]; then
      warn "Could not acquire lock on ${rc_file} after 10s; appending anyway"
      break
    fi
    sleep 0.5
    waited=$((waited + 1))
  done 9>"$lock_file"

  {
    echo "# browser4-cli"
    echo "export PATH=\"$dir:\$PATH\""
  } >> "$rc_file"

  # Clean up (flock auto-releases when fd 9 is closed)
  rm -f "$lock_file" 2>/dev/null || true

  ok "Added to PATH in $rc_file"
  say "    Reload with: source $rc_file"
}

# ──────────────────────────────────────────────
# Symlinks
# ──────────────────────────────────────────────

create_symlinks() {
  local binary_name="$1"
  local install_dir="$2"

  local ext=""
  if [[ "$binary_name" == *.exe ]]; then
    ext=".exe"
  fi

  # 1) Always: browser4-cli -> browser4-cli-<platform>
  local link_name="browser4-cli${ext}"
  local link_path="${install_dir}/${link_name}"

  if [[ "$DRY_RUN" == true ]]; then
    step "[DRY-RUN] Would create symlink: ${link_name} -> ${binary_name}"
  else
    ln -sf "$binary_name" "$link_path"
    ok "Created symlink: ${link_name} -> ${binary_name}"
  fi

  # 2) Only if no conflict: b4 -> browser4-cli-<platform>
  local short_name="b4${ext}"
  local short_path="${install_dir}/${short_name}"

  if command -v b4 >/dev/null 2>&1; then
    warn "Skipping short link '${short_name}': 'b4' already found on PATH"
    return
  fi

  if [[ -e "$short_path" ]] || [[ -L "$short_path" ]]; then
    warn "Skipping short link '${short_name}': already exists in ${install_dir}"
    return
  fi

  if [[ "$DRY_RUN" == true ]]; then
    step "[DRY-RUN] Would create symlink: ${short_name} -> ${binary_name}"
  else
    ln -sf "$binary_name" "$short_path"
    ok "Created symlink: ${short_name} -> ${binary_name}"
  fi
}

# ──────────────────────────────────────────────
# Main
# ──────────────────────────────────────────────

main() {
  check_commands
  header

  # Locate ourselves on disk (only works when run as a file, not piped)
  detect_script_dir

  # Auto-detect China mainland locale when no explicit source is given
  if [[ -z "$SOURCE" ]]; then
    if detect_china_locale; then
      CHINA_DETECTED=true
      step "China mainland locale detected: preferring Aliyun OSS mirror."
    fi
  fi

  # Detect platform
  local platform_key binary_name
  platform_key=$(get_platform_key)
  binary_name=$(get_binary_name "$platform_key")

  # ── Locate mode: print diagnostics and exit ──
  if [[ "$LOCATE_MODE" == true ]]; then
    echo -e "${color_cyan}─── Locate / diagnostics ───${color_reset}"
    echo ""
    step "Script dir:       ${SCRIPT_DIR:-'(not available — piped via curl?)'}"
    step "Platform key:     $platform_key"
    step "Binary name:      $binary_name"
    step "Default install:  $(get_default_install_dir)"
    step "China locale:     $CHINA_DETECTED"
    step "Source override:  ${SOURCE:-auto}"
    step "OS:               $(uname -s)"

    # Check for local binary
    local locate_local
    if locate_local=$(find_local_binary "$binary_name" 2>/dev/null); then
      local local_status="(present but --version failed)"
      if test_local_binary "$locate_local"; then
        local_status="(valid)"
      fi
      step "Local binary:     ${locate_local} ${local_status}"
    else
      step "Local binary:     not found alongside script"
    fi

    # Check for already-installed binary
    local default_dir existing_path
    default_dir=$(get_default_install_dir)
    existing_path="${default_dir}/${binary_name}"
    if [[ -f "$existing_path" ]]; then
      step "Already installed: $existing_path"
    else
      step "Already installed: not found at $default_dir"
    fi

    # Show download URLs that would be tried
    local locate_urls
    IFS=$'\n' read -r -d '' -a locate_urls < <(get_download_urls "$binary_name" "$VERSION" && printf '\0')
    echo ""
    step "Download order:"
    for entry in "${locate_urls[@]}"; do
      local l="${entry%%|*}" u="${entry#*|}"
      step "  ${l}: ${u}"
    done

    echo ""
    return
  fi

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

  # ── Local binary discovery (bundled/sideload) ──
  local use_local_binary=false
  local local_binary_path=""
  if [[ "$SKIP_LOCAL" != true ]]; then
    if local_binary_path=$(find_local_binary "$binary_name" 2>/dev/null) && [[ -n "$local_binary_path" ]]; then
      step "Found local binary alongside script: $(basename "$local_binary_path")"
      if test_local_binary "$local_binary_path"; then
        ok "Local binary verified (--version OK)"
        use_local_binary=true
      else
        warn "Local binary found but --version check failed — will download instead"
      fi
    fi
  else
    step "Skipping local binary check (--skip-local)"
  fi

  # Install binary: local copy > already installed > download
  if [[ -f "$binary_path" ]] && [[ -z "$VERSION" ]] && [[ "$SKIP_IF_INSTALLED" == true ]] && [[ "$use_local_binary" != true ]]; then
    ok "Binary already installed: $binary_path"
  elif [[ "$use_local_binary" == true ]]; then
    # Copy local binary to install dir
    if [[ "$DRY_RUN" != true ]]; then
      rm -f "$binary_path"
      cp "$local_binary_path" "$binary_path"
      chmod +x "$binary_path"
    fi
    ok "Installed (local): $binary_path"
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
      tried_msg+="  - For GitHub rate limits, set GITHUB_TOKEN environment variable"$'\n'
      tried_msg+="  - If you have a local copy, place it alongside this script and re-run"
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

  # Create symlinks (browser4-cli -> platform binary, b4 if no conflict)
  say ""
  create_symlinks "$binary_name" "$INSTALL_DIR"

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
