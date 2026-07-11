# Browser4-CLI Usability Evaluation: OSS Health Report Task

**Evaluator:** Claude (first-time user perspective)
**Date:** 2026-07-10
**Task:** Evaluate health of browser automation open source projects on GitHub
**browser4-cli invocation:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet --`

---

## A. Task Result

✅ **Task completed successfully.** The report `oss-health-report.md` was produced with health scores for the top 3 browser automation repositories on GitHub:
1. SeleniumHQ/selenium: 92/100 (Excellent)
2. lightpanda-io/browser: 78/100 (Good)
3. vercel-labs/agent-browser: 62/100 (Moderate)

---

## B. Execution Trace

### Commands Used (in order)
1. `cargo run --manifest-path ... -- --help` — learn available commands
2. `cargo run --manifest-path ... -- goto "https://github.com/search"` — navigate to search
3. `cargo run --manifest-path ... -- snapshot -v 0 --stdout` — view page structure
4. `cargo run --manifest-path ... -- fill e226 "browser automation stars:>1000"` — fill search
5. `cargo run --manifest-path ... -- press Enter` — submit search
6. `cargo run --manifest-path ... -- wait --load networkidle` — wait for results
7. `cargo run --manifest-path ... -- snapshot -v 0 --stdout` — read results (66KB output)
8. `cargo run --manifest-path ... -- goto` each repo's `/issues` page (×3)
9. `cargo run --manifest-path ... -- snapshot grep "Open.*Closed"` — extract issue counts (×3)
10. `cargo run --manifest-path ... -- goto` each repo main page (×3)
11. `cargo run --manifest-path ... -- htmlsnapshot` — capture structured page data (×3)
12. `cargo run --manifest-path ... -- goto` contributors page (×1, abandoned due to chart rendering)
13. `gh api repos/...` — GitHub API for supplemental data (×3 for repo info, ×3 for contributors)

### Key Decisions
- Used `snapshot grep` instead of reading full snapshots to find issue counts — more efficient
- Used `htmlsnapshot` for structured repo metadata (stars, forks, commits) — excellent choice
- Fell back to GitHub API (`gh api`) for contributor counts because GitHub's contributors page renders data as charts invisible to accessibility tree
- Did NOT fall back to any other browser automation tool (Playwright, Puppeteer, etc.)

### Workarounds Required
1. **Contributor counts inaccessible via browser4-cli alone:** GitHub renders contributor data as SVG charts. Neither snapshot nor htmlsnapshot could extract the contributor count. Used `gh api` as a workaround.
2. **Working directory resets after every command:** Had to prefix every command with `cd "D:/workspace/Browser4/Browser4-4.11" &&` because the shell resets to `C:\Users\pereg` after each invocation.
3. **Large snapshots:** 66KB+ accessibility trees required separate file reads rather than inline inspection.

---

## C. Issues Found

### Issue 1: Shell working directory resets after every cargo run invocation

**Severity:** Medium

**Category:** UX

**Reproduction:** Run any `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- ...` command. After execution, `Shell cwd was reset to C:\Users\pereg`.

**Expected:** The shell should preserve the working directory (repo root) between commands so users don't need to repeatedly `cd`.

**Actual:** Every command output ends with `Shell cwd was reset to C:\Users\pereg`, causing the next command without a `cd` prefix to execute from the home directory instead of the repo root.

**Root Cause:** The tool invocation sandbox resets the working directory after each Bash call. This is a harness-level behavior, not a browser4-cli issue per se, but it significantly impacts the CLI experience because `cargo run --manifest-path` fails silently or confusingly when PWD is wrong.

**Code Pointer:** N/A (harness behavior, not browser4-cli code)

**AI Suggested Improvement:**
- Document this behavior prominently in the development guide — new users will hit this immediately
- Consider recommending an alias or wrapper script: `alias b4='cd /path/to/repo && cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet --'`
- If possible, the SKILL.md could mention this known friction point

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: Accessibility tree snapshots too large for inline inspection

**Severity:** Medium

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "https://github.com/search?q=browser+automation+stars%3A%3E1000&type=repositories"
cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- snapshot -v 0 --stdout
```

**Expected:** A human-readable, scannable page representation that can be consumed inline.

**Actual:** The snapshot output for a typical GitHub page is 66KB-78KB of YAML, mostly navigation menus, dropdown content, and invisible elements. The output is truncated to a file and requires additional tool calls to read. The preview (first 10 lines) shows only the navigation bar — never the search results.

