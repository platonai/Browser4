# Browser4

Make websites accessible for AI agents. Automate tasks online with ease.

## Installation

### Global Installation (recommended)

Installs the native Rust binary:

```bash
npm install -g browser4-cli
```

After installation, use `browser4-cli`. The shorter `browser4` command remains
available as a compatibility alias.

### Project Installation (local dependency)

For projects that want to pin the version in `package.json`:

```bash
npm install browser4-cli
```

Then use via `package.json` scripts or by invoking `browser4-cli` directly.

### Homebrew (macOS)

```bash
brew install browser4-cli
```

### Cargo (Rust)

```bash
cargo install browser4-cli
```

### Standalone Installer Scripts (no npm / Rust / Homebrew needed)

Bootstrap the native binary directly with a single command:

**Windows (PowerShell):**
```powershell
Invoke-WebRequest -Uri "https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1" -OutFile "$env:TEMP\install-browser4-cli.ps1"
powershell -ExecutionPolicy Bypass -File "$env:TEMP\install-browser4-cli.ps1"
```

**Linux / macOS (bash):**
```bash
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash
```

Or run the scripts locally from a cloned repo:
- `cli/scripts/install-browser4-cli.ps1` (Windows)
- `cli/scripts/install-browser4-cli.sh` (Linux / macOS / Git Bash)

See [Standalone CLI Installer Scripts](../docs/cli-standalone-install.md) for
the full option reference, supported platforms, and examples.

### From Source

```bash
git clone https://github.com/platonai/browser4-cli
cd cli/browser4-cli
pnpm install
pnpm build:native   # Requires Rust (https://rustup.rs)
pnpm link --global  # Makes browser4-cli available globally
```

### Requirements

- **Chrome** - Latest Chrome installed on your system.
- **Java 17+** - Required to run the Browser4 backend (`Browser4.jar`).
- **Rust** - Only needed when building from source (see From Source above).

## Usage

```
browser4-cli <command> [args] [options]
browser4-cli -s=<session> <command> [args] [options]
```

### Global options

| Flag               | Description                                    |
|--------------------|------------------------------------------------|
| `--help [command]` | Print help (optionally for a specific command) |
| `--version`        | Print version                                  |
| `-s=<name>`        | Named session label                            |
| `--server=<url>`   | Override Browser4 server URL                   |
| `--json`           | Emit machine-parseable JSON to stdout          |
| `-q`, `--quiet`    | Suppress normal output, only show errors       |

`--json` switches every command's stdout from human-readable text to a
single-line JSON envelope (`{"status":"ok","command":"<name>","output":{...}}`).
Omit `--json` for the default human-readable output.

`-q` / `--quiet` suppresses all normal stdout output.  Errors and
progress messages still go to stderr.  Combine with `--json` for
silent-on-success scripting: `browser4-cli --json -q open`.

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
| `open [url]` | Open or switch to a browser session. Supports `--headed` (force visible window) and `--headless` (force headless). |
| `close` | Close the active session |
| `goto <url>` | Navigate to a URL, auto-opening or refreshing the session if needed |
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

#### Screenshots

| Command | Description |
|---|---|
| `screenshot [ref]` | Take a screenshot (optionally of a specific element) |

#### Tabs

| Command | Description |
|---|---|
| `tab-list` | List all tabs |
| `tab-new [url]` | Create a new tab |
| `tab-close [index]` | Close a browser tab by zero-based index |
| `tab-select <index>` | Select a browser tab by zero-based index |

Use `tab-list` first to find the zero-based tab index you want to select or close.

#### Browser storage

| Command | Description |
|---|---|
| `state-save <path>` | Save cookies and localStorage to a JSON file |
| `state-load <path>` | Restore cookies and localStorage from a saved state file |
| `cookie-list` | List all cookies (optionally filtered by `--domain` / `--path`) |
| `cookie-get <name>` | Get a cookie by name |
| `cookie-set <name> <value>` | Set a cookie (optional `--path`, `--domain`) |
| `cookie-delete <name>` | Delete a cookie by name |
| `cookie-clear` | Clear all cookies for the current page |
| `localstorage-list` | List all localStorage entries |
| `localstorage-get <key>` | Get a localStorage value by key |
| `localstorage-set <key> <value>` | Set a localStorage key-value pair |
| `localstorage-delete <key>` | Delete a localStorage key |
| `localstorage-clear` | Clear all localStorage entries |
| `sessionstorage-list` | List all sessionStorage entries |
| `sessionstorage-get <key>` | Get a sessionStorage value by key |
| `sessionstorage-set <key> <value>` | Set a sessionStorage key-value pair |
| `sessionstorage-delete <key>` | Delete a sessionStorage key |
| `sessionstorage-clear` | Clear all sessionStorage entries |

