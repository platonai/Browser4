# Issues: crawl-link-discovery

> **Source:** `20261004-160851-crawl-link-discovery.full.md` | **Date:** 20261004-160851 | **Mode:** dev

## Scenario Background

### Task

I crawled `books.toscrape.com` end-to-end with browser4-cli: link discovery at depth 1, filtered by CSS selector and regex to the 10 category pages, then the same crawl rendered as table, CSV, and JSON, a seed-file bulk fetch of three book detail pages, and a final task-history listing. All nine task steps were eventually completed, but five of them required workarounds for real defects: a natural CSS selector with embedded quotes (`a[href*="catalogue"]`) is silently truncated by the CLI and yields 0 links; crawling the site's `http://` URL (or seeding `http://` book pages) fails because the snapshot origin guard refuses the site's 301 redirect to `https://` and reports the wrong diagnosis; a crawl that trips that guard leaves the browser pool poisoned so the *next* crawl stalls indefinitely until the backend is restarted; `--parallel`/`--timeout` are displayed by the CLI but never reach the backend; and `crawl cancel` doesn't stop the waiting CLI, which polls to its 600 s timeout and then claims the task is still running.

**What succeeded** (all via `./b4w.ps1`, scratch files in `.test-sessions/20261004T1433038523034Z/`):

| Step | Command | Result |
|---|---|---|
| 1–5 | `crawl "https://books.toscrape.com/" --depth 1 --out-link-selector 'a[href*=catalogue]' --out-link-pattern ".*catalogue.*" --top-links 10` | 10 category pages in table format: Classics, Fiction, Historical Fiction, Mystery, Philosophy, Romance, Sequential Art, Travel, Womens Fiction, Books |
| 6 | same + `--format csv -o crawl-links-sql.csv --sql @extract.sql` | valid CSV, 10 rows `url,heading` |
| 7 | same + `--format json --sql @extract.sql` | valid JSON array, 10 rows with headings |
| 8 | `crawl --seed-file seeds-books-https.txt --depth 0 --sql @extract-detail.sql --format table` | 3 books with prices: A Light in the Attic £51.77, Tipping the Velvet £53.74, Soumission £50.10 |
| 9 | `crawl list` | 15 tracked tasks (17 at final check), statuses/durations shown |

The site's own `http://` URL and `http://` seed URLs never worked (steps 2–8 required switching to `https://`); the exact selector from my first attempt (`'a[href*="catalogue"]'`) never worked (required the equivalent unquoted form). The literal step-8 seed file had to be re-run after a backend restart to escape the poisoned-pool stall.

### Execution Context

**Preparation** — Verified cwd `D:/workspace/Browser4/Browser4-4.13`; ran `./b4w.ps1 help`; read `skills/browser4-cli/SKILL.md` and `references/crawl.md` fully. Backend: dev mode, CLI auto-starts the locally-built runtime bundle (`browser4-apps/.../runtime-bundle/_work/browser4-bundle-runtime-windows-x64`, v4.13.27-SNAPSHOT). Every browser4-cli command ran as `./b4w.ps1 <command>`; no Playwright/Selenium/CDP tools were used. `curl` was used only as a plain HTTP client to read static HTML and confirm real markup (`<h1>`, `.price_color`, `<title>`) when composing X-SQL — not for automation.

**Command sequence** (runs 1–13 logged in the scratch dir):

1. **Runs 1–5** — first attempts with `--out-link-selector 'a[href*="catalogue"]'` against `http://books.toscrape.com/`: all ended `⚠ Link dis...

(truncated — see full.md for complete trace)

---

## Issues Found (9 issues)

### Issue 1: CSS selectors and regex patterns containing double quotes are truncated, silently producing 0 links

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 crawl "https://books.toscrape.com/" --depth 1 --out-link-selector 'a[href*="catalogue"]' --out-link-pattern ".*catalogue.*" --top-links 10

#### Expected Behavior

The selector is passed to the backend intact (or rejected with a clear quoting error) and the 10 catalogue links are discovered.

#### Actual Behavior

Crawl ends status OK with 0 pages / 0 links discovered; user-visible diagnostic says "the CSS selector 'a[href*=' matched zero elements" (note the mangled selector). Backend log: PowerSelector - Failed to parse css query | a[href*= | Did not find balanced marker at 'href*=' (reproduced 2026-10-05 00:07, pulsar log line 4129). Workaround: unquoted CSS value a[href*=catalogue].

#### Root Cause Analysis

The CLI renders structured flags into a LoadOptions argument string with unescaped double-quote interpolation: format!("-outLink \"{}\"", v). An inner double quote terminates the token, so the backend receives a[href*= and the remaining text is dropped.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:3084 (out-link-selector), cli/browser4-cli/src/commands.rs:3090 (out-link-pattern)`

