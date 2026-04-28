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

#### Core

| Command | Description |
|---|---|
| `open [url]` | Open a new browser session (optionally navigate to URL) |
| `close` | Close the active session |
| `batch [command...]` | Execute multiple commands in one invocation |
| `goto <url>` | Navigate to a URL |
| `click <ref> [button]` | Click an element |
| `dblclick <ref> [button]` | Double-click an element |
| `type <ref> <text>` | Type text into an element |
| `fill <ref> <text>` | Fill text into an editable element |
| `hover <ref>` | Hover over an element |
| `select <ref> <val>` | Select an option in a dropdown |
| `upload <ref> <file>` | Upload a file |
| `check <ref>` | Check a checkbox or radio button |
| `uncheck <ref>` | Uncheck a checkbox or radio button |
| `drag <startRef> <endRef>` | Drag and drop between two elements |
| `snapshot` | Capture accessibility snapshot |
| `eval <func> [ref]` | Evaluate JavaScript expression |
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
| `press <ref> <key>` | Press a key on the keyboard |
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
| `tab-close [index]` | Close a browser tab |
| `tab-select <index>` | Select a browser tab |

#### Browser sessions

| Command | Description |
|---|---|
| `list` | List browser sessions |
| `close-all` | Close all browser sessions |
| `kill-all` | Forcefully kill all browser sessions |

#### DevTools

| Command | Description |
|---|---|
| `console [min-level]` | List console messages |

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
browser4-cli type e15 "Hello World"
browser4-cli press e15 Enter
browser4-cli keydown Shift
browser4-cli mousemove 150 300
browser4-cli mousewheel 0 100
browser4-cli keyup Shift

# Take a screenshot and save it to disk
browser4-cli screenshot

# Use a custom server URL
browser4-cli open --server http://localhost:9090

# Execute multiple commands in one process
browser4-cli batch "open https://playwright.dev" "snapshot"

# Stop on the first batch failure
browser4-cli batch --bail "open https://playwright.dev" "click e1" "screenshot"

# Pipe batch commands as JSON via stdin
echo '[
  ["open", "https://playwright.dev"],
  ["snapshot"],
  ["click", "e1"],
  ["screenshot", "--filename=result.png"]
]' | browser4-cli batch --json

# Close the session when done
browser4-cli close
```

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
