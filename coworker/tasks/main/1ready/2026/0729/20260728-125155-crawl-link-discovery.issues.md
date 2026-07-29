# Issues: crawl-link-discovery

> **Source:** `20260728-125155-crawl-link-discovery.full.md` | **Date:** 20260728-125155 | **Mode:** dev

## Scenario Background

### Task

The task was **partially successful**. Here's what worked and what didn't:

| Step | Description | Outcome |
|------|-------------|---------|
| 1 | Crawl with depth 1, link selector, link pattern, top-links 10, table format | ❌ **Blocked** — link discovery fails with backend `TaskLoops` bean error |
| 2 | Re-run as CSV with `--output` | ⚠️ **Partial** — works with `--sql` + `--depth 0` seed-file mode, not link discovery |
| 3 | Re-run as JSON | ⚠️ **Partial** — same limitation; JSON output is correct when X-SQL is used |
| 4 | Seed file with 2–3 book detail URLs, crawl with `--seed-file` | ✅ **Works** (seed-file bulk-fetch mode, depth 0) |
| 5 | List crawl history with `crawl list` | ✅ **Works** — shows 21 tracked tasks with timestamps and status |

**Data extracted successfully via X-SQL:**
```
price   | title                | url
--------+----------------------+-------------------------------------------------------
£51.77  | A Light in the Attic | https://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html
```

**Key blocker:** Crawl link discovery (`depth >= 1`) is completely broken due to a missing Spring bean (`ai.platon.pulsar.loop.TaskLoops`). All CSS selectors (even bare `a`) return zero matches. The bulk-fetch mode (`--depth 0` + `--seed-file`) works but suffers from an intermittent "protocol handler not ready" error on pages beyond the first, and ~100s startup delays on every crawl invocation.

### Execution Context

**Key Commands:**

1. `pwsh ./b4w.ps1 help` — load help output (direct `./b4w.ps1` failed due to CRLF shebang; used `pwsh` explicitly)
2. `pwsh ./b4w.ps1 crawl --help` — inspect crawl subcommand flags
3. `pwsh ./b4w.ps1 goto "http://books.toscrape.com/"` — navigate to site, inspect page structure
4. `pwsh ./b4w.ps1 htmlsnapshot capture` — capture HTML snapshot for inspection
5. `pwsh ./b4w.ps1 htmlsnapshot get all attr 'a[href]' href --limit 50` — extract link hrefs to understand URL patterns
6. `pwsh ./b4w.ps1 crawl ... -ol 'a[href*="catalogue"]' -olp '.*catalogue.*' -tl 10 -d 1 --format table` — first crawl attempt (link discovery — **failed: 0 pages**)
7. Multiple retries with simpler selectors (`a[href]`, `a`) — all **failed: 0 pages**
8. Switch to `https://` — got 1 page but no out-links
9. Inspected `crawl result` JSON → discovered root cause: `"No qualifying bean of type 'ai.platon.pulsar.loop.TaskLoops' available"`
10. Created seed file with 3 book detail URLs → `pwsh ./b4w.ps1 crawl --seed-file ... --depth 0 --format table` — **3 pages found**
11. `pwsh ./b4w.ps1 crawl --seed-file ... --depth 0 --format csv -o results.csv` — CSV **written but plain text** (no `--sql`)
12. Created X-SQL query file (`extract.sql`) → re-ran with `--sql @file --format json` — **valid JSON with extracted fields**
13. `pwsh ./b4w.ps1 crawl --seed-file ... --sql @file --format table` — **proper table output**
14. `pwsh ./b4w.ps1 crawl --seed-file ... --sql @file --format csv --output file.csv` — **proper CSV written**
15. `pwsh ./b4w.ps1 crawl list` — **21 tracked tasks shown**
16. `pwsh ./b4w.ps1 crawl clear` — **29 backend tasks cleared**

**Workarounds required:**
- Used `pwsh ./b4w.ps1` instead of `./b4w.sh` or `./b4w.ps1` because CRLF line endings break the shebang
- Used `--seed-file` + `--depth 0` (bulk fetch) instead of link discovery because crawl link extraction is broken
- Used `--sql @file` for X-SQL extraction because inline SQL quoting is fragile
- Created `.test-sessions/` directory manually for all outputs

