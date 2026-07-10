# Issues: x-sql-extraction-functions

> **Source:** `20260709-231931-x-sql-extraction-functions.full.md` | **Date:** 20260709-231931 | **Mode:** dev

## Scenario Background

### Task

The task was completed successfully. I:

1. Navigated to `http://localhost:18080/ec/b?node=1292115012` (6 Electronics products)
2. Captured an HTML snapshot
3. Ran `htmlsnapshot inspect` with `--max 3 --depth 3` to discover CSS selectors (using `[id*="product-B0E"]` as a workaround)
4. Wrote a comprehensive X-SQL query extracting 11 fields per product using all requested DOM, STR, and ARRAY functions
5. Applied PowerCSS `:expr()` visual filtering, WHERE/ORDER BY/LIMIT clauses
6. Ran the query successfully, retrieving 5 products sorted by price ascending ($24.99–$599.00)

The final query is at `comprehensive-query.sql` and the extracted data includes:
- `title` (STR_TRIM cleaned), `price` (float), `product_link` (abs URL), `image_url` (abs URL)
- `category_id` and `rating_data_attr` via `DOM_FIRST_ATTR`
- `title_upper` via `STR_UPPER_CASE`, `badge` via `STR_DEFAULT_IF_BLANK`
- `rating_numeric` via `STR_FIRST_FLOAT`, `title_short` via `STR_ABBREVIATE`
- `fallback_title` via `ARRAY_FIRST_NOT_BLANK` with multiple selector fallbacks
- `card_width` via `DOM_WIDTH` with `:expr(width > 150 && height > 100)` filtering

### Execution Context

**Key Commands:**

1. `cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help` — learned command structure
2. `curl` verification that MockSite was running on port 18080
3. `cargo run ... goto "http://localhost:18080/ec/b?node=1292115012"` — navigation (auto-reconnected session)
4. `cargo run ... htmlsnapshot` — captured static HTML snapshot with metadata
5. `cargo run ... htmlsnapshot inspect --max 3 --depth 3` — auto-discovery attempted, returned 0 matches
6. `cargo run ... htmlsnapshot export --file /tmp/mocksite-electronics.html` — exported HTML to diagnose structure
7. Inspected exported HTML, discovered class names contain literal `"` characters
8. `cargo run ... htmlsnapshot inspect '[id*="product-B0E"]' --max 3 --depth 3` — successful inspect with attribute selector
9. Wrote `test-basic.sql` — validated basic DOM functions work
10. Wrote `comprehensive-query.sql` — ran full query with all 15+ required features

**Key decisions:**
- Used attribute selectors (`[class*="..."]`) instead of class selectors due to MockSite quote-embedded class names
- Used `--sql @file.sql` pattern to avoid shell escaping issues
- Used `DOM_FIRST_ATTR(DOM, ':root', ...)` for data attributes on the product card itself
- Used `div[class*="product-card"]:expr(width > 150 && height > 100)` for PowerCSS filtering + robust class matching

**Workarounds required:**
- Had to export and read raw HTML to understand why auto-discovery failed
- Had to switch from `.class-name` to `[class*="text"]` attribute selectors throughout

---

## Issues Found (7 issues)
> **Review complete:** 0 approved, 7 deferred/rejected

### Issue 1: htmlsnapshot inspect auto-discovery fails on MockSite due to quoted class names

**Severity:** Medium
**Category:** Reliability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Normalize class name values that contain HTML entities during DOM parsing so the CSS engine can match them with standard `.class-name` selectors

---

### Issue 2: No guidance when inspect returns 0 matches

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a "Did you know?" hint suggesting the user run `htmlsnapshot grep` to search for partial class names in the raw HTML

---

### Issue 3: `$cliInvocation` / dev-mode invocation pattern not discoverable from help

**Severity:** Medium
**Category:** Discoverability

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add a "Running from source" section to the main `README.md` near the top, similar to what's in `cli/browser4-cli/README.md`

---

### Issue 4: `htmlsnapshot` capture footer tip suggests wrong workflow

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Show HTML-snapshot-relevant tips after `htmlsnapshot` capture, such as: "Try `htmlsnapshot get text 'h1'` to extract the page heading" or "Try `htmlsnapshot inspect` to discover CSS selectors"

---

### Issue 5: `DOM_ATTR` listed in function index but not documented in detail reference

**Severity:** Low
**Category:** Documentation

#### Review Result

**Decision:** WONTFIX

**Summary:** - Add `DOM_ATTR` documentation to `x-sql-dom-functions.md` with signature and example

---

### Issue 6: `cargo run` build-status output adds noise to every command

**Severity:** Low
**Category:** UX

#### Review Result

**Decision:** WONTFIX

**Summary:** - Document the `--quiet` pattern more prominently in the Quick Start / Development sections

---

### Issue 7: MockSite URLs embed quote characters in href/src values

**Severity:** Low
**Category:** Product (test infrastructure)

#### Review Result

**Decision:** WONTFIX

**Summary:** - Fix the MockSite HTML generation to not wrap class names, IDs, hrefs, and srcs in quote entities

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: htmlsnapshot inspect auto-discovery fails on MockSite due to quoted class names

```bash
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- htmlsnapshot inspect --max 3 --depth 3
```
Output: `### Inspect: ".\"product-card\"" (0 matches) ... No elements matched.`

#### Issue 2: No guidance when inspect returns 0 matches

```bash
cargo run ... htmlsnapshot inspect --max 3 --depth 3
```

#### Issue 3: `$cliInvocation` / dev-mode invocation pattern not discoverable from help

A new developer reading only `--help` output, `README.md`, or `SKILL.md` sees all examples using `browser4-cli` as the command. The dev-mode `cargo run --manifest-path cli/browser4-cli/Cargo.toml --` pattern is buried in `cli/browser4-cli/README.md` and `skills/browser4-cli/references/development.md`. The main `README.md` doesn't mention running from source at all.

#### Issue 4: `htmlsnapshot` capture footer tip suggests wrong workflow

```bash
cargo run ... htmlsnapshot
```
Output footer includes: `💡 Tip: Run snapshot -v 0 to see interactive element refs`

#### Issue 5: `DOM_ATTR` listed in function index but not documented in detail reference

Looking at `skills/browser4-cli/references/x-sql.md`, the function index lists `DOM_ATTR | String | Element property`. However, `x-sql-dom-select-functions.md` (the detailed reference for selector-based functions) only documents `DOM_FIRST_ATTR`/`DOM_NTH_ATTR`/`DOM_ALL_ATTRS`. The simpler `DOM_ATTR(DOM, name)` for the element itself is not documented.

#### Issue 6: `cargo run` build-status output adds noise to every command

Every `cargo run` invocation prints `Finished dev profile [unoptimized + debuginfo] target(s) in 0.XXs` and `Running ...` lines even when no code has changed.

#### Issue 7: MockSite URLs embed quote characters in href/src values

The href values in MockSite product links are `"/ec/dp/B0E000001"` (with literal quote characters). When `DOM_FIRST_HREF` resolves them to absolute URLs, the result is `http://localhost:18080/ec/"/ec/dp/B0E000001"` which is malformed.
