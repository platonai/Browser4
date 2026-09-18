# Issues: extraction-method-routing

> **Source:** `20260902-200836-extraction-method-routing.full.md` | **Date:** 20260902-200836 | **Mode:** dev

## Scenario Background

### Task

**Status: Partially Successful.** All seven branches of the §4a extraction decision tree were exercised against MockSite (localhost:18080) with the locally-built backend (4.13.13-SNAPSHOT, auto-started by the CLI daemon; LLM configured via DEEPSEEK_API_KEY):

- **AC1 (interact → extract): FAILED as written** — form filling, dropdown select, checkbox toggling, and submission all worked (live DOM + AX tree show `submit-success:email`, `submitCount: 1`, and the entered values Ada/Lovelace/jp), but the required post-submit extraction via `htmlsnapshot` capture + `htmlsnapshot get text` **silently returned the pristine pre-interaction state** (`"No submission yet."`, empty `state-log`). Root-caused: the capture performs an independent fresh page load, not a serialization of the live tab DOM. Recovered only by the documented fallback (`eval --json`).
- **AC2 (single field): PASS** — `htmlsnapshot get text "#productTitle"` → `4K OLED TV 55`.
- **AC3 (all matches): PASS** — `htmlsnapshot get all text '[class*="product-title"]'` returned all 6 listing titles.
- **AC4 (correlated rows): PASS** — X-SQL `DOM_LOAD_AND_SELECT` query returned 6 aligned rows (URL/title/price/link) via `htmlsnapshot query --sql @file`.
- **AC5 (dynamic page logic): PASS** — `eval --file ... --json` returned a structured object (title, counts of buttons/links/forms/inputs, 5 headings), cross-verified against the AX snapshot.
- **AC6 (natural language): PASS** — `extract` returned title/price/rating/bullets matching the page ground truth (page has only 2 feature bullets; the model returned both, not the asked-for "top three" — correct behavior given page content).
- **AC7 (high volume): PASS** — `crawl --seed-file --depth 0 --sql @file --format table --refresh` produced one table row per seed URL (5 rows, aligned), though at ~80 s for 5 tiny local pages.

### Execution Context

**Key Commands:**

**Major steps:** (1) Read SKILL.md fully + help + the htmlsnapshot/x-sql/crawl/agent references. (2) AC1: filled first/last name/email, selected `jp`, checked newsletter + terms, submitted; on the first attempt the `htmlsnapshot` capture threw `__pulsar_utils__` errors on the pre-existing (restarted-backend) session — recovered via `open --fresh` and repeated the flow; post-submit `htmlsnapshot get text "#result-data"` returned stale `"No submission yet."` while live `eval`/AX tree showed the submission. (3) Diagnosed the divergence with probes (live `document.title` mutation + DOM markers absent from capture; capture does not reload the tab). (4) AC2–AC7 executed per criteria above; X-SQL/crawl query files and the eval script written under `.test-sessions/extraction-eval-20260903/`. (5) Traced the code path (`HTMLSnapshotToolExecutor.capture` → `AbstractPulsarSession/AbstractPulsarContext.capture` → `loadComponent.capture`, and the `__pulsar_utils__` injection polling in `InteractiveBrowserEmulator`) to ground the root-cause analysis.

**Workarounds required:** `open --fresh` to recover the htmlsnapshot family after a backend restart broke the reused session; `eval --json` for the post-submit confirmation extraction AC1 demanded of `htmlsnapshot`; quoting `@file` paths for `--sql` (as documented for PowerShell); `--file` for JS to avoid Windows quoting issues.

**Key decisions:** kept a single default session for the flow (auto-created by `goto`), used refs from the fresh interactive snapshot for interactions, re-captured/verified live state via two independent mechanisms (`eval` and the AX-tree snapshot) whenever a tool output seemed inconsistent — which is what surfaced the two headline issues.

---

## Issues Found (6 issues)

