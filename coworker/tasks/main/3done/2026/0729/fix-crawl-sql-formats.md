# Issues: crawl-sql-formats

> **Source:** `20260729-072355-crawl-sql-formats.full.md` | **Date:** 20260729-072355 | **Mode:** dev

## Scenario Background

### Task

Both acceptance criteria passed successfully:

- **AC5 (SQL from file with CSV output):** The command `crawl --seed-file .test-sessions/seed-urls.txt --sql @.test-sessions/extract.sql --format csv -o .test-sessions/results.csv` produced a CSV file with 2 data rows containing the correct product titles and prices (Widget Alpha: $10.00, Widget Beta: $20.00).

- **AC6 (SQL from stdin with table format):** The command `crawl --seed-file .test-sessions/seed-urls.txt --sql-stdin --format table < .test-sessions/extract.sql` displayed a table-formatted result on stdout with the same extracted data, matching the CSV output from AC5.

- **Comparison (Step 4):** Re-running with `--format json` confirmed identical extraction results across all three formats (CSV, table, JSON), proving the extraction is consistent regardless of how the SQL was provided (`--sql @file` vs `--sql-stdin`).

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — learned available commands
2. Read `skills/browser4-cli/SKILL.md`, `references/crawl.md`, `references/x-sql.md` — studied documentation
3. Verified MockSite was running on localhost:18080
4. Inspected product pages 1.html and 2.html to confirm selectors (`#productTitle`, `#product-price`)
5. Created seed file and SQL query in `.test-sessions/`
6. `./b4w.ps1 crawl --seed-file .test-sessions/seed-urls.txt --sql @.test-sessions/extract.sql --format csv -o .test-sessions/results.csv` — AC5
7. Verified `results.csv` contents
8. `./b4w.ps1 crawl --seed-file .test-sessions/seed-urls.txt --sql-stdin --format table < .test-sessions/extract.sql` — AC6
9. `./b4w.ps1 crawl --seed-file .test-sessions/seed-urls.txt --sql @.test-sessions/extract.sql --format json` — Step 4 comparison

**Important decisions:**
- Used `load_and_select` (lowercase) from crawl.md examples rather than `DOM_LOAD_AND_SELECT` (uppercase) from x-sql.md — both work but naming is inconsistent across docs
- Omitted `--depth 0` since the crawl correctly processed only seed URLs without it (no `--out-link-selector` was provided, so no link discovery occurred)

**Workarounds required:** None. All commands worked on first attempt.

---

## Issues Found (3 issues)

### Issue 1: X-SQL output column order does not preserve SQL SELECT order

**Severity:** Medium
**Category:** Product

#### Reproduction

Write a SQL query with SELECT dom_first_text(dom, '#productTitle') AS title, dom_first_text(dom, '#product-price') AS price FROM load_and_select(@url, 'body'). Run crawl with --sql @file. The output shows price before title in all formats (CSV, table, JSON), reversing the SELECT clause order.

#### Expected Behavior

Column order in output should match the order specified in the SQL SELECT clause: title first, then price.

#### Actual Behavior

Columns appear in alphabetical order: price first, then title. This occurs in CSV headers (price,title), table columns (price | title), and JSON key order (price before title).

#### Verified Root Cause

**AI diagnosis was close but identified the wrong layer.** The server-side `ResultSetUtils.getTextEntityFromCurrentRecord()` (in `browser4base/pulsar-core/pulsar-ql/.../ResultSetUtils.kt:242-257`) correctly uses `mutableMapOf()` (Kotlin LinkedHashMap), which preserves insertion/column order. The `XSQLScrapeHyperlink` class also explicitly normalizes columns with `linkedSetOf`/`linkedMapOf`.

The actual culprit is **`serde_json` on the Rust CLI side**. When the CLI deserializes the JSON response into `serde_json::Value`, it uses `serde_json::Map` which is backed by a **`BTreeMap`** — this sorts keys alphabetically, destroying the SELECT column order. The `format_csv()` and `format_table()` functions iterate "in order of first appearance" across rows, but every row already has alphabetically-sorted keys, so "first appearance" == alphabetical.

This is **explicitly documented** in the existing test suite at `cli/browser4-cli/src/main.rs:20336-20337`:
```
// serde_json uses BTreeMap ordering → alphabetical keys: "title", "url"
```

#### Corrected Code Pointer

