# Full CLI Command Reference

English | [简体中文](cli-reference.zh.md)

> Complete command and option reference for `browser4-cli`. The project
> [README](../README.md) carries only a cheat sheet; everything below is the
> authoritative list. `browser4-cli --help <command>` prints the same details
> from the binary, and `browser4-cli --help-json` emits them as JSON.

## Session lifecycle and server administration

| Command | Description |
|---|---|
| `open [url]` | Open a browser session or reconnect to an existing one. **Headless by default.** Supports `--headed` (visible window), `--headless`, `--profile <path>`, `--profile-mode <DEFAULT\|SYSTEM_DEFAULT\|SEQUENTIAL\|TEMPORARY>`, `--interact-level <FASTEST\|FAST\|DEFAULT>`. **Note:** `SYSTEM_DEFAULT` is deprecated and unsupported on Chrome ≥ 143 — use `attach` + `state save`/`state load` to reuse system browser state (see [browser-state-import.md](../skills/browser4-cli/references/browser-state-import.md)). |
| `attach` | Attach to an existing browser via CDP or the Browser4 extension. Supports `--cdp <url\|port\|channel>` and remote endpoint options. A channel name resolves candidates from the browser's `DevToolsActivePort`, `--remote-debugging-port` and listening ports, and binds the first endpoint that can host a page — over `/json`, or over the browser-level WebSocket that Chrome's built-in `chrome://inspect/#remote-debugging` toggle publishes (`attach --cdp ws://127.0.0.1:<port>/devtools/browser/<uuid>` also works). After a successful attach the CLI prints the browser that actually connected (`Connected browser: …` / `Attached to … at …`) and shows a ⚠ warning when it conflicts with the requested channel (e.g. requested msedge but Chrome connected) — verify it before driving the session. |
| `close` | Close the active browser session. |
| `list` | List browser sessions with their status and next-open behavior. The Connection column prefers the backend-reported actual browser and annotates channel conflicts (e.g. `requested msedge · actual Google Chrome`). Supports `--all`, which adds a Workspace column and includes the sessions persisted by every other checkout (`*` marks the workspace you are running in; foreign rows read Unknown / `-` / Refresh because this backend cannot verify them). |
| `session default <name>` | Make a named session become the default unnamed session. |
| `close all` | Close all sessions without stopping the backend. |
| `kill all` | Force-stop the backend and Browser4-managed browser processes. |
| `stop` | Gracefully stop the Browser4 server. |
| `status` | Show server version, port, health, and the web status panel URL (`http://<server>:18182/status`). When a session is active it also prints a current-session block: Name / Session ID / Status / Display / Connection / Next open. |
| `doctor` | Run diagnostics: build info, LLM status (including the configuration file it reads, the winning provider key and the active model), stale daemon cleanup, optional repair. Supports `--verbose` and `--fix`; `--fix` also writes a commented LLM config template. |
| `doctor log [name]` | List, view, tail, or grep backend log files. Supports `--tail`, grep-style flags, and `doctor log <name> grep <pattern>`. |
| `doctor metrics [filter]` | List, filter, or grep backend metrics. Supports `doctor metrics grep <pattern>`. |
| `doctor status [--section <name>] [--verbose]` | Print the aggregated status panel report in the terminal: summary layer by default, full detail with `--verbose`, one report with `--section` (health, build, runtime, llm, sessions, pulsar-sessions, swarm, url-pool, browsers, drivers, privacy, plugins, skills, metrics, logs), machine-readable JSON with `--json`. |
| `delete data` | Delete session data. |
| `install` | Install the Browser4 runtime bundle. Supports `--tag <version>` and `--force`. |
| `upgrade` | Upgrade the CLI/runtime bundle. Supports `--tag <version>` and `--force`. |
| `uninstall` | Remove global installs and runtime data. Supports `-y`, `--yes`, and `--dry-run`. |

```bash
browser4-cli open --headed https://example.com
browser4-cli attach --cdp chrome
browser4-cli doctor --verbose
browser4-cli doctor log server.log --tail
browser4-cli doctor metrics grep request
browser4-cli doctor status --section skills --verbose
```