#### AI Suggested Improvement

Escape embedded double quotes (and backslashes) when building the LoadOptions args string, or stop serializing through a string: pass selector/pattern as their own structured fields to the tool call. Add a unit test asserting the built args for a selector containing double quotes, mirroring the existing -outLink assertions at commands.rs:6248. Until fixed, document that selectors with attribute values must use the unquoted CSS form (a[href*=catalogue]).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: http:// seeds that 301-redirect to https:// are refused by the snapshot origin guard; the crawl is diagnosed as a network problem

**Severity:** High
**Category:** Reliability

#### Reproduction

A) ./b4w.ps1 crawl "http://books.toscrape.com/" --depth 1 --out-link-selector 'a[href*=catalogue]' --top-links 10  B) seed file with http://books.toscrape.com/catalogue/<book>_<id>/index.html entries, crawl --seed-file … --depth 0

#### Expected Behavior

A same-host 301 http→https upgrade is recognized as the requested page and captured; failing that, the error explicitly names the origin-guard refusal and the committed URL.

#### Actual Behavior

All crawls of the http URL (runs 1–5, 7) and the http seed file (run 11) fail: 0 pages, diagnostic "Portal page returned near-empty content (44 bytes, 4 total elements, 0 anchors)… Try --refresh, verify the URL is reachable, or check network connectivity" — misleading, since the site is reachable (interactive goto to the same http URL follows the redirect fine). Backend log: "Tab origin mismatch: refusing to capture 'https://books.toscrape.com/…' for fetch 'http://books.toscrape.com/…' | driver #29/#31 will be retired".

#### Root Cause Analysis

UrlDocumentMatcher.referToSameDocument (and referToSamePageIgnoringQuery) require ua.scheme == ub.scheme, so the committed https document of a 301-redirected http fetch never matches; InteractiveBrowserEmulator then raises TabOriginMismatchException and retires the driver. Query-order-only redirects were already special-cased (referToSameQuery), but scheme upgrades were not. The crawl diagnostic conflates a guard refusal (near-empty snapshot) with a network failure.

#### Code Pointer

`browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/impl/UrlDocumentMatcher.kt:31 (scheme check at :40 and :85); InteractiveBrowserEmulator.kt:579/601; browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/crawl/CrawlSupport.kt:1018 (emptyOutLinksDiagnostic)`

#### AI Suggested Improvement

Accept an http→https upgrade on the same host, port and path as the same document (allow only the default-port upgrade, keep cross-host and cross-path refusals). When the guard refuses a document, surface a dedicated diagnostic naming the requested URL, the committed URL and the guard reason instead of the generic 'near-empty content… check network connectivity' text. Add a test with a same-host 301 http→https redirect, both for link discovery and seed-file bulk fetch.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Origin-guard refusals poison the browser pool: the next crawl stalls indefinitely with no diagnostics and requires a backend restart

**Severity:** High
**Category:** Reliability

#### Reproduction

After a crawl that trips the origin guard (e.g. the http seed crawl), immediately run ./b4w.ps1 crawl --seed-file seeds-books-https.txt --depth 0 --sql @extract-detail.sql --format table

#### Expected Behavior

The pool evicts unhealthy drivers/privacy contexts and a fresh crawl proceeds; or the new task fails fast with an explicit error.

#### Actual Behavior

The https seed crawl (normally ~5 s) printed 'waiting for first page' for 6+ minutes with zero backend log lines after 'processing seed URL 1/3…3/3'; crawl list later shows it 'failed (timeout)'. Log before the stall: 'LoadingWebDriverPool - no driver became available' and 'Privacy leak warning 1/100000 | 1#default | 162. Retry(1601) rs: TabOriginMismatchException, rsp: CRAWL'. After ./b4w.ps1 stop and re-run, the identical crawl completed in ~5 s.

#### Root Cause Analysis

Driver retirement plus the privacy-leak/retry path (TabOriginMismatchException recorded against the shared permanent context) leaves no ready privacy context; fetches then wait silently instead of failing the task, and nothing self-heals within the task budget.

#### Code Pointer

`browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/context/MultiPrivacyContextManager.kt:650 (leak warning) and :155-172 (retry/failure conversion); LoadingWebDriverPool retirement path`

#### AI Suggested Improvement

