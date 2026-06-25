# browser4-cli Build & Dist Scripts

Build, installation, and distribution scripts for the `browser4-cli` npm package.

## Scripts

### `install-browser4-cli.ps1` / `install-browser4-cli.sh`

One-liner install scripts for the browser4-cli native binary. Detects the OS, CPU architecture, and libc variant (glibc / musl), downloads the matching binary from GitHub Releases or Alibaba Cloud OSS, and installs it to `~/.local/bin` (configurable). These are the scripts referenced by the install commands in the top-level README.

```bash
# Linux / macOS
curl -fsSL https://.../install-browser4-cli.sh | bash

# Windows
irm https://.../install-browser4-cli.ps1 | iex
```

Options: `--version TAG`, `--install-dir DIR`, `--source github|oss`, `--no-path`

### `build-all-platforms.ps1` / `build-all-platforms.sh`

Build the browser4-cli Rust binary for all target platforms (Windows, Linux glibc, Linux musl, macOS) using Docker cross-compilation. Outputs platform-specific binaries to `cli/bin/`.

Options: `--skip-docker-build`, `--dry-run`

### `postinstall.js`

npm postinstall script for the `browser4-cli` package. Downloads the platform-specific native binary when the package is installed via npm, and patches npm's bin shims to call the native binary directly (Windows: overwrites `.cmd`/`.ps1` shims; macOS/Linux: replaces the symlink).

### `copy-native.js`

Copies a locally compiled Rust binary into the npm package's `bin/` directory with platform-specific naming (e.g., `browser4-cli-x86_64-pc-windows-msvc.exe`). Used during local development to test the npm package with a freshly built binary.

### `npm-publish-check.js`

Shared utility that reads the local `package.json` version, queries the npm registry for the published version of `browser4-cli`, and determines whether a publish is needed. Exports `getPublishDecision()` and `logPublishDecision()` for use by other scripts.

### `check-npm-publish-needed.js`

Thin CLI wrapper around `npm-publish-check.js`. Reports whether the local version differs from the npm registry and optionally writes GitHub Actions output variables.

Options: `--json`, `--json-only`, `--shell`, `--github-output`

### `publish-if-needed.js`

Publishes `browser4-cli` to the npm registry — but only when the local `package.json` version differs from the version already published on npm. Safe to run in CI on every commit; it becomes a no-op when there are no changes.

Options: `--dry-run`
Environment: `BROWSER4_CLI_NPM_REMOTE_VERSION=<version>` (override for testing)
