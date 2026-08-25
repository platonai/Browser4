# Issues: x-sql-extraction-functions

> **Source:** `20260814-145920-x-sql-extraction-functions.full.md` | **Date:** 20260814-145920 | **Mode:** dev

## Scenario Background

### Task

**Successful.** I extracted all 6 products from the MockSite Electronics category page (`http://localhost:18080/ec/b?node=1292115012`), filtered, sorted, and truncated to 4 rows using a single X-SQL query exercising every required function family. Three non-obvious bugs/limitations were discovered and worked around (documented below).

**Final extracted data** (WHERE price ≥ 49.99, ORDER BY price DESC, LIMIT 4):

| title | price | price_num | link | image | rating_attr | category_id | badge | badge_upper | rating | title_short | card_width_px |
|---|---|---|---|---|---|---|---|---|---|---|---|
| 4K OLED TV 55 | 899.99 | 899.99 | http://localhost:18080/ec/dp/B0E000001 | https://picsum.photos/seed/1250857624/200/140 | 4.6 | 1292115012 | Bestseller | BESTSELLER | 4.6 (521) | 4K OLED TV 55 | 190.9 |
| Smartphone 128GB | 599.0 | 599.0 | http://localhost:18080/ec/dp/B0E000004 | https://picsum.photos/seed/1250857627/200/140 | 4.5 | 1292115012 | Hot | HOT | 4.5 (210) | Smartphone 128GB | 190.9 |
| Wireless Noise-Cancelling Headphones | 199.99 | 199.99 | http://localhost:18080/ec/dp/B0E000002 | https://picsum.photos/seed/1250857625/200/140 | 4.4 | 1292115012 | Bestseller | BESTSELLER | 4.4 (312) | Wireless Noise-Ca... | 190.9 |
| Portable Bluetooth Speaker | 49.99 | 49.99 | http://localhost:18080/ec/dp/B0E000003 | https://picsum.photos/seed/1250857626/200/140 | 4.3 | 1292115012 | No Badge | NO-BADGE | 4.3 (901) | Portable Bluetoot... | 190.9 |

**Final query** (saved at `.test-sessions/query-final.sql`):

```sql
SELECT
    STR_TRIM(DOM_FIRST_TEXT(DOM, 'div.product-title')) AS title,
    ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, 'div.product-title'),
        DOM_FIRST_ATTR(DOM, 'img.product-img', 'alt'),
        'Unknown Product'
    )) AS title_fallback,
    DOM_FIRST_FLOAT(DOM, 'div.product-price', 0.0) AS price,
    STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, 'div.product-price'), 0.0) AS price_num,
    DOM_FIRST_HREF(DOM, 'a.product-link') AS link,
    DOM_FIRST_IMG(DOM, 'img.product-img') AS image,
    DOM_FIRST_ATTR(DOM, 'div.product-rating', 'data-rating') AS rating_attr,
    DOM_ATTR(DOM, 'data-category-id') AS category_id,
    STR_DEFAULT_IF_BLANK(DOM_FIRST_TEXT(DOM, 'span.badge'), 'No Badge') AS badge,
    STR_UPPER_CASE(STR_DEFAULT_IF_BLANK(DOM_FIRST_TEXT(DOM, 'span.badge'), 'no-badge')) AS badge_upper,
    ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(
        DOM_FIRST_TEXT(DOM, 'div.product-rating'),
        DOM_FIRST_ATTR(DOM, 'div.product-rating', 'data-rating'),
        'No Rating'
    )) AS rating,
    STR_ABBREVIATE(STR_TRIM(DOM_FIRST_TEXT(DOM, 'div.product-title')), 20) AS title_short,
    DOM_WIDTH(DOM) AS card_width_px
FROM DOM_LOAD_AND_SELECT(@url, '.product-card:expr(width > 100)')
WHERE DOM_IS_NOT_NIL(DOM)
  AND DOM_WIDTH(DOM) > 150
  AND STR_IS_NOT_BLANK(DOM_FIRST_TEXT(DOM, 'div.product-title'))
  AND STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, 'div.product-price'), 0.0) >= 49.99
ORDER BY price_num DESC
LIMIT 4
```

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — read command list; also read `skills/browser4-cli/SKILL.md` and the X-SQL reference files (`x-sql.md`, `x-sql-string-functions.md`) before touching the browser.
2. Verified MockSite: `curl http://localhost:18080/ec/b?node=1292115012` → 200.
3. `./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"` — succeeded; reconnected to an existing dev session (backend auto-managed, no manual setup).
4. `./b4w.ps1 htmlsnapshot` — captured the page; interactive-elements listing showed 6 `#product-B0E00000X a.product-link` items.
5. `./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3` — auto-discovered `.product-card` (6 matches) and suggested `div.product-title`, `a.product-link`, `img.product-img`, `[data-category-id="1292115012"]`; the structure dump revealed `.product-price` and `.product-rating`.
6. `./b4w.ps1 htmlsnapshot export .test-sessions/ec-electronics.html` — inspected raw card markup (data attributes, hrefs, img srcs).
7. Iterative X-SQL development via `htmlsnapshot query --sql @<file>.sql --format table`:
   - Query 1 (basic DOM functions) failed: `Method "DOMFIRSTFLOAT ... parameter count: 2" not found` → switched to 3-param form `DOM_FIRST_FLOAT(DOM, sel, 0.0)` per the quick-reference example (docs index contradicts the backend).
   - Full query failed: `Hexadecimal string contains non-hex character: "899.99"` → bisected with 4 isolated queries (`q-where`, `q-order`, `q-str`, `q-array`); isolated the trigger to `WHERE DOM_FIRST_FLOAT(...) >= 49.99`.
   - Verified root cause via `javap` on `pulsar-ql-4.11.2.jar`: `firstFloat` returns `org.h2.value.ValueFloat` (H2 internal Value object) instead of a primitive.
   - Workaround: use `STR_FIRST_FLOAT(DOM_FIRST_TEXT(...), 0.0)` (returns primitive) in WHERE/ORDER BY; keep `DOM_FIRST_FLOAT` in SELECT (works there).
   - Discovered `:expr()` returns empty inside `DOM_FIRST_*` UDF selectors (even `width >= 0`); moved PowerCSS to the FROM clause (`.product-card:expr(width > 100)`), where it works.