**Web status panel:** open `http://127.0.0.1:18182/status` in a browser for a live dashboard
(health, version, JVM/runtime, LLM config, sessions, **Pulsar sessions** — SDK identity,
context and main-loop state, **swarm** — swarm session plus task summary, **URL pool** —
queued/real-time/delay counts per priority cache, browsers & open tabs — per-session
browser/driver binding and tab counts, with on-demand live tab details via
`GET /api/system/tabs` — driver pools, plugins: load/enable state and SDK compatibility,
**skills**: registered skills with origin (classpath/filesystem/programmatic),
metrics, log files; auto-refreshes, set `?refresh=<ms>` to change the interval). The panel
is backed by the aggregated `GET /api/system/status` endpoint; the individual endpoints
(`/api/system/health`, `/api/system/build`, `/api/doctor/llm-status`, `/api/doctor/metrics`,
`/api/doctor/log-files`, `/api/plugins`, `/api/skills`) remain available. `browser4-cli plugin list` also
reports load/enable state and SDK version for every installed plugin; the same reports can be
read from the terminal with `browser4-cli doctor status`.

**Page screenshots:** open `http://127.0.0.1:18182/pages.html` for a grid of every open page
across sessions. The active tab of each session is captured automatically (click a screenshot
to re-capture it); inactive tabs show a placeholder that captures on click. Swarm sessions only
show placeholders. Screenshots load **asynchronously** — the backend captures in the background
(`202 Accepted` with `Retry-After` while capturing, cached `image/png` when ready), so the panel
never blocks on a capture. Backed by `GET /api/pages` and
`GET /api/pages/{sessionId}/{guid}/screenshot.png` (`?refresh=1` forces a new capture).

## Navigation

| Command | Description |
|---|---|
| `goto <url>` | Navigate to a URL; auto-opens/reconnects a session if needed. |
| `go back` | Go back in browser history. |
| `go forward` | Go forward in browser history. |
| `reload` | Reload the current page. |

## Core interaction

All interaction commands accept a snapshot ref such as `e15` or a CSS selector unless noted otherwise. Most of them also support `--no-snapshot` to skip the automatic post-action accessibility snapshot.

| Command | Description |
|---|---|
| `click <ref> [button]` | Click an element. Supports `--modifiers`, `--follow`, `--auto-dismiss-dialogs`. |
| `dblclick <ref> [button]` | Double-click an element. Supports `--modifiers`, `--follow`, `--auto-dismiss-dialogs`. |
| `hover <ref>` | Hover over an element. |
| `fill <ref> <text>` | Clear and fill text into an editable field. Supports `--submit`, `--verify`. |
| `type <text> [ref]` | Type text into the focused element or a target element. Supports `--submit`, `--verify`, `--focus`, `--interactable-timeout`, and `--method auto\|chars\|exec` (requires a target ref; `auto` types short text per-character and bulk-inserts long/multi-line text on textarea/contenteditable via a single `execCommand('insertText')`, `chars` forces per-character typing, `exec` forces the bulk insert). |
| `press <key> [ref]` | Press a key on the focused element or a target element. Supports `--verify`, `--follow`. |
| `select <ref> <value>` | Select a dropdown value. Supports `--verify`. |
| `check <ref>` | Check a checkbox or radio button. |
| `uncheck <ref>` | Uncheck a checkbox or radio button. |
| `drag <startRef> <endRef>` | Drag and drop from one element to another. |
| `wait [target]` | Wait for a selector/ref, duration, text, URL pattern, page-load state, or JavaScript expression. Supports `--timeout`, `--text`, `--url`, `--load`, `--fn`. |
| `upload <ref> <file> [file...]` | Upload one or more local files to a page file input. The target must be an `<input type="file">` (anything else errors); the paths must be readable by the browser process — local mode: the same machine, and empty/missing files are rejected with a hint; remote backend: paths resolve on the backend host. Supports `--no-snapshot`. |

`wait --load` accepts `domcontentloaded`, `load`, and `networkidle`. `networkidle` only proves the network went quiet — it does **not** mean late-rendered results exist; for result pages poll the element instead: `wait "<result-selector>"`.

```bash
browser4-cli click e8 --follow
browser4-cli fill e4 "john@example.com" --submit
browser4-cli type "Browser4" e7 --verify
browser4-cli wait --text "Success"
browser4-cli wait --load networkidle
```

## Keyboard and mouse

