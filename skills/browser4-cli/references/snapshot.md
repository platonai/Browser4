---
title: "Snapshot — Accessibility Tree Capture & Interactive Element Refs"
description: "Reference for the snapshot command. Capture the accessibility tree to discover interactive element refs for click/fill/type, compare state changes with auto-diff, and search page content with snapshot grep."
tier: procedure
---

# Snapshot — Accessibility Tree Capture & Interactive Element Refs

The `snapshot` command captures the page's **accessibility tree** (AX tree) — a structured YAML representation of the page that exposes interactive elements with **refs** for targeting in `click`, `fill`, `type`, `press`, and other interaction commands.

## Quick Start

```bash
browser4-cli goto "https://example.com"
browser4-cli snapshot -v 0              # capture accessibility tree (viewport 0 = current visible screen)
browser4-cli click <ref>                # interact using refs from the snapshot
browser4-cli snapshot -v 0 --auto-diff  # see what changed vs previous snapshot
```

For quick inline viewing without opening a file, add `--stdout`:

```bash
browser4-cli snapshot -v 0 --stdout     # print snapshot to stdout instead of file
```

## When to Use

Use **snapshot** to observe the page and discover interactive element refs — it's the primary observe step in the core loop (navigate → snapshot → interact → re-snapshot → extract). Use **htmlsnapshot** for static data extraction via CSS selectors and X-SQL queries.

| Feature | `snapshot` | `htmlsnapshot` |
|---|---|---|
| Data source | Accessibility tree | Raw HTML DOM |
| Element addressing | Refs (`e5`) | CSS selectors only |
| X-SQL support | No | Yes (`query`) |
| Interaction target | Yes (`click`, `fill`, `type`, ...) | No |
| Selector discovery | No | Yes (`inspect`) |
| Output | YAML accessibility tree | HTML (`export`), structured data (`get`/`query`/`inspect`) |

## How It Works

`snapshot` captures the page's accessibility tree via CDP and saves it as a YAML file. The output shows the page structure as a tree of semantic elements, each with a **ref** (e.g., `e7`, `e191`) — the element's Chrome DevTools Protocol backend node ID prefixed with `e`. Use these refs to target elements in subsequent interaction commands.

```yaml
- generic [ref=e7]:
    - link "News" [ref=e191]:
        - /url: https://example.com/news
    - textbox "Search query" [ref=e35]
    - button "Search" [ref=e25]
```

The snapshot file path is printed to stderr after capture. Snapshot files can be large — use viewport pagination or `snapshot grep` instead of reading them directly.

## Commands

```bash
browser4-cli snapshot [--viewport N|-v N] [--stdout] [--json] [--quiet]   # capture accessibility tree
browser4-cli snapshot --auto-diff [--viewport N|-v N]                      # diff vs previous snapshot
browser4-cli snapshot --interactive|-i [--viewport N|-v N]                 # interactive mode (strips generic <div> containers)
browser4-cli snapshot --stdout --page N                                    # paginate stdout output
browser4-cli snapshot grep [OPTIONS] <pattern>                             # search snapshot content with regex
```

### Options

| Option | Description |
|---|---|
| `--viewport N`, `-v N` | Capture viewport N (0 = current visible screen; negative = above). Paginates long pages into fixed-height chunks. |
| `--stdout` | Print snapshot to stdout instead of saving to file. |
| `--auto-diff` | Diff against the previous snapshot — shows added/removed/changed elements. |
| `--interactive`, `-i` | Interactive mode — strips generic `<div>` containers for cleaner output. |
| `--json` | Single-line JSON envelope on stdout only. All tips, hints, and warnings are suppressed. |
| `--quiet`, `-q` | Suppress all normal output; only errors appear on stderr. |
| `--page N` | When used with `--stdout`, show only page N of the output. |

## Viewport Pagination

Long pages are split into fixed-height **viewports** (roughly one screen each). Viewport indices are scroll-relative: `-v 0` captures the screen currently visible in the browser, `-v 1` the screen below it, and `-v -1` the screen above. Right after a fresh navigation the page is at the top, so `-v 0` is the top of the page in the common workflow.

```bash
browser4-cli snapshot -v 0     # current visible screen (top of page right after load)
browser4-cli snapshot -v 1     # one screen below the current position
browser4-cli snapshot -v -1    # one screen above the current position
```

> **Tip:** Most interactions are with elements near the top of the page. Start with `-v 0` right after loading and only paginate further if the element you need isn't visible.

Without `--viewport`, the snapshot captures the full page in a single file — this can be very large on long pages. Prefer `-v 0` for what's visible; use `-v 1`, `-v 2`, ... or scroll the page for elements further down.

## Auto-Diff

`--auto-diff` compares the current snapshot against the most recent snapshot for the same session and viewport, highlighting changes:

```bash
browser4-cli snapshot -v 0                # baseline snapshot
browser4-cli fill <email-ref> "user@example.com"
browser4-cli fill <password-ref> "password"
browser4-cli click <submit-ref>
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff    # shows what changed after form submission
```

