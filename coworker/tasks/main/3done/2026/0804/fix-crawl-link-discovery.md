# Issues: crawl-link-options

> **Source:** `20260804-182105-crawl-link-options.full.md` | **Date:** 20260804-182105 | **Mode:** dev

## Scenario Background

### Task

The crawl scenario evaluation revealed that **only 1 of 4 acceptance criteria fully passed** (AC1). The remaining 3 are blocked by a critical server-side bug (`TaskLoops` Spring bean missing from crawl session contexts), rendering link discovery (depth ≥ 1) non-functional. The depth-0 seed file crawl (AC4) partially worked but exhibited flaky 0-byte fetch errors and missing page titles.

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — learned available commands; build initially failed with 2 compilation errors (fixed below)
2. `curl http://localhost:18080/generated/crawl/index.html` — verified MockSite was running
3. Read `skills/browser4-cli/references/crawl.md` — learned crawl command flags and conventions
4. Read `browser4-rest/.../CrawlService.kt` — analyzed backend crawl implementation
5. `./b4w.ps1 goto "http://localhost:18080/generated/crawl/index.html"` — verified hub page structure
6. `./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" --depth 0 --refresh` — AC1 ✅
7. `./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"` — AC2 ❌ (1 page, no discovery)
8. `./b4w.ps1 crawl ... -d 1 -ol "a.product" --refresh` — debugging (same failure)
9. `./b4w.ps1 crawl ... -d 1 -ol "a" --refresh` — debugging with broadest selector (same failure)
10. `./b4w.ps1 doctor log pulsar grep "crawl"` — discovered `TaskLoops` bean error in server logs
11. `./b4w.ps1 crawl "http://...index.html" --depth 3 --refresh` — AC3 ❌ (CLI warned no `-ol`, plus same bean error)
12. `./b4w.ps1 crawl --seed-file .test-sessions/seed-ac4.txt --depth 0 --refresh` — AC4 ⚠️ (2 URLs, but 0-byte error on product 3)
13. Multiple `curl` calls to inspect MockSite page structures and verify content

**Workarounds required:**
- Fixed 2 compilation errors in `cli/browser4-cli/src/main.rs` before any CLI commands would build:
  - Restored deleted `let paginate = (field == "html") && !empty_result;` line
  - Replaced `console::style(warning).yellow()` with plain `eprintln!("⚠ {}", warning)` (the `console` crate is not a dependency)
- Created `.test-sessions/` directory for temporary files

---

---

## Issues Found (8 issues)

### Issue 1: Crawl link discovery broken: TaskLoops bean not available in crawl session context

**Severity:** Critical
**Category:** Reliability

#### Reproduction

crawl http://localhost:18080/generated/crawl/index.html -d 1 -ol "a.product" --refresh

#### Expected Behavior

Crawl discovers 3 product out-links, loads them, and returns 4 pages total (1 seed + 3 depth-1).

#### Actual Behavior

Crawl completes with only 1 page (the seed). Backend logs show: 'No qualifying bean of type ai.platon.pulsar.loop.TaskLoops available' at CrawlService.kt:609 during session.submit(). The link discovery itself succeeds (3 out-links found), but submitting them for loading crashes because the TaskLoops Spring bean is not present in the context created by AgenticContexts.createSession().

#### Root Cause Analysis

