# Issues: bulk-scale-routing

> **Source:** `20260902-190521-bulk-scale-routing.full.md` | **Date:** 20260902-190521 | **Mode:** dev

---

## Issues Found (10 issues)

### Issue 1: Git Bash mangles leading-slash argument values (e.g. -olp "/product/") into Windows paths, silently breaking crawls; no wrapper mitigation, no doc warning

**Severity:** High
**Category:** Reliability

#### Reproduction

From Git Bash (MSYS2) on Windows, in the repo root:
./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/" --refresh
Result: "Crawl completed. 1 pages found." (only the seed page), exit code 0. Running the identical command from PowerShell (pwsh) discovers the expected pages (3 at depth 1; hub + products at depth 2).

#### Expected Behavior

The pattern argument "/product/" must reach the backend unchanged on every supported shell; the documented Git Bash wrapper (b4w.sh) should neutralize MSYS path conversion (MSYS2_ARG_CONV_EXCL / MSYS_NO_PATHCONV), or the docs must warn that leading-slash values are converted and to use relative patterns or PowerShell.

#### Actual Behavior

The backend received the pattern as 'C:/Program Files/Git/product/' (MSYS path conversion applied when bash spawns pwsh). Every link was filtered out by the pattern match, so the crawl silently returned only the seed page with exit 0 and no diagnostic at the CLI (see separate issue on diagnostic gating). A first-time user on Git Bash — the shell b4w.sh explicitly exists for — gets a silently wrong result and no hint of why.

#### Root Cause Analysis

MSYS2 converts arguments beginning with '/' to Windows paths when spawning native executables. b4w.sh wraps each argument in PowerShell single quotes (to protect flags from pwsh parameter parsing) but applies no MSYS2_ARG_CONV_EXCL / MSYS_NO_PATHCONV, and single-quoting does not stop conversion. The only place the mangled pattern becomes visible is the backend's 0-page diagnostic, which the CLI only prints in a different code path (page_count == 0).

#### Code Pointer

`b4w.sh (root of repo) — argument assembly loop; cli/browser4-cli/src/main.rs:11359 (diagnostic gating) and the crawl result renderer at main.rs:11301.`

#### AI Suggested Improvement

- Export MSYS2_ARG_CONV_EXCL='*' (or MSYS_NO_PATHCONV=1) around the exec pwsh invocation in b4w.sh when any argument starts with '/' but is not an existing path.
- In SKILL.md / crawl reference, add a Windows shell note: avoid leading-slash values for -olp/--out-link-pattern from Git Bash; use 'product/' or PowerShell.
- Consider having the backend surface the effective pattern in its empty-links diagnostic unconditionally (not only when the CLI reports 0 pages).

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed in b4w.sh: single-quote wrapping protects against pwsh parameter parsing but does nothing to MSYS path conversion; adding MSYS2_ARG_CONV_EXCL='*' (or MSYS_NO_PATHCONV=1) around the exec pwsh is the correct, low-risk fix, plus a doc note. Highest impact of the set because the wrapper exists precisely for Git Bash users and the failure is silent; ship together with Issue 4 so the next occurrence is diagnosable.

---

### Issue 2: crawl depth>=1 page listing shows batch-collapsed titles and duplicate URLs; page count nondeterministic across identical runs

**Severity:** High
**Category:** Product

#### Reproduction

crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/" --refresh (from PowerShell). Inspect the 'Crawling...' completion list. On the rebuilt backend: all pages discovered in one round share a single title (e.g. product pages 1-3 all titled 'Widget Beta' in one run, all 'Alpha' in a rerun), some URLs appear twice (6.html, 7.html, 4.html), and the total page count varies between identical runs (7-8 on this backend; the pre-rebuild 4.13.11 backend listed each page once with its correct distinct title and a stable count of 10).

#### Expected Behavior

Each discovered page appears exactly once with its own <title>, and identical runs over an unchanged site produce the same page count.

#### Actual Behavior

