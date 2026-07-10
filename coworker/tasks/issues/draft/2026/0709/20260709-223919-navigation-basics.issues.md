# Issues: navigation-basics

> **Source:** `20260709-223919-navigation-basics.full.md` | **Date:** 20260709-223919 | **Mode:** dev

## Scenario Background

### Task

✅ **All 8 task steps completed successfully:**

1. ✅ Navigated to `https://en.wikipedia.org/wiki/Web_scraping`
2. ✅ Took a snapshot showing accessible elements (42 KB, 576 nodes)
3. ✅ Identified and clicked the "Data scraping" link in the "See also" section
4. ✅ Used `go-back` to return to Web scraping, then `go-forward` to Data scraping
5. ✅ Reloaded the Data scraping page with `reload`
6. ✅ Checked server status: version `v4.11.15`, server `UP` on port 8182
7. ✅ Listed sessions: 1 active default session, then confirmed empty after close
8. ✅ Closed the session cleanly

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| 1 | `cargo run -- help` | Retrieved full command listing |
| 2 | Read `skills/browser4-cli/SKILL.md` | Learned command patterns, lifecycle, warning |
| 3 | `cargo run -- goto "https://en.wikipedia.org/wiki/Web_scraping"` | Navigated to the article (reused existing session) |
| 4 | `cargo run -- snapshot -v 0` | 576 nodes, 42 KB snapshot captured |
| 5 | `cargo run -- snapshot grep -C 30 "See also"` | Found TOC entry for "See also" section |
| 6 | `cargo run -- scroll down 8000` | Scrolled to bottom of page |
| 7 | `cargo run -- snapshot --stdout --all \| grep "heading.*See also"` | Found all "See also" links with refs (viewport limitations forced alternative approach) |
| 8 | `cargo run -- scroll up 1000` | Adjusted scroll position ...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)

### Issue 1: Viewport snapshots return near-empty trees for scrolled-to positions

**Severity:** High
**Category:** Reliability

#### Reproduction

```
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"
browser4-cli scroll down 8000
browser4-cli snapshot -v 0
```

#### Expected Behavior

Snapshot should show the accessibility tree for the currently visible viewport area, including the "See also" section and its links.

#### Actual Behavior

Snapshot returns only 14-81 lines (mostly header/nav elements), with a warning: "The accessibility tree may not have been re-expanded after scrolling. This is a known server-side limitation."

#### Root Cause Analysis

The accessibility tree is not automatically re-expanded after scrolling with `scroll` commands. The server-side CDP implementation does not trigger `DOM.getOuterHTML` or `Accessibility.getFullAXTree` for the newly visible portion of the page after programmatic scrolling.

#### Code Pointer

``browser4-core/` — the accessibility tree expansion logic after scroll events.`

#### AI Suggested Improvement

- After a `scroll` command completes, automatically request a full accessibility tree snapshot that includes newly visible elements
- Or add a `--refresh-ax-tree` flag to `snapshot` to force re-expansion of the accessibility tree after scrolling
- Consider using `DOM.scrollIntoViewIfNeeded` or similar CDP commands to ensure elements are in the accessibility tree before snapshotting

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 2: Build output noise on every command invocation in dev mode

**Severity:** Low
**Category:** UX

#### Reproduction

Any `cargo run -- <command>` invocation in dev mode.

#### Expected Behavior

Clean command output without Rust compilation messages.

#### Actual Behavior

Every command prints two lines of build status before actual output:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.50s
     Running `target/debug/browser4-cli goto '...'`
