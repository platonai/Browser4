# Issues: x-sql-extraction-functions

> **Source:** `20260916-211505-x-sql-extraction-functions.full.md` | **Date:** 20260916-211505 | **Mode:** dev

## Scenario Background

### Task

I navigated to the MockSite Electronics category, captured an HTML snapshot, discovered selectors with `htmlsnapshot inspect`, and built an X-SQL query exercising every requested function family. The final query returns 5 of 6 products (the $24.99 mouse is correctly excluded by the `WHERE` filter), sorted by price descending, limited to 5 rows. I verified every extracted value against the exported source HTML — titles, prices, badges, links, and image URLs all match exactly.

**Final query** (`.test-sessions/20260916T1722282030412Z/final-query.sql`):

```sql
SELECT
  ARRAY_FIRST_NOT_BLANK(MAKE_ARRAY(
      STR_TRIM(DOM_FIRST_TEXT(DOM, 'div.product-title')),
      DOM_FIRST_ATTR(DOM, 'img.product-img', 'alt'),
      DOM_FIRST_ATTR(DOM, 'a.product-link', 'title'),
      'Unknown product'
  ))                                                                     AS product,
  STR_ABBREVIATE(STR_TRIM(DOM_FIRST_TEXT(DOM, 'div.product-title')), 20) AS short_title,
  STR_UPPER_CASE(STR_DEFAULT_IF_BLANK(STR_TRIM(DOM_FIRST_TEXT(DOM, 'span.badge')), 'standard')) AS badge,
  DOM_FIRST_FLOAT(DOM, 'div.product-price', 0.0)                         AS price_dom,
  STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, 'div.product-price'), 0.0)         AS price_str,
  STR_DEFAULT_IF_BLANK(DOM_FIRST_ATTR(DOM, 'div.product-rating', 'data-rating'), 'n/a') AS rating_attr,
  DOM_FIRST_ATTR(DOM, 'div.product-card', 'data-category-id')            AS category_attr,
  DOM_FIRST_HREF(DOM, 'a.product-link')                                  AS detail_link,
  DOM_FIRST_IMG(DOM, 'img.product-img')                                  AS image_url
FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card:expr(width > 150 && img > 0)')
WHERE STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, 'div.product-price'), 0.0) >= 25.0
ORDER BY STR_FIRST_FLOAT(DOM_FIRST_TEXT(DOM, 'div.product-price'), 0.0) DESC
LIMIT 5
```

| product | short_title | badge | price | rating | link |
|---|---|---|---|---|---|
| 4K OLED TV 55 | 4K OLED TV 55 | BESTSELLER | 899.99 | 4.6 | /ec/dp/B0E000001 |
| Smartphone 128GB | Smartphone 128GB | HOT | 599.0 | 4.5 | /ec/dp/B0E000004 |
| Wireless Noise-Cancelling Headphones | Wireless Noise-Ca... | BESTSELLER | 199.99 | 4.4 | /ec/dp/B0E000002 |
| Portable Bluetooth Speaker | Portable Bluetoot... | STANDARD | 49.99 | 4.3 | /ec/dp/B0E000003 |
| USB-C Hub 7-in-1 | USB-C Hub 7-in-1 | STANDARD | 29.95 | 4.2 | /ec/dp/B0E000005 |

### Execution Context

**Key Commands:**

**Approach.** I read `SKILL.md` and the X-SQL references first, then worked incrementally: base DOM functions → probe row-root attributes and `:expr()` → add STR functions, fallback chains, `WHERE`/`ORDER BY`/`LIMIT`. I exported the snapshot HTML and used it as ground truth to verify results rather than trusting the output.

