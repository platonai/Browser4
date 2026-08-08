# Issues: bulk-scale-routing

> **Source:** `20260807-174858-bulk-scale-routing.full.md` | **Date:** 20260807-174858 | **Mode:** dev

## Scenario Background

### Task

**Partially Successful.** 4 of 6 acceptance criteria completed successfully; 2 encountered persistent infrastructure-level failures in the local environment.

| AC | Status | Summary |
|----|--------|---------|
| AC1 | ✅ Complete | `htmlsnapshot query` with `DOM_LOAD_AND_SELECT` extracted 6 product rows (title + price + rating + URL) from the listing page |
| AC2 | ✅ Complete | `crawl --depth 0 --seed-file` with X-SQL extracted 3 product rows from seed URLs |
| AC3 | ⚠️ Partial | Link discovery concept demonstrated via `htmlsnapshot get all attr` — `a.product` selector correctly isolates 3 product links while `a.category-link` (3 links) and `a.utility-link` (3 links) are excluded. However, `crawl --depth >= 1` fails to load `/generated/crawl/index.html` (timeout), and `crawl --depth 0` on the product pages fails for 2 of 3 products with "Protocol not found (1600)" |
| AC4 | ❌ Failed | `swarm create` succeeds but worker pool never picks up tasks — all jobs stay "queued" indefinitely. Root cause: swarm browser session is "unhealthy" (active=false, driver=N/A) |
| AC5 | ✅ Complete | Loop with `--shell` wrapping `./b4w.sh -s price-watch eval "..."` correctly executed 2 iterations returning `$899.99` each time. Subcommand mode (`-- eval ...`) is broken with multi-word arguments |
| AC6 | ✅ Complete | Bash `for` loop with `goto` → `htmlsnapshot` → `htmlsnapshot get text` correctly extracted title and price from 3 product URLs |

### Execution Context

**Key Commands:**

1. `./b4w.sh help` — read CLI help
2. `pwsh ./bin/test.ps1 mock-site -Dmock.site.port=18080` — start MockSite
3. AC1: `./b4w.sh goto "http://localhost:18080/ec/b?node=1292115012"` → `htmlsnapshot inspect` → wrote `.test-sessions/ac1-listing-query.sql` → `htmlsnapshot query ... --sql @.test-sessions/ac1-listing-query.sql`
4. AC2: wrote `.test-sessions/ac2-seeds.txt` + `.test-sessions/ac2-extract.sql` → `crawl --seed-file ... --depth 0 --sql @... --format table --refresh`
5. AC3: multiple `crawl ... --depth 1 --out-link-selector "a.product"` attempts (all timed out on `/generated/crawl/`) → workaround: `goto` + `htmlsnapshot get all attr "a.product" href` confirmed link isolation → wrote seed file from discovered links → `crawl --depth 0 --seed-file ... --sql @...`
6. AC4: `swarm create --display-mode HEADLESS ...` → `swarm query --sql @... --seed-file ... --wait` (×3 attempts with different configs; all tasks stuck "queued")
7. AC5: `-s price-watch goto ...` → `loop --name ... --count 2 -i 10 --shell './b4w.sh -s price-watch eval "..."'` (workaround for broken `--` subcommand mode)
8. AC6: `for url in ...; do ./b4w.sh goto "$url"; ./b4w.sh htmlsnapshot; ./b4w.sh htmlsnapshot get text ...; sleep 2; done`

---

## Issues Found (12 issues)

### Issue 1: Swarm worker pool never picks up tasks — all jobs stuck 'queued' indefinitely

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1) swarm create --max-browser-contexts 1 --max-open-tabs 2
2) swarm query 'http://localhost:18080/ec/dp/B0E000001' --sql @query.sql --refresh --wait

#### Expected Behavior

Task transitions from 'queued' → 'processing' → 'completed' with extracted data.

#### Actual Behavior

All tasks stay 'queued' forever. After 300s --wait timeout: '0 of 1 job(s) completed'. Server logs show: 'Session SWARM is unhealthy: session active=false, browser healthy=N/A, driver healthy=N/A'. The swarm browser context never initializes.

#### Root Cause Analysis

The swarm session's underlying browser driver/Chrome instance fails to initialize. Server logs show 'session active=false' and 'browser healthy=N/A, driver healthy=N/A' immediately after creation. The DISPLAY environment variable is detected (:0) but the Chrome browser for swarm never starts successfully. The loading web driver pool metrics show only 1 offer and 1 success, suggesting the pool can create a session but the session is immediately unhealthy.