| Command | Description |
|---|---|
| `keydown <key>` | Press and hold a key. |
| `keyup <key>` | Release a held key. |
| `mousemove <x> <y>` | Move the mouse to viewport CSS pixels `(x, y)`, relative to the main frame's viewport top-left corner. This is not a desktop/screen coordinate and it does not move the OS cursor; a coordinate outside the viewport means "leave the page", not "outside the browser window". Coordinates are stale after the page scrolls. |
| `mousedown [button]` | Press a mouse button. |
| `mouseup [button]` | Release a mouse button. |
| `mousewheel <dx> <dy>` | Scroll using a wheel delta. |
| `scroll <direction> <pixels>` | Scroll the page `up`, `down`, `left`, or `right`. |

## Page inspection and live extraction

| Command | Description |
|---|---|
| `snapshot` | Capture an accessibility-tree snapshot. Supports `--boxes`/`--no-boxes`, `-i/--interactive`, `-u/--urls`, `-c/--compact`, `--no-compact`, `-d/--depth`, `--selector` (no short form — `-s` is the global session flag), `--raw`, `--stdout`, `-v/--viewport`, `--auto-diff`, `-b/--brief`, `--filename`. `--stdout`/`--raw` output is paginated at 2000 lines/page by default — when truncated, stdout (if piped) gets a `# … output truncated: showing N of M lines …` hint and the full footer goes to stderr; use `--all` or `--page-size 0` for the complete tree, and bound very large pages with `-v N`/`--depth`/`--selector`/`--no-boxes`. |
| `snapshot grep <pattern>` | Search saved/current snapshot YAML with grep-style flags such as `-i`, `-v`, `-c`, `-l`, `-F`, `-w`, `-A`, `-B`, `-C`, `--selector`, `--page`, `--page-size`, `--all`. |
| `snapshot list` | List saved snapshot files with timestamps and sizes. |
| `snapshot clean` | Remove old snapshot files. Supports `--dry-run`. |
| `get <mode> <selector> [name]` | Extract `text`, `html`, `box`, `styles`, `property`, or `attr` from a live page element. |
| `eval [expression] [ref]` | Evaluate JavaScript on the page or an element. Supports `--file`, `--stdin`, `--base64`, `--await`, `--wait-selector`, `--json`. |
| `console [min-level]` | List browser console messages. Supports `--clear`. |
| `cdp <method>` | Send an arbitrary Chrome DevTools Protocol command. Supports `--json <params>`. |
| `generate locator <ref>` | Generate the best CSS selector for a snapshot ref or existing selector. |
| `resize <width> <height>` | Resize the browser window. |
| `dialog accept [prompt]` | Accept an alert/confirm/prompt dialog, optionally filling the prompt. |
| `dialog dismiss` | Dismiss an alert/confirm/prompt dialog. |

`get` modes:

| Mode | Meaning | Example |
|---|---|---|
| `text` | visible inner text | `browser4-cli get text ".price"` |
| `html` | inner HTML | `browser4-cli get html "#main"` |
| `box` | bounding box | `browser4-cli get box "#hero"` |
| `styles` | computed styles | `browser4-cli get styles e9` |
| `property` | DOM property value | `browser4-cli get property "input" value` |
| `attr` | HTML attribute value | `browser4-cli get attr "a" href` |

```bash
browser4-cli snapshot -i --boxes
browser4-cli snapshot grep -C 2 "button"
browser4-cli eval "document.title"
browser4-cli eval --file script.js --await
browser4-cli console warn
browser4-cli cdp Runtime.evaluate --json '{"expression":"document.title"}'
```

## HTML snapshot and X-SQL extraction

`htmlsnapshot` captures a raw DOM snapshot of the page the active tab is showing and is the center of Browser4's structured extraction workflow. **Every `htmlsnapshot` command works on a fresh snapshot of the active page**: it captures the tab first (serializing the document the tab already shows, without navigating) and then operates on that snapshot — `capture` returns the metadata, while the reads consume it, so a read already sees the live document (form results, SPA updates, `eval` mutations). A read can also be told to serve the **stored** snapshot instead: `--expires <dur>` (default `0s`, the live page) serves the stored copy of the active page while it is younger than the window — `htmlsnapshot get text ".price" --expires 1d` reads the previous snapshot version and never touches the tab. A command aimed at another URL (`readability <url>`, `query --url <url>`) reads *that URL's* stored copy, or an independent read-only load when the store has nothing — a URL the tab does not show is never captured, so the tab's document is never filed under it.

