# Browser4-CLI Usability Evaluation Report — HTML Snapshot Workflow

**Date:** 2026-07-09  
**Evaluator:** Claude (AI agent acting as first-time user)  
**Task:** Explore books.toscrape.com using htmlsnapshot inspect, summary, get, grep, and X-SQL query  
**CLI Invocation:** `cargo run --manifest-path cli/browser4-cli/Cargo.toml --`

---

## A. Task Result

All 8 sub-tasks completed successfully:

1. ✅ Navigated to `http://books.toscrape.com/` and captured an HTML snapshot (64 KB, 20 images, 94 links, 100 interactive elements).
2. ✅ Ran `htmlsnapshot inspect` without a selector — auto-discovery found `.product_pod` (20 matches) and provided high-quality selector suggestions.
3. ✅ Ran `htmlsnapshot inspect ".product_pod" --max 5 --depth 3` — correctly limited analysis to 5 of 20 elements at depth 3.
4. ✅ Generated page summary (WPSI) — returned compressed overview with 4 link groups, 23 landmarks, 20 content nodes, and stats.
5. ✅ Extracted all 20 book titles via `htmlsnapshot get all attr "article.product_pod h3 a" title` (full titles from the `title` attribute; `get all text` returned CSS-truncated text).
6. ✅ Validated selectors with `htmlsnapshot grep -c` — confirmed 20 "Add to basket" buttons match the 20 product pods.
7. ✅ Wrote and executed an X-SQL query extracting titles and prices, sorted by price ascending — all 20 rows returned correctly with correlated fields.
8. ✅ Explored sidebar with `htmlsnapshot inspect ".sidebar" — --max 5 --depth 4` and extracted all 51 category links.

### Key Data Extracted

- **Book titles (full):** 20 titles extracted via `title` attribute
- **Book prices:** 20 prices extracted via `p.price_color`
- **Sidebar categories:** 51 category links extracted
- **X-SQL correlated result:** 20 rows with title + price, sorted by price (£13.99 – £57.25)

---

## B. Execution Trace

### Commands Used

| # | Command | Purpose |
|---|---------|---------|
| 1 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` | Learned available commands |
| 2 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://books.toscrape.com/"` | Navigated to target page |
| 3 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot` | Captured static HTML snapshot |
| 4 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect` | Auto-discovered page structure |
| 5 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect ".product_pod" --max 5 --depth 3` | Targeted inspection of product area |
| 6 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot summary` | Generated page summary |
| 7 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "h3 a"` | First attempt at titles (truncated) |
| 8 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get html "article.product_pod h3 a"` | Verified truncation is in the HTML source |
| 9 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all attr "article.product_pod h3 a" title` | Extracted full titles from title attribute |
| 10 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -c "Add to basket" --selector ".product_pod"` | Validated selector presence (returned 1 — single-element scope) |
| 11 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -c "Add to basket"` | Counted total matches (20 — correct) |
| 12 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -l "£" --selector ".product_pod"` | Validated currency symbol in product card |
| 13 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot query --sql @extract-books-titles-prices.sql` | X-SQL query for titles + prices |
| 14 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect ".sidebar" --max 5 --depth 4` | Explored sidebar structure |
| 15 | `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text ".sidebar li a"` | Extracted category list |

### Important Decisions

- **Used `title` attribute for full titles** after discovering `get all text` returns CSS-truncated visible text
- **Used `@file.sql` pattern** for X-SQL to avoid shell escaping issues
- **Discovered `.product_pod` via auto-discovery** (no prior knowledge of the page markup needed)

### Workarounds Required

- **Truncated text workaround:** Used `attr` mode with `title` attribute instead of `text` mode to get full book titles
- **grep --selector scope clarification:** Learned that `--selector` uses querySelector semantics (single-element scope); used un-scoped grep for aggregate counts

---

## C. Issues Found

### Issue 1: `get all text` returns CSS-truncated visible text — users may not know to check `title` attributes

**Severity:** Medium

**Category:** Documentation / UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://books.toscrape.com/"
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "h3 a"
```
Output: `["A Light in the ...","Tipping the Velvet","Soumission",...]` — truncated titles.

**Expected:** Either full titles, or a clear indication that the truncated text is from CSS `text-overflow: ellipsis` and that attribute-based extraction might yield full values.

**Actual:** `get all text` returns the visible `textContent` which is truncated by the website's CSS. Users must independently discover that the `title` attribute contains full text and switch to `get all attr ... title`.

**Root Cause:** `get all text` extracts `textContent` from the DOM, which reflects rendered (CSS-affected) text. The site uses CSS truncation for narrow columns. The `title` attribute on `<a>` tags contains the full title but there's no hint to check attributes.

**Code Pointer:**

**AI Suggested Improvement:**
- In `htmlsnapshot inspect` output, when a text node is truncated in the sample, add a hint like "💡 Text appears truncated; check for a `title` attribute if the full text is needed."
- In `htmlsnapshot get all text` output, when all extracted values end with "..." (ellipsis), emit a tip suggesting `get all attr <selector> title` as an alternative.
- Add a note to the SKILL.md and htmlsnapshot reference documentation about the `textContent` vs `title` attribute distinction for truncated text.

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: `htmlsnapshot grep --selector` uses querySelector (single-element) scope, not querySelectorAll

**Severity:** Medium

**Category:** UX / Documentation

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot grep -c "Add to basket" --selector ".product_pod"
```
Output: `1` (only counts within the first `.product_pod`)

