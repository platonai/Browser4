# Issues: x-sql-query-methods

> **Source:** `20261015-134855-x-sql-query-methods.issues.json` | **Date:** 20261015-134855 | **Mode:** dev

## Scenario Background

### Task

**All 8 requested steps completed successfully.**

The page `http://books.toscrape.com/` (redirects to `https://books.toscrape.com/`, "All products | Books to Scrape - Sandbox") was opened in an isolated headless session `books-eval`. `htmlsnapshot inspect` auto-discovered the repeating item container `article.product_pod` (20 per page) and pointed at `p.price_color` for prices; the book title is clipped in the page's own markup ("A Light in the ..."), so the full value comes from the `title` attribute of the anchor inside `h3`.

The X-SQL query used for every input method:

```sql
SELECT
    DOM_FIRST_ATTR(DOM, 'h3 a', 'title') AS title,
    DOM_FIRST_TEXT(DOM, 'p.price_color') AS price
FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod')
```

| Step | Method | Result |
|---|---|---|
| 3 | inline `--sql "…"` | 20 rows, `statusCode: 200` |
| 4 | `--sql "@…/extract_books.sql"` | 20 rows, byte-identical to step 3 |
| 5 | `cat extract_books.sql \| … --sql-stdin` | 20 rows, identical |
| 6 | `--sql-base64 "$(base64 -w0 …)"` | 20 rows, identical |
| 7 | `--sql @file --result-only` | bare pretty-printed JSON array of the 20 rows, no envelope |

Extracted data (page 1, 20 books): first row `A Light in the Attic / £51.77`, last row `It's Only the Himalayas / £45.17`, e.g. `Sapiens: A Brief History of Humankind / £54.23`. Cleanup: `extract_books.sql` was deleted (its content is preserved only as `.test-sessions/20261015T0901431699046Z/x12-extract_books-content.evidence.txt`). The `books-eval` browser session was intentionally left open; cleanup as specified covered only the SQL file.

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help`, `./b4w.ps1 help htmlsnapshot`, `./b4w.ps1 help htmlsnapshot query`; read `skills/browser4-cli/SKILL.md`, `references/x-sql.md`, `references/x-sql-dom-load-select.md`.
2. `./b4w.ps1 list` — found an existing default session; chose an isolated named session instead (`-s books-eval`), per the documented concurrent-run guidance.
3. `./b4w.ps1 -s books-eval open --headless "http://books.toscrape.com/"` — backend/JVM was already running, so no startup wait.
4. `htmlsnapshot inspect` (no selector → auto-discovery) and `htmlsnapshot inspect "article.product_pod" --depth 4 --max 3`.
5. The four query-input variants + `--result-only`, each output compared programmatically (all identical).
6. Error-path probes: missing `@file`, invalid SQL function, empty stdin — all produced clear messages and exit 1.
7. Extra probes for defects: ran the exact SQL that `inspect` suggested (`DOM_FIRST_ATTR(DOM, 'a', 'title')`) → empty; ran the suggested `get all attr "a" title` → 8 empty strings; separated stdout/stderr of an inline query → tip on stdout; replayed the command sequence in a fresh session to test tab-count stability; traced both defects to source.

**Key decisions:** used a named session for isolation; used the `title` attribute (not clipped link text) so titles are complete — the documented truncation pitfall applied on this page; used relative `@`-file paths inside the run scratch directory (never in the repo root).

**Workarounds required:** the tool's own truncation-hint suggestion (`DOM_FIRST_ATTR(DOM, 'a', 'title')`) returns empty strings, so the correct `h3 a` selector had to be derived manually; when parsing the inline-query result, only the first stdout line is JSON because a tip is appended (see Issues 1–2).

---

## Issues Found (6 issues)

### Issue 1: Inline --sql tip is printed to stdout and corrupts the default JSON output

**Severity:** High
**Category:** Reliability

#### Reproduction

cd D:/workspace/Browser4/Browser4-4.14
./b4w.ps1 -s books-eval htmlsnapshot query --sql "SELECT DOM_FIRST_TEXT(DOM, 'h1') AS t FROM DOM_LOAD_AND_SELECT(@url, ':root')" > out.json 2> err.txt
python -c "import json; json.load(open('out.json'))"

#### Expected Behavior

stdout carries exactly the JSON envelope (the default --format json is documented as the clean machine-readable mode), the tip is suppressed or goes to stderr, and stderr/exit code carry any diagnostics.

#### Actual Behavior

stdout contains the JSON envelope + a blank line + 3 tip lines ('💡 Tip: Use --sql @file.sql to avoid shell quoting issues...'); stderr is empty; exit code is 0. json.loads on the whole stdout fails with 'Extra data: line 3 column 1 (char 474)'. Only the inline --sql form triggers it (not @file, --sql-stdin or --sql-base64), but that is the form shown in the command's own examples. SKILL.md also states tips are suppressed by default and appear on stderr, which this contradicts.

#### Root Cause Analysis

In cli/browser4-cli/src/main.rs, handle_html_snapshot_query() computes `let structured_stdout = output_file.is_none() && format != "json";` and then `emit_tip` uses `cli_println!` (stdout) when structured_stdout is false, `eprintln!` when true. Because the DEFAULT format is json, the condition evaluates the default case as 'human output' and writes the tip onto the same stream as the payload; the comment above it states the opposite intent (keep machine-readable output clean). The tip is also emitted whenever SQL was inline (is_inline_sql), with no --show-tip gating.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_query() (tip emission at ~lines 11016-11039)`