| Command | Description |
|---|---|
| `htmlsnapshot` | Short form of `htmlsnapshot capture`. |
| `htmlsnapshot capture` | Capture and store a static HTML snapshot with metadata about the page and interactive elements. |
| `htmlsnapshot get <field> [selector] [name]` | Extract the first matching `text`, `textcontent`, `html`, or `attr` from a fresh snapshot of the active page. Supports `--expires <dur>`. |
| `htmlsnapshot get all <field> [selector] [name]` | Extract all matching values from a fresh snapshot of the active page. Supports `--offset`, `--limit`, and `--expires <dur>`. |
| `htmlsnapshot query [url]` | Run X-SQL against a fresh snapshot of the active page, or against an explicit URL's stored page. Supports `--sql <query\|@file>`, `--sql-stdin`, `--sql-base64`, `--expires <dur>`, result pagination, and extraction-focused output flags. |
| `htmlsnapshot export` | Export a fresh snapshot's HTML to a file. Supports positional file path or `--file <path>` plus `--clean` and `--expires <dur>`. |
| `htmlsnapshot summary [--algorithm <id>]` | Generate a compressed Web Page Summary Index (WPSI) from a fresh snapshot of the active page; plugins may contribute additional algorithm ids. Supports `--expires <dur>`. |
| `htmlsnapshot algorithms` | List installed summary algorithms (built-in `wpsi` plus plugin-contributed ids). No page required. |
| `htmlsnapshot grep <pattern>` | Search a fresh snapshot's HTML with grep-style flags. Supports `--expires <dur>`. |
| `htmlsnapshot inspect [selector]` | Discover recurring DOM patterns and selector candidates in a fresh snapshot of the active page. Supports `--max`, `--depth`, `--stdin`, `--selector-base64`, `--expires <dur>`. |
| `htmlsnapshot readability [url]` | Extract the main article content with a Readability-style heuristic — no LLM, no tokens. Without a URL: the active page; with one: that URL's own stored copy. Supports `--text-only`, `--expires <dur>`, and pagination. |

Important rules:

- use `snapshot` when you need refs and interaction
- use `htmlsnapshot` when you need repeated DOM extraction — every command captures the tab first, so simply rerunning it sees the page as it is now
- use `--expires <dur>` on a read when you want the snapshot already in the store (`--expires 1d`) instead of the live page (`--expires 0s`, the default) — the stored copy is left untouched and the tab is not serialized
- `htmlsnapshot query --sql @query.sql` is the recommended way to avoid shell quoting issues
- for correlated list extraction, prefer `htmlsnapshot query` over repeated `get all`
- for one-step article extraction (no selectors needed), use `htmlsnapshot readability`

```bash
browser4-cli htmlsnapshot
browser4-cli htmlsnapshot get text "#productTitle"
browser4-cli htmlsnapshot get all text ".result-title" --offset 10 --limit 5
browser4-cli htmlsnapshot inspect ".s-result-item" --depth 6 --max 20
browser4-cli htmlsnapshot export --file page.html --clean
browser4-cli htmlsnapshot query --sql @query.sql
browser4-cli htmlsnapshot readability --text-only --all
```

For deep X-SQL usage, see [skills/browser4-cli/references/htmlsnapshot.md](../skills/browser4-cli/references/htmlsnapshot.md) and [skills/browser4-cli/references/x-sql-dom-load-select.md](../skills/browser4-cli/references/x-sql-dom-load-select.md).

## Screenshots and PDF

| Command | Description |
|---|---|
| `screenshot [ref]` | Take a page or element screenshot. Supports `--filename`, `--full-page`, `--viewport`. |
| `pdf` | Save the current page as PDF. Supports `--filename`. |

## Tabs

| Command | Description |
|---|---|
| `tab list` | List open tabs with indexes, titles, and URLs; use `--json` for full GUIDs. |
| `tab new [url]` | Open a new tab, optionally navigating to a URL. |
| `tab close [index]` | Close a tab by index; supports `--guid <guid>`. |
| `tab select <index>` | Switch to a tab by index; supports `--guid <guid>`. |

