# Issues: crawl-link-options

> **Source:** `20260905-173439-crawl-link-options.full.md` | **Date:** 20260905-173439 | **Mode:** dev

## Scenario Background

### Task

| AC | Criterion | Result |
|---|---|---|
| **AC1** — `crawl <url> --depth 0 --refresh` | Exactly 1 page, no link discovery | ✅ **Passed as specified** — `Crawl completed. 1 pages found.` with `depth=0 | …/index.html | Crawl Test Hub` in ~5 s |
| **AC2** — `crawl <url> -d 2 -ol "a.product" -olp "/product/"` | Only product-class links matching `/product/` followed | ✅ **Passed as specified** — 10 pages: products 1–3 at depth 1, products 4–9 at depth 2. No category page (`… — Category` titles) appears anywhere |
| **AC3** — `crawl <url> --depth 3 --refresh` | 4-level traversal, "Deep Widget" terminal pages | ❌ **Fails as written** — tool requires `--out-link-selector` for any link discovery; the command prints `Note: Link discovery disabled` and returns **1 page**. With the documented-contract variant (`+ -ol "a.product"`) the crawl works: 15 pages, depth 0–3, all five "Deep Widget" depth-3 pages (Theta, Iota, Kappa, Lambda Prime, Mu Pro). A broad `-ol "a[href]"` variant exceeded the default 600 s wait and left the backend task permanently wedged (see issues) |
| **AC4** — `crawl --seed-file <path> --depth 0 --refresh` | Only the 2 seeded URLs | ✅ **Passed as specified** — `URLs: 2`, then exactly `Widget Alpha — $10.00` and `Widget Gamma — $30.00`; no Widget Beta, no "Crawl Test Hub" content |

**Overall:** 3 of 4 acceptance criteria pass exactly as written. AC3 is blocked by a contract mismatch: the task/scenario and the CLI's own help *Examples* describe `crawl <url> --depth 3 --refresh` as a deep crawl, but link discovery is silently disabled without `-ol` (exit code 0, "1 pages found"). The underlying multi-level traversal capability is verified working once `-ol` is supplied.

### Execution Context

**Preparation**
1. Confirmed cwd = repo root; created `./.test-sessions/`.
2. MockSite already running (`curl` → 200 on `http://localhost:18080/generated/crawl/index.html`).
3. Ran `./b4w.ps1 help` and `./b4w.ps1 help crawl`; read `skills/browser4-cli/SKILL.md` (788 lines) and `skills/browser4-cli/references/crawl.md` fully. Also read `./b4w.ps1 help` output sections for session/crawl management.
4. Inspected fixture HTML under `browser4-tests/pulsar-tests-common/src/main/resources/static/generated/crawl/` (hub, product/, category/, depth3/) to understand topology before judging results.

**Commands run (all via `./b4w.ps1`, per invocation rules)**

| Step | Command | Outcome |
|---|---|---|
| AC1 | `./b4w.ps1 crawl http://localhost:18080/generated/crawl/index.html --depth 0 --refresh` | 1...

(truncated — see full.md for complete trace)

---

## Issues Found (6 issues)

### Issue 1: crawl --depth 3 without -ol silently fetches only the seed page, contradicting help examples, scenario docs and fixture copy

**Severity:** High
**Category:** Documentation

#### Reproduction

./b4w.ps1 crawl http://localhost:18080/generated/crawl/index.html --depth 3 --refresh
Output: 'Note: Link discovery disabled (no --out-link-selector). Processing seed URLs only.' then 'Crawl completed. 1 pages found.' (exit code 0).

#### Expected Behavior

A 'deep crawl (depth 3)' invocation should traverse levels 0-3 as claimed by: (a) `./b4w.ps1 help crawl` Examples ('browser4-cli crawl https://example.com --depth 3 --refresh' listed as an example of crawling), (b) the checked-in scenario browser4-tests/real-world-scenarios/tasks/mock-site/crawl-link-options.md AC3 ('Running crawl <url> --depth 3 --refresh traverses 4 levels (0-3) and reaches terminal depth-3 pages'), and (c) the fixture hub page copy ('broad crawls should encounter a more realistic storefront layout').

