# Issues: bulk-scale-routing

> **Source:** `20260804-191651-bulk-scale-routing.full.md` | **Date:** 20260804-191651 | **Mode:** dev

## Scenario Background

### Task

The task covered all six branches of SKILL.md §4b. Four of six acceptance criteria succeeded; two failed due to product/reliability issues.

| AC | Description | Status |
|----|-------------|--------|
| AC1 | Single list page via `htmlsnapshot query` | ✅ Success |
| AC2 | Multiple known URLs via `crawl --seed-file` | ✅ Success |
| AC3 | Crawl from start URL with link discovery | ❌ Failed |
| AC4 | Parallel execution with swarm | ❌ Failed |
| AC5 | Repeated monitoring with loop | ✅ Success (workaround) |
| AC6 | Few URLs in shell script | ✅ Success |

### AC1 — Single list page: ✅ SUCCESS
- Navigated to MockSite listing page, discovered `.product-card`, `.product-title`, `.product-price` selectors
- X-SQL query with `DOM_LOAD_AND_SELECT(@url, '.product-card')` returned 6 product rows with correlated fields
- Minor issue: `DOM_FIRST_HREF` returned empty; `DOM_FIRST_ATTR(DOM, '.product-link', 'href')` worked

### AC2 — Multiple known URLs via seed file: ✅ SUCCESS
- Crawl extracted 3 rows (url, title, price) from 3 MockSite product detail pages
- Took 428 seconds for 3 lightweight local pages

### AC3 — Crawl with link discovery: ❌ FAILED
- Crawl hub page loads in browser and `a.product` elements exist (confirmed via eval)
- Crawl's page-loader (non-JS HTTP fetch) does NOT see `.product` class — diagnostic: "The page has 12 anchors but `a.product` matched zero elements"
- Same issue confirmed with `htmlsnapshot query` via scrape API

### AC4 — Swarm parallel execution: ❌ FAILED
- Worker pool consistently stalls: 1/3 jobs completed first attempt, 0/3 second attempt
- `--wait` flag timed out at 300s with no completions

### AC5 — Loop monitoring: ✅ SUCCESS (with workaround)
- Loop subcommand cannot pass `-s <name>` through `--` separator
- Workaround: `session-default` to set target session, then loop works correctly

### AC6 — Shell loop: ✅ SUCCESS
- Bash loop iterated over 3 URLs, extracting title + price correctly

---

### Execution Context

**Key Commands:**

**Workarounds required:** `session-default` for loop flag passing, `DOM_FIRST_ATTR` instead of `DOM_FIRST_HREF`, 12-minute Maven build for MockSite

---

---

## Issues Found (12 issues)

### Issue 1: Swarm worker pool stalls — jobs stuck as 'queued' indefinitely

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1. swarm create --display-mode HEADLESS. 2. swarm query --sql @q.sql --seed-file urls.txt --refresh. 3. Observe 1/3 complete, 2 stuck. 4. Clear, recreate, resubmit. 5. 0/3 complete, all stuck for 300+s.

#### Expected Behavior

All swarm jobs should be picked up by workers and complete within a reasonable time.

#### Actual Behavior

First attempt: 1/3 completed, 2 stuck. Second attempt: 0/3 completed, all stuck. --wait timed out at 300s.

#### Root Cause Analysis

The swarm worker pool appears to have a race condition or initialization failure. The worker pool may depend on session state that was corrupted, or headless browser contexts may fail to initialize silently.

#### AI Suggested Improvement

- Add health-check endpoint for swarm worker pool status
- Surface worker initialization errors in swarm status output
- Add automatic worker restart on stall detection
- Add a --timeout-per-job flag instead of only the global --wait timeout

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Critical reliability bug — a stalled worker pool makes the swarm feature fundamentally unusable. The race condition or silent headless-context initialization failure needs root-cause investigation. The suggested health-check endpoint and per-job timeout are good mitigations.

---

### Issue 2: Task instruction uses $(./b4w.ps1) invocation that does not work in bash

**Severity:** High
**Category:** Documentation

#### Reproduction

On Linux/bash, follow task instruction to use $(./b4w.ps1) goto <url>. The $(...) is command substitution in bash, not invocation.

#### Expected Behavior

Either the task should specify the correct Linux invocation (./b4w.sh) or the wrapper scripts should be unified across platforms.

#### Actual Behavior

SKILL.md §Invocation explicitly warns: 'The $(./b4w.ps1) <command> syntax shown in some task instructions does NOT work in bash — $(…) is command substitution, not invocation.' Yet the task instructions mandate this exact syntax.