The crawl's own result listing is unreliable: per-round title collapse, duplicate entries, nondeterministic totals. Extracted X-SQL rows (AC2-style, crawl --depth 0) remain correctly attributed, so downstream per-page extraction is unaffected — only the crawl listing/diagnostics are wrong.

#### Root Cause Analysis

Best analysis: a shared/racing variable in CrawlService.kt result collection. In crawlDepth1 (~line 668) and crawlDepthN (~line 749) the parse handler builds CrawlPageResult with title = _document.title captured after the async parse resolves; when many pages resolve concurrently the title appears to be read from whichever document finished last in that round (hence per-round identical titles). Duplicate entries suggest the add/visited bookkeeping raced (e.g. visited-mark then submit not atomic) or seed overlap processing double-added pages. Needs maintainer verification with a concurrency-focused look at crawlDepthN's CompletableDeferred completion tracking.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt — crawlDepth1 parse handler (~line 668) and crawlDepthN parse handler/visited dedup (~line 749).`

#### AI Suggested Improvement

- Capture title/url per page from the page's own parse result instead of a shared 'last completed' variable; make the visited/add-to-results sequence atomic (single-threaded submission queue or synchronized block).
- Add a unit/integration test: crawl a hub with N concurrently-loading children and assert each child URL appears exactly once with its own title.
- Consider reporting crawl results from a deterministic order (by depth, then URL) so identical runs produce byte-identical listings.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The dedup race in crawlDepthN (visited add at line 759 after results.add at 748, and check-then-add at 770-788) plausibly explains duplicate entries and nondeterministic counts; per-document title capture in the shown code argues the title-collapse mechanism is elsewhere (possibly shared document state in crawlDepth1 or the renderer), so root-cause needs maintainer confirmation before fixing. Re-verify on a fresh current-source build (per Issue 5) and fix the depth-label half together with Issue 9; keep High because listing nondeterminism undermines crawl output trust.

---

### Issue 3: htmlsnapshot query returns a raw JSON envelope by default; readable table requires --format table which no SKILL.md/reference page mentions; server-side errors still exit 0

**Severity:** Medium
**Category:** UX

#### Reproduction

1) Run a successful query exactly as taught in SKILL.md section 4e: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @.test-sessions/ac1-list-query.sql — output is a single-line JSON object (id/statusCode/pageContentBytes/resultSet/...). 2) Run a query that references a nonexistent X-SQL function (e.g. DOM_COUNT) — the envelope carries statusCode 417 'Expectation Failed', but the process exit code is 0.

#### Expected Behavior

Human-oriented default output (the --format table output exists and is good); error status reflected in the exit code so scripts can detect failure; docs teaching the query should mention --format.

#### Actual Behavior

First-time users following the documented copy-paste template get an unreadable JSON envelope; the table flag is invisible outside --help. Scripted callers cannot distinguish success from failure via exit code (0 in both cases), forcing JSON parsing of stderr/stdout.

#### Root Cause Analysis

Default output mode for htmlsnapshot query is the raw JSON response envelope; --format table is implemented but not documented in SKILL.md section 4e or references/htmlsnapshot.md (its 'Output format' section documents the grep command only). The CLI maps X-SQL/HTTP failure responses to exit 0 for this command family (contrast: crawl warns 'X-SQL query failed on N page(s)' and is exit-aware).

#### Code Pointer

`cli/browser4-cli/src/main.rs — htmlsnapshot query dispatch/output rendering (default envelope, --format handling, exit-code mapping); skills/browser4-cli/SKILL.md:388-446 (section 4e) and skills/browser4-cli/references/htmlsnapshot.md (Query section).`

#### AI Suggested Improvement

- Add '--format table' to the section 4e template and the htmlsnapshot.md Query examples (as crawl.md already does for crawl).
- Consider defaulting interactive query output to a table and keeping the JSON envelope behind --format json / --json.
- Map non-200 statusCode in the query response to a nonzero exit code (with a --allow-errors escape hatch if needed).

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Clear-cut UX defect: the documented template yields an unreadable envelope and scripts can't detect errors via exit code. Docs fix (add --format table to SKILL.md 4e and htmlsnapshot.md) is trivial; the exit-code mapping and output-default change are the substantive parts — make the exit-code change non-interactive-safe and check it against existing tests that may assert exit 0.

