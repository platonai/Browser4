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
browser4-cli snapshot -v 0 --auto-diff  # capture + diff vs the previous snapshot
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

### Node line shape

Every node line has the same shape, so line-based readers and `grep` see a uniform document:

```text
- <role> "<accessible name>" [ref=e<id>] [box=x,y,w,h] [level=N] [disabled] [checked] …
```

- `<role>` is the accessibility role (`generic`, `button`, `link`, `textbox`, `heading`, `listitem`, …).
- The accessible name is always a **double-quoted scalar**; a name containing `: ` or `"` no longer switches the whole line to a different YAML shape, and a `"` inside a name stays escaped as `\"`.
- `[ref=…]` is the handle `click` / `fill` / `type` accept; `[box=…]` is the bounding box in CSS pixels (`--no-boxes` drops it).
- Property lines (`- /url: …`, `- /value: …`) and text children (`- text: …`) are indented under the node they belong to.

## Choosing a Mode

| Mode | What it shows | Best for |
|------|--------------|----------|
| `snapshot` (default) | Full AX tree with all element refs | General exploration, first look at a page |
| `snapshot -v 0` | Current visible screen (one screen-height viewport chunk; `-v all` = whole page) | Long pages — read one chunk at a time |
| `snapshot -i` | Interactive-only rendering — buttons, links, inputs and pointer (`cursor:pointer`) elements, inner text merged into their names so each surviving ref line is a self-contained target. `-i -v <n>` narrows it to one viewport chunk (`-i -v 0` = this screen's interactive elements) | Form-heavy pages, a quick read of the actionable elements |
| `htmlsnapshot` | Static HTML (CSS selectors) | Content extraction when you need CSS selectors, not AX refs |

`-i` is a strict filter and drops refs you may need: draggable items (`li[draggable=true]`,
`cursor:grab`) and hover-only targets (tooltip spans, `cursor:help`) keep their text as merged lines
but lose their refs, and with `-v` they vanish from the output entirely. Act on those with refs from
the default snapshot or `-v 0` instead: `snapshot -i -v 0 --stdout` lists this screen's interactive
elements only, while `snapshot -v 0 --stdout` gives the full tree for that screen — draggable and
hover-revealed targets included. Extending the heuristic to draggable/hover-revealed elements is
expected; until it lands, don't use `-i` as the only orientation view before acting. `snapshot
--help` documents the same strict filter, but the size tip printed for very large snapshots (and the
optional `--show-tip` tip) may still carry the older wording "not a strict filter".

## Commands

```bash
browser4-cli snapshot [--viewport N|-v N] [--stdout] [--json] [--quiet]   # capture accessibility tree
browser4-cli snapshot --auto-diff [--viewport N|-v N]                      # capture + diff vs previous snapshot
browser4-cli snapshot --interactive|-i [--viewport N|-v N]                 # interactive-only mode
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

### What the capture says about its own scope

The header of every capture states which of the two it is:

```yaml
# Viewport State
# - scope: whole page (all 10 viewports; no --viewport filter)
# - viewportHeight: 1080px
# - viewportsTotal: 10
#
```

…for a capture **without** `-v`, and `# - processingViewport: <spec>` for an explicitly scoped one.

A capture made without `-v` is **not** a viewport-0 capture: it holds the whole page, including elements far below the fold, whose `[box=…]` y-values grow well past the viewport height. `-v 0` is the single screenful currently visible. Do not read "full page" into `-v 0`, and do not read "one screenful" into a plain `snapshot` — the header now tells the two apart, and a whole-page capture carries no viewport-navigation footer.

## Stdout Pagination

`--stdout` (and its alias `--raw`) prints the snapshot to stdout **paginated at 2000 lines/page by default**. When the tree is truncated:

- the page footer goes to **stderr** (e.g. `[Page 1/12 · 2000 lines of 23400 total · use --page N for next page · --all to show all]`), and
- when stdout is a pipe or redirect, a one-line hint is appended **to stdout** so captured output is not silently cut: `# … output truncated: showing 2000 of 23400 lines — re-run with --all (or --page-size 0) for the full tree.`

Pass `--page N` to page through (`--page-size N` changes the size), or `--all` / `--page-size 0` to print the complete tree in one go. For genuinely huge pages, prefer bounding the capture itself with `-v N` (viewport chunks), `--depth`, `--selector`, or `--no-boxes` over printing everything.

## Auto-Diff

`--auto-diff` captures as usual, then **appends a diff appendix** comparing the capture against the most recent deliberate snapshot in the snapshot directory (auto-snapshots written after interactions are skipped):

```bash
browser4-cli snapshot -v 0                # baseline snapshot
browser4-cli fill <email-ref> "user@example.com"
browser4-cli fill <password-ref> "password"
browser4-cli click <submit-ref>
browser4-cli wait --load networkidle
browser4-cli snapshot -v 0 --auto-diff    # capture + diff vs the baseline
```

> **It is not a filtered view.** The complete current tree is printed first (exactly as without `--auto-diff`), followed by `### Diff` and the appendix. To read only the delta, capture with `--stdout --all` and look below `### Diff`, or redirect the whole output to a file and use an external `grep`.

The appendix has three sections, and every entry is one line in the same node shape the tree uses — `<marker> <role> "<name>" [ref=…] [box=…]`:

```text
# Snapshot Diff: snapshot-<older>.yml → snapshot-<newer>.yml
# 3 added, 1 removed, 1 modified

## Removed

- link "Old" [ref=e4] [box=10,100,100,30]

## Modified

~ textbox "Query" [ref=e20] [box=10,60,100,30]
  /ref: "e2" → "e20"
  /value: "hello" → "hello2"

## Added

+ button "Save draft" [ref=e7078] [box=640,512,120,40]
```

- `+` = added, `-` = removed, `~` = modified. The `[ref=…]` is the **current** page's ref, so an added or modified element can be acted on straight from the appendix.
- Modified entries list what changed as indented `/ref:`, `/value:` and `/url:` lines. Element matching tolerates position and name drift, so an element whose *only* difference is its accessible name is not reported.
- Names longer than 60 characters are elided with `…`.
- `-` also starts every YAML sequence item, so the `## Removed` / `## Modified` / `## Added` section headers — not the marker alone — are what tell a diff line from a tree line.
- A navigation reassigns every ref, so after `goto`/`click` the appendix is mostly removals plus additions. That is expected, not a page rewrite.
- `### Diff` with `# No previous snapshot found — this is the first capture in this session.` means there was no deliberate baseline to compare against; capture once (`snapshot -v 0`) before interacting.
- The appendix is also **persisted** as `<capture>.diff.txt` next to the capture (also for `--stdout` captures, which write no `.yml`), so it can be searched later with `snapshot grep --diff` or any external `grep` — see [Searching the auto-diff appendix](#searching-the-auto-diff-appendix). `snapshot clean` deletes a report together with its capture.

> **`wait --load networkidle` only proves the network went quiet.** A page that computes results in JS after load (dashboards, bot-detection verdicts) can still be blank when the wait reports success, and a diff then shows nothing. Poll the result element first — `wait "<result-selector>"` — and treat `networkidle` as a network-settling extra step.

## Snapshot Grep

`snapshot grep` captures a **fresh, live, full-page AX tree** from the server and searches it with regex — it does not read the most recent saved snapshot file, and it cannot see auto-diff markers (`+`/`-`/`~`, `## Added`, …) because those exist only in the `--auto-diff` response text, never in a captured tree:

```bash
browser4-cli snapshot grep "See also"             # search live full-page tree
browser4-cli snapshot grep -i "price|rating"      # case-insensitive regex alternation
browser4-cli snapshot grep -A 3 -B 1 "Checkout"   # show surrounding context lines
browser4-cli snapshot grep --page N <pattern>     # paginate grep results
browser4-cli snapshot grep --all <pattern>        # disable pagination (default: 2K lines)
```

### Searching the auto-diff appendix

`snapshot --auto-diff` persists its appendix next to the capture (`<capture>.diff.txt`), and `--diff` searches that report instead of the live tree:

```bash
browser4-cli snapshot -v 0 --auto-diff --stdout --all   # capture + diff; writes snapshot-<ts>.diff.txt
browser4-cli snapshot grep --diff -c -F "## Added"      # how many entries were added
browser4-cli snapshot grep --diff -F "+ link"           # the added link entries
browser4-cli snapshot grep --diff -A 3 "## Modified"    # context around a section
```

With no report on disk, `--diff` fails with the fix (`No auto-diff report in … — run snapshot --auto-diff first`). The same report is a plain text file, so an external `grep` works too:

```bash
browser4-cli snapshot -v 0 --auto-diff --stdout --all > diff.txt
grep -i -C 3 "pattern language" diff.txt     # any match
grep -c '^+ ' diff.txt                       # how many entries were added
```

> **Zero matches = exit code 1** (GNU grep semantics). Use `browser4-cli snapshot grep -c <pattern>` to get the count and check `$LASTEXITCODE` / `$?` — no stdout parsing needed.

| Option | Description |
|---|---|
| `-i` | Case-insensitive matching |
| `-A N` | Show N lines after each match |
| `-B N` | Show N lines before each match |
| `-C N` | Show N lines before and after each match |
| `-n` | GNU grep `-n` compatibility — line numbers are printed by default, so `-n` is a no-op here |
| `--diff` | Search the persisted auto-diff report of the last `snapshot --auto-diff` instead of a fresh tree |
| `--page N` | Show page N of paginated results |
| `--all` | Disable pagination (show all results) |

> **The short flags are grep's, not `snapshot`'s:** here `-i` is case-insensitive (not `--interactive`), `-v` selects non-matching lines (not a viewport index) and `-c` counts matches (not `--compact`). Long forms (`--ignore-case`, `--invert-match`, `--count`) are unambiguous.

Patterns are **Rust regex** (same dialect as `htmlsnapshot grep`): `|` is alternation (an escaped `\|` is also accepted and converted), `^`/`$` anchor the start/end of a line, so write a literal `$` as `[$]` (e.g. `'[$][0-9.]+'` for prices) — it survives every shell layer (`\$` also compiles as an escaped dollar, but is easy to lose between shell layers). Use `-F` to match plain text. See the [htmlsnapshot grep dialect notes](htmlsnapshot.md#regex-dialect) for details.

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

`--interactive` (`-i`) switches the snapshot into **interactive-only rendering**: only interactive
elements survive — buttons, links, inputs/textboxes, and pointer (`cursor:pointer`) elements. Inner
text is merged into each surviving element's name, so its ref line reads as a self-contained target.
`-i` is a **strict filter**, not a relayout: everything else is dropped, or reduced to plain `text:`
lines without refs.

> **Warning:** Draggable items (`li[draggable=true]`, `cursor:grab`) and hover-only targets (e.g.
> tooltip spans) are not treated as interactive, so they lose their refs under `-i` — and combined
> with `-v` they disappear from the output entirely. Plain `snapshot -v 0` (or the default full
> snapshot) still returns their refs.

`-i` and `-v` compose: adding `-v` narrows the result to the interactive elements inside that single
viewport chunk. So `-i -v 0` is a focused interactive screenful — **not** a filtered full tree.

```bash
browser4-cli snapshot -i        # interactive-only rendering (text merged into names)
browser4-cli snapshot -i -v 0   # interactive elements in the current screenful — not a full tree
```

**Draggable or hover-revealed targets** (the usual targets of `drag`, `hover` and `dblclick`):
snapshot the plain way, act from those refs, then re-snapshot after acting.

```bash
browser4-cli snapshot -v 0                    # refs for all on-screen elements, draggable included
browser4-cli drag <source-ref> <target-ref>   # act using those refs
browser4-cli snapshot -v 0                    # re-snapshot: refs are invalidated by the interaction
```

To keep the output genuinely small and focused, use:

- `-v 0` / `-v N` — capture one screenful at a time (the recommended way to bound size)
- `-s, --selector <CSS>` — scope the capture to a subtree
- `-d, --depth <N>` — limit tree depth
- `htmlsnapshot` — CSS-selector extraction when you do not need refs

> **Note:** Extending the interactive heuristic to draggable/hover-revealed elements is expected,
> so those refs stop silently disappearing. Until then, do not use `-i` as the only orientation
> view before acting.

## Output Modes

| Mode | Flag | Behavior |
|---|---|---|
| Default | *(none)* | Human-readable output on stdout, tips on stderr |
| JSON | `--json` | Single-line JSON envelope on stdout only; tips/hints/warnings suppressed |
| Quiet | `--quiet`, `-q` | Suppress all normal output; only errors on stderr |
| Stdout | `--stdout` | Print snapshot content to stdout instead of saving to file. Large trees are paginated at 2000 lines/page (see Stdout Pagination above) — use `--all` or `--page-size 0` for the full tree. `--raw` is an alias. |

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
browser4-cli wait --load networkidle               # network settled — nothing more
browser4-cli snapshot -v 0 --auto-diff             # verify: capture + diff appendix
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
| `--stdout` | Print snapshot to stdout instead of saving to file. Large trees are paginated (default 2000 lines/page); when truncated, a hint is appended to stdout when piped and the full footer goes to stderr. |
| `--raw` | Alias of `--stdout` — strip page info and print only snapshot content. |
| `--auto-diff` | Capture, then append a diff appendix (`### Diff` + `## Removed` / `## Modified` / `## Added`) against the previous **deliberate** snapshot (auto-snapshots after interactions are skipped). The full tree is printed first — the appendix is not a filtered view. The appendix is also persisted as `<capture>.diff.txt`, searchable with `snapshot grep --diff`. After a navigation or interaction that changes the page, `click <ref> --no-snapshot` preserves the pre-interaction baseline for a clean diff. |
| `--interactive`, `-i` | Interactive-only rendering (strict filter); see Interactive Mode. |
| `--json` | Single-line JSON envelope on stdout only. All tips, hints, and warnings are suppressed. |
| `--quiet`, `-q` | Suppress all normal output; only errors appear on stderr. |
| `--page N` | When used with `--stdout`, show only page N of the output (1-based). |
| `--page-size N` | Lines per page for stdout output (default: 2000; `0` = unlimited). |
| `--all` | Disable stdout pagination — print the complete tree. |

## Errors & Recovery

| Symptom | Cause | Fix |
|---------|-------|-----|
| `snapshot --stdout` dumps a huge tree | Full page captured; stdout output is paginated at 2000 lines/page by default, but a single page is still large | Use `-v 0` viewport chunks, or `--stdout --page N` / `--page-size N`; `--all` / `--page-size 0` for the complete tree; `snapshot grep` for targeted reads |
| `snapshot grep` finds nothing | Pattern doesn't match the accessibility tree (refs/labels, not raw HTML) | Match against element names and labels; use `htmlsnapshot grep` for the captured HTML (an annotated serialization: `vi` boxes and synthetic `<link rel="normalizedURI">` head elements); check exit code (`1` = no matches) |
| `snapshot --selector` / `snapshot grep --selector` fails | Either the selector is not valid CSS (`article[[`) or it is valid and matches nothing: the backend probe now quotes and escapes the selector, so a bad one fails loudly (`SyntaxError` / `Invalid CSS selector`) instead of being reported as “matched no element” | Read the error: a syntax/`[INVALID_ARGUMENT]` error means fix the selector; `no element matches selector '<sel>'` means the selector is valid but the element is not in this frame yet — re-`snapshot`, check the frame, or verify with `is visible "<sel>"` / `htmlsnapshot get text "<sel>"` |
| Missing refs in `-i` mode | Strict interactive-only filter | Use `snapshot -v 0` instead |
| Stale refs after interaction | Refs are single-use handles | Re-snapshot after any interaction — see [SKILL.md §5](../SKILL.md#5-critical-warnings) |

## Critical Warnings

> **Note:** Warning: don't cat snapshot files (they can exceed 256KB) — see [SKILL.md §5](../SKILL.md#5-critical-warnings)

> **Note:** Warning: refs are single-use — re-snapshot after any interaction — see [SKILL.md §5](../SKILL.md#5-critical-warnings)

> **Warning:** `snapshot -i` is a strict interactive-only filter: draggable items (`cursor:grab`)
> and hover-only targets (e.g. tooltip spans) lose their refs; with `-v` they vanish entirely.
> Orient with a plain `snapshot -v 0` before acting (see Interactive Mode).

## See Also

- [htmlsnapshot.md](htmlsnapshot.md) — static DOM extraction via CSS selectors and X-SQL
- [css-selector-bridge.md](css-selector-bridge.md) — bridging snapshot refs to CSS selectors
- [shell-quoting.md](shell-quoting.md) — avoid shell-quoting issues on Windows
