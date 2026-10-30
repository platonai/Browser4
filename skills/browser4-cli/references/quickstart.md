---
title: "browser4-cli Quick Reference (distilled)"
description: "Use when you need the compressed resident CLI quick reference embedded in the engine prompt: core loop, key commands, snapshot vs htmlsnapshot, and critical warnings. Details live in the source SKILL.md."
tier: decision
x-role: distilled
source: ../SKILL.md
---

# browser4-cli Quick Reference (distilled)

> Resident mini-version: embedded in the CLI engine's system prompt and stays
> usable after compression. For full detail, load `system.skillDoc("SKILL.md")`
> or `system.skillDoc("<topic>.md")`. If this document was compressed, reload
> SKILL.md when you need details.

## Core Loop

```
1. OPEN        browser4-cli open --headless <url>   # headless is the AI agent default
2. SNAPSHOT    browser4-cli snapshot -v 0           # read the page, get refs (e5/e12...)
3. INTERACT    browser4-cli click <ref> | fill <ref> "<value>" | press Enter
4. RE-SNAPSHOT browser4-cli snapshot -v 0 --auto-diff   # verify changes
5. EXTRACT     browser4-cli htmlsnapshot get text "<css>" | query --sql @f.sql
```

**Headless default:** unless the user explicitly asks for a visible window ("show me the browser" / "open visibly"), always use `--headless`. `goto` inherits the display mode set by `open`.

