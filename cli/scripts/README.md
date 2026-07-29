# CLI Scripts

Build, install, test, and publish utilities for the browser4-cli native binary and npm package.

## Build

| Script | Platform | Purpose |
|--------|----------|---------|
| `build-all-platforms.sh` | Linux/macOS | Docker-based cross-compilation for all targets |
| `build-all-platforms.ps1` | Windows | PowerShell equivalent — same targets via Docker |
| `copy-native.js` | All (Node) | Copies the compiled Rust binary to `bin/` with platform-specific naming |

Both build-all-platforms scripts produce binaries for:
- `x86_64-unknown-linux-gnu` (Linux x64, glibc)
- `aarch64-unknown-linux-gnu` (Linux arm64, glibc)
- `x86_64-unknown-linux-musl` (Linux x64, musl)
- `aarch64-unknown-linux-musl` (Linux arm64, musl)
- `x86_64-pc-windows-gnu` (Windows x64)
- `x86_64-apple-darwin` (macOS x64)
- `aarch64-apple-darwin` (macOS arm64)

Options: `--skip-docker-build`, `--dry-run`.

`copy-native.js` expects a pre-built binary at `browser4-cli/target/release/browser4-cli` and copies it to `bin/browser4-cli-<platform>-<arch>`.

## Install

| Script | Platform | Purpose |
|--------|----------|---------|
| `install-browser4-cli.sh` | Linux/macOS | Download and install the native binary |
| `install-browser4-cli.ps1` | Windows | PowerShell equivalent |

Both scripts auto-detect OS, CPU architecture, and libc variant (glibc vs musl on Linux). They download the matching binary from GitHub Releases or Alibaba Cloud OSS (auto-selected by locale: OSS first in China mainland). The binary is installed to a user-local directory and optionally added to PATH.

### Common options

| Option | Description |
|--------|-------------|
| `--version, -v TAG` | Release tag (e.g. `v4.12.0`). Default: latest. |
| `--install-dir, -d DIR` | Install directory (default: `~/.local/bin` on Unix, `%LOCALAPPDATA%\Programs\browser4-cli` on Windows). |
| `--source SRC` | Force download source: `github` or `oss`. Default: auto (locale-aware). |
| `--no-path` / `-AddToPath:$false` | Skip adding the install directory to PATH. |
| `--silent, -s` / `-Silent` | Suppress non-error output. |
| `--dry-run` / `-DryRun` | Print what would be done without doing it. |
| `--skip-if-installed` / `-SkipIfInstalled` | Skip download if binary already exists. |
| `--skip-local` / `-SkipLocal` | Skip local bundled binary check; always download. |
| `--force` / `-Force` (pwsh) | Force reinstallation even if the binary already exists at the target path. |
| `--locate` / `-Locate` | Print detection results (OS, arch, locale) and exit (no install). |

### One-liner install

```shell
# Linux / macOS
curl -fsSL https://raw.githubusercontent.com/platonai/Browser4/main/cli/scripts/install-browser4-cli.sh | bash

# Windows (PowerShell)
iwr -useb https://raw.githubusercontent.com/platonai/Browser4/main/cli/scripts/install-browser4-cli.ps1 | iex
```

## Test

| Script | Platform | Purpose |
|--------|----------|---------|
| `smoke-test-runtime-bundle.sh` | Linux/macOS | End-to-end CLI smoke test against a runtime bundle |

Runs a full CLI smoke test suitable for CI and local development:

1. Extracts a platform runtime bundle (`.tar.gz` or `.zip`)
2. Sets up a local runtime directory (no network download)
3. Starts a Python HTTP server serving a minimal interactive page
4. Executes the full command cycle: `open` → `goto` → `snapshot` → `type` → `click` → `get` → `eval` → `screenshot` → `wait` → `close` → `kill-all`
5. Reports pass/fail for each step

```shell
./smoke-test-runtime-bundle.sh <cli-binary> <bundle-archive> [test-port] [timeout-secs]
```

### Install script tests

| File | Purpose |
|------|---------|
| `tests/install-browser4-cli.tests.sh` | Unit tests for the Unix install script |
| `tests/install-browser4-cli.tests.ps1` | Unit tests for the Windows install script (Pester) |

## Publish (npm)

| Script | Purpose |
|--------|---------|
| `npm-publish-check.js` | Shared library: reads package metadata and compares local vs npm registry version |
| `check-npm-publish-needed.js` | CLI wrapper: checks whether a publish is needed and prints the decision |
| `publish-if-needed.js` | Publishes to npm only when the local version differs from the registry. Uses `--tag next` for prerelease versions (containing `-`). |
| `postinstall.js` | npm `postinstall` hook: downloads the platform native binary after `npm install` |

### Version check

```shell
node scripts/check-npm-publish-needed.js        # human-readable output
node scripts/check-npm-publish-needed.js --json  # JSON output
node scripts/check-npm-publish-needed.js --shell # shell-var output
node scripts/check-npm-publish-needed.js --github-output  # append to $GITHUB_OUTPUT
```

### Conditional publish

```shell
node scripts/publish-if-needed.js             # publish if versions differ
node scripts/publish-if-needed.js --dry-run   # print what would happen
```

Optional env: `BROWSER4_CLI_NPM_REMOTE_VERSION` to override the remote version for testing.

### Postinstall

`postinstall.js` runs automatically after `npm install browser4-cli`. It detects the platform and downloads the matching native binary to `bin/`. On global installs, it also patches npm's bin shims to invoke the native binary directly.

## Documentation

| Script | Platform | Purpose |
|--------|----------|---------|
| `enumerate-help.ps1` | Windows (pwsh) | Discovers all CLI commands/subcommands and generates categorized help output |

Enumerates every browser4-cli command (including hidden ones), captures `--help` output for each, and saves organized documentation to `cli/help-output/`:

- Top-level help overview
- One file per command (with numbered prefixes for ordering)
- Prefix group overviews (`swarm`, `agent`, `htmlsnapshot`, `crawl`, `snapshot`)
- `INDEX.md` for easy navigation

```powershell
.\enumerate-help.ps1
.\enumerate-help.ps1 -Binary "C:\tools\browser4-cli.exe" -OutDir ".\docs\cli-help"
```

---

This README is intentionally concise; extend as the script collection grows.
