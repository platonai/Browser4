# Issues: crawl-advanced-extraction

> **Source:** `20260916-200854-crawl-advanced-extraction.full.md` | **Date:** 20260916-200854 | **Mode:** dev

## Scenario Background

### Task

All 9 task steps completed successfully, plus the required artifact set. Files live in `.test-sessions/20260916T1722282030412Z/`:

- `seed-products.txt` — the 3 MockSite Electronics product URLs
- `extract-product.sql` — X-SQL extracting `url`, `title`, `price`
- `10-crawl-main.txt` … `20-crawl-list-final.txt` — captured output of every step
- `REPORT-crawl-xsql.md` — this report

Extraction output from step 3 (foreground crawl with `--refresh --parse --expires 1h --priority 1 --page-load-timeout 30s`):

| url | title | price |
|---|---|---|
| http://localhost:18080/ec/dp/B0E000001 | 4K OLED TV 55 | $899.99 |
| http://localhost:18080/ec/dp/B0E000002 | Wireless Noise-Cancelling Headphones | $199.99 |
| http://localhost:18080/ec/dp/B0E000003 | Portable Bluetooth Speaker | $49.99 |

### Execution Context

**Preparation:** `./b4w.ps1 help`, `help crawl`, and `SKILL.md` + `references/crawl.md` read before touching the browser. MockSite verified on :18080 (HTTP 200). Selectors confirmed against the live page (`htmlsnapshot` → `htmlsnapshot get text "#productTitle"` → `4K OLED TV 55`, `#product-price` → `$899.99`) rather than trusting the doc table.

**Steps 3–9:** foreground crawl (3 pages / 3 rows / 17.8 s, exit 0) → background crawl `316cce3c-…` → `crawl list` during the run showed `processing`, and `crawl result` returned the 3 extracted rows → `--ignore-url-query`, `--no-norm`, `--readonly` crawls → final `crawl list` (17 tasks, 16 completed, 1 `request timeout`).

**Workarounds/extra work:** the depth-0 flags produce no observable difference by design, so to actually test what `--ignore-u...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)

### Issue 1: --ignore-url-query strips query parameters from seed URLs, so the crawl fetches a different document than the one requested

**Severity:** High
**Category:** Product

#### Reproduction

# Depth-0 A/B - same seed file, only the flag differs:
./b4w.ps1 crawl --seed-file seed-query.txt --depth 0 --refresh
  -> 1 pages found | http://localhost:18080/ec/b?node=1292115012 | Category: Electronics
./b4w.ps1 crawl --seed-file seed-query.txt --depth 0 --refresh --ignore-url-query
  -> 1 pages found | http://localhost:18080/ec/b?node=1292115012 | Error 400

# Depth-1 A/B on the same URL:
./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 1 --top-links 20 --refresh
  -> Crawl completed. 6 pages found.
./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 1 --top-links 20 --refresh --ignore-url-query
  -> Crawl completed. 0 pages found.  (reproduced 3/3; the no-flag baseline succeeded 2/2)

#### Expected Behavior

--ignore-url-query rewrites only discovered out-link hrefs; seed/portal URLs are loaded verbatim. Both `help crawl` ("no effect on seed URLs in depth-0 bulk fetch") and references/crawl.md ("Seed URLs in a depth-0 bulk fetch are always fetched and reported verbatim") state this explicitly.

#### Actual Behavior

The seed is loaded with its query string stripped. Backend log (doctor log pulsar.pg): `[N] [R] http://localhost:18080/ec/b -ignoreUrlQuery ... <- http://localhost:18080/ec/b?node=1292115012` - the stripped URL was derived from the seed and loaded. At depth 0 the result row still prints the ORIGINAL url, so 'Category: Electronics' is reported against a URL whose fetch actually returned the 400 error page. At depth 1 the portal load yields an 84-byte page with 0 anchors and the crawl reports 'Crawl completed. 0 pages found.'

#### Root Cause Analysis