#### Browser sessions

| Command | Description |
|---|---|
| `list` | List browser sessions |
| `close-all` | Close all browser sessions without stopping `Browser4.jar` / the Browser4 backend |
| `kill-all` | Forcefully stop `Browser4.jar` / the Browser4 backend and kill Browser4 browser processes |

Use `close-all` for session cleanup when you want to keep the current Browser4 service running. Use `kill-all` only when you explicitly want to stop the backend and clean up tracked Browser4 processes.

#### Server management

| Command | Description |
|---|---|
| `install` | Download the Browser4 runtime bundle. Supports `--tag=<version>` to pin a release and `--force` to reinstall even when already present. |
| `upgrade` | Upgrade the Browser4 runtime bundle to the latest version or a specified `--tag` |
| `stop` | Kill the Browser4 backend after closing all sessions |
| `status` | Check whether the Browser4 backend is reachable and healthy |

`install` and `upgrade` both manage the Browser4 runtime bundle — a self-contained
distribution that includes all dependency jars, a minimal `jlink`-built JRE, and
platform launcher scripts. Neither requires `cargo` or a Rust toolchain; the runtime
is a Java application downloaded from GitHub Releases.

Use `--tag=<version>` to pin a specific release (e.g. `--tag=v4.9.3`). Use `--force`
to reinstall even when the same version is already present.

When a local Browser4 checkout is detected with the `browser4-bundle` module present,
`install` and `upgrade` auto-build the runtime bundle from source (via Maven) instead
of downloading.

### Advanced commands

These commands are intentionally omitted from the global `browser4-cli help` overview.
Query `browser4-cli help <command>` for the exact syntax when you need them.

| Command | Description |
|---|---|
| `batch [command...]` | Execute multiple commands in one invocation. Only DOM operations are supported (Core, Navigation, Keyboard, Mouse, Export, Tabs categories). Commands like `open`, `close`, `list`, `agent run`, etc. are not allowed in batch mode. |
| `console [min-level]` | List console messages |
| `extract <instruction>` | Extract structured data from the current page |
| `summarize [instruction]` | Summarize page content using AI |
| `agent run <task>` | Run an autonomous agent task |
| `agent status <id>` | Check the status of a running agent task |
| `agent result <id>` | Get the result of a completed agent task |
| `swarm create` | Create a swarm scrape session with parallel browser contexts |
| `swarm submit [url]` | Submit URL(s) or raw X-SQL payloads as scrape jobs |
| `swarm query <url>` | Run an X-SQL query against a loaded webpage |
| `swarm status <id>` | Check the status of a scrape or query job |
| `swarm result <id>` | Get the result of a completed job |

## Agent task workflow (`agent <subcommand>`)

The `agent-*` commands wrap the backend command agent's asynchronous task API.
They are useful when you want Browser4 to plan and execute a natural-language
task in the background instead of issuing one low-level browser action at a
time.

Like other advanced commands, they are intentionally omitted from the global
`browser4-cli help` overview. Query `browser4-cli help agent run` (or
`agent status` / `agent result`) when you need the exact syntax.

Use the spaced `agent <subcommand>` form:

```shell
browser4-cli agent run "Open example.com and summarize the hero section"
browser4-cli agent status agent-task-1
browser4-cli agent result agent-task-1
```

### Command lifecycle

| Step | Command | What it does |
|---|---|---|
| 1 | `agent run <task>` | Submits an asynchronous natural-language task through `command_run` and prints the returned task ID |
| 2 | `agent status <id>` | Fetches the latest task status payload through `command_status` |
| 3 | `agent result <id>` | Fetches the completed task result payload through `command_result` |

### Notes

- `agent run` is asynchronous: it returns immediately after the backend accepts
  the task and prints a follow-up `agent status` command with the generated task
  ID.