**Decisive experiments** (each designed to separate two hypotheses):
- **`:expr()` real or ignored?** `width > 200` → 0 rows, `width > 150` → 6 rows. It genuinely filters.
- **Is `:expr` honored inside `DOM_FIRST_IMG`?** `img:expr(width > 99999)` → blank, `img:expr(width > 150)` → URL. It *is* honored — contradicting both the docs and the CLI's own warning.
- **Are UDF selectors row-scoped or document-scoped?** `#search-box`/`h3` (document-level) returned blank per row → correctly scoped. `DOM_FIRST_ATTR(DOM, 'div.product-card', 'id')` returned *per-row* ids → matching is **self-inclusive** (the row root matches its own selector), which is undocumented.
- **Warning stream:** redirecting stdout and stderr separately proved the advisory is on **stdout**, not stderr.
- **Numeric fidelity:** `STR_FIRST_FLOAT(...)+1` → 900.99, so values are真 numeric in SQL; the JSON envelope stringifies them.

**Workarounds needed:** none for the task itself. I re-checked three documented pitfalls empirically because they contradicted observed behavior; two were stale in this build.

---

## Issues Found (8 issues)

### Issue 1: Docs claim the 2-argument DOM_FIRST_FLOAT form is invalid, but it works

**Severity:** Medium
**Category:** Documentation

#### Reproduction

echo "SELECT DOM_FIRST_FLOAT(DOM, 'div.product-price') AS price FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card')" > q.sql
./b4w.ps1 htmlsnapshot query --sql @q.sql --format table

#### Expected Behavior

Per SKILL.md §4e and references/x-sql.md, the 2-arg call is unregistered and should fail with HTTP 417: 'Method "DOMFIRSTFLOAT ..." parameter count: 2 not found'. The docs also instruct users to 'always pass the default argument explicitly'.

#### Actual Behavior

The 2-argument form succeeded, returning all six prices (899.99, 199.99, 49.99, 599.0, 29.95, 24.99) with exit code 0. No error, no warning.

#### Root Cause Analysis

The registered UDF signature set has diverged from what the documentation describes. Either the default argument was made optional in a later build without updating the docs, or an overload was added. The docs' 'always pass the default' rule and the whole 417 failure mode are therefore stale for this build. Needs a check of the UDF registration site (the class registering DOM_FIRST_FLOAT / DOM_NTH_FLOAT / DOM_FIRST_INTEGER) to see which arities are actually exposed.

#### Code Pointer

`browser4-core/.../x-sql/DomSelectFunctions.kt (UDF registration for DOM_FIRST_FLOAT / DOM_NTH_FLOAT / DOM_FIRST_INTEGER); docs at skills/browser4-cli/references/x-sql.md and skills/browser4-cli/SKILL.md §4e`

#### AI Suggested Improvement

- Re-derive the documented arity table directly from the UDF registration code and regenerate the note in x-sql.md and the SKILL.md pitfall table
- If the 2-arg form is intended to be supported, document it as supported rather than as a failure mode
- If it is intended to be unsupported, make the registration enforce the 3-arg call so docs and behavior agree
- Add a regression test that asserts the documented arity contract, so doc drift is caught automatically

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Docs and CLI warning claim DOM_FIRST_IMG ignores PowerCSS :expr(), but it honors it

**Severity:** Medium
**Category:** Documentation

#### Reproduction

echo "SELECT DOM_FIRST_IMG(DOM, 'img:expr(width > 99999)') AS impossible, DOM_FIRST_IMG(DOM, 'img:expr(width > 150)') AS wide_ok FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card')" > q.sql
./b4w.ps1 htmlsnapshot query --sql @q.sql --format table

#### Expected Behavior

Per SKILL.md §4e ('the img-scanning path ignores :expr and matches nothing'), references/x-sql.md, and references/power-dom.md, both columns should be empty because :expr is silently dropped by DOM_FIRST_IMG.

#### Actual Behavior

The two columns differ: the impossible filter (width > 99999) returned empty while the satisfiable one (width > 150) returned the image URL. A filter that changes the result based on its predicate is being evaluated, not ignored. The CLI nevertheless printed a stderr/stdout advisory insisting DOM_FIRST_IMG does not evaluate :expr().

#### Root Cause Analysis