**Root Cause:** The accessibility tree includes ALL DOM nodes, including massive hidden navigation dropdowns (GitHub's mega-menus). GitHub pages appear to have particularly bloated accessibility trees. The `-v 0` viewport pagination helps but doesn't filter the irrelevant navigation boilerplate.

**Code Pointer:** `cli/browser4-cli/src/snapshot.rs` — consider adding content-aware filtering

**AI Suggested Improvement:**
- Add a `--no-nav` or `--content-only` flag that filters out `<nav>`, `<header>`, and `<footer>` regions from the snapshot
- Increase the preview from 10 lines to 30-50 lines so users can actually see page content without opening a file
- Consider a `--summary` mode that shows only interactive elements with their refs, not the full tree
- Add a `--max-size` flag to truncate snapshots to a reasonable limit with a note about what was omitted

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: `snapshot grep` is powerful but hard to discover

**Severity:** Medium

**Category:** Discoverability

**Reproduction:** Look at `browser4-cli --help` output. `snapshot grep` appears as a single line under the `snapshot` command. A first-time user would likely not discover it.

**Expected:** Key quality-of-life commands like `snapshot grep` should be featured prominently in the help output or highlighted in tips after taking a snapshot.

**Actual:** `snapshot grep` is buried in the help output. The tip after `snapshot` says "Run `snapshot -v 0` to see interactive element refs" — it never mentions `snapshot grep` as an alternative for large pages.

**Root Cause:** The help system lists `snapshot grep` as a sub-command of `snapshot`, but the tip after `snapshot` only suggests viewport pagination, not text searching. `snapshot grep` was the single most useful command for completing this task — finding "Open...Closed" counts in 78KB of YAML would have been impossible otherwise.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — update the tip text after `snapshot`

**AI Suggested Improvement:**
- Add a tip after `snapshot` that says: `💡 Tip: Use snapshot grep "pattern" to search for text in large snapshots instead of reading the full file`
- Consider adding `snapshot grep` examples to the `--help` output for the `snapshot` command
- Elevate `snapshot grep` in the SKILL.md quick patterns section — it's currently listed but not prominent

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: `eval` JavaScript approach fails silently for data extraction

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- goto "https://github.com/lightpanda-io/browser/issues"
cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- eval "JSON.stringify({openCount: document.querySelector('.open.issues-repo-filter-link .Counter')?.textContent || 'N/A'})" --json
```

**Expected:** The eval should return `{openCount: "77"}` or a meaningful error indicating the selector didn't match.

**Actual:** Returns `{"openCount":"N/A"}` with no indication of WHY the selector didn't match. The user has no way to know if the selector is wrong, the element is dynamically rendered, or the page hasn't loaded.

**Root Cause:** GitHub uses React and dynamically-generated class names. The CSS selectors visible in the DOM inspector don't match the actual rendered class names. The `eval` command provides no debugging information — no console output, no DOM snapshot, no error messages about selector matching.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — eval command implementation

**AI Suggested Improvement:**
- Add a `--debug` flag to `eval` that returns console errors and warnings alongside the result
- Consider adding a `selector-test` command: `browser4-cli selector-test ".my-class"` that returns the count of matching elements
- Document in SKILL.md that GitHub (and other React SPAs) use hashed class names and recommend using `snapshot grep` + refs instead of CSS selectors for such sites
- The `htmlsnapshot inspect` command could be promoted as a pre-eval step to discover valid selectors

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Chart/visualization data is invisible to accessibility tree extraction

**Severity:** High

**Category:** Product

**Reproduction:**
1. Navigate to any GitHub contributors page (e.g., `https://github.com/lightpanda-io/browser/graphs/contributors`)
2. Try to extract contributor count via `snapshot grep` or `htmlsnapshot get`
3. The contributor graph is rendered as SVG/canvas elements with no text content in the accessibility tree

**Expected:** Either the accessibility tree should expose chart data, or there should be an alternative extraction method for visualizing pages.

**Actual:** The contributors page renders contribution data as a bar chart. The accessibility tree contains only structural elements (headings, links) but no numerical data. Neither `snapshot grep`, `htmlsnapshot get`, nor `eval` could extract the contributor count. The task had to fall back to the GitHub API.

**Root Cause:** GitHub renders the contributors graph using SVG/Canvas elements. The CDP accessibility tree doesn't include SVG text content or chart data. This is a fundamental limitation of the accessibility-tree approach — any data rendered visually (charts, graphs, Canvas) is invisible to browser4-cli's extraction mechanisms.

**Code Pointer:** N/A — architectural limitation of CDP accessibility tree

**AI Suggested Improvement:**
- Add a `chart-extract` or `visual-data` command that attempts to extract data from SVG/canvas elements
- Document this limitation clearly in SKILL.md under "Known Limitations"
- Consider enhancing `eval` with helper functions for common extraction patterns (e.g., `browser4.extractTable()`, `browser4.extractListItems()`)
- Recommend using `htmlsnapshot` for pages with tabular/text data, and acknowledge that chart-heavy pages need API access

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: No built-in GitHub-specific extraction helpers

**Severity:** Low

**Category:** Feature Request / UX

**Reproduction:** Attempt to extract structured data (issue counts, stars, forks, contributors) from GitHub repository pages. Each metric requires manual snapshot parsing, grep, or HTML snapshot analysis.

**Expected:** Given how common GitHub is as a target for browser automation tasks, there could be site-specific helper commands or patterns documented.

**Actual:** Every metric requires manual discovery of text patterns and manual extraction. For example, finding open/closed issue counts requires knowing the exact text format ("Open  (N)") and using `snapshot grep`.

**Root Cause:** browser4-cli is designed as a general-purpose browser automation tool. Site-specific extraction is the user's responsibility.

**AI Suggested Improvement:**
- Document common GitHub extraction patterns in a reference file (e.g., `references/github-extraction.md`)
- Consider a `recipe` or `macro` system that bundles common extraction sequences
- Add more GitHub-specific examples to SKILL.md since GitHub is likely a top-3 target site

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `htmlsnapshot` and `snapshot` have overlapping but different capabilities — confusing for new users

**Severity:** Medium

**Category:** Discoverability / UX

**Reproduction:** A new user reads the help output and sees both `snapshot` (accessibility tree) and `htmlsnapshot` (static HTML capture). The decision tree for when to use which is documented in SKILL.md but not surfaced at decision time.

**Expected:** Clear, in-context guidance about which snapshot type to use for the current task.

**Actual:** The user has to internalize the decision tree from SKILL.md (which they might not have read thoroughly). Using the wrong snapshot type leads to silent failures (e.g., trying to use `snapshot grep` when `htmlsnapshot grep` would be better, or vice versa).

**Root Cause:** Two parallel snapshot systems with different strengths. The distinction (accessibility tree vs. static HTML) is technical and not obvious from the command names.

**AI Suggested Improvement:**
- Add a brief guidance table to the `snapshot --help` and `htmlsnapshot --help` output indicating when to use each
- Add a tip to `snapshot` output: `💡 For static data extraction use htmlsnapshot; for interactive element refs use snapshot`
- Consider merging the grep functionality: a single `grep` command that searches both snapshot types
- Rename for clarity: `snapshot` → `ax-snapshot` or `live-snapshot`, keep `htmlsnapshot` as-is
- Or provide a unified `capture` command that captures both and routes queries appropriately

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Snapshot file accumulation — no automatic cleanup

**Severity:** Low

**Category:** UX

**Reproduction:** Run 15+ browser4-cli commands that trigger snapshots (goto, fill, click, snapshot, htmlsnapshot). Check `.browser4-cli/snapshot/` directory.

**Expected:** Old snapshots should be automatically cleaned up or a command should exist to manage them.

**Actual:** Every interaction creates a new timestamped snapshot YAML file. Over a session, dozens of files accumulate. No `snapshot clean` or `snapshot prune` command exists in the help output.

**Root Cause:** No snapshot lifecycle management is implemented. Snapshots are created eagerly but never deleted.

**AI Suggested Improvement:**
- Add a `snapshot clean` or `snapshot prune --keep-last N` command
- Implement automatic cleanup: keep only last N snapshots per session
- Add a snapshot count/disk usage indicator to `status` or `doctor` command
- Document snapshot storage location and expected accumulation in SKILL.md

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: No progress indicator or timeout feedback during page loads

**Severity:** Low

**Category:** UX

**Reproduction:** Run `goto` to a heavy page (e.g., GitHub with many DOM elements). No progress feedback is shown during the page load.

**Expected:** Some indication that the page is loading and the command hasn't hung — a spinner, elapsed time, or loading state.

**Actual:** The command blocks silently until the page loads or times out. If the page takes 15+ seconds, the user has no way to know if it's working or stuck.

**Root Cause:** `goto` uses `wait --load networkidle`-like behavior but doesn't surface intermediate state to the user.

**AI Suggested Improvement:**
- Show a loading indicator or elapsed time for navigation commands
- Add a `--timeout` flag to `goto` with a clear error message if exceeded
- Report page load time in the output (some pages already show "331 ms" in the snapshot)
- Consider a `--verbose` flag that shows loading milestones (DOMContentLoaded, networkidle, etc.)

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: `--manifest-path` invocation is verbose and error-prone for dev mode

**Severity:** Medium

**Category:** Developer Experience

**Reproduction:** Every dev-mode command requires typing:
```
cargo run --manifest-path cli/browser4-cli/Cargo.toml --quiet -- <command>
```
That's 70+ characters before the actual browser4-cli command.

**Expected:** A shorter dev-mode invocation, or a documented alias pattern.

**Actual:** The full `cargo run --manifest-path` invocation must be typed every time. The development.md mentions this pattern but doesn't suggest creating an alias.

**Root Cause:** This is inherent to Cargo's project structure. The `--manifest-path` is needed because commands run from the repo root, not the CLI crate directory.

**AI Suggested Improvement:**
- Document a shell alias prominently: `alias b4='cargo run --manifest-path /path/to/cli/browser4-cli/Cargo.toml --quiet --'`
- Consider a `dev.sh`/`dev.ps1` wrapper script in the repo root that handles the invocation
- Add a Makefile or justfile target: `make cli goto "https://example.com"`
- Consider `cargo install --path cli/browser4-cli` as a one-time setup for dev work

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
✅ **Task completed.** The `oss-health-report.md` was produced with comprehensive health scores for all three repositories.

### Estimated Task Success Rate
**85%.** The task was completed successfully, but two significant workarounds were needed:
1. GitHub API (`gh api`) for contributor counts (chart data invisible to accessibility tree)
2. Repeated `cd` commands due to shell directory resets

### Number of Issues Found
**10 issues** across the following categories:
- Product: 1 (chart data extraction)
- UX: 4 (working directory, large snapshots, snapshot accumulation, progress feedback)
- Discoverability: 2 (snapshot grep discovery, htmlsnapshot vs snapshot confusion)
- Reliability: 1 (eval silent failure)
- Developer Experience: 1 (verbose invocation)
- Feature Request: 1 (GitHub-specific helpers)

### Major Blockers
1. **Chart/visualization data extraction** (Issue 5) — This is the only hard blocker encountered. Any task requiring data from chart-heavy pages (contributor graphs, analytics dashboards, etc.) cannot be completed with browser4-cli alone. API fallback is required.

### Most Confusing Aspects
1. **When to use `snapshot` vs. `htmlsnapshot`** — Both capture page state but serve different purposes. The distinction is clear after reading SKILL.md carefully, but the command names don't make it obvious.
2. **Why `eval` selectors fail** — No debugging feedback when CSS selectors don't match. GitHub's hashed class names make this worse.
3. **Snapshot file management** — Snapshots accumulate silently. It's not clear which snapshot corresponds to which page state without opening each file.

### Most Valuable Improvements
1. **`snapshot grep`** — The single most useful command for this task. Being able to search 78KB accessibility trees with regex patterns was essential.
2. **`htmlsnapshot` interactive elements list** — The structured list of buttons, inputs, links with CSS classes and bounding boxes was excellent for understanding page structure at a glance.
3. **`wait --load networkidle`** — Reliable page load synchronization. Never had a race condition issue.
4. **Comprehensive `--help` output** — Well-organized with clear command groupings (Navigation, Mouse, Keyboard, Capture, etc.)

### Overall Usability Rating: **7.2/10**

**Strengths:**
- Core workflow (goto → snapshot → interact → extract) is well-designed and intuitive
- `snapshot grep` is a killer feature for data extraction from complex pages
- `htmlsnapshot` provides excellent structured page metadata
- Help output is comprehensive and well-organized
- The SKILL.md is thorough with decision trees and quick patterns
- Page loading and waiting are reliable — no flakiness encountered

**Weaknesses:**
- Accessibility tree snapshots are too verbose for practical inline use
- Chart/visualization data is a hard extraction gap
- `eval` provides no debugging feedback for selector failures
- Dev mode invocation is verbose (70+ chars before the actual command)
- Snapshot file management is missing
- Two parallel snapshot systems (`snapshot`/`htmlsnapshot`) create cognitive overhead
- No progress feedback during page loads
