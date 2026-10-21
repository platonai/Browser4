# Issues: x-sql-extraction-functions

> **Source:** `20261013-174254-x-sql-extraction-functions.issues.json` | **Date:** 20261013-174254 | **Mode:** dev

## Scenario Background

### Task

**Task completed successfully.** The MockSite Electronics category page (`http://localhost:18080/ec/b?node=1292115012`) was navigated, snapshotted, inspected, and fully extracted with a single X-SQL query covering every required DOM/STR/ARRAY function, PowerCSS `:expr()`, and WHERE/ORDER BY/LIMIT. All extracted values were verified against the raw page markup (exported to `page-ec-listing.html` in this run's scratch dir).

### Final query (`.test-sessions/20261013T0154334271610Z/query-final.sql`)

```sql
SELECT
    STR_ABBREVIATE(STR_TRIM(ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, '.product-title'),
        DOM_FIRST_ATTR(DOM, 'img.product-img', 'alt'),
        DOM_FIRST_TEXT(DOM, 'a.product-link'),
        'Untitled product'
    ))), 22) AS title,
    DOM_FIRST_FLOAT(DOM, '.product-price', 0.0) AS price,
    STR_FIRST_FLOAT(ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(
        DOM_FIRST_ATTR(DOM, '.product-rating', 'data-rating'),
        DOM_FIRST_TEXT(DOM, '.product-rating')
    )), 0.0) AS rating,
    DOM_FIRST_HREF(DOM, 'a.product-link') AS link,
    DOM_FIRST_IMG(DOM, 'img.product-img') AS image,
    STR_DEFAULT_IF_BLANK(DOM_FIRST_ATTR(DOM, '.product-card', 'data-category-id'), 'uncategorized') AS category_id,
    STR_UPPER_CASE(ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, '.product-badges'),
        'standard'
    ))) AS badge
FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card:expr(width > 180 && height > 250)')
WHERE CAST(DOM_FIRST_FLOAT(DOM, '.product-price', 0.0) AS DOUBLE) >= 25.0
  AND STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, '.product-rating'), 0.0) >= 4.2
ORDER BY CAST(DOM_FIRST_FLOAT(DOM, '.product-price', 0.0) AS DOUBLE) DESC
LIMIT 5
```

### Result (`./b4w.ps1 -s xsql-eval htmlsnapshot query --sql "@…/query-final.sql" --format table`)

```
5 rows returned.
  title                  | price  | rating | link                                   | image                                         | category_id | badge
  -----------------------+--------+--------+----------------------------------------+-----------------------------------------------+-------------+-----------
  4K OLED TV 55          | 899.99 | 4.6    | http://localhost:18080/ec/dp/B0E000001 | https://picsum.photos/seed/1250857624/200/140 | 1292115012  | BESTSELLER
  Smartphone 128GB       | 599.0  | 4.5    | http://localhost:18080/ec/dp/B0E000004 | https://picsum.photos/seed/1250857627/200/140 | 1292115012  | HOT
  Wireless Noise-Canc... | 199.99 | 4.4    | http://localhost:18080/ec/dp/B0E000002 | https://picsum.photos/seed/1250857625/200/140 | 1292115012  | BESTSELLER
  Portable Bluetooth ... | 49.99  | 4.3    | http://localhost:18080/ec/dp/B0E000003 | https://picsum.photos/seed/1250857626/200/140 | 1292115012  | STANDARD
  USB-C Hub 7-in-1       | 29.95  | 4.2    | http://localhost:18080/ec/dp/B0E000005 | https://picsum.photos/seed/1250857628/200/140 | 1292115012  | STANDARD
```

Every requirement was exercised and verified: `DOM_FIRST_TEXT/FLOAT/HREF/IMG/ATTR` (absolute URLs auto-resolved), `STR_TRIM / STR_UPPER_CASE / STR_DEFAULT_IF_BLANK / STR_FIRST_FLOAT / STR_ABBREVIATE`, three `ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(…))` fallback chains, `:expr()` (verified actually evaluated — an impossible `height > 1000` filter returned 0 rows), `WHERE` (excluded the $24.99 / 4.1★ mouse), `ORDER BY price DESC`, `LIMIT 5`. Machine-readable output saved as `07-final-result.json`.

### Execution Context

**Key Commands:**

| Step | Command | Outcome |
|---|---|---|
| 0 | `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` + references (`htmlsnapshot.md`, `x-sql*.md`, `power-dom.md`) | Learned the documented workflow and the exact function contracts |
| 1 | `curl http://localhost:18080/ec/b?node=1292115012` | MockSite already up (200) — `./bin/test.ps1 mock-site` not needed |
| 2 | `./b4w.ps1 list` | Backend already running; isolated run into a dedicated session per docs |
| 3 | `./b4w.ps1 -s xsql-eval goto "http://localhost:18080/ec/b?node=1292115012"` | Session auto-created (headless), page loaded, title "Category: Electronics" |
| 4 | `./b4w.ps1 -s xsql-eval htmlsnapshot` | Capture: 9 KB, 7 images, 9 links, 22 interactive elements |
| 5 | `./b4w.ps1 -s xsql-eval htmlsnapshot inspect --max 3 --depth 3` | Auto-discovered `div.product-card` (6 matches) + `.product-title/.product-price/.product-rating/a.product-link/img.product-img` |
| 6 | `./b4w.ps1 -s xsql-eval htmlsnapshot export --file …/page-ec-listing.html` | Ground-truth markup for field/mapper validation |
| 7 | `… htmlsnapshot query --sql "@…/query-v1.sql" --format table` | All 6 products extracted on first run |
| 8 | `… htmlsnapshot query --sql "@…/query-final.sql" --format table` (x2) + `--result-only` | 5 verified rows, deterministic on rerun |
| 9 | Probes: `:expr(height>1000)`, `COUNT(*)`, self-`JOIN`, `WITH` (CTE), `get … --limit/--bogus`, `get all … --limit 3` | Verified `:expr` evaluation; surfaced the issues below |

**Key decisions:** dedicated named session `xsql-eval` (docs: default session is a singleton); SQL passed via `--sql @file` (docs: avoid inline quoting on Windows); `CAST(… AS DOUBLE)` in WHERE (documented `DOM_FIRST_FLOAT` comparison pitfall); tag-qualified `a.product-link` for `DOM_FIRST_HREF` (documented class-only-selector pitfall); `:expr` kept in the FROM clause (documented as the only reliable evaluation site).

**Workarounds required:** none for the happy path. One tool-suggested command had to be corrected by hand (see Issue 1): the "Try these next" image/link suggestions silently return only the first URL.

---

---

## Issues Found (6 issues)

### Issue 1: inspect's "Try these next" image/link suggestions omit `all` — silently return only the first URL

**Severity:** Medium
**Category:** UX

#### Reproduction

1) ./b4w.ps1 -s xsql-eval htmlsnapshot inspect --max 3 --depth 3
2) Copy and run the suggested line: ./b4w.ps1 -s xsql-eval htmlsnapshot get attr "img[src]" src --limit 20  # image URLs
3) Compare with the correct form: ./b4w.ps1 -s xsql-eval htmlsnapshot get all attr "img[src]" src --limit 20