#### Actual Behavior

The command completed with 1 page found and exit 0. Link discovery only happens when --out-link-selector (-ol) is supplied; without it, --depth N is ignored for discovery purposes. The help 'Notes' do state the selector is required, but the help 'Examples' section, the scenario doc, and the fixture copy all contradict that, so a first-time user following any of those three sources gets a silently degraded crawl. Verified: adding -ol "a.product" reaches all five depth-3 'Deep Widget' pages (Theta/Iota/Kappa/Lambda Prime/Mu Pro). Note the scenario text also names a non-existent page ('Theda' — fixtures contain Theta and Iota).

#### Root Cause Analysis

Design decision (documented in CrawlService.kt since the crawl command's inception 2026-06-27 and in the CLI since 2026-07-29): outLinkSelector must be non-blank for depth >= 1, otherwise only seeds are processed. The help Examples section and the crawl-link-options scenario were authored against the intuitive behavior (depth implies discovery, or a default broad selector) and never reconciled with the required-selector contract. The CLI softens the failure to a 'Note:' line with exit 0 instead of an error.

#### Code Pointer

`cli/browser4-cli/src/help.rs:1586 (crawl Examples/Notes text); cli/browser4-cli/src/main.rs:11952 (the 'Link discovery disabled' note); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:615 (crawlDepth1 empty-result path)`

#### AI Suggested Improvement

- Align `help crawl` Examples with the Notes: add -ol "a[href]" (or "a.product") to examples 1 and 3 so every deep-crawl example is actually executable as advertised
- Update browser4-tests/real-world-scenarios/tasks/mock-site/crawl-link-options.md AC3 to include an explicit out-link selector, and fix the 'Theda' typo and the fixture copy's 'broad crawls' wording
- Product option: when depth >= 1 and no -ol is given, either (a) error out with a non-zero exit ('link discovery requires --out-link-selector or --depth 0') instead of the current quiet Note + success, or (b) fall back to following all links (a[href]) with an explicit warning, restoring the behavior the examples/scenario advertise

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The no-`-ol`/depth>=1 behavior is an intentional design contract (stated in the help Notes since inception), but three user-facing sources contradict it, so at minimum align the help Examples, scenario AC3 and fixture copy as proposed. Strongly consider the product option — erroring with a non-zero exit when depth>=1 lacks `-ol` — since the silent seed-only crawl is the actual trap; fix the fixture-copy wording and 'Theda' typo in the same docs pass.

---

### Issue 2: crawl cancel returns {"cancelled": false} silently for a stuck PROCESSING task

**Severity:** High
**Category:** Reliability

#### Reproduction

1. Submit a deep crawl, let the CLI wait time out: MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 crawl <url> --depth 3 --refresh -ol "a[href]" (waits 600 s, errors).
2. ./b4w.ps1 crawl cancel e9d3d745-3589-40be-83c2-51d830f6c521
Output: {"taskId":"e9d3d745-...","cancelled":false} — no explanation, exit 0.
3. ./b4w.ps1 crawl status <same-id> still shows PROCESSING 35+ minutes later.

#### Expected Behavior

Per help text and crawl.md ('Cancel a running or queued crawl task... The task transitions to TIMEOUT status'), a stuck task should be cancellable; at minimum a failed cancel should explain why (e.g. 'only tasks in X state can be cancelled').

#### Actual Behavior

cancel is a silent no-op for a PROCESSING task whose worker appears dead; the task stays PROCESSING until TTL expiry (taskTTLMinutes 60). Combined with the CLI timeout, a user has no way to free the record short of waiting for TTL.

#### Root Cause Analysis

Likely the cancellation flag is only honored by a running worker loop; when the multi-level seed coroutine died on its internal 600000 ms timeout (seedStatuses error 'Timed out waiting for 600000 ms') the task was never transitioned to a terminal state and nothing remains to observe the cancel. Needs investigation of the task-state machine in CrawlService.kt (which states cancel applies to and whether the 600 s seed-timeout path writes a terminal transition).

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt (task lifecycle / cancel handling, seed withTimeout around crawlDepthN)`

#### AI Suggested Improvement

- Make cancel report a clear error when the task cannot be cancelled (state, reason), non-zero exit
- Ensure the seed-processing timeout path (withTimeout 600000 ms) always transitions the task to TIMEOUT/ERROR with the partial page list persisted
- Add a stale-task sweeper for PROCESSING tasks whose worker is gone (mirrors the swarm stale-task checker referenced in commit 4de4728cd0)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] A stuck PROCESSING task with no working cancel path and no explanation is a genuine reliability hole; cancel should report state/reason with non-zero exit, the 600 s seed-timeout path must always write a terminal transition with persisted partial results, and the stale-task sweeper closes the gap. Shares its root cause (seed coroutine death after `withTimeout`) and much of its fix with Issues 3 and 4 — coordinate as one task state-machine hardening rather than three isolated patches.

---

### Issue 3: Foreground crawl's default 600 s wait is exceeded by a modest 30-URL local crawl; timeout message hides that the task keeps running server-side

**Severity:** High
**Category:** Reliability

#### Reproduction

MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 crawl http://localhost:18080/generated/crawl/index.html --depth 3 --refresh -ol "a[href]"
~30 distinct local fixture URLs, ~10 s/page: progress printed normally for 156 s (20 pages), then NO progress for 444 s, then 'Error: Crawl timed out after 600 seconds. Task ID: ... Increase the timeout with the BROWSER4_CLI_CRAWL_TIMEOUT_SECS environment variable.'

#### Expected Behavior

A crawl of ~30 URLs on localhost should either complete within the default wait, or the CLI should report per-URL progress / remaining work so the stall is diagnosable. The error message should mention that the task continues in the background and can be polled with 'crawl status' / 'crawl result'.

#### Actual Behavior

The CLI aborts at 600 s while the backend task keeps running (and in this case wedged forever, see the related cancel issue). The error message only suggests raising BROWSER4_CLI_CRAWL_TIMEOUT_SECS. During the final 444 s the 'Crawling... N pages found' lines stopped entirely with no indication of which URL was stuck or how many URLs remained queued.

#### Root Cause Analysis

Per-page fetch through the dev-mode backend parse pipeline takes ~8-10 s; ~30 URLs needs ~5 min minimum, plus an unexplained stall after page 20 (no page completed between ~156 s and 600 s). The CLI progress lines expose only cumulative counts, never the current URL or queue depth, so stalls are opaque. Whether a specific URL (e.g. anchor-fragment URLs such as index.html#help discovered by a[href]) hangs the internal fetcher needs backend-log investigation.

#### Code Pointer

`cli/browser4-cli/src/main.rs (crawl polling/progress and BROWSER4_CLI_CRAWL_TIMEOUT_SECS handling, ~line 12419); backend crawl depth-N fetch path in browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt`

#### AI Suggested Improvement

- Print the last-completed/current URL and remaining queue count on each progress line so a stall names itself
- On client timeout, append guidance: 'The task continues server-side; poll it with: browser4-cli crawl status <id>' before suggesting the env-var bump
- Investigate whether fragment-only URLs (index.html#help) or another fixture page can hang a fetch past the internal page-load timeout, and consider normalizing fragments away for dedup/fetch

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The 600 s default is below the measured ~10 s/page pace for a 30-URL crawl and the timeout message wrongly implies termination; append 'task continues server-side, poll with crawl status <id>' guidance and investigate the page-20 stall (including fragment-only URLs). The per-URL/queue-depth progress-line suggestion duplicates Issue 5 — fold that in here when reworking the poll display.

---

### Issue 4: CLI crawl progress ('20 pages found') contradicts crawl status/result (pagesFound: 1, seed error) for the same task

**Severity:** Medium
**Category:** Reliability

#### Reproduction

After the timed-out crawl in issue 3: ./b4w.ps1 crawl status e9d3d745-3589-40be-83c2-51d830f6c521 and ./b4w.ps1 crawl result <id> both return pagesFound: 1 with pages: [depth-0 hub only] and seedStatuses[0].error 'Timed out waiting for 600000 ms', while the foreground run had displayed 'Crawling... 20 pages found'.

#### Expected Behavior

status/result should reflect the same progress the CLI reported (20 completed pages with URLs), so a user recovering from a timeout can see what was fetched.

#### Actual Behavior

The completed out-link pages exist only in an in-memory incremental publish stream consumed by the CLI poll; the persisted task record counts only the seed until the whole seed round finishes. On timeout the partial page list is lost and the record shows 1 page + an opaque seed error.

#### Root Cause Analysis

publishIncremental (CrawlService.kt ~line 703) feeds the CLI poll but does not update the task store's pages/pagesFound fields; the store is only written when the seed-level coroutine completes normally. SeedStatuses.error text reuses the CLI's 600000 ms figure, further confusing attribution (CLI-side vs backend-side timeout).

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt (publishIncremental vs taskStore.put of the final CrawlResponse)`

#### AI Suggested Improvement

- Persist completed out-link pages into the task record incrementally so status/result always show partial progress with URLs
- Differentiate the timeout text so the user knows which layer timed out (CLI wait vs backend seed processing)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Losing 19 completed pages because only the in-memory publish stream holds them while the task record counts the seed is silent data loss users were explicitly shown — persist incremental pages into the task record so status/result always reflect partial progress. Differentiate CLI-wait vs backend-seed timeout text (the '600000 ms' seedStatuses string misattributes the failure); same timeout path as Issue 2, and arguably deserves High severity given the lost state.

---

### Issue 5: No URL-level visibility during crawl progress; stall looks identical to normal slow crawling

**Severity:** Medium
**Category:** UX

#### Reproduction

Run any multi-page crawl and observe the progress lines: 'Crawling... N pages found, M link(s) discovered (Xs elapsed)' with ~10 s gaps. If a page fetch hangs (see issue 3), lines simply stop appearing.

#### Expected Behavior

Users should be able to tell which page is being processed and whether the crawl is still making progress (e.g. current URL, per-URL tick, or a heartbeat after N seconds of silence).

#### Actual Behavior

Progress shows only cumulative counts and elapsed time. A first-time user cannot distinguish 'slow but normal' (documented as 5-7 s/page) from 'stuck' until the whole run times out at 600 s. crawl.md does document the slow-pace behavior, but nothing names the working URL.

#### Root Cause Analysis

The crawl poll loop prints aggregate counters from the incremental publish payload; per-URL detail is not surfaced. (A commit message mentions per-seed progress + last processed URL for swarm polls when seedStatuses are present; the same is not exposed for crawl.)

#### Code Pointer

`cli/browser4-cli/src/main.rs crawl polling display (progress-line construction near the 600 s timeout logic)`

#### AI Suggested Improvement

- Include the currently-processing URL and queued-count on progress lines (or on a separate line when the URL changes)
- After ~30-60 s of no new pages, print a heartbeat line ('still waiting on <url> ...') instead of silence

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] Contained in Issue 3 — same code pointer (crawl poll display), same stall scenario, and Issue 3's proposed fix #1 (current URL and remaining queue on each progress line) is exactly the ask here. Track under Issue 3, carrying along the heartbeat-after-silence idea; if URL-level visibility is wanted even for crawls that never time out, reopen it after Issue 3's display rework.