CrawlService.crawlDepth1() creates a new session via AgenticContexts.createSession(), which builds a StaticAgenticContext that lacks the TaskLoops bean configured in Browser4AutoConfiguration. The session.submit() call at line 609 depends on AbstractPulsarContext.taskLoops (line 168: override val taskLoops: TaskLoops get() = getBean()), which fails because the static context has no such bean. depth=0 works because crawlDepth0() uses session.load() directly without calling session.submit().

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:609 — session.submit(hyperlink) call in crawlDepth1(); root fix should ensure the session created at line 509 has the TaskLoops bean available, or the crawl should reuse the Spring-managed session instead of creating an isolated one.`

#### AI Suggested Improvement

- In CrawlService, inject the Spring-managed session (from PulsarSessionManager) instead of creating an isolated session via AgenticContexts.createSession()
- Or: configure the AgenticContext created in CrawlService to inherit the Spring application context's beans
- Or: create a CrawlSessionFactory that produces sessions pre-wired with the TaskLoops bean
- Add a startup-time health check that verifies the TaskLoops bean is resolvable before accepting crawl requests

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Root cause is clearly identified (AgenticContexts.createSession() lacks the TaskLoops bean that session.submit() requires), and this directly blocks all crawl depth >= 1. The suggested fix direction — reusing the Spring-managed session or wiring the bean into the static context — is sound. This is the most impactful bug in the set.

---

### Issue 2: Rust build broken: undefined variable 'paginate' and missing crate 'console'

**Severity:** Critical
**Category:** Reliability

#### Reproduction

./b4w.ps1 help (or any command that triggers a rebuild)

#### Expected Behavior

Clean compilation and normal command output.

#### Actual Behavior

Compilation fails with two errors: (1) 'cannot find value paginate in this scope' at main.rs:6059 — the variable definition was removed during a refactor of handle_html_snapshot_get(); (2) 'cannot find module or crate console' at main.rs:6023 — console::style() was introduced but the console crate is not in Cargo.toml dependencies.

#### Root Cause Analysis

The git diff shows the refactor moved the empty_result check and introduced display_text/multi_match_warning, but accidentally deleted the line `let paginate = (field == "html") && !empty_result;` and added a console::style() call without adding the console crate dependency.

#### Code Pointer

`cli/browser4-cli/src/main.rs:6023 (console::style) and line 6029 area (paginate definition needed after empty_result is computed)`

#### AI Suggested Improvement

- Add back: `let paginate = (field == "html") && !empty_result;` after the empty_result line in handle_html_snapshot_get()
- Replace console::style(warning).yellow() with a plain eprintln! or add the console crate to Cargo.toml
- Add a pre-commit or CI check that compiles before merging to main

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Build-breaking regression from a refactor of handle_html_snapshot_get(). Two independent compilation errors (deleted `paginate` variable, missing `console` crate dependency) prevent any CLI invocation that triggers a rebuild. Should be fixed before any other work proceeds.

---

### Issue 3: Crawl failure errors not surfaced to CLI user — only visible in backend logs

**Severity:** High
**Category:** UX

#### Reproduction

Any crawl with depth >= 1 that hits the TaskLoops bean error.

#### Expected Behavior

User sees an actionable error: 'Link submission failed: crawler infrastructure unavailable' or similar.

#### Actual Behavior

CLI shows 'Crawl completed. 1 pages found.' with no indication of failure. The actual error ('No qualifying bean of type TaskLoops') is silently caught at CrawlService.kt:226 and only logged to the server log file. The user has no way to know the crawl failed to discover links unless they run 'doctor log'.

#### Root Cause Analysis

In CrawlService.submit(), the catch block at line 226 catches all exceptions during seed URL processing and records them as seedStatuses, but the CLI output doesn't display seedStatuses unless --verbose is used. Even with --verbose, the diagnostic field only captures the extractOutLinks failure case (line 542), not the session.submit() failure case.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:226-245 — error handling in submit(); cli/browser4-cli/src/main.rs — crawl result display logic`

#### AI Suggested Improvement

- When a crawl returns fewer pages than expected (depth >= 1 but only seed pages returned), automatically surface the diagnostic or seedStatuses in the CLI output
- Include the error message from caught exceptions in the crawl response, not just in server logs
- Add a warning in the CLI: 'Link discovery found N out-links but 0 were loaded — check server logs for errors' when link extraction succeeds but page loading fails

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Silent failure is a UX anti-pattern. When link submission fails, the user sees "1 pages found" with no indication that discovery was attempted and failed. The suggested fix — surfacing diagnostic/seedStatuses when link extraction succeeds but page loading fails — is the right approach. This compounds Issue 1 by hiding it from the user.

---

### Issue 4: Crawl output shows empty titles even for pages with valid <title> tags

**Severity:** Medium
**Category:** Reliability

#### Reproduction

crawl --seed-file <path> --depth 0 --refresh (AC4)

#### Expected Behavior

Each crawled page shows its <title> text (e.g., 'Widget Alpha — $10.00').

#### Actual Behavior

Output shows 'depth=0 | http://...product/1.html | ' with empty title column. The page HTML (verified via curl) contains <title>Widget Alpha — $10.00</title>.

#### Root Cause Analysis

