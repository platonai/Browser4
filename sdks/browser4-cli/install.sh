#!/usr/bin/env bash

set -euo pipefail

REPO_OWNER="platonai"
REPO_NAME="Browser4"
LATEST_RELEASE_API="https://api.github.com/repos/${REPO_OWNER}/${REPO_NAME}/releases/latest"
INSTALL_ROOT="${BROWSER4_INSTALL_ROOT:-${HOME}/.local}"
LIB_DIR="${BROWSER4_LIB_DIR:-${HOME}/.browser4/lib}"
BIN_DIR="${INSTALL_ROOT}/bin"

TMP_DIR=""
OS=""
ARCH=""
PACKAGE_MANAGER=""
APT_UPDATED=0

cleanup() {
  if [[ -n "${TMP_DIR}" && -d "${TMP_DIR}" ]]; then
    rm -rf "${TMP_DIR}"
  fi
}
trap cleanup EXIT

log() {
  printf '[browser4-cli installer] %s\n' "$*"
}

warn() {
  printf '[browser4-cli installer] WARNING: %s\n' "$*" >&2
}

die() {
  printf '[browser4-cli installer] ERROR: %s\n' "$*" >&2
  exit 1
}

have_cmd() {
  command -v "$1" >/dev/null 2>&1
}

run_as_root() {
  if [[ "${EUID}" -eq 0 ]]; then
    "$@"
  elif have_cmd sudo; then
    sudo "$@"
  else
    die "This step requires elevated privileges. Please install sudo or run the script as root."
  fi
}

append_line_if_missing() {
  local file="$1"
  local line="$2"

  mkdir -p "$(dirname "${file}")"
  touch "${file}"

  if ! grep -Fqx "${line}" "${file}"; then
    printf '\n%s\n' "${line}" >> "${file}"
  fi
}

ensure_path_line() {
  local dir="$1"
  local profile=""
  local line="export PATH=\"${dir}:\$PATH\""

  if [[ ":${PATH}:" != *":${dir}:"* ]]; then
    export PATH="${dir}:${PATH}"
  fi

  for candidate in "${HOME}/.bashrc" "${HOME}/.zshrc" "${HOME}/.profile"; do
    if [[ -f "${candidate}" ]]; then
      profile="${candidate}"
      break
    fi
  done

  if [[ -z "${profile}" ]]; then
    profile="${HOME}/.profile"
  fi

  append_line_if_missing "${profile}" "${line}"
}

ensure_java_env_lines() {
  local java_home="$1"
  local profile=""
  local path_line="export PATH=\"${java_home}/bin:\$PATH\""
  local home_line="export JAVA_HOME=\"${java_home}\""

  export JAVA_HOME="${java_home}"
  if [[ ":${PATH}:" != *":${java_home}/bin:"* ]]; then
    export PATH="${java_home}/bin:${PATH}"
  fi

  for candidate in "${HOME}/.bashrc" "${HOME}/.zshrc" "${HOME}/.profile"; do
    if [[ -f "${candidate}" ]]; then
      profile="${candidate}"
      break
    fi
  done

  if [[ -z "${profile}" ]]; then
    profile="${HOME}/.profile"
  fi

  append_line_if_missing "${profile}" "${home_line}"
  append_line_if_missing "${profile}" "${path_line}"
}

detect_platform() {
  case "$(uname -s)" in
    Linux)
      OS="linux"
      ;;
    Darwin)
      OS="macos"
      ;;
    MINGW*|MSYS*|CYGWIN*)
      die "This installer is for macOS and Linux shells. On Windows, download Browser4.jar from the latest release and install browser4-cli from source with cargo install --path . --locked."
      ;;
    *)
      die "Unsupported operating system: $(uname -s)"
      ;;
  esac

  case "$(uname -m)" in
    x86_64|amd64)
      ARCH="x86_64"
      ;;
    arm64|aarch64)
      ARCH="arm64"
      ;;
    *)
      ARCH="$(uname -m)"
      ;;
  esac
}

detect_package_manager() {
  if [[ "${OS}" == "macos" ]]; then
    PACKAGE_MANAGER="brew"
    have_cmd brew || die "Homebrew is required on macOS. Install it first from https://brew.sh/"
    return
  fi

  if have_cmd apt-get; then
    PACKAGE_MANAGER="apt-get"
  elif have_cmd dnf; then
    PACKAGE_MANAGER="dnf"
  elif have_cmd yum; then
    PACKAGE_MANAGER="yum"
  elif have_cmd zypper; then
    PACKAGE_MANAGER="zypper"
  else
    die "Unsupported Linux package manager. Supported managers: apt-get, dnf, yum, zypper."
  fi
}

