# Issues: crawl-link-discovery

> **Source:** `20260729-070839-crawl-link-discovery.full.md` | **Date:** 20260729-070839 | **Mode:** dev

## Scenario Background

### Task

**Partially Successful.** The crawl command's core features — link discovery, page content fetching, and X-SQL extraction — are all broken. Only administrative commands (`crawl list`, `crawl status`, `crawl clear`) and seed-file argument parsing function correctly. The actual crawl task goals were blocked by backend bugs.

### Execution Context

**Commands executed:** `help`, `goto`, `htmlsnapshot`, `eval`, `crawl` (5× with various `--out-link-selector` values), `crawl --seed-file` (6× with various formats), `crawl list`, `crawl status`, `crawl clear`, `htmlsnapshot get all`.

**Workarounds:** (1) Used `eval` JavaScript to manually extract product URLs instead of `--out-link-selector`. (2) Used `--json` global flag to see actual crawl errors hidden by human-readable output. (3) Accepted that `--format` flags are non-functional without `--sql`.

**Key discovery:** An existing issues report from yesterday (20260728-153336) documents the same bugs with ACCEPT verdicts — they remain unfixed. The protocol-handler bug has **worsened**: yesterday 1 of 10 pages loaded; today 0 of 10.

---

## C & D: Issues Found & Overall Assessment

```j...

(truncated — see full.md for complete trace)

---

## Issues Found (9 issues)

### Issue 1: Crawl link discovery returns 0 elements for ALL CSS selectors — depth >= 1 completely broken

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1 --top-links 5 --refresh --format table

#### Expected Behavior

Links matching the 'a' CSS selector extracted from the portal page (~94 links visible via htmlsnapshot).

#### Actual Behavior

Crawl completes with 0 pages found. Diagnostic: 'The page loaded but the CSS selector 'a' matched zero elements.' Multiple selectors tested ('a', 'h3 a', 'a[href*="catalogue"]') all produce 0 results despite 94 anchors confirmed via htmlsnapshot and eval.

#### Root Cause Analysis

The crawl link-discovery path uses a Jsoup fetch/parse pipeline (session.loadDocument() → document.select(selector)) that differs from the browser-based DOM used by htmlsnapshot/goto. The Jsoup pipeline either fails to fetch the page or produces a document model where selectors don't match. The appendSelectorIfMissing() function in LoadOptions.kt may also corrupt selectors. Previously reported 2026-07-28, ACCEPTed, still unfixed.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:extractOutLinks() ~line 780; browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/options/LoadOptions.kt:correctOutLinkSelector() ~line 988`

#### AI Suggested Improvement

- Investigate why Jsoup's document.select() returns 0 results when browser DOM shows 94 anchors — the fetch/parse pipeline may not be rendering the page correctly
- Debug appendSelectorIfMissing() in pulsar-dom external JAR to confirm it does not corrupt CSS selectors
- Add integration test verifying link discovery on books.toscrape.com (94 expected links)
- When link discovery returns 0 results, check document.select('*').size and document.html.length before blaming the selector

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Critical, reproducible bug — crawl link discovery is completely non-functional at depth >= 1. Jsoup pipeline diverges from browser DOM, and `appendSelectorIfMissing()` may corrupt selectors. Root cause is clear; fix should verify `document.select('*').size()` before blaming the selector (see Issue 6).

---

### Issue 2: Crawl fetch returns 0 bytes for ALL pages — 'protocol handler not ready' on every single URL

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 --json crawl --seed-file .test-sessions/books-seed.txt --depth 0 (10 book detail URLs from books.toscrape.com)

#### Expected Behavior

All 10 pages load with content (12KB+ each) and titles.

#### Actual Behavior

All 10 pages return contentLength: 0 with extractionError: 'fetch returned 0 bytes (possible protocol handler not ready)'. Text-mode output silently reports 'Crawl completed. 10 pages found.' with empty titles — misleading the user. JSON mode reveals the truth. This has worsened since yesterday when 1 of 10 pages worked.

#### Root Cause Analysis

The HTTP protocol handler used for page fetching (likely OkHttp or equivalent) is never properly initialized or crashes before any request. The error 'protocol handler not ready' suggests the handler lifecycle is not managed: it may be a singleton that fails to initialize, or a per-thread resource that isn't set up for crawl worker threads. Previously reported 2026-07-28, ACCEPTed, still unfixed and now affects ALL pages (was partial before).

#### Code Pointer

`browser4-core — FetchComponent or protocol handler initialization and lifecycle management`

#### AI Suggested Improvement