**Bot-detection escalation:** if the site blocks the headless browser (CAPTCHA, challenge/interstitial, "unusual traffic", or an empty body where content was expected), `close` then `open --headed` with the same `-s <name>` and retry **once** — and tell the user that anti-bot protection forced the switch. If the headed retry is blocked too, stop: prefer `attach` over more retries. See [browser-modes.md](browser-modes.md#2-axis-2--display-mode).

## Copy-Paste Template

```bash
browser4-cli open --headless "https://example.com"
browser4-cli snapshot -v 0 --stdout        # read the page; note refs
browser4-cli fill <ref> "<value>"
browser4-cli press Enter
browser4-cli wait --load networkidle       # proves the network settled — nothing more
browser4-cli wait "<result-selector>"      # poll the element carrying the result
browser4-cli snapshot -v 0 --auto-diff --stdout   # verify changes
browser4-cli htmlsnapshot get all text "<css-selector>"   # all matches
```

`wait --load networkidle` is a network-idle heuristic, not a readiness guarantee: a page whose results are computed by its own JS after load (or fetched by a long-running XHR) can still be empty when it reports success. For result-style pages, poll the result element (`wait "<result-selector>"`, or `eval --wait-selector <css>`) before reading or screenshotting.

`get` returns only the first match; `get all` returns every match (querySelectorAll). `--stdout` prints directly; the default writes to a file. The CLI snapshots automatically after interactions; `--no-snapshot` skips that round-trip.

## Key Commands

| Purpose | Command |
|---|---|
| Navigation / sessions | `open --headless <url>` / `goto <url>` / `close` / `reload`; multiple sessions with `-s <name>` |
| Read page + get refs | `snapshot -v 0` (current screen) / `-v all` (full page) / `-i` (interactive-only: buttons/links/inputs/`cursor:pointer` — draggable and hover-only targets lose their refs, so fall back to `-v 0`) / `snapshot grep <pattern>` |
| Interact | `click` / `dblclick` / `hover` / `drag` / `fill` / `type` / `press` / `select` / `check` / `focus` / `key` |
| Extract | `htmlsnapshot get text\|attr\|html "<css>"` (first match) / `get all ... "<css>"` (all matches); `query --sql @f.sql` (X-SQL multi-field); `eval --json` (live DOM); `extract` (natural language, needs LLM key) |
| Bulk / structured | `crawl <url> --depth N --sql @f.sql`; `swarm create/query` (parallel); `loop` (scheduled repeats) |
| State | `state-save` / `state-load` / `cookie-*`; `attach` (connect to existing Chrome) |
| Tabs | `tab-list` / `tab-new <url>` / `tab-select <index>` (re-snapshot after switching) |
| Diagnostics | `status` / `doctor`; `errors` (page JS errors); `vitals` (performance) |

## snapshot vs htmlsnapshot (Key Decision)

| | `snapshot` | `htmlsnapshot` |
|---|---|---|
| Content | Accessibility tree (AXTree): role / name / ref | Raw HTML DOM: full text |
| Use for | **Interaction** — get refs to click/fill | **Extraction** — read text / data / attributes |
| Decider | "I need to click a button / find an input" | "I need to read an article / extract a price" |

Every `htmlsnapshot` command — `capture` / `get` / `get all` / `inspect` / `summary` / `grep` / `export` / `query` / `readability` — **captures the active page first and then works on that snapshot**: `capture` returns the metadata, the reads consume the snapshot. So a read already sees the page as it is right now — JS-updated content (post-interaction, form submission, SPA route change) included — with no capture step to remember. Add `--expires <dur>` (default `0s`) to a read to serve the **stored** snapshot instead while it is younger than the window (`--expires 1d`), which leaves the tab untouched and works on the previous snapshot version. A command aimed at another URL (`readability <url>`, `query --url <url>`) reads that URL's own stored copy instead, or loads it read-only when the store has nothing; the tab's document is never filed under it. The only precondition is a loaded, archivable page (http(s)/file); use `eval --json` for arbitrary JavaScript against the live DOM.

## Refs: Single-Use Handles

refs are temporary handles: any interaction (click/fill/type/press/select/check/hover/drag) or navigation (goto/reload/tab switch) can invalidate them. **Safe loop = interact → re-snapshot → use fresh refs.** Never store refs for use across navigations. `generate-locator <ref>` produces a resilient CSS selector.

## Critical Warnings

- **Selectors go stale**: they break when sites change their HTML. Discover selectors with `htmlsnapshot inspect` / `summary` before extracting; scenario docs are patterns, not recipes.
- **Shell quoting (Windows)**: for complex JS/SQL use `--sql @file.sql`, `--sql-stdin`, `eval --file` / `--stdin` / `--base64`; quote `@file` paths in PowerShell (`--sql "@q.sql"`). Never inline double-quoted CSS selectors.
- **Git Bash / MSYS**: arguments starting with `/` are path-converted before PowerShell starts, so invoke `./b4w.sh <command>` or prefix `MSYS2_ARG_CONV_EXCL='*'`; prefer the slash-free spelling (`-olp "product/"`), and remember `cookie-set --path` already defaults to `/`.
- **`eval` runs in the page's own JavaScript world** (page-defined globals resolve; `--world isolated` switches to the driver's `__browser4_runtime__` world, where page scripts cannot see what you write and page globals read as `null`). **An async expression needs `--await`** — without it the call fails with "result is a Promise", not with data. **`--ref` takes an arrow function** (`element => element.textContent`); a bare `element.textContent` prints `null` — the most common mistake. JS `undefined` prints a `null` line plus an explanation (`--json` keeps `null`); return an explicit value when it matters. The result goes to **stdout and there is no `--output` option**: write it to a file through the shell (`eval --file x.js > out.json`), or `--json` when you only need the scalar.
- **`b4.run` runs in the task workspace** (relative paths like `eval --file .b4scratch/x.js` resolve there — the same directory `coding.*` uses, not the checkout or an earlier task's workspace): `timeoutSeconds` above 30s is also passed to the CLI as its own `--timeout`, and a failing command reports `b4.run [fail] …` (a non-zero exit, a JSON `status:error`, a timeout or a cancelled job) with the CLI's own output attached. **One workspace per task** — a file an earlier task wrote is not in this run's workspace or its `filesChanged`; `--resume <id>` continues the interrupted task's workspace, `--shared-workspace` opts into the old shared directory. `--token-budget <n>` caps THIS task (0 = unlimited); `config set agent.token.budget.total <n>` changes every later run.
- **Verdicts vs exit codes**: `is visible|enabled|checked` print the value and exit 0 even when false — read the value or use `--json`, or pass `--fail-on-false` to make a false/null assertion exit 1. A `wait` whose condition never became true exits non-zero. `wait --download` baselines the directory when the call starts, so only files that appear or change during the wait count as the download — pre-existing files never do.
- **Paginated output**: read `snapshot -v 0` per screen; locate with `snapshot grep`; `get html` / `grep` and `snapshot --stdout` paginate at 2K lines by default (`--page N` to page; `--all` or `--page-size 0` for the full tree — a `# … output truncated …` hint is appended to stdout when piped). **Don't cat snapshot files** (they can exceed 256KB).
- **Dialogs**: clicking something that triggers `alert`/`confirm`/`prompt` times out; handle with `dialog-accept` / `dialog-dismiss` (or `click --auto-dismiss-dialogs <ref>`).
- **Sandboxed environments**: when the JVM cannot write its logs, `open`/`goto` time out at startup — set `BROWSER4_RUNTIME_DIR` / `BROWSER4_CLI_STATE_DIR` to writable directories.
- **Session reuse**: `--headless` / `--headed` only apply when creating a new session; running sessions ignore them with a warning.
- **`-s <name>` goes BEFORE the command**, like every other global flag: `browser4-cli -s job-1 open …`. Written after the command it is rejected as an unexpected positional argument (`unknown option -s for 'open'`), which reads like a broken option rather than a position rule.
- **When driving the CLI through `b4.run` (agent runs), lifecycle commands are refused**: `stop`, `close-all`, `kill-all`, `install`, `uninstall`, `upgrade`, `delete-data` and `doctor --fix` would stop or rewrite the backend executing the task, so `b4.run` answers `PERMISSION_DENIED | code=COMMAND_NOT_ALLOWED`. Report the blocker in `system.taskComplete(problems=[...])` instead of retrying — a retried `stop` never helps, and the old failure mode was the task killing its own backend.
- **Spreadsheet output is not supported**: tabular deliverables are **CSV** (or JSONL); an `.xlsx` is an *input* to parse. A "save it as a spreadsheet" request is a documented downgrade — deliver CSV and state the downgrade in `problems`.
- **A localized marketplace is fixed by OPERATING THE PAGE, not by a header**: landing on a "change language & currency" / `/customer-preferences/` page (or getting Chinese titles for a US task) means the *site* localized for this profile. Open the storefront, `snapshot -v 0` (or `snapshot grep -i "language|currency|English"`), `select`/`click` the language + currency control, then re-snapshot and verify one known product. The choice lands in the browser profile, so the rest of the session (`crawl --session <name>`) inherits it. `?language=en_US`, `set headers Accept-Language` and a hand-written cookie are hints only — on a fresh profile all three left amazon.com on the Chinese interstitial. Never post-process localized values.

## Scaling Up: X-SQL / PowerCSS / crawl / swarm

Four escalation axes on top of `snapshot` + `htmlsnapshot` — none of them replaces reading one page.

| Capability | One-liner | Reach for it when |
|---|---|---|
| **X-SQL** | SQL over the page DOM: `SELECT … FROM DOM_LOAD_AND_SELECT(@url, 'sel')` | You need **several correlated fields per row** (title + price + link), filtering/sorting/paging, or a query you will re-run. Single-field reads stay `htmlsnapshot get` |
| **PowerCSS** | `:expr()` filters by computed geometry/density: `img:expr(width > 400)`, `div:expr(img == 1 && width > 400)` | Class/id selectors are brittle or meaningless (unstable classes, layout-only differences) — visual features survive markup changes |
| **crawl** | Follow links from a URL or `--seed-file`, `--depth N`, optional `--sql` per page, `--format csv/json` | **Many pages of one site** in one sequential run: recursive discovery, or a URL list harvested from a search page. Keeps ONE warm profile, so locale/currency/login hold |
| **swarm** | Task-ID jobs across several isolated browser contexts, `swarm query --sql @q.sql` → `status` → `result` | The URL set is big and one context's tabs are not enough **concurrency**; throughput matters, no warm profile needed (`swarm create --session <name>` pins one when it is) |

Order of preference: one page → `htmlsnapshot`; one site, many pages → `crawl`; many pages, throughput first → `swarm`; brittle selector at any step → PowerCSS inside the selector; correlated fields or a re-run query → X-SQL inside `htmlsnapshot query` / `crawl --sql` / `swarm query`. Details: [x-sql.md](x-sql.md), [power-dom.md](power-dom.md), [crawl.md](crawl.md), [swarm.md](swarm.md).

## Extraction Decision Tree

```
Extracting data?
├─ Needs interaction first? → snapshot + refs → interact → `htmlsnapshot` (capture) → read the captured copy
├─ Static page, single field → htmlsnapshot get text "<sel>"
├─ Static page, correlated fields (title+price+URL) → query with DOM_LOAD_AND_SELECT(@url,'.card')
├─ Dynamic / complex JS → eval --json
├─ Natural language → extract (needs LLM key)
└─ Many pages → crawl / swarm --sql
```

X-SQL essentials: CSS selectors use **single quotes** (`'h2'`); `@url` is unquoted; FROM is always `DOM_LOAD_AND_SELECT(@url, '...')`; a **JOIN of two `DOM_LOAD_AND_SELECT` sources executes** (each source loads the page again), while CTEs (`WITH`) and FROM-clause subqueries are not supported; numeric-returning functions (`DOM_FIRST_FLOAT`, `STR_FIRST_FLOAT`) come back as JSON strings unless you `CAST(... AS DOUBLE)`; discover selectors with `inspect` before writing SQL.

## Context Discipline

- When re-examining the same page, use references/diffs (the system folds them); pass a refresh argument only when a forced re-fetch is needed.