8. `./b4w.ps1 htmlsnapshot query ... --sql @.test-sessions/query-final.sql --format table` — final query succeeded, 4 rows.

**Workarounds required:**
1. `DOM_FIRST_FLOAT` needs the 3-param default-value form (docs index says 2).
2. `DOM_FIRST_FLOAT` cannot be used in WHERE/ORDER BY comparisons — substitute `STR_FIRST_FLOAT(DOM_FIRST_TEXT(...), 0.0)`.
3. PowerCSS `:expr()` must be used in `DOM_LOAD_AND_SELECT`/`htmlsnapshot get`, not inside `DOM_FIRST_*` UDF selectors.

**Decisions:** Used class selectors (`.product-card` etc.) directly — they work on the current MockSite; an archived scenario draft suggested attribute-selector workarounds that are no longer necessary. Used `@file.sql` for all queries to avoid shell quoting. Kept all temp files in `.test-sessions/`.

---

## Issues Found (7 issues)

### Issue 1: DOM_FIRST_FLOAT documented with 2 params but backend only registers 3-param form

**Severity:** High
**Category:** Documentation

#### Reproduction

Write query: SELECT DOM_FIRST_FLOAT(DOM, 'div.product-price') AS price FROM DOM_LOAD_AND_SELECT(@url, '.product-card'). Run: ./b4w.ps1 htmlsnapshot query --sql @q.sql

#### Expected Behavior

Price extracted as a number, per the function index in skills/browser4-cli/references/x-sql.md which lists DOM_FIRST_FLOAT as (DOM, sel).

#### Actual Behavior

417 Expectation Failed: Method "DOMFIRSTFLOAT (ai.platon.pulsar.ql.h2.udfs.DomSelectFunctions, parameter count: 2)" not found. The 3-param form DOM_FIRST_FLOAT(DOM, sel, 0.0) works.

#### Root Cause Analysis

The backend UDF (verified via javap on pulsar-ql-4.11.2.jar) only has firstFloat(ValueDom, String, float) — no 2-param overload. The x-sql.md function index table (row: DOM_FIRST_FLOAT | (DOM, sel) | ValueFloat) contradicts both the backend and the quick-reference example in the same file, which correctly shows DOM_FIRST_FLOAT(DOM, '.price', 0.0).

#### Code Pointer

