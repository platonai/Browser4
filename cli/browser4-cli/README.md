# Browser4 CLI

A command-line interface for controlling a [Browser4](https://github.com/platonai/Browser4)
server. Designed for use by AI agents through SKILLS + CLI.

## Install

### Prerequisites

- Java 17+
- Google Chrome
- Rust 1.70+ (with MSVC C++ build tools on Windows)

### macOS / Linux

```bash
mkdir -p ~/.browser4/lib
curl -fsSL -o ~/.browser4/lib/Browser4.jar https://github.com/platonai/Browser4/releases/latest/download/Browser4.jar

git clone https://github.com/platonai/Browser4.git
cd Browser4/cli/browser4-cli
cargo install --path . --locked
```

By default, Cargo installs the executable to `~/.cargo/bin`. Ensure that directory is on `PATH`.

### Windows

```powershell
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.browser4\lib" | Out-Null
Invoke-WebRequest 'https://github.com/platonai/Browser4/releases/latest/download/Browser4.jar' -OutFile "$env:USERPROFILE\.browser4\lib\Browser4.jar"

git clone https://github.com/platonai/Browser4.git
cd Browser4\cli\browser4-cli
cargo install --path . --locked
```

By default, Cargo installs the executable to `%USERPROFILE%\.cargo\bin`. Ensure that directory is on `PATH`.

## Prerequisites

- For localhost auto-start, prefer running the CLI from a Browser4 source checkout with Java 17+ and Maven available
- Or point the CLI at an already-running Browser4 server (default port **8182**)
- Rust 1.70+ (to build from source manually)

## Build

```bash
cd cli/browser4-cli
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

Sessions are persisted independently per name. Omitting `-s` uses the
default session (`~/.browser4/cli-state.json`). With `-s=<name>`, a
separate state file is stored under `~/.browser4/sessions/<name>.json`.
`open` without `-s` reuses the default session if one exists; with
`-s=<name>` it switches to or creates the named session.

### Commands

The tables below mirror the commands surfaced by the global `browser4-cli help` overview.

#### Core

| Command | Description |
|---|---|
| `open [url]` | Open or switch to a browser session (optionally navigate to URL) |
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
| `tab-close [index]` | Close a browser tab by zero-based index |
| `tab-select <index>` | Select a browser tab by zero-based index |

Use `tab-list` first to find the zero-based tab index you want to select or close.

#### Browser sessions

| Command | Description |
|---|---|
| `list` | List browser sessions |
| `close-all` | Close all browser sessions without stopping `Browser4.jar` / the Browser4 backend |
| `kill-all` | Forcefully stop `Browser4.jar` / the Browser4 backend and kill Browser4 browser processes |

Use `close-all` for session cleanup when you want to keep the current Browser4 service running. Use `kill-all` only when you explicitly want to stop the backend and clean up tracked Browser4 processes.


### Advanced commands

These commands are intentionally omitted from the global `browser4-cli help` overview.
Query `browser4-cli help <command>` for the exact syntax when you need them.

| Command | Description |
|---|---|
| `batch [command...]` | Execute multiple commands in one invocation. Only DOM operations are supported (Core, Navigation, Keyboard, Mouse, Export, Tabs categories). Commands like `open`, `close`, `list`, `agent-run`, etc. are not allowed in batch mode. |
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

`browser4-cli` persists CLI state between invocations under `~/.browser4` by
default. Override the root directory with the `BROWSER4_CLI_STATE_DIR`
environment variable.

- Default session state: `~/.browser4/cli-state.json`
- Named session state (`-s=<name>`): `~/.browser4/sessions/<name>.json`

Each state file stores the current Browser4 server URL plus session-scoped
fields such as:

- `sessionId` — active Browser4 session ID
- `baseUrl` — Browser4 backend URL used by the CLI
- `activeSelector` — last selector tracked for keyboard restore flows
- `lastMousePosition` — last pointer coordinates tracked for mouse restore flows

### Session state transitions

The `with_session()` helper in `src/main.rs` is the central session lifecycle
gate for commands that require an active Browser4 session.

| Situation | Persisted state transition | Result |
|---|---|---|
| No persisted session | No state change | `require_session()` fails with `No active session. Run "browser4-cli open" first.` |
| `open` succeeds (no existing session) | `create_session()` writes a fresh state file with new `sessionId`, current `baseUrl`, and clears `activeSelector` / `lastMousePosition` | A new active session becomes the current CLI session |
| `open` when session already exists | No state change — reuses the existing `sessionId` | The existing session is reused; subsequent commands target the same session |
| `open -s=<name>` | Reads/writes the named session state file | Opens or switches to the named session; subsequent `-s=<name>` commands use the same session |
| Command succeeds through `with_session()` | `sessionId` stays unchanged | The command uses the persisted session normally |
| Command fails because the server reports a stale / expired session and `recover_stale = false` | `invalidate_session()` clears `sessionId`, `activeSelector`, and `lastMousePosition`, while keeping `baseUrl` | The command fails with `Saved session expired. Run "browser4-cli open" first.` |
| Command fails because the session is stale and `recover_stale = true` | `invalidate_session()` clears the stale session first, then `create_session()` writes a brand-new `sessionId` and retries the action | The command transparently continues on the recreated session if the retry succeeds |
| `close` | `clear_state()` removes only the current session state file after best-effort remote close | The selected default or named session is fully cleared |
| `close-all` / `kill-all` | `clear_all_state()` removes the default state file and all named session files | All persisted CLI session files are cleared |

Notes:

- Today, normal single-command stale-session recovery is enabled only for flows
  that dispatch with `recover_stale = true` (currently `goto`).
- `open` without `-s` reuses the default session if one already exists; it only creates
  a new session when no default session is present. With `-s=<name>`, `open` switches
  to or creates the named session.
- `list` reads persisted session files and compares them with live backend
  sessions to label entries as `Active` or `Stale`.

## Runtime Temp Files

`browser4-cli` keeps ephemeral runtime artifacts under the system temp directory:

- Windows: `%TEMP%\.browser4\browser4-cli`
- Linux/macOS: `${TMPDIR:-/tmp}/.browser4/browser4-cli`

This temp subtree contains items such as:

- startup logs for auto-started Browser4 servers
- staged Maven wrapper launchers
- Rust test scratch directories used by `browser4-cli` tests

Persistent CLI state and the fallback `Browser4.jar` remain under `~/.browser4` by default.

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

# Inspect tab indices before switching tabs
browser4-cli tab-list
browser4-cli tab-select 1
browser4-cli tab-close 1

# Use a custom server URL
browser4-cli open --server http://localhost:9090

# Advanced: execute multiple commands in one process (batch mode)
# Batch mode only supports DOM operations. You must run `open` separately first.
browser4-cli open
browser4-cli batch "goto https://playwright.dev" "snapshot"

# Advanced: stop on the first batch failure
browser4-cli batch --bail "goto https://playwright.dev" "click e1" "screenshot"

# Advanced: batch mode for form filling (recommended use case)
browser4-cli batch "fill e1 'John Doe'" "fill e2 'john@example.com'" "click e3"

# Advanced: pipe batch commands as JSON via stdin
echo '[
  ["goto", "https://example.com/form-filling"],
  ["click", "#reset-btn"],
  ["fill", "#first-name", "Bob"],
  ["fill", "#last-name", "Smith"],
  ["fill", "#email", "bob@example.com"],
  ["select", "#country", "uk"],
  ["check", "#agree-terms"],
  ["click", "#submit-btn"]
]' | browser4-cli batch --json

# Close the session when done
browser4-cli close

# Close all sessions but keep the current Browser4 backend running
browser4-cli close-all

# Explicitly stop the Browser4 backend and clean up tracked Browser4 processes
browser4-cli kill-all
```

## Architecture

The Rust CLI is structured as follows:

| Module | Purpose |
|---|---|
| `main.rs` | Entry point, command dispatch, session management |
| `args.rs` | CLI argument parsing (global flags, positional args, options) |
| `commands.rs` | Command definitions mapping to MCP tool names and parameters |
| `http.rs` | HTTP client for calling `/mcp/call-tool` |
| `state.rs` | Persistent state management for the default state file and named session files under `~/.browser4/` |
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
