# Issues: crawl-advanced-extraction

> **Source:** `20260729-065156-crawl-advanced-extraction.full.md` | **Date:** 20260729-065156 | **Mode:** dev

## Scenario Background

### Task

The task was partially successful. All 9 steps were executed, but the core X-SQL data extraction via `crawl --sql` returned empty or inconsistent results due to known bugs (documented in project memory).

**Extraction that worked:**
- `htmlsnapshot query` correctly extracted all three products on the first attempt (before cache state interfered)
- `crawl --sql` with pre-loaded pages extracted 2 of 3 products (B0E000002: "Wireless Noise-Cancelling Headphones" / $199.99 and B0E000003: "Portable Bluetooth Speaker" / $49.99)
- B0E000001 (4K OLED TV 55 / $899.99) was successfully extracted via `htmlsnapshot query` on the first attempt

**Key workaround required:** CSS selectors needed adjustment from class selectors (`.price`, `.title`) to ID selectors (`#product-price`, `#productTitle`) after inspecting MockSite's actual HTML.

### Execution Context

| Step | Command | Outcome |
|------|---------|---------|
| 0 | `cd D:/workspace/Browser4/Browser4-4.12` | Verified repo root |
| 1 | `./b4w.ps1 help` | Success — comprehensive help output |
| 2 | Read `SKILL.md` + references (`crawl.md`, `x-sql.md`) | Success — good documentation |
| Prep | `./bin/test.ps1 mock-site` | **Failed** — MockServerPorts compilation error |
| Fix | `mvn install -pl browser4-tests/pulsar-tests-common -am -DskipTests` | Fixed dependency |
| Prep | `./bin/test.ps1 mock-site -Dmock.site.port=18080` | Success — MockSite on port 18080 |
| 1 | Created `.test-sessions/seed-urls.txt` with 3 URLs | Success |
| 2 | Created `.test-sessions/extract.sql` | Success (v2: fixed selectors) |
| 3 | `crawl --seed-file ... --depth 0 --sql @file --refresh --parse --expires 1h --prior...

(truncated — see full.md for complete trace)

---

## Issues Found (11 issues)

### Issue 1: crawl --sql returns empty extracted data due to UDF cache mismatch

**Severity:** Critical
**Category:** Product

#### Reproduction

browser4-cli crawl --seed-file urls.txt --depth 0 --sql @extract.sql --refresh

#### Expected Behavior

Each crawled page's extracted data (title, price) appears in the result table/JSON.

#### Actual Behavior

All extracted fields are empty strings. Pages are fetched (3 pages found, contentLength shown) but DOM_LOAD_AND_SELECT in the X-SQL UDF returns no data unless pages were pre-loaded via goto+htmlsnapshot.

#### Root Cause Analysis

The H2 UDF backing DOM_LOAD_AND_SELECT reads from the WebDB page cache, but crawl's internal session.load() does not populate that same cache. This is documented in project memory as Bug #2 (crawl-x-sql-bugs-2026-07-27). The CrawlToolExecutor may also drop sql/urls params (Bug #1).

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/CrawlToolExecutor.kt`

#### AI Suggested Improvement

- Fix CrawlToolExecutor to preserve sql and urls params in CrawlRequest
- Ensure crawl session.load() populates the same WebDB cache that the X-SQL UDF reads from, or make DOM_LOAD_AND_SELECT fall back to direct HTTP fetch when cache miss
- Add an integration test that verifies crawl --sql extraction returns non-empty data for a known page

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed critical bug — already documented in project memory as Bugs #1 (CrawlToolExecutor drops sql/urls params) and #2 (UDF cache mismatch). This is the root cause blocking crawl --sql extraction. Fixing this also reduces the severity of Issues 7 and 9.

---

### Issue 2: htmlsnapshot query results are inconsistent — same query returns data then 417 Expectation Failed

**Severity:** High
**Category:** Reliability

#### Reproduction

1. goto product page 2. htmlsnapshot 3. htmlsnapshot query with --sql @file — first attempt returns data with statusCode 200, second attempt returns statusCode 417 with resultSet:[]

#### Expected Behavior

Consistent results for the same query against the same page.

#### Actual Behavior