install_packages() {
  case "${PACKAGE_MANAGER}" in
    apt-get)
      if [[ "${APT_UPDATED}" -eq 0 ]]; then
        run_as_root apt-get update
        APT_UPDATED=1
      fi
      run_as_root apt-get install -y "$@"
      ;;
    dnf)
      run_as_root dnf install -y "$@"
      ;;
    yum)
      run_as_root yum install -y "$@"
      ;;
    zypper)
      run_as_root zypper --non-interactive install "$@"
      ;;
    brew)
      brew install "$@"
      ;;
    *)
      die "Unsupported package manager: ${PACKAGE_MANAGER}"
      ;;
  esac
}

java_major_version() {
  local version
  version="$(java -version 2>&1 | sed -n '1s/.*version "\(.*\)".*/\1/p')"
  if [[ -z "${version}" ]]; then
    printf '0\n'
    return
  fi

  if [[ "${version}" == 1.* ]]; then
    printf '%s\n' "${version#1.}" | cut -d. -f1
  else
    printf '%s\n' "${version}" | cut -d. -f1
  fi
}

ensure_java() {
  if have_cmd java; then
    local major
    major="$(java_major_version)"
    if [[ "${major}" =~ ^[0-9]+$ ]] && (( major >= 17 )); then
      log "Java ${major} is already installed."
      return
    fi
  fi

  log "Installing Java 17+..."
  case "${PACKAGE_MANAGER}" in
    apt-get)
      install_packages ca-certificates curl tar gzip build-essential pkg-config libssl-dev openjdk-17-jdk
      ;;
    dnf)
      install_packages ca-certificates curl tar gzip gcc gcc-c++ make pkgconf-pkg-config openssl-devel java-17-openjdk-devel
      ;;
    yum)
      install_packages ca-certificates curl tar gzip gcc gcc-c++ make pkgconfig openssl-devel java-17-openjdk-devel
      ;;
    zypper)
      install_packages ca-certificates curl tar gzip gcc gcc-c++ make pkg-config libopenssl-devel java-17-openjdk-devel
      ;;
    brew)
      brew install openjdk@17
      local brew_prefix
      brew_prefix="$(brew --prefix openjdk@17)"
      ensure_java_env_lines "${brew_prefix}/libexec/openjdk.jdk/Contents/Home"
      ;;
  esac
}

chrome_exists() {
  if have_cmd google-chrome || have_cmd google-chrome-stable; then
    return 0
  fi

  if [[ "${OS}" == "macos" && -x "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome" ]]; then
    return 0
  fi

  return 1
}

install_chrome_linux_apt() {
  local deb_path="${TMP_DIR}/google-chrome.deb"

  [[ "${ARCH}" == "x86_64" ]] || die "Google Chrome packages are only published for linux/x86_64."

  download_file "https://dl.google.com/linux/direct/google-chrome-stable_current_amd64.deb" "${deb_path}"
  if [[ "${APT_UPDATED}" -eq 0 ]]; then
    run_as_root apt-get update
    APT_UPDATED=1
  fi
  run_as_root apt-get install -y "${deb_path}"
}

install_chrome_linux_rpm() {
  local rpm_path="${TMP_DIR}/google-chrome.rpm"

  [[ "${ARCH}" == "x86_64" ]] || die "Google Chrome packages are only published for linux/x86_64."

  download_file "https://dl.google.com/linux/direct/google-chrome-stable_current_x86_64.rpm" "${rpm_path}"
  case "${PACKAGE_MANAGER}" in
    dnf)
      run_as_root dnf install -y "${rpm_path}"
      ;;
    yum)
      run_as_root yum install -y "${rpm_path}"
      ;;
    zypper)
      run_as_root zypper --non-interactive install "${rpm_path}"
      ;;
    *)
      die "RPM Chrome installation is not supported with package manager ${PACKAGE_MANAGER}."
      ;;
  esac
}

ensure_chrome() {
  if chrome_exists; then
    log "Google Chrome is already installed."
    return
  fi

  log "Installing Google Chrome..."
  case "${PACKAGE_MANAGER}" in
    apt-get)
      install_chrome_linux_apt
      ;;
    dnf|yum|zypper)
      install_chrome_linux_rpm
      ;;
    brew)
      brew install --cask google-chrome
      ;;
    *)
      die "Chrome installation is not supported with package manager ${PACKAGE_MANAGER}."
      ;;
  esac
}

ensure_build_toolchain() {
  case "${PACKAGE_MANAGER}" in
    apt-get)
      install_packages ca-certificates curl tar gzip build-essential pkg-config libssl-dev
      ;;
    dnf)
      install_packages ca-certificates curl tar gzip gcc gcc-c++ make pkgconf-pkg-config openssl-devel
      ;;
    yum)
      install_packages ca-certificates curl tar gzip gcc gcc-c++ make pkgconfig openssl-devel
      ;;
    zypper)
      install_packages ca-certificates curl tar gzip gcc gcc-c++ make pkg-config libopenssl-devel
      ;;
    brew)
      if ! xcode-select -p >/dev/null 2>&1; then
        die "Xcode Command Line Tools are required on macOS. Run 'xcode-select --install' and re-run this installer."
      fi
      ;;
  esac
}

