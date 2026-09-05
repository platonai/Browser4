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
browser4-cli snapshot --interactive|-i [--viewport N|-v N]                 # interactive-oriented rendering (text merged into names — not a strict filter)
browser4-cli snapshot --stdout --page N                                    # paginate stdout output
browser4-cli snapshot grep [OPTIONS] <pattern>                             # search snapshot content with regex
```

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
| `-n` | GNU grep `-n` compatibility — line numbers are printed by default, so `-n` is a no-op here |
| `--page N` | Show page N of paginated results |
| `--all` | Disable pagination (show all results) |

Patterns are **Rust regex** (same dialect as `htmlsnapshot grep`): `|` is alternation, `^`/`$` anchor the start/end of a line, and a literal `$` must be written `[$]` (e.g. `'[$][0-9.]+'` for prices) — `\$` is an invalid escape, not a way to write a literal dollar. Use `-F` to match plain text. See the [htmlsnapshot grep dialect notes](htmlsnapshot.md#regex-dialect) for details.

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

`--interactive` (`-i`) switches the snapshot into **interactive-oriented rendering**: the AX capture aggregates inner text into the enclosing element's name, so each ref line reads as a self-contained target (e.g. a `<header>`/`banner` line carries the text of everything inside it).

> **`-i` is not a strict filter.** Despite the name, the tree is **not** reduced to buttons, links, inputs and other interactive controls: any addressable element (headings, paragraphs, list items, generic `<div>` containers — they all carry refs) stays in the output. Do not use `-i` expecting a smaller tree.

```bash
browser4-cli snapshot -i        # interactive-oriented rendering (text merged into names)
browser4-cli snapshot -i -v 0   # same, but only the current screenful — the reliable way to bound output size
```

To keep the output genuinely small and focused, use:

- `-v 0` / `-v N` — capture one screenful at a time (the recommended way to bound size)
- `-s, --selector <CSS>` — scope the capture to a subtree
- `-d, --depth <N>` — limit tree depth
- `htmlsnapshot` — CSS-selector extraction when you do not need refs

> **Warning:** Do not rely on `-i` to shrink large snapshots or to strip product-card containers — addressable non-interactive elements remain. For shopping/search pages prefer `-v 0` viewport pagination or `htmlsnapshot` for selector-based extraction.

## Output Modes

| Mode | Flag | Behavior |
|---|---|---|
| Default | *(none)* | Human-readable output on stdout, tips on stderr |
| JSON | `--json` | Single-line JSON envelope on stdout only; tips/hints/warnings suppressed |
| Quiet | `--quiet`, `-q` | Suppress all normal output; only errors on stderr |
| Stdout | `--stdout` | Print snapshot content to stdout instead of saving to file |

## Where Snapshots Are Stored

Snapshot files are written to **`.browser4-cli/snapshot/` under the current working directory** (`<cwd>/.browser4-cli/snapshot/snapshot-<timestamp>.yml`) — **not** to the session-state directory (`~/.browser4`) and **not** affected by `BROWSER4_CLI_STATE_DIR`. The file path is printed to stderr after each capture.

**They accumulate.** Every navigation and interaction that triggers a capture (`goto`, `open`, `click`, `fill`, `select`, …) writes a new timestamped file, plus any `snapshot` command run without `--stdout`. Over a session this can grow to hundreds of files; the directory is gitignored in the Browser4 repo (`.gitignore`: `.browser4-cli/`), but other projects may not ignore it.

Manage the directory with the `snapshot list` / `snapshot clean` commands (canonical kebab-case names `snapshot-list` / `snapshot-clean`; both spellings work):

```bash
browser4-cli snapshot list              # show saved files (name, size, modified; default: 20 most recent)
browser4-cli snapshot list -n 50        # more files
browser4-cli snapshot list --all        # include archived snapshots
browser4-cli snapshot clean --dry-run   # preview what would be deleted
browser4-cli snapshot clean             # delete all but the 100 most recent
browser4-cli snapshot clean --keep 20   # keep only the 20 most recent
browser4-cli snapshot clean --all       # delete everything (including the archive)
```

`extract` / `summarize` / screenshot artifacts also land in this directory (timestamped), so `snapshot list`/`clean` manage those too.

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

## Flags / Options

| Option | Description |
|---|---|
| `--viewport N`, `-v N` | Capture viewport N (0 = current visible screen; negative = above). Paginates long pages into fixed-height chunks. |
| `--stdout` | Print snapshot to stdout instead of saving to file. |
| `--auto-diff` | Diff against the previous snapshot — shows added/removed/changed elements. |
| `--interactive`, `-i` | Interactive-oriented rendering: inner text is aggregated into the enclosing element's name so ref lines read as self-contained targets. This is **not** a strict interactive-only filter — addressable headings, paragraphs and generic containers remain in the tree. |
| `--json` | Single-line JSON envelope on stdout only. All tips, hints, and warnings are suppressed. |
| `--quiet`, `-q` | Suppress all normal output; only errors appear on stderr. |
| `--page N` | When used with `--stdout`, show only page N of the output. |

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| `snapshot --stdout` dumps a huge tree | Full page captured; stdout output is not paginated by default | Use `-v 0` or `--stdout --page N`; or `snapshot grep` for targeted reads |
| `snapshot grep` finds nothing | Pattern doesn't match the accessibility tree (refs/labels, not raw HTML) | Match against element names and labels; use `htmlsnapshot grep` for raw HTML |
| Missing elements in `-i` mode | Interactive mode strips generic `<div>` containers | Use `--viewport 0` or `htmlsnapshot` for shopping/search pages |
| Stale refs after interaction | Refs are single-use handles | Re-snapshot after any interaction — see [SKILL.md §5](../SKILL.md#5-critical-warnings) |

## Critical Warnings

> **Note:** Warning: don't cat snapshot files (they can exceed 256KB) — see [SKILL.md §5](../SKILL.md#5-critical-warnings)

> **Note:** Warning: refs are single-use — re-snapshot after any interaction — see [SKILL.md §5](../SKILL.md#5-critical-warnings)

> **Warning:** Interactive mode (`snapshot -i`) does **not** strip generic `<div>` containers or other non-interactive elements — any addressable element remains in the tree. Prefer `-v 0` viewport pagination or `htmlsnapshot` for shopping/search pages where you need small, focused output.

## See Also

- [htmlsnapshot.md](htmlsnapshot.md) — static DOM extraction via CSS selectors and X-SQL
- [css-selector-bridge.md](css-selector-bridge.md) — bridging snapshot refs to CSS selectors
- [shell-quoting.md](shell-quoting.md) — avoid shell-quoting issues on Windows