The diff marks elements as added (`+`), removed (`-`), or modified (`~`), making it easy to spot navigation results, error messages, or confirmation text.

> **Note:** `--auto-diff` requires a previous snapshot in the same session. If no previous snapshot exists, it behaves like a normal capture with a warning.

## Snapshot Grep

`snapshot grep` searches the accessibility tree content with regex — no need to read the full snapshot file:

```bash
browser4-cli snapshot grep "See also"             # search for text in the full AX tree
browser4-cli snapshot grep -i "price|rating"      # case-insensitive regex alternation
browser4-cli snapshot grep -A 3 -B 1 "Checkout"   # show surrounding context lines
browser4-cli snapshot grep --page N <pattern>     # paginate grep results
browser4-cli snapshot grep --all <pattern>        # disable pagination (default: 2K lines)
```

Grep operates on the most recent snapshot. If no snapshot exists yet, run `snapshot` first.

| Option | Description |
|---|---|
| `-i` | Case-insensitive matching |
| `-A N` | Show N lines after each match |
| `-B N` | Show N lines before each match |
| `-C N` | Show N lines before and after each match |
| `--page N` | Show page N of paginated results |
| `--all` | Disable pagination (show all results) |

## Ref Lifecycle

Refs are **ephemeral** — they become invalid after commands that change the DOM tree structure:

| Safe (refs survive) | Unsafe (re-snapshot after) |
|---|---|
| `fill` | `click` on links or navigation buttons |
| `type` | `goto` |
| `press` | `reload` |
| `check` | Tab switches |
| `uncheck` | Commands that trigger page navigation |
| `select` | |

**Gray area:** `click` on checkboxes, radio buttons, and some dropdown toggles may or may not mutate the DOM. When in doubt, capture a new snapshot after clicking.

> **In practice, you can fill an entire form from a single snapshot.** Only re-snapshot if a ref unexpectedly fails — the CLI will surface a clear error so you know when it's needed.

## Interactive Mode

`--interactive` (`-i`) strips generic `<div>` containers from the accessibility tree for cleaner output:

```bash
browser4-cli snapshot -i        # cleaner tree, generic containers removed
browser4-cli snapshot -i -v 0   # interactive mode with viewport
```

> **Warning:** Many e-commerce product cards use generic `<div>` elements, not semantic elements. Interactive mode may strip important structural containers on shopping/search pages. Prefer `--viewport 0` or `htmlsnapshot` for those cases.

## Output Modes

| Mode | Flag | Behavior |
|---|---|---|
| Default | *(none)* | Human-readable output on stdout, tips on stderr |
| JSON | `--json` | Single-line JSON envelope on stdout only; tips/hints/warnings suppressed |
| Quiet | `--quiet`, `-q` | Suppress all normal output; only errors on stderr |
| Stdout | `--stdout` | Print snapshot content to stdout instead of saving to file |

## Patterns

### Basic Observe-Interact-Verify Loop

```bash
browser4-cli goto "https://example.com/login"
browser4-cli snapshot -v 0                         # observe: find email ref, password ref, submit ref
browser4-cli fill <email-ref> "user@example.com"   # interact
browser4-cli fill <password-ref> "password"
browser4-cli click <submit-ref>
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff             # verify: see what changed
```

### Find Elements by Text

```bash
browser4-cli goto "https://example.com"
browser4-cli snapshot -v 0                         # capture first
browser4-cli snapshot grep "See also"              # search for text
browser4-cli snapshot grep -i "price|rating"       # regex alternation
browser4-cli snapshot grep -A 3 -B 1 "Checkout"    # with context
```

### Scroll Through a Long Page

```bash
browser4-cli goto "https://example.com/long-page"
browser4-cli snapshot -v 0     # top
browser4-cli snapshot -v 1     # next section
browser4-cli snapshot -v 2     # further down
```

### Machine-Readable Output

```bash
browser4-cli snapshot -v 0 --json   # clean JSON for scripts/agents
```

## Critical Warnings

> **Warning:** Don't cat snapshot files — they can exceed 256KB. Use viewport pagination (`snapshot -v 0`), `snapshot grep <pattern>`, or `snapshot --stdout --page 1` instead.

> **Warning:** Refs are single-use for navigation and DOM-mutating commands. Re-snapshot after `click` (on links/buttons), `goto`, `reload`, and tab switches. Never store refs across navigations.

> **Warning:** Interactive mode (`snapshot -i`) strips generic `<div>` containers. Many e-commerce product cards use generic divs, not semantic elements. Prefer `--viewport 0` or `htmlsnapshot` for shopping/search pages.

## See Also

- [htmlsnapshot.md](htmlsnapshot.md) — static DOM extraction via CSS selectors and X-SQL
- [css-selector-bridge.md](css-selector-bridge.md) — bridging snapshot refs to CSS selectors
- [shell-quoting.md](shell-quoting.md) — avoid shell-quoting issues on Windows
