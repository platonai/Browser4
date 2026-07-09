# Issues: html-snapshot-extraction

> **Source:** `20260708-182856-html-snapshot-extraction.full.md` | **Date:** 20260708-182856 | **Mode:** dev

## Scenario Background

### Task

All 10 steps were completed successfully:

| Step | Command | Result |
|------|---------|--------|
| 1 | `goto "http://books.toscrape.com/"` | ✅ Page loaded |
| 2 | `htmlsnapshot capture` | ✅ Snapshot captured (retried once after timeout) |
| 3 | `htmlsnapshot get text "h3 a"` | ✅ `"A Light in the ..."` |
| 4 | `htmlsnapshot get html "article.product_pod"` | ✅ Full HTML of first product container |
| 5 | `htmlsnapshot get attr "h3 a" href` | ✅ `catalogue/a-light-in-the-attic_1000/index.html` |
| 6 | `htmlsnapshot get all text "h3 a"` | ✅ JSON array of 20 book titles |
| 7 | `htmlsnapshot get all text "h3 a" --offset 5 --limit 5` | ✅ Titles 6-10 (0-indexed) |
| 8 | `htmlsnapshot export --file books-snapshot.html` | ✅ 45KB valid HTML file |
| 9 | `htmlsnapshot summary` | ✅ Detailed WPSI summary |
| 10 | `htmlsnapshot grep -i -c "price"` | ✅ 41 occurrences |

---

### Execution Context

**Key Commands:**

All commands used the `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>` pattern from the repo root.

**Major steps:**
1. Read `skills/browser4-cli/SKILL.md` and `references/development.md` to learn invocation patterns
2. Ran `--help` to see full command reference
3. Navigated to books.toscrape.com with `goto`
4. Captured HTML snapshot (first attempt timed out, succeeded on retry)
5. Used `htmlsnapshot inspect` to discover CSS selectors (`.product_pod`, `h3 a`, etc.)
6. Extracted single and bulk data with `htmlsnapshot get` / `get all`
7. Paginated results with `--offset`/`--limit`
8. Exported full snapshot HTML to file
9. Generated WPSI summary
10. Searched with `htmlsnapshot grep`

**Workarounds required:**
- Initial `htmlsnapshot capture` timed out with HTTP error; retry succeeded

---

---

## Issues Found (7 issues)

### Issue 1: `htmlsnapshot capture` HTTP timeout on first attempt

**Severity:** High
**Category:** Reliability

#### Reproduction

1. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://books.toscrape.com/"` — succeeds
2. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot capture` — times out after 60s

#### Expected Behavior

The command should complete within a few seconds, same as on the second attempt.

#### Actual Behavior

First attempt failed with `Error: HTTP request timed out [tool=html_snapshot_capture, endpoint=http://localhost:8182/mcp/call-tool, timeout=60s, sessionId=DEFAULT]: error sending request for url (http://localhost:8182/mcp/call-tool)`. Second attempt (moments later) succeeded normally.

#### Root Cause Analysis

Likely a transient backend readiness issue — the `goto` command reconnected to an existing session but the backend may not have been fully ready for the htmlsnapshot MCP call. The 60-second default timeout without retry logic means a single transient hiccup causes a hard failure. The backend was confirmed `UP` by `status` before the retry.

#### Code Pointer

``cli/browser4-cli/src/` — the HTTP client used for MCP tool calls should implement retry with exponential backoff for transient errors.`

#### AI Suggested Improvement

- Add automatic retry with exponential backoff (e.g., 3 retries) for HTTP timeout errors on MCP tool calls
- Reduce the default timeout for individual attempts to 15-20s but allow 3 retries (better UX than a single 60s wait)
- Surface whether the backend was reachable at all vs. the specific tool call timing out (the error message is ambiguous)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 2: `get text` and `get all text` return site-truncated text without indicating truncation

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "h3 a"
# Returns: "A Light in the ..."
```
The full title ("A Light in the Attic") is only available via `get attr "h3 a" title`.

#### Expected Behavior

The tool should either (a) return the full text content from the DOM, or (b) indicate when visible text is truncated and point the user to attribute alternatives.

#### Actual Behavior

The tool returns whatever `textContent` the DOM element has — which on many sites is CSS-truncated for display. A new user sees "A Light in the ..." and doesn't know whether the CLI truncated it, the snapshot truncated it, or the site truncated it. The full title is only discoverable by manually inspecting the HTML and noticing the `title` attribute.

#### Root Cause Analysis

The tool faithfully returns `textContent`, which for this site is visually truncated by the template. The `title` attribute contains the full text but there's no hint that the user should check there.

#### AI Suggested Improvement

- When output text ends with `...` or `…`, add a hint: "Text may be truncated by site CSS. Check the `title` attribute for the full text."
- Consider adding a `get fulltext` mode that checks for `title` attribute fallback when text appears truncated
- In `htmlsnapshot inspect` output, include `title` attribute values when they differ from element text

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: REJECT] The tool faithfully returns the DOM textContent — the truncation is the website's own HTML content, not a tool bug. AI agents can discover the full text by inspecting the title attribute via `get attr`. Adding ellipsis-based truncation hints would produce false positives (many texts legitimately end with '...') and the tool cannot reliably distinguish site-truncated text from intentional ellipsis.

---

### Issue 3: `--offset` in `get all` is zero-indexed but not documented as such

**Severity:** Low
**Category:** Documentation

#### Reproduction

```bash
# To get "titles 6 through 10", a user must use --offset 5:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "h3 a" --offset 5 --limit 5
```

#### Expected Behavior

Documentation should explicitly state whether `--offset` is 0-indexed or 1-indexed, with examples demonstrating each.

#### Actual Behavior

The README documents `--offset N` and `--limit N` with a single example (`--offset 10 --limit 5`) but never states the indexing base. A user wanting "the 6th through 10th items" might naturally try `--offset 6` and get items 7-11 instead.

#### Root Cause Analysis

Missing documentation detail. The code correctly uses 0-indexing (standard in programming), but this convention is not stated for non-programmer users.

#### Code Pointer

``cli/README.md` lines around 506-522 (the `htmlsnapshot get all` section)`

#### AI Suggested Improvement

- Add a sentence: "`--offset` is 0-indexed (the first match is at offset 0). Use `--offset 5 --limit 5` to get matches 6 through 10."
- Add a descriptive example: `# Get results 6 through 10 (0-indexed: offset 5 for the 6th item)`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 4: `grep -c` output is ambiguous — count of what?

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -i -c "price"
# Returns: 41
```

#### Expected Behavior

The output should indicate what is being counted (matching lines? total matches? files?). Ideally: "41 lines match" or "Matching lines: 41".

#### Actual Behavior

The output is a bare number `41`. Without a label, a user doesn't know if this is 41 lines, 41 matches, 41 files, or something else.

#### Root Cause Analysis

The `-c` flag follows grep conventions where the output is just a number, but `grep` users know this from experience. New browser4-cli users lack that context.

#### Code Pointer

`The htmlsnapshot grep command handler — output formatting for `-c` mode`

#### AI Suggested Improvement

- Label the count output: `41 matching lines` instead of bare `41`
- In `--json` mode, provide structured output: `{"count": 41, "pattern": "price"}`
- Consider adding `-o` (only matching) to show individual matches per line

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: REJECT] The bare count follows standard grep conventions that AI agents understand from context — the agent knows it invoked `grep -c` and interprets the output accordingly. Adding a label like '41 matching lines' would break the grep-compatible interface and make programmatic parsing harder for agents that consume the raw output.

---

### Issue 5: `--help` output is overwhelming — hundreds of lines with no TOC or filtering

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help`

#### Expected Behavior

Help output should be scannable, perhaps with a compact summary at the top and detailed sections below, or with a way to filter by category (e.g., `--help navigation`).

#### Actual Behavior

The help output dumps ALL ~60 commands in one long listing. A new user must scroll through hundreds of lines to understand what's available. While commands are grouped by category headers (`---` dividers), there's no table of contents at the top, and no way to get help for just one category (only individual commands via `help <command>`).

#### Root Cause Analysis

The help system lists everything by default with no category-level filtering. The `help <command>` pattern works for individual commands (e.g., `help goto`) but there's no `help navigation` or `help session` for category-level help.

#### Code Pointer

`The CLI argument parser / help generator`

#### AI Suggested Improvement

- Add a compact category summary at the top: "Categories: Navigation (5), Interaction (14), Capture (2), HTML Snapshot (8), Agent (2), Swarm (8), Session (9), Storage (16)..."
- Support category-level help: `browser4-cli help navigation` or `browser4-cli help htmlsnapshot`
- Consider adding `--help --category navigation` for filtered output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Category-level help filtering is a valid UX improvement but the current `help <command>` pattern already serves AI agents well — they can look up individual commands or parse the full listing. A TOC and category filtering would primarily benefit human discoverability. Defer until the CLI help system gets dedicated attention; the existing pattern is functional for both humans and agents.

---

### Issue 6: Dev mode invocation is verbose and error-prone

**Severity:** Low
**Category:** UX (Development)

#### Reproduction

Every command from the repo root requires:
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>
```

#### Expected Behavior

A shorter dev invocation pattern, perhaps an alias or a wrapper script documented in the README.

#### Actual Behavior

The full cargo invocation is ~80 characters before the actual command. The `development.md` reference documents `cd cli/browser4-cli && cargo run --` as an alternative, but this changes the working directory and breaks relative file paths. The `--manifest-path` pattern works from any directory but is cumbersome to type repeatedly.

#### Root Cause Analysis

Cargo's standard invocation patterns. The development workflow is documented but no convenience wrapper is provided.

#### Code Pointer

``cli/browser4-cli/` — could add a `dev.sh` wrapper or document an alias`

#### AI Suggested Improvement

- Add a `dev.sh` wrapper script at the repo root: `./dev.sh goto "https://example.com"` that passes through all arguments
- Document a shell alias in development.md: `alias b4='cargo run --manifest-path cli/browser4-cli/Cargo.toml --'`
- Consider a Makefile target: `make cli CMD="goto https://example.com"`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] This is standard Rust development-mode friction. The `cargo run --manifest-path` pattern is idiomatic and documented in `development.md`. A wrapper script or alias is a personal shell configuration preference, not a tool issue — each developer can create their own alias. Adding an official wrapper would create a maintenance burden for a non-issue in release builds.

---

### Issue 7: `htmlsnapshot summary` output is dense and difficult to parse for new users

**Severity:** Low
**Category:** UX

#### Reproduction

Run `htmlsnapshot summary` on any page.

#### Expected Behavior

The summary should be immediately useful at a glance — a structured overview with clear takeaways.

#### Actual Behavior

The WPSI output is information-rich but uses terse labels and abbreviations (e.g., "avg:195×370 score:392", "p~len/4 +id(10) +cls(5)"). A new user may not understand what "score" means, how scores are calculated, or what the abbreviations in the scale legend mean. The value is clearly there for experienced users, but the learning curve is steep.

#### Root Cause Analysis

The summary format was designed for machine consumption and advanced users, with scoring heuristics that are not explained inline.

#### Code Pointer

`The WPSI generator — scoring and formatting logic`

#### AI Suggested Improvement

- Add a brief legend at the bottom explaining score ranges and abbreviations
- Consider a `--simple` flag that shows a more human-readable version
- Add tooltips or expand the help text for `htmlsnapshot summary --help`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: REJECT] The WPSI summary format is designed for machine consumption — terse labels and scoring abbreviations are exactly what AI agents parse efficiently. Adding human-readable legends or a `--simple` flag adds complexity without improving agent workflows. The dense format is intentional: agents get all relevant data in a compact, structured output they can reason about programmatically.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: `htmlsnapshot capture` HTTP timeout on first attempt

1. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://books.toscrape.com/"` — succeeds
2. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot capture` — times out after 60s

#### Issue 2: `get text` and `get all text` return site-truncated text without indicating truncation

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get text "h3 a"
# Returns: "A Light in the ..."
```
The full title ("A Light in the Attic") is only available via `get attr "h3 a" title`.

#### Issue 3: `--offset` in `get all` is zero-indexed but not documented as such

```bash
# To get "titles 6 through 10", a user must use --offset 5:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "h3 a" --offset 5 --limit 5
```

#### Issue 4: `grep -c` output is ambiguous — count of what?

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -i -c "price"
# Returns: 41
```

#### Issue 5: `--help` output is overwhelming — hundreds of lines with no TOC or filtering

Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help`

#### Issue 6: Dev mode invocation is verbose and error-prone

Every command from the repo root requires:
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>
```

#### Issue 7: `htmlsnapshot summary` output is dense and difficult to parse for new users

Run `htmlsnapshot summary` on any page.

