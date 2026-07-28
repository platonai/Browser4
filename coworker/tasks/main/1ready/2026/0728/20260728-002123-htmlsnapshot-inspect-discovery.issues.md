# Issues: htmlsnapshot-inspect-discovery

> **Source:** `20260728-002123-htmlsnapshot-inspect-discovery.full.md` | **Date:** 20260728-002123 | **Mode:** dev

## Scenario Background

### Task

All 9 task steps completed. The final X-SQL query extracted **20 books** with full titles, prices, and product URLs from `http://books.toscrape.com/`. The key workaround: `get text` returns CSS-truncated visible text (e.g., "A Light in the ..."), so I used `get attr` with the `title` attribute to get complete titles. The `htmlsnapshot inspect` tool's auto-discovery correctly identified `.product_pod` as the repeating product card pattern and surfaced `p.price_color` and `h3:expr(a>0)` as high-quality selectors.

Full extracted dataset (`books_query_full.sql`):
- 20 rows × 3 columns (title, price, url)
- Prices range £13.99 – £57.25
- All product detail URLs are relative

---

### Execution Context

| Step | Command | Outcome |
|------|---------|---------|
| 1. Navigate | `goto "http://books.toscrape.com/"` | ✅ Page loaded, title "All products \| Books to Scrape - Sandbox" |
| 2. Snapshot | `htmlsnapshot` | ✅ 64 KB captured, 20 images, 94 links, 100 interactive elements |
| 3. Inspect (no selector) | `htmlsnapshot inspect` | ✅ Auto-discovered `.product_pod` (20 matches), suggested `p.price_color`, `h3:expr(a>0)`, etc. |
| 4. Inspect (scoped) | `htmlsnapshot inspect ".product_pod" --max 5 --depth 3` | ✅ Analyzed 5/20, confirmed consistent structure |
| 5. Summary | `htmlsnapshot summary` | ✅ WPSI generated: 23 landmarks, 4 link groups, 3 lists |
| 6. Extract titles | `htmlsnapshot get all text "h3:expr(a>0)"` | ⚠️ 20 titles extracted but **truncated** (e.g., "A Light in the ...") |
| 6...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)

### Issue 1: Text truncation in htmlsnapshot get text — visible text clipped at CSS overflow

**Severity:** High
**Category:** Product

#### Reproduction

htmlsnapshot get all text ".product_pod h3 a" on books.toscrape.com (or any page where text is CSS-truncated with ellipsis).

#### Expected Behavior

Full text content of the element (e.g., "A Light in the Attic"), or at minimum a warning that text was truncated.

#### Actual Behavior

Returns CSS-visible text only: "A Light in the ..." — the same text as rendered on screen, clipped by the container width.

#### Root Cause Analysis

The HTML snapshot stores computed visible text. When a site uses `text-overflow: ellipsis` or `overflow: hidden` on a narrow container (the h3 is only 195px wide), the snapshot captures the truncated rendering. The `title` attribute on the `<a>` child contains the full text but is not surfaced by `get text` or flagged by `inspect`.

#### AI Suggested Improvement

- Add a truncation warning to `get text` output when text is clipped (detect via comparing scrollWidth vs clientWidth, or via CSS text-overflow detection)
- `inspect` should surface attribute-based selectors (e.g., `a[title]`) alongside text-based ones, especially when text appears truncated
- Consider offering a `--full-text` flag that reads textContent via JavaScript rather than the snapshot's rendered text
- In the inspect output, flag elements whose visible text differs from their title attribute as a data-quality hint

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Data-correctness issue — users receive silently truncated text with no warning. The snapshot system captures CSS-rendered visible text rather than DOM textContent, which is a fundamental design choice that needs at minimum a truncation warning and ideally a `--full-text` escape hatch.

---

### Issue 2: htmlsnapshot grep --selector requires a pattern argument, breaking selector-validation workflow

**Severity:** Medium
**Category:** UX

#### Reproduction

htmlsnapshot grep --selector ".product_pod"

#### Expected Behavior

Either list all matching elements/their text, show match count, or accept --selector as a standalone filtering mode without a required pattern.

#### Actual Behavior

Error: Pattern is required. Provide a positional pattern, or use -e PATTERN (repeatable) for multiple patterns.

#### Root Cause Analysis

The grep command is designed primarily for content search, with --selector as a scoping filter. It doesn't support a selector-only mode for element counting or listing. Users wanting to validate a selector's element count must supply a dummy pattern (e.g., grep -c "£" --selector-all ".product_pod").

#### AI Suggested Improvement

- Support `--count-only` or `--match-count` flag that returns the number of elements matching --selector without requiring a pattern
- Alternatively, allow an empty pattern when --selector is provided (treat no pattern as "match all content within selector")
- Add a dedicated `htmlsnapshot count <selector>` subcommand or alias for quick selector validation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Legitimate UX friction — forcing a dummy pattern to count/validate selector matches is unintuitive. A `--count-only` mode or allowing empty pattern with `--selector` are both reasonable improvements. Related to Issues 5/6 in the broader discoverability theme, but addresses a distinct workflow gap.