---

### Issue 4: Crawl that discovers no out-links reports 'Crawl completed. 1 pages found.' with exit 0 and hides the backend's helpful diagnostic; docs say it 'Completes with 0 pages'

**Severity:** Medium
**Category:** UX

#### Reproduction

From Git Bash: ./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/" --refresh (any crawl where the out-link pattern filters everything). Output: 'Crawl completed. 1 pages found.' plus tips, exit 0. The backend diagnostic explaining the cause (e.g. the effective pattern that filtered all links) is only printed by the CLI when page_count == 0 — which never happens because the seed page is always counted.

#### Expected Behavior

A crawl that discovered zero out-links should say so explicitly (0 pages discovered / all N links filtered by pattern '...') with a nonzero or at least differentiated exit code, and should print the backend's diagnostic for the empty-links case.

#### Actual Behavior

The failure mode looks like success: exit 0, '1 pages found' (the seed page is counted), no diagnostic. During this evaluation the actual cause (MSYS-mangled pattern) went undiagnosed for hours partly because this path never surfaced the pattern it used.

#### Root Cause Analysis

The seed URL is included in page_count, so page_count == 0 never occurs for a crawl with a valid seed; the CLI's diagnostic block at main.rs:11359 is gated on exactly that condition. crawl.md:358's error table ('No links found (depth >= 1) -> Completes with 0 pages') describes a case the CLI cannot reach and does not mention the '1 pages found' wording or the counting of the seed.

#### Code Pointer

`cli/browser4-cli/src/main.rs:11301 ('Crawl completed. N pages found.') and :11359 (0-page diagnostic gating); skills/browser4-cli/references/crawl.md:358.`

#### AI Suggested Improvement

- Track discovered-out-links separately from the seed page: print '0 links discovered' with the backend's filtered-pattern diagnostic whenever out-link discovery yields nothing, and consider a distinct exit code (e.g. 3) or a warning marker.
- Fix crawl.md:358 wording to match actual CLI behavior ('Completes reporting only the seed page; verify --out-link-selector / pattern').
- Echo the effective --out-link-pattern in the completion line so shell-mangling issues become visible.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Confirmed structurally: page_count includes the seed, so the page_count == 0 gate at main.rs:11360 is unreachable for a valid seed, and the backend's filtered-pattern diagnostic (CrawlService.kt:620-623, which names the effective pattern) is exactly what's withheld. Track discovered-out-links separately from the seed count, fix crawl.md:358's wording, and treat as the reporting half of Issue 1 — the two should land and be tested together.

---

### Issue 5: Dev-mode auto-start silently reuses a stale runtime bundle that does not match checked-out sources

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1) Check out a source tree whose version differs from the last built runtime bundle (observed: sources 4.13.13-SNAPSHOT, bundle jars 4.13.11-SNAPSHOT, jars ~7 days older than Kotlin sources). 2) Run ./b4w.ps1 goto <url> — the daemon starts the backend from browser4-apps/browser4-bundle/target/runtime-bundle/_work without any staleness check ('Using existing local Browser4 runtime bundle' fast path). 3) The server behaves per the OLD code; only a later 'browser4-cli status'-style banner shows the version mismatch, after the fact.

#### Expected Behavior

Auto-start from the local source tree should serve the checked-out code, or should detect that the bundle is older than the sources and warn/rebuild before use.

#### Actual Behavior

The evaluation ran against a week-old backend for several hours; genuine regressions and artifacts of old code were indistinguishable from current-source behavior, leading to misdiagnosis (e.g. a crawl issue initially attributed to the current sources was an old-build artifact) and a costly rebuild detour.

#### Root Cause Analysis