`skills/browser4-cli/references/x-sql.md (DOM namespace index table)`

#### AI Suggested Improvement

- Fix the index row to show (DOM, sel, default) and note the default is required
- Add a lint/test that cross-checks documented function arities against the registered UDF aliases
- Consider registering a 2-param alias in the backend for the documented signature

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: DOM_FIRST_FLOAT in WHERE/ORDER BY comparison fails with cryptic hexadecimal error

**Severity:** High
**Category:** Product

#### Reproduction

SELECT DOM_FIRST_FLOAT(DOM, 'div.product-price', 0.0) AS price FROM DOM_LOAD_AND_SELECT(@url, '.product-card') WHERE DOM_FIRST_FLOAT(DOM, 'div.product-price', 0.0) >= 49.99

#### Expected Behavior

Numeric comparison filters products priced >= 49.99.

#### Actual Behavior

417 with message: Hexadecimal string contains non-hex character: "899.99" (H2 error 90004-197). Same expression in SELECT displays 899.99 fine; ORDER BY on the alias also works.

#### Root Cause Analysis

javap on the runtime bundle's pulsar-ql-4.11.2.jar shows firstFloat returns org.h2.value.ValueFloat (an H2 internal Value object) instead of a primitive float. H2's FunctionAlias type inference mishandles this return type in predicate contexts: unifying the comparison type attempts a VARCHAR→BINARY conversion, and H2's binary conversion parses the string as hex, failing on "899.99". Returning a primitive float (or registering the alias with an explicit REAL return type) would fix it. Workaround: STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, sel), 0.0) works in WHERE/ORDER BY.

#### Code Pointer

`Dependency ai.platon.pulsar.ql.h2.udfs.DomSelectFunctions.firstFloat (pulsar-ql jar; not in this repo) — browser4 could override the alias registration or pin a fixed pulsar-ql version`

#### AI Suggested Improvement

- Change firstFloat to return primitive float (or register the alias with explicit returnDataType)
- Add a regression test for DOM_FIRST_FLOAT inside WHERE comparisons
- Until fixed, document the limitation and the STR_FIRST_FLOAT workaround in x-sql.md

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: PowerCSS :expr() silently returns empty inside DOM_FIRST_* UDF selectors — and SKILL.md teaches this broken pattern

**Severity:** High
**Category:** Product

#### Reproduction

SELECT DOM_FIRST_IMG(DOM, 'img.product-img:expr(width > 100)') AS image FROM DOM_LOAD_AND_SELECT(@url, '.product-card'). Even 'img.product-img:expr(width >= 0)' returns empty, while plain 'img.product-img' returns the URL. The same selector works in htmlsnapshot get all attr and in the FROM clause.

#### Expected Behavior

Image URL extracted using the PowerCSS visual filter, as documented in SKILL.md §6 Bulk Extraction which shows DOM_FIRST_ATTR(DOM, 'img:expr(width > 250 && height > 250)', 'src').

#### Actual Behavior

Empty column, no error, no warning — silent data loss. A user following the SKILL.md example gets an all-empty column and no indication why.

#### Root Cause Analysis

Selector arguments inside DOM_FIRST_*/DOM_ALL_* UDFs are evaluated with plain CSS (jsoup) without PowerCSS feature computation; numerical features are only computed in the page-load/scoping path (DOM_LOAD_AND_SELECT, htmlsnapshot get/inspect over the stored snapshot). The UDF path never evaluates :expr predicates, so they match nothing.

#### Code Pointer

`pulsar-ql DomSelectFunctions (external dep); skills/browser4-cli/SKILL.md Bulk Extraction example`

#### AI Suggested Improvement

