# Browser4 CLI

A command-line interface for controlling a [Browser4](https://github.com/platonai/Browser4)
server. Designed for use by AI agents through SKILLS + CLI.

## Install

### macOS / Linux installer

The repository now includes an installer that:

- checks the required build/runtime dependencies
- installs Java 17+, Google Chrome, and Rust when they are missing
- downloads the latest released `Browser4.jar` to `~/.browser4/lib/Browser4.jar` as a fallback runtime
- downloads the latest tagged Browser4 source and installs `browser4-cli` to `~/.local/bin`

```bash
curl -fsSL https://raw.githubusercontent.com/platonai/Browser4/master/sdks/browser4-cli/install.sh | bash
```

Optional environment overrides:

| Variable | Description |
|---|---|
| `BROWSER4_INSTALL_VERSION` | Install a specific release tag instead of the latest one |
| `BROWSER4_INSTALL_ROOT` | Override the Cargo install root (default: `~/.local`) |
| `BROWSER4_LIB_DIR` | Override where `Browser4.jar` is stored (default: `~/.browser4/lib`) |

### Windows manual install

`install.sh` is only supported on macOS and Linux. On Windows, install the CLI manually:

1. Install Java 17+, Google Chrome, Rust, and the MSVC C++ build tools.
2. Download the latest `Browser4.jar` release asset to `%USERPROFILE%\.browser4\lib\Browser4.jar`.
3. Build and install `browser4-cli.exe` from source into Cargo's bin directory:

```powershell
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.browser4\lib" | Out-Null
Invoke-WebRequest 'https://github.com/platonai/Browser4/releases/latest/download/Browser4.jar' -OutFile "$env:USERPROFILE\.browser4\lib\Browser4.jar"

git clone https://github.com/platonai/Browser4.git
cd Browser4\sdks\browser4-cli
cargo install --path . --locked
```

By default, Cargo installs the executable to `%USERPROFILE%\.cargo\bin`. Ensure that directory is on `PATH`.

## Prerequisites

- For localhost auto-start, prefer running the CLI from a Browser4 source checkout with Java 17+ and Maven available
- Or point the CLI at an already-running Browser4 server (default port **8182**)
- Rust 1.70+ (to build from source manually)

## Build

```bash
cd sdks/browser4-cli
cargo build --release
# Binary is at target/release/browser4-cli
# Or install to Cargo bin directory (%USERPROFILE%\.cargo\bin on Windows, ~/.cargo/bin on Unix):
cargo install --path .
```

Or run directly:

```bash
cargo run -- <command> [args] [options]
```

## Usage

```
browser4-cli <command> [args] [options]
browser4-cli -s=<session> <command> [args] [options]
```

### Global options

| Flag | Description |
|---|---|
| `--help [command]` | Print help (optionally for a specific command) |
| `--version` | Print version |
| `-s=<name>` | Named session label |
| `--server=<url>` | Override Browser4 server URL |

### Commands

The tables below mirror the commands surfaced by the global `browser4-cli help` overview.

#### Core

| Command | Description |
|---|---|
| `open [url]` | Open a new browser session (optionally navigate to URL) |
| `close` | Close the active session |
| `goto <url>` | Navigate to a URL |
| `click <ref> [button]` | Click an element |
| `dblclick <ref> [button]` | Double-click an element |
| `type <text> [ref]` | Type text into the focused element or an optional target element |
| `fill <ref> <text>` | Fill text into an editable element |
| `hover <ref>` | Hover over an element |
| `select <ref> <val>` | Select an option in a dropdown |
| `upload <ref> <file>` | Upload a file |
| `check <ref>` | Check a checkbox or radio button |
| `uncheck <ref>` | Uncheck a checkbox or radio button |
| `drag <startRef> <endRef>` | Drag and drop between two elements |
| `snapshot` | Capture accessibility snapshot |
| `eval <expression> [ref]` | Evaluate JavaScript on the page or a target element |
| `dialog-accept [prompt]` | Accept a dialog |
| `dialog-dismiss` | Dismiss a dialog |
| `resize <w> <h>` | Resize the browser window |
| `delete-data` | Delete session data |

#### Navigation

| Command | Description |
|---|---|
| `go-back` | Go back to the previous page |
| `go-forward` | Go forward to the next page |
| `reload` | Reload the current page |

#### Keyboard

| Command | Description |
|---|---|
| `press <key> [ref]` | Press a key on the focused element or an optional target element |
| `keydown <key>` | Press and hold a key |
| `keyup <key>` | Release a key |

#### Mouse

| Command | Description |
|---|---|
| `mousemove <x> <y>` | Move mouse to coordinates |
| `mousedown [button]` | Press mouse button |
| `mouseup [button]` | Release mouse button |
| `mousewheel <dx> <dy>` | Scroll the mouse wheel |

#### Save as

| Command | Description |
|---|---|
| `screenshot [ref]` | Take a screenshot |
| `pdf` | Save page as PDF |

#### Tabs

| Command | Description |
|---|---|
| `tab-list` | List all tabs |
| `tab-new [url]` | Create a new tab |
| `tab-close [tabId]` | Close a browser tab by tab ID |
| `tab-select <tabId>` | Select a browser tab by tab ID |

#### Browser sessions

| Command | Description |
|---|---|
| `list` | List browser sessions |
| `close-all` | Close all browser sessions |
| `kill-all` | Forcefully kill all browser sessions |

Use `tab-list` first to obtain the tab ID you want to select or close.

### Advanced commands

These commands are intentionally omitted from the global `browser4-cli help` overview.
Query `browser4-cli help <command>` for the exact syntax when you need them.

| Command | Description |
|---|---|
| `batch [command...]` | Execute multiple commands in one invocation |
| `console [min-level]` | List console messages |
| `extract <instruction>` | Extract structured data from the current page |
| `summarize [instruction]` | Summarize page content using AI |
| `agent-run <task>` | Run an autonomous agent task |
| `agent-status <id>` | Check the status of a running agent task |
| `agent-result <id>` | Get the result of a completed agent task |
| `co-create` | Create a collective session with parallel browser contexts |
| `co-submit [url]` | Submit URL(s) or tasks to the active collective session |
| `co-scrape <url>` | Scrape data from a URL using CSS selectors |
| `co-status <id>` | Check the status of a collective task |
| `co-result <id>` | Get the result of a completed collective task |

## Element References

The `snapshot` command returns an accessibility tree where every interactive
node is labeled with a short identifier such as `e15`. Pass this identifier
directly to commands like `click`, `type`, or `press`; the CLI automatically
converts it to the `backend:15` selector format required by the server.

You can also pass plain CSS selectors (e.g. `.my-button`, `#search-input`) or
fully-qualified `backend:<N>` refs directly.

## State Persistence

The active session ID and server URL are kept in `~/.browser4/cli-state.json`
between invocations. Override the directory with the `BROWSER4_CLI_STATE_DIR`
environment variable.

## Runtime Temp Files

`browser4-cli` keeps ephemeral runtime artifacts under the system temp directory:

- Windows: `%TEMP%\.browser4\browser4-cli`
- Linux/macOS: `${TMPDIR:-/tmp}/.browser4/browser4-cli`

This temp subtree contains items such as:

- startup logs for auto-started Browser4 servers
- staged Maven wrapper launchers
- Rust test scratch directories used by `browser4-cli` tests

Persistent CLI state and the fallback `Browser4.jar` remain under `~/.browser4` by default.

### Clean browser4-cli temp artifacts

On Windows PowerShell:

```powershell
.\bin\cleanup-temp.ps1
```

Preview only:

```powershell
.\bin\cleanup-temp.ps1 -WhatIf
```

## Snapshots

After each command that modifies browser state, the CLI automatically:

1. Retrieves the current page URL and title
2. Captures an accessibility snapshot
3. Saves the snapshot to `.browser4-cli/snapshot/page-<timestamp>.yml`
4. Prints the snapshot path in Markdown link format

## Examples

```shell
# Open a new browser window
browser4-cli open

# Navigate to a page
browser4-cli goto https://playwright.dev

# Inspect the page — note the eN labels on interactive nodes
browser4-cli snapshot

# Interact using refs from the snapshot
browser4-cli click e15
browser4-cli type "Hello World" e15
browser4-cli press Enter e15
browser4-cli eval "document.title"
browser4-cli eval "element => element.textContent.trim()" e15
browser4-cli keydown Shift
browser4-cli mousemove 150 300
browser4-cli mousewheel 0 100
browser4-cli keyup Shift

# Take a screenshot and save it to disk
browser4-cli screenshot

# Inspect tab IDs before switching tabs
browser4-cli tab-list
browser4-cli tab-select <tabId-from-tab-list>
browser4-cli tab-close <tabId-from-tab-list>

# Use a custom server URL
browser4-cli open --server http://localhost:9090

# Advanced: execute multiple commands in one process
browser4-cli batch "open https://playwright.dev" "snapshot"

# Advanced: stop on the first batch failure
browser4-cli batch --bail "open https://playwright.dev" "click e1" "screenshot"

# Advanced: pipe batch commands as JSON via stdin
echo '[
  ["open", "https://playwright.dev"],
  ["snapshot"],
  ["click", "e1"],
  ["screenshot", "--filename=result.png"]
]' | browser4-cli batch --json

# Close the session when done
browser4-cli close
```

### Batch `open` session reuse behavior

When `browser4-cli batch` runs against an already active session (for example via persisted CLI state or an explicit `sessionId` carried into `command_batch`), the batch `open` step reuses that session instead of creating a new one.

- If no active session exists, `open` creates a new session as before.
- If an active session already exists, `open` returns `Session already open: <sessionId>` and keeps using that session.
- If the batch `open` step also provides `capabilities`, they are ignored while reusing the existing session.
- If the supplied session no longer exists on the server, the batch fails with `Session not found: <sessionId>`.

## Architecture

The Rust CLI is structured as follows:

| Module | Purpose |
|---|---|
| `main.rs` | Entry point, command dispatch, session management |
| `args.rs` | CLI argument parsing (global flags, positional args, options) |
| `commands.rs` | Command definitions mapping to MCP tool names and parameters |
| `http.rs` | HTTP client for calling `/mcp/call-tool` |
| `state.rs` | Persistent state management (`~/.browser4/cli-state.json`) |
| `daemon.rs` | Local server auto-start (prefer Maven from repo root, fall back to jar) and health checking |
| `managed_processes.rs` | Registry for browser4 server processes |
| `snapshot.rs` | Snapshot and screenshot file helpers |
| `help.rs` | Help text generation |

## Testing

```bash
## Run all tests (unit + end-to-end):
cargo test

## Run only the end-to-end tests and print their output:
cargo test --test e2e -- --nocapture

## Run a specific end-to-end test scenario:
cargo test --test e2e -- --nocapture --scenario=test_e2e_batch_form_submission
```

## License

Apache-2.0