cli/browser4-cli/src/daemon.rs existing_runtime_bundle fast path (~lines 4240-4330) trusts the on-disk bundle unconditionally; no comparison of bundle build time/jar version against source timestamps/version, and the docs' claim that the backend auto-starts 'from the local source tree' is only true when the bundle happens to be current.

#### Code Pointer

`cli/browser4-cli/src/daemon.rs — try_build_local_runtime_bundle / existing_runtime_bundle (~lines 4240-4330); related: browser4-apps/browser4-bundle/build-runtime-bundle.ps1.`

#### AI Suggested Improvement

- Compare the bundle jar version (or newest class-file timestamp) with the current project version before reuse; if stale, print an explicit warning naming the rebuild command, or trigger the rebuild automatically behind a flag.
- Surface the running backend version in the first command's output (not only in status) whenever it differs from the checked-out version.
- Document the bundle lifecycle in SKILL.md/CLAUDE.md: where the bundle lives, when it is rebuilt, and how to force a rebuild (rm -rf target/runtime-bundle/_work).

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The bundle-reuse fast path with no staleness check is a real dev-environment trap, and this evaluation itself demonstrates the cost (hours of misdiagnosis against 4.13.11 while sources were 4.13.13-SNAPSHOT). Minimum viable fix is a version/timestamp comparison with a loud warning naming the rebuild command; also flag that findings 1-4 and 9 were at risk of stale-backend contamination and should be re-validated on current sources once fixed.

---

### Issue 6: htmlsnapshot query intermittently returns 'No data. 0 rows returned.' on a fresh backend for a URL/page that later returns rows identically — state-dependent, contradicts documented cache semantics

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1) After (re)starting the backend and without any prior navigation/capture in the session, run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @.test-sessions/ac1-list-query.sql. Observed twice in a row: 'No data. 0 rows returned.' (exit 0) while curl on the same URL returns HTTP 200 with the expected 6 product cards. 2) Run ./b4w.ps1 goto <same-url> then ./b4w.ps1 htmlsnapshot (capture). 3) Re-run the identical query (with or without the URL argument) — now returns 6 rows, reproducibly, including across fresh CLI invocations.

#### Expected Behavior

The query must be deterministic for a given URL: either it always fetches (as the per-call fetch timestamps/ids suggest) or it always uses the cached snapshot (as references/htmlsnapshot.md states: 'get/query/export/inspect reuse the cache until the next capture or page navigation'). The docs never mention any first-run/state dependency, and no message indicates a fallback or empty cache.

#### Actual Behavior

A first-time user hitting this right after starting the backend sees an empty result for a page that demonstrably contains the data, with exit 0 and no explanation; the identical command later succeeds. The episode was not reproducible after warm-up, and no backend/CLI logs were available to explain the first-run failure.

#### Root Cause Analysis

Unresolved — needs maintainer investigation. Observed context: on a session with no prior navigation, the query's fetch path produced a page state that matched no rows; after an explicit goto+capture the same path produced the full DOM. Hypotheses to check: (a) query-with-URL races backend session warm-up (browser context created lazily and DOM_LOAD_AND_SELECT running before the first navigation completes); (b) stale/empty cached-snapshot fallback on first use; (c) the driver-normalized DOM (article -> div normalization, vi=/normalizedURI attributes; raw HTML is 13.5 KB vs ~10 KB snapshot DOM) interacting with capture timing. Additional observed doc contradiction: htmlsnapshot.md says query reuses the cache until the next capture, but every invocation shows a fresh fetch (new ids/timestamps, fresh pageContentBytes), and SKILL.md/help text disagree on whether query uses the stored snapshot or re-fetches.

#### Code Pointer

`browser4-rest — htmlsnapshot/X-SQL query service (DOM_LOAD_AND_SELECT fetch path); cli/browser4-cli/src/main.rs htmlsnapshot query dispatch; skills/browser4-cli/references/htmlsnapshot.md (Query section cache claims).`

#### AI Suggested Improvement

