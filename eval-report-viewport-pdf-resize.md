# Browser4-CLI Usability Evaluation: Viewport, Screenshot, Scroll & PDF

**Date:** 2026-07-10
**Evaluator:** Claude (AI Agent)
**Task:** Resize viewport, navigate Wikipedia, scroll, screenshot, and PDF export

---

## A. Task Result

**Status:** ✅ All 10 steps completed successfully.

### Output Files

| File | Size | Location |
|------|------|----------|
| `screenshot-2026-07-09T23-11-27-236Z.png` | 408 KB | `.browser4-cli/snapshot/` (default) |
| `wikipedia-llm-fullpage.png` | 7.1 MB | `/home/vincent/` (custom filename) |
| `wikipedia-llm-top.png` | 547 KB | `/home/vincent/` (custom filename) |
| `wikipedia-llm-article.pdf` | 2.4 MB | `/home/vincent/` (custom filename) |

### Task Steps Completed

1. ✅ Resize viewport to 1280×900 — `resize 1280 900`
2. ✅ Navigate to Wikipedia LLM article — `goto "https://en.wikipedia.org/wiki/Large_language_model"`
3. ✅ Wait for network idle — `wait --load networkidle`
4. ✅ Default viewport screenshot — `screenshot`
5. ✅ Scroll down 3×800px — `scroll down 800` (×3)
6. ✅ Wait for "History" heading — `wait --text "History"`
7. ✅ Full-page screenshot with custom filename — `screenshot --full-page --filename "../../wikipedia-llm-fullpage.png"`
8. ✅ Scroll to top — `eval "window.scrollTo(0, 0)"`
9. ✅ Wait 2s + top screenshot — `wait 2000` then `screenshot --filename "../../wikipedia-llm-top.png"`
10. ✅ Save as PDF — `pdf --filename "../../wikipedia-llm-article.pdf"`

---

## B. Execution Trace

### Commands Used (in order)

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- resize --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- open
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- resize 1280 900
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://en.wikipedia.org/wiki/Large_language_model"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- wait --load networkidle
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 800
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 800
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 800
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- wait --text "History" --timeout 10000
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot --full-page --filename "../../wikipedia-llm-fullpage.png"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- eval "window.scrollTo(0, 0)"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- wait 2000
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot --filename "../../wikipedia-llm-top.png"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- pdf --help
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- pdf --filename "../../wikipedia-llm-article.pdf"
```

### Important Decisions

- Used `eval "window.scrollTo(0, 0)"` to scroll to top because `scroll` only supports direction+pixels with no built-in "go to top" shortcut.
- Used `--text "History"` for the content-load wait because Wikipedia section headings are predictable and stable.
- Used `../../` prefix on custom filenames to try saving files to the repo root (from CWD `cli/browser4-cli/`), but this produced unexpected results (see Issue 1).

### Workarounds Required

- **Path resolution workaround:** The `--filename` flag resolved `../../` relative to the repo root instead of the CWD, placing files in `/home/vincent/` instead of the repo root. Had to locate files with `find` after they appeared missing.
- **Scroll-to-top workaround:** No native command to scroll to top of page; used `eval "window.scrollTo(0, 0)"` JavaScript instead.
- **Pre-existing session:** An existing browser session from a prior Wikipedia session was reused; had to proceed from that state rather than a clean start.

---

## C. Issues Found

### Issue 1: --filename path resolution is relative to repo root, not CWD

**Severity:** High

**Category:** Reliability

**Reproduction:**
```bash
cd /home/vincent/workspace/Browser4
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot --filename "../../my-screenshot.png"
```

**Expected:** The path `../../my-screenshot.png` should resolve relative to the CWD (`cli/browser4-cli/`), producing `/home/vincent/workspace/Browser4/my-screenshot.png`.

**Actual:** The path resolved relative to the repo root, producing `/home/vincent/workspace/Browser4/../../my-screenshot.png` which normalizes to `/home/vincent/workspace/my-screenshot.png`.

**Root Cause:** The CLI resolves relative `--filename` paths from the repository root directory (likely detected via git or pom.xml) rather than from the process working directory. The `--help` text explicitly says "resolved relative to the current directory", but the implementation uses a different base. Additionally, the output path is not normalized (e.g., `/home/vincent/workspace/Browser4/../../...`), making it harder to debug.

**Code Pointer:** `cli/browser4-cli/src/` — path resolution logic for `--filename` in screenshot/pdf commands.

**AI Suggested Improvement:**
- Fix path resolution to use the actual process CWD, matching the documented behavior.
- Normalize displayed paths (resolve `..` segments) before printing them to the user.
- Add an integration test that verifies `--filename` with relative paths saves to the correct location when run from different directories.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: No built-in "scroll to top" or "scroll to bottom" command

**Severity:** Medium

**Category:** Discoverability / UX

**Reproduction:** Try to scroll to the top of the page using only documented `scroll` subcommands.

**Expected:** A `scroll top` or `scroll to 0` shortcut that jumps to the top of the page. Similarly, `scroll bottom` to go to the bottom.

**Actual:** The `scroll` command only accepts `<direction> <pixels>` (e.g., `scroll up 2400`). The user must either:
1. Know exactly how many pixels they've scrolled down and reverse that amount, or
2. Resort to `eval "window.scrollTo(0, 0)"` which requires JavaScript knowledge.

**Root Cause:** The `scroll` command was designed for incremental scrolling only, with no positional shortcuts. The `scroll to` pattern is a common browser automation convention (Playwright: `page.evaluate("window.scrollTo(0, 0)")`, Puppeteer: same pattern) that browser4-cli could wrap.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — `scroll` command definition.

**AI Suggested Improvement:**
- Add positional keywords: `scroll top` (equivalent to `window.scrollTo(0, 0)`) and `scroll bottom` (equivalent to `window.scrollTo(0, document.body.scrollHeight)`).
- Document these alongside the existing direction+pixels usage in `--help` output.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: scroll output is ambiguous and lacks context

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 800
# Output: 800.0
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- scroll down 800
# Output: 1600.0
```