- Investigate protocol handler lifecycle — ensure proper initialization before first fetch in crawl context
- Add retry logic for 'protocol handler not ready' errors (at least 3 retries with exponential backoff)
- Consider instantiating a fresh protocol handler per request rather than sharing a singleton
- Add a pre-flight health check before each crawl to verify the handler is ready, failing fast with a clear error

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Critical, reproducible — protocol handler is never initialized or crashes before any crawl fetch, rendering ALL pages as 0 bytes. Has worsened from partial failure to total failure since 2026-07-28. The handler lifecycle bug may be the same root cause as Issue 1 if both rely on the same HTTP fetch layer.

---

### Issue 3: Crawl --sql flag returns 'No extracted data' — X-SQL extraction through crawl is non-functional

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --sql @.test-sessions/extract-books.sql --format table (query uses load_and_select(@url, 'body') with dom_first_text for title, price, availability)

#### Expected Behavior

Structured table output with url, title, price, and availability columns from 10 book detail pages.

#### Actual Behavior

'No extracted data.' — crawl completes but returns zero rows. Known from memory as: 'CrawlToolExecutor drops sql/urls params (critical), and load_and_select needs cached pages (high)'.

#### Root Cause Analysis

CrawlToolExecutor drops or mishandles the --sql and URL parameters passed to the X-SQL query engine. Additionally, load_and_select may require pages to be pre-cached (via htmlsnapshot capture or prior fetch), which the crawl pipeline doesn't do. Previously documented, still unfixed.

#### Code Pointer

`browser4-rest — CrawlToolExecutor SQL parameter handling; browser4-core — load_and_select cache dependency`

#### AI Suggested Improvement

- Fix CrawlToolExecutor to correctly forward --sql and URL parameters to the X-SQL engine
- Remove or document the load_and_select cached-page dependency — either auto-cache pages before query, or switch to a fetch-on-demand model
- Add a clear error message when --sql fails, distinguishing 'query syntax error' from 'parameter forwarding failure' from 'page not cached'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Critical — X-SQL crawl extraction is entirely non-functional. Root cause is confirmed in project memory: `CrawlToolExecutor` drops `sql`/`urls` params, and `load_and_select` requires pre-cached pages. Fixing this unblocks the structured output path that Issue 4 depends on.

---

### Issue 4: --format csv and --format json silently produce plain text when used without --sql

**Severity:** High
**Category:** UX

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --format csv --output results.csv

#### Expected Behavior

CSV output contains comma-separated values with headers, or at minimum a loud warning that --format requires --sql.

#### Actual Behavior

Output file contains human-readable plain text: 'Crawl completed. 10 pages found.' followed by pipe-separated lines. No CSV structure. The --format flag is silently ignored without --sql. Previously reported 2026-07-28, ACCEPTed, still unfixed.

#### Root Cause Analysis

--format controls how X-SQL result sets are formatted. Without --sql, there is no structured result set, so the flag is a no-op. The design is intentional but the flag documentation doesn't mention the --sql dependency, and no warning is emitted.

#### Code Pointer

`cli/browser4-cli/src/commands.rs — crawl output formatting logic; skills/browser4-cli/references/crawl.md — --format flag documentation`

#### AI Suggested Improvement

- When --format csv/json is specified without --sql, emit structured output from page metadata (url, title, content_length) as default columns
- At minimum, emit a prominent warning: 'Warning: --format has no effect without --sql. Use --sql to produce structured output.'
- Document the --sql dependency explicitly in the --format flag description in help output and crawl.md

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid UX defect — `--format csv|json` is silently ignored without `--sql`, misleading users. At minimum, emit a warning. The suggested improvement (auto-structure page metadata as default columns) is reasonable and would make `--format` useful standalone. Cross-cuts with Issue 3 — fixing `--sql` makes this less severe but doesn't eliminate the no-op-silence problem.

---

### Issue 5: Crawl startup delay — consistently 80-100 seconds before processing begins

**Severity:** High
**Category:** Reliability

#### Reproduction

Any crawl invocation with seed file (3-10 URLs).

#### Expected Behavior

Crawl processing begins within a few seconds of submission.

#### Actual Behavior

Every crawl shows 'Still waiting for crawl to start...' at 16-second intervals. Minimum wait was ~80 seconds for a 3-URL crawl. The 10-URL crawl with --sql took ~100 seconds. Previously reported 2026-07-28, ACCEPTed, still present.

#### Root Cause Analysis

The crawl task scheduler uses a 16-second polling interval and the worker pool has contention or serialization issues. New tasks queue behind existing ones even after they complete.

#### Code Pointer

`browser4-rest — crawl task scheduler, worker pool, or session lifecycle`

#### AI Suggested Improvement

- Reduce the polling interval from 16s to 2-3s for initial task pickup
- Ensure completed tasks immediately release worker threads and session resources
- Investigate whether crawl sessions share a global lock that causes serialization
- Add a progress indicator during the waiting period (e.g., 'Queue position: 3')

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] High-impact UX — 80-100s startup delay makes crawl feel broken. 16s polling interval is too coarse; reducing to 2-3s for initial pickup is a low-risk fix. May also relate to Issue 2 (protocol handler init happening during this wait window and failing).