#### AI Suggested Improvement

- Add explicit health check on swarm session creation — fail fast if browser/driver are unhealthy
- Surface the 'session unhealthy' warning to the CLI user (currently only visible in server logs)
- Implement automatic retry with backoff for swarm browser context initialization
- Add a 'swarm doctor' command to diagnose why browser contexts won't start
- Consider detecting the 'chrome not available' condition and suggesting fallback to crawl --depth 0

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
check if this is caused by the lazing spring bean loading

---

### Issue 2: SKILL.md quickstart template uses DOM_TEXT but actual X-SQL function is DOM_FIRST_TEXT

**Severity:** High
**Category:** Documentation

#### Reproduction

Copy the X-SQL template from SKILL.md §4e: SELECT DOM_TEXT(DOM, 'h2') AS title FROM DOM_LOAD_AND_SELECT(@url, '.product-card'). Run against any page.

#### Expected Behavior

Query executes successfully and returns text values.

#### Actual Behavior

statusCode 417 with 'Data conversion error converting ...'. The DOM_TEXT function does not exist; the correct function is DOM_FIRST_TEXT.

#### Root Cause Analysis

SKILL.md §4e 'X-SQL Quickstart Template' uses DOM_TEXT() in its example, but the actual X-SQL DomSelectFunctions expose DOM_FIRST_TEXT, DOM_ALL_TEXTS, DOM_NTH_TEXT. DOM_TEXT is not a valid function name. This is the #1 user mistake with element-scoped eval-equivalent in X-SQL per the SKILL.md warning section.

#### Code Pointer

`skills/browser4-cli/SKILL.md:298-304 — the X-SQL Quickstart Template section uses DOM_TEXT instead of DOM_FIRST_TEXT`

#### AI Suggested Improvement

- Update SKILL.md §4e quickstart template to use DOM_FIRST_TEXT(DOM, 'h2') instead of DOM_TEXT(DOM, 'h2')
- Add DOM_TEXT as an alias in the X-SQL engine for backward compatibility
- Add a 'Common mistakes' entry: 'Function not found' → 'Use DOM_FIRST_TEXT not DOM_TEXT'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Task instructions require $(./b4w.ps1) invocation which is bash command substitution — fails on Linux

**Severity:** High
**Category:** Documentation

#### Reproduction

In bash on Linux: $(./b4w.ps1) help

#### Expected Behavior

The browser4-cli help output.

#### Actual Behavior

/bin/bash: line 1: b4w: command not found. $(./b4w.ps1) resolves to the string 'b4w' (the script output), and bash tries to execute 'b4w help' which fails because b4w is not in PATH.

#### Root Cause Analysis

The task instructions template uses $(./b4w.ps1) <command> syntax which is bash command substitution — it runs ./b4w.ps1 and substitutes its output ('b4w') as a command name. This is explicitly warned against in SKILL.md line 26-28. The task instructions should be platform-aware or use ./b4w.sh.

#### AI Suggested Improvement

- Update evaluation task templates to use ./b4w.sh on Linux/macOS and ./b4w.ps1 on PowerShell
- Add platform detection to task templates: 'if bash, use ./b4w.sh; if pwsh, use ./b4w.ps1'
- Remove the $(...) wrapper entirely — it's never correct in any shell

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Crawl depth >= 1 fails to load /generated/crawl/index.html with timeout after 300s

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.sh crawl 'http://localhost:18080/generated/crawl/index.html' --depth 1 --out-link-selector 'a.product' --refresh

#### Expected Behavior

Page loads, 3 product links discovered, product pages crawled.

#### Actual Behavior

Timeout after 300s: 'Timed out waiting for 300000 ms'. The start page never loads in the crawler. However, goto (interactive browser) and curl both load the page instantly (10ms, 5KB).

#### Root Cause Analysis

The crawl engine's page loading mechanism (WebDriver/FetchComponent) fails to load pages from the /generated/crawl/ path on localhost. Server logs show 'Protocol not found (1600)' errors. This is likely a protocol handler registration issue — the internal HTTP client uses different protocol handlers based on URL paths, and /generated/ is not properly registered. Additionally, the first attempt worked (loaded page, 0 links found), but subsequent attempts all timeout — suggesting a worker pool deadlock after initial failure.

#### AI Suggested Improvement

