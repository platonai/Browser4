# Issues: htmlsnapshot-inspect-discovery

> **Source:** `20260728-155253-htmlsnapshot-inspect-discovery.full.md` | **Date:** 20260728-155253 | **Mode:** dev

## Scenario Background

### Task

Successfully completed all 9 task steps on `https://books.toscrape.com/`:

1. **Navigation** — `goto` loaded the page (redirected HTTP→HTTPS)
2. **HTML Snapshot** — 64KB capture with 20 images, 94 links, 100 interactive elements
3. **Inspect (no selector)** — Auto-discovered `.product_pod` repeating pattern (20 matches) with detailed suggested selectors including `p.price_color`, `h3`, `button.btn.btn-primary`
4. **Inspect (scoped)** — `ol.row` with `--max 5 --depth 3` discovered `li.col-xs-6.col-sm-4` repeating pattern with 5 analyzed of 20
5. **Summary** — WPSI compressed overview showing 23 landmarks, 4 link groups, 3 lists, 516 nodes
6. **Extract titles** — 20 book titles extracted via `htmlsnapshot get all text "article.product_pod h3 a"` (some truncated with "...")
7. **Grep validation** — `htmlsnapshot grep "price_color" --selector-all "article.product_pod" --count` confirmed 20 matches
8. **Correlated extraction** — 20 books with full titles + prices via `eval --file` (JavaScript). X-SQL `htmlsnapshot query` failed consistently with 417 (Expectation Failed); `swarm query` hung indefinitely. `eval` worked after re-navigation and ensuring the last line was a bare expression (not a statement).
9. **Sidebar exploration** — `.sidebar` inspect revealed 51 category `<li>` items; all 51 category names extracted

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| 1 | `goto "http://books.toscrape.com/"` | Page loaded (redirected to HTTPS) |
| 2 | `htmlsnapshot` | 64KB snapshot captured |
| 3 | `htmlsnapshot inspect` | Auto-discovered `.product_pod` (20 matches) |
| 4 | `htmlsnapshot inspect "ol.row" --max 5 --depth 3` | Discovered `li.col-xs-6.col-sm-4` pattern |
| 5 | `htmlsnapshot summary` | WPSI generated |
| 6 | `htmlsnapshot get all text "article.product_pod h3 a"` | 20 titles extracted (truncated) |
| 7 | `htmlsnapshot grep "price_color" --selector-all "article.product_pod" --count` | 20 confirmed |
| 8 | `eval --file .test-sessions/extract-books.js` | 20 books with full titles + prices |
| 9 | `htmlsnapshot inspect ".sidebar" --max 5 --depth 3` | 51 categories discovered |

**Workaroun...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)

### Issue 1: X-SQL htmlsnapshot query consistently fails with 417 Expectation Failed

**Severity:** Critical
**Category:** Reliability

#### Reproduction

echo 'SELECT DOM_FIRST_TEXT(DOM, \'h1\') AS title FROM DOM_LOAD_AND_SELECT(@url, \':root\')' | ./b4w.ps1 htmlsnapshot query --sql-stdin

Or: ./b4w.ps1 htmlsnapshot query --sql @query.sql (with single-quoted CSS selectors in the file)

#### Expected Behavior

The X-SQL query should execute successfully and return a resultSet with extracted data matching the CSS selectors.

#### Actual Behavior

Returns JSON with statusCode 417, empty resultSet, status 'Expectation Failed'. Backend log shows 'Session is already closed | #2/85' and 'Failed to execute scrape task #null'.

#### Root Cause Analysis

The scrape session used internally by the X-SQL query engine closes before the task can execute. This appears to be a race condition or session lifecycle bug in the backend — the session is being closed between when the task is created and when it attempts to execute. Additionally, H2 SQL interprets double-quoted strings as identifiers (not string literals), which causes 'Column not found' errors when users naturally use double quotes for CSS selectors in their SQL.

