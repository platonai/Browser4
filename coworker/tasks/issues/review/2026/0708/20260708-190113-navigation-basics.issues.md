# Issues: navigation-basics

> **Source:** `20260708-190113-navigation-basics.full.md` | **Date:** 20260708-190113 | **Mode:** dev

## Scenario Background

### Task

**Task completion status:** Fully completed (8/8 steps successful)

### Steps completed:
1. ✅ **Navigated to Wikipedia** — `goto "https://en.wikipedia.org/wiki/Web_scraping"` loaded successfully
2. ✅ **Took snapshot, identified "See also" link** — Used `snapshot grep "See also"` to discover refs in full in-memory tree; clicked "Data scraping" (ref=e2418)
3. ✅ **History back** — `go-back` returned to Web scraping article
4. ✅ **History forward** — `go-forward` returned to Data scraping article
5. ✅ **Page reload** — `reload` refreshed the Data scraping page
6. ✅ **Server status** — `status` confirmed server UP, version v4.11.15
7. ✅ **Active sessions listed** — `list` showed one DEFAULT session
8. ✅ **Session closed** — `close` terminated browser; `list` confirmed empty

---

### Execution Context

**Key Commands:**

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://en.wikipedia.org/wiki/Web_scraping"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep "See also"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep "heading.*See also"  (no results)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep -A 30 "See also"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 5000
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0  (truncated — only 80 nodes)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 8  (only 15 nodes — server limitation warning)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 2000
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0  (still truncated)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep -A 30 "See.also"  (found refs!)
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- click e2418
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- go-back
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- go-forward
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- reload
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- status
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- list
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- close
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- list  (verification)
```

**Workarounds Applied During Task:**

1. **Viewport snapshot limitation**: Standard `snapshot -v 0` after scrolling returns truncated trees. Switched to `snapshot grep` which searches the full in-memory tree.
2. **Discovering page sections**: Had to grep the snapshot file directly to find the "See also" section since the in-CLI `snapshot grep` only showed the TOC entry on first attempt.

---

---

## Issues Found (6 issues)

### Issue 1: Viewport snapshots produce truncated accessibility trees after scrolling

**Severity:** High
**Category:** Reliability

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://en.wikipedia.org/wiki/Web_scraping"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 5000
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0
```

#### Expected Behavior

The snapshot should show all interactive elements visible in the current viewport, including links, buttons, and other DOM content scrolled into view.

#### Actual Behavior

The snapshot contains only 80 nodes, with the entire page content collapsed into a single `generic` node (`generic "Web scraping" [ref=e1751]` with one child `generic [ref=e1757]`). All inner content (paragraphs, headings, links, lists) is absent. The CLI itself warns: *"The accessibility tree may not have been re-expanded after scrolling. This is a known server-side limitation."*

#### Root Cause Analysis

The server-side accessibility tree does not re-expand child nodes when the viewport shifts via scrolling. Only the top-level structural nodes remain accessible. This is a fundamental limitation of how the CDP accessibility tree is managed after scroll events — the backend likely only requests the AX tree once during initial page load and does not request child node expansion on subsequent viewport queries.

#### Code Pointer

`Backend WebDriver implementation — the method that handles accessibility tree snapshots after scroll events does not trigger re-expansion of child AX nodes.`

#### AI Suggested Improvement

- After a scroll event, re-request the full accessibility tree from CDP with `fetchFullAXTree` or equivalent, rather than relying on the cached partial tree
- Implement a force-refresh flag on snapshot (`snapshot --force-full-tree`) that always re-fetches from CDP
- When the tree is detected as truncated (child-count mismatch vs expected), automatically re-fetch

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

### Issue 2: Snapshot grep refs are undocumented as usable targets for click/fill/etc.

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. Run `snapshot grep "See also"` to find refs in the full in-memory tree
2. Use a ref from grep output (e.g., `e2418`) with `click`
3. There is no documentation confirming this is a supported workflow

#### Expected Behavior