#### Expected Behavior

The suggestion labeled '# image URLs' / '# link URLs' should list ALL matching image/link URLs (as the sibling `get all text …` suggestions do), i.e. it should invoke `htmlsnapshot get all attr`.

#### Actual Behavior

The suggested command exits 0 and prints exactly ONE URL (the first match, an unrelated placeholder image); `--limit 20` is silently ignored, so a user copying the tool's own suggestion for batch extraction gets silently partial data with no error or warning.

#### Root Cause Analysis

The suggestion strings are hard-coded and inconsistent: the `get all text` lines pass the correct subcommand, but the image/link-url lines were written as `htmlsnapshot get attr …` (missing `all`). The stray `--limit 20` on single `get` is additionally tolerated by the parser (see related unknown-flag issue), so the mistake fails silently instead of erroring. Same strings duplicated in three places (inspect renderer + capture tip).

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_inspect() lines ~11332-11347 (also ~11345-11347 fallback branch) and handle_html_snapshot_capture() lines ~9203-9206`

#### AI Suggested Improvement

- Change the emitted lines to `htmlsnapshot get all attr "img[src]" src --limit 20`, `htmlsnapshot get all attr "a[href]" href --limit 20`, and the `:expr` large-images variant to use `get all`
- Generate these suggestion lines from one shared helper so the three call sites cannot drift apart again
- Add a unit/integration test asserting every printed 'Try these next' command parses as a valid command (e.g. rejects `get` combined with `--limit`)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Unrecognized/misplaced flags are silently ignored by htmlsnapshot get / get all (e.g. --bogus)

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s xsql-eval htmlsnapshot get text "div.product-title" --bogus 1   → prints '4K OLED TV 55', exit 0
./b4w.ps1 -s xsql-eval htmlsnapshot get all text "div.product-title" --bogus 1   → prints all 6 titles, exit 0
./b4w.ps1 -s xsql-eval htmlsnapshot get text "div.product-title" --limit 20   → prints 1 title, exit 0 (flag ignored)

#### Expected Behavior

An unknown flag should be a usage error (the CLI already rejects other stray flags, e.g. `-s` placed after the command, with the hint 'global flags must appear before the command'), or at minimum produce a stderr warning naming the ignored flag.

#### Actual Behavior

Unknown `--flag value` pairs are accepted silently; the command runs with default semantics and exit 0. A typo'd flag (e.g. `--abslute`, `--lmmit`) silently changes behavior with no feedback.

#### Root Cause Analysis

parse_raw_args() inserts every `--key value` token into a params map without validating it against the invoked command's declared option set; per-command tool_params builders read only known keys, so unknown keys disappear without a trace.

#### Code Pointer

`cli/browser4-cli/src/args.rs:parse_raw_args() (line 223) — validation would need the command's declared option set, available near the dispatch layer in cli/browser4-cli/src/main.rs`