## Browser storage and local page data

| Command | Description |
|---|---|
| `state save [filename]` | Save cookies and localStorage to a JSON file. |
| `state load <filename>` | Restore cookies and localStorage from a JSON file. |
| `cookie list` | List cookies. Supports `--domain`, `--path`. |
| `cookie get <name>` | Get a cookie by name. |
| `cookie set <name> <value>` | Set a cookie. Supports `--domain`, `--path`, `--expires`, `--httpOnly`, `--secure`, `--sameSite`. |
| `cookie delete <name>` | Delete a cookie by name. Supports `--domain`, `--path`. |
| `cookie clear` | Clear all cookies. |
| `localstorage list` | List localStorage entries. |
| `localstorage get <key>` | Read a localStorage key. |
| `localstorage set <key> <value>` | Set a localStorage key. |
| `localstorage delete <key>` | Delete a localStorage key. |
| `localstorage clear` | Clear localStorage. |
| `sessionstorage list` | List sessionStorage entries. |
| `sessionstorage get <key>` | Read a sessionStorage key. |
| `sessionstorage set <key> <value>` | Set a sessionStorage key. |
| `sessionstorage delete <key>` | Delete a sessionStorage key. |
| `sessionstorage clear` | Clear sessionStorage. |
| `webdb export <dir>` | Export pages from the Browser4 web database to a local directory. |
| `webdb normalize <url>` | Normalize a URL into the web database key format. |

## AI extraction, chat, and autonomous agent tasks

These commands require an LLM key.