### Issue 1: htmlsnapshot capture silently ignores interactive-tab DOM state — post-interaction extraction returns stale (pre-interaction) content

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 goto "http://localhost:18080/generated/form-filling.html"  2) ./b4w.ps1 snapshot -i --stdout 3) ./b4w.ps1 fill <first-name-ref> "Ada"; fill <last-name-ref> "Lovelace"; fill <email-ref> "a@example.com" 4) ./b4w.ps1 select <country-ref> jp; check <newsletter-ref>; check <agree-terms-ref>; click <submit-ref> 5) ./b4w.ps1 htmlsnapshot (fresh capture) 6) ./b4w.ps1 htmlsnapshot get text "#result-data" -> prints "No submission yet." although the live DOM (./b4w.ps1 eval 'document.getElementById("state-log").textContent') shows submitCount:1 and lastSubmission with the entered values. Also: ./b4w.ps1 eval 'document.title="PROBE-TITLE"' then ./b4w.ps1 htmlsnapshot capture -> captured snapshot still reports the original server title; the live tab keeps PROBE-TITLE afterwards (tab is never reloaded).

#### Expected Behavior

Per SKILL.md §4a/§5 and htmlsnapshot.md, a fresh htmlsnapshot capture taken AFTER an interaction should reflect the JS-updated DOM (form submission results, toggles, dynamic updates), so htmlsnapshot get text "#result-data" should return the submitted values (Ada/Lovelace/jp...).

#### Actual Behavior

The captured snapshot always contains the state of a fresh, independent page load (pristine initial DOM: submitCount 0, empty fields, original <title>, no eval mutations). htmlsnapshot get/get all/inspect/export all read that stale copy and silently return pre-interaction data with exit code 0 and no warning. Only the AX-tree snapshot (live CDP view) and eval reflect the real tab state. AC1's documented workflow therefore fails silently.

#### Root Cause Analysis