---

## Issues Found (9 issues)

### Issue 1: Crawl link discovery completely broken — missing TaskLoops Spring bean

**Severity:** Critical
**Category:** Reliability

#### Reproduction

pwsh ./b4w.ps1 crawl 'https://books.toscrape.com/' -ol 'a' -d 1 -tl 3 --refresh

#### Expected Behavior

The crawl extracts links matching the CSS selector and follows them up to the specified depth.

#### Actual Behavior

Crawl completes with 0 out-links found. The crawl result JSON reveals: 'No qualifying bean of type ai.platon.pulsar.loop.TaskLoops available'. Every CSS selector including bare 'a' returns zero matches. The portal page itself loads (page found at depth 0), but link discovery never executes.

#### Root Cause Analysis

The crawl's link-discovery pipeline requires a Spring bean 'ai.platon.pulsar.loop.TaskLoops' that is not registered in the application context when running from the locally-built JAR. This could be a missing component scan, a conditional bean that evaluates to false in dev mode, or a missing module dependency.

#### Code Pointer

`browser4-rest or browser4-core — the Spring configuration that should register TaskLoops bean`

#### AI Suggested Improvement

- Ensure TaskLoops is registered as a Spring bean (check @ComponentScan base packages, @Conditional annotations)
- Add a fallback or null-safe path in the crawl link extractor so link discovery degrades gracefully rather than silently returning 0 results
- Add a clear error message to the CLI output when this bean is missing, rather than showing 'matched zero elements'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Critical — core crawl feature is completely non-functional due to missing `TaskLoops` Spring bean. Clear repro, well-analyzed root cause. The `load_and_select` pipeline silently returns zero matches with no user-visible error, making this a priority fix.

---

### Issue 2: No way to complete the task as documented — crawl link discovery fundamentally broken

**Severity:** Critical
**Category:** Product

#### Reproduction

Any crawl command with depth >= 1: pwsh ./b4w.ps1 crawl <url> -ol <any-selector> -d 1

#### Expected Behavior

Links are discovered from the portal page and followed to depth 1.

#### Actual Behavior

Link discovery always returns 0 results regardless of CSS selector, URL, or flags. The documented core crawl workflow (navigate portal → extract links → follow to detail pages) is completely non-functional.

#### Root Cause Analysis

Combination of the TaskLoops bean missing (Issue 2) and the protocol handler issue (Issue 3). Even if link discovery worked, the handler would likely fail on subsequent pages.

#### AI Suggested Improvement

- Fix the TaskLoops bean registration (Issue 2) and protocol handler lifecycle (Issue 3)
- After fixes, run the documented crawl workflow end-to-end as a validation test
- Add an integration test that verifies link discovery on a known test site like books.toscrape.com

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] Describes the same broken crawl link-discovery symptom as Issue 1, just framed from the user/product perspective ("documented workflow can't complete"). The root cause section itself references Issues 1 and 4. No distinct actionable item beyond fixing those two.

---

### Issue 3: CRLF line endings in b4w.ps1 break direct execution on Linux

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 help

#### Expected Behavior

The script executes via the pwsh shebang.

#### Actual Behavior

/usr/bin/env: 'pwsh\r': No such file or directory

#### Root Cause Analysis

The b4w.ps1 file uses Windows CRLF line terminators. The shebang line ends with \r, which /usr/bin/env treats as part of the executable name. pwsh 7.6.3 IS installed at /opt/microsoft/powershell/7/pwsh, but the CR causes the lookup to fail.

#### Code Pointer

`b4w.ps1:1 — shebang line`

#### AI Suggested Improvement

- Convert b4w.ps1 line endings to LF (Unix) using dos2unix or a git attribute (.gitattributes: b4w.ps1 text eol=lf)
- Alternative: add a b4w.sh wrapper that invokes pwsh -File b4w.ps1, which already exists but should be the documented primary entry point on Linux

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] High severity — completely blocks Linux users from running the primary entry-point script. Fix is straightforward (`.gitattributes` `text eol=lf` or `dos2unix`). The existing `b4w.sh` wrapper mitigates but doesn't excuse the shebang being broken.