The limitation was fixed in the image-scanning path but the documentation and the static advisory were not updated. The advisory appears to be emitted from a purely syntactic check (selector contains ':expr' AND function is DOM_FIRST_IMG) with no runtime confirmation of whether a match was actually produced.

#### Code Pointer

`The CLI site emitting the 'DOM_FIRST_IMG does not evaluate PowerCSS :expr(...)' advisory (cli/browser4-cli/src/, string literal 'does not evaluate PowerCSS'); docs in skills/browser4-cli/references/x-sql.md, power-dom.md, SKILL.md §4e/§PowerCSS`

#### AI Suggested Improvement

- Confirm the fix in the image-scanning code path, then delete or correct the stale limitation from x-sql.md, power-dom.md and SKILL.md §4e
- Gate the runtime advisory on an observed empty result for a filtered selector instead of on selector syntax alone, so it cannot fire on a successful query
- Add a test asserting that DOM_FIRST_IMG honors a satisfiable :expr and rejects an unsatisfiable one

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: X-SQL advisory is printed to stdout (not stderr) and pollutes --format csv output

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 htmlsnapshot query --sql @q-imgprobe.sql --format csv > out.csv 2> err.txt
grep -c 'does not evaluate PowerCSS' out.csv   # 1
grep -c 'does not evaluate PowerCSS' err.txt   # 0

#### Expected Behavior

Diagnostics and advisories belong on stderr so stdout stays parseable. The documented machine-readable formats (csv/json) should contain data only.

#### Actual Behavior

The advisory is written to stdout. Redirecting stdout and stderr to separate files confirms stdout=1 occurrence, stderr=0. It also appears in --format csv output, where it is an extra line a CSV consumer must skip. Only --json suppresses it.

#### Root Cause Analysis

The advisory is emitted with a plain stdout print rather than a stderr writer, unlike the tips/hints path which is correctly gated on --show-tip and suppressed by --json. A query that succeeds therefore still contaminates piped stdout.

#### Code Pointer

`The CLI advisory emission site for X-SQL results (the same site as the PowerCSS :expr message); compare with the tip/hint printer which honours --show-tip and --json`

#### AI Suggested Improvement

- Route the advisory to stderr (eprintln!) so stdout carries only the data payload or the human-readable result
- Suppress advisories entirely in --format csv/json/--result-only modes, matching how --json already suppresses tips
- Add a test that asserts stdout of --format csv parses cleanly with a strict CSV reader

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: --format csv is not valid CSV: a human-readable footer is appended to stdout

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 htmlsnapshot query --sql @final-query.sql --format csv > out.csv
python -c "import csv; rows=list(csv.reader(open('out.csv'))); print(len(rows)); print(rows[-1])"

#### Expected Behavior

CSV output is parseable as CSV: a header row plus one row per record. Any status/footer text goes to stderr, or is omitted for machine formats.

#### Actual Behavior

A strict csv.reader parses 8 rows from a 5-record result: the 6 data rows, a blank row, and a final row containing the single field '5 rows returned.'. Consumers therefore ingest a spurious record or crash on a field-count mismatch.

#### Root Cause Analysis

The human-readable summary line ('N rows returned.') is emitted by the same rendering path for table and csv formats, with no format-specific suppression. CSV is advertised as a machine format in the help text ('Output format: json, csv, or table') but does not behave like one.

#### Code Pointer

`cli/browser4-cli/src/ — the htmlsnapshot query output renderer that appends the 'N rows returned.' footer (shared by --format table and csv)`

#### AI Suggested Improvement

- Suppress the row-count footer when --format csv (and json) is selected, or send it to stderr
- Document explicitly which --format values are machine-clean; currently only --json/--result-only are
- Add a test feeding --format csv output through a strict CSV parser

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: No way to discover available X-SQL functions; unknown-function errors give raw H2 codes with no suggestion

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

echo "SELECT DOM_FIRST_TEXTT(DOM, 'div.product-title') AS t FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card')" > q.sql
./b4w.ps1 htmlsnapshot query --sql @q.sql