#### Root Cause Analysis

The task template hardcodes a Windows/PowerShell-specific invocation pattern that is incompatible with Linux/bash. The SKILL.md documentation correctly identifies this issue but the task instructions contradict it.

#### AI Suggested Improvement

- Update task templates to use platform-appropriate invocation (./b4w.sh on Linux, ./b4w.ps1 on Windows)
- Add a wrapper that auto-detects platform and selects the right script
- Consider a single ./b4w entry point that works cross-platform

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Task templates mandate a Windows/PowerShell-specific invocation pattern that fails on Linux/bash. SKILL.md already documents the correct platform behavior, so the templates need to be updated to match (use `./b4w.sh` on Linux, or add a cross-platform entry point).

---

### Issue 3: Crawl execution is extremely slow — 428s for 3 URLs, 600s timeout for link discovery

**Severity:** High
**Category:** Reliability

#### Reproduction

Run crawl --seed-file urls.txt --depth 0 --sql @query.sql with 3 local MockSite URLs. Observe a 428-second runtime. Run crawl with link discovery (-d 1 or 2) and observe 600s timeout.

#### Expected Behavior

Crawling 3 lightweight local pages should complete in under 30 seconds.

#### Actual Behavior

3 local URLs took 428 seconds (2+ minutes per page). Link discovery crawl timed out at 600 seconds while still waiting for the first page.

#### Root Cause Analysis

The crawl mechanism appears to use a full browser page load with extensive waiting. The 'waiting for first page' polling message suggests the page load is stalling or the load-detection heuristic is too conservative.

#### AI Suggested Improvement

- Reduce default page load timeout for simple static pages
- Add a 'fast mode' for local/static pages that skips heavy load detection
- Profile the page load pipeline to identify the bottleneck
- Consider using the scrape API (SimpleHttpFetcher) for depth-0 bulk fetches

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] 428 seconds for 3 local static pages (2+ min/page) is a clear performance regression or misconfiguration in the crawl pipeline. The "waiting for first page" polling suggests the load-detection heuristic is stalling. Profiling the page-load pipeline is the right next step.

---

### Issue 4: Crawl link discovery cannot see JavaScript-added CSS classes

**Severity:** High
**Category:** Product

#### Reproduction

1. Open crawl hub in browser — eval confirms a.product elements exist. 2. Run crawl with -ol a.product — diagnostic: 'The page has 12 anchors but a.product matched zero elements.'

#### Expected Behavior

Link discovery should work with the same selectors that work in the browser DOM.

#### Actual Behavior

The crawl's page-loading mechanism produces different HTML than the browser DOM. JavaScript-added CSS classes are invisible. X-SQL DOM_LOAD_AND_SELECT confirmed same issue.

#### Root Cause Analysis

The crawl uses a non-JS HTTP fetch that captures server-rendered HTML without executing JavaScript. Client-side class additions are invisible. This is a fundamental architectural gap.

#### AI Suggested Improvement

- Document clearly that crawl link selectors must match server-rendered HTML, not JS-modified DOM
- Add a --browser-links flag to extract links from the live browser DOM
- Update the diagnostic to mention this JS vs static HTML distinction
- Consider a 'crawl inspect' command that shows what the crawl fetcher sees

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] This is a documented architectural trade-off — crawl uses HTTP fetch (no JS execution) by design for throughput. Adding browser-DOM-based link extraction is a feature request. Mitigation: document the limitation clearly and add `crawl inspect` to show what the fetcher sees.

---

### Issue 5: MockSite requires undocumented 12+ minute Maven local build prerequisite

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Run ./bin/test.ps1 mock-site from a fresh checkout. It fails because browser4-rest and pulsar-tests-common JARs are not in local Maven repo. Requires ./mvnw install -pl browser4-rest -am -DskipTests (12+ minutes).

#### Expected Behavior

The task should document that MockSite depends on local Maven artifacts that must be built first, or provide a one-command setup step.

#### Actual Behavior

MockSite failed to start with dependency resolution errors. Required a 12-minute multi-module Maven build before MockSite could launch.

#### Root Cause Analysis

MockSite's Maven POM depends on sibling modules (browser4-rest, pulsar-tests-common) that aren't published to Maven Central. They must be mvn installed locally first.

#### AI Suggested Improvement