#### Code Pointer

`ai.platon.pulsar.agentic.context.sql.AbstractBrowser4SQLContext.kt:158 — getSession() throws 'object is already closed'`

#### AI Suggested Improvement

- Fix the session lifecycle race condition in AbstractBrowser4SQLContext.getSession() — ensure the session remains open for the duration of query execution
- Add robust retry logic when session closure is detected, automatically re-creating the session
- Improve error messages: surface the 'Session is already closed' error to the CLI instead of returning generic 417 with empty resultSet
- Handle CSS selector quoting transparently: accept both single and double quotes for CSS selectors in X-SQL, normalizing internally to H2-compatible single quotes
- Add a clear warning in the CLI help that CSS selectors in X-SQL must use single quotes (SQL string literal syntax)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: swarm query hangs indefinitely

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 swarm create
./b4w.ps1 swarm query "https://books.toscrape.com/" --sql @query.sql --wait

#### Expected Behavior

Query completes within seconds and returns extracted data.

#### Actual Behavior

Query stays at '0/1 job(s) completed' indefinitely. After 200+ seconds, still no progress. Required manual task cancellation.

#### Root Cause Analysis

Likely related to the same scrape session lifecycle issue as the htmlsnapshot query 417 errors. The scrape worker may be failing silently or getting stuck in a queue that never processes.

#### Code Pointer

`Likely in the swarm task runner or scrape job dispatcher — the job is submitted but never picked up or fails silently.`

#### AI Suggested Improvement

- Add timeout for swarm query jobs — fail fast with a clear error instead of hanging indefinitely
- Surface worker errors to the CLI — if a scrape job fails to start, report why immediately
- Add progress reporting: show which phase the job is in (fetching page, parsing, executing SQL)
- Consider unifying the scrape session pool between htmlsnapshot query and swarm query to avoid the same race condition

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: eval requires page to be current — silently returns empty/null after other commands

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1. ./b4w.ps1 goto "https://books.toscrape.com/"
2. ./b4w.ps1 htmlsnapshot  (or other commands)
3. ./b4w.ps1 eval "document.title"
→ Returns "" (empty string)

#### Expected Behavior

eval should either work consistently regardless of prior commands, or clearly document that re-navigation is needed, or produce a clear error when the page context is stale.

#### Actual Behavior

eval returns empty string or null with no indication that the page context was lost. A new user would assume the JavaScript is wrong, not that the page needs re-navigation.

#### Root Cause Analysis

After certain commands (possibly htmlsnapshot capture or navigation to about:blank during scrape operations), the browser session's current page may change or the execution context becomes detached. eval runs against whatever page is currently loaded, which may not be the expected page.

#### AI Suggested Improvement

- Add a tip/warning when eval returns empty/null: 'Page context may be stale. Try re-navigating with goto first.'
- Document in SKILL.md that eval depends on current session page state
- Consider making eval auto-restore the last navigated page if the current page is about:blank
- Add a --url flag to eval to specify the page context explicitly

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: eval --file requires last line to be a bare expression, not a statement

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Write a JS file ending with: JSON.stringify(results, null, 2);
Run: ./b4w.ps1 eval --file script.js
→ Returns null

#### Expected Behavior

The documentation should show that the eval result is the value of the last expression, and that statements (ending with semicolons) return undefined/null.

#### Actual Behavior

SKILL.md examples show expressions like `document.title` but don't explain the expression-vs-statement distinction. Users writing multi-statement scripts naturally end with `JSON.stringify(data);` which returns nothing.

#### Root Cause Analysis

eval() in JavaScript returns the completion value of the last statement. Expression statements with a semicolon return `undefined` (serialized as null). To return a value, the last line must be a bare expression or a `return` statement.

#### Code Pointer

`skills/browser4-cli/SKILL.md — the eval section should include this guidance`

#### AI Suggested Improvement

