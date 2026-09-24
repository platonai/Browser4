# Build from Source

This guide covers prerequisites, platform-specific requirements, and build steps for compiling Browser4 from source.

## Prerequisites

| Tool | Minimum Version | Required For | Notes |
|------|----------------|-------------|-------|
| **Git** | any | Clone, root discovery | |
| **JDK** | 17+ (21+ recommended) | Build & runtime | Eclipse Temurin recommended. JDK 21+ enables best jlink compression (zip-9). |
| **Maven** | 3.9+ | Java build | Included via `mvnw` wrapper — no separate install needed. |
| **PowerShell 7** (`pwsh`) | 7.0+ | Runtime bundle assembly (jlink) | Required on **Linux** and **macOS**. Built-in on Windows (`powershell.exe`). Install: `curl -fsSL https://aka.ms/install-powershell.sh \| bash` |
| **JDK tools** (`jdeps`, `jlink`, `jpackage`) | bundled with JDK 16+ | Runtime bundle assembly | Included in your JDK installation — no separate install needed. |
| **Chrome / Chromium** | latest | Runtime | See auto-detection paths below. Docker images bundle Chromium via `apk add chromium`. |
| **Rust** | stable (edition 2021) | CLI build only | Only needed when building `browser4-cli` from source (not needed for the Java backend). |
| **Node.js + pnpm** | Node 24 / pnpm 10 | CLI distribution | Only needed when packaging the CLI for npm publish. |

### Platform-specific tools for runtime bundle assembly

| Platform | Additional tools |
|----------|-----------------|
| **Linux** | `tar`, `wget` or `curl` |
| **macOS** | `tar` |
| **Windows** | `powershell.exe` (built-in) — Windows PowerShell 5.1+ is sufficient |

## Chrome Setup

### Auto-detection paths

| Platform | Search paths |
|----------|-------------|
| **Windows** | `C:\Program Files\Google\Chrome\Application\chrome.exe`, `C:\Program Files (x86)\Google\Chrome\Application\chrome.exe` |
| **macOS** | `/Applications/Google Chrome.app/Contents/MacOS/Google Chrome`, `/Applications/Chromium.app/Contents/MacOS/Chromium` |
| **Linux** | `/opt/google/chrome/chrome`, `/usr/bin/google-chrome`, `/usr/bin/chromium-browser`, `PATH: google-chrome`, `chromium-browser`, `chromium` |

### Automatic installation

If Chrome is not found, the CLI attempts automatic installation:

- **Windows**: via `winget` or PowerShell download of the standalone installer
- **Debian/Ubuntu**: via `wget`/`curl` + `sudo dpkg -i`
- **RHEL/Fedora**: via `curl` + `sudo dnf install -y`
- **macOS**: prints `brew install --cask google-chrome` instructions

## Build Steps

1. **Clone the repository**
   ```shell
   git clone https://github.com/platonai/Browser4.git
   cd Browser4
   ```

2. **Configure your LLM API key**

   Edit [application.properties](../application.properties) and add your API key, or set environment variables. See [LLM Configuration](../README.md#llm-configuration) for supported providers and variable names.

3. **Build the project**
   ```shell
   ./mvnw -DskipTests
   ```

## Building the CLI

The CLI (`browser4-cli`) is a Rust binary that communicates with the Browser4 backend.
It requires **Rust** (stable, edition 2021) — see the prerequisites table above.

**Running without installing** (recommended for development):

```bash
# From the repo root:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>

# Or from the CLI directory:
cd cli/browser4-cli
cargo run -- <command>
```

The backend server starts automatically on first use (no manual `./mvnw` required).

Dev mode only ever serves the **checked-out** code: an already assembled runtime
bundle under `browser4-apps/browser4-bundle/target/runtime-bundle/` is reused
without rebuilding while it matches the checkout. A bundle built from a
different project version makes the CLI **refuse to start**, printing the
detected reason, the bundled/checked-out versions and build time, and the exact
rebuild command; sources that merely look newer than the bundle jars are
rebuilt before the server starts instead. Rebuild from source with:

```bash
# Rebuild the runtime bundle from source (Windows; on Linux/macOS use: pwsh -File ...):
powershell -ExecutionPolicy Bypass -File browser4-apps/browser4-bundle/build-runtime-bundle.ps1

# Or let the next command rebuild it first (value: 1/true/yes/on):
BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 browser4-cli goto https://example.com
```

Deliberately testing an older backend requires the explicit opt-out
`BROWSER4_CLI_ALLOW_STALE_BUNDLE=1`, which keeps the warning and is reported by
`browser4-cli status` / `browser4-cli doctor`.

**Building the release binary:**

```bash
cd cli/browser4-cli
cargo build --release
# Binary at: target/release/browser4-cli[.exe]
```

**Installing globally:**

```bash
cd cli/browser4-cli
cargo install --path .
# Now available as: browser4-cli <command>
```
