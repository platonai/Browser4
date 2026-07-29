# Issues: crawl-link-discovery

> **Source:** `20260728-153336-crawl-link-discovery.full.md` | **Date:** 20260728-153336 | **Mode:** dev

## Scenario Background

### Task

**Partially Successful.** The crawl feature suffered from multiple critical backend bugs that prevented link discovery entirely and caused intermittent fetch failures. All task steps were attempted but required significant workarounds.

| Step | Description | Outcome |
|------|-------------|---------|
| 1 | Crawl with depth 1, CSS selector, regex pattern, top-links 10, table format | ❌ **Blocked** — link discovery returns 0 elements for all selectors |
| 2 | Same crawl as CSV with `--output` | ⚠️ **Partial** — `--format csv` without `--sql` produces plain text, not CSV |
| 3 | Same crawl as JSON | ⚠️ **Partial** — `--format json` without `--sql` produces plain text, not JSON |
| 4 | Seed file with 2-3 book URLs, crawl with `--seed-file` | ✅ **Works** (with caveats: ~2min startup, intermittent 0-byte fetches) |
| 5 | `crawl list` to verify history | ✅ **Works** — shows 13 tracked tasks with timestamps and status |

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — loaded CLI help
2. Read `skills/browser4-cli/SKILL.md` and `skills/browser4-cli/references/crawl.md` — learned crawl syntax and flags
3. `./b4w.ps1 goto "http://books.toscrape.com/"` — navigated to site in browser
4. `./b4w.ps1 htmlsnapshot` then `htmlsnapshot inspect` — discovered page structure and CSS selectors
5. `./b4w.ps1 htmlsnapshot get all attr "article.product_pod h3 a" href --all` — extracted 20 book detail URLs
6. **5× attempts** at `crawl "http://books.toscrape.com/" --out-link-selector <various> --depth 1` — all failed with 0 pages
7. `./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --format table` — first successful crawl (3 URLs, but 2 had 0-byte fetches)
8. `./b4w.ps1 crawl --seed-file <file> --depth 0 --format table` — successful with 10 URLs (9 had 0-byte errors)
9. `./b4w.ps1 crawl --seed-file <file> --depth 0 --format csv --output .test-sessions/books-crawl.csv --background` — submitted CSV crawl
10. `./b4w.ps1 crawl --seed-file <file> --depth 0 --format json --output .test-sessions/books-crawl.json --background` — submitted JSON crawl
11. `./b4w.ps1 crawl --seed-file <file> --depth 0 --format csv --output .test-sessions/simple-crawl.csv` — CSV output produced plain text, not CSV
12. `./b4w.ps1 crawl --seed-file <file> --depth 0 --format json --output .test-sessions/simple-crawl.json` — JSON output produced plain text, not JSON
13. `./b4w.ps1 crawl status <id>` — checked individual crawl statuses
14. `./b4w.ps1 crawl result <id>` — inspected detailed crawl results
15. `./b4w.ps1 crawl cancel <id>` — cancelled stuck background crawls
16. `./b4w.ps1 crawl list` — listed all 13 crawl tasks

**Workarounds Applied During Task:**

- **Link discovery workaround:** Used `htmlsnapshot get all attr` to manually extract book detail URLs, wrote them to a seed file, then used `crawl --seed-file --depth 0` for bulk fetch instead of depth 1 link discovery
- **Format workaround:** Accepted that `--format csv` and `--format json` produce identical plain text without `--sql` — no structured output available from crawl without X-SQL
- **Startup delay:** Every crawl invocation required 96-449 seconds of waiting before processing began; no way to skip the queue

---

---

## Issues Found (9 issues)

### Issue 1: Crawl link discovery returns 0 elements for all CSS selectors — depth >= 1 completely broken

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1 --top-links 5 --refresh --format table

#### Expected Behavior

Links matching the 'a' CSS selector are extracted from the portal page (~94 links visible via htmlsnapshot).

#### Actual Behavior

Crawl completes with 0 pages found. Diagnostic: 'The page loaded but the CSS selector 'a' matched zero elements.' Multiple selectors tested ('a', '.product_pod h3 a', 'a[href*=catalogue]') all produce 0 results. The page clearly contains 94 anchor elements when inspected via htmlsnapshot or goto+snapshot.