- Investigate FetchComponent protocol handler registration for localhost paths
- Add better error messages when a page load fails (not just generic 'timed out')
- Implement worker pool health checks to detect and recover from stuck workers
- Consider falling back to the interactive browser loading mechanism when the internal fetcher fails

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
check for the lazy spring bean loading mechanism, it might be the root cause

---

### Issue 5: Crawl on /generated/crawl/ path returns 'Protocol not found (1600)' for some URLs

**Severity:** High
**Category:** Reliability

#### Reproduction

crawl --seed-file (containing /generated/crawl/product/1.html through 3.html) --depth 0 --refresh

#### Expected Behavior

All 3 product pages fetched successfully with structured extraction.

#### Actual Behavior

Product 1: fetched 3226 bytes (after 'Protocol not found' warning → retried successfully). Product 2: 'fetch returned 0 bytes after 3 attempts — Protocol not found (1600)'. Product 3: same failure. Only 1 of 3 pages extracted.

#### Root Cause Analysis

The FetchComponent uses protocol handlers that are not registered for the /generated/ URL path. Server logs show 'Protocol not found (1600)' for all three URLs. Product 1 succeeded on retry (the protocol handler was registered during the retry), but products 2 and 3 consistently failed. This is a race condition in protocol handler initialization. The /ec/ path works perfectly because its protocol handler is pre-registered.

#### Code Pointer

`browser4-core/ FetchComponent — protocol handler registration and discovery for custom URL paths`

#### AI Suggested Improvement

- Pre-register protocol handlers for all supported URL schemes/paths at server startup
- Implement a retry-with-backoff for ProtoNotFound errors that waits for handler registration
- Add a 'protocol handler warmup' step before accepting crawl/swarm requests
- Surface 'ProtoNotFound' errors to the CLI user with actionable advice

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
check spring bean lazy loading mechanism

---

### Issue 6: Loop subcommand mode (--) fails with multi-word arguments

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.sh loop --name test --count 1 -i 10 -- -s price-watch eval 'document.title'

#### Expected Behavior

Loop runs eval on the named session.

#### Actual Behavior

Error: too many arguments: expected 1, received 3. The loop parser treats '-s', 'price-watch', and 'eval' as separate task arguments when it expects exactly 1.

#### Root Cause Analysis

The loop command's argument parser expects exactly 1 argument after -- (treated as a single command). Multi-word subcommands fail because the parser doesn't collect all remaining args into a subcommand array. This contradicts the documentation which shows examples like 'loop -- eval "document.title" -i 300' with multiple words.

#### Code Pointer

`cli/browser4-cli/src/ (loop argument parser — the subcommand arg collection logic)`

#### AI Suggested Improvement

- Fix the loop argument parser to collect ALL args after -- as subcommand arguments, not just 1
- Add a test for multi-word subcommand: loop -- status --json
- Document the workaround: use --shell mode wrapping browser4-cli call for complex subcommands

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: htmlsnapshot capture times out on listing page with 60s default timeout

**Severity:** Medium
**Category:** Reliability

#### Reproduction

goto 'http://localhost:18080/ec/b?node=1292115012' then htmlsnapshot

#### Expected Behavior

HTML snapshot captured within a few seconds.

#### Actual Behavior

HTTP request timed out [tool=html_snapshot_capture, timeout=60s]. However, htmlsnapshot query (which uses DOM_LOAD_AND_SELECT/@url independent of stored snapshot) works fine.

#### Root Cause Analysis

The htmlsnapshot capture tool uses a 60s default timeout and fails on the e-commerce listing page. The page is small (10KB) but the capture operation may involve additional processing that exceeds 60s. The error message doesn't explain why it timed out or suggest alternatives.

#### AI Suggested Improvement

- Increase the default htmlsnapshot capture timeout to match other tools (120s+)
- Add a note in the error message: 'try htmlsnapshot query with @url instead — it fetches pages independently'
- Consider making the timeout configurable per-invocation

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Swarm session hijacks default browser session

**Severity:** Medium
**Category:** UX

#### Reproduction

1) goto any URL (creates DEFAULT session)
2) swarm create
3) list

#### Expected Behavior

Separate isolated sessions: DEFAULT (interactive browser) and SWARM (swarm session).

#### Actual Behavior

The DEFAULT session is replaced by SWARM. list shows only: (default) | SWARM. After swarm close, goto creates a new DEFAULT session, but while swarm is active, the interactive browser session is unavailable.

#### Root Cause Analysis

Swarm creates a session with sessionId=SWARM that also registers as the default session, replacing the existing DEFAULT session. The swarm reference mentions 'The swarm session uses fixed session ID SWARM — it doesn't share state with named or default sessions' but in practice it occupies the default session slot.

