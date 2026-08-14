# Issues: swarm-parallel-scraping

> **Source:** `20260814-143321-swarm-parallel-scraping.full.md` | **Date:** 20260814-143321 | **Mode:** dev

## Scenario Background

### Task

All 9 task steps were completed:

1. **Swarm session created** with `--display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` ✓
2. **Seed file** created at `.test-sessions/swarm-seed.txt` with 10 MockSite product URLs (6 Electronics `B0E000001`–`B0E000006`, 4 Home `B0H000001`–`B0H000004`; all verified to exist in the mock seed data) ✓
3. **X-SQL query** written to `.test-sessions/swarm-query.sql`, extracting `url`, `title`, `price`, `image_url` from each product page ✓
4. **`swarm query --sql @file --seed-file --refresh`** submitted 10 extraction jobs ✓ (after one session restart — see issues 1–3)
5. **`swarm submit`** enqueued a plain scrape job for `http://localhost:18080/ec/dp/B0E000001` → completed with `resultSet` containing only the URL and `pageContentBytes: 16110`, exactly as documented ✓
6. **Polled** each job via `swarm status` / `swarm list` until `isDone: true` ✓
7. **Retrieved and reviewed results** — all 10 extraction jobs returned complete, correct data (raw payloads saved to `.test-sessions/swarm-results.json`):

| URL | Title | Price | Image URL |
|---|---|---|---|
| /ec/dp/B0E000001 | 4K OLED TV 55 | $899.99 | https://picsum.photos/seed/1250857624/200/140 |
| /ec/dp/B0E000002 | Wireless Noise-Cancelling Headphones | $199.99 | https://picsum.photos/seed/1250857625/200/140 |
| /ec/dp/B0E000003 | Portable Bluetooth Speaker | $49.99 | https://picsum.photos/seed/1250857626/200/140 |
| /ec/dp/B0E000004 | Smartphone 128GB | $599.00 | https://picsum.photos/seed/1250857627/200/140 |
| /ec/dp/B0E000005 | USB-C Hub 7-in-1 | $29.95 | https://picsum.photos/seed/1250857628/200/140 |
| /ec/dp/B0E000006 | Wireless Mouse | $24.99 | https://picsum.photos/seed/1250857629/200/140 |
| /ec/dp/B0H000001 | Vacuum Cleaner Smart | $159.99 | https://picsum.photos/seed/-381598629/200/140 |
| /ec/dp/B0H000002 | Air Purifier | $129.50 | https://picsum.photos/seed/-381598628/200/140 |
| /ec/dp/B0H000003 | Essential Oil Diffuser | $22.99 | https://picsum.photos/seed/-381598627/200/140 |
| /ec/dp/B0H000004 | LED Desk Lamp | $35.99 | https://picsum.photos/seed/-381598626/200/140 |

8. **`swarm list`** showed full task history (21 tasks, all completed; history saved to `.test-sessions/swarm-final-task-history.txt`) ✓
9. **`swarm close`** released the swarm session (confirmed removed from `list`) ✓

### Execution Context

**Key Commands:**

**Key decisions & workarounds:**
- **Selector discovery without a working capture:** `htmlsnapshot` (capture) failed twice with `ReferenceError: __pulsar_utils__ is not defined`, blocking the documented selector-discovery workflow. I discovered selectors by (a) reading the MockSite fixture sources in-repo (`HtmlRenderer.kt`, `ec-product.html`, `products.json`) and (b) probing with server-side `htmlsnapshot query --sql @file` which bypasses the stored snapshot entirely.
- **Stale driver pool recovery:** the first submission round left all 11 jobs stuck "queued" with a "Page fetch failed with status 1601" message. Backend logs showed the worker pool contained a retired browser context from a prior run. I followed the documented recovery path: `swarm close` → `swarm list --clear` → `swarm create` → resubmit. Everything completed in ~90s after that.
- **Image URL extraction:** the docs-recommended `DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img'))` pattern returned empty strings; `DOM_FIRST_SRC` doesn't exist in the engine. I probed variants via `htmlsnapshot query` and settled on `DOM_FIRST_ATTR(DOM, '#product-image', 'src')`, which returned the absolute image URLs. I then resubmitted the query batch with the fixed SQL.
- **Non-interactive stale-task handling:** `swarm list --clear` (rather than the TTY prompt) was needed since this shell is non-interactive.