#### AI Suggested Improvement

- Treat the default JSON envelope as structured stdout: use `output_file.is_none()` (or `format != "table" && format != "csv"`) so the tip goes to stderr for --format json too.
- Alternatively route every tip/hint to stderr unconditionally, and reserve stdout for the payload the user asked for.
- Gate this hint behind --show-tip (as SKILL.md describes) or emit it at most once per session.
- Add a regression test that pipes `htmlsnapshot query --sql <inline>` stdout into a JSON parser and asserts it parses.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: htmlsnapshot inspect's truncation hint names a selector that silently returns empty strings

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 -s books-eval htmlsnapshot inspect  # prints: DOM_FIRST_ATTR(DOM, 'a', 'title') → "A Light in the Attic"
./b4w.ps1 -s books-eval htmlsnapshot query --sql "SELECT DOM_FIRST_ATTR(DOM, 'a', 'title') AS suggested, DOM_FIRST_ATTR(DOM, 'h3 a', 'title') AS actual FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod', 1, 3)"
./b4w.ps1 -s books-eval htmlsnapshot get all attr "a" title --limit 8

#### Expected Behavior

The SQL snippet printed next to the sample value returns that value for each item (here the full book titles from the title attribute), or the tool warns that the ref is not directly usable as a container-scoped selector.

#### Actual Behavior