- Add a setup step to the task: './mvnw install -pl browser4-rest -am -DskipTests && pwsh ./bin/test.ps1 mock-site'
- Document expected build time (~12 minutes) in task prerequisites
- Consider pre-building these artifacts in CI to eliminate this step

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] A 12+ minute undocumented Maven build prerequisite is a significant onboarding barrier. MockSite's POM dependencies on sibling modules must be surfaced in task setup instructions or automated via a one-command bootstrap.

---

### Issue 6: Loop subcommand cannot pass -s <session> flag through -- separator

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.sh loop --name test --count 2 -i 10 -- -s price-watch eval 'document.title'. Observe: 'Error: Unknown command: price-watch'

#### Expected Behavior

The -s price-watch should be passed as arguments to the nested browser4-cli process.

#### Actual Behavior

The nested CLI interprets 'price-watch' as a command name. Argument parsing after -- drops flag-value association.

#### Root Cause Analysis

The argument tokenization after -- splits -s and price-watch into separate tokens that are passed independently. The nested CLI sees 'price-watch' as a positional argument.

#### Code Pointer

`cli/browser4-cli/src/loop.rs — function that assembles subcommand arguments for the nested browser4-cli process`

#### AI Suggested Improvement

- Fix argument assembly to preserve flag-value pairs
- Add a test case for loop subcommand with -s flag
- Document the known limitation in loop.md
- Consider adding --session-name as a loop-level flag

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Real argument-parsing bug. The `--` separator tokenizes `-s price-watch` into separate tokens, and the nested CLI misinterprets the value as a command. The loop subcommand needs to preserve flag-value pairs when assembling the nested command.

---

### Issue 7: session-default with swarm interaction causes session loss

**Severity:** Medium
**Category:** UX

#### Reproduction

1. swarm create. 2. session-default price-watch. 3. swarm close. 4. list — shows 'No active browser sessions' even though price-watch session should still exist.

#### Expected Behavior

Closing the swarm session should not affect other named sessions.

#### Actual Behavior

After swarm close + session-default changes, all sessions disappeared. The price-watch named session was lost.

#### Root Cause Analysis

Session lifecycle management has an interaction between session-default, swarm create (which sets default to SWARM), and swarm close.

#### AI Suggested Improvement

- Add session isolation guarantees — closing one session should never affect others
- Document the interaction between session-default and swarm session management
- Add a warning when session-default is about to overwrite an existing default

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Session lifecycle bug — `session-default` combined with `swarm create`/`swarm close` causes unrelated named sessions to be lost. Session isolation guarantees are missing; closing one session (swarm) should never destroy another (price-watch).

---

### Issue 8: Commands compile on every invocation making shell scripts slow

**Severity:** Medium
**Category:** UX

#### Reproduction

Run the AC6 shell loop script. Each ./b4w.sh invocation recompiles the Rust binary, adding ~0.5s per command. A 3-URL loop with 4 commands per URL adds ~6s of overhead.

#### Expected Behavior

The binary should be compiled once and reused across invocations.

#### Actual Behavior

Each ./b4w.sh invocation independently runs cargo, adding noticeable compilation overhead in scripts.

#### Root Cause Analysis

The ./b4w.sh script uses cargo run which has per-invocation overhead. No persistent dev server mode.

#### Code Pointer

`b4w.sh`

#### AI Suggested Improvement

- Build the binary once with cargo build and use the artifact directly
- Implement a daemon mode where the CLI stays resident

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Per-invocation `cargo run` overhead adds ~0.5s per command, compounding in shell scripts (6s overhead for a 3-URL loop). Building once with `cargo build` and using the artifact directly in `b4w.sh` is a low-effort, high-impact fix.

---

### Issue 9: DOM_FIRST_HREF returns empty while DOM_FIRST_ATTR with 'href' works

**Severity:** Low
**Category:** Product

#### Reproduction

In X-SQL query scoped to .product-card, DOM_FIRST_HREF(DOM, '.product-link') returns empty. DOM_FIRST_ATTR(DOM, '.product-link', 'href') returns correct relative URL.

#### Expected Behavior

DOM_FIRST_HREF should return the href attribute value.

#### Actual Behavior

DOM_FIRST_HREF returned empty for all 6 product cards. DOM_FIRST_ATTR works correctly.

#### Root Cause Analysis

DOM_FIRST_HREF may use a different DOM traversal or attribute resolution strategy than DOM_FIRST_ATTR. The scoped DOM context may affect how href resolution works.

#### AI Suggested Improvement

