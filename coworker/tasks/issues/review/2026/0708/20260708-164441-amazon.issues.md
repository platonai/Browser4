# Issues: amazon

> **Source:** `20260708-164441-amazon.full.md` | **Date:** 20260708-164441 | **Mode:** dev

## Scenario Background

### Task

✅ **Task completed.** The comparison of the top 4 results for "pens to draw on whiteboards" on Amazon has been written to `amazon-whiteboard-pens-comparison.md` in the repository root. The file contains structured product data (title, price, rating, URL), individual product analysis, a summary comparison table, key takeaways, and a recommendation.

---

### Execution Context

**Key Commands:**

1. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` — learned available commands
2. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- goto "https://www.amazon.com/"` — navigated to Amazon
3. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot -v 0` — captured accessibility tree
4. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- snapshot grep -i "search"` — found search box ref `e204439`
5. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- fill e204439 "pens to draw on whiteboards"` — entered search query
6. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- press Enter` — submitted search
7. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot` — captured static HTML snapshot
8. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect` — attempted selectors discovery (too broad)
9. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text "a.a-link-normal.s-line-clamp-4" --limit 10` — extracted product titles
10. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text ".a-price .a-offscreen" --limit 10` — extracted prices
11. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot get all text ".a-icon-alt" --limit 10` — extracted ratings
12. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect "div[data-component-type]"` — targeted selector discovery
13. Two X-SQL queries to extract correlated data (title, price, rating, URL)

**Major decisions:**
- Used `snapshot grep` to find the search box rather than scrolling through the full snapshot
- Switched from individual `get all` calls to X-SQL for correlated extraction (as recommended by the SKILL.md warning about unaligned arrays)
- Used `[data-component-type="s-search-result"]` as the product card container selector
- Noted that result #1 is sponsored (identified via URL inspection)

**Workarounds:**
- Had to try multiple title selectors (`h2 a span` → empty → `a.a-link-normal.s-line-clamp-4` → success)
- Review count extraction failed after 2 attempts with different selectors — omitted from final comparison
- `htmlsnapshot inspect` without arguments analyzed `<div>` broadly — had to re-run with a targeted selector

---

---

## Issues Found (8 issues)

### Issue 1: Verbose development invocation command

**Severity:** Medium
**Category:** UX

#### Reproduction

Run any command during development: `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>`. This is 75+ characters before the actual command begins.

#### Expected Behavior

A short, memorable alias or documented shortcut for development (e.g., an alias script, a `just` command, or a make target).

#### Actual Behavior

Every invocation requires the full `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` prefix. The development docs mention this pattern but offer no shortcut.

#### Root Cause Analysis

No development convenience wrapper (shell alias, Makefile, or `just` recipe) is documented or provided. The CLI is designed for global npm/cargo installation, not frequent `cargo run` usage during development, but the development docs acknowledge both workflows without bridging them ergonomically.

#### Code Pointer

``cli/browser4-cli/README.md` and `skills/browser4-cli/references/development.md``

#### AI Suggested Improvement

- Add a `justfile` or `Makefile` with a `b4` target: `b4 = "cargo run --manifest-path cli/browser4-cli/Cargo.toml --"` so devs can run `just b4 goto "https://example.com"`
- Document a shell alias in the development guide: `alias b4='cargo run --manifest-path cli/browser4-cli/Cargo.toml --'`
- Or add a thin wrapper script at `bin/b4` or `bin/dev-cli.sh` that does the same

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] Development-mode friction (cargo run verbosity). Per review guidelines: issues about development-mode friction (cargo run overhead, cd into subdirs) are WONTFIX. Users can create their own shell aliases — this is not a tool defect.

---

### Issue 2: Silent session reuse on `goto` obscures page state

**Severity:** Medium
**Category:** UX / Reliability

#### Reproduction

Run `goto "https://www.amazon.com/"` when a previous session with a different Amazon page is still active:
```
Reconnected to existing session on https://www.amazon.com/dp/B0DMW1T59F?th=1
### Page
- Page URL: https://www.amazon.com/
```

#### Expected Behavior

Either (a) a clear warning that the previous session had a different page loaded and was being navigated away, or (b) `goto` should always navigate to the requested URL regardless of session state.

#### Actual Behavior

"Reconnected to existing session" appeared briefly, then navigation proceeded. The message is easy to miss and doesn't clearly signal what's happening. A new user might not understand that a stale session from prior work is being reused.

#### Root Cause Analysis

The session management auto-reconnects to existing browser windows rather than opening fresh ones. This is a feature for session persistence, but the communication to the user is minimal — a one-line notice that scrolls past quickly. The "next open" behavior is documented in `list` command but not surfaced at the point of action.