Documentation should explicitly state whether refs discovered via `snapshot grep` are valid for interaction commands, and whether they share the same lifecycle as refs from standard `snapshot`.

#### Actual Behavior

Refs from `snapshot grep` work (click succeeded), but this behavior is undocumented. A new user would not know whether grep-discovered refs are safe to use. The SKILL.md only documents refs from standard `snapshot` output.

#### Root Cause Analysis

The `snapshot grep` documentation in SKILL.md (§6) is described only as a "find elements by text" tool with no mention that the refs in grep output are the same backend node IDs usable in `click`, `fill`, and other interaction commands.

#### Code Pointer

``skills/browser4-cli/SKILL.md` — the "Find Elements by Text (snapshot grep)" section`

#### AI Suggested Improvement

- Add a sentence to the `snapshot grep` documentation: "Refs shown in grep output are the same backend node IDs from the full accessibility tree and can be used directly with `click`, `fill`, and other interaction commands."
- Document the lifecycle of grep-discovered refs: they belong to the full in-memory tree and survive scrolling but not DOM-mutating actions
- Add a "Verification" pattern to §6: `snapshot grep` → identify ref → `click <ref>` → verify

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

### Issue 3: Long page navigation workflow is poorly documented

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

1. A new user needs to find content below the fold on a long page (9+ viewports)
2. The SKILL.md doesn't document the workflow for navigating long pages: scroll → snapshot → find refs
3. The viewport pagination feature (`-v N`) is documented but the scroll-then-snapshot approach for long pages isn't clearly explained

#### Expected Behavior

There should be a documented pattern for interacting with content deep in long pages, including the known viewport limitations and the recommended workaround (use `snapshot grep` for discovery).

#### Actual Behavior

The user must discover through trial and error that:
- `snapshot -v 0` after scrolling gives truncated results
- `snapshot grep` searches the full tree and reveals refs
- Refs from grep output are usable for interaction
- Scrolling to a target area is needed before clicking

#### Root Cause Analysis

The "Core Loop" in SKILL.md (§1) presents a simple linear workflow that assumes all content fits in one viewport. The viewport pagination concept is mentioned only in the snapshot command description and the footer of snapshot files, without a dedicated section on the long-page workflow.

#### Code Pointer

``skills/browser4-cli/SKILL.md` — §1 Core Loop, §6 Quick Patterns`

#### AI Suggested Improvement

- Add a "Long Pages" subsection to §6 Quick Patterns showing: scroll → snapshot grep → click ref workflow
- Include the known viewport-snapshot limitation and the grep workaround
- Add a diagram showing the relationship between viewport snapshots, the full in-memory tree, and `snapshot grep`

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

### Issue 4: First-run build time has no progress feedback

**Severity:** Low
**Category:** UX

#### Reproduction

1. Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` for the first time
2. Cargo compiles the binary with no indication of how long it will take

#### Expected Behavior

Some form of progress indication, or a tip in the documentation about expected first-build time.

#### Actual Behavior

The user sees cargo compilation output (`Compiling...`) which streams build steps but gives no ETA. A first-time user may wonder if something is broken or hanging.

#### Root Cause Analysis

Cargo's default output is verbose but not time-estimated. The `--quiet` flag suppresses build output entirely, which can make the wait feel even longer.

#### Code Pointer

``skills/browser4-cli/references/development.md``

#### AI Suggested Improvement

- Add a note in development.md: "First build takes approximately X seconds (Y dependencies to compile)"
- Recommend `cargo build` first (separately) before running commands, to separate the build wait from command execution
- Consider adding a brief spinner or progress note to the CLI itself when running in development mode

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] Development-mode friction — Cargo compilation time is third-party tool behavior, not a browser4-cli concern. This does not affect AI agent workflows; it only matters during human development setup. Would need a fundamentally different distribution model (pre-built binaries) to change.

---

### Issue 5: `snapshot grep` regex support has inconsistent behavior

**Severity:** Low
**Category:** UX

#### Reproduction

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep "heading.*See also"
```
Returns 0 matches even though "See also" exists in the tree and headings exist.