- Reproduce with backend logging enabled on first-run query after restart (no prior navigation); check whether the page fetch happens before the browser session's first navigation completes.
- Align docs with actual behavior: state clearly that query <url> fetches fresh per invocation (evidence: per-call ids/timestamps), and what exactly 'reuse the cache' means for query-without-url.
- On 0-row results, include the page title / matched-element count in the response so empty results are distinguishable from selector mismatches.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Observed deterministically at first-run (twice in a row) yet unreproducible after warm-up, so split the work: doc alignment is actionable now (query <url> fetches per invocation contradicts the "reuse the cache" text), while the code fix must wait for a reproduction with backend logging enabled. DEFER only the code-fix half, not the issue.

---

### Issue 7: build-runtime-bundle.ps1 cannot re-run over an existing build: jlink fails with 'directory already exists' and leaves a broken runtime (java.exe: could not open jvm.cfg)

**Severity:** Low
**Category:** Product

#### Reproduction

1) Build the runtime bundle once (build-runtime-bundle.ps1 -SkipMavenInstall). 2) Modify sources and run the same script again over the existing target/runtime-bundle/_work directory. 3) The jlink step fails ('directory already exists', exit 1), and the leftover partial runtime is unusable: java.exe reports 'could not open ... jvm.cfg'. Only a manual rm -rf of _work before re-running produces a working bundle.

#### Expected Behavior

Re-running the build script after a source change should succeed (overwrite or clean the jlink output), like any incremental build tool.

#### Actual Behavior

The script is not idempotent; a failed re-run leaves the previously working runtime broken, which can strand the dev backend (auto-start picks up the broken bundle).

#### Root Cause Analysis

The jlink output directory is pre-created or left over from the previous run, and jlink refuses to write into a non-empty directory; the script does not clean or version the jlink output before invoking jlink.

#### Code Pointer

`browser4-apps/browser4-bundle/build-runtime-bundle.ps1 (jlink step; -SkipMavenInstall parameter).`

#### AI Suggested Improvement

- Remove (or archive) the jlink output directory before each jlink invocation, or make the script detect an existing runtime and prompt/skip cleanly.
- On any build failure, leave the previous working bundle intact (build into a temp dir, then atomically swap) so a failed rebuild never breaks the running dev environment.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Plausible and cheap: jlink refuses non-empty output dirs and the script's jlink invocation (lines 1372-1388) has no pre-clean, so re-runs break the bundle. Fix compounds with Issue 5 — a broken bundle is exactly what the auto-start fast path will silently reuse — so clean (or temp-dir-and-swap) the jlink output before each run, and consider having daemon.rs detect a broken bundle.

---

### Issue 8: swarm close reports 'N locally tracked pending task(s) marked as failed (closed)' for tasks that had already completed

**Severity:** Low
**Category:** UX

#### Reproduction

1) swarm create (headless). 2) swarm query --sql @q.sql --seed-file seeds.txt; poll swarm status until all 3 jobs show completed; fetch swarm result (all rows present). 3) swarm close. Output: '3 locally tracked pending task(s) marked as failed (closed)'.

#### Expected Behavior

Closing a swarm whose tasks completed should say so ('3 completed task(s) closed') or at least not claim the tasks failed.

#### Actual Behavior

The close message implies data loss/failure of tasks that demonstrably completed and whose results were retrievable; immediately after close, swarm list showed the tasks as completed. Misleading for a first-time user deciding whether results were persisted.

#### Root Cause Analysis

Close-path wording apparently labels every still-locally-tracked pending task as 'failed' on close regardless of its actual terminal state (or the local tracker's view lags the swarm's completed state).

#### Code Pointer

`cli/browser4-cli/src — swarm close/result handling (message formatting); backend swarm task-state mapping in CrawlService/swarm endpoints.`

#### AI Suggested Improvement