#### Code Pointer

``cli/browser4-cli/src/main.rs` (session reconnection logic in `goto` handler)`

#### AI Suggested Improvement

- When `goto` reconnects to a session on a different URL, print a more prominent notice: "⚠️ Reconnected to existing session (was on <previous-url>). Navigating to <new-url>..."
- Add a `--new` or `--fresh` flag to `goto` that explicitly opens a new session instead of reconnecting
- Show the "next open" column behavior in the `goto` output so users understand session state

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

### Issue 3: `snapshot -v 0` preview truncation hides interactive elements

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Run `snapshot -v 0` on a complex page like Amazon search results. The preview shows only 10 lines (the page header/navigation), while the search box ref is at line 33+.

#### Expected Behavior

The preview should prioritize or surface interactive elements (inputs, buttons, links) near the top, or the tip should explicitly guide users to `snapshot grep` for finding elements.

#### Actual Behavior

Preview shows generic `[ref=e204135]` and navigation structure. The search box is hidden in the non-previewed part. The tip says "Run `snapshot -v 0` to see interactive element refs" but doesn't mention `snapshot grep` for targeted search.

#### Root Cause Analysis

The snapshot preview renders the top of the accessibility tree linearly. On pages with deep DOM nesting (Amazon has 200+ lines of header before the search box), interactive elements are buried. The preview doesn't prioritize or filter for interactivity. The `-i` (interactive) mode is mentioned in the SKILL.md but strips generic containers — making it unsuitable for e-commerce pages, as documented.

#### Code Pointer

``cli/browser4-cli/src/snapshot.rs` (snapshot preview rendering)`

#### AI Suggested Improvement

- After the snapshot preview, add a dynamic tip listing the first 3-5 interactive elements found (e.g., "Found: searchbox e204439, button e25, link e191...")
- Update the tip text from "Run `snapshot -v 0` to see interactive element refs" to include: "Use `snapshot grep <text>` to find specific elements by name"
- Add a `--interactives-only` or `--summary` flag that outputs just the interactive elements and their refs

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid discoverability issue for AI agents trying to find interactive elements. Focus the fix on updating the tip text to mention `snapshot grep` for targeted element search, rather than adding new flags like `--interactives-only` or `--summary` which add complexity for marginal gain. The dynamic interactive-element listing in the preview is a reasonable middle ground.

---

### Issue 4: `htmlsnapshot inspect` without arguments picks wrong element type

**Severity:** Medium
**Category:** Discoverability / Reliability

#### Reproduction

Run `htmlsnapshot inspect` on an Amazon search results page. It analyzes `<div>` (4344 matches) and finds shortcut menu patterns instead of product cards. The user must know to re-run with a more specific selector like `div[data-component-type]`.

#### Expected Behavior

The auto-discovery should detect the most likely "repeating pattern" container on the page (e.g., product cards, list items, search results) and analyze that first, or provide clear guidance that a more specific selector is needed.

#### Actual Behavior

The tool defaults to `<div>`, the most generic element, and finds patterns in the page header/shortcut menu area. The product cards (97 matches on `div[data-component-type]`) go undiscovered until the user manually specifies a better selector.

#### Root Cause Analysis

The heuristic picks the most common element type (`<div>`) rather than the most structurally repetitive one at the right granularity. The "visual geometry detection" hint to try another selector appears below the results, but only after the unhelpful analysis output — a user skimming the output might miss it.

#### Code Pointer

``cli/browser4-cli/src/` (htmlsnapshot inspect logic — likely in snapshot.rs or a dedicated module)`

#### AI Suggested Improvement

- When the analyzed element has >1000 matches and the suggested selectors have low coverage (<20%), automatically suggest: "Analysis found low-specificity patterns. Try a more targeted container selector..."
- Add a heuristic to prefer elements with `data-component-type`, `role="listitem"`, or common card class patterns (`s-result-item`, `.product-card`, `[data-cy]`) as the default analysis target
- Include a "quick start" hint after inspect: "For e-commerce search results, try: `htmlsnapshot inspect '[data-component-type=\"s-search-result\"]'`"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Same root cause as historical example #3 (DEFER): the inspect auto-discovery heuristic picks the first repeating element from the most common type, which on e-commerce pages is navigation/header divs, not product cards. Fixing this requires refactoring the container-priority heuristic to prefer elements with data attributes, list roles, or card-class patterns. Postpone until inspect gets dedicated attention.

---