---

### Issue 3: inspect doesn't surface attribute-based selectors when text is truncated

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Run `htmlsnapshot inspect` on books.toscrape.com. The suggested selectors include `h3:expr(a>0)` but not `h3 a[title]` or any attribute-based selector that would yield untruncated text.

#### Expected Behavior

Inspect should detect elements with `title` attributes and suggest them as alternative data sources, especially when the visible text appears truncated.

#### Actual Behavior

Only text-content and PowerCSS selectors are suggested. The `title` attribute — which contains the full, untruncated book title — is not mentioned anywhere in the inspect output.

#### Root Cause Analysis

The inspect algorithm focuses on CSS class selectors, PowerCSS :expr() patterns, and bare tags. It doesn't analyze element attributes as data sources, even though `get attr` and `DOM_FIRST_ATTR` fully support attribute extraction.

#### AI Suggested Improvement

- When inspect detects text that appears truncated (e.g., ends with "..."), scan for `title` or `aria-label` attributes on the same element or its children and surface them as alternative selectors
- Add an "Attribute sources" section to inspect output listing elements with informative attributes (title, data-*, aria-label, href, src)
- Add a hint like: "💡 Text appears truncated. Try: htmlsnapshot get all attr 'h3 a' title" when truncation is detected

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid discoverability gap that compounds Issue 1 — when text IS truncated, inspect should help users find the `title` attribute escape hatch. Closely coupled to Issue 1 (truncation detection is the prerequisite), but distinct: Issue 1 is about fixing/warning about the data loss itself; Issue 3 is about inspect proactively offering workarounds. Should be prioritized after Issue 1's truncation-detection infrastructure exists.

---

### Issue 4: X-SQL DOM_LOAD_AND_SELECT re-fetches page despite existing snapshot — inefficient and potentially confusing

**Severity:** Low
**Category:** UX

#### Reproduction

Capture snapshot with `htmlsnapshot`, then run `htmlsnapshot query --sql @query.sql` where the SQL uses DOM_LOAD_AND_SELECT(@url, ...).

#### Expected Behavior

Either reuse the already-captured snapshot, or clearly document that X-SQL always re-fetches the page.

#### Actual Behavior

The query re-fetches the page (visible from the statusCode:200 and pageContentBytes in the response), even though a snapshot was just captured. This is inefficient and could lead to different results if the page content changed between snapshot and query.

#### Root Cause Analysis

X-SQL DOM_LOAD_AND_SELECT is designed to work independently of the snapshot system — it always fetches the URL fresh. The two systems (snapshot storage vs scrape API) operate independently. The documentation mentions this pattern but doesn't emphasize the re-fetch.

#### AI Suggested Improvement

- Add a `DOM_FROM_SNAPSHOT` function or flag that queries against the stored snapshot instead of re-fetching
- Document the re-fetch behavior more prominently in the X-SQL reference and in the inspect/summary output hints
- Consider adding a warning when `query` is run shortly after `capture` on the same URL

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Legitimate inefficiency — two independent page-fetch paths (snapshot vs X-SQL) create both wasted bandwidth and potential content-drift between capture and query. A `DOM_FROM_SNAPSHOT` function is the cleanest resolution but requires significant plumbing. Accept at Low severity is appropriate given the workaround exists (re-fetch works, just inefficiently).

---

### Issue 5: Inspect auto-discovery behavior is powerful but opaque to new users

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run `htmlsnapshot inspect` without arguments. It auto-discovers `.product_pod` from `:root`, then `htmlsnapshot inspect .sidebar` auto-discovers `.sidebar li`.

#### Expected Behavior

Clear documentation of when auto-discovery fires, what it looks for, and how to override it. The user should understand why `.sidebar li` is chosen over `.sidebar`.

#### Actual Behavior

The output says "Auto-discovered repeating pattern from ':root'" and "Auto-discovered repeating pattern from '.sidebar'" but doesn't explain the algorithm. A new user might be confused about why the tool chose those specific selectors and whether they can trust the discovery.

#### Root Cause Analysis

The auto-discovery uses sibling-group detection in the DOM tree to find repeating elements (product cards, list items), then descends into the discovered container. This is sophisticated but the heuristics aren't explained to the user.

#### AI Suggested Improvement

- Add a `--verbose` flag to inspect that explains the discovery algorithm step-by-step
- Add a brief explanation in the inspect output: "Auto-discovery works by finding sibling groups — elements of the same type that repeat at the same DOM level"
- Document the discovery algorithm in the htmlsnapshot reference docs

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Reasonable transparency concern — the auto-discovery heuristic is powerful but unexplained, which undermines user trust in its output. A `--verbose` flag explaining the sibling-group detection algorithm is the right level of investment (low effort, high UX return). Cross-links with Issue 6 (both are about helping users discover what the tool can do).