HTMLSnapshotToolExecutor.capture() calls pulsarSession.capture(managed.driver) -> AbstractPulsarSession.capture -> AbstractPulsarContext.capture -> loadComponent.capture(normURL, driver). That is the fetch-pipeline page loader, which performs its OWN fresh load of the URL (own document, JS bootstrap runs, but no user interactions are replayed) and serializes that page. It does NOT serialize the live DOM of the interactive session tab, and it does not disturb that tab (probe: title mutation survives the capture; tab-list still shows 1 tab). Load-time JS state (e.g. default topics:[automation]) IS present in captures, which explains why docs believed 'capture reflects JS' — only load-time JS is reflected, never interaction-driven state.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:161 (capture(), calls pulsarSession.capture(managed.driver)); browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/session/AbstractPulsarSession.kt:227; browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/context/support/AbstractPulsarContext.kt:385 (context.capture -> loadComponent.capture)`

#### AI Suggested Improvement

- Change html_snapshot_capture so it serializes the LIVE DOM of the interactive session tab (e.g. CDP DOM.getOuterHTML/Runtime.evaluate on the current document of the active tab) so post-interaction state is captured as documented
- If the independent-load design is intentional, rename/clarify semantics ('capture = fresh render of the URL') and emit a warning when the stored copy's fingerprint (e.g. document.title, a content hash, submitCount-like markers) differs from the live tab DOM
- In the CLI, after capture print a hint when the page is known to have interaction state (e.g. session tab title/URL vs captured title mismatch) suggesting eval --json for live reads

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Top priority — silent stale data with exit code 0 is the worst failure class in the set. Implement live-DOM capture (CDP serialization of the active tab's document) but confirm product intent first: if the independent-load semantics are meant as archival capture, keep them under an explicit flag and always warn on fingerprint mismatch (title/content hash) with the live tab. Add an e2e test against the form fixture proving post-submit state is captured; docs (Issue 3) must be corrected in the same change either way.

---

### Issue 2: htmlsnapshot capture fails hard with internal 'ReferenceError: __pulsar_utils__ is not defined' on reused sessions whose page predates the backend process

**Severity:** High
**Category:** Reliability

#### Reproduction

1) Run the dev-mode CLI (./b4w.ps1 goto <url>) so the daemon auto-starts the backend and opens a session with a loaded page. 2) Stop the backend (e.g. dev rebuild/daemon restart) and invoke ./b4w.ps1 goto <url> again — it reconnects the existing session ('Using existing session DEFAULT; 2 tabs') instead of creating a new one. 3) Run ./b4w.ps1 htmlsnapshot -> 'ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1' followed by only the generic tool signature help. 4) Retrying or ./b4w.ps1 reload does not help; only ./b4w.ps1 open --fresh (new session) restores htmlsnapshot.

#### Expected Behavior

Either the capture works on the reconnected session, or the CLI reports a clear, actionable error (e.g. 'the page in this session is missing Browser4's injected helper — reconnect with open --fresh'), or it self-heals by re-injecting the helper script.

#### Actual Behavior

Every htmlsnapshot capture on the stale session throws the raw internal ReferenceError referencing the private __pulsar_utils__ identifier. No remediation hint is given; the htmlsnapshot family (capture/get/query-without-url/inspect/export) stays broken for the whole session until the user guesses to recreate the session. Other commands (eval, snapshot, click, fill) keep working on the same session, so the failure looks arbitrary.

#### Root Cause Analysis

The emulator polls for the injected helper before interacting: InteractiveBrowserEmulator.waitForJavascriptInjected()/isScriptInjected() evaluate 'typeof(__pulsar_utils__)'; the comment there admits 'For some type of pages, the script can not be injected'. When the session's tab was created by an earlier backend process (dev-mode restart) or injection otherwise never registered for that document, isScriptInjected keeps returning false, the code only logs a warning and proceeds, and the next hard evaluation of __pulsar_utils__ (e.g. getOriginalContentLength() at InteractiveBrowserEmulator.kt:790-803) throws the ReferenceError that surfaces verbatim as the tool error. Injection is registered for new documents, which is why a fresh session works and a reused one never recovers.

#### Code Pointer

`browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/impl/InteractiveBrowserEmulator.kt:640-690 (waitForJavascriptInjected/isScriptInjected) and :790-803 (getOriginalContentLength/_captureMetaLinks); failure surfaces through HTMLSnapshotToolExecutor.capture() (browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:161)`

#### AI Suggested Improvement

- When isScriptInjected() fails after retries, either re-inject the helper directly into the current document (driver.evaluate of the utils source) or abort the capture with an actionable message instead of proceeding to a guaranteed ReferenceError
- Map the raw JS ReferenceError in the tool boundary to a friendly error: 'page helper not injected — run `open --fresh` or re-navigate' and detect stale sessions at reconnect (session page created before backend start time) to auto-recover
- Add a regression test: restart backend mid-session, reconnect, and assert htmlsnapshot capture succeeds or fails cleanly with guidance

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Fix in the same round as Issue 1 since both surface through the same executor — fold error-surface work in. Prefer an actionable mapped error ('page helper missing — reopen session / open --fresh') plus reconnect-time staleness detection (tab created before backend start) over silent re-injection, which can be unsafe on some documents; treat re-injection as best-effort only, and add a regression test simulating a session whose tab predates the backend process.

---

### Issue 3: SKILL.md §4a/§5 and htmlsnapshot.md promise that re-capturing after an interaction reflects JS-updated content — contradicted by actual behavior; decision tree misroutes users

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/SKILL.md lines ~285-315 and ~488, and references/htmlsnapshot.md line ~35. Then execute the documented 'interact first' branch on http://localhost:18080/generated/form-filling.html: fill/select/check/submit, run htmlsnapshot, then htmlsnapshot get text '#result-data' — output is the pre-interaction 'No submission yet.' (see the Critical issue above for full repro).

#### Expected Behavior

The docs' claim 'Content added or modified by JavaScript before the capture (form submission results, dynamic updates, SPA route changes) is reflected — but only if you run htmlsnapshot (capture) after the interaction' and the decision-tree branch 'Need to interact first? -> snapshot + refs, then re-capture htmlsnapshot after interacting, then extract' should be accurate, or the docs should steer interaction-driven extraction to a method that works.

#### Actual Behavior

The claim holds only for JS that runs during a fresh page load (initial SPA rendering), not for state created by session interactions (form submissions, toggles, evals). A first-time user following the tree extracts silently stale data. The only doc branch that works for post-interaction state is the 'Page has JS-updated content (after interaction, form submit, SPA)? -> eval --json' branch, but the two branches contradict each other and the eval branch is listed below a tree line that already sent the user to htmlsnapshot re-capture.

#### Root Cause Analysis

The documentation was written from the mental model that htmlsnapshot capture serializes the current live DOM of the interactive tab; the implementation actually runs an independent fetch-pipeline load (see Critical issue). The two never diverge only on purely static pages, which hides the contradiction in most examples.

#### Code Pointer

`skills/browser4-cli/SKILL.md §4a decision tree and the '⚠️ htmlsnapshot capture requirements' note (~lines 285-315), Critical Warning ~line 488; skills/browser4-cli/references/htmlsnapshot.md 'Notes' section`

#### AI Suggested Improvement

- Rewrite the §4a note to state plainly: 'htmlsnapshot capture performs an independent fresh load of the URL; it reflects load-time JS but NOT state created by your clicks/fills/submits in the current tab. For post-interaction state use eval --json (or snapshot grep on the AX tree)'
- Reorder the §4a decision tree so the 'JS-updated content after interaction/form submit' branch (eval --json) comes before the generic 'interact first, then htmlsnapshot' branch, and qualify the latter with 'only if the interaction changes server-rendered content (e.g. navigation to a POST result page)'
- Add the divergence warning to htmlsnapshot.md error-handling/troubleshooting so the silent-staleness failure mode is discoverable

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Same root cause as Issue 1 — docs were written against the "capture = live DOM" model that the implementation violates. If Issue 1 fixes capture to serialize the live tab, the affected claims become true with no standalone work; if semantics are clarified/renamed instead, this issue's suggested rewrites apply inside Issue 1's change, so track nothing separately.

---

### Issue 4: extract: no progress indication during long LLM runs, default output is a bare file link, and the command's help text contradicts itself about default output

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." on http://localhost:18080/ec/dp/B0E000002 (LLM key configured). Also run ./b4w.ps1 help extract and compare the --filename option text ('Save extracted content to a file instead of printing to stdout') with the Notes ('Output is saved to a timestamped file by default').

#### Expected Behavior

The user should see progress/status while the ~70s LLM call runs, and either the content inline or a clear statement that it was saved to a file. Help text should agree on the default output destination.

#### Actual Behavior

The command printed nothing for ~70 seconds, then only '### Extracted content' plus a markdown link to a timestamped file under .browser4-cli/snapshot/. The file contains an ExtractResult JSON envelope whose 'description' field holds a JSON-encoded string with a plain-text answer (title/price/rating/bullets as label:value lines, not the requested JSON object), and 'metadata.completed' is false despite content being present. help output contradicts itself (--filename 'instead of printing to stdout' vs Notes 'saved to a timestamped file by default').

#### Root Cause Analysis

The extract result is delivered as an async task-style envelope (ExtractResult with description + metadata) written to a file by CLI default; the CLI shows only the file path (same pattern as snapshot). The sync-looking command gives no intermediate status; the LLM output formatting is not constrained to valid JSON even when requested.

#### Code Pointer

`cli/browser4-cli/src/commands.rs — extract command implementation (result-file handling and help text for --filename/--stdout); backend ExtractResult assembly`

#### AI Suggested Improvement

- Print a short status line when extract starts ('Extracting with <provider>… this can take ~1 min') so long silent runs are not mistaken for hangs
- On completion print the extracted content inline by default (or keep the file link but show the first N chars), and make --stdout/--raw the documented default for 'as JSON' prompts
- Fix the help contradiction so --filename and the default-destination Notes agree; consider validating JSON output when the user asks for JSON (or document that the envelope must be unwrapped)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid Low UX; defer to a polish round batched with Issues 5 and 6 (they share output/status/TTY concerns in commands.rs). When scheduled, fix the self-contradicting help text first (definite defect, small change), add a starting status line for long LLM runs, and separately verify whether `metadata.completed: false` with content present is a real flag bug or by-design.

---

### Issue 5: crawl foreground progress output is repetitive and noisy: identical lines repeat many times, no final duration summary

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 crawl --seed-file ".test-sessions/extraction-eval-20260903/ac7-seeds.txt" --depth 0 --sql "@.test-sessions/extraction-eval-20260903/ac7-query.sql" --format table --refresh (5 seeds on localhost). Capture stdout to a file.

#### Expected Behavior

A clean progress display (e.g. carriage-return updates or sparse status lines) and a concise completion line with elapsed time and row count.

#### Actual Behavior

Output contained ~15 duplicate lines: 'Crawling... 3 pages found so far' x2, 'Crawling... 4 pages found so far' x10, and the identical line 'Crawling... 4/5 seeds done, 4 pages found, 4 rows extracted (http://localhost:18080/ec/d... / Smartphone 128GB) (56s elapsed)' repeated at 56s/66s/76s. Final summary '5 pages crawled, 5 rows extracted.' appeared with no duration. The run took ~80s for 5 tiny localhost pages (~16s/page) with no per-page progress detail (page-level progress appears only at seed granularity).

#### Root Cause Analysis

The progress reporter appears designed for a TTY (overwrite-in-place), and when stdout is redirected/not a TTY the overwrite is emitted as repeated full lines; the identical '4/5 seeds done' line is also re-printed on each poll tick rather than only on state change. Per-page time is dominated by the fetch pipeline's per-page load/settle waits.

#### Code Pointer

`cli/browser4-cli/src/commands.rs — crawl progress reporting (progress/task-status poller); fetch settle timings in the backend load pipeline`

#### AI Suggested Improvement

- Detect non-TTY stdout and collapse progress updates: print one line per state change, or suppress progress entirely and print a final summary with duration and per-page rows
- Include elapsed time and per-seed breakdown in the final 'N pages crawled, N rows extracted' line
- Consider a --quiet progress mode for scripted use

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Valid Low UX polish; group with Issues 4 and 6. Implement TTY detection once and share it: on non-TTY, collapse crawl progress to one line per state change and end with a duration and per-seed summary; per-page settle-time concern is backend-side and out of scope for the CLI fix.

---

### Issue 6: htmlsnapshot query's default human output is a raw single-line JSON envelope with internals (statusCode, pageContentBytes, event, timestamps) — readability/format flags are not discoverable from the main help

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 htmlsnapshot query --sql @query.sql (no --format). Main help (./b4w.ps1 help) shows only 'htmlsnapshot query [url] — Run X-SQL...' with no mention of --format; the --format table option is only mentioned in the detailed 'help htmlsnapshot' output ('Use --format table for human-readable output').

#### Expected Behavior

A first-time user should get human-readable default output (table) or be told about --format table from the main help / a tip after the first query.

#### Actual Behavior

Default output is a ~1.2KB single-line JSON envelope mixing result data with transport internals (id, statusCode, pageStatusCode, pageContentBytes, event, createdTime, startedTime, finishTime...). Readable only with jq or by discovering --format table in the sub-help. Minor but real friction for the documented AC4-style workflow where the tutorial never passes --format.

#### Root Cause Analysis

The query tool returns the raw scrape-task JSON, and the CLI prints it verbatim when no --format is given; the abbreviated main-help entry omits output-format options that exist only in detailed help.

#### Code Pointer

`cli/browser4-cli/src/commands.rs — htmlsnapshot query command output handling and its help text`

#### AI Suggested Improvement

- Default query output to the same table renderer crawl uses (or auto-detect TTY: table on TTY, JSON envelope when piped/--json), keeping --json for machine use
- Add 'use --format table for readable output' to the main help line and to the tip printed after the first raw-envelope query

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid; the simplest item in the batch — reuse crawl's existing table renderer for query output (table on TTY, JSON envelope when piped/`--json`) and add the `--format table` hint to the main help line plus a post-first-query tip. Deferring alongside Issues 4 and 5 keeps severity-based prioritization consistent while fixes for Issues 1–3 land.

---

## Overall Assessment

**Completion Status:** Partially Successful — the evaluation was completed end-to-end. AC2, AC3, AC4, AC5, AC6, AC7 all passed. AC1 (interact first, then extract via a fresh HTML snapshot) failed its verification step: after filling and submitting the form successfully (live DOM and AX tree show submit-success with the entered values), htmlsnapshot capture + htmlsnapshot get text silently returned the pre-interaction page state ('No submission yet.', empty fields), because the capture performs an independent fresh load instead of serializing the live tab DOM. The interaction part of AC1 was salvaged using eval --json (the documented fallback for JS-updated state), but the acceptance criterion as written could not be satisfied on the MockSite form fixture.

**Success Rate:** 86%

**Issues Found:** 6

**Major Blockers:** One core-workflow blocker: htmlsnapshot capture does not reflect interactive-tab DOM state, so the documented 'interact -> re-capture htmlsnapshot -> extract' flow returns silently stale data on any JS-interactive page (Critical, AC1). Secondary blocker: on sessions reused across a backend restart, htmlsnapshot capture fails permanently with an internal 'ReferenceError: __pulsar_utils__ is not defined' and only a fresh session (open --fresh) recovers (High).

**Most Confusing Aspects:** 1) SKILL.md's prominent promise that htmlsnapshot capture reflects JS-updated content after interactions is false in practice — the two documented extraction branches for the same scenario contradict each other, and the wrong one silently returns stale data. 2) The __pulsar_utils__ ReferenceError leaks an internal identifier with no hint that recreating the session fixes it. 3) extract runs ~70s with zero output, then shows only a file link (plus self-contradicting help). 4) For a tool named around 'snapshot', having two different snapshots (live AX tree vs independent HTML fetch) with the same workflow vocabulary is a persistent source of confusion.

**Most Valuable Improvements:** 1) Make html_snapshot_capture serialize the live tab DOM (or clearly flag and warn when it serves a fresh independent load) so post-interaction extraction works as documented (Critical). 2) Self-heal or clearly explain the missing page-helper injection on reused sessions (High). 3) Correct SKILL.md §4a/§5 so the decision tree routes post-interaction JS-state extraction to eval --json and says capture reflects load-time state only (Medium).

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

#### Issue 1: htmlsnapshot capture silently ignores interactive-tab DOM state — post-interaction extraction returns stale (pre-interaction) content

1) ./b4w.ps1 goto "http://localhost:18080/generated/form-filling.html"  2) ./b4w.ps1 snapshot -i --stdout 3) ./b4w.ps1 fill <first-name-ref> "Ada"; fill <last-name-ref> "Lovelace"; fill <email-ref> "a@example.com" 4) ./b4w.ps1 select <country-ref> jp; check <newsletter-ref>; check <agree-terms-ref>; click <submit-ref> 5) ./b4w.ps1 htmlsnapshot (fresh capture) 6) ./b4w.ps1 htmlsnapshot get text "#result-data" -> prints "No submission yet." although the live DOM (./b4w.ps1 eval 'document.getElementById("state-log").textContent') shows submitCount:1 and lastSubmission with the entered values. Also: ./b4w.ps1 eval 'document.title="PROBE-TITLE"' then ./b4w.ps1 htmlsnapshot capture -> captured snapshot still reports the original server title; the live tab keeps PROBE-TITLE afterwards (tab is never reloaded).

#### Issue 2: htmlsnapshot capture fails hard with internal 'ReferenceError: __pulsar_utils__ is not defined' on reused sessions whose page predates the backend process

1) Run the dev-mode CLI (./b4w.ps1 goto <url>) so the daemon auto-starts the backend and opens a session with a loaded page. 2) Stop the backend (e.g. dev rebuild/daemon restart) and invoke ./b4w.ps1 goto <url> again — it reconnects the existing session ('Using existing session DEFAULT; 2 tabs') instead of creating a new one. 3) Run ./b4w.ps1 htmlsnapshot -> 'ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1' followed by only the generic tool signature help. 4) Retrying or ./b4w.ps1 reload does not help; only ./b4w.ps1 open --fresh (new session) restores htmlsnapshot.

#### Issue 3: SKILL.md §4a/§5 and htmlsnapshot.md promise that re-capturing after an interaction reflects JS-updated content — contradicted by actual behavior; decision tree misroutes users

Read skills/browser4-cli/SKILL.md lines ~285-315 and ~488, and references/htmlsnapshot.md line ~35. Then execute the documented 'interact first' branch on http://localhost:18080/generated/form-filling.html: fill/select/check/submit, run htmlsnapshot, then htmlsnapshot get text '#result-data' — output is the pre-interaction 'No submission yet.' (see the Critical issue above for full repro).

#### Issue 4: extract: no progress indication during long LLM runs, default output is a bare file link, and the command's help text contradicts itself about default output

./b4w.ps1 extract "Return the product title, displayed price, rating, and the top three feature bullets as JSON." on http://localhost:18080/ec/dp/B0E000002 (LLM key configured). Also run ./b4w.ps1 help extract and compare the --filename option text ('Save extracted content to a file instead of printing to stdout') with the Notes ('Output is saved to a timestamped file by default').

#### Issue 5: crawl foreground progress output is repetitive and noisy: identical lines repeat many times, no final duration summary

./b4w.ps1 crawl --seed-file ".test-sessions/extraction-eval-20260903/ac7-seeds.txt" --depth 0 --sql "@.test-sessions/extraction-eval-20260903/ac7-query.sql" --format table --refresh (5 seeds on localhost). Capture stdout to a file.

#### Issue 6: htmlsnapshot query's default human output is a raw single-line JSON envelope with internals (statusCode, pageContentBytes, event, timestamps) — readability/format flags are not discoverable from the main help

./b4w.ps1 htmlsnapshot query --sql @query.sql (no --format). Main help (./b4w.ps1 help) shows only 'htmlsnapshot query [url] — Run X-SQL...' with no mention of --format; the --format table option is only mentioned in the detailed 'help htmlsnapshot' output ('Use --format table for human-readable output').