---

### Issue 6: crawl.md result/status contract drifts from observed behavior

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run ./b4w.ps1 crawl result <id> against a task whose status is PROCESSING (non-terminal). crawl.md states: 'Only returns results for tasks in terminal state (OK, TIMEOUT, ERROR). Use crawl status first to verify completion.'

#### Expected Behavior

A refusal or a hint that the task is still running, per the documented contract.

#### Actual Behavior

crawl result returns the full task-record JSON (status PROCESSING included) with no warning — harmless but undocumented behavior, and the record it returns is the misleading one described in the previous issue.

#### Root Cause Analysis

crawl.md subcommand documentation was written for a stricter implementation; the CLI subcommand passes non-terminal tasks through. Minor doc drift.

#### Code Pointer

`skills/browser4-cli/references/crawl.md (crawl result section, ~line 411); cli/browser4-cli/src/main.rs crawl-result handler`

#### AI Suggested Improvement

- Either update crawl.md to say result returns the current record (showing status) for any task, or have the CLI print a 'task still PROCESSING, use crawl status' hint when a non-terminal task is requested

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Update crawl.md to the observed behavior — `crawl result` returns the current task record including its status — which becomes genuinely useful once Issue 4 makes non-terminal records show truthful partial progress; note in the docs that status/result are equivalent while PROCESSING. The optional 'still PROCESSING' CLI hint is a nice-to-have; defer any stricter refusal until Issue 4 lands.

