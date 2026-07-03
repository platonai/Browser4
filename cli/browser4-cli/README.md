# Browser4 CLI (development)

Rust CLI binary for controlling a [Browser4](https://github.com/platonai/Browser4) server.
See [`cli/README.md`](../README.md) for the user-facing documentation.

## Commands

### Browser sessions

| Command | Description |
|---|---|
| `open [url]` | Open a browser session or refresh the saved one if it is no longer active |
| `close` | Close the browser |
| `list` | List browser sessions with their status and next-open behavior |
| `close-all` | Close all browser sessions without stopping the Browser4 backend |
| `kill-all` | Forcefully stop the Browser4 backend and kill all browser processes |
| `stop` | Gracefully stop the Browser4 server |
| `status` | Show Browser4 server status (version, port, health) |

### Navigation

| Command | Description |
|---|---|
| `goto <url>` | Navigate to a URL, auto-opening or refreshing the session when needed |
| `go-back` | Go back to the previous page |
| `go-forward` | Go forward to the next page |
| `reload` | Reload the current page |

### Keyboard

| Command | Description |
|---|---|
| `press <key> [ref]` | Press a key on the focused element or an optional target ref |
| `type <text> [ref]` | Type text into the focused element or an optional target ref |
| `keydown <key>` | Press a key down on the keyboard |
| `keyup <key>` | Press a key up on the keyboard |
| `fill <ref> <text>` | Fill text into an editable element |

### Mouse

| Command | Description |
|---|---|
| `click <ref> [button]` | Perform click on a web page |
| `dblclick <ref> [button]` | Perform double click on a web page |
| `hover <ref>` | Hover over element on page |
| `drag <startRef> <endRef>` | Perform drag and drop between two elements |
| `mousemove <x> <y>` | Move mouse to a given position |
| `mousedown [button]` | Press mouse down |
| `mouseup [button]` | Press mouse up |
| `mousewheel <dx> <dy>` | Scroll mouse wheel |
| `scroll <direction> <pixels>` | Scroll the page in a given direction (up, down, left, right) |

### Core

| Command | Description |
|---|---|
| `snapshot` | Capture page snapshot to obtain element refs. Supports `--boxes`, `-i`/`--interactive`, `-u`/`--urls`, `-c`/`--compact`, `-d`/`--depth <n>`, `-s`/`--selector <sel>`, `--raw` |
| `get <mode> <selector> [name]` | Extract data from a page element (text, html, box, styles, property, attr) |
| `eval [expression] [ref]` | Evaluate JavaScript expression on page or element |
| `wait [target]` | Wait for a condition: element, time, text, URL pattern, page load, or JS expression |
| `select <ref> <val>` | Select an option in a dropdown |
| `check <ref>` | Check a checkbox or radio button |
| `uncheck <ref>` | Uncheck a checkbox or radio button |
| `dialog-accept [prompt]` | Accept a dialog |
| `dialog-dismiss` | Dismiss a dialog |
| `resize <w> <h>` | Resize the browser window |
| `delete-data` | Delete session data |
| `batch [command...]` | Execute multiple commands in one invocation |

### Save as

| Command | Description |
|---|---|
| `screenshot [ref]` | Screenshot of the current page or element |
| `pdf` | Save page as PDF |

### Tabs

| Command | Description |
|---|---|
| `tab-list` | List all tabs |
| `tab-new [url]` | Create a new tab |
| `tab-close [index]` | Close a browser tab |
| `tab-select <index>` | Select a browser tab |

### Storage

| Command | Description |
|---|---|
| `state-save [filename]` | Save cookies and localStorage to a JSON file |
| `state-load <filename>` | Load cookies and localStorage from a JSON file |
| `cookie-list` | List browser cookies |
| `cookie-get <name>` | Get a cookie by name |
| `cookie-set <name> <value>` | Set a browser cookie |
| `cookie-delete <name>` | Delete a browser cookie by name |
| `cookie-clear` | Clear all browser cookies |
| `localstorage-list` | List localStorage entries |
| `localstorage-get <key>` | Get a localStorage value by key |
| `localstorage-set <key> <value>` | Set a localStorage value |
| `localstorage-delete <key>` | Delete a localStorage entry |
| `localstorage-clear` | Clear localStorage |
| `sessionstorage-list` | List sessionStorage entries |
| `sessionstorage-get <key>` | Get a sessionStorage value by key |
| `sessionstorage-set <key> <value>` | Set a sessionStorage value |
| `sessionstorage-delete <key>` | Delete a sessionStorage entry |
| `sessionstorage-clear` | Clear sessionStorage |

### Agent

| Command | Description |
|---|---|
| `extract <instruction>` | Extract structured data from the current page |
| `agent run <task>` | Run an autonomous agent task (async, returns task ID) |
| `agent status <id>` | Check the status of a running agent task |
| `agent result <id>` | Get the result of a completed agent task |

### Swarm

| Command | Description |
|---|---|
| `swarm create` | Create a swarm scrape session with parallel browser contexts |
| `swarm submit [url]` | Submit URL(s) or X-SQL payloads as scrape jobs |
| `swarm query <url>` | Submit an X-SQL query to extract structured data from a loaded webpage |
| `swarm status <id>` | Check the status of a scrape job |
| `swarm result <id>` | Get the result of a completed scrape job |

### Crawl

| Command | Description |
|---|---|
| `crawl [url]` | Crawl from a URL or seed file, with optional X-SQL extraction |
| `crawl list` | List all tracked crawl tasks and their status |

### Snapshot

| Command | Description |
|---|---|
| `htmlsnapshot` | Capture a static DOM snapshot and return metadata |
| `htmlsnapshot get <field> [selector] [name]` | Extract elements from the stored DOM snapshot (text, html, attr) |
| `htmlsnapshot query [url]` | Run X-SQL against the stored DOM snapshot |
| `htmlsnapshot export` | Export snapshot HTML to a local file |
| `htmlsnapshot summary` | Generate a compressed Web Page Summary Index (WPSI) from the stored DOM snapshot |
| `htmlsnapshot grep [OPTIONS] <pattern>` | Search snapshot HTML with regex patterns and grep-style output |
| `generate-locator <ref>` | Generate a unique CSS selector path for an element |

### Install / Admin

| Command | Description |
|---|---|
| `install` | Install the self-contained Browser4 runtime bundle |
| `upgrade` | Upgrade Browser4 to the latest version (or a specified release tag) |
| `uninstall` | Remove all globally installed browser4-cli and its runtime data |

### Global options

| Option | Description |
|---|---|
| `--help [command]` | Print help (all commands, or detailed help for a specific command) |
| `--version` | Print version |
| `--json` | Emit machine-parseable JSON to stdout |
| `-q, --quiet` | Suppress normal output, only show errors |
| `-s <name>` | Named session label |
| `--server <url>` | Override Browser4 server URL |

## Build

```bash
cargo build --release
# Binary: target/release/browser4-cli
cargo install --path .
```

## Architecture

| Module | Purpose |
|---|---|
| `src/main.rs` | Entry point, command dispatch, session lifecycle |
| `src/commands.rs` | Command definitions (name, args, options, MCP tool mapping) |
| `src/args.rs` | CLI argument parsing |
| `src/http.rs` | HTTP client for `/mcp/call-tool` |
| `src/state.rs` | Persistent CLI state (`~/.browser4/cli-state.json`) and runtime data directory resolution |
| `src/daemon.rs` | Local Browser4 server auto-start (Maven / jar / download) |
| `src/managed_processes.rs` | Server process registry and force-cleanup |
| `src/snapshot.rs` | Snapshot & screenshot file helpers |
| `src/help.rs` | Help text generation |

## Testing

### Filtering by target

`browser4-cli` has 3 test targets, defined in `Cargo.toml`:

| Command | Target |
|------|-------------|
| `cargo test` | All 3 targets |
| `cargo test --lib` | Only `#[cfg(test)] mod tests` in `src/lib.rs` |
| `cargo test --bin browser4-cli` | Only `#[cfg(test)]` blocks in `src/main.rs` and other `src/*.rs` files |
| `cargo test --test e2e` | Only `tests/e2e/mod.rs` (uses a custom harness) |

`--lib` and `--bin` can be combined:
```bash
# Run all unit tests (lib + bin), skip e2e
cargo test --lib --bin browser4-cli

# Run e2e tests separately (slower, requires a running backend)
cargo test --test e2e -- --nocapture
```

> [!TIP]
> `cargo test` runs all 3 targets by default.  Use `--lib --bin browser4-cli`
> during development to stay in the fast feedback loop.  Reach for
> `--test e2e` only when you specifically need integration coverage.

### Filtering by test name / module

```bash
# Exact match on function name
cargo test test_mousewheel_params_preserve_decimal_numbers

# Substring match — any test whose name contains "mousewheel"
cargo test mousewheel

# Filter by module path prefix
cargo test daemon::tests

# Module + function
cargo test daemon::tests::test_find_browser4_root

# Multiple patterns (OR semantics)
cargo test mousewheel keypress
```

### Filtering by test attribute

```bash
# Run only tests marked with #[ignore]
cargo test -- --ignored

# Run both normal and ignored tests
cargo test -- --include-ignored
```

### Controlling output

```bash
# Show println! output (captured by default)
cargo test -- --nocapture

# Show full output even for passing tests
cargo test -- --show-output

# Limit concurrency
cargo test -- --test-threads 1
```

### e2e-specific

e2e uses a custom harness (`harness = false`). Arguments after `--` are forwarded to
`tests/e2e/mod.rs`'s `main()`:

```bash
# Basics
cargo test --test e2e -- --nocapture

# Run only "Extended" level scenarios (default is "Basic")
cargo test --test e2e -- --nocapture --level EXTENDED

# Limit to 1 scenario
cargo test --test e2e -- --nocapture --scenario-limit 1

# Run a specific scenario (exact match)
cargo test --test e2e -- --nocapture --scenario test_e2e_batch_form_submission

# Wildcard — match multiple scenarios
cargo test --test e2e -- --nocapture --scenario *open*
cargo test --test e2e -- --nocapture --scenario test_e2e_swarm_*

# Re-run scenarios that failed last time
cargo test --test e2e -- --nocapture --failed

# Enable batch / swarm scenarios (skipped by default)
cargo test --test e2e -- --nocapture --enable-batch-scenario
cargo test --test e2e -- --nocapture --enable-swarm-scenario

# Combine flags
cargo test --test e2e -- --nocapture --failed --enable-batch-scenario

# List all available scenarios without running them
cargo test --test e2e -- --list

# More examples:
cargo test --test e2e -- --nocapture
cargo test --test e2e -- --nocapture --level Basic
cargo test --test e2e -- --nocapture --scenario-limit 1
cargo test --test e2e -- --nocapture --enable-batch-scenario
cargo test --test e2e -- --nocapture --enable-install-scenario
cargo test --test e2e -- --nocapture --batch-only
cargo test --test e2e -- --nocapture --scenario *open*
cargo test --test e2e -- --nocapture --scenario test_e2e_batch_*
cargo test --test e2e -- --nocapture --scenario test_e2e_swarm_*
cargo test --test e2e -- --nocapture --scenario test_e2e_agent_*
cargo test --test e2e -- --nocapture --scenario test_e2e_agent_task_commands
cargo test --test e2e -- --nocapture --scenario-from test_e2e_mouse_and_dialog
cargo test --test e2e -- --nocapture --scenario-from test_e2e_navigation_and_storage --scenario-limit 5
cargo test --test e2e -- --nocapture --failed
cargo test --test e2e -- --nocapture --scenario test_e2e_eval_command --fail-fast
cargo test --test e2e -- --nocapture --force-remote-bundle

# Nightly regression testing (runs all scenarios)
cargo test --test e2e -- --nocapture --level All --force-remote-bundle --enable-batch-scenario --enable-install-scenario
```

## License

Apache-2.0