#### Expected Behavior

A typo in a ~200-function library should either suggest the closest match ('did you mean DOM_FIRST_TEXT?') or point to a command that lists valid functions. The friendly error enrichment that other X-SQL failures receive should apply here too.

#### Actual Behavior

Output is a raw response envelope with message 'Function "DOM_FIRST_TEXTT" not found; SQL statement: ... [90022-197]' and a one-line 'Error: X-SQL query failed (417 Expectation Failed)'. No suggestion, no function index, and no '- Fix:' hint. This contrasts with the double-quoted-selector and WHERE-cast failures, which both get a '### X-SQL Query Failed' block with a '- Fix:' bullet containing corrected SQL.

#### Root Cause Analysis

Two gaps. (1) There is no CLI command that enumerates X-SQL functions, so the only discovery path is reading the markdown references offline. (2) The error-enrichment layer pattern-matches on a small set of known H2 error messages ('Column ... not found', 'Hexadecimal string ...') and has no case for 'Function ... not found', so the most common beginner error (typo) falls through to the un-enriched path.

#### Code Pointer

`CLI X-SQL error enrichment (the match arms producing the '### X-SQL Query Failed' block and '- Fix:' hints, alongside the handlers for 'Column ... not found' and 'Hexadecimal string contains non-hex character')`

#### AI Suggested Improvement

- Add a 'Function "X" not found' arm to the error enricher that suggests near-miss names (edit distance) from the registered UDF list
- Expose the registered function list to users, e.g. an `htmlsnapshot functions [--namespace DOM|STR|ARRAY]` command backed by the same registry the UDFs are registered in, so it cannot drift from the source of truth
- Include a link to references/x-sql.md in the unknown-function error message
- Add a test asserting every H2 error the engine can emit for a simple SELECT has either an enrichment arm or a deliberate fallback

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Numeric columns are serialized as JSON strings in --result-only/--json output

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 htmlsnapshot query --sql @.test-sessions/20260916T1722282030412Z/q-final.sql --result-only

#### Expected Behavior

STR_FIRST_FLOAT is documented as returning Float and DOM_FIRST_FLOAT as ValueFloat, so a JSON consumer should receive numbers (899.99) and be able to compare or sum them without conversion.

#### Actual Behavior

The resultSet contains "price_dom": "899.99" and "price_str": "899.99" — JSON strings, not numbers. The values are genuinely numeric in SQL (STR_FIRST_FLOAT(...)+1 returned 900.99 and *2 returned 1799.98), so the type is lost during envelope serialization, not at the SQL layer.

#### Root Cause Analysis

The scrape response envelope stringifies every resultSet cell when building the JSON payload, discarding the H2 column type. Consumers must re-parse numbers client-side, and a downstream sort/sum written against the documented Float type will silently do string comparison.

#### Code Pointer

`The X-SQL result-set serialization path that builds the response envelope resultSet (server side, near the SQLTemplate / scrape result envelope construction)`

#### AI Suggested Improvement

- Preserve numeric and boolean column types when serializing the resultSet instead of coercing every cell to a string
- If stringification is intentional for backward compatibility, document it explicitly in the --result-only/--json help text and X-SQL reference
- Add a test asserting the JSON type of a Float-returning column

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: :scope in a DOM_* UDF selector silently returns an empty string

**Severity:** Low
**Category:** Reliability

#### Reproduction

echo "SELECT DOM_FIRST_ATTR(DOM, ':scope', 'id') AS scope_id, DOM_ATTR(DOM, 'id') AS vdom_id FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card')" > q.sql
./b4w.ps1 htmlsnapshot query --sql @q.sql --format table

#### Expected Behavior

Either :scope resolves to the row's context element (standard CSS semantics) and returns the per-row id, or the unsupported pseudo-selector produces a diagnosable error. An empty result should never be indistinguishable from a genuinely absent attribute.

#### Actual Behavior