ensure_rust() {
  if have_cmd cargo && have_cmd rustc; then
    log "Rust toolchain already installed."
    return
  fi

  log "Installing Rust toolchain..."
  curl --proto '=https' --tlsv1.2 -fsSL https://sh.rustup.rs -o "${TMP_DIR}/rustup-init.sh"
  sh "${TMP_DIR}/rustup-init.sh" -y --profile minimal

  if [[ -f "${HOME}/.cargo/env" ]]; then
    # shellcheck disable=SC1090
    . "${HOME}/.cargo/env"
  fi

  have_cmd cargo || die "Rust installation completed, but cargo was not found on PATH."
}

resolve_latest_release_tag() {
  local requested_tag=""

  if [[ -n "${BROWSER4_INSTALL_VERSION:-}" ]]; then
    requested_tag="${BROWSER4_INSTALL_VERSION}"
  else
    local release_json
    release_json="$(curl -fsSL -H 'Accept: application/vnd.github+json' "${LATEST_RELEASE_API}")"
    requested_tag="$(printf '%s' "${release_json}" | sed -n 's/.*"tag_name":"\([^"]*\)".*/\1/p' | head -n 1)"
  fi

  [[ -n "${requested_tag}" ]] || die "Unable to determine the latest Browser4 release tag."
  if [[ "${requested_tag}" == v* ]]; then
    printf '%s\n' "${requested_tag}"
  else
    printf 'v%s\n' "${requested_tag}"
  fi
}

download_file() {
  local url="$1"
  local target="$2"

  log "Downloading $(basename "${target}")..."
  curl -fL --retry 3 --retry-delay 2 -o "${target}" "${url}"
}

install_browser4_jar() {
  local tag="$1"
  local jar_url="https://github.com/${REPO_OWNER}/${REPO_NAME}/releases/download/${tag}/Browser4.jar"
  local jar_target="${LIB_DIR}/Browser4.jar"

  mkdir -p "${LIB_DIR}"
  download_file "${jar_url}" "${jar_target}"

  [[ -s "${jar_target}" ]] || die "Downloaded Browser4.jar is empty."
}

install_browser4_cli_from_source() {
  local tag="$1"
  local archive_url="https://github.com/${REPO_OWNER}/${REPO_NAME}/archive/refs/tags/${tag}.tar.gz"
  local archive_path="${TMP_DIR}/Browser4-${tag}.tar.gz"
  local source_root=""

  download_file "${archive_url}" "${archive_path}"
  tar -xzf "${archive_path}" -C "${TMP_DIR}"

  source_root="$(find "${TMP_DIR}" -mindepth 1 -maxdepth 1 -type d -name 'Browser4*' | head -n 1)"
  [[ -n "${source_root}" ]] || die "Unable to locate the extracted Browser4 source tree."
  [[ -d "${source_root}/sdks/browser4-cli" ]] || die "Extracted release does not contain sdks/browser4-cli."

  mkdir -p "${INSTALL_ROOT}"
  (
    cd "${source_root}/sdks/browser4-cli"
    cargo install --path . --root "${INSTALL_ROOT}" --locked --force
  )
}

print_summary() {
  local tag="$1"

  ensure_path_line "${BIN_DIR}"

  log "Installation complete."
  log "Release: ${tag}"
  log "browser4-cli: ${BIN_DIR}/browser4-cli"
  log "Browser4.jar: ${LIB_DIR}/Browser4.jar"
  log "Architecture: ${OS}/${ARCH}"

  if [[ ":${PATH}:" != *":${BIN_DIR}:"* ]]; then
    warn "${BIN_DIR} is not on your PATH in the current shell yet. Open a new shell or run: export PATH=\"${BIN_DIR}:\$PATH\""
  fi
}

main() {
  detect_platform
  detect_package_manager

  have_cmd curl || {
    log "Installing curl..."
    install_packages curl
  }
  have_cmd tar || {
    log "Installing tar..."
    install_packages tar
  }

  TMP_DIR="$(mktemp -d)"

  ensure_build_toolchain
  ensure_java
  ensure_chrome
  ensure_rust

  local tag
  tag="$(resolve_latest_release_tag)"

  log "Installing Browser4 ${tag}..."
  install_browser4_jar "${tag}"
  install_browser4_cli_from_source "${tag}"
  print_summary "${tag}"
}

main "$@"