First query: statusCode 200, resultSet:[{title:'4K OLED TV 55',price:'$899.99'}]. Second query (after navigating away and back): statusCode 417 Expectation Failed, resultSet:[].

#### Root Cause Analysis

Appears to be a cache-state dependent issue — the query succeeds when the page was freshly loaded by goto but fails on subsequent attempts. The htmlsnapshot command itself may evict or alter the cache entry. Needs investigation into how DOM_LOAD_AND_SELECT resolves @url in the context of htmlsnapshot query vs crawl.

#### AI Suggested Improvement

- Investigate why DOM_LOAD_AND_SELECT succeeds on first call but returns 417 on subsequent calls for the same URL
- Ensure htmlsnapshot capture does not interfere with subsequent htmlsnapshot query calls
- Add deterministic cache behavior so repeated queries produce consistent results

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Likely shares root cause with Issue 1's cache-population path — DOM_LOAD_AND_SELECT resolves differently after htmlsnapshot capture. The 417 on second attempt is a distinct reliability symptom worth tracking separately from Issue 1, even if the same underlying fix resolves both. Investigate whether htmlsnapshot capture is evicting or marking the cache entry stale.

---

### Issue 3: MockSite fails to start from source due to missing MockServerPorts dependency

**Severity:** High
**Category:** Reliability

#### Reproduction

./bin/test.ps1 mock-site on a fresh checkout

#### Expected Behavior

MockSite compiles and starts without manual intervention.

#### Actual Behavior

Compilation fails with 'Unresolved reference: MockServerPorts' in 4 test files. Requires manual `mvn install -pl browser4-tests/pulsar-tests-common -am -DskipTests` before mock-site can start.

#### Root Cause Analysis

pulsar-tests-common (which contains MockServerPorts) is a compile-scope dependency of browser4-rest-tests but is not auto-installed to the local Maven repo when building only browser4-rest-tests via test.ps1 mock-site.

#### Code Pointer

`bin/test.ps1:Invoke-MockSiteBoot()`

#### AI Suggested Improvement

- Add `-am` (also-make) flag or a pre-build step to Invoke-MockSiteBoot that ensures pulsar-tests-common is compiled and installed before building browser4-rest-tests
- Or add a `mvn install -pl browser4-tests/pulsar-tests-common -DskipTests` step to the mock-site launch sequence

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid blocker for new-contributor onboarding. The suggested fix should add `-am` directly to the existing Maven invocation in Invoke-MockSiteBoot rather than as a separate pre-build step — simpler and avoids an extra Maven resolve cycle. If `-am` has undesirable side effects (rebuilding too much of the tree), fall back to the explicit `mvn install -pl browser4-tests/pulsar-tests-common -DskipTests` pre-step.

---

### Issue 4: MockSite starts on random port by default, not the documented 18080

**Severity:** Medium
**Category:** UX

#### Reproduction

./bin/test.ps1 mock-site (without port flag)

#### Expected Behavior

MockSite starts on port 18080, the commonly referenced port in documentation and seed files.

#### Actual Behavior

MockSite starts on a random port (e.g. 51983). The user must discover and add -Dmock.site.port=18080 manually.

#### Root Cause Analysis

test.ps1 defaults mockSitePort to 18080 for port-conflict checking but passes --server.port=0 to Spring Boot unless -Dmock.site.port is explicitly provided.

#### Code Pointer

`bin/test.ps1:460 (mockSitePort = 18080) vs line 530-534 (only passes --server.port if -Dmock.site.port JVM arg is present)`

#### AI Suggested Improvement

- Default --server.port to 18080 when no -Dmock.site.port override is given
- Or print a prominent message showing the actual port the server is listening on

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The script already tracks mockSitePort=18080 for conflict detection but passes --server.port=0 (random) to Spring Boot. The fix is a one-line change: default --server.port to $mockSitePort when no -Dmock.site.port override is present. Consistent with the documented port everywhere else.

---

### Issue 5: @ file path syntax causes PowerShell parser errors without quoting

**Severity:** Medium
**Category:** UX

#### Reproduction

browser4-cli crawl --sql @.test-sessions/extract.sql (without quotes in PowerShell)

#### Expected Behavior

