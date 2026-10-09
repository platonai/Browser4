# Issues: x-sql-query-methods

> **Source:** `20261004-174741-x-sql-query-methods.full.md` | **Date:** 20261004-174741 | **Mode:** dev

## Scenario Background

### Task

**Outcome: Successful.** I navigated to `http://books.toscrape.com/` (redirected to `https://books.toscrape.com/`), used `htmlsnapshot inspect` to auto-discover the page's repeating structure, and extracted all **20 books on page 1** with **full title + price** via X-SQL, exercising **all four documented SQL input methods** plus `--result-only`:

```
title                                                                        | price
A Light in the Attic                                                         | £51.77
Tipping the Velvet                                                           | £53.74
Soumission                                                                   | £50.10
Sharp Objects                                                                | £47.82
Sapiens: A Brief History of Humankind                                        | £54.23
... (20 rows total, full titles from the title attribute)
It's Only the Himalayas                                                      | £45.17
```

Selectors discovered / used: `article.product_pod` (item container), `h3 a` (title link), `p.price_color` (price). The working query:

```sql
SELECT
  DOM_FIRST_ATTR(DOM, 'h3 a', 'title') AS title,
  DOM_FIRST_TEXT(DOM, 'p.price_color') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')
```

Note: `DOM_FIRST_TEXT(DOM, 'h3 a')` — the pattern the docs and `inspect` samples imply — returns the site's **ellipsized** link text (`"A Light in the ..."`). The full title only exists in the `title` attribute, so I switched the query to `DOM_FIRST_ATTR`. The temporary `extract_books.sql` (and its `.b64` sibling) were deleted after use.

### Execution Context

| Step | Command (all via `./b4w.ps1`, session `-s books4eval`) | Result |
|---|---|---|
| 0 | `status` | Backend already UP (v4.13.27-SNAPSHOT, matches checkout); stale default session noted |
| 1 | `goto "http://books.toscrape.com/"` | OK → `https://books.toscrape.com/`, new named session created, title "All products \| Books to Scrape" |
| 2 | `htmlsnapshot inspect` | Auto-discovered `.product_pod` (20 matches); suggested `p.price_color` (★), `img.thumbnail`, and several starred `:expr()` selectors; plain `h3 a` was **absent** (found manually from the sample structure) |
| 2b | `htmlsnapshot get all attr "h3 a" title` vs `get all text "h3 a"` | Proved the link text is site-truncated; full titles are in `title` |
| 3 | `htmlsnapshot query --sql "<inline SQL>" --format table` | 20 rows, e...

(truncated — see full.md for complete trace)

---

## Issues Found (5 issues)

### Issue 1: Invalid X-SQL syntax (HTTP 400) exits 0 and is displayed as an empty result set

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s books4eval htmlsnapshot query --sql "SELECT FROM WHERE" --format table; echo $?  -> prints "No data." and "0 rows returned.", exit code 0. In default JSON mode the same query prints an envelope with statusCode 400, status "Bad Request" and message "X-SQL syntax error: Syntax error in SQL statement ... [42001-197]", still exit code 0.

#### Expected Behavior

Any error envelope (4xx/5xx, e.g. 400 Bad Request) should print the X-SQL failure banner with the server message and return a nonzero exit, exactly like the documented 417 path, so scripts branching on exit code cannot mistake a broken query for "no rows matched".

#### Actual Behavior

Only statusCode == 417 and >= 500 are treated as failures. A 400 falls through to the success branch, renders as "No data. / 0 rows returned." in table mode, and the process exits 0. CI steps keying off exit code silently accept a syntactically invalid query.

#### Root Cause Analysis

The result handler's status dispatch special-cases only 417 (session race) and 5xx (backend error). HTTP 400 from the H2/X-SQL syntax error is matched by neither branch and takes the success path, where an empty resultSet is a valid outcome.

#### Code Pointer

`cli/browser4-cli/src/main.rs:8093-8178 (handle_html_snapshot_query result handling: `if status_code == 417 ... else if status_code >= 500 ... else` branch)`

