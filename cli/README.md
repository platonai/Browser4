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
| `goto <url>` | Navigate to a URL using the current active session |
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
| `batch [command...]` | Execute multiple commands in one invocation. Only DOM operations are supported (Core, Navigation, Keyboard, Mouse, Export, Tabs categories). Commands like `open`, `close`, `list`, `agent run`, etc. are not allowed in batch mode. |
| `console [min-level]` | List console messages |
| `extract <instruction>` | Extract structured data from the current page |
| `summarize [instruction]` | Summarize page content using AI |
| `agent run <task>` | Run an autonomous agent task |
| `agent status <id>` | Check the status of a running agent task |
| `agent result <id>` | Get the result of a completed agent task |
| `swarm create` | Create a swarm scrape session with parallel browser contexts |
| `swarm submit [url]` | Submit URL(s) or X-SQL payloads as scrape jobs |
| `swarm status <id>` | Check the status of a scrape job |
| `swarm result <id>` | Get the result of a completed scrape job |

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
browser4-cli agent run "Open example.com and summarize the hero section"
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

Use the spaced `swarm <subcommand>` form:

```shell
browser4-cli swarm create
browser4-cli swarm submit https://example.com
```

### Command lifecycle

| Step | Command | What it does |
|---|---|---|
| 1 | `swarm create` | Opens a swarm scrape session and persists the returned session ID in the current CLI slot |
| 2 | `swarm submit [url]` | Submits one direct URL plus any URLs from `--seed-file` as scrape jobs through `ScrapeController.submit(payload)` |
| 3 | `swarm status <id>` | Calls `ScrapeController.getStatus(id)` and prints the returned scrape job status JSON |
| 4 | `swarm result <id>` | Calls `ScrapeController.getResult(id)` and prints the returned scrape job result JSON |

### Notes

- `swarm create` accepts backend capability hints such as `--profile-mode`,
  `--max-open-tabs`, `--max-browser-contexts`, and `--display-mode`.
- `swarm submit` accepts either a direct positional URL, `--seed-file`, or both.
  Seed files are plain text files with one URL per line; blank lines and lines
  starting with `#` are ignored.
- `swarm submit` maps CLI flags like `--deadline`, `--expires`, `--refresh`,
  `--parse`, and `--store-content` into the raw submission payload sent to the
  scrape REST API.
- `swarm status` and `swarm result` are read-only follow-up commands; keep the job ID
  printed by `swarm submit`.

### Use cases

#### 1. Create a supervised swarm scrape session for manual monitoring

```shell
browser4-cli swarm create \
  --profile-mode=TEMPORARY \
  --max-open-tabs=12 \
  --max-browser-contexts=3 \
  --display-mode=HEADLESS
```

Use this when you want multiple isolated browser contexts and you still want to
watch the run visually.

#### 2. Submit a seed crawl as scrape jobs

```shell
browser4-cli swarm submit https://example.com/direct \
  --seed-file=./swarm-seeds.txt \
  --deadline=2026-03-30T00:00:00Z \
  --expires=1d \
  --refresh \
  --parse \
  --store-content
```

Example `swarm-seeds.txt`:

```text
# campaign landing pages
https://example.com/seed-1
https://example.com/seed-2
```

This pattern is useful for warming caches, refreshing a URL list, or launching
parallel collection across a curated seed set.

#### 3. Poll and fetch the result

```shell
browser4-cli swarm status scrape-task-4
browser4-cli swarm result scrape-task-4
```

The status and result commands print the scrape job response payload as-is. In
the current backend, `getResult(id)` returns the same response envelope type as
`getStatus(id)`.

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
| `open` when a saved session exists and the backend still reports it `active` | No state change — keeps the existing `sessionId` | The existing session is reused; subsequent commands target the same session |
| `open` when a saved session exists but is missing or no longer `active` in the backend | `invalidate_session()` clears the stale saved `sessionId`, `activeSelector`, and `lastMousePosition`, then `create_session()` writes a fresh session | The stale session is refreshed automatically by opening a new one |
| `open -s=<name>` | Reads/writes the named session state file | Opens, reuses, or refreshes the named session for that slot; subsequent `-s=<name>` commands use the same slot |
| Command succeeds through `with_session()` | `sessionId` stays unchanged | The command uses the persisted session normally |
| Command fails because the server reports a stale / expired session and `recover_stale = false` | `invalidate_session()` clears `sessionId`, `activeSelector`, and `lastMousePosition`, while keeping `baseUrl` | The command fails with `Saved session expired. Run "browser4-cli open" first.` |
| `goto` is invoked but the saved session is missing or no longer `active` in the backend | `invalidate_session()` clears any stale saved `sessionId`, then `create_session()` writes a fresh session before navigation continues | `goto` automatically refreshes the session and proceeds to the requested URL |
| `close` with an active session | `clear_state()` removes only the current session state file after best-effort remote close | The selected default or named session is fully cleared |
| `close` with no persisted `sessionId` | `clear_state()` best-effort removes the current session slot | Prints `No active session. Run "browser4-cli open" first.` and exits successfully as a no-op |
| `close-all` / `kill-all` | `clear_all_state()` removes the default state file and all named session files | All persisted CLI session files are cleared |

Notes:

- `goto` first tries to reuse the current backend-`active` session. If the saved
  session is missing, stale, or the backend had been stopped, it automatically
  opens a fresh session for the current slot before navigating.
- `open` first checks whether the saved session for the current slot is still
  backend-`active`. It reuses active sessions and refreshes stale ones by
  creating a new session for the same slot.
- `list` reads persisted session files and compares them with live backend
  sessions to show both the current status (`Active`, `Stale`, or `Unknown`)
  and whether the next `open` will `Reuse` or `Refresh` that slot.

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

# Navigate to a page with the current active session
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