- Investigate the implementation difference between DOM_FIRST_HREF and DOM_FIRST_ATTR for href
- Add a test case for DOM_FIRST_HREF with scoped DOM and relative URLs
- Document any known limitations in x-sql.md

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Functional inconsistency — `DOM_FIRST_HREF` returns empty for scoped DOM with relative URLs while `DOM_FIRST_ATTR` for the same attribute works correctly. The implementation difference between these two paths needs investigation and a test case.

---

### Issue 10: htmlsnapshot capture HTTP timeout at 60s is too short and --timeout flag doesn't affect it

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.sh htmlsnapshot (after stale session). Observe 'HTTP request timed out [tool=html_snapshot_capture, timeout=60s]'. Using --timeout 120 does not change the 60s value.

#### Expected Behavior

The global --timeout flag should override the default 60s tool timeout.

#### Actual Behavior

Capture timed out at exactly 60 seconds. Passing --timeout 120 did not change the timeout value in the error message.

#### Root Cause Analysis

The HTTP client timeout for MCP tool calls appears to be separate from the --timeout global flag.

#### AI Suggested Improvement

- Make --timeout flag override the MCP HTTP request timeout
- Increase default htmlsnapshot capture timeout to 120s
- Document which timeouts --timeout affects

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The global `--timeout` flag should propagate to the MCP HTTP client timeout, but it doesn't. When `--timeout 120` is passed, the error message still shows 60s. The HTTP request timeout layer needs to read the global timeout setting.

---

### Issue 11: Constant Rust compilation overhead on every command invocation (~0.5s)

**Severity:** Low
**Category:** UX

#### Reproduction

Run any ./b4w.sh command. Observe 'Compiling browser4-cli ... Finished dev profile in 0.XXs' before every command.

#### Expected Behavior

In dev mode, the binary should be compiled once and reused, or a daemon mode should be available.

#### Actual Behavior

Every command invocation recompiles the Rust binary (~0.4-0.6s).

#### Root Cause Analysis

The ./b4w.sh wrapper uses cargo run which has per-invocation overhead.

#### Code Pointer

`b4w.sh — the cargo run invocation`

#### AI Suggested Improvement

- Build the binary once with cargo build and use the artifact directly in dev mode
- Add a 'dev install' command that builds and symlinks the binary

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] Same `cargo run` per-invocation compilation overhead described in Issue 8. Merge into Issue 8 and close this one.

---

### Issue 12: dead_code compiler warning clutters every command output

**Severity:** Low
**Category:** UX

#### Reproduction

Run any ./b4w.sh command. Observe: 'warning: constant QUICK_START_COMMANDS is never used --> src/help.rs:83:7'

#### Expected Behavior

Compiler warnings should be suppressed in normal operation.

#### Actual Behavior

Every command output includes a 7-line Rust compiler warning about an unused constant.

#### Root Cause Analysis

QUICK_START_COMMANDS in src/help.rs is defined but never referenced.

#### Code Pointer

`cli/browser4-cli/src/help.rs:83 — QUICK_START_COMMANDS constant`

#### AI Suggested Improvement

- Either use the QUICK_START_COMMANDS constant or remove it
- Add #[allow(dead_code)] attribute
- Consider cargo build --quiet in the wrapper script

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The `QUICK_START_COMMANDS` constant in `help.rs:83` is never referenced but produces a 7-line warning on every `cargo` invocation. Either use it or remove it — dead code in a shipped CLI is sloppy and the warning clutters all output.
---
**Cross-issue patterns:**
- **Timeout/infrastructure cluster** (Issues 1, 3, 10): Swarm stalls, crawl hangs, and the htmlsnapshot timeout all share a common signature — operations that should complete in seconds block for minutes or time out. A systemic investigation of the browser context initialization, page-load detection heuristics, and MCP HTTP timeout plumbing may resolve all three with shared fixes.
- **Documentation/platform drift** (Issues 2, 5): Task templates and setup instructions haven't been updated for Linux users or first-time contributors. These are high-leverage to fix because they affect every new user's first experience.
- **Dev-mode UX noise** (Issues 8/11, 12): The `./b4w.sh` wrapper and the codebase itself produce unnecessary output on every invocation. Building once + removing dead code eliminates two sources of friction with minimal effort.
- **Priority order for implementation**: Fix the reliability blockers first (1, 3, 10 → swarm/crawl/timeout), then the correctness bugs (6, 7, 9 → argument parsing, sessions, X-SQL), then the UX polish (8/11, 12 → compilation overhead, warnings), then documentation (2, 5 → task templates, MockSite setup). Defer Issue 4 as a feature request.

---

## Overall Assessment