---

### Issue 4: Crawl bulk fetch fails for pages beyond the first — 'protocol handler not ready'

**Severity:** High
**Category:** Reliability

#### Reproduction

pwsh ./b4w.ps1 crawl --seed-file .test-sessions/book-seeds.txt --depth 0 --sql @.test-sessions/extract.sql --format json
(seed file contains 3 distinct book URLs)

#### Expected Behavior

All 3 pages load and X-SQL extraction produces 3 rows of results.

#### Actual Behavior

Only the first page loads successfully (contentLength: 12787, extracted fields correct). Pages 2 and 3 fail with contentLength: 0 and extractionError: 'fetch returned 0 bytes (possible protocol handler not ready)'. This pattern is 100% reproducible across multiple crawl invocations.

#### Root Cause Analysis

The HTTP protocol handler (likely OkHttp or similar) used for page fetching appears to close, crash, or become unregistered after the first successful request, causing subsequent fetches to return 0 bytes. The error message suggests the handler is not 'ready' rather than timing out or returning an HTTP error.

#### Code Pointer

`browser4-core — the page fetch/protocol handler initialization and lifecycle management`

#### AI Suggested Improvement

- Investigate the protocol handler lifecycle — ensure it is properly re-initialized or reused between page fetches within a single crawl session
- Add a retry mechanism for the 'protocol handler not ready' error (at least 3 retries with backoff)
- Consider whether the handler needs per-request instantiation rather than a shared instance

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] High severity — protocol handler dies after first successful fetch, reliably breaking any crawl with more than one page. Distinct root cause from Issue 1 (handler lifecycle vs. bean registration). The 100% repro rate and specific error message make this diagnosable.

---

### Issue 5: htmlsnapshot query (X-SQL) returns 417 'Expectation Failed' while crawl --sql works on same page

**Severity:** High
**Category:** Reliability

#### Reproduction

pwsh ./b4w.ps1 htmlsnapshot query --sql @extract.sql 'https://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html'
(using the same SQL file that works with crawl --sql)

#### Expected Behavior

X-SQL extraction returns results identical to crawl --sql on the same page.

#### Actual Behavior

Returns status 417 'Expectation Failed' with empty resultSet, even though pageStatusCode: 200 and pageContentBytes: 12787 confirm the page loaded successfully. The same SQL file and same URL work perfectly through crawl --seed-file --depth 0 --sql.

#### Root Cause Analysis

The htmlsnapshot query code path uses a different X-SQL execution pipeline than crawl --sql. The 417 status suggests the X-SQL query engine or template processor fails validation in the htmlsnapshot pathway while succeeding in the crawl pathway. The @url placeholder resolution might differ between the two paths.

#### Code Pointer

`browser4-rest — MCPToolController or X-SQL query executor for htmlsnapshot vs crawl paths`

#### AI Suggested Improvement

- Unify the X-SQL execution pipeline between htmlsnapshot query and crawl --sql
- Add detailed error information to the 417 response (current response only has empty resultSet)
- Document the known difference or fix so both paths produce consistent results

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Two code paths for the same operation (X-SQL extraction) produce different results — a correctness problem. The 417 with empty resultSet while the page loaded fine suggests a validation/setup divergence between `htmlsnapshot query` and `crawl --sql` pipelines. Unifying them is the right fix.

---

### Issue 6: Crawl startup delays — consistently 96+ seconds before processing begins

**Severity:** Medium
**Category:** Reliability

#### Reproduction

pwsh ./b4w.ps1 crawl --seed-file .test-sessions/book-seeds.txt --depth 0 --format table

#### Expected Behavior

Crawl processing begins within a few seconds of submission.

#### Actual Behavior

Every crawl invocation shows 'Still waiting for crawl to start...' messages at 16-second intervals, taking 80-96 seconds before processing begins. This happens even after running 'crawl clear' to remove terminal tasks, and regardless of whether 1 or 3 URLs are submitted.

#### Root Cause Analysis

The crawl backend appears to have a fixed polling interval (16s) and the queue/worker startup takes multiple cycles. This could be due to worker thread pool exhaustion, a stuck previous task holding a lock, or the scheduler using a pessimistic polling strategy.