### Issue 5: `get all` arrays produce unaligned multi-field data

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
htmlsnapshot get all text "a.a-link-normal.s-line-clamp-4" --limit 10  # returns 10 titles
htmlsnapshot get all text ".a-price .a-offscreen" --limit 10           # returns 10 values, but includes per-unit prices interleaved
htmlsnapshot get all text ".a-icon-alt" --limit 10                     # returns 10 ratings
```
The arrays cannot be reliably zipped: prices include `$9.99, $0.77, $6.99, $0.58...` where the per-unit prices break alignment.

#### Expected Behavior

A single command that returns correlated multi-field data, or clear documentation that `get all` is single-field only and `query` (X-SQL) must be used for multi-field extraction.

#### Actual Behavior

The SKILL.md warns about this ("multiple `get all` calls produce unaligned arrays"), but the `htmlsnapshot` command output after `inspect` suggests `get all text` as the next step, which a new user would naturally try for multi-field data. The tip reads: "Use `get all text` to extract visible text, or `get all attr <name>` for attribute values."

#### Root Cause Analysis

Each `get all` call runs an independent CSS query. Results from different selectors may have different cardinalities and orderings due to DOM structure differences. The tip at the bottom of `inspect` output suggests `get all` as the next step without warning about the alignment issue.

#### Code Pointer

``cli/browser4-cli/src/` (htmlsnapshot get command handler)`

#### AI Suggested Improvement

- Add a warning to the `inspect` and `get all` tips: "For multi-field extraction (e.g., title + price + rating per product), use `htmlsnapshot query` with X-SQL to get correlated rows."
- Consider adding a `get table` command that accepts multiple selectors and returns aligned results: `htmlsnapshot get table --title ".title" --price ".price" --rating ".rating"`
- The inspect output tip should mention X-SQL before `get all` for extraction tasks

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

### Issue 6: Cargo build status lines pollute command output

**Severity:** Low
**Category:** UX

#### Reproduction

Run any command without `--quiet`:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.48s
     Running `cli/browser4-cli/target/debug/browser4-cli snapshot -v 0`
### Page
- Page URL: ...
```

#### Expected Behavior

Clean command output on stdout, with build status on stderr or suppressed entirely in dev mode.

#### Actual Behavior

Two cargo status lines precede every command's output on stderr. The `--quiet` flag suppresses them, but it must be passed explicitly. Without `--quiet`, piping output to files captures these lines (the development docs warn about this).

#### Root Cause Analysis

`cargo run` always prints build status to stderr. The `--quiet` flag passes through to cargo, but it's not the default. The development docs mention using `--quiet` for clean output but don't set it as a default behavior.

#### Code Pointer

``cli/browser4-cli/src/main.rs` (no automatic `--quiet` passthrough in dev mode)`

#### AI Suggested Improvement

- Detect when running via `cargo run` (check `CARGO` env var) and automatically apply `--quiet` to cargo output
- Or add a `--no-build-output` flag that's automatically enabled in dev mode
- Update development docs to recommend `alias b4='cargo run --quiet --manifest-path cli/browser4-cli/Cargo.toml --'`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] Development-mode friction (cargo build output on stderr). Per review guidelines: issues about development-mode friction are WONTFIX. The `--quiet` flag already exists and is documented. Shell alias configuration is a user preference, not a tool defect.

---

### Issue 7: No built-in sponsored/organic result distinction

**Severity:** Low
**Category:** Product

#### Reproduction

Extract search results from Amazon. Sponsored products have different DOM structures (e.g., `data-component-type` values, "Sponsored" labels) but the tool provides no way to filter or flag them.

#### Expected Behavior

A way to identify or filter sponsored vs. organic results, either via a CSS selector filter, an X-SQL function, or metadata in the extraction output.

#### Actual Behavior

The user must manually inspect URLs (looking for "sspa" or "sp_csd" query parameters) or read the snapshot text for "Sponsored" labels to distinguish sponsored from organic results.

#### Root Cause Analysis

The tool is site-agnostic and doesn't have Amazon-specific or e-commerce-specific sponsored-content detection. This is a reasonable design choice, but it means common extraction tasks require manual work.

#### Code Pointer

`N/A (product feature request)`

#### AI Suggested Improvement

- Add a `--skip-sponsored` flag to `htmlsnapshot query` that filters out elements containing text like "Sponsored" or elements with common sponsored-data attributes
- Document a pattern in the e-commerce scenario reference for identifying sponsored results on Amazon
- Consider adding a `DOM_HAS_TEXT` or `DOM_CONTAINS` function to X-SQL for conditional filtering

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [x] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: WONTFIX] The tool is intentionally site-agnostic — it cannot have Amazon-specific sponsored-content detection without becoming a platform-specific scraper. Amazon's sponsored markup varies across locales and changes over time. Per guidelines: third-party/external website behavior the tool can't control → WONTFIX. Document the manual URL-inspection pattern for identifying sponsored results instead.