#### Root Cause Analysis

The crawl link-discovery path uses `session.loadDocument()` which fetches the page through a Jsoup/parse pipeline, then calls `document.select(selector)`. This pipeline and the browser-based DOM used by `htmlsnapshot` are different paths. The `appendSelectorIfMissing()` function in `LoadOptions.kt` transforms CSS selectors (possibly appending ' a' or modifying them), and the Jsoup document may not contain the same DOM structure as the live browser page. Additionally, `correctOutLinkSelector()` in LoadOptions.kt line 988-992 calls `appendSelectorIfMissing(it, 'a')` which may transform the selector in unexpected ways.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:extractOutLinks() line 780; browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/options/LoadOptions.kt:correctOutLinkSelector() line 988`

#### AI Suggested Improvement

- Investigate why Jsoup's document.select() returns 0 results when browser DOM shows 94 anchors — the fetch/parse pipeline may not be rendering the page correctly
- Debug the appendSelectorIfMissing() logic in the pulsar-dom external JAR to confirm it does not corrupt CSS selectors
- Add integration test that verifies link discovery on a known site like books.toscrape.com
- When link discovery returns 0 results, log the document HTML length and anchor count (existing code has this logging at line 800-804 but it may not be reaching that point)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Critical — crawl link discovery is completely broken at depth >= 1. Jsoup pipeline returns 0 elements when browser DOM has 94 anchors. This is the single most impactful bug: it makes multi-level crawl unusable. Fix the Jsoup fetch/parse path so it matches the browser DOM contract; regression-test on books.toscrape.com.

---

### Issue 2: Crawl bulk fetch fails with 'protocol handler not ready' for most pages beyond the first

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/book-details-seed.txt --depth 0 --format table
(seed file with 10 book detail URLs from books.toscrape.com)

#### Expected Behavior

All 10 pages load and are listed in crawl results with titles and content lengths.

#### Actual Behavior

Only 1 of 10 pages loaded successfully (12,787 bytes). The remaining 9 returned 0 bytes with error: 'fetch returned 0 bytes (possible protocol handler not ready)'. This pattern was consistent across multiple crawl invocations — always the first page succeeds, subsequent pages fail.

#### Root Cause Analysis

The HTTP protocol handler used for page fetching (likely OkHttp or similar) appears to close, crash, or become unregistered after the first successful request. The error 'protocol handler not ready' suggests the handler lifecycle is not properly managed — it may be a singleton that gets into a bad state after first use, or a per-thread resource that isn't initialized for worker threads beyond the first.

#### Code Pointer

`browser4-core — the FetchComponent or protocol handler initialization and lifecycle management`

#### AI Suggested Improvement

- Investigate the protocol handler lifecycle — ensure it is properly re-initialized or reused between page fetches within a single crawl session
- Add retry logic for 'protocol handler not ready' errors (at least 3 retries with exponential backoff)
- Consider instantiating a fresh protocol handler per request rather than sharing a singleton
- Add a health check before each fetch to verify the handler is ready

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Critical — 9 of 10 pages produce 0 bytes due to protocol handler lifecycle failure. Shares root-cause territory with Issue 1 (both are fetch-pipeline bugs). Fix the handler lifecycle (singleton re-init or per-request instantiation) and add retry logic. Test with seed files of 10+ URLs.

---

### Issue 3: Crawl page titles empty for book detail pages even when fetched successfully

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/simple-seed.txt --depth 0
(seed file includes https://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html)

#### Expected Behavior

The title field contains the page's HTML <title> text, e.g. 'A Light in the Attic | Books to Scrape - Sandbox'.

#### Actual Behavior

The result JSON shows 'title': '' (empty string) for the book detail page, even though contentLength: 12787 confirms the page loaded successfully. The main page (books.toscrape.com/) correctly returns 'All products | Books to Scrape - Sandbox'. This means title extraction works for some pages but fails for book detail pages.

#### Root Cause Analysis

The Jsoup document parser's title extraction (`document.title`) may be returning empty for certain page structures. The book detail pages at books.toscrape.com have a specific HTML structure where the <title> element may be parsed differently by Jsoup vs. the live browser DOM.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt — parseIntoTokens() or the Jsoup document.title call`