The @file syntax is parsed correctly regardless of shell.

#### Actual Behavior

PowerShell parser error: 'Unrecognized token in source text' at the @ character.

#### Root Cause Analysis

In PowerShell, @ is a splatting operator. When an unquoted argument starts with @, pwsh tries to interpret it as a splat variable. This contradicts the SKILL.md recommendation to use --sql @file.sql for Windows.

#### AI Suggested Improvement

- Update SKILL.md shell-quoting documentation to warn about @ prefix in PowerShell specifically
- Document the quoting workaround: --sql "@path/to/file.sql"
- Consider supporting an alternative file-reference syntax that avoids shell-special characters (e.g. --sql-file path/to/file.sql)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Confirmed — shell-quoting.md recommends @file for Windows but never mentions PowerShell's @ splatting operator. The improvement should: (1) add a PowerShell-specific warning to shell-quoting.md that unquoted @args are interpreted as splat variables, (2) document `--sql "@path"` quoting workaround, (3) evaluate adding `--sql-file` as a splat-safe alternative (separate flag, no @ prefix needed). The third item is good follow-up work, not required for the doc fix.

---

### Issue 6: Crawl task list shows accumulated stale tasks with auto-cleanup message

**Severity:** Medium
**Category:** UX

#### Reproduction

Run crawl list after multiple crawl operations across sessions.

#### Expected Behavior

Clean task listing showing only current-session tasks, or clear separation of active vs historical tasks.

#### Actual Behavior

Shows 'Cleaned up 112 stale crawl task(s) — server no longer has them.' then lists 6+ tasks including ones from 12 hours ago. The auto-cleanup message is noisy and indicates the task store accumulates garbage.

#### Root Cause Analysis

The CLI's local task store retains completed tasks indefinitely. Stale tasks are only cleaned when the server reports they're gone (triggered by crawl list). No automatic TTL-based cleanup on the CLI side.

#### Code Pointer

`cli/browser4-cli/src/ (task tracking/listing logic)`

#### AI Suggested Improvement

- Add a configurable TTL for local task tracking entries (e.g. 24h auto-expiry)
- Periodically prune completed tasks older than TTL without waiting for crawl list
- Show a less alarming message (or no message) for routine cleanup, and reserve warnings for actual errors

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid UX issue but TTL-based cleanup may be overengineered for this scale. Simpler approach: (1) prune local task store entries older than 24h on startup (no TTL infrastructure needed), (2) downgrade the cleanup message from warning to debug/info level — "Cleaned up 112 stale tasks" reads as something broke when it's routine housekeeping. The message should only surface when there's an anomaly (e.g. tasks not found on server that should exist).

---

### Issue 7: Empty crawl extraction results are silent — no error or warning

**Severity:** Medium
**Category:** UX

#### Reproduction

Run crawl --sql with selectors that don't match page content.

#### Expected Behavior

A warning that X-SQL extraction returned 0 results, or an indication that the query may need adjustment.

#### Actual Behavior

Table output shows column headers with blank rows. No indication of failure. The user must infer that either the page has no matching elements or the query failed silently.

#### Root Cause Analysis

The crawl result formatter renders empty strings for null/missing extraction values without distinguishing between 'query executed but found nothing' and 'query failed to execute.'

#### Code Pointer

`browser4-rest/ (crawl result formatting logic)`

#### AI Suggested Improvement

- Emit a warning when all rows have empty extracted fields (e.g. 'X-SQL returned 3 rows with all fields empty — check your selectors')
- Distinguish between null (query error) and empty string (found nothing) in output
- Show a summary line like '3 pages crawled, 0 fields extracted'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] This is partially a symptom of Issue 1, but the UX concern remains valid even after the cache bug is fixed — selectors can legitimately not match, and the user gets no feedback. The fix should distinguish three states: (a) query executed, found 0 matching elements → "0 fields extracted — check your selectors", (b) query failed to execute → error, (c) query succeeded with data → normal output. A summary footer line ("N pages crawled, M fields extracted") would help regardless.

---

### Issue 8: SKILL.md crawl examples use Amazon selectors that don't apply to MockSite

**Severity:** Low
**Category:** Documentation

#### Reproduction