#### AI Suggested Improvement

- Treat any statusCode >= 400 as an error envelope, not just 417 and 5xx.
- Print the server `message` with the same actionable banner used for 417 and propagate a nonzero exit code.
- Add a CLI test asserting a nonzero exit for a server envelope with statusCode 400 and an empty resultSet.
- Update the htmlsnapshot.md / x-sql.md exit-code tables to state that every 4xx/5xx envelope is nonzero.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: CSV output on stdout and in --output-file is polluted by a human-readable footer and inline-SQL tip

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s books4eval htmlsnapshot query --sql @query.sql --format csv > rows.csv   (or --format csv --output-file rows.csv). The file ends with a blank line followed by "20 rows returned." (byte-verified). Running the inline form without @file additionally appends two lines starting with a lightbulb tip.

#### Expected Behavior

CSV is the documented machine format ("pipe to a file: browser4-cli htmlsnapshot query ... --format csv > rows.csv"); stdout and --output-file should contain only CSV records, with counts/tips on stderr or gated on a TTY.

#### Actual Behavior

A summary string ("\nN rows returned.\n") is concatenated into both the CSV and table outputs, and the inline-SQL tip is printed to stdout. --output-file writes the same polluted buffer to disk, so every documented file-output path produces a file that downstream parsers (pandas, csvkit, Miller) must post-process.

#### Root Cause Analysis

Human-facing annotations are appended to the same string that is written to stdout or --output-file, and tips use the plain stdout printer with no format/TTY awareness.

#### Code Pointer

`cli/browser4-cli/src/main.rs:8172-8177 (format_csv(rows) + &summary) and cli/browser4-cli/src/main.rs:8215-8235 (output write plus is_inline_sql tip)`

#### AI Suggested Improvement

- Emit the row-count summary and tips to stderr for csv (and table), never into --output-file.
- Or gate all annotations on --show-tip and on stdout being a TTY, keeping data streams pure.
- Add a regression test that --format csv output and --output-file content are exactly the CSV records (no trailing summary).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: htmlsnapshot inspect ranks PowerCSS :expr() selectors as high-quality even though they silently return empty inside X-SQL DOM_* selectors

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 -s books4eval htmlsnapshot inspect lists starred rows such as "a:expr(img>0)", "a:expr(a>0)", "img:expr(img>0)", "h3:expr(a>0)", with the legend "star = high-quality (specific enough to use reliably)" and "Use these selectors with htmlsnapshot get ... The SQL variant lets you query". Then: ./b4w.ps1 -s books4eval htmlsnapshot query --sql "SELECT DOM_FIRST_TEXT(DOM, 'a:expr(img>0)') AS t, DOM_FIRST_TEXT(DOM, 'h3 a') AS h FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')" --format table -> the t column is empty for all 20 rows while h is populated; no error, exit 0. The same :expr filter works in the FROM clause.

#### Expected Behavior

Selectors presented as reliably extractable should either work in both paths the tool recommends (htmlsnapshot get and X-SQL DOM_* arguments) or carry an explicit scope warning; and the plain recurring selector for titles (h3 a) should be suggested for a list page of books.

#### Actual Behavior

The starred list is dominated by :expr() variants, and the only title-bearing suggestion is "h3:expr(a>0)" (starred); bare h3 is relegated to the discouraged structural list, and plain "h3 a" never appears. A first-time user following the starred suggestion into X-SQL gets a silently empty column. The limitation is documented deep in x-sql.md, but the inspect output that recommends the selectors does not mention it.

#### Root Cause Analysis