The query returns suggested="" for all rows while the true selector 'h3 a' returns "A Light in the Attic"/"Tipping the Velvet"/…; the suggested `get all attr "a" title` returns ["","","","","","","",""]. No error is raised, so a first-time user (or an agent following the tool's own advice) gets a silently empty title column.

#### Root Cause Analysis

The backend builds the hint's childSelector with buildElementRef(target), which yields a standalone tag/class ref of the element that carries the fuller text ('a'), not a selector path relative to the repeating container ('h3 a'). Inside each article.product_pod the first matching <a> is the image link, which has no title attribute, so DOM_FIRST_ATTR / get all resolve to a different element and return "". The CLI then renders that ref verbatim as a SQL selector.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:inspectDocument() (truncation-hint block ~lines 2547-2580, selector from buildElementRef() at ~line 2977); rendered by cli/browser4-cli/src/main.rs:handle_html_snapshot_inspect() (~line 12111 for the SQL snippet, ~line 12333 for the `get all attr` line)`

#### AI Suggested Improvement

- Build the hint selector relative to the inspected container (e.g. 'h3 a' via the same selector-derivation used for 'Suggested selectors'), not from a standalone element ref.
- Validate the emitted selector before printing it: re-run it scoped to the sample and drop the hint if the attribute comes back empty.
- When only an element ref is known, say so explicitly ('the element matching ref <x> carries title=…') instead of emitting it as a DOM_FIRST_ATTR CSS argument.
- Add a books.toscrape.com-shaped fixture test asserting the suggested SQL returns the full title for every row.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Inspect offers no directly usable X-SQL selector for the page's key field (titles)

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 -s books-eval htmlsnapshot inspect   # then read the 'Suggested selectors' block and try the printed title-related suggestions

#### Expected Behavior

For a repeated listing whose title is clipped in the markup, inspect should name a plain, SQL-ready selector path for the title (e.g. h3 a).

#### Actual Behavior

The title is covered only by `h3:expr(a>0)` (printed with the warning that :expr() works in the FROM clause/get but NOT inside DOM_* X-SQL args, i.e. unusable in the SELECT list) and `h3` (listed under 'Structural (bare tags, low specificity) ... use only as a fallback'). The working selector `h3 a` never appears; the only title-oriented snippet is the broken truncation hint of the previous issue. The user must hand-derive `h3 a`.

#### Root Cause Analysis

The selector suggester emits bare tag names and PowerCSS :expr variants for nested elements but no plain descendant combinations, and the truncation hint (the intended bridge from clipped text to the title attribute) carries an unusable standalone ref.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_html_snapshot_inspect() (selector suggestion rendering); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/mcp/controller/MCPToolController.kt:inspectDocument() (selector ranking)`

#### AI Suggested Improvement

- Rank and emit plain descendant selectors ('h3 a', 'h3 > a') when a nested element is the unique carrier of a field, not only bare tags and :expr() forms.
- When a suggested selector would be unusable in the target context (e.g. :expr inside DOM_* args), either suppress it from the X-SQL-oriented list or offer the plain equivalent next to it.
- Print one ready-to-run X-SQL snippet for the most salient field of the repeated row (title/price/link), which is what listing pages are inspected for.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: tab-list reports the only tab as inactive and shows the pre-redirect URL

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s books-eval tab-list --json
./b4w.ps1 -s books-eval page-info

#### Expected Behavior

With exactly one tab, that tab reports active:true; the URL matches the page actually shown (https after the http->https redirect).

#### Actual Behavior

tab-list --json: {"tabs":[{"index":0,...,"url":"http://books.toscrape.com/","title":"All products | Books to Scrape - Sandbox","active":false}],"count":1} while page-info reports https://books.toscrape.com/ for the same tab. In the earlier two-tab state BOTH entries reported active:false, so no tab was ever marked active.

#### Root Cause Analysis

Not investigated in depth. Likely the backend tab enumeration does not mark the session's current target as active (field left false), and tab-list reports the last navigated URL rather than the landed URL after redirects; both need checking in the tab-listing path.

#### AI Suggested Improvement

- Mark the session's current tab active in tab-list output, and assert the invariant 'exactly one active tab'.
- Report the landed URL (post-redirect) in tab-list, or label the field as the navigated URL if the pre-redirect form is intentional.
- Cover it with a test that opens a redirecting URL and compares tab-list vs page-info.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Duplicate tab appeared once and was not reproducible

**Severity:** Low
**Category:** Reliability

#### Reproduction

Session sequence: open --headless -> htmlsnapshot inspect (auto) -> inspect "article.product_pod" -> query (inline) -> query (@file) -> query (--sql-stdin) -> query (--sql-base64) -> query --result-only -> get all attr "a" title -> get all attr "h3 a" title -> page-info. Then: ./b4w.ps1 -s books-eval page-info  # printed 'Page 0' and 'Page 1'; ./b4w.ps1 -s books-eval tab-list --json  # count 2, both on https://books.toscrape.com/

#### Expected Behavior

Read-only extraction commands leave the session with the single tab that was opened; page-info lists one page.

#### Actual Behavior

Two identical tabs existed in the session at the end of that sequence (only open, a navigation, was ever supposed to create a tab). Replaying every command of the sequence on a fresh session (close, open, inspect, query, query --sql-base64, query --sql-stdin, get all, page-info) kept the tab count at 1 after each step, so the trigger is unknown.

#### Root Cause Analysis

Unknown. Candidates: the session/browser launch restoring a previous target, or a command on the page-load path opening a helper target that is never closed. Needs instrumented logging of Target.createTarget callers per command.

#### AI Suggested Improvement

- Log which component opens each CDP target (and why) at debug level so a stray tab can be attributed.
- Add an invariant check/test: after each read-only htmlsnapshot command, the session's tab count must be unchanged.
- Document explicitly if any read path (e.g. -readonly loads) may open a target, so users are not surprised by extra entries in tab-list.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: --sql-base64 help example cannot run in PowerShell

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 help htmlsnapshot query   # example: browser4-cli htmlsnapshot query --sql-base64 "$(base64 -w0 query.sql)"  — paste it into PowerShell

#### Expected Behavior

A copy-pasteable example for the CLI's primary platform shell (b4w.ps1 targets Windows/PowerShell), or a per-shell pair.

#### Actual Behavior

The example is POSIX-only ($(...) command substitution and base64 -w0 do not exist in PowerShell); the Windows equivalent ([Convert]::ToBase64String([IO.File]::ReadAllBytes(...))) is documented only for eval --base64 in references/eval.md.

#### Root Cause Analysis

The example block for htmlsnapshot query in cli/browser4-cli/src/help.rs (~line 1211) was written from a bash perspective only.

#### Code Pointer

`cli/browser4-cli/src/help.rs (htmlsnapshot query examples block, ~line 1211)`

#### AI Suggested Improvement

- Add the PowerShell form next to the POSIX one, e.g. --sql-base64 ([Convert]::ToBase64String([IO.File]::ReadAllBytes('query.sql'))).
- Label the existing line as 'bash / macOS' and mirror the eval.md wording for Windows.
- Verify examples per platform in CI (a smoke test that runs each documented example under both shells).

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

**Completion Status:** Successful — all 8 task steps completed. The page was opened, the DOM structure and selectors were discovered with htmlsnapshot inspect, the X-SQL title/price query returned 20 rows, and all four query-input methods (inline --sql, @file, --sql-stdin, --sql-base64) produced byte-identical results; --result-only printed the bare data array and the temporary extract_books.sql was deleted. One tool-suggested selector had to be corrected by hand (see Issue 2).

**Success Rate:** 100% of the requested steps succeeded (the falsified inspect suggestion is counted as a defect, not a failed step).

**Issues Found:** 6

**Major Blockers:** None. No command failed in a way that blocked the task; the only correctness trap was the inspect truncation hint, which silently returns empty strings and could have produced an empty title column if followed verbatim.

**Most Confusing Aspects:** 1) The tool's own recommendation (DOM_FIRST_ATTR(DOM, 'a', 'title')) returns "" while printing the correct value beside it — a new user cannot tell whether the page, the selector, or their SQL is wrong. 2) Inline --sql emits a tip onto stdout, so a JSON-oriented pipeline breaks on the first line-1 parse even though the exit code is 0. 3) For the most important field on the page (titles) inspect offers no plain usable selector, only a :expr() form it simultaneously warns against. 4) tab-list metadata (active:false, http URL) disagrees with page-info (https URL).

**Most Valuable Improvements:** 1) Keep stdout clean for the default JSON format — route every tip/hint to stderr (trivial fix in handle_html_snapshot_query). 2) Make the truncation hint emit a container-relative selector and validate it before printing, so the suggested SQL actually returns the sample value it shows. 3) Suggest plain descendant selectors (h3 a) alongside bare tags in inspect output, with one ready-to-run X-SQL snippet for a listing row. 4) Fix tab-list's active flag/URL and add per-shell examples for --sql-base64.

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

#### Issue 1: Inline --sql tip is printed to stdout and corrupts the default JSON output

cd D:/workspace/Browser4/Browser4-4.14
./b4w.ps1 -s books-eval htmlsnapshot query --sql "SELECT DOM_FIRST_TEXT(DOM, 'h1') AS t FROM DOM_LOAD_AND_SELECT(@url, ':root')" > out.json 2> err.txt
python -c "import json; json.load(open('out.json'))"

#### Issue 2: htmlsnapshot inspect's truncation hint names a selector that silently returns empty strings

./b4w.ps1 -s books-eval htmlsnapshot inspect  # prints: DOM_FIRST_ATTR(DOM, 'a', 'title') → "A Light in the Attic"
./b4w.ps1 -s books-eval htmlsnapshot query --sql "SELECT DOM_FIRST_ATTR(DOM, 'a', 'title') AS suggested, DOM_FIRST_ATTR(DOM, 'h3 a', 'title') AS actual FROM DOM_LOAD_AND_SELECT(@url, 'article.product_pod', 1, 3)"
./b4w.ps1 -s books-eval htmlsnapshot get all attr "a" title --limit 8

#### Issue 3: Inspect offers no directly usable X-SQL selector for the page's key field (titles)

./b4w.ps1 -s books-eval htmlsnapshot inspect   # then read the 'Suggested selectors' block and try the printed title-related suggestions

#### Issue 4: tab-list reports the only tab as inactive and shows the pre-redirect URL

./b4w.ps1 -s books-eval tab-list --json
./b4w.ps1 -s books-eval page-info

#### Issue 5: Duplicate tab appeared once and was not reproducible

Session sequence: open --headless -> htmlsnapshot inspect (auto) -> inspect "article.product_pod" -> query (inline) -> query (@file) -> query (--sql-stdin) -> query (--sql-base64) -> query --result-only -> get all attr "a" title -> get all attr "h3 a" title -> page-info. Then: ./b4w.ps1 -s books-eval page-info  # printed 'Page 0' and 'Page 1'; ./b4w.ps1 -s books-eval tab-list --json  # count 2, both on https://books.toscrape.com/

#### Issue 6: --sql-base64 help example cannot run in PowerShell

./b4w.ps1 help htmlsnapshot query   # example: browser4-cli htmlsnapshot query --sql-base64 "$(base64 -w0 query.sql)"  — paste it into PowerShell