CombinedUrlNormalizer.normalize() calls URLUtils.normalizeOrNull(normURL, options.ignoreUrlQuery) for every URL passing through the load path, and it receives the request's LoadOptions - so the seed and the depth-1 portal get the query stripped too. The option was meant to be applied only where discovered links are parsed (AbstractPulsarSession.parseNormalizedLink(link, !noNorm, ignoreUrlQuery), lines 830/844/862). The depth-2 path does NOT strip the seed query (that crawl recorded its seed page at depth 0 with the full URL), so depth-1 and depth-2 disagree with each other as well as with the docs.

#### Code Pointer

`browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/urls/CombinedUrlNormalizer.kt:52 (normalize); entry points browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/crawl/CrawlRoundRunner.kt:97 (crawlDepth0 -> session.load(request.url, options)) and browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/session/AbstractPulsarSession.kt:838 (submitForOutPages0 -> normalize(portalUrl, options))`

#### AI Suggested Improvement

- Never apply ignoreUrlQuery to the primary URL being loaded: strip the query only in parseNormalizedLink, where discovered links are normalized.
- Add regression tests that assert the seed/portal URL keeps its query at depth 0, 1 and 2, using a seed whose stripped form resolves to a different document (e.g. /ec/b?node=X vs /ec/b).
- If rewriting seed URLs is intended after all, print the rewrite (`seed URL rewritten: X -> Y`) and correct `help crawl` plus references/crawl.md, which currently promise the opposite.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Empty-portal failures blame the page or the network and never mention that a crawl flag rewrote the URL

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 1 --top-links 20 --refresh --ignore-url-query

#### Expected Behavior

The failure explains that the URL was rewritten before loading (which is the actual cause), or the diagnostic at least names the URL it loaded.

#### Actual Behavior

`Crawl completed. 0 pages found.` followed by `Diagnostic: Portal page returned near-empty content (84 bytes, 4 total elements, 0 anchors). The page may not have loaded correctly. Try --refresh, verify the URL is reachable, or check network connectivity.` Every suggested remedy is wrong: the page is reachable, --refresh was already passed, and the URL that failed is not the one printed. The Tips block then points at --out-link-selector, which is not implicated at all.

#### Root Cause Analysis

The diagnostic is produced by emptyOutLinksDiagnostic(document, outLinkSelector, outLinkPattern) in CrawlRoundRunner, which only sees the loaded document's size and the selector. It has no knowledge of the LoadOptions rewrite that changed the requested URL, so it cannot distinguish 'flag rewrote the URL' from 'site is broken'. The requested URL is not echoed in the diagnostic either.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/crawl/CrawlRoundRunner.kt (emptyOutLinksDiagnostic, called at the depth-1 portal path around line 247)`

#### AI Suggested Improvement

- Include the URL actually loaded in the diagnostic text, next to the requested URL, so a mismatch is visible at a glance.
- When the loaded URL differs from the requested one, say so explicitly and name the option responsible (ignoreUrlQuery / noNorm).
- Demote the network/flakiness tips when the response is a well-formed small document, and keep `--refresh` out of the suggestions when the user already passed it.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: CLI crawl wait equals the server task limit, so the suggested BROWSER4_CLI_CRAWL_TIMEOUT_SECS increase cannot help

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 2 --top-links 5 --refresh --ignore-url-query --verbose
(waits 600s, then) ./b4w.ps1 crawl status 6fd4fb42-ef97-4ef1-8493-4eba3048d2ae

#### Expected Behavior

Either the CLI waits longer than the server's own budget so it can report the real terminal record, or the message states that the server limit was reached and the advice is actionable.

#### Actual Behavior

After 600s the CLI prints `Crawl timed out after 600 seconds (CLI wait)` and advises `Increase the CLI wait with the BROWSER4_CLI_CRAWL_TIMEOUT_SECS environment variable`. The server record for the same task says `"error": "Crawl timed out while processing seeds (server-side limit of 600s exceeded)"` - the server budget is also 600s, so raising the CLI wait changes nothing. The crawl itself is slow for a 7-page local site: an earlier depth-2 crawl of the same site finished in 1m14s, this one never finished.