- Query the actual terminal state of tracked tasks at close time and word the summary accordingly ('N completed / M pending tasks closed').
- If the tasks truly are marked failed on close in local tracking only, rename the message to make clear it is about local tracking, not swarm results.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Misleading "marked as failed (closed)" for demonstrably completed tasks is a wording/state-reconciliation bug that erodes trust in swarm persistence semantics for new users. Query terminal state at close time and word by actual state; if close genuinely force-marks pending tasks failed, say so explicitly and distinguish local tracking from swarm results.

---

### Issue 9: crawl progress output: 'waiting for first page (Ns elapsed)' lines repeat while pages are actively being processed; final listing labels seed and depth-2 pages uniformly as depth=1

**Severity:** Low
**Category:** UX

#### Reproduction

Run the depth-2 crawl from AC3 and watch stdout during the run: the same 'waiting for first page (N s elapsed)' line prints repeatedly while the crawl is demonstrably processing pages (pages appear in the final listing); in the completion listing, the seed page and depth-2 pages are all labeled with depth=1.

#### Expected Behavior

Progress lines should reflect actual state (pages processed / total) once fetching has started, and each listed page should show its real depth.

#### Actual Behavior

Progress output is noisy and misleading about what the crawl is doing, and the depth column in the result listing is unreliable (depth-2 pages reported as depth=1). Cosmetic, but it erodes confidence in the crawl's bookkeeping — which matters given the listing issues above.

#### Root Cause Analysis

Progress reporting appears to key off first-page arrival with a timer that re-prints on each poll tick; the listed 'depth' likely derives from the same shared/racing per-round state as the title collapse issue (CrawlService.kt crawlDepthN parse handler bookkeeping, ~line 749), which would also explain the uniform labels.

#### Code Pointer

`cli/browser4-cli/src/main.rs crawl progress rendering (~lines 11186-11196); browser4-rest/.../CrawlService.kt crawlDepthN (~line 749).`

#### AI Suggested Improvement

- Once the first page has arrived, print cumulative progress ('N pages, M links discovered') on each tick instead of repeating the waiting message.
- Fix the depth label together with the title-collapse fix (issue #2): each CrawlPageResult should carry its own depth captured at discovery time.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Two independent halves: the repeated 'waiting for first page' line is a CLI-side progress bug (once fetching starts, print cumulative pages/links instead), while the uniform depth=1 labels trace to backend bookkeeping shared with Issue 2 — fix that half together with Issue 2 and cover both in the regression test asserting per-page depth/title uniqueness. Cosmetic overall, but it compounds the trust damage from Issue 2.

---

### Issue 10: swarm create warns about stale completed tasks from previous sessions with no auto-clean or clear guidance

**Severity:** Low
**Category:** UX

#### Reproduction

1) Complete some swarm tasks in a session. 2) Start a later session (or after a backend restart) and run swarm create: output warns that N stale completed tasks from a previous session (e.g. 2026-09-01) exist. The swarm still works, but the user must discover and run swarm list --clear to silence the warning on future creates.

#### Expected Behavior

Either completed swarm tasks are cleaned automatically (or on session close), or the warning is actionable in place with a clear single command.

#### Actual Behavior

Every swarm create reminds the user of unrelated historical tasks; a first-time user cannot tell whether the warning indicates a problem with their new swarm. Functionally harmless but noisy and slightly alarming.

#### Root Cause Analysis

Swarm task records persist across sessions and swarm create reports the pre-existing completed tasks as stale without auto-cleaning them; the cleanup command (swarm list --clear) is not surfaced in the warning.

#### Code Pointer

`cli/browser4-cli/src — swarm create/state-loading warning; swarm list --clear handling.`

#### AI Suggested Improvement

- Auto-prune completed tasks older than the current session (or at swarm close), keeping only genuinely pending ones for crash recovery.
- Include the exact cleanup command in the warning text ('run: browser4-cli swarm list --clear').

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Harmless but noisy and slightly alarming; the cheap fix is including the exact cleanup command in the warning text and auto-pruning completed tasks older than the current session. Consider wording the warning as informational rather than a warning to avoid first-time-user confusion.