- Document in SKILL.md: 'The eval result is the value of the last expression. Do NOT end scripts with JSON.stringify(data); — instead, end with data to let the CLI serialize it.'
- Add an example showing multi-statement scripts with a bare expression on the last line
- Consider detecting when the last statement is a void expression call (like JSON.stringify) and automatically extracting the argument as the result

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: --selector vs --selector-all naming is confusing for first-time users

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 htmlsnapshot grep "pattern" --selector "article.product_pod"
→ Only matches first element (querySelector semantics)

#### Expected Behavior

The default --selector flag name suggests it scopes to the matched elements. A first-time user naturally expects it to search across all matching elements, not just the first.

#### Actual Behavior

--selector uses querySelector (first match only). User must discover --selector-all for querySelectorAll behavior. The distinction is buried in --help text, not visible in the main help output.

#### Root Cause Analysis

The names follow DOM API semantics (querySelector vs querySelectorAll) but the naming doesn't make the behavioral difference obvious to users unfamiliar with the DOM API.

#### Code Pointer

`cli/browser4-cli/ — the grep command flag definitions`

#### AI Suggested Improvement

- Add a tip when --selector is used: 'Showing results from first matching element only. Use --selector-all to search across all N matching elements.'
- Consider renaming: --selector → --first-in or --scope-first, --selector-all → --selector
- In the main help output, show --selector-all more prominently alongside --selector

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: htmlsnapshot get all text truncates long titles with '...'

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot get all text "article.product_pod h3 a"
→ "A Light in the ...", "Sapiens: A Brief History ...", etc.

#### Expected Behavior

Full text should be returned, or truncation should be clearly indicated with an option to disable it.

#### Actual Behavior

Titles longer than ~30 characters are truncated with '...'. The full title is available in the title attribute but requires a separate extraction method (eval or attr query).

#### Root Cause Analysis

The text extraction likely has a character limit or uses the visible rendered text which may be CSS-truncated by the page's own styling (text-overflow: ellipsis).

#### AI Suggested Improvement

- Add a --no-truncate flag to get full text content regardless of rendered truncation
- Use textContent instead of innerText/innerHTML for get text extraction to avoid CSS-induced truncation
- When truncation occurs, automatically include the title attribute if available (as a secondary field or annotation)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: No way to discover that htmlsnapshot is a prerequisite for htmlsnapshot inspect

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run ./b4w.ps1 htmlsnapshot inspect without first running htmlsnapshot capture.

#### Expected Behavior

Clear error message: 'No snapshot found. Run htmlsnapshot first.'

#### Actual Behavior

The help says 'Use htmlsnapshot first to capture the page into storage' but if a user runs inspect without capturing, it's not clear what error they'd get. The SKILL.md does mention this but a new user reading help output alone might miss it.

#### Root Cause Analysis

The dependency between capture and inspect/get/summary is documented but not enforced with a clear, actionable error message at runtime.

#### Code Pointer

`cli/browser4-cli/ — the htmlsnapshot inspect command handler`

#### AI Suggested Improvement