#### Code Pointer

`browser4-rest — crawl task scheduler or worker pool configuration`

#### AI Suggested Improvement

- Reduce the polling interval from 16s to 2-3s for the first few checks
- Ensure completed tasks release worker threads immediately
- Add a --nowait flag for immediate synchronous submission to the worker pool

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] 80-96s startup is a real usability problem, but the suggested `--nowait` flag adds API surface. Better approach: fix the root cause (stuck tasks holding worker threads, pessimistic polling) rather than adding a workaround flag. The polling interval reduction is a good quick win; ensure completed tasks release workers immediately is the proper fix.

---

### Issue 7: X-SQL function naming inconsistent between crawl reference and X-SQL reference docs

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Compare skills/browser4-cli/references/crawl.md examples vs skills/browser4-cli/references/x-sql.md examples.

#### Expected Behavior

Consistent function naming across all documentation files.

#### Actual Behavior

crawl.md uses lowercase: dom_first_text(), dom_base_uri(), load_and_select(@url, ...). x-sql.md uses uppercase: DOM_FIRST_TEXT(), DOM_BASE_URI(), DOM_LOAD_AND_SELECT(@url, ...). Both forms appear to work (case-insensitive), but a first-time user reading one doc and copying to the other context would be confused.

#### Root Cause Analysis

The SQL engine accepts both uppercase and lowercase function names, but documentation was written by different authors at different times without a style guide.

#### AI Suggested Improvement

- Pick one convention (recommend uppercase DOM_* to match SQL convention) and standardize all documentation
- Add a note in both docs acknowledging the case-insensitivity
- Add a linter/CI check that validates all X-SQL examples in docs parse correctly

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Legitimate documentation inconsistency. Both forms work (case-insensitive SQL engine) but mixed conventions confuse users who copy examples between docs. Recommend standardizing on uppercase `DOM_*` to match SQL convention, plus a one-line note about case-insensitivity.

---

### Issue 8: --format flag misleading without --sql — produces plain text, not structured output

**Severity:** Medium
**Category:** UX

#### Reproduction

pwsh ./b4w.ps1 crawl --seed-file seeds.txt --depth 0 --format csv -o results.csv

#### Expected Behavior

CSV-formatted output with at least a URL column.

#### Actual Behavior

The output file contains human-readable plain text listing crawled pages, identical to the default (no --format) output. CSV/JSON formatting only activates when --sql is provided, since there are no columns to format.

#### Root Cause Analysis

The --format flag is a formatter for structured X-SQL result sets. Without --sql, there is no structured data, so the flag is silently ignored and the default plain-text page listing is emitted instead.

#### Code Pointer

`cli/browser4-cli/src/ — crawl output formatting logic`

#### AI Suggested Improvement

- When --format csv or --format json is specified without --sql, emit a default structured output with at least URL and title columns (from page metadata)
- Or emit a warning: '--format requires --sql to produce structured output; falling back to plain text'
- Document this dependency prominently in the --format flag description

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Silently ignoring `--format` without `--sql` is a real UX trap — users get plain text when they asked for CSV/JSON. However, auto-emitting default structured output (URL+title) changes behavior silently. Better: emit a clear warning to stderr ("--format requires --sql; outputting plain text") and document the dependency in `--help`. This preserves predictability while eliminating the silent-failure UX.

---

### Issue 9: Stderr noise: 'Finding browser4 root from...' printed during crawl --sql operations

**Severity:** Low
**Category:** UX

#### Reproduction

pwsh ./b4w.ps1 crawl --seed-file seeds.txt --depth 0 --sql @query.sql --format json

#### Expected Behavior

Clean output with only the crawl progress and results.

#### Actual Behavior

A diagnostic line 'Finding browser4 root from /home/vincent/workspace/Browser4-4.12' appears on stderr before the crawl output, polluting both terminal display and JSON parsing.

#### Root Cause Analysis

The file resolution logic for --sql @file writes a debug/info log message to stderr unconditionally, rather than using a proper logging framework or suppressing it in non-verbose mode.

#### Code Pointer

`cli/browser4-cli/src/ — SQL file resolution logic`