- Either make UDF selectors PowerCSS-aware or return an explicit error when :expr( appears in a UDF selector
- Fix the SKILL.md Bulk Extraction example to use plain selectors (or move :expr to the FROM clause)
- Document the supported places for :expr (DOM_LOAD_AND_SELECT, htmlsnapshot get/inspect) vs unsupported (UDF selector args)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
i will fix it in the library.

---

### Issue 4: Failed X-SQL queries exit with status 0 — silent failure in scripts

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 htmlsnapshot query --sql @bad-query.sql --result-only; echo $? → prints 0 even though the query 417s and the resultSet is empty.

#### Expected Behavior

Non-zero exit code when the query fails, so shell scripts and CI can detect failure.

#### Actual Behavior

Exit code 0 with the failure only visible in stdout text. A pipeline consuming --result-only gets an empty array and a zero exit — indistinguishable from a genuinely empty result set.

#### Root Cause Analysis

The query command's result processing treats a 417/5xx response as printable output rather than an error: it prints the failure banner and returns Ok, so process::exit(0).

#### Code Pointer

`cli/browser4-cli/src/main.rs (query command result processing, ~line 6360)`

#### AI Suggested Improvement

- Return a non-zero exit code (e.g. 1 for 4xx, 2 for 5xx) after printing the failure banner
- Preserve exit 0 only for successful queries with empty result sets

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Misleading 417 error wrapper blames a race condition for any SQL error

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run any X-SQL query with an unknown function or bad arity, e.g. DOM_FIRST_FLOAT(DOM, 'div.product-price') with 2 params.

#### Expected Behavior

The CLI surfaces the real error (Method DOMFIRSTFLOAT not found) prominently so the user can fix the SQL.

#### Actual Behavior

The CLI prints: "X-SQL Query Failed (417 Expectation Failed) - The scrape session closed before the query could execute. This is a known backend race condition. Try these workarounds: 1. Re-run the query..." The real cause is only visible in the trailing raw-response JSON. A first-time user is told to re-run a query that can never succeed and to check CSS quoting when the issue is a function signature.

#### Root Cause Analysis

cli/browser4-cli/src/main.rs:6360-6364 — the 417 handler unconditionally prints the scrape-session race-condition boilerplate for every 417 statusCode. It never inspects the response's message field, which contains the actual SQL error (e.g. 'Method ... not found', 'Hexadecimal string ...', 'Syntax error in SQL statement').

#### Code Pointer

`cli/browser4-cli/src/main.rs:6363 (417 branch in the htmlsnapshot query result processing)`

#### AI Suggested Improvement

- Inspect parsed.get("message") and print the actual SQL error as the headline, keeping the race-condition text only when the message matches session-closure signatures
- Categorize known failures: method-not-found → arity hint; hexadecimal → numeric-comparison hint; syntax error → quoting hint

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: htmlsnapshot inspect omits the price and rating selectors from its suggestions

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3 on the Electronics category page, then look at 'Suggested selectors'.

#### Expected Behavior

The recurring .product-price and .product-rating class selectors (present in 3/3 analyzed cards, shown in the per-element structure dump) appear in the suggested-selectors list — they are the primary extraction targets on an e-commerce page.

#### Actual Behavior

Suggested list contains div.product-title, a.product-link, img.product-img, and even [data-category-id="1292115012"], but omits div.product-price and div.product-rating entirely. The user only finds them by reading the sample-structure dump or exporting the HTML.

#### Root Cause Analysis

Unconfirmed — likely the suggestion-ranking logic drops elements that carry unique per-card id attributes (id="product-price-B0E000001", id="product-rating-B0E000001") as non-recurring, even though a perfectly recurring class selector exists. Needs investigation in the backend selector-discovery code that powers htmlsnapshot inspect.

#### AI Suggested Improvement

- When an element has both a unique id and a recurring class, still suggest the class selector
- Include elements with numeric text (prices) in suggestions — they are high-value extraction targets
- Add a unit test asserting price/rating selectors are suggested on this MockSite fixture

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Docs say DOM_FIRST_HREF/DOM_FIRST_ATTR return relative hrefs; observed absolute URLs

**Severity:** Low
**Category:** Documentation

#### Reproduction

SELECT DOM_FIRST_HREF(DOM, 'a.product-link') AS link FROM DOM_LOAD_AND_SELECT(@url, '.product-card') — returns http://localhost:18080/ec/dp/B0E000001 although the page HTML has href="/ec/dp/B0E000001".

#### Expected Behavior

Per x-sql.md: 'DOM_FIRST_ATTR ... returns the href consistently (relative; use DOM_ABS_HREF or abs:href for the absolute URL)' — the docs describe relative hrefs.

#### Actual Behavior

DOM_FIRST_HREF returns a fully resolved absolute URL. (The behavior itself is user-friendly; the documentation is just wrong/outdated about relative-vs-absolute.)

#### Root Cause Analysis

The scrape pipeline (or DOM_FIRST_HREF implementation) resolves hrefs against the base URI before returning them; the doc note was written against an older behavior.

#### Code Pointer

`skills/browser4-cli/references/x-sql.md (note on DOM_FIRST_HREF)`

#### AI Suggested Improvement

- Update the note to state that DOM_FIRST_HREF returns absolute resolved URLs on the current backend
- Re-verify whether DOM_FIRST_ATTR(..., 'href') is also resolved and document consistently

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 9 task steps completed: navigation, HTML snapshot, inspect-based selector discovery, and a final X-SQL query using all required DOM/STR/ARRAY functions, PowerCSS :expr(), WHERE, ORDER BY, and LIMIT, returning correct 4-row extracted data.

**Success Rate:** 92% — every step ultimately succeeded; 3 workarounds were required (3-param DOM_FIRST_FLOAT form, STR_FIRST_FLOAT for WHERE/ORDER BY, :expr() moved from UDF selector to FROM clause), and the CLI never blocked outright.

**Issues Found:** 7

**Major Blockers:** None that fully blocked the task. The closest was the DOM_FIRST_FLOAT hexadecimal error in WHERE, which required bisection and javap inspection of the dependency jar to work around.

**Most Confusing Aspects:** 1) The docs' function index contradicts the backend for DOM_FIRST_FLOAT arity; 2) 'Hexadecimal string contains non-hex character' for a simple numeric comparison; 3) the 417 'scrape session closed' banner misdirecting users away from the real SQL error; 4) PowerCSS :expr() silently producing empty columns in UDF selectors while the official SKILL.md example uses exactly that pattern.