The backend inspect scorer adds `descTag:expr(...)` candidates weighted as power selectors and qualityTier maps them to "high"; scoring is unaware of where the selector will be evaluated, and no plain tag-plus-descendant candidate (h3 a) is generated when the PowerCSS variant covers the same nodes.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:1556 (inspectDocument; PowerCSS candidates around 1808-1837; qualityTier around 1906) and CLI rendering cli/browser4-cli/src/main.rs:8807 (handle_html_snapshot_inspect)`

#### AI Suggested Improvement

- Mark :expr() suggestions with a scope hint ("live page only") and exclude or down-rank them from the SQL examples.
- Generate plain structural selectors such as `tag descendant` when they recur, instead of only the PowerCSS variant.
- Append a note to :expr rows: not evaluated inside DOM_* X-SQL selector arguments (silently matches nothing).
- Refresh the sample output in htmlsnapshot.md, which still shows `h3 a` as a 100% suggestion on this exact page.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Documented title-extraction pattern silently returns site-truncated link text; full titles live in the title attribute

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s books4eval htmlsnapshot get all text "h3 a" --limit 5 returns ["A Light in the ...", "Tipping the Velvet", ...] while get all attr "h3 a" title returns the full titles ("A Light in the Attic", "Sapiens: A Brief History of Humankind"). Same distinction holds for X-SQL DOM_FIRST_TEXT vs DOM_FIRST_ATTR(DOM, 'h3 a', 'title').

#### Expected Behavior

The canonical list-page extraction pattern should note that visible link text can be truncated by the site and that the full value may live in an attribute (title/aria-label); alternatively inspect could flag samples ending in an ellipsis.

#### Actual Behavior

The quick-reference pattern ("h3 a -> all product titles") recommends DOM_FIRST_TEXT on the title link with no caveat, so a user following the docs extracts silently truncated titles and has no signal that richer data exists in the attribute.

#### Root Cause Analysis

books.toscrape.com renders <a title="Full title">Truncated...</a>; element-text extraction faithfully returns the truncated text. The documented examples assume element text is the full value.

#### Code Pointer

`skills/browser4-cli/references/x-sql.md (Quick Reference: Scrape a list page) and skills/browser4-cli/references/htmlsnapshot.md (get all examples)`

#### AI Suggested Improvement

- Add a "truncated link text" note to the list-page pattern: prefer DOM_FIRST_ATTR(DOM, 'h3 a', 'title') when visible text ends in an ellipsis.
- Consider having inspect detect "..."/ellipsis samples and print an attribute hint next to the selector.
- Add the get all text vs get all attr comparison to the empty/partial-results troubleshooting steps.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: X-SQL reference warns that the 2-argument DOM_FIRST_FLOAT form is unregistered and fails with 417, but it works in this build

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s books4eval htmlsnapshot query --sql "SELECT DOM_FIRST_FLOAT(DOM, 'p.price_color') AS p FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')" --format table prints 51.77 / 53.74 / 50.1 / ... and exits 0.

#### Expected Behavior

Documentation should match the shipped runtime: either the 2-argument form fails as described, or the warning is removed/updated.

#### Actual Behavior

The 2-argument call succeeds and returns prices. x-sql.md states the 2-argument forms are not registered and fail with Method DOMFIRSTFLOAT parameter count: 2 not found (HTTP 417); that guidance is stale for this build and needlessly constrains queries.

#### Root Cause Analysis

The server-side UDF registration was changed to accept the 2-argument form (or supply a default) without a matching doc update. The exact commit is unknown and needs a doc/version sync check.

#### Code Pointer

`skills/browser4-cli/references/x-sql.md (Number-extraction functions note)`

#### AI Suggested Improvement

- Re-test documented arities for DOM_FIRST_FLOAT / DOM_NTH_FLOAT / DOM_FIRST_INTEGER / DOM_ALL_FLOATS against the current backend and update the note.
- If the 2-argument form is intentionally supported, document the default used when the selector matches nothing.
- Add a doc-vs-behavior test covering each documented function signature.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful - all 8 task steps completed: navigation, inspect-based selector discovery, one X-SQL query run through all four documented input paths (inline --sql, --sql @file, --sql-stdin, --sql-base64), --result-only verification, and deletion of the temporary extract_books.sql. 20 books extracted with full titles and GBP prices.

**Success Rate:** 100% of task steps completed; one in-task correction (switched title extraction from link text to the title attribute after discovering site-side ellipsis truncation).

**Issues Found:** 5

**Major Blockers:** None. The backend was already running and every required command succeeded. The hazards found were silent correctness/UX problems (truncated titles, exit 0 on invalid SQL, polluted CSV) rather than blocking failures.

**Most Confusing Aspects:** - Four overlapping ways to pass SQL with different quoting hazards; help does not say when each is preferred, and the tip recommending @file only appears after you have already run inline SQL.
- htmlsnapshot inspect stars :expr() selectors as reliable although they silently match nothing inside X-SQL DOM_* arguments, and the plain `h3 a` title selector shown in the docs' example was absent from the live suggestions.
- DOM_FIRST_TEXT returning the site's ellipsized link text with no signal that the full value is in the title attribute.
- Help documents --sql-base64 as taking a value while the reference shows it as a bare flag beside --sql @file.b64; both work, but the dual mode is unexplained.
- The row-count and tip footer appearing in machine-readable output, including inside --output-file, breaks pipelines.

**Most Valuable Improvements:** 1) Keep machine-readable output pure: csv/json/--result-only and --output-file must contain data only, with footers and tips on stderr or behind a TTY/--show-tip check. 2) Treat every >=400 error envelope as a failure with a nonzero exit and the server message. 3) Make inspect's suggestions evaluation-aware: annotate or drop :expr() for X-SQL and surface plain recurring selectors like `h3 a`. 4) Document the truncated-link-text pitfall and the title-attribute alternative in the list-page extraction pattern.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: Invalid X-SQL syntax (HTTP 400) exits 0 and is displayed as an empty result set

./b4w.ps1 -s books4eval htmlsnapshot query --sql "SELECT FROM WHERE" --format table; echo $?  -> prints "No data." and "0 rows returned.", exit code 0. In default JSON mode the same query prints an envelope with statusCode 400, status "Bad Request" and message "X-SQL syntax error: Syntax error in SQL statement ... [42001-197]", still exit code 0.

#### Issue 2: CSV output on stdout and in --output-file is polluted by a human-readable footer and inline-SQL tip

./b4w.ps1 -s books4eval htmlsnapshot query --sql @query.sql --format csv > rows.csv   (or --format csv --output-file rows.csv). The file ends with a blank line followed by "20 rows returned." (byte-verified). Running the inline form without @file additionally appends two lines starting with a lightbulb tip.

#### Issue 3: htmlsnapshot inspect ranks PowerCSS :expr() selectors as high-quality even though they silently return empty inside X-SQL DOM_* selectors

./b4w.ps1 -s books4eval htmlsnapshot inspect lists starred rows such as "a:expr(img>0)", "a:expr(a>0)", "img:expr(img>0)", "h3:expr(a>0)", with the legend "star = high-quality (specific enough to use reliably)" and "Use these selectors with htmlsnapshot get ... The SQL variant lets you query". Then: ./b4w.ps1 -s books4eval htmlsnapshot query --sql "SELECT DOM_FIRST_TEXT(DOM, 'a:expr(img>0)') AS t, DOM_FIRST_TEXT(DOM, 'h3 a') AS h FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')" --format table -> the t column is empty for all 20 rows while h is populated; no error, exit 0. The same :expr filter works in the FROM clause.

#### Issue 4: Documented title-extraction pattern silently returns site-truncated link text; full titles live in the title attribute

./b4w.ps1 -s books4eval htmlsnapshot get all text "h3 a" --limit 5 returns ["A Light in the ...", "Tipping the Velvet", ...] while get all attr "h3 a" title returns the full titles ("A Light in the Attic", "Sapiens: A Brief History of Humankind"). Same distinction holds for X-SQL DOM_FIRST_TEXT vs DOM_FIRST_ATTR(DOM, 'h3 a', 'title').

#### Issue 5: X-SQL reference warns that the 2-argument DOM_FIRST_FLOAT form is unregistered and fails with 417, but it works in this build

./b4w.ps1 -s books4eval htmlsnapshot query --sql "SELECT DOM_FIRST_FLOAT(DOM, 'p.price_color') AS p FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')" --format table prints 51.77 / 53.74 / 50.1 / ... and exits 0.