#### AI Suggested Improvement

- Keep the DEFAULT interactive session separate from the SWARM session
- Allow both to coexist: 'list' should show both DEFAULT and SWARM
- Auto-restore the previous DEFAULT session when swarm closes
- Document the session takeover behavior and its implications for mixed interactive/swarm workflows

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: X-SQL result columns appear twice — once uppercase (null) and once lowercase (with values)

**Severity:** Low
**Category:** Product

#### Reproduction

Run any htmlsnapshot query with AS lowercase aliases: SELECT DOM_FIRST_TEXT(DOM, '.title') AS title FROM DOM_LOAD_AND_SELECT(@url, '.card')

#### Expected Behavior

Single set of columns with the specified alias names.

#### Actual Behavior

resultSet contains both TITLE: null and title: 'actual value'. The H2 SQL engine creates both uppercase (identifier) and lowercase (alias) versions of each column.

#### Root Cause Analysis

H2 SQL engine normalizes unquoted identifiers to uppercase but preserves alias casing. The query SELECT DOM_FIRST_TEXT(...) AS title produces both TITLE (H2's normalized form) and title (the alias). The JSON serialization includes both keys.

#### Code Pointer

`Browser4 backend — the H2 SQL result set serialization to JSON`

#### AI Suggested Improvement

- Filter duplicate columns in the JSON serialization, keeping only the lowercased alias form
- Consider using quoted identifiers consistently to avoid H2's case normalization
- Document this behavior so users know to expect duplicate keys

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
add unit tests for this

---

### Issue 10: Version mismatch warning on every command

**Severity:** Low
**Category:** UX

#### Reproduction

Run any browser4-cli command: status, goto, etc.

#### Expected Behavior

Clean output without version mismatch warnings.

#### Actual Behavior

⚠ Version mismatch: CLI is 4.13.0 but running backend is 4.13.0-SNAPSHOT. Rebuild both to match: mvn install ... This appears on every command output.

#### Root Cause Analysis

The local build produces 4.13.0-SNAPSHOT backend JAR but the CLI Cargo.toml is versioned 4.13.0. In dev mode from source, these should be considered compatible.

#### Code Pointer

`cli/browser4-cli/ (CLI version check) and browser4-rest/ (backend version)`

#### AI Suggested Improvement

- In dev mode, treat SNAPSHOT suffixes as matching their release version (4.13.0-SNAPSHOT ≈ 4.13.0)
- Show the warning only once per session, not on every command
- Or: align the version strings so dev mode shows no warning

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 11: First-run startup takes 10-13 seconds — acceptable but spinner feedback is verbose

**Severity:** Low
**Category:** UX

#### Reproduction

First browser4-cli command after server stop or fresh start.

#### Expected Behavior

Quick startup with concise progress indication.

#### Actual Behavior

~40 lines of spinner output showing JVM loading → TCP port waiting → Spring Boot startup → MCP tools ready. The spinner changes every 100ms producing dense output. Total: ~13s with ~120 spinner frames.

#### Root Cause Analysis

The spinner renders at 10fps with stage-level messages. For a 13s startup, this produces many lines of output that obscure the actual command result. The stage transition messages are useful but the per-frame spinner output is excessive.

#### Code Pointer

`cli/browser4-cli/src/ (server startup progress display)`

#### AI Suggested Improvement

- Use a single-line animated spinner (overwrite in place) instead of new-line-per-frame
- Show only stage transitions (JVM → Spring Boot → MCP), not every second
- Consider using terminal escape codes for a progress bar
- Add a '--quiet-startup' flag that suppresses spinner output

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 12: No htmlsnapshot inspect output when page not yet captured — unclear error

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run htmlsnapshot inspect without first running htmlsnapshot (capture).

#### Expected Behavior

Clear error: 'No HTML snapshot available. Run htmlsnapshot first.'

#### Actual Behavior

The error message depends on server state. When the swarm session had taken over, the error was a timeout rather than a clear 'no snapshot available' message.

#### Root Cause Analysis

The htmlsnapshot tool family has a split: query works independently (re-fetches via DOM_LOAD_AND_SELECT), but capture/inspect/get require the stored snapshot. The error messages don't clearly distinguish these two paths or tell the user which commands need capture first and which don't.

#### AI Suggested Improvement

- Add a table to SKILL.md showing which htmlsnapshot commands need capture first vs. which work independently
- Improve error messages: 'No HTML snapshot in storage. Either: a) run htmlsnapshot first, or b) use htmlsnapshot query with @url for independent fetching'
- Consider making htmlsnapshot inspect auto-capture if no snapshot exists

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Partially Successful — 4 of 6 ACs fully successful (AC1, AC2, AC5, AC6); AC3 demonstrated core concept via workaround; AC4 failed due to infrastructure-level swarm worker pool failure that prevented any task processing.

**Success Rate:** 67% — 4 ACs fully successful, 1 partially successful with workaround (AC3), 1 failed (AC4)

**Issues Found:** 12

**Major Blockers:** 1) Swarm worker pool never initializes — browser session is 'unhealthy' immediately after creation, all jobs stuck 'queued' forever. 2) Crawl depth >= 1 on /generated/crawl/ path times out (300s); depth 0 on same path has 'Protocol not found (1600)' failures for 2/3 URLs. 3) X-SQL quickstart template uses wrong function name (DOM_TEXT vs DOM_FIRST_TEXT).