Likely an issue in CrawlService.crawlDepth0() at line 457: document.title may return null or empty despite the page having a <title> tag, possibly due to how session.parse(page) handles the document or a timing issue in the parse pipeline. The 'Protocol not found' warnings in the server log suggest the fetch pipeline may be returning cached/stale pages where the parse step is skipped.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:455-463 — document.title extraction in crawlDepth0()`

#### AI Suggested Improvement

- Investigate whether session.parse(page) correctly extracts the title when the fetch pipeline returns cached content
- Add a fallback: if document.title is blank, try extracting <title> from the raw HTML via regex
- Add verbose logging when title extraction returns empty for a non-empty page

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Empty titles degrade crawl output usefulness. May be a downstream symptom of the parse pipeline skipping steps on cached content (related to the Protocol-not-found warnings noted in Issue 5). The fallback regex extraction is a reasonable belt-and-suspenders fix, but investigate the parse-pipeline skip first.

---

### Issue 5: Flaky 0-byte fetch errors in depth-0 seed file crawls

**Severity:** Medium
**Category:** Reliability

#### Reproduction

crawl --seed-file seed-ac4.txt --depth 0 --refresh (AC4, second seed URL)

#### Expected Behavior

Both seed URLs load successfully with content.

#### Actual Behavior

Product 3 (second seed URL) returns 'fetch returned 0 bytes (possible protocol handler not ready)'. The retry logic at CrawlService.kt:429-452 retries up to 3 times but still fails intermittently. The 'Protocol not found' warnings in logs indicate the HTTP protocol handler hasn't re-registered between session teardown and the next session's fetch.

#### Root Cause Analysis

Race condition in the HTTP protocol handler lifecycle. When crawlDepth0() closes the first session and immediately creates a new one for the next seed URL, the protocol handler may not have re-registered in time. The 500ms retry delay (FETCH_RETRY_DELAY_MS) may be insufficient, and the retry loop at line 439 checks contentLength == 0 but the protocol error at line 471 is caught separately.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:429-501 — crawlDepth0 fetch and retry logic`

#### AI Suggested Improvement

- Increase SEED_INTERVAL_MS (currently 100ms) between seed URL processing to allow more time for protocol handler cleanup
- Increase FETCH_RETRY_DELAY_MS (currently 500ms) or add exponential backoff
- Consider reusing the same session for all depth-0 seed URLs instead of creating/destroying sessions per URL

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Protocol handler race between session teardown and re-creation is a plausible root cause. The 100ms seed interval and 500ms retry delay are tight. Increasing SEED_INTERVAL_MS and FETCH_RETRY_DELAY_MS are low-risk first steps; session reuse across depth-0 seeds is a more invasive but higher-reward follow-up. Not a duplicate of Issue 1 — distinct failure mode (fetch pipeline vs. bean resolution).

---

### Issue 6: Task instructions for AC3 omit required --out-link-selector flag

**Severity:** Low
**Category:** Documentation

#### Reproduction

crawl http://localhost:18080/generated/crawl/index.html --depth 3 --refresh (as written in AC3 task instructions)

#### Expected Behavior

Crawl traverses 4 levels (0–3) of pages.

#### Actual Behavior

CLI correctly warns: 'Link discovery disabled (no --out-link-selector). Processing seed URLs only.' and returns only the seed page. The crawl.md reference clearly documents that --out-link-selector is required for link discovery, but the task scenario doesn't include it.

#### Root Cause Analysis

The task scenario was written assuming --depth alone enables link following, but the tool requires an explicit --out-link-selector. This is a documentation/task-design mismatch, not a tool bug — the tool's behavior is correctly documented.

#### AI Suggested Improvement

- Update AC3 task instructions to include `-ol "a[href]"` or `-ol "a.product"`
- Consider whether the CLI should have a sensible default out-link-selector (e.g., 'a[href]') when --depth > 0 but none is specified, rather than silently degrading to depth-0 behavior

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The tool correctly warns about the missing --out-link-selector; the bug is in the task instructions, not the code. The additional suggestion — a sensible default out-link-selector (`a[href]`) when --depth > 0 — is worth considering as a separate UX improvement but shouldn't block the doc fix. Related to Issue 8 (both are task-instruction documentation errors).

---

### Issue 7: No --help output for crawl subcommands (crawl status, crawl result, crawl cancel, crawl list)

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help crawl-status or ./b4w.ps1 crawl --help

#### Expected Behavior

Detailed help output for each crawl subcommand showing arguments and examples.

#### Actual Behavior

The main 'help' output lists crawl subcommands briefly. The crawl reference document (crawl.md) covers the main crawl command but doesn't document crawl-status, crawl-result, crawl-cancel, and crawl-list subcommands. A new user who sees 'Use --background for long-running crawls' wouldn't know how to check status without reading SKILL.md's crawl reference.

#### Root Cause Analysis

The crawl.md reference focuses on the primary crawl workflow. The status/result/cancel/list subcommands are mentioned but not documented with their own sections. The CLI --help for subcommands may not exist.

#### Code Pointer

`skills/browser4-cli/references/crawl.md — add subcommand documentation section`

#### AI Suggested Improvement

- Add a 'Subcommands' section to crawl.md documenting crawl status, crawl result, crawl cancel, and crawl list with examples
- Ensure each subcommand supports --help with argument descriptions
- Add a tip in the crawl output: 'Track progress: crawl status <id>' or 'List all crawls: crawl list'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The crawl subcommands (status, result, cancel, list) have no --help output and are only briefly mentioned in crawl.md. Users who start background crawls have no discoverable way to learn how to manage them. Adding subcommand sections to crawl.md and wiring up --help for each subcommand are both needed.

---

### Issue 8: SKILL.md notes $(./b4w.ps1) syntax doesn't work in bash — but task instructions require it