---

## Issues Found (9 issues)

### Issue 1: Swarm worker pool reuses retired browser contexts from a prior session, stalling every submitted job

**Severity:** High
**Category:** Reliability

#### Reproduction

1) Run a swarm session and close it (or have any prior swarm session on the same backend). 2) ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4. 3) ./b4w.ps1 swarm query --sql @query.sql --seed-file urls.txt --refresh. 4) Poll swarm status/list. All tasks stay queued; status shows message 'Page fetch failed with status 1601'.

#### Expected Behavior

Creating a new swarm session should provision fresh browser contexts (or validate/retire stale ones), and jobs should complete normally.

#### Actual Behavior

All 11 jobs stuck in 'queued' for 60+ seconds with statusCode 201 and a fetch-failure message. Backend log (logs/pulsar.log): 'Context 2/#0 is not active | Retry(1601) rs: Driver pool exception, rsp: CRAWL | {closed,isRetired,retired,browser:007,...,startTime:2026-08-14T13:12:29Z}' — the pool handed workers a context created ~1h earlier by a previous session. Workers retried every 33s and never recovered. Workaround: swarm close + swarm list --clear + swarm create + resubmit (all jobs then completed).

#### Root Cause Analysis

MultiPrivacyContextManager (driver pool) returned a context marked closed/isRetired/retired from a previous session instead of creating or re-activating one. swarm create does not verify pool health, so a stale pool silently poisons all new jobs. This matches the known upstream pulsar-browser tab/context lifecycle quirks (frontDriver dangling after close).

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/SwarmService.kt (session create should force fresh contexts or evict retired pool entries; likely interacts with the driver pool in browser4-core/browser4-browser)`

#### AI Suggested Improvement

- On swarm create, evict closed/retired contexts from the driver pool (or recreate the pool) before accepting jobs
- Health-check the pool after create and surface a warning if any context is retired
- When a fetch fails with Retry(1601) due to a retired context, fail the task fast with a clear 'stale browser context' message instead of looping 33s retries while reporting 'queued'
- Consider proactively auto-recovering: detect the retired-context signature in the fetch error and restart the worker context once

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Stuck swarm tasks report lifecycleState 'queued' while carrying a fetch-failure message — contradictory status output

**Severity:** Medium
**Category:** UX

#### Reproduction

Reproduce the stale-pool condition above, then run: ./b4w.ps1 swarm status <task-id>. Output: lifecycleState 'queued', statusCode 201, but message 'Page fetch failed with status 1601...', and the CLI appends 'Note: Task is queued... A worker will pick it up shortly.'

#### Expected Behavior

A task that has failed a fetch attempt should show a failed/processing state, or the message should be empty while queued. Status output should not simultaneously claim failure and 'a worker will pick it up shortly'.

#### Actual Behavior

statusCode 201 / lifecycleState 'queued' persisted for 60+ seconds while the message contained a fetch failure; the optimistic 'A worker will pick it up shortly' note printed directly under a failure message. The user cannot tell whether the task will ever run.

#### Root Cause Analysis

The backend writes the fetch-failure message into the task record during a worker attempt but leaves statusCode at 201 (Created); the CLI renders the message verbatim and always prints the queued-note when statusCode==201. The two signals are not reconciled.

#### Code Pointer

`cli/browser4-cli/src/ (swarm status rendering — the 'queued' note logic) and browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/SwarmService.kt (task message/status update)`

#### AI Suggested Improvement

- Only print the 'A worker will pick it up shortly' note when message is empty or the task has never been attempted
- Move attempted-but-failed tasks to a distinct state (e.g. 'retrying') so users see progress instead of a frozen 'queued'
- Include attempt count / next-retry time in swarm status output for retrying tasks

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Error message example malformed: '-refresh' glued inside the quoted URL and single-dashed

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Trigger any fetch failure in swarm (e.g. stale-pool 1601 above) and read the task message: 'Example: swarm submit "http://localhost:18080/ec/dp/B0E000001 -refresh"'.

#### Expected Behavior

A copy-pasteable command with correct flag syntax, e.g. swarm submit "http://localhost:18080/ec/dp/B0E000001" --refresh (or --ignore-failure).

#### Actual Behavior

The example interpolates '-refresh' inside the quoted URL string, so the flag becomes part of the URL ('http://.../B0E000001 -refresh'), and uses single-dash '-refresh' instead of the actual '--refresh'. Copy-pasting it submits a broken URL.

#### Root Cause Analysis

String interpolation bug: buildString appends 'Example: swarm submit "${page.url} -refresh"' — the flag is concatenated inside the quotes instead of after them.

#### Code Pointer

`browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/crawl/common/XSQLScrapeHyperlink.kt:94 (extract() method)`

#### AI Suggested Improvement

- Change to: append("Example: swarm submit \"${page.url}\" --refresh")
- Use the canonical double-dash flag names (--refresh, --ignore-failure) consistently in user-facing messages
- Add a unit test asserting the example command parses correctly

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: DOM_FIRST_SRC documented as a common extraction function but not implemented in the X-SQL engine

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/dp/B0E000001" --sql @probe.sql where probe.sql contains SELECT DOM_FIRST_SRC(DOM, '#product-image') AS x FROM DOM_LOAD_AND_SELECT(@url, ':root'). Result: statusCode 417, message 'Function "DOM_FIRST_SRC" not found; SQL statement: ...'.

#### Expected Behavior

Every function listed in the docs (swarm.md line 106 lists DOM_FIRST_SRC among 'Common extraction functions') should exist, or the doc should not list it.

#### Actual Behavior

Query fails with statusCode 417 'Expectation Failed' and 'Function "DOM_FIRST_SRC" not found'. A grep of the entire repo source finds zero implementations of DOM_FIRST_SRC (the X-SQL engine comes from an external dependency).

#### Root Cause Analysis

The X-SQL engine (external pulsar dependency) provides DOM_SRC/DOM_ABS_SRC/DOM_FIRST_ATTR etc. but no DOM_FIRST_SRC. The docs were written against an imagined/renamed function. Doc lists it as available; users following the doc hit an opaque H2 error.

#### Code Pointer

`skills/browser4-cli/references/swarm.md:106 and skills/browser4-cli/references/x-sql-dom-functions.md (remove DOM_FIRST_SRC or map it to the real function)`

#### AI Suggested Improvement

- Remove DOM_FIRST_SRC from swarm.md's common-function list, or replace with DOM_SRC / DOM_FIRST_ATTR(DOM, selector, 'src')
- Add a doc test/CI check that greps every DOM_* function name mentioned in skills/ docs against the engine's registry to prevent doc drift

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Docs-recommended composable pattern DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img')) returns an empty string

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/dp/B0E000001" --sql @probe.sql with SELECT DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img')) AS a, DOM_SRC(DOM_SELECT_FIRST(DOM, 'img')) AS b FROM DOM_LOAD_AND_SELECT(@url, ':root'). Result: a = "" (empty), b = the full image URL.

#### Expected Behavior

Per swarm.md tip ('Use DOM_ABS_SRC(DOM) to get the resolved absolute URL... prefer DOM_ABS_SRC') and x-sql.md line 156 (SELECT DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img'))), the pattern should return the absolute image URL.

#### Actual Behavior

DOM_ABS_SRC returns an empty string on both DOM_SELECT_FIRST(DOM, '#product-image') and DOM_SELECT_FIRST(DOM, 'img'), while DOM_SRC on the same element returns the URL. First-round swarm results shipped with image_url: "" for all 10 products. Working alternative: DOM_FIRST_ATTR(DOM, '#product-image', 'src').

#### Root Cause Analysis

DOM_ABS_SRC appears to require DOM to be the img element itself (works when FROM is scoped to 'img'), not an element selected from a wider scope — or it relies on browser-resolved src that is absent in server-side parsing. Either way the documented composable pattern is broken for the common ':root'-scoped case.

#### Code Pointer

`skills/browser4-cli/references/swarm.md:108-110 and skills/browser4-cli/references/x-sql.md:156 (correct the recommendation); engine behavior lives in the external pulsar X-SQL dependency`

#### AI Suggested Improvement

- Fix the docs to recommend DOM_FIRST_ATTR(DOM, '<img-selector>', 'src') as the primary image-URL extraction, and only mention DOM_ABS_SRC for img-scoped FROM queries
- Add a worked example in swarm.md showing both patterns with their results
- If DOM_ABS_SRC on element selections is intended to work, file/verify against the engine and add a regression test in the E2E X-SQL scenarios

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: htmlsnapshot capture fails with 'ReferenceError: __pulsar_utils__ is not defined' in an existing session

**Severity:** Medium
**Category:** Reliability

#### Reproduction

With a pre-existing DEFAULT session (created by an earlier run), run: ./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001" then ./b4w.ps1 htmlsnapshot. Output: 'ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1'. Retrying produces the identical error.

#### Expected Behavior

htmlsnapshot captures the page HTML, enabling the documented selector-discovery workflow (inspect/get/summary).

#### Actual Behavior

Capture failed twice with the same ReferenceError, blocking the entire stored-snapshot workflow. Workaround: htmlsnapshot query --sql @file with DOM_LOAD_AND_SELECT(@url,...) works because it fetches server-side and bypasses the session.

#### Root Cause Analysis

The capture evaluates JavaScript that depends on __pulsar_utils__, a helper injected into the page by PulsarWebDriver during navigation. In this pre-existing session the injection did not happen for the new page (injection is likely attach-time or was skipped for the reused driver). Needs investigation: whether injection is tied to session creation and reused sessions skip it.

#### Code Pointer

`browser4-core/browser4-browser (PulsarWebDriver.kt wrapper — utils injection on navigate) and/or browser4-agentic htmlsnapshot tool executor`

#### AI Suggested Improvement

- Ensure utils injection (re)runs on every navigation, including in reconnected sessions — or detect the missing helper and re-inject before capture
- On this failure, surface a targeted error like 'page helper script missing — try reopening the session with open --fresh' instead of a raw ReferenceError
- Add an E2E scenario: create session, close backend-level tab state, reconnect, then htmlsnapshot

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: swarm create does not echo the applied options, so users cannot verify their configuration took effect

**Severity:** Low
**Category:** UX

#### Reproduction

Run: ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4. Output is exactly: 'Swarm session created: SWARM'.

#### Expected Behavior

Output confirming the session's effective settings (display mode, context count, tab limit, profile mode), e.g. 'Swarm session created: SWARM (HEADLESS, 2 contexts, 4 tabs/context)'.

#### Actual Behavior

Single-line output with no configuration details. There is no documented command to query an existing swarm session's configuration, so a user cannot confirm the options were honored without reading backend logs.

#### Root Cause Analysis

The create command's success path renders a static one-line message without including the session's resolved options.

#### Code Pointer

`cli/browser4-cli/src/ (swarm create output rendering; look for the 'Swarm session created' string)`

#### AI Suggested Improvement

- Echo resolved options in the create output (include defaults when flags are omitted)
- Consider a 'swarm info' subcommand showing current session settings, task counts, and worker pool health

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: swarm list shows a FINISHED timestamp on tasks that are still queued

**Severity:** Low
**Category:** UX

#### Reproduction

Submit swarm jobs and immediately run ./b4w.ps1 swarm list. Rows with STATUS 'queued' show a FINISHED timestamp identical to STARTED (e.g. STARTED 2026-08-14 22:23:24 FINISHED 2026-08-14 22:23:24, STATUS queued).

#### Expected Behavior

The FINISHED column should show '-' until the task actually completes (as some other queued rows correctly did).

#### Actual Behavior

Some queued tasks display a FINISHED timestamp while their STATUS is 'queued' — contradictory columns in the same row, misleading users about task progress.

#### Root Cause Analysis

The FINISHED column is likely rendered from lastModifiedTime (which is updated at submission and on each state change) rather than from an actual finishTime, and the renderer doesn't suppress it for non-terminal states.

#### Code Pointer

`cli/browser4-cli/src/ (swarm list table rendering — FINISHED column logic)`

#### AI Suggested Improvement

- Render FINISHED only when a real finish timestamp exists (terminal states); show '-' for queued/processing
- Align the task table with the swarm status JSON which already distinguishes lastModifiedTime from finishTime

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: No warning from swarm create about stale driver-pool state from prior sessions (non-interactive path)

**Severity:** Low
**Category:** Discoverability

#### Reproduction

After a previous swarm session has run and been closed on the same backend, run swarm create and submit jobs in a non-interactive shell. No warning is printed about pool/session state; jobs then stall per the stale-context issue.

#### Expected Behavior

swarm create should proactively surface stale state that will break job processing (docs only mention stale tracked tasks, which only warn/prompt in TTY).

#### Actual Behavior

No output beyond 'Swarm session created: SWARM'. Stale-task prompting (documented in swarm.md) silently does not happen outside a TTY, and the deeper problem — retired contexts in the driver pool — is never surfaced anywhere.

#### Root Cause Analysis

The stale-state check covers tracked tasks only (TTY-prompt), not driver-pool health; in non-TTY mode the check degrades to silence.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/SwarmService.kt (create path) and cli/browser4-cli/src/ (create output)`