scope_id is empty for all six rows while the equivalent DOM_ATTR(DOM, 'id') returns product-B0E000001..6. No error and no warning. Separately, a plain selector does resolve the row root because matching is self-inclusive: DOM_FIRST_ATTR(DOM, 'div.product-card', 'id') correctly returned per-row ids.

#### Root Cause Analysis

The selector engine used inside DOM_* UDF arguments does not implement :scope, and an unmatched/unsupported selector degrades to an empty string rather than signalling failure — the same silent-empty pattern the docs warn about for DOM_FIRST_HREF with class-only selectors. Because matching is undocumented and self-inclusive, users who follow the CSS standard and reach for :scope get silence instead of the row root.

#### Code Pointer

`The selector-resolution helper backing DOM_FIRST_ATTR / DOM_FIRST_TEXT (the scoped matcher that applies a selector relative to the ValueDom context node)`

#### AI Suggested Improvement

- Support :scope as an alias for the context element, since plain selectors already match it via self-inclusion
- Or at minimum emit a warning when a selector contains an unsupported pseudo-class, rather than returning an empty string
- Document the self-inclusive matching rule (a row-root selector matches the row itself) in references/x-sql.md, and state which selectors address the row root

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Conflicting wrapper guidance for Git Bash users; documented CWD-reset risk did not reproduce

**Severity:** Low
**Category:** UX

#### Reproduction

Read skills/browser4-cli/SKILL.md §Invocation (table lists ./b4w.sh for Git Bash) and compare with the header comment of b4w.sh, then run ~20 commands as ./b4w.ps1 <command> from Git Bash.

#### Expected Behavior

A first-time user in Git Bash should be told unambiguously which wrapper to use, and any warning about a broken invocation should be accurate so it can be relied on.

#### Actual Behavior

SKILL.md's table maps Git Bash to ./b4w.sh and lists ./b4w.ps1 only for PowerShell, while b4w.sh's header states that running ./b4w.ps1 directly from Git Bash 'may cause the shell working directory to reset to the user home directory after each command'. In this session ./b4w.ps1 from Git Bash worked correctly for every command (goto, htmlsnapshot, inspect, query, export) with relative paths resolving against the repo root and no CWD reset observed. The task instructions for this evaluation in fact mandate ./b4w.ps1.

#### Root Cause Analysis

The Git Bash entry in SKILL.md omits the direct ./b4w.ps1 form that the wrapper scripts themselves support, and the CWD-reset warning (a real historical issue with pwsh -Command, which b4w.sh was written to avoid) is now inaccurate for the direct invocation. The one hazard that does remain with direct invocation — MSYS2 rewriting leading-slash arguments — is not documented in the table at all, though the CLI detects it and refuses to run with a clear message.

#### Code Pointer

`skills/browser4-cli/SKILL.md §Invocation table; b4w.sh header comment; b4w.ps1 (arg passing)`

#### AI Suggested Improvement

- Add the direct ./b4w.ps1 form to the Git Bash row of the SKILL.md invocation table and note that it works, so users are not told to avoid a working invocation
- Update or qualify the CWD-reset warning in b4w.sh's header, marking it as historical / specific to windows other than the current implementation
- Document the MSYS2 leading-slash rewriting hazard and the CLI's guard message in the same table, since that is the real trap for Git Bash users passing paths or URL substrings starting with '/'

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

**Completion Status:** Successful — all 9 task steps completed. Navigation, HTML capture, selector discovery, and the full X-SQL extraction pipeline (DOM functions, STR transforms, ARRAY fallback chains, PowerCSS :expr, WHERE/ORDER BY/LIMIT) all worked. Every extracted value was cross-checked against the exported source HTML and matched exactly; the WHERE filter correctly excluded the $24.99 item and ORDER BY DESC plus LIMIT 5 behaved as specified.

**Success Rate:** 95% — every task step succeeded on the first or second attempt. The only friction was documentation that contradicted observed behavior (two stale pitfall notes and one wrong runtime advisory), which I had to resolve empirically rather than by reading.

**Issues Found:** 8