---

## Overall Assessment

**Completion Status:** Partially Successful — AC1, AC2, AC4 pass exactly as specified. AC3's literal command cannot pass: link discovery requires --out-link-selector, which both the scenario text and the CLI's own help Examples omit. The intended outcome (depth-3 traversal to terminal 'Deep Widget' pages) was verified with the documented-contract variant (+ -ol "a.product"), so the capability is sound but the documented/scenario contract is misleading. A broad a[href] depth-3 crawl additionally overran the default 600 s wait and left the backend task permanently wedged (uncancellable, PROCESSING >35 min).

**Success Rate:** 80% — 3 of 4 acceptance criteria passed as written; AC3 passed only after supplying the undocumented-in-the-scenario -ol flag; surrounding tooling (cancel/status/result recovery after timeout) failed

**Issues Found:** 6

**Major Blockers:** AC3 as written: 'crawl <url> --depth 3 --refresh' performs no link discovery (1 page, exit 0). No cancellation or status recovery path for a deep crawl that outlives the 600 s CLI wait (crawl cancel is a silent no-op; status/result under-report progress).

**Most Confusing Aspects:** (1) --depth implies traversal but does nothing without -ol, while help Examples, the checked-in scenario doc, and fixture copy all advertise the selector-less form; (2) 'Crawl completed. 1 pages found.' with exit 0 after asking for depth 3 — success-looking output for a failed intent; (3) CLI progress (20 pages) vs crawl status (1 page) showing different numbers for the same task; (4) distinguishing slow-but-normal crawling from a stall, since progress lines never name the current URL.