**Expected:** Either count across all `.product_pod` elements (20), or documentation that clearly states `--selector` scopes to a single element.

**Actual:** Returns `1` because `--selector` uses `querySelector` (first match only). A user expecting to validate that a selector covers all expected elements would be misled.

**Root Cause:** The backend `html_snapshot_scrape` tool likely uses `querySelector` rather than `querySelectorAll`. The documentation says "Scope search to a specific CSS element" which implies single-element scope but doesn't explicitly warn about it.

**Code Pointer:**

**AI Suggested Improvement:**
- Update the `--selector` help text to say: "Scope search to the **first matching** CSS element (querySelector semantics)"
- Add a note to the htmlsnapshot reference documentation clarifying that `--selector` scopes to the first match, and that un-scoped grep + `-c` should be used to count across all elements
- Consider adding a `--selector-all` flag that concatenates HTML from all matching elements, enabling scoped multi-element search

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Session reuse message is confusing for first-time users

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "http://books.toscrape.com/"
```
Output: `Reconnected to existing session on https://news.ycombinator.com/item?id=48834405`

**Expected:** A clean message indicating a new page was loaded, or at least that the session is being reused with the new URL.

**Actual:** The message references a completely different URL (news.ycombinator.com) from a prior session, which is confusing and provides no useful context about the current action. The actual navigation to books.toscrape.com succeeded but the reconnect message steals attention.

**Root Cause:** A previous browser session was still alive on the backend. The CLI reconnects to the existing browser window and navigates to the new URL, but the "Reconnected to" message shows the old URL rather than the target URL.

**Code Pointer:**

**AI Suggested Improvement:**
- Change message to: "Reconnected to existing browser session. Navigating to http://books.toscrape.com/..."
- Or simply suppress the reconnect message for `goto` when the navigation succeeds
- Show the target URL prominently in the navigation confirmation, de-emphasizing the session reconnect detail

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Inspect sample structure doesn't show nested children at all requested depths

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect ".product_pod" --max 5 --depth 3
```
Output shows `h3  [589 621.5 195 20]  "A Light in the ..."` without revealing the nested `<a>` tag inside.

**Expected:** At `--depth 3`, the sample structure should show that `h3` contains an `<a>` child, since `h3` is at depth 1 and `a` is at depth 2, both within the `--depth 3` limit.

**Actual:** The `<a>` tag inside `<h3>` is not shown in the sample structure. It's only hinted at by the suggested selector `h3:expr(a>0)`.

**Root Cause:** The inspect algorithm may be collapsing leaf elements that have only a single text child, or the display logic prunes nodes that add no structural diversity. This is a display choice, not a data loss issue, but it reduces transparency.

**Code Pointer:**

**AI Suggested Improvement:**
- Show at least one level of text-containing children even when they're "simple" (e.g., `h3 > a "Full Title Text"`)
- Add a visual indicator (e.g., `▶`) when children have been elided from the display
- Consider adding a `--verbose` flag that shows the complete tree without structural pruning

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: X-SQL query output format is raw JSON, inconsistent with other commands

**Severity:** Low

**Category:** UX

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot query --sql @query.sql
```
Output is a raw JSON object with `id`, `statusCode`, `resultSet`, `finishTime`, etc.

**Expected:** A consistent output format across `htmlsnapshot` subcommands. `get all` returns a clean JSON array. `inspect` returns structured text. `query` returns a verbose API response envelope.

**Actual:** The query output wraps the `resultSet` in a large JSON envelope with internal metadata (`id`, `statusCode`, `pageStatusCode`, `pageContentBytes`, `isDone`, `event`, `lastModifiedTime`, `finishTime`, `status`). The actual data is nested under `resultSet`.

**Root Cause:** `htmlsnapshot query` uses the scrape API endpoint, which returns a full job result envelope. Other `htmlsnapshot` commands return cleaner output. This is an API design inconsistency.

**Code Pointer:**

**AI Suggested Improvement:**
- When invoked from the CLI, extract and display only `resultSet` by default (with `--json` to get the full envelope)
- Format the result as a table (like the `--format table` option mentioned in crawl docs) or a clean JSON array
- Add `--format json|table|csv` flag to `htmlsnapshot query` for output control

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `cargo run` development cycle has ~0.5s overhead per invocation

**Severity:** Low