**Most Confusing Aspects:** The X-SQL documentation actively misdescribes its own engine in three places, and the CLI repeats one of those misdescriptions as a runtime warning. A new user is told (a) that a working 2-argument DOM_FIRST_FLOAT form will fail, (b) that DOM_FIRST_IMG ignores PowerCSS :expr filters when it demonstrably honors them, and (c) they get that incorrect advisory printed on stdout even for a query that returned correct data. Because the docs are also the only way to discover the ~200 X-SQL functions, a user cannot easily tell which documented constraint is real. Secondary confusion: addressing the row-root element has no documented idiom — :scope silently returns empty while plain selectors match the root via undocumented self-inclusive matching.

**Most Valuable Improvements:** 1) Make X-SQL output hygiene reliable: diagnostics and row-count footers must go to stderr so --format csv is parseable CSV and stdout is safe to pipe, and --json should not double-encode the result payload as a nested string. 2) Regenerate the documented X-SQL function/pitfall contracts from the UDF registration code so arity rules and PowerCSS limitations cannot drift, and gate runtime advisories on observed behavior rather than selector syntax. 3) Make unknown-function errors actionable with a nearest-name suggestion plus a command that lists registered functions, since typos in a 200-function library are the most likely beginner failure. 4) Preserve numeric JSON types in the resultSet so machine consumers get what the docs promise.

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

#### Issue 1: Docs claim the 2-argument DOM_FIRST_FLOAT form is invalid, but it works

echo "SELECT DOM_FIRST_FLOAT(DOM, 'div.product-price') AS price FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card')" > q.sql
./b4w.ps1 htmlsnapshot query --sql @q.sql --format table

#### Issue 2: Docs and CLI warning claim DOM_FIRST_IMG ignores PowerCSS :expr(), but it honors it

echo "SELECT DOM_FIRST_IMG(DOM, 'img:expr(width > 99999)') AS impossible, DOM_FIRST_IMG(DOM, 'img:expr(width > 150)') AS wide_ok FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card')" > q.sql
./b4w.ps1 htmlsnapshot query --sql @q.sql --format table

#### Issue 3: X-SQL advisory is printed to stdout (not stderr) and pollutes --format csv output

./b4w.ps1 htmlsnapshot query --sql @q-imgprobe.sql --format csv > out.csv 2> err.txt
grep -c 'does not evaluate PowerCSS' out.csv   # 1
grep -c 'does not evaluate PowerCSS' err.txt   # 0

#### Issue 4: --format csv is not valid CSV: a human-readable footer is appended to stdout

./b4w.ps1 htmlsnapshot query --sql @final-query.sql --format csv > out.csv
python -c "import csv; rows=list(csv.reader(open('out.csv'))); print(len(rows)); print(rows[-1])"

#### Issue 5: No way to discover available X-SQL functions; unknown-function errors give raw H2 codes with no suggestion

echo "SELECT DOM_FIRST_TEXTT(DOM, 'div.product-title') AS t FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card')" > q.sql
./b4w.ps1 htmlsnapshot query --sql @q.sql

#### Issue 6: Numeric columns are serialized as JSON strings in --result-only/--json output

./b4w.ps1 htmlsnapshot query --sql @.test-sessions/20260916T1722282030412Z/q-final.sql --result-only

#### Issue 7: :scope in a DOM_* UDF selector silently returns an empty string

echo "SELECT DOM_FIRST_ATTR(DOM, ':scope', 'id') AS scope_id, DOM_ATTR(DOM, 'id') AS vdom_id FROM DOM_LOAD_AND_SELECT(@url, 'div.product-card')" > q.sql
./b4w.ps1 htmlsnapshot query --sql @q.sql --format table

#### Issue 8: Conflicting wrapper guidance for Git Bash users; documented CWD-reset risk did not reproduce

Read skills/browser4-cli/SKILL.md §Invocation (table lists ./b4w.sh for Git Bash) and compare with the header comment of b4w.sh, then run ~20 commands as ./b4w.ps1 <command> from Git Bash.