| Command | Description |
|---|---|
| `extract <instruction>` | Extract structured data from the current page. Supports `--schema <json\|@file>`, `--filename`, `--raw`, `--stdout`. |
| `summarize [instruction]` | Summarize the current page. Supports `--selector`, `--filename`, `--raw`, `--stdout`. |
| `chat <message>` | Send a plain AI chat request without auto-appended browser context. |
| `chat result <id>` | Retrieve the result of an async chat task. |
| `agent run <task>` | Submit an autonomous browser task and immediately receive a task ID. Supports `--wait` (block for the result) and `--wait-timeout <seconds>` (default 600). `--follow` streams every step (tool calls, model text, slow/failed tools) and ends with the result — Ctrl+C cancels the task; `--ndjson` emits that stream as one JSON object per line. `-i` opens an interactive session where a line typed while a task runs steers it (never queued as a second task; `/followup <text>` is delivered instead when the agent would stop, so the same task continues with it), and `/stats` reports the run's LLM rounds/requests, token usage (prompt-cache reads/writes and hit rate included when the provider reports them) and tool calls; the per-task budget counts **cache-miss** tokens (5M by default — prompt-cache reads are free), a budget abort offers a one-key "continue" with the cap doubled, and after every task `-i` predicts 2–3 numbered next *tasks* — the model's own follow-up suggestions first, then what the payload left open (files to review/test/commit, findings to dig into, a failure to diagnose) — never a menu of CLI commands; every task of a session also inherits the **actual conversation** of the earlier ones as a `## Session context` block (instructions, the model's messages, tool calls and results, your steering lines, each task's outcome and files/workspace), which the model compacts with a dedicated compression instruction once it crosses `browser4.agent.session.contextTokens` (24000 by default) into about `browser4.agent.session.memoryTokens` (2000) — the goal, corrections, decisions, files/workspace and open problems survive the fold (`/new` starts a fresh context), the conversation survives a backend restart (`browser4.agent.session.contextTtlMinutes`, 12 h default), an unnamed `-i` uses its own `interactive-<hash>` session instead of the backend DEFAULT pool, and a steering line that arrives after the last model round is reported (`⚠ steering not delivered`) and kept for the next task; `/result [--json] [--full]` re-prints the last payload (pretty-printed JSON, or nothing folded). A backend hiccup or an unreadable step never ends the live view: `-i` keeps following a task that still runs and skips the one unreadable step. To go back instead of forward: `--rewind <task-id>` continues this session's CONVERSATION from the point where that task began, with the abandoned branch summarized and carried along (`-i`: `/rewind <task-id>`; it needs the live backend process, and combining it with `--resume` is rejected). Task type is browser by default; `--coding` explicitly marks a pure file/build task (never auto-detected). `--access <preset>` (`full`/`write`/`read-build`/`read`) narrows that task's coding tools and is reset when the task ends; `--unrestricted` runs it with YOUR own OS permissions instead (full preset, no workspace/protected rails, no OS sandbox — and the shell's command allow-list off as well, so programs outside it (a PowerShell cmdlet, for example) run verbatim; shapes the checker cannot decompose, such as `for` loops and inline scripts, are handed to the platform shell in every mode), audited per task and inherited by `--resume`, while the OS credential stores (`~/.ssh`, `~/.aws`, `~/.gnupg`, `~/.config/gcloud`) and the commands that would kill or rebuild the backend stay refused; `--add-dir <folder>` grants this task an extra **existing** folder besides its workspace (repeatable; readable, writable and usable as a shell working directory — `--access` still bounds what may be written there, credential stores stay refused, and `--resume` inherits the grant; in `-i`, `/add-dir <folder>` grants one mid-session and `/dirs [clear]` lists or drops the grants); a refused step is not a dead end — `coding.requestAccess` files a non-blocking pending request and an operator approves it through `POST /api/agent/<taskId>/access/<requestId>/approve`, while `denyTools`, protected paths and sensitive paths are never approved; a job with no TTY (scripts, CI, submitted tasks) derives `full` with outbound network instead of the server-wide preset (`browser4.agent.access.unattended` narrows it) |
| `agent status <id>` | Check a task — the shared status envelope (`status`: queued/running/done/failed/cancelled, plus `statusCode`, `message` and the agent's step count). |
| `agent result <id>` | Fetch a completed task's result: its summary, `keyFindings`, `filesChanged` and the workspace its deliverables live in. Rendered as sections (`--json` prints the raw envelope). |
| `agent list` | List tracked agent tasks with the same statuses (refreshed from the backend on every call); `--clear` untracks the finished ones. |

```bash
browser4-cli extract "product name, price, rating"
browser4-cli extract "contacts" --schema @schema.json
browser4-cli summarize --selector "#reviews"
browser4-cli agent run "Go to amazon.com, compare the first 3 keyboards, write a summary"
browser4-cli agent status agent-task-1
```

## Batch and loop automation

| Command | Description |
|---|---|
| `batch [command...]` | Execute multiple commands in one invocation. The steps run on the client — one backend call per step, through the same path as the equivalent standalone command. Supports `--bail` (stop at the first failed step) and `--json` (read the command array from stdin). Exit code 5 means at least one step failed. |
| `loop [task]` | Run a task repeatedly. Supports `--name`, `-i/--interval`, `-n/--count`, `-t/--timeout`, `--shell`, `--list`, `--pause`, `--resume`, `--pause-all`, `--resume-all`, `--stop`, `--stop-all`, `--status`, `--history`, `--keep-state`. |

Batch-compatible commands:

```text
goto, go back, go forward, reload, press, key, keyboard, type, keydown, keyup,
click, dblclick, hover, fill, select, upload, check, uncheck, drag, focus,
mousemove, mousedown, mouseup, mousewheel, scroll, wait, get, eval,
snapshot, screenshot, pdf, dialog accept, dialog dismiss, resize,
tab list, tab new, window new, tab close, tab select, frame,
htmlsnapshot, htmlsnapshot capture, htmlsnapshot get, htmlsnapshot get all
```

```bash
browser4-cli batch --bail "goto https://example.com" "snapshot" "screenshot"
browser4-cli loop "load https://example.com and extract the title" -i 300 -n 10
browser4-cli loop --shell "curl -s https://api.example.com/health" -i 60
browser4-cli loop --list
```

Every batch step is compiled and executed on the client through the same code
path as the equivalent standalone command, so a step behaves exactly as it would
on its own: it opens the session when needed, restores the focused element and
the mouse position, honours `--timeout` and `--verify`, and prints its snapshot.
The steps are **not** packed into a single backend request — each one is its own
call. A step that fails is reported on stderr as
`Batch command N failed (<command>): <reason>` and the batch continues with the
next step, unless `--bail` was given.

## Network inspection, HAR recording & request routing

Inspect what the page actually loaded (XHR/fetch calls, status codes, headers,
response bodies), record a HAR 1.2 archive importable by Chrome DevTools, and
route (mock/abort) matching requests. See
[`skills/browser4-cli/references/network.md`](../skills/browser4-cli/references/network.md)
for the full guide.

| Command | Description |
|---|---|
| `network requests` | List tracked requests. Supports `--filter`, `--type`, `--method`, `--status` (`200`, `2xx`, `400-499`), `--clear`. |
| `network request <id>` | Full detail of one request: headers, timing, and the response body (fetched on demand). |
| `network har start [--content <mode>]` | Start a HAR recording. Content mode: `none`, `text`, or `all` (binary base64). |
| `network har stop [path]` | Stop recording and print the HAR JSON, or write it to a `.har` file when a path is given. |
| `network route <pattern> --body <text>\|--abort` | Intercept matching requests (mock response or fail them) via CDP Fetch. Supports `--content-type`, `--resource-type`. |
| `network unroute [pattern]` | Remove routes; without a pattern, disable interception entirely. |

```bash
browser4-cli network requests --filter api --status 2xx
browser4-cli network har start --content text
browser4-cli network har stop ./capture.har
browser4-cli network route "**/api/users" --body '{"users":[]}' --content-type application/json
```

## Swarm and crawl for scale

The `co` prefix is accepted as an alias for `swarm`.

| Command | Description |
|---|---|
| `swarm create` | Create a parallel scraping session. Supports `--profile-mode`, `--max-open-tabs`, `--max-browser-contexts`, `--display-mode`. |
| `swarm submit [url]` | Submit URLs or X-SQL payloads as jobs. Supports `--seed-file`, `--sql`, `--deadline`, `--expires`, `--refresh`, `--parse`. |
| `swarm query <url>` | Run an X-SQL extraction job against one or more loaded pages. Supports `--sql`, `--seed-file`, `--deadline`, `--expires`, `--refresh`. |
| `swarm status <id>` | Check a swarm task status. |
| `swarm result <id>` | Fetch a completed swarm result. |
| `swarm list` | List tracked swarm tasks. |
| `swarm close` | Close the swarm session and release browser resources. |
| `crawl [url]` | Crawl from a URL or seed file (every seed must be an absolute http(s) URL — a malformed one is refused before the crawl is submitted). Supports `--seed-file`, `--sql`, `--sql-stdin`, `--sql-base64`, `--format`, `--output`, `-d/--depth`, `-ol/--out-link-selector`, `-olp/--out-link-pattern`, `-tl/--top-links`, `-a/--args`, `--refresh`, `--parse`, `--expires`, `-p/--priority`, `--page-load-timeout`, `--ignore-url-query`, `--no-norm`, `--readonly`, `--args-stdin`, `--parallel`, `--timeout`, `--verbose`, `-bg/--background`. |
| `crawl status <id>` | Check crawl task status. |
| `crawl result <id>` | Fetch crawl results. |
| `crawl cancel <id>` | Cancel a running crawl (the checkpoint is kept, so it can be resumed). |
| `crawl resume <id>` | Continue an interrupted crawl from its checkpoint: same task id, no repeat requests for URLs that already succeeded. `--retry-failed` re-fetches terminal failures; automatic resume at startup is off by default (`crawl.autoResume`). See [Crawl checkpoint & resume](crawl-checkpoint-resume.md). |
| `crawl clear` | Remove terminal-state crawl tasks; supports force-style cleanup options. Resumable checkpoints are kept until `crawl clear --all`. |
| `crawl list` | List tracked crawl tasks (`--status interrupted` shows the resumable ones). |

```bash
browser4-cli swarm create --max-open-tabs 12 --display-mode HEADLESS
browser4-cli swarm query --seed-file urls.txt --sql @query.sql --refresh
browser4-cli crawl "https://example.com" --depth 2 --out-link-selector "a[href]"
browser4-cli crawl list
browser4-cli crawl resume <task-id>    # continue an interrupted crawl
```

## WebMiner — HTML to spreadsheets, zero LLM Tokens

WebMiner runs locally against a downloaded JAR (`scent-miner.jar`) and does not need the Browser4 server. See [How to Turn HTML into Spreadsheets](../README.md#how-to-turn-html-into-spreadsheets--zero-llm-tokens) for the full pipeline.

| Command | Description |
|---|---|
| `webminer install [version]` | Download and install the webminer release to `~/.scent/webminer`, verifying the SHA-256 checksum (GitHub Releases with an OSS mirror fallback). Supports `--force`. |
| `webminer update` | Update webminer to the latest release. |
| `webminer version` | Print the installed webminer version. |
| `webminer uninstall` | Remove the installed webminer. |
| `webminer run-example` | Run the bundled example. |
| `webminer all <html-dir>` | Run the full pipeline (encode → cluster → views) on a directory of HTML files. Supports `--max-files`, `--output`, `--resume`. |
| `webminer views <result-dir>` | Rebuild the interactive views (`<project>.html`, xlsx, json) from an existing clustering result directory. |

```bash
browser4-cli webminer install
browser4-cli webminer all ./pages/
```

## Bundled skill files vs installed runtime skills

Browser4 has two different "skill" surfaces:

1. **`skills ...`** manages bundled, embedded skill documents that ship with the CLI.
2. **`skill-*`** manages installed runtime skills exposed by the backend.

### Bundled CLI skills

| Command | Description |
|---|---|
| `skills` | List bundled skill names. |
| `skills list` | Same as `skills`. |
| `skills get <name>` | Print a skill's `SKILL.md`. Supports `--full` and `--all`. |
| `skills path [name]` | Print the bundled skill directory path. |
| `skills unpack [dest]` | Unpack bundled skill files to a directory. |

### Installed runtime skills

| Command | Description |
|---|---|
| `skill list` | List installed backend skills. |
| `skill info <id>` | Show detailed skill metadata. |
| `skill install <path>` | Install a skill from a directory containing `SKILL.md`. Supports `--overwrite`. |
| `skill uninstall <id>` | Remove a skill by ID. |
| `skill reload <id>` | Reload a skill from its source directory. |

## Progressive experience memory

These commands operate on Browser4's learned experience store.

| Command | Description |
|---|---|
| `experience save <url> <trace>` | Save a task execution trace. Supports `--outcome`, `--intent`, `--task-type` (canonical types including `publish_post`), and `--facts <inline JSON \| @file.json>` — merges retrospective knowledge (`selectors` / `interaction_hints` / `known_blockers` / `anti_patterns`, camelCase or snake_case keys) into the (domain, intent) facts entry; the merge is refused when that entry is VERIFIED (immutable). |
| `experience query <url>` | Query known selectors, blockers, and hints for a URL/domain. Supports `--intent`. |
| `experience list` | List stored experience entries. Supports `--filter`, `--intent-filter`, `--page`, `--page-size`. |
| `experience deep learn <url> <intent>` | Re-derive the (domain, intent) knowledge entry from stored traces and promote it (no page access). Supports `--force`. |

## Plugins

Plugins are server-side JARs that extend Browser4. A development runtime bundle
ships the first-party plugins the checkout builds — `browser4-swarm`,
`browser4-images`, `browser4-markdown`, `browser4-media` and `browser4-seo`
(SEO is opt-in: enable it with `-Dbrowser4.plugins.enable=browser4-seo`).
`browser4-{captcha,pptx,profile-import,summary-demo}` are installed on demand:

```bash
browser4-cli plugin install "browser4-plugins/browser4-pptx/target/browser4-pptx-$(cat VERSION).jar"
browser4-cli stop    # the next command restarts the dev backend
```

Plugin tool names are the snake_case form of `domain.method`
(`image.detectImages` → `image_detect_images`) and are enumerated by
`browser4-cli tool list` (or `GET /mcp/tools`); `plugin list` lists installed plugin
JARs, not tools.

| Command | Description |
|---|---|
| `plugin list` | List installed plugins. |
| `plugin info <name>` | Show plugin details. |
| `plugin install <file>` | Install a plugin from a local JAR file. Supports `--replace`. |
| `plugin remove <name>` | Remove a plugin. Supports `-y`, `--yes`. |
| `tool list [--filter <substr>]` | List every MCP tool name the backend advertises (plugin tools included); `tool call <name> --json '{…}'` invokes one. |

## Removed commands

| Command | Replacement |
|---|---|
| `act <description>` | Removed in 4.16: the server-side `/api/act` one-shot text→tool-call translator (which reported a failed tool call as success) is gone. Use `agent run "<task>" --wait` — the CLI tool loop is the only agent engine. |