---

### Issue 8: X-SQL learning curve — no interactive query builder

**Severity:** Medium
**Category:** Discoverability / UX

#### Reproduction

A new user needs to write an X-SQL query for multi-field extraction. They must: (1) understand the X-SQL function reference, (2) discover the right CSS selectors via trial and error, (3) write the query to a file, (4) pass it via `--sql @file.sql`. There's no interactive guidance during this process.

#### Expected Behavior

A guided or interactive way to build X-SQL queries. At minimum, the `inspect` output should include a ready-to-use X-SQL template populated with discovered selectors.

#### Actual Behavior

The `inspect` command suggests `get all` commands and a generic X-SQL example (`...FROM load_and_select(@url, 'a')`). No X-SQL template is generated from the discovered selectors. The user must bridge the gap from "discovered selectors" to "working X-SQL query" manually.

#### Root Cause Analysis

`inspect` focuses on CSS selector discovery, not end-to-end extraction workflow. The X-SQL suggestion at the bottom is a fixed example, not generated from the analysis context.

#### Code Pointer

``cli/browser4-cli/src/` (htmlsnapshot inspect output generation)`

#### AI Suggested Improvement

- After successful `inspect` analysis, generate and print a suggested X-SQL template using the discovered selectors: `SELECT DOM_FIRST_TEXT(dom, '.title') AS title, DOM_FIRST_TEXT(dom, '.price') AS price FROM DOM_LOAD_AND_SELECT(@url, '<container>', 1, 10)`
- Add a `--generate-sql` flag to `inspect` that outputs a ready-to-use query file
- Add more e-commerce specific examples to the SKILL.md reference files

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid gap in the extraction workflow: inspect discovers selectors but leaves the user to manually bridge to X-SQL. The most impactful fix is generating an X-SQL template from discovered selectors in the inspect output (as suggested). Defer the interactive query builder and `--generate-sql` flag — those are larger features that need separate design. Focus on the inspect→X-SQL bridge as a documentation/template generation improvement.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Verbose development invocation command

Run any command during development: `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- <command>`. This is 75+ characters before the actual command begins.

#### Issue 2: Silent session reuse on `goto` obscures page state

Run `goto "https://www.amazon.com/"` when a previous session with a different Amazon page is still active:
```
Reconnected to existing session on https://www.amazon.com/dp/B0DMW1T59F?th=1
### Page
- Page URL: https://www.amazon.com/
```

#### Issue 3: `snapshot -v 0` preview truncation hides interactive elements

Run `snapshot -v 0` on a complex page like Amazon search results. The preview shows only 10 lines (the page header/navigation), while the search box ref is at line 33+.

#### Issue 4: `htmlsnapshot inspect` without arguments picks wrong element type

Run `htmlsnapshot inspect` on an Amazon search results page. It analyzes `<div>` (4344 matches) and finds shortcut menu patterns instead of product cards. The user must know to re-run with a more specific selector like `div[data-component-type]`.

#### Issue 5: `get all` arrays produce unaligned multi-field data

```bash
htmlsnapshot get all text "a.a-link-normal.s-line-clamp-4" --limit 10  # returns 10 titles
htmlsnapshot get all text ".a-price .a-offscreen" --limit 10           # returns 10 values, but includes per-unit prices interleaved
htmlsnapshot get all text ".a-icon-alt" --limit 10                     # returns 10 ratings
```
The arrays cannot be reliably zipped: prices include `$9.99, $0.77, $6.99, $0.58...` where the per-unit prices break alignment.

#### Issue 6: Cargo build status lines pollute command output

Run any command without `--quiet`:
```
    Finished `dev` profile [unoptimized + debuginfo] target(s) in 0.48s
     Running `cli/browser4-cli/target/debug/browser4-cli snapshot -v 0`
### Page
- Page URL: ...
```

#### Issue 7: No built-in sponsored/organic result distinction

Extract search results from Amazon. Sponsored products have different DOM structures (e.g., `data-component-type` values, "Sponsored" labels) but the tool provides no way to filter or flag them.

#### Issue 8: X-SQL learning curve — no interactive query builder

A new user needs to write an X-SQL query for multi-field extraction. They must: (1) understand the X-SQL function reference, (2) discover the right CSS selectors via trial and error, (3) write the query to a file, (4) pass it via `--sql @file.sql`. There's no interactive guidance during this process.