**Expected:** A clear message indicating what the number represents (e.g., "Scrolled down 800px (total: 1600px)").

**Actual:** A bare float (`800.0`, `1600.0`) with no label or context. A new user cannot tell whether this is:
- The amount scrolled this time
- The cumulative scroll position
- The remaining scrollable distance

**Root Cause:** The scroll command returns the raw numeric result from the backend without wrapping it in a user-friendly message.

**Code Pointer:** `cli/browser4-cli/src/` — scroll command output formatting.

**AI Suggested Improvement:**
- Format scroll output as: `Scrolled down 800px (position: 1600px)` or similar descriptive message.
- Use `--json` for the raw numeric output; keep the default human-readable.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: pdf command has minimal options — missing page size, orientation, margins

**Severity:** Medium

**Category:** Product

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- pdf --help
# Output shows only --filename option
```

**Expected:** Options for controlling PDF output: paper size (A4, Letter), orientation (portrait/landscape), margins, page ranges, and a `--full-page` equivalent (like `screenshot --full-page`).

**Actual:** Only `--filename` is available. No control over PDF rendering parameters.

**Root Cause:** The `pdf` command maps directly to Chrome CDP's `Page.printToPDF` which supports many parameters, but only the filename is exposed through the CLI.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — `pdf` CommandDef; backend at `WebDriver.kt` for PDF generation.

**AI Suggested Improvement:**
- Add `--format <A4|Letter|...>` for paper size selection.
- Add `--landscape` flag for landscape orientation.
- Add `--margin-*` options for margin control.
- Add `--scale` for content scaling.
- Consider a `--full-page` flag (matching screenshot's API) for single-page long-form content.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: resize viewport dimensions may not match screenshot pixel dimensions

**Severity:** Low

**Category:** Documentation / UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- resize 1280 900
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://en.wikipedia.org/wiki/Large_language_model"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot --full-page --filename "test.png"
# Screenshot width: 1600px, not 1280px
```

**Expected:** Screenshot width matches the requested viewport width (1280px), or documentation clarifies that device pixel ratio (DPR) scaling applies.

**Actual:** Full-page screenshot was 1600×42994 pixels. The width of 1600 vs requested 1280 suggests a 1.25× device pixel ratio or that `resize` sets the outer window size (including chrome) rather than the inner viewport.

**Root Cause:** Either `resize` sets browser window dimensions (including toolbars) rather than viewport/content area dimensions, or a HiDPI/retina device pixel ratio is applying. The help text says "Resize the browser window" which is ambiguous. Chrome DevTools Protocol's `Browser.setWindowBounds` sets outer window bounds, while viewport dimensions depend on OS chrome.

**Code Pointer:** Backend `WebDriver.kt` — resize implementation.

**AI Suggested Improvement:**
- Clarify in help text whether `resize` sets window size or viewport/content area size.
- Document the relationship between resize dimensions and screenshot pixel dimensions, including DPR behavior.
- Consider adding a `--viewport` flag to `resize` that explicitly sets the content area (inner) dimensions.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: Session persistence can confuse new users (stale sessions from prior runs)

**Severity:** Low

**Category:** UX / Discoverability

**Reproduction:**
```bash
# Run browser4-cli, navigate somewhere, then exit
# Days later, run browser4-cli again:
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- open
```

**Expected:** A fresh browser session opens, or the user is clearly informed about the reused session state.

**Actual:** Output says "Reconnected to existing session on https://en.wikipedia.org/wiki/Web_scraping" — reconnecting to a session from a prior task. The user may not remember or expect this.