- When inspect is run without a prior capture, show a clear error: 'No HTML snapshot stored. Run: browser4-cli htmlsnapshot first.'
- Consider making inspect auto-capture if no snapshot exists (with a note that it's doing so)
- Add 'Prerequisite: htmlsnapshot capture' to the inspect help text

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: htmlsnapshot grep --selector with empty pattern gives unhelpful error

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 htmlsnapshot grep --selector "article.product_pod" ""

#### Expected Behavior

Either accept empty pattern as 'match all' or give a clear error: 'Pattern cannot be empty. To see all HTML within a selector, use: htmlsnapshot get all html <selector>'

#### Actual Behavior

Error: Pattern is required. Provide a positional pattern, or use -e PATTERN (repeatable) for multiple patterns.

#### Root Cause Analysis

Pattern validation rejects empty strings but the error doesn't suggest alternatives for the user's likely intent (viewing all content within a selector scope).

#### Code Pointer

`cli/browser4-cli/ — htmlsnapshot grep argument validation`

#### AI Suggested Improvement

- When pattern is empty and --selector is provided, suggest: 'To view all content within a selector, try: htmlsnapshot get all html "<selector>"'
- Consider accepting empty pattern as 'match all' when --selector is provided (useful for structural exploration)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all 9 task steps completed. The core workflow (goto → htmlsnapshot → inspect → get → grep → extract) worked well. The X-SQL query path was completely broken (Critical issue), requiring a workaround via JavaScript eval. The swarm query path also hung indefinitely.

**Success Rate:** 78% — 7 of 9 steps succeeded on first attempt. Steps 8 (X-SQL query) required workaround with eval, and Step 7 (grep validation) required discovering --selector-all vs --selector distinction.

**Issues Found:** 8

**Major Blockers:** X-SQL htmlsnapshot query is completely non-functional (417 error — scrape session closes prematurely). This blocks the primary documented path for correlated multi-field extraction. Swarm query also hangs indefinitely. Both are likely caused by the same backend scrape session lifecycle bug.

**Most Confusing Aspects:** 1) The eval command returning empty/null after other commands — it's not clear the page context was lost. 2) --selector vs --selector-all distinction — the default only matches the first element, which is counterintuitive. 3) The expression-vs-statement distinction in eval scripts — ending with JSON.stringify() returns null, but ending with the bare array works. 4) X-SQL quoting rules — double quotes mean identifiers in H2 SQL, not strings, but this isn't documented in the help.

**Most Valuable Improvements:** 1) Fix the X-SQL scrape session lifecycle bug — this is the single highest-impact fix as it blocks the primary extraction path. 2) Add clear error messages and automatic recovery for stale sessions. 3) Improve eval documentation with multi-statement script examples. 4) Make --selector-all the default or rename flags for clarity. 5) Surface backend errors (session closed, SQL parse errors) to the CLI instead of generic 417 status.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: X-SQL htmlsnapshot query consistently fails with 417 Expectation Failed

echo 'SELECT DOM_FIRST_TEXT(DOM, \'h1\') AS title FROM DOM_LOAD_AND_SELECT(@url, \':root\')' | ./b4w.ps1 htmlsnapshot query --sql-stdin

Or: ./b4w.ps1 htmlsnapshot query --sql @query.sql (with single-quoted CSS selectors in the file)

#### Issue 2: swarm query hangs indefinitely

./b4w.ps1 swarm create
./b4w.ps1 swarm query "https://books.toscrape.com/" --sql @query.sql --wait

#### Issue 3: eval requires page to be current — silently returns empty/null after other commands

1. ./b4w.ps1 goto "https://books.toscrape.com/"
2. ./b4w.ps1 htmlsnapshot  (or other commands)
3. ./b4w.ps1 eval "document.title"
→ Returns "" (empty string)

#### Issue 4: eval --file requires last line to be a bare expression, not a statement

Write a JS file ending with: JSON.stringify(results, null, 2);
Run: ./b4w.ps1 eval --file script.js
→ Returns null

#### Issue 5: --selector vs --selector-all naming is confusing for first-time users

./b4w.ps1 htmlsnapshot grep "pattern" --selector "article.product_pod"
→ Only matches first element (querySelector semantics)

#### Issue 6: htmlsnapshot get all text truncates long titles with '...'

./b4w.ps1 htmlsnapshot get all text "article.product_pod h3 a"
→ "A Light in the ...", "Sapiens: A Brief History ...", etc.

#### Issue 7: No way to discover that htmlsnapshot is a prerequisite for htmlsnapshot inspect

Run ./b4w.ps1 htmlsnapshot inspect without first running htmlsnapshot capture.

#### Issue 8: htmlsnapshot grep --selector with empty pattern gives unhelpful error

./b4w.ps1 htmlsnapshot grep --selector "article.product_pod" ""