#### AI Suggested Improvement

- Investigate why Jsoup's document.title returns empty for book detail pages that clearly have <title> elements in their source HTML
- Add fallback title extraction using a CSS selector query if document.title is empty
- Log the raw HTML title element content when document.title returns empty to aid debugging

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] High — titles empty on book detail pages despite successful fetch (12KB content). Likely the same Jsoup parsing defect as Issue 1: the document model differs from real DOM. Add a fallback CSS-query title extractor and log the raw `<title>` element when `document.title` is empty. Should be investigated alongside Issue 1.

---

### Issue 4: Crawl startup delay — consistently 80-450 seconds before processing begins

**Severity:** High
**Category:** Reliability

#### Reproduction

Any crawl invocation: ./b4w.ps1 crawl --seed-file <file> --depth 0 --format table

#### Expected Behavior

Crawl processing begins within a few seconds of submission.

#### Actual Behavior

Every crawl invocation shows 'Still waiting for crawl to start...' messages at 16-second intervals. Minimum wait was ~96 seconds (for a 3-URL crawl). The 10-URL crawl took 449 seconds (~7.5 minutes) to start. Two background crawls submitted with --background never started within the 5-minute window and timed out with 'request timeout'.

#### Root Cause Analysis

The crawl task scheduler uses a 16-second polling interval and the worker pool appears to have contention or serialization issues. New tasks queue behind existing ones even after they complete. The scheduler may not release worker threads promptly, or there may be a global lock that serializes all crawl processing.

#### Code Pointer

`browser4-rest — crawl task scheduler, worker pool, or PulsarSession/AgenticContexts lifecycle`

#### AI Suggested Improvement

- Reduce the polling interval from 16s to 2-3s for initial task pickup
- Ensure completed tasks immediately release worker threads and session resources
- Investigate whether crawl sessions share a global resource that causes serialization
- Add a --nowait or --sync flag for immediate inline execution without queue scheduling

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] High — 80–450s startup delay before any crawl processing begins, and background crawls timeout entirely. The 16s polling interval and apparent worker-pool serialization are the likely causes. Reduce initial poll interval to 2–3s, ensure completed tasks release workers promptly, and investigate whether a global lock serializes all crawl sessions. The "two background crawls never started" detail elevates this from perf to correctness.

---

### Issue 5: --format csv and --format json silently produce plain text when used without --sql

**Severity:** High
**Category:** UX

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/simple-seed.txt --depth 0 --format csv --output results.csv
./b4w.ps1 crawl --seed-file .test-sessions/simple-seed.txt --depth 0 --format json --output results.json

#### Expected Behavior

CSV output contains comma-separated values with headers. JSON output contains a JSON array of page objects.

#### Actual Behavior

Both output files contain identical human-readable plain text: 'Crawl completed. 3 pages found.' followed by pipe-separated lines. No CSV structure, no JSON structure. The --format flag is silently ignored when --sql is not provided.

#### Root Cause Analysis

The --format flag controls how X-SQL result sets are formatted into structured output. Without --sql, there is no structured result set to format, so the flag has no effect and the default plain-text page listing is emitted. This is by design but is not documented clearly enough — the flag description does not mention the --sql dependency.

#### Code Pointer

`cli/browser4-cli/src/commands.rs — crawl output formatting logic; skills/browser4-cli/references/crawl.md — --format flag documentation`

#### AI Suggested Improvement

- When --format csv/json is specified without --sql, emit structured output with default columns (url, title, content_length) from page metadata
- At minimum, emit a prominent warning: 'Warning: --format has no effect without --sql. Use --sql to produce structured output.'
- Document this dependency explicitly in the --format flag description in help output and crawl.md

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] High — `--format csv` and `--format json` silently produce plain text when `--sql` is omitted. Users reasonably expect structured output from a `--format` flag. At minimum, emit a hard-to-miss warning. Better: when `--format` is set without `--sql`, emit structured output using default page-metadata columns (url, title, content_length). Document the `--sql` dependency in `--help` and `crawl.md`.

---

### Issue 6: Diagnostic message misleading — suggests selector is wrong when backend is the root cause

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1

#### Expected Behavior

A clear error message indicating the backend failed to extract links, with suggestions to check backend health/logs.