#### AI Suggested Improvement

- Print a non-interactive-safe warning when stale tasks OR retired pool contexts are detected at create time (e.g. 'stale tasks found — use swarm create --clear-stale')
- Document the non-TTY behavior of the stale-task prompt in swarm.md

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

**Completion Status:** Successful — all 9 task steps completed (swarm session create, seed + X-SQL files, 10 extraction jobs via swarm query, 1 plain scrape via swarm submit, polling, result retrieval, task history listing, session close). All 10 products were extracted with correct title, price, and image URL. Two workarounds were required: a full swarm session restart to escape a stale driver pool, and swapping the image-URL function to DOM_FIRST_ATTR after the docs-recommended pattern returned empty strings.

**Success Rate:** 90% — every task step ultimately succeeded; the first submission round (11 jobs) was lost to the stale-pool stall and the first query revision returned empty image_url fields.

**Issues Found:** 9

**Major Blockers:** Stale retired browser contexts in the driver pool stalled all swarm jobs on the first attempt (Recovery: swarm close + swarm list --clear + swarm create + resubmit). htmlsnapshot capture was unusable in the pre-existing session (__pulsar_utils__ ReferenceError), forcing selector discovery through repo fixture sources and server-side htmlsnapshot query.

**Most Confusing Aspects:** 1) Tasks visibly stuck as 'queued' while carrying a fetch-failure message plus an optimistic 'a worker will pick it up shortly' note — status output actively contradicted itself. 2) The docs-recommended image-extraction pattern (DOM_ABS_SRC(DOM_SELECT_FIRST(...))) silently returned empty strings instead of an error, and DOM_FIRST_SRC (also documented) doesn't exist — so the doc-guided happy path produced empty data with no indication of which function was wrong. 3) The error-path recovery example in the status message was itself malformed (flag glued inside the URL).