#### Root Cause Analysis

The CLI-side default wait (600s) and the backend per-task limit (10 minutes, per references/crawl.md) coincide, so on a slow crawl the CLI always gives up at the same instant the server does and can never poll the terminal record. Separately, the observed >600s runtime for a small local crawl points at per-page load latency in the backend (the same pages take ~5-7s each, and depth-2 rounds serialise).

#### Code Pointer

`cli/browser4-cli/src/commands.rs (crawl wait / BROWSER4_CLI_CRAWL_TIMEOUT_SECS handling and the timeout message)`

#### AI Suggested Improvement

- Make the CLI wait comfortably larger than the server limit (e.g. server limit + 60s) by default, so the CLI reports the server's terminal record rather than a local timeout.
- Reword the message to distinguish 'the server task hit its 600s limit' from 'the CLI gave up waiting', and only suggest BROWSER4_CLI_CRAWL_TIMEOUT_SECS in the second case.
- Investigate why a 7-page local depth-2 crawl can exceed 600s when the same shape completed in 74s - the CLI-visible symptom is that identical crawls vary by ~8x.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Behavioural crawl flags give inconsistent feedback: --readonly reports what it did, --ignore-url-query and --no-norm report nothing

**Severity:** Low
**Category:** UX

#### Reproduction

Compare the outputs of:
./b4w.ps1 crawl --seed-file seed-products.txt --depth 0 --sql @extract.sql --refresh --readonly
./b4w.ps1 crawl --seed-file seed-products.txt --depth 0 --sql @extract.sql --refresh --no-norm

#### Expected Behavior

Each behavioural flag either reports its effect or is documented in the command output as a no-op for the chosen mode.

#### Actual Behavior

--readonly prints `readonly: verified fresh - all 3 page(s) fetched from the live site (none served from the page store); nothing was written to the page store`. --no-norm prints nothing at all - the output is byte-identical to the run without it - and --ignore-url-query likewise prints nothing (while, per Issue 1, actually changing the fetch). A first-time user following the documented depth-0 workflow has no way to tell whether either flag was accepted or silently ignored.

#### Root Cause Analysis

The readonly path echoes a verification message built from the page-store markers it computes, while noNorm and ignoreUrlQuery have no equivalent reporting surface even though they change the LoadOptions that the load ran under. The completion summary does not echo the effective LoadOptions.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/crawl/CrawlRoundRunner.kt (crawl completion report) and cli/browser4-cli/src/commands.rs (crawl output rendering)`

#### AI Suggested Improvement

- Echo the effective behavioural options once in the completion report (e.g. `options: refresh, parse, expires=1h, priority=1, timeout=30s, no-norm`), the way --readonly already reports its own effect.
- When a flag is a documented no-op for the chosen mode (depth-0 bulk fetch), print one short line saying so instead of accepting it silently.
- Reuse one rendering helper so every behavioural flag reports through the same channel.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: crawl list shows a terminal task with status 'request timeout' but FINISHED/DURATION as '-'

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 crawl list   # after the CLI wait expired on a still-running task

#### Expected Behavior

A task the list itself labels 'request timeout' shows its finish time and duration like every other terminal task.

#### Actual Behavior

Row `6fd4fb42-... | crawl | 1 URLs, depth 2 | 2026-09-17 03:57:02 | - | - | request timeout`, even though the server record carries `"finishTime": "2026-09-16T20:07:02Z"` and the task has been terminal for a minute.

#### Root Cause Analysis

The status column is refreshed from the server when the list is rendered, but STARTED/FINISHED/DURATION come from the CLI's local task tracking, which only records a finish time when the CLI process is present to observe completion. When the CLI's 600s wait expires and the process exits, the local record is never completed, leaving a terminal server status paired with empty local timings.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (crawl list rendering / local task store)`