**Most Confusing Aspects:** 1) The disconnect between SKILL.md quickstart templates (which use DOM_TEXT) and actual working function names (DOM_FIRST_TEXT) — the most common error for new users. 2) The swarm session silently hijacking the default session with no warning — breaks mixed interactive+parallel workflows. 3) The htmlsnapshot capture requirement — query works without capture, but inspect/get require it, with unclear errors when capture is missing. 4) The $(./b4w.ps1) invocation syntax in task instructions which SKILL.md explicitly says doesn't work in bash.

**Most Valuable Improvements:** 1) Fix the X-SQL quickstart template to use DOM_FIRST_TEXT instead of DOM_TEXT. 2) Implement swarm worker pool health checks with user-visible diagnostics (not just hidden in server logs). 3) Add protocol handler pre-registration to fix ProtoNotFound(1600) errors. 4) Fix loop subcommand mode to accept multi-word arguments as documented. 5) Keep swarm and default sessions independent.

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

#### Issue 1: Swarm worker pool never picks up tasks — all jobs stuck 'queued' indefinitely

1) swarm create --max-browser-contexts 1 --max-open-tabs 2
2) swarm query 'http://localhost:18080/ec/dp/B0E000001' --sql @query.sql --refresh --wait

#### Issue 2: SKILL.md quickstart template uses DOM_TEXT but actual X-SQL function is DOM_FIRST_TEXT

Copy the X-SQL template from SKILL.md §4e: SELECT DOM_TEXT(DOM, 'h2') AS title FROM DOM_LOAD_AND_SELECT(@url, '.product-card'). Run against any page.

#### Issue 3: Task instructions require $(./b4w.ps1) invocation which is bash command substitution — fails on Linux

In bash on Linux: $(./b4w.ps1) help

#### Issue 4: Crawl depth >= 1 fails to load /generated/crawl/index.html with timeout after 300s

./b4w.sh crawl 'http://localhost:18080/generated/crawl/index.html' --depth 1 --out-link-selector 'a.product' --refresh

#### Issue 5: Crawl on /generated/crawl/ path returns 'Protocol not found (1600)' for some URLs

crawl --seed-file (containing /generated/crawl/product/1.html through 3.html) --depth 0 --refresh

#### Issue 6: Loop subcommand mode (--) fails with multi-word arguments

./b4w.sh loop --name test --count 1 -i 10 -- -s price-watch eval 'document.title'

#### Issue 7: htmlsnapshot capture times out on listing page with 60s default timeout

goto 'http://localhost:18080/ec/b?node=1292115012' then htmlsnapshot

#### Issue 8: Swarm session hijacks default browser session

1) goto any URL (creates DEFAULT session)
2) swarm create
3) list

#### Issue 9: X-SQL result columns appear twice — once uppercase (null) and once lowercase (with values)

Run any htmlsnapshot query with AS lowercase aliases: SELECT DOM_FIRST_TEXT(DOM, '.title') AS title FROM DOM_LOAD_AND_SELECT(@url, '.card')

#### Issue 10: Version mismatch warning on every command

Run any browser4-cli command: status, goto, etc.

#### Issue 11: First-run startup takes 10-13 seconds — acceptable but spinner feedback is verbose

First browser4-cli command after server stop or fresh start.

#### Issue 12: No htmlsnapshot inspect output when page not yet captured — unclear error

Run htmlsnapshot inspect without first running htmlsnapshot (capture).

