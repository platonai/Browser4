---
name: browser4-cli
title: "Browser Automation with browser4-cli"
description: "Automates browser interactions for web testing, form filling, screenshots, and data extraction. Use when the user needs to navigate websites, interact with web pages, fill forms, take screenshots, test web applications, or extract information from web pages."
tags:
  - browser
  - automation
  - testing
  - cdp
  - snapshot
allowed-tools: Bash(browser4-cli:*)
tier: decision
---

# Browser Automation with browser4-cli

Browser automation CLI for AI agents — Chrome/Chromium via CDP with accessibility-tree snapshots.

This is the **decision layer**: the core loop, the command map, and which reference to open. Every
topic links to a deep dive — load only what the task needs ([§7 Reference Map](#7-reference-map)).

### Invocation

After installing (`browser4-cli install`), invoke commands directly — the docs use `browser4-cli` as the generic command name:

```bash
browser4-cli open --headless <url>
```

## 1. Core Loop

> **⚡ First-run latency:** From a source tree, the first launch builds the runtime bundle via Maven (~1–3 min, before the spinner appears) and then starts the Browser4 backend (Spring Boot + JVM, ~10s). Subsequent commands are instant — the server stays alive between invocations.

> **🖥️ Headless mode is the default for AI agents:** Always open browsers with `--headless` unless the user **explicitly** asks to see the browser window ("show me the browser", "open visibly", "I want to watch", or "headed"), a human must act on the page, or the site blocked the headless browser — in that last case retry `--headed` **once** (§2 Display Mode).

> **🛑 Dev mode never serves a stale backend.** From a source tree the backend must be the checked-out code. An already assembled runtime bundle is reused as-is while it matches the checkout. If it was built from a **different project version**, the command **fails with a non-zero exit** and names the reason, the bundled/checked-out versions, the bundle's build time and the rebuild command — nothing is started. If the checkout's sources merely look **newer than the bundle jars**, the command rebuilds from source and then starts (that signal cannot tell a real source change from a touched-but-unchanged tree, so refusing on it would block startup on a false positive). Rebuild with `powershell -ExecutionPolicy Bypass -File browser4-apps/browser4-bundle/build-runtime-bundle.ps1` (`pwsh -File …` on Linux/macOS) or re-run with `BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1`. To deliberately test the older backend, set the single opt-out `BROWSER4_CLI_ALLOW_STALE_BUNDLE=1` — the warning stays, and `status` / `doctor` keep reporting the skew (`local_bundle` in `--json`).

```
1. OPEN        browser4-cli open --headless <url>   # headless by default for AI agents
              browser4-cli goto <url>               # or goto to navigate within existing session
2. SNAPSHOT    browser4-cli snapshot -v 0           # capture accessibility tree (viewport 0 = current visible screen)
3. INTERACT    browser4-cli click <ref>             # use refs from the snapshot
              browser4-cli fill <ref> <value>
              browser4-cli press Enter
4. RE-SNAPSHOT browser4-cli snapshot -v 0 --auto-diff # verify what changed (diff vs previous)
5. EXTRACT     browser4-cli htmlsnapshot get ...      # or eval, or X-SQL (see §4)
```

### Copy-Paste Template

```bash
browser4-cli open --headless "https://example.com"  # headless by default for AI agents
browser4-cli snapshot -v 0 --stdout       # read the page; note refs
browser4-cli fill <ref> "<value>"         # interact
browser4-cli press Enter
browser4-cli wait --load networkidle   # prove the network settled — nothing more
browser4-cli wait "<result-selector>"  # poll the element carrying the result (late-rendered pages)
browser4-cli snapshot -v 0 --auto-diff --stdout  # verify what changed
browser4-cli htmlsnapshot get all text "<css-selector>"   # extract from the live page
```

For quick inline viewing without opening a file, add `--stdout` to any snapshot command.

## 2. Key Concepts

### Element Refs

After commands that modify browser state, browser4-cli saves an **accessibility-tree snapshot** — a YAML file showing the page structure:

```yaml
- generic [ref=e7]:
  - link "News" [ref=e191]: /url: https://example.com/news
  - textbox "Search query" [ref=e35]
  - button "Search" [ref=e25]
```

Each interactive element has a **ref** (`e5`, `e12`) — the element's Chrome DevTools Protocol backend node ID prefixed with `e` (so `e12345` refers to backend node 12345). Use refs to target elements in `click`, `fill`, `type`, `get attr`, etc.

> **Note:** `/url` fields may be **relative** (e.g. `/url: news`). The snapshot output includes the page URL at the top for resolution. Add `-u` / `--urls` to include link hrefs, and `-b` / `--brief` when you only need the page URL and title. For absolute URLs after redirect resolution use `htmlsnapshot get all attr "a[href]" href`.

### Ref Lifecycle

Refs are **ephemeral** — treat them as single-use handles:

- **Always re-snapshot after interactions:** `click`, `fill`, `type`, `press`, `check`, `uncheck`, `select`, `hover`, `drag`, `dblclick`.
- **Definitely re-snapshot after page/context changes:** `goto`, `reload`, tab switches, or clicks that navigate/update the page. **If you are chaining form actions:** rely on the automatic post-action snapshot, then use refs from that fresh snapshot for the next step.
- **Interactions auto-scroll** the target into view, so a later `snapshot -v 0` can start mid-page (nonzero `hiddenTopHeight`) — `eval "window.scrollTo(0,0)"` or capture `-v all` when documenting the top of a page.

**In practice, the safest loop is interact → re-snapshot → use new refs.** Interaction commands capture an automatic snapshot after execution; pass `--no-snapshot` to skip it when you plan to capture a fresh snapshot manually (saves a round-trip).

### Output Modes

- **Default** — human-readable output on stdout.
- **`--show-tip` / `-tip`** — show a relevant, rotating tip on stderr after each successful command. Tips are suppressed by default; use this flag to enable them.
- **`--json`** — single-line JSON envelope on stdout for commands that support structured output. This is the clean machine-readable mode for commands such as `tab-list`, `htmlsnapshot get`, `htmlsnapshot query`, and `eval`. **Exception:** `snapshot` remains YAML-focused and warns on stderr instead of returning JSON snapshot data.
- **File output (default for AI commands)** — `extract` and `summarize` save their result to a timestamped file in `.browser4-cli/snapshot/` and print only a link; add `--stdout` (or `--raw`) to print the payload directly. When `extract --schema` is used, the requested schema fields are emitted as plain **top-level JSON** (in the file and on stdout) — no envelope to parse.
- **`--quiet` / `-q`** — suppress all normal output; only errors appear on stderr.
- **Global flags after the command** — `-q` / `--quiet` and `--timeout <secs>` / `--timeout=<secs>` are also accepted *after* the command name (they are hoisted into the global flags): `browser4-cli htmlsnapshot -q`, `browser4-cli webdb export "url1,url2" ./out --timeout 30`.  Two exceptions: `--json` after the command belongs to the command (`batch --json` reads JSON from stdin) and is never hoisted, and `--timeout` is hoisted only when the command does not define its own — `wait --timeout` stays in milliseconds.  A global flag that still lands as a stray positional is rejected with the hint "global flags must appear before the command".

### Display Mode (Headless vs Headed)

| Mode | Flag | Window | Use case |
|------|------|--------|----------|
| **Headless** | `--headless` | No GUI window | **Default for AI agents** — scraping, automation, CI/CD, servers |
| **Headed** | `--headed` | Visible window | Debugging, demonstration, a human must act, or the one-shot bot-detection retry |

Use `--headed` for exactly three reasons: (1) the user explicitly asks for a visible browser; (2) a human must act on the page (login, CAPTCHA, QR code, 2FA); (3) **anti-bot escalation**.

> **Choosing the whole setup** (session × display × browser source — including
> when to attach to your own browser instead of launching one): see
> [browser-modes.md](references/browser-modes.md).

**Rule for AI agents: always use `--headless` by default.** Use `--headed` only when the user **explicitly** requests a visible browser ("show me the browser", "I want to see", "open visibly", "headed", "watch what happens"). If user participation in the page interaction is required — e.g., logging in or entering a verification code (CAPTCHA) — open a **headed** browser so the user can see and act on the page.

**Anti-bot escalation — retry once:** when a page looks blocked (CAPTCHA/verification widget, Cloudflare/DataDome/Akamai/PerimeterX challenge, Google `/sorry/`, "unusual traffic" / "access denied", or an implausibly empty body), `close` then `open --headed` **with the same `-s <name>`** so profile and cookies survive, retry the same step **once**, and **tell the user the mode was switched**. If the headed retry is blocked too, stop — the block is fingerprint/IP-level: prefer `attach --cdp` / `attach --extension` (a real logged-in profile), raise `--interact-level`, or report the site as unreachable. GUI-less environments (CI, Docker) degrade `--headed` to headless — say the retry ran headless instead of claiming a visible window. `goto`/`open` run a one-call probe after a successful navigation and print this escalation as an **advisory stderr warning** (naming the signature that fired) when the landed page matches a challenge URL or body marker; the command still succeeds, so act on the warning yourself rather than waiting for a failure (`--json` reports it as `challenge_detected` / `challenge_signature`).

Set the display mode with `open` when starting a **new** session; `goto` does not accept `--headless`/`--headed` — it inherits the session's mode:

```bash
browser4-cli open --headless https://example.com     # headless (preferred default)
browser4-cli open --headed https://example.com       # headed (only when user asks)
browser4-cli goto https://other-page.com             # stays headless (or headed) as set by open
```

> **Notes:** When `goto` is the very first command (no prior `open`), it auto-opens a new session using the CLI's default display mode, which is headless. `--headless`/`--headed` only take effect when **creating** a new session — when `open` reconnects to a running session they are ignored (the CLI warns on stderr). To change the mode, `close` first, then `open --headless`. Use `open --fresh` to discard a stale session's tabs/cookies/location entirely and start clean.

### Sessions

Named sessions isolate browser state (cookies, localStorage, tabs) in a **dedicated browser profile directory** keyed by the session id — reopening always restores the same profile. Use `-s <name>` to target a named session; `goto` auto-opens/reconnects. `list` shows a "Next open" column: **Reuse** (reconnects to the active window) or **Refresh** (opens fresh — session stale/missing).

> **Concurrent runs — always pass `-s <name>`:** the unnamed DEFAULT session is a singleton shared by every invocation that omits `-s` (last writer wins), so parallel agents navigate each other's pages. Give each run its own `-s job-42`.

Two on-disk locations — don't confuse them:

- **Session state** lives in `~/.browser4` by default (per checkout in development mode — see **Development Mode** below), falling back to `./.browser4-cli-state` when unwritable; override with `BROWSER4_CLI_STATE_DIR` / `BROWSER4_RUNTIME_DIR`.
- **Snapshots** (accessibility-tree captures from `snapshot` / `htmlsnapshot`) live in `./.browser4-cli/snapshot/`, relative to the directory the command runs in — the same directory the `extract` / `summarize` file output uses. Look there when hunting saved snapshot files or cleaning up capture artifacts.

### Configuration

CLI defaults (`config.json`: `server`, `timeout`, `proxy`, `session`) and server-side runtime overrides are managed by the `config` command family — `config list` prints every value, `config set server <url>` pins a backend; see **[config.md](references/config.md)** for the full key reference.

### Development Mode (one backend per checkout)

Running the CLI from inside a Browser4 checkout (a directory holding `ROOT.md` + `pom.xml`) enables **development mode**: each checkout owns a backend port (first free from **8282** upward), a state namespace (`~/.browser4/workspaces/<checkout>-<hash>/`) and a backend app data root, so parallel checkouts and git worktrees run side by side — `stop` then stops only this checkout's backends. Full matrix, shared paths and escape hatches: **[development-mode.md](references/development-mode.md)**.

### Tab Management

Tab commands (`tab-list`, `tab-new`, `tab-select`, `tab-close`, `window new`) scope to a session. **Re-snapshot after `tab-select`** — tab switches change the active page context. See **[tab-management.md](references/tab-management.md)** for the workflow (quick start, recipes, error recovery) and **[tabs.md](references/tabs.md)** for the command behaviour (GUID forms, `--json` envelope, tab insert position, the last-tab rule, extension-session quirks).

### Frame Switching (iframes)

Element commands (`click`, `fill`, `type`, …) resolve CSS selectors against the **main document** by default. For iframe content, switch first: `frames` lists the frame tree; `frame "<target>"` switches (element ref, CSS selector, frame name/id, or URL fragment — nested frames by switching repeatedly); `frame main` returns. Scope resets on navigation. **Same-origin iframes are fully supported**; cross-origin ones fail with an actionable error. `eval` always runs in the main document. See **[frames.md](references/frames.md)** for details.

## 3. Command Map

| Command family | Purpose | When to use | Full reference |
|---------------|---------|-------------|----------------|
| `goto`, `open`, `close`, `close-all`, `reload` | Navigation & session management | Every session starts here; `close-all` cleans up every session | — |
| `snapshot` | Capture accessibility tree (AXTree) with element refs | **Page structure & interaction** — find elements to click, fill, etc. Use `snapshot` when you need refs (e5, e36) to interact with. | [snapshot.md](references/snapshot.md) |
| `snapshot grep` | Search the page's AX tree with regex (live, no prior capture) | Find elements by text or pattern. Patterns are **Rust regex** — `\|` is alternation, and a literal `$` is safest written `[$]` (`-i`, `-A/-B/-C`, `-F`, `-v`, `-c`, `-l` supported) | — |
| `click`, `dblclick`, `drag`, `hover`, `mousemove`, `fill`, `type`, `press`, `select`, `check`, `generate-locator` | Page interaction | Form filling, button clicks, mouse actions, navigation. Clicks/hovers move the pointer onto the element first; `mousemove 5 5` clears a lingering `:hover`. `type --method auto\|chars\|exec` (needs a target ref) bulk-inserts long (>150 chars) or multi-line text in one `execCommand('insertText')` instead of typing per character | — |
| `upload <ref> <file> [file...]` | Upload local files to a page file input | Send attachments/photos/documents to an `<input type="file">`; the target must be a file input, paths must be readable on the machine running the browser | [upload.md](references/upload.md) |
| `focus`, `key`, `keyboard` | Focus an element / press a key (key & keyboard alias `press`) | Explicit focus before typing, agent-browser-style keypresses | — |
| `is visible\|enabled\|checked <sel>` | Element state assertions | Verify visibility, enabled-ness, or checked state before acting | — |
| `dialog-accept`, `dialog-dismiss`, `dialog-status` | Native JS dialog handling | After clicking buttons that trigger alert/confirm/prompt; `dialog-status` inspects the pending dialog. The triggering click parks server-side until the dialog is handled, so run the dialog command in a **separate** invocation — `dialog-accept "text"` fills a prompt, or `click --auto-dismiss-dialogs <ref>` auto-accepts in one step | — |
| `htmlsnapshot get`, `get all` | Extract `text` / `textcontent` / `html` / `attr` via CSS selectors from the **live page** (no prior capture) | **Page content & text extraction** — get article text, headings, attributes. Prefer `textcontent` when `text` looks truncated (CSS overflow clips `text`) | [htmlsnapshot.md](references/htmlsnapshot.md) |
| `get <mode> <selector> [name]` | **Live-DOM single-element read** (`text`, `html`, `box`, `styles`, `property`, `attr`) — capture-free, works on the current live page with refs or CSS selectors (`--raw` keeps text verbatim) | **Post-interaction verification & quick reads** — "did the submit work?" without a capture round-trip. Value contract: a matched element returns its value — or `""` when the attribute/property is absent; `null` means the selector matched nothing; an unresolvable `eN` ref errors explicitly | — |
| `htmlsnapshot readability` | One-step article extraction via a Readability-style heuristic (no LLM, no selectors) | Get the main article (title, byline, text) from the stored snapshot in one call; `htmlsnapshot readability <url>` fetches a page independently | [htmlsnapshot.md](references/htmlsnapshot.md) |
| `htmlsnapshot query` | X-SQL queries for structured extraction | Multi-field, filtered, sorted data | [x-sql.md](references/x-sql.md) |
| `eval` | Execute JavaScript in the page (`--await` for Promises/fetch, `--wait-selector` for late-rendered content, `--file`/`--stdin`/`--base64` to dodge shell quoting) | Live DOM access, complex transforms | [eval.md](references/eval.md) |
| `eval --ref` | Execute JS scoped to a specific element | Element property extraction (text, attrs, styles) | **⚠️ Expression MUST be an arrow function: `element => element.textContent`** |
| `scrollintoview`, `pushstate`, `highlight` | Element scroll / history / visual highlight (eval-based shortcuts) | Scroll an element into view, push a history entry, outline an element | — |
| `vitals`, `web-vitals` | Core Web Vitals measurement (LCP, CLS, INP, FCP, TTFB) via injected web-vitals lib | Performance checks on a live page (needs network for CDN) | — |
| `set geo\|offline\|headers\|media\|device` | CDP emulation: geolocation, offline mode, extra headers, color scheme, device metrics | Emulate locations, networks, devices, and media features | — |
| `errors` | Console errors only (alias of `console --min-level error`) | Surface page JS errors fast | — |
| `extract`, `summarize`, `agent run` | AI-powered extraction | Natural language extraction (needs LLM key) | [agent.md](references/agent.md) |
| `crawl` | Recursive crawling + bulk extraction | Multi-page traversal, seed-file processing | [crawl.md](references/crawl.md) |
| `swarm` | Parallel scraping across browser contexts | High-throughput extraction | [swarm.md](references/swarm.md) |
| `loop` | Repeated task execution with persistence | Monitoring, scheduled checks | [loop.md](references/loop.md) |
| `state-save`, `state-load`, `cookie-*`, `*-storage-*` | Browser storage management | Auth state reuse, cookie manipulation | [storage-state.md](references/storage-state.md) |
| `attach` | Connect to existing Chrome/Edge via CDP | Debug live browser, reuse auth — after attaching, check the printed actual browser; a ⚠ warning flags a channel mismatch | [attach.md](references/attach.md) |
| `webdb export`, `webdb normalize` | Export cached pages, normalize URLs to database keys | Post-crawl content extraction, URL key lookup | [webdb.md](references/webdb.md) |
| `skills`, `skills get`, `skills path`, `skills unpack` | Bundled AI agent skill files | Refresh agent instructions, unpack skill files | [skills.md](references/skills.md) |
| `skill-list`, `skill-info`, `skill-install`, `skill-uninstall`, `skill-reload` | Backend skill management | Install/manage server-side skills | [skills.md](references/skills.md) |
| `screenshot`, `scroll`, `wait`, `resize` | Visual capture & viewport control | Screenshots, viewport sizing, scroll control; `wait --download` polls a download directory | — |
| `tab-list`, `tab-new`, `tab-select`, `tab-close`, `window new` | Tab & window management | Multi-tab workflows, session-scoped tab operations | [tab-management.md](references/tab-management.md) |
| `frames`, `frame <target>`, `frame main` | Iframe frame switching | Interact with content inside `<iframe>`s: `frame "#pay-frame"` then `fill`/`click`/`is visible` resolve inside that frame; `frames` lists the frame tree | [frames.md](references/frames.md) |
| `diff snapshot` | Unified diff between two saved accessibility snapshots | Verify what changed between interactions (`snapshot --auto-diff` equivalent on saved files) | — |
| `download`, `wait --download` | Download management | `download --dir <path>` configures the browser download folder; `wait --download` blocks until a download completes | — |
| `network requests`, `network request <id>`, `network har start`, `network har stop`, `network route`, `network unroute` | Network request inspection, HAR recording & request routing | Inspect what the page loaded (XHR/fetch/status/headers), debug API calls, record a `.har` file (Chrome DevTools importable), or mock/abort matching requests (Fetch interception). `network requests --filter api --status 2xx`; `network har start --content text` then `network har stop ./capture.har`; `network route "**/api/users" --body '{"users":[]}'` | [network.md](references/network.md) |
| `profiler start`, `profiler stop` | V8 CPU profiling via CDP | Profile page interactions and save `.cpuprofile` (Chrome DevTools / speedscope compatible) | — |
| `profiles list` | List browser profile directories | See what profiles exist under `~/.browser4/browser/chrome` before `open --profile` | — |
| `profile-import` | Import bookmarks/history/passwords/cookies/extensions from system Chrome/Edge/Safari (requires the browser4-profile-import plugin) | `profile-import --list-sources` to discover browsers; `profile-import --source chrome --data bookmarks,cookies` copies a whole profile snapshot to `~/.browser4/imports/`; `--into prototype|default` seeds a managed profile dir; then `open --profile <dir>` mounts it | [browser-state-import.md](references/browser-state-import.md) |
| `config` | Persistent CLI defaults (server, timeout, proxy, session) | Set default server URL, timeout, proxy, or session name | [config.md](references/config.md) |
| `status`, `doctor`, `doctor log`, `doctor metrics`, `doctor status` | Server health & diagnostics | `doctor status` prints the aggregated status report (health, build, runtime, LLM, sessions, browsers, swarm, plugins, skills, metrics, logs) in layers: summary by default, `--verbose` for full detail, `--section <name>` for one report, `--json` for machine-readable output. `status` prints the web status panel URL (`http://<server>:8182/status`) — a live dashboard of the same reports; `http://<server>:8182/pages.html` shows every open page | — |
| `batch` | Run several commands in one invocation | Scripted multi-step flows, fewer round-trips | [quickstart.md](references/quickstart.md) |
| `console`, `cdp`, `pdf`, `page-info`, `go-back`, `go-forward`, `keydown`, `keyup`, `mousedown`, `mouseup`, `mousewheel`, `snapshot list`, `snapshot clean`, `crawl status\|result\|cancel\|clear\|list`, `swarm submit\|status\|result\|list\|close`, `chat`, `session-default`, `delete-data`, `kill-all`, `stop`, `uninstall`, `plugin-*` | Remaining command families (not covered here) | Discover with `browser4-cli help` / `browser4-cli help <command>` | — |
| `experience save`, `experience query`, `experience list`, `experience deep learn` | Progressive experience memory | Reuse selectors, extraction patterns and blocker awareness across sessions — see the sibling skill | [browser4-experience](../browser4-experience/SKILL.md) |

### Refreshing This Skill

The `skills` command retrieves bundled skill content that always matches the installed CLI version. Use it to get current instructions rather than relying on cached copies:

```bash
browser4-cli skills                         # List all bundled skills
browser4-cli skills get browser4-cli        # Print this SKILL.md
browser4-cli skills get browser4-cli --full # Include all reference files
browser4-cli skills path                    # Print skills directory path
browser4-cli skills unpack                  # Unpack bundled skill files to disk
```

Skill files are unpacked automatically during `browser4-cli install` (and refreshed by `upgrade`);
unchanged files are skipped, so re-running is cheap. `install` / `upgrade` also copy the bundled
skills into `~/.agents/skills` so agents (e.g. Codex) load them automatically. Overrides:
`BROWSER4_SKILLS_DIR`, `BROWSER4_AGENTS_SKILLS_DIR`.

## 4. Decision Trees

What kind of work is this? Pick the branch first — it names the command family; the
expanded trees, the comparisons behind them and the X-SQL quickstart template live in
**[decision-trees.md](references/decision-trees.md)**.

```text
What do you need to do?
|- act on a page (click, fill, press, upload) ..... snapshot -> act on refs -> re-snapshot
|- read content off the live page ................. htmlsnapshot get / get all / query
|- compute something in page JS ................... eval          (--ref takes an arrow function)
|- understand a page, or find selectors ........... htmlsnapshot inspect | summary
|- fetch many known or linked pages ............... crawl         (--seed-file, --depth N)
|- the same task across many URLs, in parallel .... swarm
|- repeat on a schedule ........................... loop
`- structure pages you already have ............... webminer all  (< 1,000 pages, no tokens)
```

- **4a. Extraction method:** interact → `snapshot` + refs; read content → `htmlsnapshot`; live DOM → `eval --json`; natural language → `extract`; many pages → `crawl`/`swarm`. **`htmlsnapshot` reads the LIVE page — no prior capture is needed** for `get`/`get all`/`inspect`/`summary`/`grep`/`export`/`query`; an empty read means the selector did not match (or no page is loaded), not a missing capture — per-command matrix in [decision-trees.md](references/decision-trees.md).
- **4b. Bulk/scale:** one list page → `query`; known URLs → `crawl --seed-file`; follow links → `crawl <url> --depth N`; more crawl overlap → `crawl --parallel 8` (each unit collects on its own tab); parallel → `swarm`; scheduled → `loop`.
- **4c. Query granularity:** `get` = first match; `get all` = all matches (unaligned arrays — don't combine); `query` = correlated multi-field rows.
- **4d. Structuring pages (WebMiner):** `< 1,000 pages` → `webminer all` (free, local, zero tokens); `> 1,000 pages` → WebMiner Commercial (Spark). Acquire pages first with `crawl`/`swarm`, then feed the HTML directory in.
- **4e. X-SQL quickstart:** `SELECT DOM_FIRST_TEXT(DOM,'h2') AS title ... FROM DOM_LOAD_AND_SELECT(@url, '.product-card')` — single quotes for CSS, `@url` unquoted, no JOIN/CTE/subqueries; run via `--sql @file.sql`.

## 5. Critical Warnings

> **Refs are single-use.** Re-snapshot after any interaction, and always after `goto`, `reload`, and tab switches. Never reuse a ref across navigations.

> **Selectors break when sites change their HTML.** Discover them with `htmlsnapshot inspect` / `summary` before extracting. Treat scenario examples as patterns, not copy-paste recipes.

> **Shell quoting on Windows.** Complex JS/SQL with nested quotes breaks inline: prefer `--sql @file.sql`, `--sql-stdin`, `--sql-base64`, `eval --file` / `--stdin` / `--base64`; for `htmlsnapshot inspect` use `@file`, `--stdin`, or `--selector-base64`. **On PowerShell always quote `@file` paths (`--sql "@query.sql"`)** — an unquoted `@` is the splatting operator. Never inline `--sql "…"` with double-quoted CSS selectors. See [shell-quoting.md](references/shell-quoting.md).

> **Don't cat snapshot files** — they can exceed 256KB, and `--stdout` can dump 63KB+ trees. Bound the capture with `-v 0`, `--depth`, `--selector`, `--no-boxes`, or use `snapshot grep` / `htmlsnapshot`. `snapshot --stdout`, `htmlsnapshot get html` and `grep` paginate at 2000 lines by default (`--all` / `--page-size 0` disables); `get all …` and `text`/`textcontent` reads print in full.

> **`eval --ref` requires an arrow function:** `element => element.textContent`. Writing `element.textContent` (or `this.textContent`) returns `null` — the #1 mistake with element-scoped eval.

> **`wait --load networkidle` is not "the page is ready".** It proves only that the network went quiet: a page whose results are computed by page JS after load (dashboards, bot-detection verdicts), or fetched by a long-running XHR, can still be empty when it reports success — and the CLI now says so. For result pages, poll the result element first (`wait "<result-selector>"`, or `eval --wait-selector <css>`); treat `wait --load networkidle` as a network-settling extra step.

> **Sandboxed environments:** the backend writes its logs inside the runtime bundle, so a workspace-only sandbox makes `open`/`goto` hang until the startup timeout with `Access denied` (see the startup log path under `🧾 Details`). Fix before the first launch: point `BROWSER4_RUNTIME_DIR` and `BROWSER4_CLI_STATE_DIR` at writable directories.

> **`get` value contract:** a matched element returns its value — or `""` when the attribute/property is absent; `null` means the selector matched nothing; an unresolvable `eN` ref fails with an explicit error.

> **Snapshot modes — when to use `-v 0` vs `-i` vs default:**
>
> | Mode | What it shows | Best for |
> |------|--------------|----------|
> | `snapshot` (default) | Full AX tree with all element refs | General exploration, first look at a page |
> | `snapshot -v 0` | Current visible screen (a single screen-height viewport chunk) | Long pages — read one chunk at a time to keep output small. Use `-v all` for the entire page |
> | `snapshot -i` | **Interactive-oriented layout** — inner text is aggregated into the enclosing element's name so each ref line reads as a self-contained target | Quick orientation before acting via refs; form-heavy pages. Pair with `-v 0` (`snapshot -i -v 0`) for one focused screenful |
> | `htmlsnapshot` | Static HTML (CSS selectors) | Content extraction (text, attributes), when you need CSS selectors instead of AX refs |
>
> **`-i` does not shrink the tree:** it aggregates text into element names — addressable headings, paragraphs and generic containers all remain. It changes the layout, it does not reduce the tree to buttons/links; use `htmlsnapshot` when you need CSS-selector extraction instead of refs.

## 6. Quick Patterns & Deep Dives

Copy-paste pairs for the common flows — form fill, `snapshot grep`, mouse/drag, dialog handling,
verify-after-interaction, single-field and bulk extraction, PowerCSS, agent tasks:

- [quick-patterns.md](references/quick-patterns.md) — the recipe catalog.
- [quickstart.md](references/quickstart.md) — the compressed resident quick reference.
- [decision-trees.md](references/decision-trees.md) — extraction decision trees, expanded.
- [htmlsnapshot-scenarios.md](references/htmlsnapshot-scenarios.md) — end-to-end walkthroughs; focused variants: [advanced](references/htmlsnapshot-scenarios-advanced.md), [amazon](references/htmlsnapshot-scenarios-amazon.md), [audit](references/htmlsnapshot-scenarios-audit.md), [extraction](references/htmlsnapshot-scenarios-extraction.md).

Proven copy-paste recipes — full walkthroughs in **[quick-patterns.md](references/quick-patterns.md)**:

1. **Multi-Session Workflow** — `-s <name>` isolates state; `list`/`close`/`close-all` manage sessions
2. **Interactive Form Fill** — open → snapshot → fill refs → submit → `wait --load networkidle` → verify
3. **Find Elements by Text** — `snapshot grep [-i] [-A 3 -B 1] "pattern"`
4. **Mouse Interactions** — `hover`, `dblclick`, `drag`; verify with `snapshot grep`
5. **Dialog Handling** — `dialog-accept`/`dialog-dismiss` in a separate invocation (or `click --auto-dismiss-dialogs <ref>`)
6. **Verifying Results** — `snapshot -v 0 --auto-diff --stdout` after every interaction; `generate-locator` for resilient selectors
7. **Static Data Extraction** — `get text` / `get attr "<css>"` read the live page (no capture needed)
8. **Bulk Extraction (X-SQL)** — correlated fields via `--sql @query.sql` with `DOM_LOAD_AND_SELECT(@url, '.product-card')`
9. **PowerCSS** — `:expr()` visual-feature selectors; full reference in [power-dom.md](references/power-dom.md)
10. **Agent Task Lifecycle** — `agent run` (async) → `status` → `result`; or `--wait [--wait-timeout]`
11. **Agent Memory** — run-start `## Memory` recall, `memory_note`, `memory_search`/`read`/`forget`, auto-deposit
12. **Typing text (`type`)** — `type "text" <ref>`; add `--method auto|chars|exec` (requires a target ref): `auto` (default) types short text per-character and switches to a one-shot `execCommand('insertText')` bulk insert for long (>150 chars) or multi-line text on textarea/contenteditable; `chars` forces per-character typing; `exec` forces the bulk insert. `--verify` keeps its strict read-back semantics for tool callers.
13. **File Upload** — `upload <ref> <file> [file...]` uploads one or more local files to a page file input (`<input type="file">` only); the paths must be readable by the browser process (remote backend: resolved on the backend host). Multi-file, absolute paths, `--no-snapshot` supported; see [upload.md](references/upload.md).

**Resilient selectors (PowerCSS):** `div:expr(width > 400 && height > 400)` filters by computed
geometry/position/content density instead of class names, so it survives markup changes — usable in
any CSS selector and in `htmlsnapshot get` / X-SQL selectors. Feature list and operators:
[power-dom.md](references/power-dom.md); bridging refs to selectors: [css-selector-bridge.md](references/css-selector-bridge.md).

**AI-powered extraction:** `agent run "<task>"` submits an async task — poll `agent status <id>` and
read `agent result <id>`. A finished task reports `"processState": "completed"` (older payloads used
`"done"`), so **`isDone: true` is the reliable completion check**; `agent list` shows all tracked
tasks. `extract` / `summarize` are the synchronous variants. See [agent.md](references/agent.md).

## 7. Reference Map

Organized by task — follow the link that matches what you're trying to do:

**Start here (distilled core):** [quickstart.md](references/quickstart.md) — distilled resident quick reference (core loop, copy-paste template, key commands, snapshot vs htmlsnapshot, critical warnings); embedded in the CLI engine's system prompt — full details live in this SKILL.md.

**Interact with pages (accessibility tree & element refs):** [snapshot.md](references/snapshot.md) — `snapshot`, `snapshot grep`, `-v` viewport paging, `--auto-diff`, `-i` interactive mode, element refs
[tabs.md](references/tabs.md) — tab command reference: GUID forms and prefixes, `--json` envelope, tab insert position, last-tab rule, extension-session quirks
[upload.md](references/upload.md) — upload local files to a page `<input type="file">` (multi-file, `--no-snapshot`, browser-host path rules)

**Extract data from pages:**
[htmlsnapshot.md](references/htmlsnapshot.md) — `get`, `get all`, `query`, `grep`, `summary`, `inspect`, `export`
[eval.md](references/eval.md) — `eval`, `eval --ref`: live-DOM JavaScript evaluation (inline/`--file`/`--stdin`/`--base64`, `--await`, `--wait-selector`, `--json`, arrow-function rule)
[x-sql.md](references/x-sql.md) — X-SQL function reference (DOM, STR, ARRAY namespaces); sub-references: [x-sql-dom-functions.md](references/x-sql-dom-functions.md), [x-sql-dom-load-select.md](references/x-sql-dom-load-select.md), [x-sql-dom-select-functions.md](references/x-sql-dom-select-functions.md), [x-sql-string-functions.md](references/x-sql-string-functions.md), [x-sql-array-functions.md](references/x-sql-array-functions.md)
[htmlsnapshot-scenarios.md](references/htmlsnapshot-scenarios.md) — end-to-end recipes; focused variants: [advanced](references/htmlsnapshot-scenarios-advanced.md), [amazon](references/htmlsnapshot-scenarios-amazon.md), [audit](references/htmlsnapshot-scenarios-audit.md), [extraction](references/htmlsnapshot-scenarios-extraction.md)
[decision-trees.md](references/decision-trees.md) — choosing extraction method, bulk/scale approach, query granularity, WebMiner, X-SQL quickstart
[quick-patterns.md](references/quick-patterns.md) — proven interact/verify/extract copy-paste recipes

**Run at scale (multiple pages/URLs):**
[crawl.md](references/crawl.md) — recursive crawling, seed-file bulk fetch, X-SQL extraction
[swarm.md](references/swarm.md) — parallel scraping across multiple browser contexts
[loop.md](references/loop.md) — repeated task execution with persistence/resume

**Manage browser state:**
[storage-state.md](references/storage-state.md) — cookies, localStorage, sessionStorage, state save/load
[browser-state-import.md](references/browser-state-import.md) — copy system browser state into Browser4-managed sessions
[webdb.md](references/webdb.md) — export cached pages, normalize URLs for database lookups
[attach.md](references/attach.md) — connect to existing Chrome/Edge via CDP
[tab-management.md](references/tab-management.md) — multi-tab workflows: quick start, recipes (GUID targeting, cross-session, windows), flags, error recovery

**Choose how the browser runs:**
[browser-modes.md](references/browser-modes.md) — session (default / named / swarm) × display (headless / headed / SUPERVISED) × browser source (managed / `attach --cdp` / `attach --extension`), plus the headless→headed escalation when a site blocks the bot, profile mode, interact level, contexts, and their failure modes

**Manage skills & configuration:**
[skills.md](references/skills.md) — bundled skill files, backend skill management
[config.md](references/config.md) — `config` command family: CLI defaults and server-side runtime overrides
[development-mode.md](references/development-mode.md) — one backend per checkout: development ports from 8282, per-checkout state and app data, workspace-scoped `stop`

**AI-powered extraction:** [agent.md](references/agent.md) — `extract`, `summarize`, `agent run|status|result`, LLM provider config

**Resilient selectors:**
[power-dom.md](references/power-dom.md) — PowerCSS `:expr()` visual-feature selectors
[css-selector-bridge.md](references/css-selector-bridge.md) — bridging snapshot refs to CSS selectors

**Configure fetching:**
[load-options-guide.md](references/load-options-guide.md) — cache control, quality requirements, interaction, portal crawling
[load-options-decision.md](references/load-options-decision.md) — choosing LoadOptions (decision tree)

**Troubleshoot:** [shell-quoting.md](references/shell-quoting.md) — avoid shell-quoting breakage for complex JS/X-SQL on Windows / Git Bash

## 8. Installation

```bash
npm install -g browser4-cli && browser4-cli install     # Node.js available
irm https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.ps1 | iex   # Windows
curl -fsSL https://browser4.oss-cn-beijing.aliyuncs.com/scripts/install-browser4-cli.sh | bash  # Linux/macOS
```

The bootstrap scripts also install the Browser4 backend (runtime bundle) automatically:
`browser4-cli install` on a fresh machine, or `browser4-cli upgrade` when a backend already exists.
Those two subcommands accept only `--tag` and `--force`; to install the CLI **only**, pass
`--skip-backend` (`-SkipBackend` in PowerShell) to the install script — the CLI forwards that flag
to the script internally.
