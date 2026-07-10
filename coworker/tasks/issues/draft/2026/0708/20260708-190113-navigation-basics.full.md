All 8 steps completed successfully. Now let me compile the full evaluation report.

---

# Browser4-CLI Usability Evaluation Report

**Date:** 2026-07-09
**Evaluator:** Claude (AI Agent)
**Task:** Wikipedia navigation, history, reload, session management
**CLI Invocation:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`

---

## A. Task Result

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

## B. Execution Trace

### Commands used:
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

### Important decisions:
- Used `snapshot grep` to search the full in-memory tree after discovering viewport-based snapshots were inadequate for scrolled content
- Used a ref discovered via `snapshot grep` directly for the click operation, assuming the full-tree refs remain valid
- Chose "Data scraping" as the "See also" link target due to its close semantic relationship to the starting article

### Workarounds required:
1. **Viewport snapshot limitation**: Standard `snapshot -v 0` after scrolling returns truncated trees. Switched to `snapshot grep` which searches the full in-memory tree.
2. **Discovering page sections**: Had to grep the snapshot file directly to find the "See also" section since the in-CLI `snapshot grep` only showed the TOC entry on first attempt.

---

## C. Issues Found

### Issue 1: Viewport snapshots produce truncated accessibility trees after scrolling

**Severity:** High

**Category:** Reliability

**Reproduction:**
```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://en.wikipedia.org/wiki/Web_scraping"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 5000
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0
```

**Expected:** The snapshot should show all interactive elements visible in the current viewport, including links, buttons, and other DOM content scrolled into view.

**Actual:** The snapshot contains only 80 nodes, with the entire page content collapsed into a single `generic` node (`generic "Web scraping" [ref=e1751]` with one child `generic [ref=e1757]`). All inner content (paragraphs, headings, links, lists) is absent. The CLI itself warns: *"The accessibility tree may not have been re-expanded after scrolling. This is a known server-side limitation."*

**Root Cause:** The server-side accessibility tree does not re-expand child nodes when the viewport shifts via scrolling. Only the top-level structural nodes remain accessible. This is a fundamental limitation of how the CDP accessibility tree is managed after scroll events — the backend likely only requests the AX tree once during initial page load and does not request child node expansion on subsequent viewport queries.

**Code Pointer:** Backend WebDriver implementation — the method that handles accessibility tree snapshots after scroll events does not trigger re-expansion of child AX nodes.

**AI Suggested Improvement:**
- After a scroll event, re-request the full accessibility tree from CDP with `fetchFullAXTree` or equivalent, rather than relying on the cached partial tree
- Implement a force-refresh flag on snapshot (`snapshot --force-full-tree`) that always re-fetches from CDP
- When the tree is detected as truncated (child-count mismatch vs expected), automatically re-fetch

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Snapshot grep refs are undocumented as usable targets for click/fill/etc.

**Severity:** Medium

**Category:** Documentation

**Reproduction:**
1. Run `snapshot grep "See also"` to find refs in the full in-memory tree
2. Use a ref from grep output (e.g., `e2418`) with `click`
3. There is no documentation confirming this is a supported workflow

**Expected:** Documentation should explicitly state whether refs discovered via `snapshot grep` are valid for interaction commands, and whether they share the same lifecycle as refs from standard `snapshot`.

**Actual:** Refs from `snapshot grep` work (click succeeded), but this behavior is undocumented. A new user would not know whether grep-discovered refs are safe to use. The SKILL.md only documents refs from standard `snapshot` output.

**Root Cause:** The `snapshot grep` documentation in SKILL.md (§6) is described only as a "find elements by text" tool with no mention that the refs in grep output are the same backend node IDs usable in `click`, `fill`, and other interaction commands.

**Code Pointer:** `skills/browser4-cli/SKILL.md` — the "Find Elements by Text (snapshot grep)" section

**AI Suggested Improvement:**
- Add a sentence to the `snapshot grep` documentation: "Refs shown in grep output are the same backend node IDs from the full accessibility tree and can be used directly with `click`, `fill`, and other interaction commands."
- Document the lifecycle of grep-discovered refs: they belong to the full in-memory tree and survive scrolling but not DOM-mutating actions
- Add a "Verification" pattern to §6: `snapshot grep` → identify ref → `click <ref>` → verify

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Long page navigation workflow is poorly documented

**Severity:** Medium

**Category:** Discoverability

**Reproduction:**
1. A new user needs to find content below the fold on a long page (9+ viewports)
2. The SKILL.md doesn't document the workflow for navigating long pages: scroll → snapshot → find refs
3. The viewport pagination feature (`-v N`) is documented but the scroll-then-snapshot approach for long pages isn't clearly explained

**Expected:** There should be a documented pattern for interacting with content deep in long pages, including the known viewport limitations and the recommended workaround (use `snapshot grep` for discovery).

**Actual:** The user must discover through trial and error that:
- `snapshot -v 0` after scrolling gives truncated results
- `snapshot grep` searches the full tree and reveals refs
- Refs from grep output are usable for interaction
- Scrolling to a target area is needed before clicking

**Root Cause:** The "Core Loop" in SKILL.md (§1) presents a simple linear workflow that assumes all content fits in one viewport. The viewport pagination concept is mentioned only in the snapshot command description and the footer of snapshot files, without a dedicated section on the long-page workflow.

**Code Pointer:** `skills/browser4-cli/SKILL.md` — §1 Core Loop, §6 Quick Patterns

**AI Suggested Improvement:**
- Add a "Long Pages" subsection to §6 Quick Patterns showing: scroll → snapshot grep → click ref workflow
- Include the known viewport-snapshot limitation and the grep workaround
- Add a diagram showing the relationship between viewport snapshots, the full in-memory tree, and `snapshot grep`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: First-run build time has no progress feedback

**Severity:** Low

**Category:** UX

**Reproduction:**
1. Run `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` for the first time
2. Cargo compiles the binary with no indication of how long it will take

**Expected:** Some form of progress indication, or a tip in the documentation about expected first-build time.

**Actual:** The user sees cargo compilation output (`Compiling...`) which streams build steps but gives no ETA. A first-time user may wonder if something is broken or hanging.

**Root Cause:** Cargo's default output is verbose but not time-estimated. The `--quiet` flag suppresses build output entirely, which can make the wait feel even longer.

**Code Pointer:** `skills/browser4-cli/references/development.md`

**AI Suggested Improvement:**
- Add a note in development.md: "First build takes approximately X seconds (Y dependencies to compile)"
- Recommend `cargo build` first (separately) before running commands, to separate the build wait from command execution
- Consider adding a brief spinner or progress note to the CLI itself when running in development mode

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `snapshot grep` regex support has inconsistent behavior

**Severity:** Low

**Category:** UX

**Reproduction:**
```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep "heading.*See also"
```
Returns 0 matches even though "See also" exists in the tree and headings exist.

**Expected:** The regex should match if both "heading" and "See also" appear anywhere in the same line of the snapshot. While no single line contains both "heading" and "See also", the error message could be more helpful (e.g., "No lines matched — try broader patterns or `-A N` for context").

**Actual:** Silent "0 matches found" with no guidance on how to refine the search. The user had to iteratively try different patterns.

**Root Cause:** The grep searches line-by-line in the YAML output, but the YAML structure splits heading names and content across multiple lines. A regex that spans two lines (like "heading" on one line and "See also" on the next) will never match without multiline support.

**Code Pointer:** `snapshot grep` implementation — the grep operates line-by-line without multiline regex support

**AI Suggested Improvement:**
- Add a note in `--help` output: "Patterns match individual lines. For cross-line patterns, use `-A N` to expand context."
- Consider adding a `--multiline` flag (or documenting the current limitation)
- Improve the "0 matches" output with a suggestion: "Tip: try broader patterns or add `-A N` for surrounding context lines"

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: No quick inline snapshot view for simple pages

**Severity:** Low

**Category:** UX

**Reproduction:**
1. Run `snapshot -v 0` on a page
2. Output shows file path and "first 10 lines" preview
3. To see actual content, must open file or use `--stdout`

**Expected:** A flag to print the full snapshot inline (not just a preview), or a smart default that shows more content when the snapshot is small (<100 lines).

**Actual:** Even small snapshots (80 nodes / lines) only show 10-line previews. The `--stdout` flag exists but isn't the default, requiring an extra flag to read the output.

**Root Cause:** The default snapshot output is optimized for large pages (preventing terminal flooding), but the 10-line preview is too conservative for small/medium pages where inline display would be more convenient.

**Code Pointer:** CLI snapshot rendering logic — the preview threshold is fixed at 10 lines

**AI Suggested Improvement:**
- Show full inline output for snapshots under 50 lines
- Add `snapshot --view` as a shorthand for `snapshot -v 0 --stdout`
- Make the preview threshold configurable or adaptive to terminal height

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task completion status
**Fully completed** — All 8 task steps executed successfully. The task was achievable with workarounds.

### Estimated task success rate
**87.5% (7/8 steps worked on first attempt).** The snapshot step required multiple retries with different approaches (viewport-based failed, grep-based succeeded).

### Number of issues found
**6 issues** (1 High, 2 Medium, 3 Low)

### Major blockers
1. **Viewport snapshot limitation after scrolling** (Issue 1) — The most significant issue. Without the `snapshot grep` workaround, locating and clicking elements below the fold would be effectively impossible on long pages. This is a known server-side limitation acknowledged by the CLI's own warning message.

### Most confusing aspects
1. **The viewport/snapshot/scroll interaction model** — Understanding when to use `snapshot -v N`, `scroll`, and `snapshot grep` required trial and error. The relationship between "viewports" (chunks of the page) and "scrolling" (moving the browser viewport) is conceptually clear in retrospect but not well-documented.
2. **Ref provenance** — Not knowing whether refs from `snapshot grep` are the same as refs from `snapshot -v 0` caused hesitation before clicking.

### Most valuable improvements
1. **Fix the scroll + viewport snapshot bug** (Issue 1) — This would make long-page interaction reliable without workarounds
2. **Document the long-page discovery workflow** (Issue 3) — A single documented pattern covering scroll → grep → click would save significant trial and error
3. **Clarify ref sources in documentation** (Issue 2) — Explicitly documenting that grep refs are usable for interaction would build user confidence

### Positive observations
- **Help output quality**: Excellent — well-organized with clear categories, consistent formatting, and practical tips
- **Automatic snapshots**: The `goto`, `go-back`, `go-forward`, `reload`, and `click` commands all automatically capture snapshots, saving the user from having to remember to do so
- **Session management**: `list` and `close` are simple, clear, and work reliably
- **Status command**: Clean output showing version, port, health status
- **History navigation**: `go-back`/`go-forward` work exactly as expected
- **CLI tips**: The `💡 Tip:` messages on stderr are helpful (e.g., "Run `snapshot -v 0` to see interactive element refs")
- **Self-aware warnings**: The CLI warns about known limitations (e.g., the viewport scroll issue)
- **File path output**: Snapshot file paths are clearly displayed and clickable

### Overall usability rating: **7/10**

**Rationale:**
- **Strengths (+3)**: Clean CLI design, automatic snapshots, excellent help output, consistent command patterns, good session management, useful tips/warnings
- **Weaknesses (-3)**: The viewport scrolling issue is a significant reliability gap for any task involving long pages, the documentation doesn't cover the long-page workflow, and ref provenance is ambiguous. These aren't edge cases — most real-world pages require scrolling and interacting with below-fold content.
- **Verdict**: browser4-cli is well-designed for simple, above-the-fold interactions. For production use involving long pages, the viewport snapshot limitation needs to be addressed or more prominently documented with clear workarounds.
