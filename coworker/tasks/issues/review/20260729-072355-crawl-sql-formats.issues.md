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

```json
{
  "issues": [
    {
      "title": "X-SQL output column order does not preserve SQL SELECT order",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "Write a SQL query with SELECT dom_first_text(dom, '#productTitle') AS title, dom_first_text(dom, '#product-price') AS price FROM load_and_select(@url, 'body'). Run crawl with --sql @file. The output shows price before title in all formats (CSV, table, JSON), reversing the SELECT clause order.",
      "expected": "Column order in output should match the order specified in the SQL SELECT clause: title first, then price.",
      "actual": "Columns appear in alphabetical order: price first, then title. This occurs in CSV headers (price,title), table columns (price | title), and JSON key order (price before title).",
      "rootCause": "The result aggregation likely uses a Map or sorted structure (e.g., TreeMap in Kotlin/Java) keyed by column name, which sorts columns alphabetically rather than preserving insertion order. The SQL result set's column order from H2 is being lost during aggregation across multiple pages.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt or wherever crawl X-SQL results are aggregated into output rows.",
      "suggestion": "- Use a LinkedHashMap or similar order-preserving data structure when aggregating X-SQL results across pages\n- Alternatively, propagate the SELECT column order from the SQL metadata and use it when formatting output\n- Add a test that verifies SELECT column order is preserved in all output formats (CSV, table, JSON)"
    },
    {
      "title": "Function name casing inconsistency between crawl.md and x-sql.md documentation",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Read skills/browser4-cli/references/crawl.md which uses lowercase `load_and_select(@url, 'body')` and `dom_first_text(dom, ...)`. Then read skills/browser4-cli/references/x-sql.md which uses uppercase `DOM_LOAD_AND_SELECT(...)` and `DOM_FIRST_TEXT(DOM, ...)`.",
      "expected": "Consistent casing across all documentation files. Either all lowercase or all uppercase, with a note that H2 SQL is case-insensitive for function names.",
      "actual": "crawl.md examples use all-lowercase function names. x-sql.md uses all-uppercase function names. A first-time user would wonder which is correct or whether they are different functions.",
      "rootCause": "The two reference files were likely authored at different times or by different contributors without a shared style convention. H2 SQL is case-insensitive so both work, but the inconsistency creates confusion.",
      "codePointer": "",
      "suggestion": "- Standardize on one casing convention across all documentation (prefer UPPERCASE as it's the SQL convention and matches the x-sql.md function index)\n- Add a brief note in crawl.md acknowledging both forms work: \"Function names are case-insensitive — DOM_FIRST_TEXT and dom_first_text are equivalent\"\n- Run a documentation lint check to catch casing inconsistencies in future updates"
    },
    {
      "title": "Crawl help says --out-link-selector is 'required for depth >= 1' but crawl works without it",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Run `crawl --help` and note the line: \"--out-link-selector (-ol) specifies a CSS selector to extract links (required for depth >= 1).\" Then run `crawl --seed-file urls.txt --sql @query.sql --format csv -o out.csv` without --depth or --out-link-selector. Default depth is 1, but the crawl completes successfully processing only seed URLs.",
      "expected": "Either the crawl should error out when depth >= 1 and no out-link-selector is provided (matching the docs), or the docs should accurately describe the fallback behavior.",
      "actual": "The crawl silently degrades to seed-only processing when no out-link-selector is provided, even at default depth=1. The user gets no warning that link discovery was skipped.",
      "rootCause": "The crawl implementation treats absence of --out-link-selector as \"no link discovery\" regardless of depth setting. This is a reasonable UX choice (don't force errors for bulk-fetch workflows) but contradicts the documented requirement.",
      "codePointer": "",
      "suggestion": "- Update the help text to say: \"--out-link-selector (-ol) specifies a CSS selector to extract links. Required for link discovery; without it, only seed URLs are processed regardless of depth.\"\n- Consider emitting an informational note when depth >= 1 but no out-link-selector is provided: \"Link discovery disabled (no --out-link-selector). Processing seed URLs only.\""
    }
  ],
  "assessment": {
    "completionStatus": "Successful — Both AC5 and AC6 passed on first attempt. The crawl command correctly extracted product titles and prices from two seed URLs using both --sql @file (CSV output to file) and --sql-stdin (table output to stdout). Cross-format comparison with JSON confirmed identical extraction results across all three output formats.",
    "successRate": "100% — all task steps and acceptance criteria succeeded without errors or workarounds",
    "issuesFound": 3,
    "majorBlockers": "",
    "mostConfusingAspects": "1. The column order in output (price,title) doesn't match the SQL SELECT order (title,price) — a first-time user might doubt whether the extraction worked correctly. 2. The function name casing difference between crawl.md (lowercase) and x-sql.md (uppercase) makes it unclear which convention to follow, though both work. 3. The --out-link-selector 'required' warning in --help contradicts actual behavior — a cautious user might add an unnecessary flag or avoid using the command.",
    "mostValuableImprovements": "1. Preserve SQL SELECT column order in output — this is the most impactful fix as it affects result readability and trust in all X-SQL extraction workflows. 2. Standardize function name casing across documentation to reduce first-time-user confusion. 3. Add an informational note when depth >= 1 but no out-link-selector is provided so users understand link discovery was intentionally skipped.",
    "usabilityRating": 8
  }
}
```

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

#### Root Cause Analysis

The result aggregation likely uses a Map or sorted structure (e.g., TreeMap in Kotlin/Java) keyed by column name, which sorts columns alphabetically rather than preserving insertion order. The SQL result set's column order from H2 is being lost during aggregation across multiple pages.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt or wherever crawl X-SQL results are aggregated into output rows.`

#### AI Suggested Improvement

- Use a LinkedHashMap or similar order-preserving data structure when aggregating X-SQL results across pages
- Alternatively, propagate the SELECT column order from the SQL metadata and use it when formatting output
- Add a test that verifies SELECT column order is preserved in all output formats (CSV, table, JSON)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

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

#### Root Cause Analysis

The two reference files were likely authored at different times or by different contributors without a shared style convention. H2 SQL is case-insensitive so both work, but the inconsistency creates confusion.

#### AI Suggested Improvement

- Standardize on one casing convention across all documentation (prefer UPPERCASE as it's the SQL convention and matches the x-sql.md function index)
- Add a brief note in crawl.md acknowledging both forms work: "Function names are case-insensitive — DOM_FIRST_TEXT and dom_first_text are equivalent"
- Run a documentation lint check to catch casing inconsistencies in future updates

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

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

#### Root Cause Analysis

The crawl implementation treats absence of --out-link-selector as "no link discovery" regardless of depth setting. This is a reasonable UX choice (don't force errors for bulk-fetch workflows) but contradicts the documented requirement.

#### AI Suggested Improvement

- Update the help text to say: "--out-link-selector (-ol) specifies a CSS selector to extract links. Required for link discovery; without it, only seed URLs are processed regardless of depth."
- Consider emitting an informational note when depth >= 1 but no out-link-selector is provided: "Link discovery disabled (no --out-link-selector). Processing seed URLs only."

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

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