Follow the crawl.md 'Bulk product detail extraction' example with MockSite pages.

#### Expected Behavior

Documentation provides guidance on discovering selectors for any site, not just Amazon-specific examples.

#### Actual Behavior

All examples use Amazon-specific selectors (#productTitle, .a-price, #acrCustomerReviewText). A first-time user testing with MockSite gets empty results and no guidance on why.

#### Root Cause Analysis

The crawl.md examples are Amazon-centric. While the SKILL.md warns 'CSS selectors are tied to live websites', it doesn't show MockSite-specific examples that users can test against.

#### Code Pointer

`skills/browser4-cli/references/crawl.md:190-205`

#### AI Suggested Improvement

- Add a MockSite-specific crawl example that users can run immediately without external dependencies
- Include a note about using htmlsnapshot inspect to discover selectors for unknown pages
- Add a 'Testing locally' section showing the MockSite workflow

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Amazon-only examples leave new users without a way to verify the tool works locally. Adding a MockSite-specific section in crawl.md with the actual MockSite selectors (#productTitle, #product-price) gives users an immediate success path. Also worth cross-referencing htmlsnapshot inspect as the selector-discovery workflow.

---

### Issue 9: crawl --help does not mention known limitations with --sql extraction

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run browser4-cli crawl --help.

#### Expected Behavior

Help text mentions that --sql extraction requires pre-cached pages or has known issues.

#### Actual Behavior

No mention of limitations. The help presents --sql as a straightforward feature without caveats.

#### Root Cause Analysis

Help text is generated from the CLI flag definitions, which don't include caveats or known issues.

#### Code Pointer

`cli/browser4-cli/src/ (crawl command definition)`

#### AI Suggested Improvement

- Add a note in the --sql help text: 'Note: extraction requires pages to be in WebDB cache. Pre-load with goto+htmlsnapshot if results are empty.'
- Or fix the underlying bug so the caveat is unnecessary

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Documenting known bugs in --help text is an anti-pattern — it trains users to distrust the tool's own help output. The right fix is to resolve Issue 1 (the underlying bug). If Issue 1 can't be fixed in the near term, a note in a known-issues doc or SKILL.md caveats section is more appropriate than polluting --help. Revisit only if Issue 1 is WONTFIXed.

---

### Issue 10: No visual feedback during blocking crawl execution

**Severity:** Low
**Category:** UX

#### Reproduction

Run a blocking crawl with 3 URLs.

#### Expected Behavior

Progress indicator showing which URL is being processed.

#### Actual Behavior

Only 'Waiting for crawl to complete...' message. No per-URL progress updates, no elapsed time, no indication of which page is currently being fetched. The user stares at a blank screen for 10-30 seconds.

#### Root Cause Analysis

The blocking crawl mode does not stream progress updates to the CLI. Progress information is only available after completion or via --background + polling.

#### Code Pointer

`cli/browser4-cli/src/ (crawl wait logic)`

#### AI Suggested Improvement

- Stream per-URL progress during blocking crawl: 'Fetching 1/3: http://...'
- Show a spinner or elapsed time counter
- Consider making --verbose the default for blocking mode

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Standard UX expectation — blocking operations should show progress. The improvement should stream per-URL status as pages complete (not per-URL start, which would be misleading for parallel fetches). A spinner with elapsed time and "X/Y pages processed" counter at minimum. The backend already knows when each page completes; the gap is in streaming those events to the CLI during blocking wait.

---

### Issue 11: crawl list DESCRIPTION column always shows first seed URL, not command summary

**Severity:** Low
**Category:** UX

#### Reproduction

Run multiple crawls with different options, then crawl list.

#### Expected Behavior

Description column distinguishes tasks (e.g. 'depth 0 + X-SQL', 'background crawl', '--readonly mode').

#### Actual Behavior

All tasks show the same description: 'http://localhost:18080/ec/dp/B0E000001' (the first seed URL). Impossible to tell which task used which options without remembering task IDs.

#### Root Cause Analysis

The description field is populated from the first URL rather than from the command context or user-provided label.

#### Code Pointer

`browser4-rest/ (CrawlTask or CrawlRequest description generation)`

#### AI Suggested Improvement

- Include key flags in the description (e.g. '3 URLs, depth 0, X-SQL, --background')
- Allow users to set a custom label via --label or --name
- Show a compact flag summary column

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The description should be auto-generated from command context, not first URL. A compact format like "3 URLs, depth 0, X-SQL" is more useful than repeating the seed URL. Adding --label for user-provided names is a natural extension but should be scoped as follow-up — the auto-generated improvement alone fixes the core problem of indistinguishable task entries.

---

## Overall Assessment

**Completion Status:** Partially Successful — all 9 task steps were executed, but the core crawl --sql extraction workflow is broken by known bugs (UDF cache mismatch, param dropping). The X-SQL query itself works correctly via htmlsnapshot query (single-page), confirming the query logic is sound. Product data was successfully extracted via the per-page query workaround (4K OLED TV 55 / $899.99, Wireless Headphones / $199.99, Bluetooth Speaker / $49.99).

**Success Rate:** 70% — 8 of 9 crawl commands ran without errors; X-SQL extraction succeeded for 2/3 products via crawl (with pre-loading workaround) and 1/3 via htmlsnapshot query. The fundamental crawl --sql feature is non-functional without the workaround.

**Issues Found:** 11

**Major Blockers:** crawl --sql extraction returns empty data due to two known bugs: (1) CrawlToolExecutor may drop sql/urls params, (2) DOM_LOAD_AND_SELECT UDF reads from WebDB cache but crawl session.load() doesn't populate it. MockSite also fails to start from source without manual mvn install of a dependency module. The @file syntax causes PowerShell parser errors without quoting, contradicting the documented recommendation.

**Most Confusing Aspects:** 1. Discovering that MockSite HTML uses IDs (#productTitle) rather than the classes (.price) shown in documentation examples — required manual curl inspection. 2. The @file syntax failing in PowerShell despite being the documented workaround for Windows shell quoting. 3. Empty extraction results being indistinguishable from query failures — no error, no warning, just blank table cells. 4. crawl list showing tasks from 12 hours ago with an alarming 'Cleaned up 112 stale tasks' message.

**Most Valuable Improvements:** 1. Fix the crawl --sql UDF cache mismatch bug so extraction works without pre-loading workaround. 2. Make MockSite start reliably with ./bin/test.ps1 mock-site (fix dependency build order). 3. Add progress feedback during blocking crawl execution. 4. Add warnings when all extraction fields are empty. 5. Default MockSite to port 18080 instead of random.

**Usability Rating:** 5/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: crawl --sql returns empty extracted data due to UDF cache mismatch

browser4-cli crawl --seed-file urls.txt --depth 0 --sql @extract.sql --refresh

#### Issue 2: htmlsnapshot query results are inconsistent — same query returns data then 417 Expectation Failed

1. goto product page 2. htmlsnapshot 3. htmlsnapshot query with --sql @file — first attempt returns data with statusCode 200, second attempt returns statusCode 417 with resultSet:[]

#### Issue 3: MockSite fails to start from source due to missing MockServerPorts dependency

./bin/test.ps1 mock-site on a fresh checkout

#### Issue 4: MockSite starts on random port by default, not the documented 18080

./bin/test.ps1 mock-site (without port flag)

#### Issue 5: @ file path syntax causes PowerShell parser errors without quoting

browser4-cli crawl --sql @.test-sessions/extract.sql (without quotes in PowerShell)

#### Issue 6: Crawl task list shows accumulated stale tasks with auto-cleanup message

Run crawl list after multiple crawl operations across sessions.

#### Issue 7: Empty crawl extraction results are silent — no error or warning

Run crawl --sql with selectors that don't match page content.

#### Issue 8: SKILL.md crawl examples use Amazon selectors that don't apply to MockSite

Follow the crawl.md 'Bulk product detail extraction' example with MockSite pages.

#### Issue 9: crawl --help does not mention known limitations with --sql extraction

Run browser4-cli crawl --help.

#### Issue 10: No visual feedback during blocking crawl execution

Run a blocking crawl with 3 URLs.

#### Issue 11: crawl list DESCRIPTION column always shows first seed URL, not command summary

Run multiple crawls with different options, then crawl list.