#### AI Suggested Improvement

- Backfill FINISHED/DURATION from the server record when the server status is terminal and the local record has no finish time.
- If the two sources disagree, render a single consistent row rather than mixing a server-sourced status with local-sourced timings.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: htmlsnapshot inspect on a detail page surfaces the 'Customers also viewed' block as the recurring pattern

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot inspect

#### Expected Behavior

On a product detail page the tool either reports that no recurring content pattern exists, or marks its finding as an incidental/side block rather than the page's main content.

#### Actual Behavior

It reports `Auto-discovered repeating pattern from ":root"`, analyses the 4 recommendation cards and prints starred suggestions for `article.recommendation-card`, `span.recommendation-price`, etc. The words 'Customers also viewed' appear only as sample text. A new user following the documented advice to inspect before writing a query is steered to the recommendation rail, not the product; the extracting query then yields the wrong data with no error.

#### Root Cause Analysis

inspect ranks by repetition across sibling groups, which fits list/grid pages. A detail page has no repeating main block, so the only repeating group on the page (the recommendation rail) wins by default. The output has no signal that the winning group is a rail rather than page content - the documented caveat exists in references/crawl.md and SKILL.md, but nothing in the command output itself says it.

#### Code Pointer

`cli/browser4-cli/src/commands.rs and the backend htmlsnapshot inspect implementation (selector-pattern discovery for the inspect command)`

#### AI Suggested Improvement

- Label the discovered group (e.g. `recommendation rail / sidebar` vs `main content`) using its position and share of page area, and say when the match looks incidental.
- When no main content block repeats, print `No recurring main-content pattern found - this looks like a detail page` and point at `htmlsnapshot summary` or explicit selectors, rather than printing starred selectors for the rail.
- Bias group ranking away from blocks whose text matches common rail words (also viewed, related, sponsored).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Success messages suggest commands using browser4-cli while the user invoked ./b4w.ps1, pointing at a different installed binary

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 crawl --seed-file urls.txt --depth 0 --background
-> "Running in background. Task ID: ... Use 'browser4-cli crawl list' to view all tracked tasks."

#### Expected Behavior

Follow-up hints reflect how the CLI was invoked, or at least note that the plain binary may be a different build.

#### Actual Behavior

The hint names `browser4-cli`, and on this machine a separate npm-installed `browser4-cli` is on PATH (/c/Users/pereg/AppData/Roaming/npm/browser4-cli). Copying the suggested command therefore runs a different artifact from the source-tree build the user just used - exactly the stale-binary pitfall the dev wrapper exists to avoid. The task ID is still valid for both, since they talk to the same backend, so the failure is silent when the versions diverge.

#### Root Cause Analysis