- `agent status` prints the backend status payload as-is. In practice this is a
  JSON object that commonly includes fields such as `id`, `status`,
  `statusCode`, `processState`, `message`, `agentState`, `agentHistory`, and
  `commandResult`.
- `agent result` prints the backend result payload as-is. Depending on the task,
  it may be plain text or structured JSON.
- These commands are task-ID based and do not require an active CLI browser
  session slot. The global `-s=<name>` option is therefore usually not relevant
  for `agent-*` follow-up calls.
- `agent` subcommands are not supported inside `batch` mode.
- `agent run` performs a short post-submit status probe so obvious missing-LLM
  configuration failures can be surfaced immediately instead of leaving you with
  a task ID that will never succeed.

### Use cases

#### 1. Submit an autonomous agent task

```shell
browser4-cli agent run "Open browser4.io and summarize the hero section"
```

Typical output:

```text
Task submitted: agent-task-1
Use 'browser4-cli agent status agent-task-1' to check progress.
```

#### 2. Poll task progress

```shell
browser4-cli agent status agent-task-1
```

Example status payload:

```json
{"id":"agent-task-1","status":"RUNNING"}
```

On a real Browser4 backend the payload can be richer and may include lifecycle
details such as `processState`, agent history snapshots, or an embedded partial
`commandResult`.

#### 3. Read the final result

```shell
browser4-cli agent result agent-task-1
```

If the backend returns a structured `CommandResult`, expect fields such as
`summary`, `pageSummary`, `fields`, `links`, or `xsqlResultSet`.

## Swarm scrape workflow (`swarm <subcommand>`)

The `swarm` subcommands support a swarm scrape workflow where one CLI session
coordinates multiple browser contexts in the Browser4 backend.

### Command overview

| Command | Purpose | Backend endpoint |
|---|---|---|
| `swarm create` | Create a swarm scrape session | `POST /api/swarm` |
| `swarm submit <url>` | Scrape URLs or submit raw X-SQL | `POST /api/swarm/submit` |
| `swarm query <url>` | Run X-SQL queries against loaded pages | `POST /api/swarm/query` |
| `swarm status <id>` | Poll job status | `GET /api/swarm/{id}/status` |
| `swarm result <id>` | Fetch completed job result | `GET /api/swarm/{id}/result` |

### URL scraping with `swarm submit`

```shell
# create a session
browser4-cli swarm create \
  --profile-mode=TEMPORARY \
  --max-open-tabs=12 \
  --max-browser-contexts=3 \
  --display-mode=HEADLESS

# submit URLs as scrape jobs
browser4-cli swarm submit https://example.com/direct \
  --seed-file=./swarm-seeds.txt \
  --deadline=2026-03-30T00:00:00Z \
  --expires=1d \
  --refresh --parse --store-content

# poll and fetch the result
browser4-cli swarm status scrape-task-4
browser4-cli swarm result scrape-task-4
```

### X-SQL queries with `swarm query`

Run structured X-SQL queries against loaded webpages to extract data.

```shell
# Inline query:
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql "
  SELECT
    dom_base_uri(dom) AS url,
    dom_first_text(dom, '#productTitle') AS title,
    dom_first_slim_html(dom, 'img:expr(width > 400)') AS img
  FROM load_and_select(@url, 'body');
"

# From a file:
browser4-cli swarm query "https://www.amazon.com/dp/B08PP5MSVB" --sql @query.sql

# With seed file and load options:
browser4-cli swarm query --sql @query.sql --seed-file=./urls.txt --refresh --parse
```

### Notes

- `swarm create` accepts backend capability hints: `--profile-mode`, `--max-open-tabs`,
  `--max-browser-contexts`, `--display-mode`.
- `swarm submit` and `swarm query` both accept a positional URL, `--seed-file`, or both.
  Seed files use one URL per line; `#` comments and blank lines are ignored.
- Both commands support load-option flags: `--deadline`, `--expires`, `--refresh`,
  `--parse`, `--store-content`.
- `swarm query --sql` is **required**; `swarm submit --sql` also works as a convenience.
  Use `@url` in the X-SQL template; it is replaced with the target URL server-side.
- Prefix the `--sql` value with `@` to read from a file (e.g. `--sql @query.sql`).
- All commands return a task ID; use `swarm status` / `swarm result` to track progress.