#### Actual Behavior

Diagnostic says 'The page loaded but the CSS selector 'a' matched zero elements. Verify the selector or check that the page content loaded correctly.' and suggests using 'snapshot' or 'htmlsnapshot' to inspect the page. This implies the user's selector is wrong, when in fact the backend Jsoup pipeline is failing to parse the document at all.

#### Root Cause Analysis

The diagnostic logic (CrawlService.kt lines 525-537) assumes that if document.select(selector) returns 0 elements, the selector is the problem. It does not check whether the document itself has any DOM elements at all (e.g., checking document.select('*').size or document.select('a').size independently).

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:525-537`

#### AI Suggested Improvement

- Before concluding the selector is wrong, verify the document actually has DOM elements (check document.select('a').size or document.html.length)
- If the document has anchors but the user's selector returns 0, only then suggest the selector is wrong
- If the document has 0 anchors, report that the page parsing failed rather than blaming the selector
- Add a 'document diagnostic' section to the crawl output showing: anchors found, HTML size, base URI

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Medium — the diagnostic blames the user's selector when the real problem is that Jsoup parsed zero DOM elements at all. Fix: before suggesting the selector is wrong, check `document.select('a').size()` or `document.html.length`. If the document has zero anchors total, report a parsing failure, not a selector mismatch. This is downstream of Issue 1 but worth tracking separately — the diagnostic logic stays wrong even after the root cause is fixed.

---

### Issue 7: No crawl-specific help category works — '--help crawl' returns full help instead of filtered output

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 --help crawl

#### Expected Behavior

Filtered help output showing only crawl-related commands and flags.

#### Actual Behavior

The main help output mentions '--help crawl' as a category filter, but using it returns the full unfiltered help. The same applies to other advertised categories like '--help nav', '--help extract', etc.

#### Root Cause Analysis

The help category filtering may not be implemented in the Rust CLI, or the filter logic may not match the category names correctly. The help.rs file defines categories but the routing may not work.

#### Code Pointer

`cli/browser4-cli/src/help.rs — help category filtering logic; cli/browser4-cli/src/main.rs — --help flag handling`

#### AI Suggested Improvement

- Implement or fix the help category filtering so --help crawl shows only crawl commands
- Verify all advertised categories (nav, extract, session, kb, agent, swarm, crawl) work
- Add 'crawl --help' as a subcommand-specific help that shows crawl flags in detail

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Medium — `--help crawl` returns full unfiltered help; same for all advertised categories. The help category routing is either unimplemented or the filter keys don't match. Fix the filter logic so each category shows only its own commands, and add `crawl --help` as a subcommand-specific help path.

---

### Issue 8: Goto auto-redirects HTTP to HTTPS without clear indication in output

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 goto 'http://books.toscrape.com/'

#### Expected Behavior

Clear indication when a redirect occurs, showing original URL and final URL.

#### Actual Behavior

The output says 'Navigated to https://books.toscrape.com/ (redirected from http://books.toscrape.com/)' which does mention the redirect, but this is embedded in the snapshot block and easy to miss. The crawl command silently follows redirects without any indication.

#### Root Cause Analysis

The goto output format includes redirect info as a parenthetical note but it could be more prominent.

#### Code Pointer

`cli/browser4-cli/src/ — goto command output formatting`

#### AI Suggested Improvement

- Add a clear redirect notice at the top of the goto output, not just as a parenthetical
- Ensure crawl command output also indicates when redirects are followed
- Consider a --no-redirect flag for crawl to prevent following redirects

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Low — redirect info is present but easy to miss in goto output, and entirely absent from crawl output. Surface redirects more prominently (dedicated notice line for goto; log entry for crawl). The suggested `--no-redirect` flag is a separate feature request; tackle the visibility issue first.

---

### Issue 9: Crawl list shows truncated descriptions instead of useful command summaries

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 crawl list

#### Expected Behavior

The DESCRIPTION column shows the full URL or seed file path, making it easy to identify what each crawl task did.

#### Actual Behavior

The DESCRIPTION column truncates long URLs with '...' (e.g., 'https://books.toscrape.com/catalogue/a-…'). This makes it hard to distinguish between different crawl tasks at a glance.

#### Root Cause Analysis

The crawl list output truncates the description field to a fixed width for table formatting.

#### Code Pointer

`cli/browser4-cli/src/ — crawl list output formatting`

#### AI Suggested Improvement

- Use the first seed URL or seed file path as the description instead of the full URL list
- Allow --verbose or --full flag to show untruncated descriptions
- Add a 'seed count' column showing how many URLs were crawled

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Low — truncated descriptions make `crawl list` hard to use at a glance. Show the first seed URL or seed file path instead of a truncated concatenation; add a seed-count column; support `--verbose` for full URLs. Straightforward formatting fix.

---

## Overall Assessment

**Completion Status:** Partially Successful — The seed-file bulk fetch crawl mode works (with significant caveats). The primary link-discovery workflow (depth >= 1 with --out-link-selector) is completely non-functional due to backend bugs. Output format flags (--format csv/json) do not work without --sql. Crawl list and cancel work correctly.

**Success Rate:** 40% — 2 of 5 task steps fully succeeded (seed file crawl, crawl list); 3 steps were either blocked or produced incorrect output (link discovery, CSV format, JSON format)

**Issues Found:** 9

**Major Blockers:** 1) Crawl link discovery returns 0 elements for ALL CSS selectors — the core depth>=1 crawl feature is completely broken. 2) Protocol handler returns 0 bytes for 90% of pages beyond the first in bulk fetch mode, limiting practical multi-page crawls. 3) --format csv/json silently produces plain text without --sql, making output-format workflows impossible for page-listing crawls.

**Most Confusing Aspects:** For a first-time user: (a) The --out-link-selector diagnostic blames the user's CSS selector when the backend is the real problem — hours could be wasted trying different selectors. (b) --format csv/json produce identical text output with no warning — silently broken. (c) The 80-450 second startup delay feels like a hang with no progress indicators. (d) The help system advertises '--help crawl' as a category filter but it doesn't actually filter. (e) Goto and htmlsnapshot find elements correctly, but crawl's link discovery uses a completely different (broken) pipeline — the inconsistency is baffling.

**Most Valuable Improvements:** 1) Fix link discovery so depth >= 1 crawl works — this is the crawl command's documented primary use case. 2) Fix the protocol handler lifecycle so multi-page crawls are reliable. 3) Make --format emit a clear warning when used without --sql, or auto-generate structured output from page metadata. 4) Reduce the 16s polling interval to 2-3s for crawl startup. 5) Improve diagnostic accuracy — check if the document has ANY elements before blaming the CSS selector.

**Usability Rating:** 3/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Crawl link discovery returns 0 elements for all CSS selectors — depth >= 1 completely broken

./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1 --top-links 5 --refresh --format table

#### Issue 2: Crawl bulk fetch fails with 'protocol handler not ready' for most pages beyond the first

./b4w.ps1 crawl --seed-file .test-sessions/book-details-seed.txt --depth 0 --format table
(seed file with 10 book detail URLs from books.toscrape.com)

#### Issue 3: Crawl page titles empty for book detail pages even when fetched successfully

./b4w.ps1 crawl --seed-file .test-sessions/simple-seed.txt --depth 0
(seed file includes https://books.toscrape.com/catalogue/a-light-in-the-attic_1000/index.html)

#### Issue 4: Crawl startup delay — consistently 80-450 seconds before processing begins

Any crawl invocation: ./b4w.ps1 crawl --seed-file <file> --depth 0 --format table

#### Issue 5: --format csv and --format json silently produce plain text when used without --sql

./b4w.ps1 crawl --seed-file .test-sessions/simple-seed.txt --depth 0 --format csv --output results.csv
./b4w.ps1 crawl --seed-file .test-sessions/simple-seed.txt --depth 0 --format json --output results.json

#### Issue 6: Diagnostic message misleading — suggests selector is wrong when backend is the root cause

./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1

#### Issue 7: No crawl-specific help category works — '--help crawl' returns full help instead of filtered output

./b4w.ps1 --help crawl

#### Issue 8: Goto auto-redirects HTTP to HTTPS without clear indication in output

./b4w.ps1 goto 'http://books.toscrape.com/'

#### Issue 9: Crawl list shows truncated descriptions instead of useful command summaries

./b4w.ps1 crawl list