- **Primary:** `cli/browser4-cli/src/main.rs` — `format_csv()` (~line 10494) and `format_table()` (~line 10556) — these consume serde_json Values with alphabetically-sorted keys
- **Root:** `serde_json` deserialization behavior — JSON object → BTreeMap
- **Server-side (NOT the cause):** `browser4base/pulsar-core/pulsar-ql/.../ResultSetUtils.kt:242` — correctly uses LinkedHashMap
- **Test documenting behavior:** `cli/browser4-cli/src/main.rs:20336-20362` — `format_csv_columns_ordered_by_first_appearance` test implicitly asserts alphabetical ordering

#### AI Suggested Improvement (annotated)

- ~~Use a LinkedHashMap or similar order-preserving data structure when aggregating X-SQL results across pages~~ — Server side already does this; not the fix
- **Recommended fix:** Enable `serde_json`'s `preserve_order` feature flag in `Cargo.toml`, which switches the internal map from `BTreeMap` to `IndexMap` (insertion-ordered). This is a one-line change that fixes column ordering everywhere serde_json is used.
- **Alternative:** Add a `columns` array to the crawl response that carries the SQL SELECT column order as metadata, and have format_csv/format_table use it explicitly rather than relying on JSON key iteration order.
- Add a test that verifies SELECT column order is preserved in all output formats (CSV, table, JSON)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Well-diagnosed root cause — `serde_json` uses `BTreeMap` by default, alphabetically sorting keys. The fix (enabling `preserve_order` feature flag in Cargo.toml) is a one-line change that corrects column ordering across all output formats. Medium severity because it directly contradicts user intent expressed in SQL SELECT order.

---

### Issue 2: Function name casing inconsistency between crawl.md and x-sql.md documentation

**Severity:** Low
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/references/crawl.md which uses lowercase `load_and_select(@url, 'body')` and `dom_first_text(dom, ...)`. Then read skills/browser4-cli/references/x-sql.md which uses uppercase `DOM_LOAD_AND_SELECT(...)` and `DOM_FIRST_TEXT(DOM, ...)`.

#### Expected Behavior

Consistent casing across all documentation files. Either all lowercase or all uppercase, with a note that H2 SQL is case-insensitive for function names.

#### Actual Behavior

crawl.md examples use all-lowercase function names. x-sql.md uses all-uppercase function names. A first-time user would wonder which is correct or whether they are different functions.

#### Verified Scope (wider than reported)

**UPPERCASE convention used in:**
- `x-sql.md`, `x-sql-dom-load-select.md`, `x-sql-dom-functions.md`, `x-sql-dom-select-functions.md`, `x-sql-string-functions.md`, `x-sql-array-functions.md`

**lowercase convention used in:**
- `crawl.md`, `css-selector-bridge.md`, `htmlsnapshot-scenarios-amazon.md`, `htmlsnapshot-scenarios-advanced.md`, `htmlsnapshot-scenarios-extraction.md`, `htmlsnapshot-scenarios-audit.md`, `swarm.md`, `loop.md`

**Mixed in:**
- `htmlsnapshot.md` — uses `DOM_LOAD_AND_SELECT` in warning note but `load_and_select` in SQL examples

The CLI `help.rs:371` already notes: `"Function names are case-insensitive (DOM_FIRST_TEXT = dom_first_text)."` — so the fix is purely to standardize documentation style.

#### Root Cause Analysis

The two reference files were likely authored at different times or by different contributors without a shared style convention. H2 SQL is case-insensitive so both work, but the inconsistency creates confusion.

#### AI Suggested Improvement