---

## Overall Assessment

**Completion Status:** Successful — all six bulk/scale scenarios (AC1-AC6) were exercised end-to-end and produced correct results; the task and the usability evaluation were both completed.

**Success Rate:** 85% — estimated proportion of task steps that succeeded on the first attempt. All 6 scenario groups ultimately succeeded; AC1 had a transient 0-row episode after a backend rebuild (resolved, unreproducible), AC3 was blocked in Git Bash by MSYS argument mangling (worked from PowerShell), and the environment itself required a stale-backend rebuild before further progress.

**Issues Found:** 10

**Major Blockers:** No hard blockers remained at the end, but two medium blockers consumed most of the session: (1) the dev-mode backend silently ran a week-old build (4.13.11-SNAPSHOT vs 4.13.13-SNAPSHOT sources), which invalidated several debugging conclusions until the bundle was rebuilt; (2) MSYS2 path conversion of a leading-slash --out-link-pattern value silently broke crawl link discovery from Git Bash, the shell the project's own wrapper (b4w.sh) targets.

**Most Confusing Aspects:** 1) Crawl failures that look like success ('Crawl completed. 1 pages found.', exit 0, no diagnostic) — the single most confusing behavior in the whole evaluation. 2) htmlsnapshot query defaulting to a raw JSON envelope while --format table is undocumented in the very section that teaches the query. 3) The documented 'snapshot cache' semantics that do not match observed behavior (every query re-fetches; a first-run query on a fresh backend returned 0 rows with no explanation). 4) Server-side query errors (statusCode 417 in the envelope) still exiting 0. 5) Dev-mode version skew between the auto-started backend and the checked-out sources. 6) Swarm/lifecycle messages that contradict observed state ('tasks marked as failed (closed)' for completed tasks; stale-task warnings for tasks from unrelated sessions).

**Most Valuable Improvements:** 1) Surface crawl diagnostics whenever out-link discovery yields nothing (un-gate main.rs:11359; don't count the seed as success) and echo the effective --out-link-pattern. 2) Make b4w.sh neutralize MSYS path conversion (MSYS2_ARG_CONV_EXCL) and document the Git Bash caveat for leading-slash values. 3) Staleness-check the auto-started runtime bundle against the checked-out sources and warn loudly before serving old code. 4) Fix the crawl listing bookkeeping (per-page title/depth capture, duplicate suppression — CrawlService.kt crawlDepth1/crawlDepthN). 5) Document --format table for htmlsnapshot query in SKILL.md section 4e, and make query failures exit nonzero. 6) Align SKILL.md/help/reference wording on whether htmlsnapshot query fetches fresh or uses the stored snapshot.

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

#### Issue 1: Git Bash mangles leading-slash argument values (e.g. -olp "/product/") into Windows paths, silently breaking crawls; no wrapper mitigation, no doc warning

From Git Bash (MSYS2) on Windows, in the repo root:
./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/" --refresh
Result: "Crawl completed. 1 pages found." (only the seed page), exit code 0. Running the identical command from PowerShell (pwsh) discovers the expected pages (3 at depth 1; hub + products at depth 2).

#### Issue 2: crawl depth>=1 page listing shows batch-collapsed titles and duplicate URLs; page count nondeterministic across identical runs

crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/" --refresh (from PowerShell). Inspect the 'Crawling...' completion list. On the rebuilt backend: all pages discovered in one round share a single title (e.g. product pages 1-3 all titled 'Widget Beta' in one run, all 'Alpha' in a rerun), some URLs appear twice (6.html, 7.html, 4.html), and the total page count varies between identical runs (7-8 on this backend; the pre-rebuild 4.13.11 backend listed each page once with its correct distinct title and a stable count of 10).

#### Issue 3: htmlsnapshot query returns a raw JSON envelope by default; readable table requires --format table which no SKILL.md/reference page mentions; server-side errors still exit 0

1) Run a successful query exactly as taught in SKILL.md section 4e: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @.test-sessions/ac1-list-query.sql — output is a single-line JSON object (id/statusCode/pageContentBytes/resultSet/...). 2) Run a query that references a nonexistent X-SQL function (e.g. DOM_COUNT) — the envelope carries statusCode 417 'Expectation Failed', but the process exit code is 0.