```

#### Root Cause Analysis

`cargo run` always prints compilation status to stderr, which the eval harness captures alongside stdout. This is inherent to Cargo's output model and affects the dev-mode experience.

#### Code Pointer

`Not a code bug — this is Cargo tooling behavior.`

#### AI Suggested Improvement

- Document in `skills/browser4-cli/SKILL.md` that dev mode includes build output
- Consider a wrapper script or shell alias (`b4`) that suppresses cargo build output with `cargo run -q` or `2>/dev/null`
- Add a dev-mode setup step: `cargo build` first, then use `./target/debug/browser4-cli` directly

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 3: `snapshot` viewport command output is confusingly verbose vs. `--stdout`

**Severity:** Medium
**Category:** UX / Discoverability

#### Reproduction

```
browser4-cli snapshot -v 0
```

#### Expected Behavior

The snapshot content should be the primary output.

#### Actual Behavior

By default, `snapshot -v 0` saves to a file and shows only a 10-line preview, plus a tip to use `--stdout`. The user has to take extra steps (open file OR re-run with `--stdout`) to see the content. The tip about `--stdout` is helpful but adds friction for first-time users who just want to see the page structure.

#### Root Cause Analysis

The default behavior prioritizes file saving over inline viewing. The 10-line preview is often insufficient to understand page structure, especially for large pages where the important content is below the first 10 lines.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` — snapshot output formatting logic.`

#### AI Suggested Improvement

- Make `--stdout` the default behavior and add `--save` for file-saving use cases
- Or increase the preview to 30-50 lines for better quick scanning
- Display a more helpful hint: "10 of 576 lines shown. Use --stdout for full output or --page 2 for more."

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 4: No CSS selector support for `click` command — requires snapshot refs or JavaScript

**Severity:** Medium
**Category:** Product / UX

#### Reproduction

```
browser4-cli click "a[href*='Data_scraping']"
```

#### Expected Behavior

The click command should accept CSS selectors as targets.

#### Actual Behavior

The `click` command only accepts element refs (`e5`, `backend:15`) from snapshot output, not CSS selectors. When refs are unavailable (due to viewport limitations), the user must fall back to JavaScript `eval` to perform clicks.

#### Root Cause Analysis

The `click` command maps to `browser_click` MCP tool which requires a backend node ID reference. CSS selector resolution is not implemented in the click workflow.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — click command definition (`looks_like_selector_or_ref` function could be extended).`

#### AI Suggested Improvement

- Extend `click` (and other interaction commands) to accept CSS selectors, resolving to the first matching element
- Add a `--selector` flag as an alternative to positional ref for all interaction commands
- Document the limitation clearly in help output: "click <ref> requires a snapshot ref; use eval for CSS selectors"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 5: `snapshot grep` output formatting includes `:` prefix on matching lines

**Severity:** Low
**Category:** UX

#### Reproduction

```
browser4-cli snapshot grep -C 30 "See also"
```

#### Expected Behavior

Standard grep-style output with optional `-` prefix for context lines and `:` for matching lines (or no prefix).

#### Actual Behavior

All lines are prefixed with `-` (e.g., `204:-                  - listitem ...`), making it confusing to distinguish matching vs. context lines at a glance. The format mixes YAML tree markers with grep markers.

#### Root Cause Analysis

The `- ` prefix is the YAML list item marker for the accessibility tree, and the grep output prepends line numbers. The combined format is visually noisy.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` — snapshot grep output formatting.`

#### AI Suggested Improvement

- Use standard grep markers: `:` for matching lines, `-` for context lines, instead of mixing with YAML syntax
- Or strip the YAML formatting from grep output and show raw lines with grep-style prefixes
- At minimum, document the output format in help text

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

### Issue 6: `list` command shows empty table after `close` without clear confirmation

**Severity:** Low
**Category:** UX

#### Reproduction

```
browser4-cli close
browser4-cli list
```

#### Expected Behavior

After closing, `list` should show a message like "No active sessions" or show the previous session as "Closed" with status.

#### Actual Behavior

`list` shows an empty table with column headers but zero rows, which could leave the user uncertain whether the command worked or the backend is unresponsive.

#### Root Cause Analysis

The table rendering shows headers even when no sessions exist, with no empty-state message.

#### Code Pointer

``cli/browser4-cli/src/` — session list rendering.`

#### AI Suggested Improvement

- Add an empty-state message: "No active browser sessions."
- Or keep the closed session row with status "Closed"/"Terminated" for one more query

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**


---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Viewport snapshots return near-empty trees for scrolled-to positions

```
browser4-cli goto "https://en.wikipedia.org/wiki/Web_scraping"
browser4-cli scroll down 8000
browser4-cli snapshot -v 0
```

#### Issue 2: Build output noise on every command invocation in dev mode

Any `cargo run -- <command>` invocation in dev mode.

#### Issue 3: `snapshot` viewport command output is confusingly verbose vs. `--stdout`

```
browser4-cli snapshot -v 0
```

#### Issue 4: No CSS selector support for `click` command — requires snapshot refs or JavaScript

```
browser4-cli click "a[href*='Data_scraping']"
```

#### Issue 5: `snapshot grep` output formatting includes `:` prefix on matching lines

```
browser4-cli snapshot grep -C 30 "See also"
```

#### Issue 6: `list` command shows empty table after `close` without clear confirmation

```
browser4-cli close
browser4-cli list
```