## Element References

The `snapshot` command returns an accessibility tree where every interactive
node is labeled with a short identifier such as `e15`. Pass this identifier
directly to commands like `click`, `type`, or `press`. You can also use plain
CSS selectors (e.g. `.my-button`, `#search-input`).

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

| Command | Behavior |
|---|---|
| `open` | Creates a new session, or reuses an existing active one. Stale sessions are automatically refreshed. |
| `open -s=<name>` | Same as `open` but scoped to a named session slot. |
| `goto <url>` | Reuses the current session if active; otherwise opens a fresh one before navigating. |
| `close` | Closes the current session (no-op if none active). |
| `close-all` / `kill-all` / `stop` | Clears all persisted session state. |

The `list` command shows each session's status: **Active** (backend confirms),
**Stale** (backend has stopped it), or **Unknown** (backend unreachable).

## Runtime Temp Files

`browser4-cli` keeps ephemeral runtime artifacts under the system temp directory:

- Windows: `%TEMP%\browser4\browser4-cli`
- Linux/macOS: `${TMPDIR:-/tmp}/browser4/browser4-cli`

This temp subtree contains items such as:

- startup logs for auto-started Browser4 servers
- staged Maven wrapper launchers
- Rust test scratch directories used by `browser4-cli` tests

Persistent CLI state remains under `~/.browser4` by default.  The Browser4 runtime
bundle (JRE, JARs, launchers) is stored separately in a platform-conventional
data directory so that clearing CLI session state does not require re-downloading
the ~200 MB runtime:

- Linux:   `~/.local/share/browser4/runtime/<version>/`
- macOS:   `~/Library/Application Support/browser4/runtime/<version>/`
- Windows: `%APPDATA%/browser4/runtime/<version>/`

The `current.tag` file in the `runtime/` directory records the active version.
Override the runtime data root with the `BROWSER4_RUNTIME_DIR` environment variable.
Downloaded archives are cached under the platform cache directory
(`~/.cache/browser4/downloads/` on Linux, `~/Library/Caches/browser4/downloads/`
on macOS, `%LOCALAPPDATA%/browser4/downloads/` on Windows).

## Snapshots

After each command that modifies browser state, the CLI automatically:

1. Retrieves the current page URL and title
2. Captures an accessibility snapshot
3. Saves the snapshot to `.browser4-cli/snapshot/page-<timestamp>.yml`
4. Prints the snapshot path in Markdown link format

## Examples

```shell
# Open a new browser window (defaults to headed)
browser4-cli open

# Open in headed or headless mode
browser4-cli open --headed https://browser4.io
browser4-cli open --headless https://browser4.io

# Navigate to a page — auto-opens a session if none is active
browser4-cli goto https://playwright.dev

# Inspect the page — note the eN labels on interactive nodes
browser4-cli snapshot

# Interact using refs from the snapshot
browser4-cli click e15
browser4-cli type "Hello World" e15
browser4-cli press Enter e15
browser4-cli eval "document.title"
browser4-cli eval "element => element.textContent" e15
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

## Publishing the CLI package

For maintainers, the CLI package now uses an npm version guard before publish.

The GitHub release workflow publishes the npm package via npm trusted publishing
(GitHub Actions OIDC) instead of `NODE_AUTH_TOKEN`. This avoids CI failures caused
by npm one-time-password challenges (`EOTP`).

- Local release entrypoint: `npm run release`
- Direct guarded publish entrypoint: `npm run publish:if-needed`
- GitHub release workflow: re-checks npm immediately before the publish step

If the local version in `cli/package.json` already matches the version currently
published on npm, the publish step is skipped automatically.

Examples:

```bash
# Check whether npm publish is needed
node scripts/check-npm-publish-needed.js --json

# Publish only when the local version differs from npm
npm run publish:if-needed

# Standard maintainer release command (also guarded)
npm run release
```

For local testing, you can override the detected remote version:

```bash
BROWSER4_CLI_NPM_REMOTE_VERSION=0.1.7 node scripts/check-npm-publish-needed.js --json
BROWSER4_CLI_NPM_REMOTE_VERSION=0.1.7 node scripts/publish-if-needed.js --dry-run
```

## License

Apache-2.0