Treat origin-guard refusals as task-level failures with an explicit message rather than feeding them into privacy-context bookkeeping. Add a watchdog: if no privacy context becomes ready within N seconds, fail the task with 'no usable browser context' instead of waiting silently. Ensure retired drivers trigger context replacement (or backoff + clear error) so a single bad redirect cannot wedge subsequent crawls.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: --parallel and --timeout are displayed by the CLI but silently dropped before reaching the crawl service

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 crawl "https://books.toscrape.com/catalogue/category/books/classics_6/index.html" --depth 0 --parallel 2 --timeout 2m --background

#### Expected Behavior

The server task runs with 2 parallel tabs and a 2-minute budget, or the CLI refuses/clamps visibly.

#### Actual Behavior

CLI prints 'Parallel tabs: 2', but the backend logs 'Crawl task submitted: 41fcf283… seeds=1 depth=0 parallelTabs=4 budget=600000ms' — server defaults. Same for --parallel 1. The CLI header reports the requested value, so the user believes the flag took effect.

#### Root Cause Analysis

The CLI sends the values as tool-call params (parallel → parallelTabs at main.rs:14013, timeout → taskTimeoutMillis at main.rs:14024), but the MCP tool executor that builds the CrawlRequest reads only url, urls, depth, args and sql — the REST controller path (CrawlController) honors them, the MCP path does not.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/CrawlToolExecutor.kt:72 (callFunctionOn 'submit')`

#### AI Suggested Improvement

Parse parallelTabs and taskTimeoutMillis in CrawlToolExecutor and pass them into CrawlRequest, reusing CrawlController's validation (MAX_PARALLEL_TABS, timeout clamping). Add a test that a crawl submitted through the MCP tool honors parallelTabs/taskTimeoutMillis, so the two entry paths cannot diverge again. If intentionally unsupported on this path, reject the flags in the CLI instead of echoing them.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: crawl cancel is acknowledged but the waiting crawl command never notices; it polls to its 600 s timeout and then reports the task as still running

**Severity:** Medium
**Category:** UX

#### Reproduction

Start a foreground crawl that stalls, then in a second shell run ./b4w.ps1 crawl cancel <task-id>

#### Expected Behavior

The foreground command observes the terminal/cancelled state, exits promptly, and reports the cancellation; crawl list/status show 'cancelled'.

#### Actual Behavior

cancel returns {"cancelled": true}; the foreground waiter keeps printing 'waiting for first page' to 600 s, then prints 'Crawl timed out after 600 seconds (CLI wait) … The task keeps running server-side — poll it with …' and exits 1, although the task finished (per crawl list FINISHED timestamp) ~5 s after the cancel. crawl status for the cancelled task shows terminal status 'Request Timeout' with remaining=3, resumable=true.

#### Root Cause Analysis

The wait loop's terminal-status arms match literal strings 'SC_REQUEST_TIMEOUT'/'SC_INTERNAL_SERVER_ERROR' (main.rs:15222) while the server-reported status is the friendlier 'Request Timeout' — a spelling the CLI's own status table knows (main.rs:11933-11934) but the wait loop does not consult — so the status falls into the '_ still running' arm (main.rs:15247). Cancellation is also not represented as a distinct status.

#### Code Pointer

`cli/browser4-cli/src/main.rs:15222 and :15247 (wait loop), :11933 (friendly_crawl_status), :14697-14707 (timeout message)`

#### AI Suggested Improvement

Normalize the status through friendly_crawl_status (or a shared canonical enum) before the terminal check, and include the timeout/error spellings the server actually emits. Add a distinct CANCELLED status surfaced end-to-end: cancel response, crawl status, crawl list, and the waiter's exit message. Have the waiter re-poll immediately on each iteration's terminal check so a cancellation is noticed within one poll interval.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: PrivacyException retry bound never trips across crawl rounds: '(1/5)' repeats indefinitely

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Crawl a URL whose fetches exhaust the privacy-context pool (observed with http://example.com and https://iana.org/domains/example); watch the pulsar log.

#### Expected Behavior

After maxPrivacyRetries the task fails once, with the underlying cause, and stops fetching.

#### Actual Behavior

Log shows 'PrivacyException, retry later (1/5) | https://iana.org/domains/example' repeated at ~40 s cadence (e.g. 23:43:01, 23:43:42, 23:44:18) with the counter never advancing past 1/5; retries continued for 10+ minutes, past the CLI's 600 s wait, and the example.com crawl produced no terminal result in the UI until its own timeout.

#### Root Cause Analysis

The bounded guard increments task.nRetries on the FetchTask handed to run(), but each crawl round constructs a fresh FetchTask, so nRetries restarts at 0 for every round and the 5-retry ceiling is unreachable across rounds.

#### Code Pointer

`browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/context/MultiPrivacyContextManager.kt:155-172 (catch PrivacyException, task.nRetries++)`

#### AI Suggested Improvement

Count privacy retries at the crawl/task level (persisted on the crawl entry or the fetch's parent), not on the per-round FetchTask. Alternatively, have CrawlRoundRunner treat repeated 'retry later' results as terminal after N rounds and fail the page with the privacy cause included. Log the round number alongside (n/max) so the repetition is visible as rounds rather than an apparently stuck counter.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: --format csv|json without --sql still writes a plain-text listing under the requested -o filename

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 crawl "https://books.toscrape.com/" --depth 1 --out-link-selector 'a[href*=catalogue]' --out-link-pattern ".*catalogue.*" --top-links 10 --format csv -o crawl-links.csv

#### Expected Behavior

Either the command fails fast ('--format csv requires --sql') without creating the file, or the -o file contains valid CSV.

#### Actual Behavior

Prints 'Warning: --format CSV has no effect without --sql…' but still writes crawl-links.csv containing: 'Crawl completed. 10 pages found.', then '  depth=1 | https://… | Classics | Books to Scrape - Sandbox' lines, then 'Parallelism: budget 4 tab(s), peak 1 unit(s) in flight' — not parseable as CSV, yet the filename promises it.

#### Root Cause Analysis

write_crawl_output always writes the formatted stdout content to -o; when --sql is absent the content is the human-readable listing plus summary line regardless of the requested format.

#### Code Pointer

`cli/browser4-cli/src/main.rs:15199-15203 (page_summary + write_crawl_output call), :14355 (write_crawl_output)`

#### AI Suggested Improvement

Refuse to combine --format csv|json with -o unless --sql is present (exit non-zero), or emit genuinely formatted CSV/JSON for the non-SQL listing. Keep the existing warning but make it actionable: state that no file was written, or write it with a truthful extension.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Progress and summary lines mislabel counters and pluralize incorrectly

**Severity:** Low
**Category:** UX

#### Reproduction

Run a 1-seed depth-1 crawl with --sql; and any crawl writing results.

#### Expected Behavior

Counters name what they count and read consistently.

#### Actual Behavior

With 1 seed URL the progress line prints '2/1 seeds done', '5/1 seeds done' … (numerator is pages with extracted rows, denominator is seed URLs); non-SQL runs print '1 pages found'; the completion line reads 'Crawl completed. 10 pages found.. Results written to …' (double period).

#### Root Cause Analysis

The progress template prints extracted_count/url_count under a 'seeds done' label (main.rs:14796-14803); the plural is hardcoded (main.rs:14814-14819); the summary string already ends in '.' and is interpolated followed by another '.' (main.rs:15199/15203).

#### Code Pointer

`cli/browser4-cli/src/main.rs:14796, :14814, :15199, :15203`

#### AI Suggested Improvement

Label the fraction for what it is ('pages with rows'/'pages expected') or print seeds completed only for seed-driven crawls. Pluralize 'page(s)' from the count; drop the duplicated period when composing the 'Results written to' line.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: crawl list table columns do not align with the header for UUID task IDs

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 crawl list

#### Expected Behavior

Header, separator and data rows share one column grid.

#### Actual Behavior

Header 'TASK ID' and the '----' separator are 12 characters wide while each row prints the full 36-character UUID, shifting every later column 24 characters to the right relative to the header.

#### Root Cause Analysis

id_w is capped at 12 (max(8).min(12)) but the row format {:<id_w$} pads to the width without truncating longer values, so the actual cell width exceeds the header's grid.

#### Code Pointer

`cli/browser4-cli/src/state.rs:1155 (id_w) with the row format at cli/browser4-cli/src/state.rs:1229`

#### AI Suggested Improvement

Truncate IDs to the cap (e.g. first 8-12 chars with an ellipsis) or size id_w from the data and let the table be wider. Apply the same rule to every column whose values can exceed its computed width (status today, others later).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## Overall Assessment

**Completion Status:** All 9 task steps completed. Steps 1-5 (selector/pattern/top-links, table) succeeded after replacing the quoted selector with its unquoted form and the http URL with https. Step 6 produced both the literal outcome (warning + mislabeled file) and a correct CSV via --sql. Step 7 (JSON) succeeded. Step 8 succeeded only after fixing the SQL scope, using https seeds, and restarting the backend to clear the poisoned pool. Step 9 succeeded.

**Success Rate:** 9/9 steps completed; 5/9 required workarounds (unquoted selector, https URLs, backend restart, --sql added, SQL selector fix); the literal task-8 seed file and every http:// crawl failed until changed

**Issues Found:** 9

**Major Blockers:** Quoted CSS selector truncated by the CLI (commands.rs:3084) - blocks any natural attribute-value selector with zero useful error at command level http-to-https 301 refused by the snapshot origin guard (UrlDocumentMatcher scheme equality), with a misleading 'check network connectivity' diagnosis Browser pool poisoned after origin-guard driver retirements - next crawl stalls silently for minutes; only a backend restart recovers --parallel and --timeout accepted and echoed by the CLI but dropped by CrawlToolExecutor before the crawl service sees them

**Most Confusing Aspects:** 'Portal page returned near-empty content (44 bytes)… check network connectivity' while the site is reachable and interactive goto works - the message points at the network, not at the origin guard that actually refused the document A natural selector a[href*="catalogue"] silently yields 0 links; the diagnostic echoes the mangled 'a[href*=' back at the user crawl cancel says cancelled:true, then the same terminal prints 'The task keeps running server-side' and crawl list shows it finished seconds after the cancel The CLI displays 'Parallel tabs: 1' while the server runs 4 - the flag appears honored at every user-visible surface but has no effect --format csv producing a non-CSV file, and needing --sql before any format flag does anything Progress line '2/1 seeds done' with a seeds denominator on a single-seed link-discovery crawl

**Most Valuable Improvements:** Escape or structurally pass selector/pattern args so quoted CSS works (single highest-leverage fix; unblocks tasks 1-5 as naturally written) Recognize same-host http-to-https 301 upgrades in the origin guard, and replace the generic near-empty diagnostic with a guard-specific message naming requested vs committed URL Make the pool self-heal after guard refusals, and fail stalled tasks with an explicit 'no usable browser context' error instead of waiting silently Forward parallelTabs/taskTimeoutMillis through CrawlToolExecutor so CLI flags match server behavior Propagate cancellation to the waiting CLI and introduce a distinct cancelled status end-to-end

**Usability Rating:** 6/10

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

#### Issue 1: CSS selectors and regex patterns containing double quotes are truncated, silently producing 0 links

./b4w.ps1 crawl "https://books.toscrape.com/" --depth 1 --out-link-selector 'a[href*="catalogue"]' --out-link-pattern ".*catalogue.*" --top-links 10

#### Issue 2: http:// seeds that 301-redirect to https:// are refused by the snapshot origin guard; the crawl is diagnosed as a network problem

A) ./b4w.ps1 crawl "http://books.toscrape.com/" --depth 1 --out-link-selector 'a[href*=catalogue]' --top-links 10  B) seed file with http://books.toscrape.com/catalogue/<book>_<id>/index.html entries, crawl --seed-file … --depth 0

#### Issue 3: Origin-guard refusals poison the browser pool: the next crawl stalls indefinitely with no diagnostics and requires a backend restart

After a crawl that trips the origin guard (e.g. the http seed crawl), immediately run ./b4w.ps1 crawl --seed-file seeds-books-https.txt --depth 0 --sql @extract-detail.sql --format table

#### Issue 4: --parallel and --timeout are displayed by the CLI but silently dropped before reaching the crawl service

./b4w.ps1 crawl "https://books.toscrape.com/catalogue/category/books/classics_6/index.html" --depth 0 --parallel 2 --timeout 2m --background

#### Issue 5: crawl cancel is acknowledged but the waiting crawl command never notices; it polls to its 600 s timeout and then reports the task as still running

Start a foreground crawl that stalls, then in a second shell run ./b4w.ps1 crawl cancel <task-id>

#### Issue 6: PrivacyException retry bound never trips across crawl rounds: '(1/5)' repeats indefinitely

Crawl a URL whose fetches exhaust the privacy-context pool (observed with http://example.com and https://iana.org/domains/example); watch the pulsar log.

#### Issue 7: --format csv|json without --sql still writes a plain-text listing under the requested -o filename

./b4w.ps1 crawl "https://books.toscrape.com/" --depth 1 --out-link-selector 'a[href*=catalogue]' --out-link-pattern ".*catalogue.*" --top-links 10 --format csv -o crawl-links.csv

#### Issue 8: Progress and summary lines mislabel counters and pluralize incorrectly

Run a 1-seed depth-1 crawl with --sql; and any crawl writing results.

#### Issue 9: crawl list table columns do not align with the header for UUID task IDs

./b4w.ps1 crawl list

