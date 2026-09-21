# Build from Source

This guide covers prerequisites, platform-specific requirements, and build steps for compiling Browser4 from source.

## Prerequisites

| Tool | Minimum Version | Required For | Notes |
|------|----------------|-------------|-------|
| **Git** | any | Clone, root discovery | |
| **JDK** | 25+ | Build & runtime | Eclipse Temurin recommended. JDK 25 enables the JVM AOT cache (JEP 483/515) for fast startup. |
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

**Development mode and parallel checkouts.** When the CLI runs from inside a
repository checkout it keeps its state per checkout
(`~/.browser4/workspaces/<checkout>-<hash>/`) and allocates its backend port
from **8282** upward, so `Browser4-4.13`, `Browser4-4.14` and git worktrees can
each run and be tested at the same time — the second checkout automatically
moves to 8283 when 8282 is taken. The backend also gets its own app data root
(`-Dapp.data.dir=<workspace>/app-data`), so each workspace has its own Chrome
profiles, H2/WebDB data and agent memory: two headed browsers no longer contend
for one profile directory. Two things stay shared on purpose, linked into that
root (Windows junction / POSIX symlink): the user's configuration
(`~/.browser4/config/conf-enabled`, which holds the LLM API keys) and the
browser prototype (`~/.browser4/browser/chrome/prototype`, the tree every
`SEQUENTIAL`/`TEMPORARY` context is copied from) — so keys and seed state keep
working without duplication. Where links are unavailable the config tree is
copied and re-synced, and a workspace whose prototype cannot be linked simply
keeps its own. Installed builds
keep the production default (8182) and the flat `~/.browser4` state. Point a
command at a specific backend with `--server <url>` /
`browser4-cli config set server <url>`, or disable development mode with
`BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1`.

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