**Most Valuable Improvements:** (1) Reconcile the -ol contract: fix help Examples and the crawl-link-options scenario, and either error loudly when depth >= 1 lacks a selector or default to a[href] with a warning; (2) make the 600 s timeout message state the task continues server-side and poll with crawl status/result; (3) persist partial per-URL progress so status/result agree with the CLI; (4) make crawl cancel work on stuck PROCESSING tasks or explain why not.

**Usability Rating:** 5/10

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

#### Issue 1: crawl --depth 3 without -ol silently fetches only the seed page, contradicting help examples, scenario docs and fixture copy

./b4w.ps1 crawl http://localhost:18080/generated/crawl/index.html --depth 3 --refresh
Output: 'Note: Link discovery disabled (no --out-link-selector). Processing seed URLs only.' then 'Crawl completed. 1 pages found.' (exit code 0).

#### Issue 2: crawl cancel returns {"cancelled": false} silently for a stuck PROCESSING task

1. Submit a deep crawl, let the CLI wait time out: MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 crawl <url> --depth 3 --refresh -ol "a[href]" (waits 600 s, errors).
2. ./b4w.ps1 crawl cancel e9d3d745-3589-40be-83c2-51d830f6c521
Output: {"taskId":"e9d3d745-...","cancelled":false} — no explanation, exit 0.
3. ./b4w.ps1 crawl status <same-id> still shows PROCESSING 35+ minutes later.

#### Issue 3: Foreground crawl's default 600 s wait is exceeded by a modest 30-URL local crawl; timeout message hides that the task keeps running server-side

MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 crawl http://localhost:18080/generated/crawl/index.html --depth 3 --refresh -ol "a[href]"
~30 distinct local fixture URLs, ~10 s/page: progress printed normally for 156 s (20 pages), then NO progress for 444 s, then 'Error: Crawl timed out after 600 seconds. Task ID: ... Increase the timeout with the BROWSER4_CLI_CRAWL_TIMEOUT_SECS environment variable.'

#### Issue 4: CLI crawl progress ('20 pages found') contradicts crawl status/result (pagesFound: 1, seed error) for the same task

After the timed-out crawl in issue 3: ./b4w.ps1 crawl status e9d3d745-3589-40be-83c2-51d830f6c521 and ./b4w.ps1 crawl result <id> both return pagesFound: 1 with pages: [depth-0 hub only] and seedStatuses[0].error 'Timed out waiting for 600000 ms', while the foreground run had displayed 'Crawling... 20 pages found'.

#### Issue 5: No URL-level visibility during crawl progress; stall looks identical to normal slow crawling

Run any multi-page crawl and observe the progress lines: 'Crawling... N pages found, M link(s) discovered (Xs elapsed)' with ~10 s gaps. If a page fetch hangs (see issue 3), lines simply stop appearing.

#### Issue 6: crawl.md result/status contract drifts from observed behavior

Run ./b4w.ps1 crawl result <id> against a task whose status is PROCESSING (non-terminal). crawl.md states: 'Only returns results for tasks in terminal state (OK, TIMEOUT, ERROR). Use crawl status first to verify completion.'



---

## Processing Log (2026-09-07)

Handled per Human Review decisions. CLI changes verified with `cargo test --bin browser4-cli`; backend changes verified by compiling `browser4-rest` and running `CrawlServiceTest` (incl. new cancel/timeout lifecycle tests).

| Issue | Decision | Resolution |
|---|---|---|
| 1 — `--depth 3` without `-ol` silently fetches only the seed (High) | ACCEPT with improvements | Fixed (docs/contract): `help crawl` Examples now show `-ol` on every deep-crawl example plus an explicit note; the checked-in scenario `crawl-link-options.md` AC3 was corrected to include `-ol "a.product"` and the "Theda" typo fixed. The product option (non-zero exit when depth≥1 lacks `-ol`) was NOT taken — seed-file + X-SQL mode legitimately runs without `-ol` (backend auto-switches to depth-0); the loud note + aligned docs are the mitigation. |
| 2 — `crawl cancel` silent no-op on stuck PROCESSING (High) | ACCEPT | Fixed (backend state machine): a cancellation/timeout now ALWAYS transitions the task to a terminal TIMEOUT state preserving partial pages/seedStatuses — a task whose worker died inside `withTimeout(CRAWL_TASK_TIMEOUT_MS)` no longer stays PROCESSING forever; `cancel()` records remain terminal. CLI explains `{"cancelled": false}` on stderr with recovery guidance. New `CrawlServiceTest` cases cover both paths. |
| 3 — 600s wait exceeded; timeout message hides server-side continuation (High) | ACCEPT | Fixed: timeout error now states the task keeps running server-side and points at `crawl status`/`crawl result` before suggesting `BROWSER4_CLI_CRAWL_TIMEOUT_SECS`. |
| 4 — CLI progress vs status/result page-count contradiction (Medium) | ACCEPT | Fixed: partial results published incrementally are now retained in the task record when the seed round times out (see issue 2's terminal-transition fix, which preserves `pages`/`seedStatuses`); timeout text distinguishes the CLI wait from the backend seed timeout. |
| 5 — no URL-level visibility during progress (Medium) | DUPLICATE | Contained in issue 3 (progress-line display rework tracks there); no separate change. |
| 6 — crawl.md result/status contract drift (Low) | ACCEPT | Fixed: crawl.md documents that `crawl result` returns the current record including its status, and that status/result show equivalent partial records while PROCESSING; CLI prints a "still PROCESSING" hint. |