**Most Valuable Improvements:** 1) Refresh or health-check the driver pool on swarm create so new sessions never inherit retired contexts. 2) Reconcile task status/message rendering so users can distinguish 'queued' from 'failed/retrying'. 3) Fix the X-SQL image-extraction documentation to match engine reality (DOM_FIRST_ATTR works; DOM_FIRST_SRC doesn't exist; DOM_ABS_SRC element-composition is broken). 4) Echo resolved options in swarm create output and add a swarm info command. 5) Fix the malformed '-refresh' example in XSQLScrapeHyperlink.kt.

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

#### Issue 1: Swarm worker pool reuses retired browser contexts from a prior session, stalling every submitted job

1) Run a swarm session and close it (or have any prior swarm session on the same backend). 2) ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4. 3) ./b4w.ps1 swarm query --sql @query.sql --seed-file urls.txt --refresh. 4) Poll swarm status/list. All tasks stay queued; status shows message 'Page fetch failed with status 1601'.

#### Issue 2: Stuck swarm tasks report lifecycleState 'queued' while carrying a fetch-failure message — contradictory status output

Reproduce the stale-pool condition above, then run: ./b4w.ps1 swarm status <task-id>. Output: lifecycleState 'queued', statusCode 201, but message 'Page fetch failed with status 1601...', and the CLI appends 'Note: Task is queued... A worker will pick it up shortly.'