**Category:** UX (Development)

**Reproduction:** Every `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>` invocation outputs `Finished dev profile [unoptimized + debuginfo] target(s) in 0.48s` even when no source files changed.

**Expected:** Near-instant invocation when binary is already compiled (sub-100ms).

**Actual:** 0.5s compile-check overhead per command. Across 15+ commands in a session, this adds ~7.5s of dead time.

**Root Cause:** `cargo run` always performs a freshness check even when nothing changed. This is standard Cargo behavior, not specific to browser4-cli.

**AI Suggested Improvement:**
- Document `cargo build` followed by direct binary invocation (`./cli/browser4-cli/target/debug/browser4-cli <command>`) as the faster development workflow
- Add a convenience script (e.g., `cli/dev-cli.sh`) that checks if the binary is fresh and invokes it directly
- Add a note to the development reference about this

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: Sidebar inspect shows `a:expr(a>0)` as a high-quality selector — confusing pseudo-class expression

**Severity:** Low

**Category:** Discoverability

**Reproduction:**
```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect ".sidebar" --max 5 --depth 4
```
Suggested selector: `★ 5/5 (100%) a:expr(a>0) → "Books" | "Travel" | "Mystery"`

**Expected:** A more intuitive selector expression, like simply `a` (which is also listed as structural), or a clearer explanation of what `:expr(a>0)` means.

**Actual:** `a:expr(a>0)` is listed as a "high-quality" selector using PowerCSS `:expr()` syntax. For a first-time user, `a:expr(a>0)` (meaning "`<a>` elements that contain at least one `<a>` child") is non-obvious and seems circular. The bare `a` selector is listed under "Structural (bare tags, low specificity)" which correctly identifies it as low-specificity but might lead users to choose the confusing `:expr()` variant.

**Root Cause:** The `:expr()` syntax is a PowerCSS extension for visual-feature selectors. The expression `a>0` means "has more than 0 `<a>` children." This is useful for distinguishing parent anchor elements from leaf anchor elements, but the syntax is domain-specific and not explained in the inspect output.

**AI Suggested Improvement:**
- Add a brief tooltip next to `:expr()` selectors in inspect output, e.g., `a:expr(a>0)` (has `<a>` children)
- In the inspect output legend, add a line explaining `:expr()` syntax
- Consider showing a human-readable label alongside the technical selector, e.g., `a (parent links)`

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
**Fully completed.** All 8 sub-tasks executed successfully with correct results. One workaround was required (using `attr title` instead of `text` for full book titles).

### Estimated Task Success Rate
**95%.** The core workflows (capture → inspect → get → query) all worked as documented. The only friction was the truncated text discovery (Issue #1) and understanding `--selector` scope (Issue #2).

### Number of Issues Found
**7 issues** — 2 Medium, 5 Low severity. No Critical or High severity issues found.

### Major Blockers
**None.** No blocking issues encountered. All tasks were completable within the documented workflows.

### Most Confusing Aspects
1. **Truncated text from `get all text`** — the full data was there but in an unexpected place (`title` attribute). A tip in the output would have saved time.
2. **`--selector` scope** — the single-element scope of `grep --selector` was surprising. Documentation clarification needed.
3. **Session reconnect message** — seeing a reference to an unrelated URL (news.ycombinator.com) when navigating to a new page was momentarily disorienting.

### Most Valuable Improvements
- Auto-discovery in `htmlsnapshot inspect` is **excellent** — found `.product_pod` without any hints
- The `@file.sql` pattern for X-SQL is **well-designed** — completely avoids shell escaping issues
- The suggested selectors with coverage percentages and sample values are **actionable and accurate**
- The page summary (WPSI) provides a **useful compressed overview** of page structure

### Overall Usability Rating: **8/10**

The htmlsnapshot workflow is well-designed, well-documented, and reliable. The inspect → get → query pipeline forms a coherent data extraction workflow that would be usable by a new user after reading the documentation. The auto-discovery feature is a standout — it eliminates the need to know page markup ahead of time. The main areas for improvement are UX polish (tips for truncated text, clarifying `--selector` scope, cleaner query output) rather than functional gaps.

**Strengths:**
- Comprehensive documentation with reference materials and 16 end-to-end scenarios
- Auto-discovery makes the tool accessible without prior DOM knowledge
- CSS selector suggestions are accurate and well-ranked
- X-SQL provides powerful correlated multi-field extraction
- `@file` / `--sql-stdin` / `--sql-base64` options for shell-safe SQL input
- Output pagination prevents overwhelming terminal output
- Rich metadata in capture output (interactive elements with bounding boxes)

**Areas for Improvement:**
- More proactive tips/hints in command output (truncation, attribute alternatives)
- Clearer documentation of `--selector` single-element scope
- Cleaner default output format for `htmlsnapshot query`
- Reduce the session reconnect message noise
- Development workflow ergonomics (`cargo run` overhead)