---

### Issue 6: Top-level `help` output doesn't mention htmlsnapshot subcommands explicitly

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run `./b4w.ps1 help`. The htmlsnapshot section lists `capture`, `get`, `query`, `export`, `summary`, `grep`, `inspect` — but these are listed under the generic 'HTML Snapshot (htmlsnapshot)' header. A new user scanning for `inspect` or `summary` might miss it because they're indented under the parent command.

#### Expected Behavior

Key subcommands like `inspect`, `summary`, and `query` should be callable or at least visible at the top-level help, or the help should have a dedicated 'htmlsnapshot' category filter.

#### Actual Behavior

Running `./b4w.ps1 help --help extract` lists extraction commands but `inspect` and `summary` are only visible under the full help output. The category filter `--help extract` doesn't specifically highlight htmlsnapshot subcommands.

#### Root Cause Analysis

The help system organizes by category (nav, extract, session, kb, swarm, crawl) but `inspect` and `summary` are discovery tools that don't fit neatly into 'extract'. They're listed under the htmlsnapshot umbrella in the main help, which requires scrolling through all commands to find them.

#### AI Suggested Improvement

- Add an `--help htmlsnapshot` category filter that lists all htmlsnapshot subcommands with brief descriptions
- Promote `inspect` and `summary` as first-class discovery commands in the 'Common workflows' section at the top of help
- Add a tip after `htmlsnapshot capture`: "💡 Next: run `htmlsnapshot inspect` to discover CSS selectors, or `htmlsnapshot summary` for a compressed overview"

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Simple discoverability fix — `--help htmlsnapshot` as a category filter is low-effort and consistent with existing `--help extract`/`--help nav` patterns. Cross-links with Issue 5: both aim to make htmlsnapshot's discovery-oriented subcommands (inspect, summary) more visible to new users exploring the tool.

---

## Overall Assessment

**Completion Status:** Successful — all 9 task steps completed. Data extraction required one workaround (using title attribute instead of text content) due to CSS text truncation on the target site.

**Success Rate:** 100% — every documented command executed successfully, though 2 of 9 steps required a workaround or adaptation.

**Issues Found:** 6

**Major Blockers:** Text truncation in get text/inspect/query is the most significant issue — it silently returns incomplete data for any site that uses CSS text-overflow. Users unaware of the title attribute workaround would extract truncated data without realizing it.

**Most Confusing Aspects:** 1. The inspect tool's auto-discovery algorithm is invisible — it picks selectors without explaining why. 2. htmlsnapshot grep requiring a pattern with --selector is counterintuitive — as a validation tool, it should support counting without a content pattern. 3. The relationship between snapshot capture and X-SQL query (independent systems, re-fetch) is not obvious.

**Most Valuable Improvements:** 1. Truncation detection and attribute-based selector suggestions in inspect. 2. Selector-count mode in grep (no pattern required). 3. A unified data-quality report after extraction that flags potential truncation, missing attributes, or low-specificity selectors.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Text truncation in htmlsnapshot get text — visible text clipped at CSS overflow

htmlsnapshot get all text ".product_pod h3 a" on books.toscrape.com (or any page where text is CSS-truncated with ellipsis).

#### Issue 2: htmlsnapshot grep --selector requires a pattern argument, breaking selector-validation workflow

htmlsnapshot grep --selector ".product_pod"

#### Issue 3: inspect doesn't surface attribute-based selectors when text is truncated

Run `htmlsnapshot inspect` on books.toscrape.com. The suggested selectors include `h3:expr(a>0)` but not `h3 a[title]` or any attribute-based selector that would yield untruncated text.

#### Issue 4: X-SQL DOM_LOAD_AND_SELECT re-fetches page despite existing snapshot — inefficient and potentially confusing

Capture snapshot with `htmlsnapshot`, then run `htmlsnapshot query --sql @query.sql` where the SQL uses DOM_LOAD_AND_SELECT(@url, ...).

#### Issue 5: Inspect auto-discovery behavior is powerful but opaque to new users

Run `htmlsnapshot inspect` without arguments. It auto-discovers `.product_pod` from `:root`, then `htmlsnapshot inspect .sidebar` auto-discovers `.sidebar li`.

#### Issue 6: Top-level `help` output doesn't mention htmlsnapshot subcommands explicitly

Run `./b4w.ps1 help`. The htmlsnapshot section lists `capture`, `get`, `query`, `export`, `summary`, `grep`, `inspect` — but these are listed under the generic 'HTML Snapshot (htmlsnapshot)' header. A new user scanning for `inspect` or `summary` might miss it because they're indented under the parent command.