#### AI Suggested Improvement

- Validate parsed flag keys against the command's declared options and reject unrecognized ones with the existing usage-error style
- If lenient parsing is deliberate (e.g. MCP passthrough), emit a stderr warning listing ignored flags
- Add a regression test that `htmlsnapshot get text "h1" --bogus 1` fails (or warns) instead of succeeding silently

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: X-SQL reference says 'no joins' but a JOIN of two DOM_LOAD_AND_SELECT sources executes successfully

**Severity:** Low
**Category:** Documentation

#### Reproduction

Write probe-join.sql:
SELECT COUNT(*) AS n FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card') a JOIN DOM_LOAD_AND_SELECT(@url, 'div.product-card') b ON 1=1
Run: ./b4w.ps1 -s xsql-eval htmlsnapshot query --sql "@…/probe-join.sql" --format table

#### Expected Behavior

Per skills/browser4-cli/references/x-sql.md ('No other SQL syntax is supported — no CTEs (WITH), no subqueries in FROM, no EXPLODE, no joins'), the query should be rejected.

#### Actual Behavior

Executes successfully: 1 row, n = 36 (= 6 cards × 6 cards). (For contrast, CTEs are indeed rejected, and COUNT(*) aggregates work — both as documented.) The CLI's own hint text 'The SQL variant lets you query with full expressive power (joins, filters, aggregates)' is therefore right about joins while the reference says they are impossible.

#### Root Cause Analysis

The restriction documented in x-sql.md is at least partly policy-only: SQLTemplate.createSQL() produces a plain SELECT that H2 executes, and no backend validation rejects JOINed table-function sources. Either enforcement is missing or the doc is stale; the two sources of truth need reconciling. Note joins duplicate page loads per source, which may be why they are discouraged.

#### Code Pointer

`skills/browser4-cli/references/x-sql.md (constraint block above 'URL parameter', line ~23); enforcement would live in the backend X-SQL validation layer (browser4-rest scrape session / SQLTemplate)`

#### AI Suggested Improvement