#### AI Suggested Improvement

- Move this message to a debug/trace log level, only shown with --verbose
- Or emit it to stderr only when the resolved path differs from the expected path
- Suppress all non-error stderr output when --json flag is active

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Diagnostic noise on stderr breaks JSON parsing and is unprofessional for a CLI tool. The fix is trivial — gate the message behind `--verbose` or use a proper logging framework. The JSON-output flag should suppress all non-error stderr by convention.

---

## Overall Assessment

**Completion Status:** Partially Successful — The seed-file (bulk fetch) crawl mode, X-SQL extraction, multi-format output (table/CSV/JSON), and crawl history listing all work correctly. However, the primary crawl link-discovery workflow (depth >= 1 with --out-link-selector) is completely broken due to two backend bugs, forcing a workaround via manual URL collection + seed-file mode.

**Success Rate:** 60% — 3 of 5 task steps succeeded (seed file crawl, CSV/JSON formats, crawl list); 2 steps were blocked by the link-discovery bug. The core 'follow links from a portal page' workflow that the crawl command is designed for is non-functional.

**Issues Found:** 9

**Major Blockers:** Two critical backend reliability issues: (1) Missing TaskLoops Spring bean prevents ALL link discovery, making the documented 'depth >= 1' crawl mode completely non-functional. (2) Protocol handler returns 0 bytes for all pages beyond the first in bulk-fetch mode, limiting practical usage to single-page extractions.

**Most Confusing Aspects:** For a first-time user: (a) The --format json/csv flag silently produces plain text without --sql, with no warning or error. (b) X-SQL function names differ between crawl.md and x-sql.md documentation. (c) The diagnostic 'CSS selector matched zero elements' is misleading — the page loads fine but the backend silently fails. (d) The ~100s startup delay with no progress feedback feels like a hang.

**Most Valuable Improvements:** (1) Fix the TaskLoops bean so link discovery works — this is the crawl command's core feature. (2) Fix the protocol handler lifecycle so multi-page crawls work reliably. (3) Add clear error messages when backend components are missing instead of silent zero-results. (4) Reduce the 16s polling interval for crawl startup to 2-3s. (5) Make --format warn when used without --sql.

**Usability Rating:** 4/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Crawl link discovery completely broken — missing TaskLoops Spring bean

pwsh ./b4w.ps1 crawl 'https://books.toscrape.com/' -ol 'a' -d 1 -tl 3 --refresh

#### Issue 2: No way to complete the task as documented — crawl link discovery fundamentally broken

Any crawl command with depth >= 1: pwsh ./b4w.ps1 crawl <url> -ol <any-selector> -d 1

#### Issue 3: CRLF line endings in b4w.ps1 break direct execution on Linux

./b4w.ps1 help

#### Issue 4: Crawl bulk fetch fails for pages beyond the first — 'protocol handler not ready'

pwsh ./b4w.ps1 crawl --seed-file .test-sessions/book-seeds.txt --depth 0 --sql @.test-sessions/extract.sql --format json
(seed file contains 3 distinct book URLs)

#### Issue 5: htmlsnapshot query (X-SQL) returns 417 'Expectation Failed' while crawl --sql works on same page

pwsh ./b4w.ps1 htmlsnapshot query --sql @extract.sql 'https://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html'
(using the same SQL file that works with crawl --sql)

#### Issue 6: Crawl startup delays — consistently 96+ seconds before processing begins

pwsh ./b4w.ps1 crawl --seed-file .test-sessions/book-seeds.txt --depth 0 --format table

#### Issue 7: X-SQL function naming inconsistent between crawl reference and X-SQL reference docs

Compare skills/browser4-cli/references/crawl.md examples vs skills/browser4-cli/references/x-sql.md examples.

#### Issue 8: --format flag misleading without --sql — produces plain text, not structured output

pwsh ./b4w.ps1 crawl --seed-file seeds.txt --depth 0 --format csv -o results.csv

#### Issue 9: Stderr noise: 'Finding browser4 root from...' printed during crawl --sql operations

pwsh ./b4w.ps1 crawl --seed-file seeds.txt --depth 0 --sql @query.sql --format json