**Severity:** Low
**Category:** Documentation

#### Reproduction

Using $(./b4w.ps1) in bash as shown in task instructions.

#### Expected Behavior

Either the task instructions should use the correct invocation, or the SKILL.md note is wrong.

#### Actual Behavior

SKILL.md line 27 explicitly warns: 'The $(./b4w.ps1) <command> syntax shown in some task instructions does NOT work in bash — $(…) is command substitution, not invocation.' But the task instructions for this evaluation use exactly that syntax: '$(./b4w.ps1) <command>'. On Windows with Git Bash, ./b4w.ps1 works directly.

#### Root Cause Analysis

The task instructions were written for a different shell environment. On Windows Git Bash, `./b4w.ps1 <command>` (without the $(...) wrapper) works correctly. The $(...) wrapper is only correct in PowerShell where $(...) is the subexpression operator, not command substitution.

#### AI Suggested Improvement

- Update task templates to use platform-appropriate invocation syntax or clarify that the $(...) wrapper is PowerShell-specific
- Add a note to the SKILL.md that clarifies the PowerShell vs Bash invocation difference with concrete examples for each platform

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The $(./b4w.ps1) wrapper is PowerShell subexpression syntax, not valid in bash. Task instructions should either use platform-appropriate syntax or note the difference explicitly. Related to Issue 6 (both are task-instruction documentation errors); consider fixing them together in a single documentation pass.

---

## Overall Assessment

**Completion Status:** Partially Successful — AC1 passed fully, AC4 passed partially (2 URLs processed but one failed with 0-byte error, titles missing), AC2 and AC3 failed due to critical TaskLoops bean bug that prevents all link discovery. The core crawl functionality (depth 0 bulk fetch) works but is flaky. The crawl link discovery feature (the primary differentiator from a simple curl loop) is completely non-functional.

**Success Rate:** 30% — 1 of 4 ACs passed fully, 1 passed partially with errors, 2 completely blocked by backend bug

**Issues Found:** 8

**Major Blockers:** 1. TaskLoops Spring bean missing from crawl session context — prevents ALL link discovery (depth >= 1), which is the crawl command's core value proposition. Without this, crawl at depth >= 1 is equivalent to depth 0. 2. Rust build broken on current HEAD — requires manual code fixes before any CLI command can run, creating a dead-end for first-time evaluators.

**Most Confusing Aspects:** 1. The crawl silently fails at link discovery — it says 'Crawl completed. 1 pages found.' with no error indication, even though the backend threw a fatal exception. A new user would assume the selector was wrong, not that the infrastructure is broken. 2. The SKILL.md explicitly warns about $(./b4w.ps1) not working in bash, but the task instructions require that exact syntax. 3. The distinction between snapshot (accessibility tree for interaction) and htmlsnapshot (static HTML for extraction) requires reading the full SKILL.md — without that context, it's easy to use the wrong tool for extraction tasks.

**Most Valuable Improvements:** 1. Fix the TaskLoops bean wiring so crawl link discovery works — this is a one-line configuration change with massive impact. 2. Surface backend errors in CLI output — the silent failure pattern makes debugging impossible without server log access. 3. Add a pre-commit CI check that compiles the Rust binary before merge. 4. Add sensible defaults for --out-link-selector when --depth > 0 (e.g., default to 'a[href]' with a warning).

**Usability Rating:** 3/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Crawl link discovery broken: TaskLoops bean not available in crawl session context

crawl http://localhost:18080/generated/crawl/index.html -d 1 -ol "a.product" --refresh

#### Issue 2: Rust build broken: undefined variable 'paginate' and missing crate 'console'

./b4w.ps1 help (or any command that triggers a rebuild)

#### Issue 3: Crawl failure errors not surfaced to CLI user — only visible in backend logs

Any crawl with depth >= 1 that hits the TaskLoops bean error.

#### Issue 4: Crawl output shows empty titles even for pages with valid <title> tags

crawl --seed-file <path> --depth 0 --refresh (AC4)

#### Issue 5: Flaky 0-byte fetch errors in depth-0 seed file crawls

crawl --seed-file seed-ac4.txt --depth 0 --refresh (AC4, second seed URL)

#### Issue 6: Task instructions for AC3 omit required --out-link-selector flag

crawl http://localhost:18080/generated/crawl/index.html --depth 3 --refresh (as written in AC3 task instructions)

#### Issue 7: No --help output for crawl subcommands (crawl status, crawl result, crawl cancel, crawl list)

./b4w.ps1 help crawl-status or ./b4w.ps1 crawl --help

#### Issue 8: SKILL.md notes $(./b4w.ps1) syntax doesn't work in bash — but task instructions require it

Using $(./b4w.ps1) in bash as shown in task instructions.