The message strings are written against the generic published command name and have no notion of the invoking wrapper (b4w.ps1 / b4w.sh / b4w.bat), so they cannot echo the invocation form.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (background/next-step hint strings)`

#### AI Suggested Improvement

- Derive the suggested command prefix from the invocation (argv[0] / wrapper env var) so hints read `./b4w.ps1 crawl list` when run through the dev wrapper.
- Add a one-line warning when a differing browser4-cli build is detected on PATH, or suppress the PATH-based hint in that case.

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

**Completion Status:** Successful - all 9 prescribed steps ran to completion and the crawl extracted the right title and price for all 3 MockSite products, foreground and background. The evaluation also uncovered a reproducible data-correctness bug (Issue 1) that the prescribed steps do not exercise, because at depth 0 the URL flags are documented as no-ops.

**Success Rate:** 95% - every prescribed step returned exit 0 with correct data; the deduction is for the depth-1 investigation runs, where one crawl reported 0 pages and another exceeded the CLI wait, leaving the crawl's terminal state to be polled separately.

**Issues Found:** 7

**Major Blockers:** None for the prescribed task. Issue 1 is a latent blocker for any real crawl whose seed URL carries a meaningful query string (paginated listings, search results, ?node= category URLs): with --ignore-url-query the crawl silently fetches a different document, and at depth 1 reports 0 pages while blaming the page or the network. The docs state the opposite in two places, so the flag is currently unsafe to use on such seeds.

**Most Confusing Aspects:** The URL flags are documented as being invisible at depth 0 and effective only on discovered links, but they are neither: --ignore-url-query changes a depth-0 seed fetch, and --no-norm changes nothing observable on the MockSite fixture. The only way to see what --ignore-url-query actually does was an A/B test on a seed URL whose query selects the content, because the crawl prints the requested URL next to data fetched from a different one. Three behavioural flags also give three different levels of feedback: --readonly narrates its verification, --no-norm and --ignore-url-query say nothing.

**Most Valuable Improvements:** 1) Stop applying ignoreUrlQuery (and noNorm) to the URL being loaded - restrict them to discovered-link normalization where the docs place them, and add seed-URL regression tests at depth 0/1/2. 2) Print the URL actually loaded, and flag any URL rewritten by an option, in both the diagnostics and the result rows. 3) Give behavioural flags one consistent reporting channel in the completion summary, including explicit 'no-op in this mode' lines. 4) Make the CLI wait longer than the server's 600s task limit so its timeout advice is actionable. 5) Have htmlsnapshot inspect label side rails as incidental on detail pages instead of starring their selectors.

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

#### Issue 1: --ignore-url-query strips query parameters from seed URLs, so the crawl fetches a different document than the one requested

# Depth-0 A/B - same seed file, only the flag differs:
./b4w.ps1 crawl --seed-file seed-query.txt --depth 0 --refresh
  -> 1 pages found | http://localhost:18080/ec/b?node=1292115012 | Category: Electronics
./b4w.ps1 crawl --seed-file seed-query.txt --depth 0 --refresh --ignore-url-query
  -> 1 pages found | http://localhost:18080/ec/b?node=1292115012 | Error 400

# Depth-1 A/B on the same URL:
./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 1 --top-links 20 --refresh
  -> Crawl completed. 6 pages found.
./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 1 --top-links 20 --refresh --ignore-url-query
  -> Crawl completed. 0 pages found.  (reproduced 3/3; the no-flag baseline succeeded 2/2)

#### Issue 2: Empty-portal failures blame the page or the network and never mention that a crawl flag rewrote the URL

./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 1 --top-links 20 --refresh --ignore-url-query

#### Issue 3: CLI crawl wait equals the server task limit, so the suggested BROWSER4_CLI_CRAWL_TIMEOUT_SECS increase cannot help

./b4w.ps1 crawl "http://localhost:18080/ec/b?node=1292115012" -ol "a[href]" -d 2 --top-links 5 --refresh --ignore-url-query --verbose
(waits 600s, then) ./b4w.ps1 crawl status 6fd4fb42-ef97-4ef1-8493-4eba3048d2ae

#### Issue 4: Behavioural crawl flags give inconsistent feedback: --readonly reports what it did, --ignore-url-query and --no-norm report nothing

Compare the outputs of:
./b4w.ps1 crawl --seed-file seed-products.txt --depth 0 --sql @extract.sql --refresh --readonly
./b4w.ps1 crawl --seed-file seed-products.txt --depth 0 --sql @extract.sql --refresh --no-norm

#### Issue 5: crawl list shows a terminal task with status 'request timeout' but FINISHED/DURATION as '-'

./b4w.ps1 crawl list   # after the CLI wait expired on a still-running task

#### Issue 6: htmlsnapshot inspect on a detail page surfaces the 'Customers also viewed' block as the recurring pattern

./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"
./b4w.ps1 htmlsnapshot
./b4w.ps1 htmlsnapshot inspect

#### Issue 7: Success messages suggest commands using browser4-cli while the user invoked ./b4w.ps1, pointing at a different installed binary

./b4w.ps1 crawl --seed-file urls.txt --depth 0 --background
-> "Running in background. Task ID: ... Use 'browser4-cli crawl list' to view all tracked tasks."