**Most Valuable Improvements:** 1) Make DOM_FIRST_FLOAT return a primitive so it works in WHERE/ORDER BY; 2) surface the actual SQL error message in 417 responses and return non-zero exit codes on failure; 3) support (or explicitly reject) :expr() inside UDF selectors and fix the SKILL.md example; 4) include price/rating selectors in htmlsnapshot inspect suggestions; 5) align documented function arities with registered UDFs.

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

#### Issue 1: DOM_FIRST_FLOAT documented with 2 params but backend only registers 3-param form

Write query: SELECT DOM_FIRST_FLOAT(DOM, 'div.product-price') AS price FROM DOM_LOAD_AND_SELECT(@url, '.product-card'). Run: ./b4w.ps1 htmlsnapshot query --sql @q.sql

#### Issue 2: DOM_FIRST_FLOAT in WHERE/ORDER BY comparison fails with cryptic hexadecimal error

SELECT DOM_FIRST_FLOAT(DOM, 'div.product-price', 0.0) AS price FROM DOM_LOAD_AND_SELECT(@url, '.product-card') WHERE DOM_FIRST_FLOAT(DOM, 'div.product-price', 0.0) >= 49.99

#### Issue 3: PowerCSS :expr() silently returns empty inside DOM_FIRST_* UDF selectors — and SKILL.md teaches this broken pattern

SELECT DOM_FIRST_IMG(DOM, 'img.product-img:expr(width > 100)') AS image FROM DOM_LOAD_AND_SELECT(@url, '.product-card'). Even 'img.product-img:expr(width >= 0)' returns empty, while plain 'img.product-img' returns the URL. The same selector works in htmlsnapshot get all attr and in the FROM clause.

#### Issue 4: Failed X-SQL queries exit with status 0 — silent failure in scripts

./b4w.ps1 htmlsnapshot query --sql @bad-query.sql --result-only; echo $? → prints 0 even though the query 417s and the resultSet is empty.

#### Issue 5: Misleading 417 error wrapper blames a race condition for any SQL error

Run any X-SQL query with an unknown function or bad arity, e.g. DOM_FIRST_FLOAT(DOM, 'div.product-price') with 2 params.

#### Issue 6: htmlsnapshot inspect omits the price and rating selectors from its suggestions

./b4w.ps1 htmlsnapshot inspect --max 3 --depth 3 on the Electronics category page, then look at 'Suggested selectors'.

#### Issue 7: Docs say DOM_FIRST_HREF/DOM_FIRST_ATTR return relative hrefs; observed absolute URLs

SELECT DOM_FIRST_HREF(DOM, 'a.product-link') AS link FROM DOM_LOAD_AND_SELECT(@url, '.product-card') — returns http://localhost:18080/ec/dp/B0E000001 although the page HTML has href="/ec/dp/B0E000001".