#### Expected Behavior

The regex should match if both "heading" and "See also" appear anywhere in the same line of the snapshot. While no single line contains both "heading" and "See also", the error message could be more helpful (e.g., "No lines matched — try broader patterns or `-A N` for context").

#### Actual Behavior

Silent "0 matches found" with no guidance on how to refine the search. The user had to iteratively try different patterns.

#### Root Cause Analysis

The grep searches line-by-line in the YAML output, but the YAML structure splits heading names and content across multiple lines. A regex that spans two lines (like "heading" on one line and "See also" on the next) will never match without multiline support.

#### Code Pointer

``snapshot grep` implementation — the grep operates line-by-line without multiline regex support`

#### AI Suggested Improvement

- Add a note in `--help` output: "Patterns match individual lines. For cross-line patterns, use `-A N` to expand context."
- Consider adding a `--multiline` flag (or documenting the current limitation)
- Improve the "0 matches" output with a suggestion: "Tip: try broader patterns or add `-A N` for surrounding context lines"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid issue — silent "0 matches" output on failed cross-line regex can mislead AI agents into thinking content doesn't exist. Fix should focus on: (1) documenting the line-by-line limitation in --help, (2) improving "0 matches found" with a tip like "Try broader patterns or add -A N for context lines", rather than implementing full multiline regex support.

---

### Issue 6: No quick inline snapshot view for simple pages

**Severity:** Low
**Category:** UX

#### Reproduction

1. Run `snapshot -v 0` on a page
2. Output shows file path and "first 10 lines" preview
3. To see actual content, must open file or use `--stdout`

#### Expected Behavior

A flag to print the full snapshot inline (not just a preview), or a smart default that shows more content when the snapshot is small (<100 lines).

#### Actual Behavior

Even small snapshots (80 nodes / lines) only show 10-line previews. The `--stdout` flag exists but isn't the default, requiring an extra flag to read the output.

#### Root Cause Analysis

The default snapshot output is optimized for large pages (preventing terminal flooding), but the 10-line preview is too conservative for small/medium pages where inline display would be more convenient.

#### Code Pointer

`CLI snapshot rendering logic — the preview threshold is fixed at 10 lines`

#### AI Suggested Improvement

- Show full inline output for snapshots under 50 lines
- Add `snapshot --view` as a shorthand for `snapshot -v 0 --stdout`
- Make the preview threshold configurable or adaptive to terminal height

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [x] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: REJECT] AI agents read snapshot YAML files directly from disk — the terminal preview line count is irrelevant to agent workflows. The 10-line preview is a human debugging convenience, and --stdout already provides inline output when needed. This change would only benefit interactive human use.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Viewport snapshots produce truncated accessibility trees after scrolling

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://en.wikipedia.org/wiki/Web_scraping"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 5000
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0
```

#### Issue 2: Snapshot grep refs are undocumented as usable targets for click/fill/etc.

1. Run `snapshot grep "See also"` to find refs in the full in-memory tree
2. Use a ref from grep output (e.g., `e2418`) with `click`
3. There is no documentation confirming this is a supported workflow

#### Issue 3: Long page navigation workflow is poorly documented

1. A new user needs to find content below the fold on a long page (9+ viewports)
2. The SKILL.md doesn't document the workflow for navigating long pages: scroll → snapshot → find refs
3. The viewport pagination feature (`-v N`) is documented but the scroll-then-snapshot approach for long pages isn't clearly explained

#### Issue 4: First-run build time has no progress feedback

1. Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` for the first time
2. Cargo compiles the binary with no indication of how long it will take

#### Issue 5: `snapshot grep` regex support has inconsistent behavior

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep "heading.*See also"
```
Returns 0 matches even though "See also" exists in the tree and headings exist.

#### Issue 6: No quick inline snapshot view for simple pages

1. Run `snapshot -v 0` on a page
2. Output shows file path and "first 10 lines" preview
3. To see actual content, must open file or use `--stdout`