#### Issue 4: Crawl that discovers no out-links reports 'Crawl completed. 1 pages found.' with exit 0 and hides the backend's helpful diagnostic; docs say it 'Completes with 0 pages'

From Git Bash: ./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/" --refresh (any crawl where the out-link pattern filters everything). Output: 'Crawl completed. 1 pages found.' plus tips, exit 0. The backend diagnostic explaining the cause (e.g. the effective pattern that filtered all links) is only printed by the CLI when page_count == 0 — which never happens because the seed page is always counted.

#### Issue 5: Dev-mode auto-start silently reuses a stale runtime bundle that does not match checked-out sources

1) Check out a source tree whose version differs from the last built runtime bundle (observed: sources 4.13.13-SNAPSHOT, bundle jars 4.13.11-SNAPSHOT, jars ~7 days older than Kotlin sources). 2) Run ./b4w.ps1 goto <url> — the daemon starts the backend from browser4-apps/browser4-bundle/target/runtime-bundle/_work without any staleness check ('Using existing local Browser4 runtime bundle' fast path). 3) The server behaves per the OLD code; only a later 'browser4-cli status'-style banner shows the version mismatch, after the fact.

#### Issue 6: htmlsnapshot query intermittently returns 'No data. 0 rows returned.' on a fresh backend for a URL/page that later returns rows identically — state-dependent, contradicts documented cache semantics

1) After (re)starting the backend and without any prior navigation/capture in the session, run: ./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql @.test-sessions/ac1-list-query.sql. Observed twice in a row: 'No data. 0 rows returned.' (exit 0) while curl on the same URL returns HTTP 200 with the expected 6 product cards. 2) Run ./b4w.ps1 goto <same-url> then ./b4w.ps1 htmlsnapshot (capture). 3) Re-run the identical query (with or without the URL argument) — now returns 6 rows, reproducibly, including across fresh CLI invocations.

#### Issue 7: build-runtime-bundle.ps1 cannot re-run over an existing build: jlink fails with 'directory already exists' and leaves a broken runtime (java.exe: could not open jvm.cfg)

1) Build the runtime bundle once (build-runtime-bundle.ps1 -SkipMavenInstall). 2) Modify sources and run the same script again over the existing target/runtime-bundle/_work directory. 3) The jlink step fails ('directory already exists', exit 1), and the leftover partial runtime is unusable: java.exe reports 'could not open ... jvm.cfg'. Only a manual rm -rf of _work before re-running produces a working bundle.

#### Issue 8: swarm close reports 'N locally tracked pending task(s) marked as failed (closed)' for tasks that had already completed

1) swarm create (headless). 2) swarm query --sql @q.sql --seed-file seeds.txt; poll swarm status until all 3 jobs show completed; fetch swarm result (all rows present). 3) swarm close. Output: '3 locally tracked pending task(s) marked as failed (closed)'.

#### Issue 9: crawl progress output: 'waiting for first page (Ns elapsed)' lines repeat while pages are actively being processed; final listing labels seed and depth-2 pages uniformly as depth=1

Run the depth-2 crawl from AC3 and watch stdout during the run: the same 'waiting for first page (N s elapsed)' line prints repeatedly while the crawl is demonstrably processing pages (pages appear in the final listing); in the completion listing, the seed page and depth-2 pages are all labeled with depth=1.

#### Issue 10: swarm create warns about stale completed tasks from previous sessions with no auto-clean or clear guidance

1) Complete some swarm tasks in a session. 2) Start a later session (or after a backend restart) and run swarm create: output warns that N stale completed tasks from a previous session (e.g. 2026-09-01) exist. The swarm still works, but the user must discover and run swarm list --clear to silence the warning on future creates.