- Standardize on one casing convention across all documentation (prefer UPPERCASE as it's the SQL convention and matches the x-sql.md function index)
- Add a brief note in crawl.md acknowledging both forms work: "Function names are case-insensitive — DOM_FIRST_TEXT and dom_first_text are equivalent"
- Run a documentation lint check to catch casing inconsistencies in future updates

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid documentation inconsistency — x-sql.md family uses UPPERCASE, crawl.md and other references use lowercase. Both work since H2 SQL is case-insensitive, but the inconsistency creates confusion for first-time users. Standardizing to one convention (prefer UPPERCASE as the SQL convention) and adding a brief case-insensitivity note is the right fix.

---

### Issue 3: Crawl help says --out-link-selector is 'required for depth >= 1' but crawl works without it

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run `crawl --help` and note the line: "--out-link-selector (-ol) specifies a CSS selector to extract links (required for depth >= 1)." Then run `crawl --seed-file urls.txt --sql @query.sql --format csv -o out.csv` without --depth or --out-link-selector. Default depth is 1, but the crawl completes successfully processing only seed URLs.

#### Expected Behavior

Either the crawl should error out when depth >= 1 and no out-link-selector is provided (matching the docs), or the docs should accurately describe the fallback behavior.

#### Actual Behavior

The crawl silently degrades to seed-only processing when no out-link-selector is provided, even at default depth=1. The user gets no warning that link discovery was skipped.

#### Verified Source Locations

1. **`cli/browser4-cli/src/help.rs:1068-1070`** — Notes prose in crawl section:
   ```
   "  - --out-link-selector (-ol) specifies a CSS selector to extract links (required for depth >= 1)."
   ```

2. **`skills/browser4-cli/references/crawl.md:26-28`** — Documentation note:
   ```
   > **Note:** `--out-link-selector` is required for link discovery (depth >= 1).
   ```

3. **`cli/browser4-cli/src/commands.rs:2642`** — The option definition itself does NOT include the requirement:
   ```rust
   OptionDef { name: "out-link-selector", description: "CSS selector to extract links from each page", ... }
   ```

#### Root Cause Analysis

The crawl implementation treats absence of --out-link-selector as "no link discovery" regardless of depth setting. This is a reasonable UX choice (don't force errors for bulk-fetch workflows) but contradicts the documented requirement.

#### AI Suggested Improvement

- Update the help text to say: "--out-link-selector (-ol) specifies a CSS selector to extract links. Required for link discovery; without it, only seed URLs are processed regardless of depth."
- Consider emitting an informational note when depth >= 1 but no out-link-selector is provided: "Link discovery disabled (no --out-link-selector). Processing seed URLs only."

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The help text and docs say `--out-link-selector` is "required for depth >= 1" but the crawl silently degrades to seed-only processing without it. This is a UX documentation bug — the behavior (graceful degradation) is reasonable, but the documented requirement is inaccurate. The suggested rewording accurately describes fallback behavior. Consider also emitting an informational note when link discovery is skipped.

---

## Overall Assessment

**Completion Status:** Successful — Both AC5 and AC6 passed on first attempt. The crawl command correctly extracted product titles and prices from two seed URLs using both --sql @file (CSV output to file) and --sql-stdin (table output to stdout). Cross-format comparison with JSON confirmed identical extraction results across all three output formats.

**Success Rate:** 100% — all task steps and acceptance criteria succeeded without errors or workarounds

**Issues Found:** 3

**Most Confusing Aspects:** 1. The column order in output (price,title) doesn't match the SQL SELECT order (title,price) — a first-time user might doubt whether the extraction worked correctly. 2. The function name casing difference between crawl.md (lowercase) and x-sql.md (uppercase) makes it unclear which convention to follow, though both work. 3. The --out-link-selector 'required' warning in --help contradicts actual behavior — a cautious user might add an unnecessary flag or avoid using the command.

**Most Valuable Improvements:** 1. Preserve SQL SELECT column order in output — this is the most impactful fix as it affects result readability and trust in all X-SQL extraction workflows. 2. Standardize function name casing across documentation to reduce first-time-user confusion. 3. Add an informational note when depth >= 1 but no out-link-selector is provided so users understand link discovery was intentionally skipped.

**Usability Rating:** 8/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: X-SQL output column order does not preserve SQL SELECT order

Write a SQL query with SELECT dom_first_text(dom, '#productTitle') AS title, dom_first_text(dom, '#product-price') AS price FROM load_and_select(@url, 'body'). Run crawl with --sql @file. The output shows price before title in all formats (CSV, table, JSON), reversing the SELECT clause order.

#### Issue 2: Function name casing inconsistency between crawl.md and x-sql.md documentation

Read skills/browser4-cli/references/crawl.md which uses lowercase `load_and_select(@url, 'body')` and `dom_first_text(dom, ...)`. Then read skills/browser4-cli/references/x-sql.md which uses uppercase `DOM_LOAD_AND_SELECT(...)` and `DOM_FIRST_TEXT(DOM, ...)`.

#### Issue 3: Crawl help says --out-link-selector is 'required for depth >= 1' but crawl works without it

Run `crawl --help` and note the line: "--out-link-selector (-ol) specifies a CSS selector to extract links (required for depth >= 1)." Then run `crawl --seed-file urls.txt --sql @query.sql --format csv -o out.csv` without --depth or --out-link-selector. Default depth is 1, but the crawl completes successfully processing only seed URLs.
