---
title: "Quick Patterns — Interact, Verify, Extract"
description: "Use when you need a proven copy-paste recipe: multi-session workflows, form filling, mouse interactions, dialogs, verification after interaction, static and bulk extraction, agent task lifecycle, and agent memory."
tier: procedure
---

# Quick Patterns — Interact, Verify, Extract

## Quick Start

```bash
browser4-cli open --headless "https://example.com/login"
browser4-cli snapshot -v 0
browser4-cli fill <email-ref> "user@example.com"
browser4-cli fill <password-ref> "password"
browser4-cli click <submit-ref>
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff
```

Every interaction should be followed by verification (`snapshot -v 0 --auto-diff` or `snapshot grep`).

## When to Use

Use these patterns for common interactive workflows: session management, form filling, mouse actions, dialog handling, and verifying that interactions had the expected effect. For extraction-only work, prefer [htmlsnapshot.md](htmlsnapshot.md); for choosing between extraction approaches, see [decision-trees.md](decision-trees.md).

## How It Works

The patterns share one loop: open → snapshot (get refs) → interact → re-snapshot/verify → extract. Refs are single-use (see [SKILL.md §5](../SKILL.md#5-critical-warnings)), so the loop always re-snapshots after interactions before reusing refs.

## Patterns

### 1. Multi-Session Workflow

Named sessions isolate browser state. Create and switch with `-s <name>`, list with `list`, close one with `close`, and clean up with `close-all`:

```bash
browser4-cli -s research goto "https://en.wikipedia.org"   # opens "research"
browser4-cli -s news     goto "https://news.ycombinator.com" # opens "news"
browser4-cli -s news     snapshot -i --stdout              # act inside "news"
browser4-cli list                                          # show all sessions
browser4-cli -s news     close                             # close only "news"
browser4-cli close-all                                     # close every session
```

### 2. Interactive Form Fill

```bash
browser4-cli open --headless "https://example.com/login"
browser4-cli snapshot -v 0
browser4-cli fill <email-ref> "user@example.com"
browser4-cli fill <password-ref> "password"
browser4-cli click <submit-ref>
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff
```

Dropdowns (`select`) accept the option's **visible label or its value**, matched case-insensitively:

```bash
browser4-cli select <country-ref> "Singapore"          # by visible label (option value may be 'sg')
browser4-cli select <country-ref> "sg"                 # by option value — both forms work
browser4-cli select <country-ref> "Singapore" --verify # confirms the selected option; exits non-zero on a genuine mismatch
```

### 3. Find Elements by Text (snapshot grep)

```bash
browser4-cli open --headless "https://example.com"
browser4-cli snapshot -v 0                        # capture snapshot first
browser4-cli snapshot grep "See also"             # search for text in the full AX tree
browser4-cli snapshot grep -i "price|rating"      # case-insensitive regex alternation
browser4-cli snapshot grep -A 3 -B 1 "Checkout"   # show surrounding context lines
```

### 4. Mouse Interactions

```bash
# Hover — reveal tooltips, expand menus, trigger hover effects
browser4-cli hover <ref>                          # hover over an element
browser4-cli mousemove 5 5                        # move the pointer to blank space (clears any CSS :hover)

# Double-click — trigger dblclick handlers
browser4-cli dblclick <ref>                       # double-click an element

# Drag-and-drop — move elements between containers
browser4-cli drag <source-ref> <target-ref>            # drag source onto target (drop at target center)
browser4-cli drag <source-ref> <target-ref> --at top   # insert the source BEFORE the target
browser4-cli drag <source-ref> <target-ref> --at bottom # insert the source AFTER the target
```

Clicks, double-clicks, and hovers move the pointer onto the element first, so CSS `:hover` styles match the element under the pointer — they no longer linger on a previously hovered element. `drag --at bottom`/`--at top` pin the drop point to the target's edge so live-reorder lists land deterministically, and the command prints where the source landed (e.g. `Dropped li#item3 as child 4 of 4 in ul#list`).

**Verifying hover-revealed content:** snapshot text is not a visibility proof — full-page snapshots can include `visibility:hidden` text and viewport snapshots may omit a hover-visible tooltip. Verify hover effects with an eval of the computed style or geometry instead:

```bash
browser4-cli hover <ref>
browser4-cli eval "getComputedStyle(document.querySelector('.tooltip')).visibility"  # "visible"
browser4-cli eval "document.querySelector('.card-detail').clientHeight"              # grew past 0
```

### 5. Dialog Handling

Native browser dialogs (`alert()`, `confirm()`, `prompt()`) block the page's main thread while they are open. A click that triggers a dialog returns quickly with a "dialog is pending" error instead of hanging — the click stays parked server-side and completes on its own once the dialog is handled. Do not re-run the triggering click (it would open a second dialog):

```bash
browser4-cli click "#alertBtn"                    # prints: native dialog pending — run dialog-accept
browser4-cli dialog-accept                        # dismiss the alert ("OK") — the parked click then completes

browser4-cli click "#confirmBtn"                  # triggers confirm
browser4-cli dialog-accept                        # click "OK" (returns true to page)

browser4-cli click "#promptBtn"                   # triggers prompt
browser4-cli dialog-accept "Hello from Browser4"  # fill prompt and accept

browser4-cli dialog-dismiss                       # cancel/dismiss any dialog
```

**Note:** `dialog-accept` and `dialog-dismiss` must be run in a separate invocation — they cannot be part of the same command as the triggering `click`. The triggering `click` fails fast with a "dialog pending" message instead of hanging, and the underlying click stays parked and finishes when `dialog-accept`/`dialog-dismiss` releases the dialog — run the two commands as separate invocations (e.g. in a script: `browser4-cli click "#alertBtn" || true; browser4-cli dialog-accept`). Alternatively, use `click --auto-dismiss-dialogs <ref>` to auto-accept any dialog triggered by the click in a single invocation (auto-accept only — it cannot exercise the dismiss path of `confirm`/`prompt`).

### 6. Verifying Results (verify-after-interaction)

```bash
# After click — diff vs previous snapshot
browser4-cli click <submit-ref>
browser4-cli snapshot -v 0 --auto-diff --stdout   # shows only what changed

# After hover — check the computed style of the revealed content
browser4-cli hover <ref>
browser4-cli eval "getComputedStyle(document.querySelector('#tooltip')).visibility"  # expect "visible"

# After drag — the command itself reports where the source landed
browser4-cli drag <source> <target> --at bottom     # e.g. "Dropped li#x as child 4 of 4 in ul#list"

# After dialog — handle the dialog, then verify the interaction log
browser4-cli click "#alertBtn" || true          # "dialog pending" error is expected
browser4-cli dialog-accept
browser4-cli snapshot grep "\[alert\]|\[confirm\]|\[prompt\]"

# Generate resilient CSS selectors from snapshot refs
browser4-cli generate-locator <ref>               # produces e.g. "#contactForm > button.primary"
browser4-cli get text "#contactForm > button.primary"  # verify with the generated selector
```

### 7. Static Data Extraction (Single Field)

```bash
browser4-cli open --headless "https://example.com/product/42"
browser4-cli htmlsnapshot                           # capture static HTML snapshot
browser4-cli htmlsnapshot get text ".product-title"
browser4-cli htmlsnapshot get attr ".product-image" src
```

### 8. Bulk Extraction (X-SQL — Correlated Fields)

```bash
# Write query to file (no shell escaping)
cat > query.sql << 'SQLEOF'
SELECT
    DOM_FIRST_TEXT(DOM, '.title') AS title,
    DOM_FIRST_TEXT(DOM, '.price') AS price,
    DOM_FIRST_ATTR(DOM, 'a[href]', 'href') AS url,
    DOM_FIRST_ATTR(DOM, 'img', 'src') AS img   -- plain CSS inside UDF selector args
FROM DOM_LOAD_AND_SELECT(@url, '.product-card')
SQLEOF

# Default output is the raw JSON envelope; add --format table for human-readable
# output (--result-only prints just the resultSet):
browser4-cli htmlsnapshot query "https://example.com/products" --sql @query.sql --format table
```

> **PowerCSS `:expr(...)` and UDF selector args:** visual filters like
> `img:expr(width > 250 && height > 250)` are **not reliably evaluated inside
> `DOM_FIRST_*`/`DOM_ALL_*` selector arguments** — they silently match nothing.
> Keep UDF selector args to plain CSS (as above) and put `:expr(...)` filters
> where they are supported: the `DOM_LOAD_AND_SELECT` selector in the `FROM`
> clause, or `htmlsnapshot get` / `inspect` selectors.

### 9. PowerCSS (visual-feature selectors)

PowerCSS extends CSS selectors with `:expr()` over computed visual features (size, position, content density) — resilient to markup changes:

```
element:expr(width > 400 && height > 400)
```

Operators: `+`, `-`, `*`, `/`, `^`, `%`, `==`, `!=`, `<`, `>`, `<=`, `>=`, `&&`, `||`. Use parentheses for grouping. See **[power-dom.md](power-dom.md)** for the full feature list, operators, and real-world patterns.

### 10. Agent Task Lifecycle (Async)

```bash
# 1. Submit a natural-language task (returns <task-id>)
browser4-cli agent run "Find the top 5 products and their prices on this page"

# 2. Poll until complete
browser4-cli agent status <task-id>
# Look for: "processState": "done" or "isDone": true

# 3. Get the result
browser4-cli agent result <task-id>
```

**Note:** `agent run` is asynchronous. To block in one command, pass `--wait` (default 600s; tune with `--wait-timeout <seconds>` or `BROWSER4_CLI_AGENT_WAIT_TIMEOUT_SECS`). If the wait window expires, the task keeps running server-side and stays listed in `agent list` for later polling with `agent status` / `agent result`.

**Status codes reference:**

| statusCode | processState | Meaning |
|-----------|-------------|---------|
| (null) | `"created"` | Queued, not yet picked up |
| 102 | `"in_progress"` | Agent is actively working |
| 200 | `"done"` | Task completed successfully |
| 417 | `"done"` | Expectation failed (e.g., missing LLM key) |
| 4xx/5xx | `"done"` | Task failed — inspect `message` for details |

**CLI status labels:** `queued` (submitted, waiting), `processing` (working), `completed` (finished — call `agent result`), `failed (NNN)` (failed with HTTP status NNN). `browser4-cli agent list` shows all tracked tasks.

### 11. Agent Memory (progressive)

`agent run` tasks run inside a memory-equipped engine:

- **Run-start recall:** every task begins with an automatic `## Memory` section in the system prompt — L0 fact hits from past tasks, L1 PEM knowledge (selectors/blockers with confidence), and user preferences. Treat it as a directory: verify before use, and fetch details via `memory.read`.
- **Working memory:** write stable cross-step conclusions with `memory_note` (key ≤ 32 chars of `[a-zA-Z0-9_.-]`, value ≤ 200 chars; the tool name is `memory_note` with an underscore). Notes are re-injected every round and survive context compression.
- **Search history:** `memory_search "query"` finds past tasks/tool executions; `memory_read <taskId> [seq]` fetches a bounded event window; `memory_forget <taskId>` explicitly removes a task (privacy/correction).
- **Auto-deposit:** completed and failed tasks are automatically saved to the knowledge store (`experience_*` tools remain available for deeper queries and diagnostics). No manual `experience_save` needed.

See **[agent.md](agent.md)** for full details including LLM key configuration, error recovery, and `extract`/`summarize` synchronous variants.

## Flags / Options

| Flag | Used in | Description |
|------|---------|-------------|
| `-s <name>` | session commands | Target a named session |
| `--stdout` | `snapshot` | Print to stdout instead of a file |
| `--auto-diff` | `snapshot` | Diff vs the previous snapshot — shows only what changed |
| `-i`, `--interactive` | `snapshot` | Interactive-oriented rendering: inner text merged into element names so ref lines are self-contained targets (not a strict interactive-only filter — pair with `-v 0` to bound size) |
| `--all` | `htmlsnapshot get` | Return all matches (JSON array) |
| `--sql @file` | `htmlsnapshot query` | Read the X-SQL query from a file (avoids shell quoting) |
| `--wait`, `--wait-timeout <s>` | `agent run` | Block for the result (default 600 s) |

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| `click` errors with "dialog is pending" | the click triggered a native dialog and returned early by design — it did not hang | handle the dialog with `dialog-accept` / `dialog-dismiss` in a separate invocation (the parked click then completes), or use `click --auto-dismiss-dialogs <ref>`
| Refs fail after an interaction | Refs are single-use | Re-snapshot before reusing refs — see [SKILL.md §5](../SKILL.md#5-critical-warnings) |
| `agent status` never completes | Task failed (417/4xx/5xx) or LLM key missing | Inspect `message`; configure the LLM key |
| SQL errors on inline `--sql` | Shell escaping on Windows | Use `--sql @file.sql` — see [shell-quoting.md](shell-quoting.md) |