---

### Issue 6: Diagnostic message misleading — blames user's CSS selector when backend parsing is the root cause

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1

#### Expected Behavior

Clear error indicating the backend failed to parse the page, with actionable debugging guidance.

#### Actual Behavior

Diagnostic says 'The page loaded but the CSS selector 'a' matched zero elements. Verify the selector or check that the page content loaded correctly.' It suggests trying 'snapshot' or 'htmlsnapshot' — implying the user's selector is wrong when the page clearly has 94 anchors via htmlsnapshot. Wastes user time trying different selectors. Previously reported 2026-07-28, ACCEPTed.

#### Root Cause Analysis

CrawlService.kt diagnostic logic assumes document.select(selector) returning 0 means the selector is the problem. It never checks whether the document itself has any DOM elements at all.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:~525-537`

#### AI Suggested Improvement

- Before blaming the selector, verify the document has DOM elements: check document.select('a').size or document.html.length
- If document has 0 anchors total, report a PARSE FAILURE, not a 'selector wrong' message
- Add a 'document diagnostic' section: anchors found, HTML size, base URI

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Cross-cuts with Issue 1 — the diagnostic blames the user's selector when the Jsoup document itself is empty. Fixing Issue 1's root cause eliminates the false blame, but the diagnostic should still verify document health before suggesting the user try different selectors. The proposed `document.select('a').size` / `document.html.length` pre-check is the right approach.

---

### Issue 7: htmlsnapshot get all fails with internal error 'Unsupported html_snapshot method: scrapeAll'

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 goto 'https://books.toscrape.com/' → ./b4w.ps1 htmlsnapshot → ./b4w.ps1 htmlsnapshot get all text 'a' --limit 5

#### Expected Behavior

Array of text content from first 5 anchor elements on the page.

#### Actual Behavior

ERROR: html_snapshot_scrape_all failed: Unsupported html_snapshot method: scrapeAll. Even after explicitly running `htmlsnapshot` first as suggested by the error tip, the same error persists.

#### Root Cause Analysis

The 'get all' subcommand uses an internal method called 'scrapeAll' which is not implemented or registered in the backend's htmlsnapshot method dispatcher. The error message suggests running 'htmlsnapshot' first, but this doesn't help since the method itself is missing.

#### Code Pointer

`browser4-rest — htmlsnapshot method dispatcher (scrapeAll method registration); browser4-core — HtmlSnapshot component`

#### AI Suggested Improvement

- Implement the scrapeAll method in the htmlsnapshot backend, or fix the method routing to use the correct implementation
- If scrapeAll is intentionally unsupported, replace the error with a clear deprecation/unsupported notice pointing to the working alternative (eval with querySelectorAll)
- Update the 'htmlsnapshot' tips section which currently recommends 'get all' as a working command

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Real bug — `scrapeAll` method is not registered in the backend dispatcher. The error message's suggestion to "run htmlsnapshot first" is misleading since the method simply doesn't exist. Should either implement the method or replace with a clear unsupported-feature error pointing to the `eval` alternative.

---

### Issue 8: Crawl text-mode output silently hides 0-byte fetch errors — user sees success when all pages failed

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --format table

#### Expected Behavior

Clear indication that pages failed to load (0 bytes, protocol handler errors).

#### Actual Behavior

Output shows 'Crawl completed. 10 pages found.' with a list of URLs and empty title columns. No mention of fetch errors. Only the JSON output (via --json flag) reveals 'contentLength: 0' and 'extractionError: fetch returned 0 bytes' for every page. A user relying on text output would believe the crawl succeeded.

#### Root Cause Analysis

The human-readable output formatter only shows the page listing; extraction errors are suppressed. The success message 'X pages found' counts pages that failed to load alongside working ones — 'found' is ambiguous.

#### Code Pointer

`cli/browser4-cli/src/commands.rs — crawl output formatting`

#### AI Suggested Improvement

- Show a warning summary in text output when pages have errors: '10 pages processed (10 had fetch errors)'
- Distinguish 'pages found' from 'pages successfully loaded' in the completion message
- Add --verbose flag to show per-page error details in text mode
- Make 0-byte fetches more visible in the listing (e.g., 'ERROR' instead of empty title)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Cross-cuts with Issue 2 — the text output formatter suppresses extraction errors, making a 100% failure crawl appear successful. Fixing Issue 2 eliminates the underlying errors, but the output layer should still surface errors independently. The proposed warning summary and "pages processed (N had errors)" distinction is sound.

---

### Issue 9: Crawl list shows truncated descriptions instead of useful command summaries

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 crawl list (after running multiple crawls)

#### Expected Behavior

DESCRIPTION column shows the first seed URL or seed file path, or URL count — enough to identify each task.

#### Actual Behavior

DESCRIPTION column truncates long URLs with '...' (e.g., 'https://books.toscrape.com/catalogue/a-…'). The seed-file crawls show the first URL from the file rather than the seed file path or count, making tasks indistinguishable.

#### Root Cause Analysis

The crawl list output truncates description to a fixed width. For seed-file crawls, the description is the concatenated URL list rather than the file path or count.

#### Code Pointer

`cli/browser4-cli/src/ — crawl list output formatting`

#### AI Suggested Improvement

- Show seed file path (or 'N URLs' for inline URLs) as description rather than the first URL
- Add a 'URLs' column showing count
- Support --verbose flag to show untruncated descriptions

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid UX improvement — seed-file crawl descriptions are indistinguishable when they all show the first URL truncated. Showing seed file path or URL count instead of first URL is a straightforward fix. Low severity but high impact for users managing multiple crawls.

---

## Overall Assessment

**Completion Status:** Partially Successful — The crawl command's core features (link discovery, page fetching, X-SQL extraction, structured output) are all broken by unfixed backend bugs. Only administrative commands (crawl list, status, clear) and seed-file argument parsing work correctly. The task goals requiring link discovery, content extraction, and formatted output were blocked. Seed-file crawling, crawl list/status/clear, and the workaround-based data extraction path (goto → eval → manual seed file) were the only functioning workflows.

**Success Rate:** 20% — 1 of 5 task steps fully succeeded (crawl list). Seed-file creation worked but crawl couldn't fetch content. Link discovery, CSV/JSON output, and X-SQL extraction all failed due to backend bugs.

**Issues Found:** 9

**Major Blockers:** Three Critical bugs make crawl unusable: (1) Link discovery returns 0 elements for all CSS selectors — the core depth>=1 crawl feature is completely broken. (2) Protocol handler returns 0 bytes for ALL pages — not a single page loaded successfully. (3) --sql X-SQL extraction returns 'No extracted data' — structured extraction through crawl is non-functional. Combined, these mean crawl can neither discover links, load pages, nor extract data — the three fundamental crawl operations are all broken.

**Most Confusing Aspects:** For a first-time user: (a) The diagnostic blames your CSS selector when the backend is the problem — hours could be wasted trying different selectors. (b) Text output says '10 pages found' when all 10 pages have 0 bytes and fetch errors — only the --json flag reveals the truth. (c) --format csv/json produce identical plain-text output with no warning. (d) Goto and htmlsnapshot work fine on the same page, but crawl can't find a single link — the inconsistency is baffling. (e) The 80-100 second startup delay feels like a hang with no useful progress indicator.

**Most Valuable Improvements:** 1) Fix link discovery so depth>=1 crawl works — this is crawl's documented primary use case. 2) Fix the protocol handler lifecycle so pages actually load. 3) Fix --sql parameter forwarding in CrawlToolExecutor. 4) Make --format emit a clear warning when used without --sql, or generate structured output from page metadata. 5) Show fetch errors in text-mode output — don't silently report success when pages have 0 bytes. 6) Reduce the 16s polling interval to 2-3s. 7) Fix htmlsnapshot get all's 'scrapeAll' internal error.

**Usability Rating:** 2/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Crawl link discovery returns 0 elements for ALL CSS selectors — depth >= 1 completely broken

./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1 --top-links 5 --refresh --format table

#### Issue 2: Crawl fetch returns 0 bytes for ALL pages — 'protocol handler not ready' on every single URL

./b4w.ps1 --json crawl --seed-file .test-sessions/books-seed.txt --depth 0 (10 book detail URLs from books.toscrape.com)

#### Issue 3: Crawl --sql flag returns 'No extracted data' — X-SQL extraction through crawl is non-functional

./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --sql @.test-sessions/extract-books.sql --format table (query uses load_and_select(@url, 'body') with dom_first_text for title, price, availability)

#### Issue 4: --format csv and --format json silently produce plain text when used without --sql

./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --format csv --output results.csv

#### Issue 5: Crawl startup delay — consistently 80-100 seconds before processing begins

Any crawl invocation with seed file (3-10 URLs).

#### Issue 6: Diagnostic message misleading — blames user's CSS selector when backend parsing is the root cause

./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1

#### Issue 7: htmlsnapshot get all fails with internal error 'Unsupported html_snapshot method: scrapeAll'

./b4w.ps1 goto 'https://books.toscrape.com/' → ./b4w.ps1 htmlsnapshot → ./b4w.ps1 htmlsnapshot get all text 'a' --limit 5

#### Issue 8: Crawl text-mode output silently hides 0-byte fetch errors — user sees success when all pages failed

./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --format table

#### Issue 9: Crawl list shows truncated descriptions instead of useful command summaries

./b4w.ps1 crawl list (after running multiple crawls)