- Decide the real contract, then make docs and behavior agree: either state which JOIN forms are supported (and their page-load cost) or reject JOIN at validation time with a 4xx
- Mention that aggregates (COUNT/SUM) and self-joins behave differently from the blanket 'no joins' statement
- Add a test pinning the accepted FROM-clause grammar so docs cannot drift again

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Rejected non-SELECT X-SQL (CTE) returns HTTP 500 instead of the documented 400/417

**Severity:** Low
**Category:** Reliability

#### Reproduction

Write probe-cte.sql:
WITH cards AS (SELECT DOM_FIRST_TEXT(DOM, '.product-title') AS t FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card'))
SELECT t FROM cards LIMIT 3
Run: ./b4w.ps1 -s xsql-eval htmlsnapshot query --sql "@…/probe-cte.sql" --format table

#### Expected Behavior

According to skills/browser4-cli/references/htmlsnapshot.md's status table, an X-SQL syntax/query error should surface as 400 Bad Request (or 417 Expectation Failed), letting callers distinguish bad input from backend failure.

#### Actual Behavior

HTTP 500 'Internal Server Error' with message 'Only select statements are supported'; the CLI prints '### X-SQL Query Failed (500 Internal Server Error) — Check the backend logs for details' and exits 1. A user input rejection is reported as a server fault.

#### Root Cause Analysis

The backend's pre-validation ('Only select statements are supported') maps to statusCode 500 in the response envelope instead of a 4xx client-error class. The CLI faithfully forwards the server status.

#### AI Suggested Improvement

- Map the non-SELECT rejection to 400 Bad Request in the backend query endpoint (a client-usable error), or 417 to match the existing convention for query failures
- If 500 is intentional for this class, update the htmlsnapshot.md status table so scripts branch correctly
- Make the CLI hint distinguish 'your query was rejected' from 'the backend broke'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Capture metadata's `w=` (visual weight) value is undocumented

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 -s xsql-eval htmlsnapshot   → interactive element lines like:
  1. button.shop-btn  "SHOP NOW →"  [box=286 248 123 35; w=1004305]
Then check ./b4w.ps1 --help htmlsnapshot — no mention of `w=`.

#### Expected Behavior

The numerical field printed in primary output should be explained — in `--help htmlsnapshot` and/or a one-line legend — or self-describing.

#### Actual Behavior

`w=1004305` appears with no explanation anywhere in the CLI help or skill references; only a source comment reveals it is a visual-prominence weight used to sort the list.

#### Root Cause Analysis

The renderer appends `w=<weight>` as a free-form extra while the help text's description of interactive elements lists only tag/class/id/aria/bounding-box.

#### Code Pointer

`cli/browser4-cli/src/main.rs:9103 (extras.push(format!("w={}", weight))) — help text in cli/browser4-cli/src/help.rs`

#### AI Suggested Improvement

- Document the weight in `--help htmlsnapshot` (e.g. 'w = visual-prominence weight; elements are sorted by it, descending')
- Consider labeling it `weight=` in the output so it is self-describing
- Hide it behind `--verbose` if it is diagnostic rather than user-facing

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Numeric X-SQL results serialize as JSON strings in --result-only output

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 -s xsql-eval htmlsnapshot query --sql "@…/query-final.sql" --result-only  → {"title": "4K OLED TV 55", "price": "899.99", "rating": "4.6", …}

#### Expected Behavior

Machine-readable output should either carry JSON numbers for numeric-returning functions (DOM_FIRST_FLOAT, STR_FIRST_FLOAT) or document that they are emitted as strings.

#### Actual Behavior

price and rating are quoted strings ('899.99', '4.6'); consumers must parse them. (Workaround: wrap the expression in a numeric cast in the SELECT list, e.g. CAST(DOM_FIRST_FLOAT(…) AS DOUBLE).)

#### Root Cause Analysis

DOM_FIRST_FLOAT/STR_FIRST_FLOAT values ride custom H2 value types whose serialization is string-based; the JSON envelope copies that string form.

#### AI Suggested Improvement

- Add a line to skills/browser4-cli/references/x-sql.md: numeric-returning functions serialize as strings unless CAST(… AS DOUBLE) is selected
- Consider emitting JSON numbers for ValueFloat/ValueInt in the resultSet encoder so the default output is type-correct
- Show the typed example in the x-sql.md quick-reference so users write machine-friendly queries by default

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

**Completion Status:** Successful — all 9 scenario steps completed. Page navigation, HTML snapshot capture, selector discovery via inspect (--max 3 --depth 3), the enhanced X-SQL query (DOM + STR + ARRAY + PowerCSS :expr() + WHERE/ORDER BY/LIMIT) and final result review were all performed, and every extracted value was verified against the raw page markup.

**Success Rate:** 100% of scenario steps succeeded on the first or second attempt; the only friction was one silently-wrong suggestion line emitted by the tool itself (Issue 1), which was detected during cross-checks.

**Issues Found:** 6

**Most Confusing Aspects:** For a first-time user: (1) the difference between `get` and `get all` is easy to miss, and the tool's own suggested command for image/link URLs uses the wrong one, silently returning a single row; (2) unknown or misplaced flags are silently ignored, so typos produce quietly different results instead of errors; (3) unexplained output fields (`w=` weights in capture metadata); (4) whether X-SQL supports joins/aggregates is contradictory between the CLI hint text and the X-SQL reference.

**Most Valuable Improvements:** Fix the `get`/`get all` suggestion strings (and centralize their generation); validate flags against each command's declared options so mistakes fail loudly; reconcile the X-SQL capability documentation with actual behavior (joins, CTE rejection status code, numeric-as-string serialization); document the `w=` weight field.

**Usability Rating:** 8/10

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

#### Issue 1: inspect's "Try these next" image/link suggestions omit `all` — silently return only the first URL

1) ./b4w.ps1 -s xsql-eval htmlsnapshot inspect --max 3 --depth 3
2) Copy and run the suggested line: ./b4w.ps1 -s xsql-eval htmlsnapshot get attr "img[src]" src --limit 20  # image URLs
3) Compare with the correct form: ./b4w.ps1 -s xsql-eval htmlsnapshot get all attr "img[src]" src --limit 20