#### Issue 3: Error message example malformed: '-refresh' glued inside the quoted URL and single-dashed

Trigger any fetch failure in swarm (e.g. stale-pool 1601 above) and read the task message: 'Example: swarm submit "http://localhost:18080/ec/dp/B0E000001 -refresh"'.

#### Issue 4: DOM_FIRST_SRC documented as a common extraction function but not implemented in the X-SQL engine

Run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/dp/B0E000001" --sql @probe.sql where probe.sql contains SELECT DOM_FIRST_SRC(DOM, '#product-image') AS x FROM DOM_LOAD_AND_SELECT(@url, ':root'). Result: statusCode 417, message 'Function "DOM_FIRST_SRC" not found; SQL statement: ...'.

#### Issue 5: Docs-recommended composable pattern DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img')) returns an empty string

Run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/dp/B0E000001" --sql @probe.sql with SELECT DOM_ABS_SRC(DOM_SELECT_FIRST(DOM, 'img')) AS a, DOM_SRC(DOM_SELECT_FIRST(DOM, 'img')) AS b FROM DOM_LOAD_AND_SELECT(@url, ':root'). Result: a = "" (empty), b = the full image URL.

#### Issue 6: htmlsnapshot capture fails with 'ReferenceError: __pulsar_utils__ is not defined' in an existing session

With a pre-existing DEFAULT session (created by an earlier run), run: ./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001" then ./b4w.ps1 htmlsnapshot. Output: 'ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1'. Retrying produces the identical error.

#### Issue 7: swarm create does not echo the applied options, so users cannot verify their configuration took effect

Run: ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4. Output is exactly: 'Swarm session created: SWARM'.

#### Issue 8: swarm list shows a FINISHED timestamp on tasks that are still queued

Submit swarm jobs and immediately run ./b4w.ps1 swarm list. Rows with STATUS 'queued' show a FINISHED timestamp identical to STARTED (e.g. STARTED 2026-08-14 22:23:24 FINISHED 2026-08-14 22:23:24, STATUS queued).

#### Issue 9: No warning from swarm create about stale driver-pool state from prior sessions (non-interactive path)

After a previous swarm session has run and been closed on the same backend, run swarm create and submit jobs in a non-interactive shell. No warning is printed about pool/session state; jobs then stall per the stale-context issue.

