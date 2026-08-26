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

## Copy-Paste Template

```bash
browser4-cli open --headless "https://example.com"
browser4-cli snapshot -v 0 --stdout        # read the page; note refs
browser4-cli fill <ref> "<value>"
browser4-cli press Enter
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff --stdout   # verify changes
browser4-cli htmlsnapshot get text "article" --all
```

`--stdout` prints directly; the default writes to a file. The CLI snapshots automatically after interactions; `--no-snapshot` skips that round-trip.

## Key Commands

| Purpose | Command |
|---|---|
| Navigation / sessions | `open --headless <url>` / `goto <url>` / `close` / `reload`; multiple sessions with `-s <name>` |
| Read page + get refs | `snapshot -v 0` (current screen) / `-v all` (full page) / `-i` (interactive only) / `snapshot grep <pattern>` |
| Interact | `click` / `dblclick` / `hover` / `drag` / `fill` / `type` / `press` / `select` / `check` / `focus` / `key` |
| Extract | `htmlsnapshot get text\|attr\|html "<css>" [--all]`; `query --sql @f.sql` (X-SQL multi-field); `eval --json` (live DOM); `extract` (natural language, needs LLM key) |
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

`htmlsnapshot` (capture) must run once **before** `get` / `get all` / `inspect` / `grep` / `export` become available; `query` is the exception — it re-fetches independently and needs no prior capture. For JS-updated content, capture before extracting; `eval --json` reads the live DOM. **Re-capture after every navigation/interaction** or the snapshot goes stale.

## Refs: Single-Use Handles

refs are temporary handles: any interaction (click/fill/type/press/select/check/hover/drag) or navigation (goto/reload/tab switch) can invalidate them. **Safe loop = interact → re-snapshot → use fresh refs.** Never store refs for use across navigations. `generate-locator <ref>` produces a resilient CSS selector.

## Critical Warnings

- **Selectors go stale**: they break when sites change their HTML. Discover selectors with `htmlsnapshot inspect` / `summary` before extracting; scenario docs are patterns, not recipes.
- **Shell quoting (Windows)**: for complex JS/SQL use `--sql @file.sql`, `--sql-stdin`, `eval --file` / `--stdin` / `--base64`; quote `@file` paths in PowerShell (`--sql "@q.sql"`). Never inline double-quoted CSS selectors.
- **Paginated output**: read `snapshot -v 0` per screen; locate with `snapshot grep`; `get html` / `grep` paginate at 2K lines by default (`--page N` to page). **Don't cat snapshot files** (they can exceed 256KB).
- **eval --ref must be an arrow function**: `element => element.textContent`; writing `element.textContent` returns null — the most common mistake.
- **Dialogs**: clicking something that triggers `alert`/`confirm`/`prompt` times out; handle with `dialog-accept` / `dialog-dismiss` (or `click --auto-dismiss-dialogs <ref>`).
- **Sandboxed environments**: when the JVM cannot write its logs, `open`/`goto` time out at startup — set `BROWSER4_RUNTIME_DIR` / `BROWSER4_CLI_STATE_DIR` to writable directories.
- **Session reuse**: `--headless` / `--headed` only apply when creating a new session; running sessions ignore them with a warning.

## Extraction Decision Tree

```
Extracting data?
├─ Needs interaction first? → snapshot + refs → interact → re-capture → extract
├─ Static page, single field → htmlsnapshot get text "<sel>"
├─ Static page, correlated fields (title+price+URL) → query with DOM_LOAD_AND_SELECT(@url,'.card')
├─ Dynamic / complex JS → eval --json
├─ Natural language → extract (needs LLM key)
└─ Many pages → crawl / swarm --sql
```

X-SQL essentials: CSS selectors use **single quotes** (`'h2'`); `@url` is unquoted; FROM is always `DOM_LOAD_AND_SELECT(@url, '...')`; no JOIN/CTE/subqueries; discover selectors with `inspect` before writing SQL.

## Context Discipline

- When re-examining the same page, use references/diffs (the system folds them); pass a refresh argument only when a forced re-fetch is needed.