#### Issue 2: Unrecognized/misplaced flags are silently ignored by htmlsnapshot get / get all (e.g. --bogus)

./b4w.ps1 -s xsql-eval htmlsnapshot get text "div.product-title" --bogus 1   → prints '4K OLED TV 55', exit 0
./b4w.ps1 -s xsql-eval htmlsnapshot get all text "div.product-title" --bogus 1   → prints all 6 titles, exit 0
./b4w.ps1 -s xsql-eval htmlsnapshot get text "div.product-title" --limit 20   → prints 1 title, exit 0 (flag ignored)

#### Issue 3: X-SQL reference says 'no joins' but a JOIN of two DOM_LOAD_AND_SELECT sources executes successfully

Write probe-join.sql:
SELECT COUNT(*) AS n FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card') a JOIN DOM_LOAD_AND_SELECT(@url, 'div.product-card') b ON 1=1
Run: ./b4w.ps1 -s xsql-eval htmlsnapshot query --sql "@…/probe-join.sql" --format table

#### Issue 4: Rejected non-SELECT X-SQL (CTE) returns HTTP 500 instead of the documented 400/417

Write probe-cte.sql:
WITH cards AS (SELECT DOM_FIRST_TEXT(DOM, '.product-title') AS t FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card'))
SELECT t FROM cards LIMIT 3
Run: ./b4w.ps1 -s xsql-eval htmlsnapshot query --sql "@…/probe-cte.sql" --format table

#### Issue 5: Capture metadata's `w=` (visual weight) value is undocumented

./b4w.ps1 -s xsql-eval htmlsnapshot   → interactive element lines like:
  1. button.shop-btn  "SHOP NOW →"  [box=286 248 123 35; w=1004305]
Then check ./b4w.ps1 --help htmlsnapshot — no mention of `w=`.

#### Issue 6: Numeric X-SQL results serialize as JSON strings in --result-only output

./b4w.ps1 -s xsql-eval htmlsnapshot query --sql "@…/query-final.sql" --result-only  → {"title": "4K OLED TV 55", "price": "899.99", "rating": "4.6", …}

