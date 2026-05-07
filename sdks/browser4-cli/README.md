# Browser4 CLI

A command-line interface for controlling a [Browser4](https://github.com/platonai/Browser4) server. Designed for use by AI agents through SKILLS + CLI.

## Installation

### Global Installation (recommended)

Installs the native Rust binary:

```bash
npm install -g browser4-cli
browser4-cli install  # Download Chrome from Chrome for Testing (first time only)
```

### Project Installation (local dependency)

For projects that want to pin the version in `package.json`:

```bash
npm install browser4-cli
browser4-cli install
```

Then use via `package.json` scripts or by invoking `browser4-cli` directly.

### Homebrew (macOS)

```bash
brew install browser4-cli
browser4-cli install  # Download Chrome from Chrome for Testing (first time only)
```

### Cargo (Rust)

```bash
cargo install browser4-cli
browser4-cli install  # Download Chrome from Chrome for Testing (first time only)
```

### From Source

```bash
git clone https://github.com/platonai/Browser4
cd browser4-cli
pnpm install
pnpm build
pnpm build:native   # Requires Rust (https://rustup.rs)
pnpm link --global  # Makes browser4-cli available globally
browser4-cli install
```

### Linux Dependencies

On Linux, install system dependencies:

```bash
browser4-cli install --with-deps
```

### Updating

Upgrade to the latest version:

```bash
browser4-cli upgrade
```

Detects your installation method (npm, Homebrew, or Cargo) and runs the appropriate update command automatically.

### Requirements

- **Chrome** - Run `browser4-cli install` to download Chrome from [Chrome for Testing](https://developer.chrome.com/blog/chrome-for-testing/) (Google's official automation channel). Existing Chrome, Brave, Playwright, and Puppeteer installations are detected automatically. No Playwright or Node.js required for the daemon.
- **Rust** - Only needed when building from source (see From Source above).

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
browser4-cli type e15 "Hello World"
browser4-cli press e15 Enter
browser4-cli eval "document.title"
browser4-cli eval "element => element.textContent.trim()" e15
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