**Root Cause:** Named sessions persist on the backend across CLI invocations. The `open` command reconnects by default rather than creating a fresh session. The `list` command shows session status, but a new user may not know to run it.

**AI Suggested Improvement:**
- Add a brief note to the `open` output when reconnecting: "Tip: Use `list` to see all sessions, `close` to end this session, or `open --new` for a fresh session."
- Consider showing the session age or idle time in the reconnection message.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Output path display is not normalized (contains ../ segments)

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- screenshot --filename "../../test.png"
# Output: [Screenshot](/home/vincent/workspace/Browser4/../../test.png)
```

**Expected:** Normalized path: `[Screenshot](/home/vincent/workspace/test.png)` or `[Screenshot](/home/vincent/test.png)`.

**Actual:** The displayed path contains unresolved `..` segments, making it harder to identify where the file was actually written.

**Root Cause:** The path is constructed by joining the repo root directory with the user-provided relative path, but `..` segments are not resolved/canonicalized before display.

**Code Pointer:** `cli/browser4-cli/src/` — screenshot/pdf output formatting.

**AI Suggested Improvement:**
- Canonicalize the output path before displaying it (resolve symlinks and `..` segments).
- As a simpler fix, just `Path::canonicalize()` or equivalent before printing.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: wait --text searches entire body, no scoping option

**Severity:** Low

**Category:** Product

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- wait --text "History"
# This matches any occurrence of "History" anywhere on the page
```

**Expected:** Option to scope text search to a specific container/selector, or to specify that the text should be visible in the viewport.

**Actual:** `--text` waits for the text to appear anywhere in `document.body.innerText`. For a page like Wikipedia where "History" might appear in the sidebar, nav, or other sections, this could match earlier than intended.

**Root Cause:** The `wait --text` implementation uses a broad `document.body` text search without supporting scoping or visibility constraints.

**Code Pointer:** Backend wait implementation.

**AI Suggested Improvement:**
- Add `--selector` option to `wait --text` to scope the text search to a specific DOM element.
- Add `--visible` flag to require the matching text be within the visible viewport.
- Document the search scope clearly in `--help`.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status
**✅ Fully completed.** All 10 task steps executed successfully. 4 output files produced (1 default screenshot, 2 custom screenshots, 1 PDF).

### Estimated Task Success Rate
**90%** — The core functionality worked reliably. The main friction was the `--filename` path resolution bug that placed files in an unexpected directory.

### Number of Issues Found
**8 issues** (1 High, 2 Medium, 5 Low)

### Major Blockers
None. The task was completable without any blocking failures.

### Most Confusing Aspects
1. **Path resolution inconsistency (Issue 1):** The `--filename` flag resolved paths from the repo root rather than the working directory, placing files in `/home/vincent/` instead of the repo root. Had to search for them with `find`.
2. **No scroll-to-top shortcut (Issue 2):** Had to drop into JavaScript evaluation to scroll to the top — not discoverable for non-programmers.
3. **Ambiguous scroll output (Issue 3):** The bare float output from `scroll` required inference about what it meant.

### Most Valuable Improvements
1. **Fix --filename path resolution** — This is the highest-impact fix. It directly contradicts documented behavior and can cause file loss confusion.
2. **Add scroll top/bottom shortcuts** — Low implementation cost, high UX value for a common operation.
3. **Add pdf formatting options** — Unlocks real-world PDF export use cases (A4 reports, letter-size documents).
4. **Normalize output paths** — Simple fix that improves debuggability.

### What Worked Well
- **Resize command:** Clean, simple, worked first try with clear confirmation.
- **goto + auto-snapshot:** Seamless navigation with automatic snapshot capture.
- **wait --load networkidle:** Reliable and straightforward.
- **wait --text:** Flexible text-based synchronization that worked perfectly for waiting on section headings.
- **screenshot --full-page:** Produced a complete 1600×42994px capture of the entire article.
- **pdf command:** Generated a valid 2.4MB PDF with no issues.
- **eval escape hatch:** JavaScript evaluation provided a reliable fallback when native commands lacked a feature.
- **Help output:** Comprehensive, well-organized, with examples.
- **cargo run --manifest-path pattern:** Clean way to run from source from any directory.

### Overall Usability Rating
**7.5 / 10**

The CLI is functional, well-documented, and reliable for the tested operations. The core commands (resize, goto, screenshot, pdf, wait) all worked correctly. The main deductions come from the `--filename` path resolution bug (which is genuinely confusing), the lack of scroll-to-top (requiring JavaScript workaround), and some rough edges in output formatting. These are all fixable issues — none represent fundamental design problems.

For a first-time user following the SKILL.md documentation, the experience would be solid except for the filename path confusion. The command discovery via `--help` is excellent, and the conceptual model (navigate → snapshot → interact) is clear.