**Completion Status:** Partially Successful — 4 of 6 ACs passed (AC1, AC2, AC5, AC6), 2 failed (AC3 crawl link discovery, AC4 swarm). Both failures stem from backend reliability issues.

**Success Rate:** 67% — 4 of 6 acceptance criteria fully satisfied. AC2 and AC5 required workarounds.

**Issues Found:** 12

**Major Blockers:** 1) Swarm worker pool consistently stalls — critical for any parallel extraction workflow. 2) Crawl link discovery cannot see JS-added CSS classes — makes crawl with link discovery unreliable for modern pages. 3) MockSite requires 12-minute local Maven build that is undocumented.

**Most Confusing Aspects:** 1) Task says to use $(./b4w.ps1) but SKILL.md says this doesn't work in bash. 2) Crawl and X-SQL query use a different page-loading mechanism than the browser, causing selector mismatches. 3) Session lifecycle management becomes confusing when mixing swarm, named sessions, and session-default. 4) The per-command Rust compilation overhead is unexpected for first-time users.

**Most Valuable Improvements:** 1) Fix swarm worker pool stalling. 2) Document or fix crawl/browser DOM mismatch for JS-added classes. 3) Fix loop subcommand -s flag passing. 4) Eliminate per-command Rust compilation overhead. 5) Remove dead_code warning from output. 6) Add a pre-built binary mode for dev use.

**Usability Rating:** 4/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Swarm worker pool stalls — jobs stuck as 'queued' indefinitely

1. swarm create --display-mode HEADLESS. 2. swarm query --sql @q.sql --seed-file urls.txt --refresh. 3. Observe 1/3 complete, 2 stuck. 4. Clear, recreate, resubmit. 5. 0/3 complete, all stuck for 300+s.

#### Issue 2: Task instruction uses $(./b4w.ps1) invocation that does not work in bash

On Linux/bash, follow task instruction to use $(./b4w.ps1) goto <url>. The $(...) is command substitution in bash, not invocation.

#### Issue 3: Crawl execution is extremely slow — 428s for 3 URLs, 600s timeout for link discovery

Run crawl --seed-file urls.txt --depth 0 --sql @query.sql with 3 local MockSite URLs. Observe a 428-second runtime. Run crawl with link discovery (-d 1 or 2) and observe 600s timeout.

#### Issue 4: Crawl link discovery cannot see JavaScript-added CSS classes

1. Open crawl hub in browser — eval confirms a.product elements exist. 2. Run crawl with -ol a.product — diagnostic: 'The page has 12 anchors but a.product matched zero elements.'

#### Issue 5: MockSite requires undocumented 12+ minute Maven local build prerequisite

Run ./bin/test.ps1 mock-site from a fresh checkout. It fails because browser4-rest and pulsar-tests-common JARs are not in local Maven repo. Requires ./mvnw install -pl browser4-rest -am -DskipTests (12+ minutes).

#### Issue 6: Loop subcommand cannot pass -s <session> flag through -- separator

./b4w.sh loop --name test --count 2 -i 10 -- -s price-watch eval 'document.title'. Observe: 'Error: Unknown command: price-watch'

#### Issue 7: session-default with swarm interaction causes session loss

1. swarm create. 2. session-default price-watch. 3. swarm close. 4. list — shows 'No active browser sessions' even though price-watch session should still exist.

#### Issue 8: Commands compile on every invocation making shell scripts slow

Run the AC6 shell loop script. Each ./b4w.sh invocation recompiles the Rust binary, adding ~0.5s per command. A 3-URL loop with 4 commands per URL adds ~6s of overhead.

#### Issue 9: DOM_FIRST_HREF returns empty while DOM_FIRST_ATTR with 'href' works

In X-SQL query scoped to .product-card, DOM_FIRST_HREF(DOM, '.product-link') returns empty. DOM_FIRST_ATTR(DOM, '.product-link', 'href') returns correct relative URL.

#### Issue 10: htmlsnapshot capture HTTP timeout at 60s is too short and --timeout flag doesn't affect it

./b4w.sh htmlsnapshot (after stale session). Observe 'HTTP request timed out [tool=html_snapshot_capture, timeout=60s]'. Using --timeout 120 does not change the 60s value.

#### Issue 11: Constant Rust compilation overhead on every command invocation (~0.5s)

Run any ./b4w.sh command. Observe 'Compiling browser4-cli ... Finished dev profile in 0.XXs' before every command.

#### Issue 12: dead_code compiler warning clutters every command output

Run any ./b4w.sh command. Observe: 'warning: constant QUICK_START_COMMANDS is never used --> src/help.rs:83:7'

