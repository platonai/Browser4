# Issues: javascript-evaluation

> **Source:** `20260905-181500-javascript-evaluation.full.md` | **Date:** 20260905-181500 | **Mode:** dev

## Scenario Background

### Task

**All 8 task steps completed successfully.** The page was loaded, an interactive snapshot captured refs, and JavaScript was evaluated five ways — inline expression, `--json` object, `--file`, `--stdin`, and `--ref` (both flag and positional forms). Every evaluation method returned **correct and mutually consistent** output, verified against the accessibility-tree snapshot, native DOM collections, and (after a workaround, see below) a fresh htmlsnapshot/X-SQL extraction:

| Check | Expected | Got (all methods agree) |
|---|---|---|
| `document.title` (inline) | "Interactive Single Page" | ✓ |
| `--json` metadata (URL / title / links) | URL + title + 3 | ✓ `{"url":"http://localhost:18080/generated/interactive-1.html","title":"Interactive Single Page","links":3}` |
| `eval --file page_info.js` | images/links/forms counts | ✓ `{"images":2,"links":3,"forms":1}` |
| `eval --stdin` headings | 5 heading texts | ✓ 5 items incl. emoji headings |
| `eval --ref e2605` / positional `e2664` | H1 tag+text; link tag+text+href | ✓ |

One significant cross-path inconsistency was discovered and fully root-caused (stale server-side page cache; see Issues 1–2), including the workaround that unblocked verification.

### Execution Context

**Key Commands:**

**Major steps:**
1. **Setup:** Verified cwd = repo root; read `skills/browser4-cli/SKILL.md` fully (788 lines); ran `./b4w.ps1 help` (first command — daemon/backend already running, no build wait) and `eval --help` (rich, accurate flag docs). No prior browser4-cli knowledge assumed.
2. **Navigate + snapshot:** `goto` the fixture; `snapshot -i --stdout` produced the interactive tree with refs (e2605 H1, e2592 textbox, e2664 link, …) and page facts (5 headings, 3 footer links, 2 images, 1 form, `userForm`).
3. **Eval methods (steps 3–7):** All five invocation styles worked on the first attempt, including inline quoting through the bash→pwsh→CLI layers (the docs' quoting warnings did not bite for these expressions). `page_info.js` was written under `.test-sessions/` (an IIFE that console.logs counts and returns them — only the return value was printed; console.log is documented as not captured).
4. **Verification (step 8):** eval outputs cross-checked against snapshot refs and native DOM collections — all consistent. Cross-checking against the *documented extraction paths* (`htmlsnapshot get all`, X-SQL) initially **failed**: `get all text "a"` returned `[]` ("No elements matched", exit code 0), `DOM_FIRST_TEXT(DOM,'a')` was empty, and `htmlsnapshot export` produced HTML whose embedded `PulsarMetaInformation` was stamped **2026/9/3** — while the capture header claimed "captured 2026-09-06 02:01:12", the live DOM had 3 links, and the raw fixture HTML (curl) contained them.
5. **Root-cause work:** Navigating to the same URL with a cache-busting query (`?fresh=1`) made the *same* pipeline report "2 images · 3 links · 10 interactive elements" and extract the 3 link texts correctly — proving a stale **server-side page cache** keyed by URL was being served for the identical URL, days old, with misleading "captured now" labeling and no refresh flag exposed on the htmlsnapshot CLI. A background code exploration confirmed the mechanism: default `LoadOptions.EXPIRES` of *decades* (`LoadOptionDefaults.kt:16`), a fetch-state machine that serves records ≥3 days old without re-fetch (`LoadComponent.getFetchStateForExistPage`), cache-shell captures that deliberately skip persisting fresh content, and export serving the stored record verbatim — no element-stripping canonicalizer drops the footer; the stored record is simply 3 days old.
6. **Re-verification:** With the fresh-URL capture, `get all text "a"` → the same 3 link texts eval reported, and X-SQL `DOM_FIRST_TEXT(DOM,'a')`/`DOM_FIRST_ATTR(DOM,'img','alt')` → same values as eval. All eval methods are correct and consistent; the inconsistency lives in the caching layer of other extraction paths.

**Decisions/workarounds:** Used `--file`/`--stdin` for multi-line/complex JS per docs (inline also fine); kept all scratch files (`page_info.js`, `consistency_query.sql`, `stored-dom*.html`, `fixture-source.html`, evidence notes) under `.test-sessions/`; used a cache-buster query param as the workaround for the stale-cache defect; verified counts via a second independent mechanism (native DOM collections) rather than trusting any single path.



---

---

## Issues Found (5 issues)

### Issue 1: htmlsnapshot capture/export/query serve a days-old cached page for a same-URL page and label it as captured now

**Severity:** High
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 goto "http://localhost:18080/generated/interactive-1.html"
2) ./b4w.ps1 htmlsnapshot   # header prints 'captured 2026-09-06 02:01:12', summary '6 interactive elements' with 0 links
3) ./b4w.ps1 htmlsnapshot get all text "a" --stdout   # prints [] 'No elements matched "a"', exit code 0
4) ./b4w.ps1 htmlsnapshot export .test-sessions/x.html   # exported HTML has no footerLinkTop/pageFooter and embeds PulsarMetaInformation date-time="2026/9/3 04:21:09" (3 days stale)
5) ./b4w.ps1 eval 'document.querySelectorAll("a").length'   # 3 — live DOM does contain the footer links; snapshot -i also shows them
6) Repeat step 2 on the same URL plus a query param (?fresh=1): the same pipeline now reports '2 images · 3 links · 10 interactive elements' and get all text "a" returns the 3 links.

#### Expected Behavior

A fresh `htmlsnapshot` capture of the session's current page stores the current live DOM (SKILL.md states 'htmlsnapshot captures the current live DOM at capture time'), so extraction/export/X-SQL on that URL returns today's content, consistent with `snapshot` and `eval`.

#### Actual Behavior

For a URL already seen by the backend (record fetched 3 days earlier), every htmlsnapshot read path — capture summary, `get all`, `export`, and X-SQL DOM_LOAD_AND_SELECT on that URL — silently served the 3-day-old stored record: the page's footer (3 links, 2 images) was missing, results were empty arrays / empty columns with exit code 0, and the capture header claimed a current capture time ('captured 2026-09-06 02:01:12') while the exported HTML's own metadata is stamped 2026/9/3. eval and the AX snapshot disagreed with all htmlsnapshot paths. Only changing the URL key (cache-buster query param) forced a genuinely fresh fetch.

#### Root Cause Analysis

The backend page cache never expires and does not revalidate: LoadOptionDefaults.kt defines default LoadOptions.EXPIRES as DECADES, and LoadComponent.getFetchStateForExistPage (613–670) serves any record fetched >= 3 days ago with DO_NOT_FETCH — HTTP Cache-Control is never consulted. Captures whose page shell comes from the cache deliberately skip persisting content ('The content is loaded from cache... do not persist it', LoadComponent.persist 688–709 at 695–701), so repeated captures of the same URL neither refresh nor overwrite the stale webdb record. WebDbToolExecutor.exportPage (135–142) then serves that record verbatim with no freshness check. No canonicalizer drops <footer> — the record is simply an older page version. Needs follow-up verification: why HTMLSnapshotToolExecutor.capture's primary live-DOM path (captureLiveDocumentSnapshot 224–239) did not override the stale record in this session (likely fell through to the fallback session.capture branch at 187–204), since the code comment for the X-SQL live-seed path shows a '-refresh' capture variant that DOES overwrite the cache entry.

#### Code Pointer

`browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/workflow/component/LoadComponent.kt:getFetchStateForExistPage (613–670) and persist (688–709); browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/options/LoadOptionDefaults.kt:16; browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/HTMLSnapshotToolExecutor.kt:capture (165–206); browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/WebDbToolExecutor.kt:exportPage (135–142)`

#### AI Suggested Improvement

- When the capture target URL equals the live session page, always capture and persist the live DOM from the bound CDP driver (mirror the '-refresh' capture already used by seedLivePageForQuery in HTMLSnapshotToolExecutor.kt:492–516) instead of letting the cache-shell path skip persistence.
- Expose a refresh control on the htmlsnapshot CLI surface (e.g. --refresh / --no-cache / --fresh on capture, export, and query) that maps to LoadOptions refresh semantics.
- Make staleness visible: derive the 'captured' label from the stored record's own fetch time (the embedded PulsarMetaInformation date-time) and print a cache-hit notice such as 'served from cache (originally fetched 2026-09-03 04:21:09)' when content is not freshly fetched.
- Reconsider the decade-long default LoadOptions.EXPIRES, or treat pages served without Cache-Control headers as short-expiry.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Documentation never mentions that htmlsnapshot reads a server-side cached page for previously-seen URLs, and the empty-result recovery hint cannot actually fix it

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/SKILL.md sections 4a/5 (htmlsnapshot = 'captures the current live DOM at capture time', 'Re-capture... to reflect JS updates') and run `./b4w.ps1 htmlsnapshot --help` (full help reviewed: no cache/refresh flag exists). Then run the Issue-1 repro: get all text "a" -> [] with hint 'The snapshot may be stale — it reflects the DOM at capture time... re-capture with htmlsnapshot first' — but re-capturing repeatedly still returns [] for the same URL. The only refresh mechanism (-refresh / -expires in references/load-options-guide.md) is documented solely for crawl/load workflows, not for the htmlsnapshot command family, and is not reachable from any htmlsnapshot flag.

#### Expected Behavior

Either the htmlsnapshot family exposes a documented refresh option, or the docs state that extraction of a previously-fetched URL may serve a cached page for a default period, with an explicit 'how to force fresh' recipe. The empty-result hint should point at a remedy that works.

#### Actual Behavior

A first-time user following the documentation gets silently stale extraction results (3 links and 2 images reported as absent) and is told to re-capture, which does not help; the working workaround (cache-buster URL parameter) is undocumented and undiscoverable. The staleness can persist for days (default cache expiry is decades in code).

#### Root Cause Analysis

Documentation was written for the intended live-DOM capture semantics (SKILL.md lines ~286–300 and ~501) without documenting the webdb/PageCatch caching layer that capture/export/query share, and without exposing the backend LoadOptions refresh knobs on the htmlsnapshot command surface, so no documented instruction can produce fresh content for a cached URL.

#### Code Pointer

`skills/browser4-cli/SKILL.md (htmlsnapshot warnings ~lines 286–300 and 501; CLI arg definitions in cli/browser4-cli/src/commands.rs for the htmlsnapshot family have no refresh option)`

#### AI Suggested Improvement

- Add a 'Caching & staleness' subsection to SKILL.md §4a/htmlsnapshot warnings: same-URL captures may serve the previously stored page; list the refresh mechanisms (cache-buster URL or a future --refresh flag).
- Change the 'No elements matched' recovery hint in the CLI so it distinguishes 'no such selector' from 'content served from an old cache entry' and suggests a concrete fresh-capture path.
- Add a dedicated eval-like reference (or section) for htmlsnapshot capture semantics that includes observed cache behavior and how to verify freshness (compare the exported PulsarMetaInformation date-time).

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: eval has no reference file and an empty link in SKILL.md's Command Map; full semantics live only in `eval --help`

**Severity:** Low
**Category:** Discoverability

#### Reproduction

1) grep eval in skills/browser4-cli/SKILL.md — Command Map row 'eval ... Live DOM access, complex transforms | —' with an empty reference column; Section 7 Reference Map contains no eval entry.
2) ls skills/browser4-cli/references/ — no eval.md.
3) The only complete usage documentation (--file/--stdin/--base64/--js alias, --await, --wait-selector, console.log caveat, arrow-function requirement for --ref, examples) is produced by `./b4w.ps1 eval --help`.

#### Expected Behavior

eval should be as discoverable as the extraction commands: a references/eval.md file linked from the Command Map and the Reference Map, matching the doc depth of htmlsnapshot.md/snapshot.md.

#### Actual Behavior

A new user who has read SKILL.md (the primary documented onboarding path) knows eval exists and that --ref needs an arrow function, but has no way to learn the remaining flags, aliases, caveats, and patterns without discovering `eval --help`. Several behaviors observed in this evaluation (console.log not captured; --js alias; --json envelope shape) are absent from SKILL.md.

#### Root Cause Analysis

Documentation gap: the skill package (SKILL.md + references/) has reference files for snapshot/htmlsnapshot/x-sql/crawl/swarm/etc. but none for eval, even though SKILL.md's decision trees steer users to eval repeatedly ('use eval --json', 'eval --file/--stdin on Windows').

#### Code Pointer

`skills/browser4-cli/SKILL.md lines 241–243 (Command Map rows with '—' reference) and Section 7 Reference Map (lines ~727–765); new file would be skills/browser4-cli/references/eval.md`

#### AI Suggested Improvement

- Author references/eval.md from the eval --help content (flags, --ref arrow-function rule, --json/--stdin/--file/--base64 examples, console.log caveat) and link it from both the Command Map row and Section 7.
- Add a 'Run JavaScript' pattern block in SKILL.md §6 mirroring the Interactive Form Fill block (one-liner, --file, --stdin, --ref examples).

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: eval silently discards console.log output, so scripts that 'log' values print a bare null

**Severity:** Low
**Category:** UX

#### Reproduction

Write a snippet whose only side effect is logging, e.g. 'console.log(document.querySelectorAll("a").length);' and run it:
1) ./b4w.ps1 eval 'console.log(document.querySelectorAll("a").length)'
2) Output is the single token 'null' (the return value of console.log is undefined), with no log lines and no hint that logging was discarded. (Same for eval --file with only console.log statements — observed while running .test-sessions/page_info.js: the three console.log lines produced no output; only the explicitly returned object was printed.)

#### Expected Behavior

console.log output should be visible (or clearly summarized) so a script written in the natural 'compute and log' style is usable; at minimum the CLI should hint that only the return value is shown.

#### Actual Behavior

console.log output is silently dropped and the bare result is the value of the last statement ('null'). This is documented only in `eval --help` notes, not in SKILL.md — and the common mental model (and this task's own phrasing, 'computes and logs') assumes logging works, so first-time users get confusing empty results.

#### Root Cause Analysis

Design decision at the tool boundary: the executor serializes only the expression's return value (Runtime.evaluate return value), and console output is not captured/forwarded (documented in eval --help: 'console.log() output is NOT captured — only the expression's return value is shown. Use return instead.').

#### Code Pointer

`cli/browser4-cli/src/commands.rs eval argument/help definitions (~lines 1638–1641) and the eval result rendering path; backend evaluation in browser4-agentic (AgentToolManager eval tool) — the fix would surface console entries from the CDP Runtime.evaluate result`

#### AI Suggested Improvement

- Capture and print console entries (Runtime.consoleAPICalled / exceptionDetails) alongside the return value, or print them on stderr so return-value output stays machine-parseable.
- When the expression contains console.log calls and the returned value is null/undefined, emit a stderr tip: 'console.log output is not captured — return the value from your expression to see it.'
- Mention the caveat in SKILL.md next to the eval rows so it is visible without running eval --help.

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: snapshot full-page output is headed '# Viewport State ... You are currently viewing viewport 0' although it contains the entire page

**Severity:** Low
**Category:** UX

#### Reproduction

1) ./b4w.ps1 snapshot --stdout   (page taller than one viewport, e.g. interactive-1.html with total height 1497px)
2) The output opens with '# Viewport State / processingViewport: 0 / viewportHeight: 1080px / viewportsTotal: 2 / hiddenBottomHeight: 417px' and closes with 'You are currently viewing viewport 0 (absolute)', yet the tree includes elements far below y=1080 (footer content at y=1330+ with its links).
3) Compare ./b4w.ps1 snapshot -v 0 --stdout, which genuinely excludes that footer content (0 footerLinkTop matches) — proving the no-flag output is a full-page tree wearing a viewport-0 header.

#### Expected Behavior

A full-page snapshot should be labeled as the full page (or the header should be omitted), so users know they received everything; viewport headers should only appear when -v viewport paging actually truncates the output.

#### Actual Behavior

The header text contradicts the content, so a first-time user cannot tell whether the dump was truncated to one screen (and whether they must page with -v 1, -v all) or complete. Elements at y>1080 appearing under a 'currently viewing viewport 0' banner are confusing; the tail comment does explain -v, but only after the fact.

#### Root Cause Analysis

The snapshot writer emits the same '# Viewport State / processingViewport' header block regardless of whether the tree was filtered to a viewport chunk; the header describes the default processing state (viewport 0) rather than the output scope actually rendered.

#### Code Pointer

`cli/browser4-cli/src/snapshot_diff.rs:562 (header block '# Viewport State / processingViewport: 0') where the snapshot file/stdout header is assembled`

#### AI Suggested Improvement

- Emit the Viewport State header only when -v viewport filtering was requested, and label unfiltered output 'full page (N viewports)' or omit the block entirely.
- If the header is retained for unfiltered dumps, add an explicit line such as 'output: full tree (not viewport-filtered)' so scope is unambiguous.

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

**Completion Status:** Successful — all 8 evaluation task steps completed. Each of the five eval invocation styles (inline, --json, --file, --stdin, --ref) returned correct results, and outputs were cross-verified as mutually consistent and consistent with the accessibility snapshot and with fresh (cache-busted) htmlsnapshot/X-SQL extraction. The evaluation surfaced one serious cross-path reliability defect (server-side page cache serving days-old content to the htmlsnapshot family) plus documentation/discoverability gaps; the defect was root-caused with code-level confirmation and a working workaround.

**Success Rate:** 95% — every task step and eval method succeeded first-try; the only task friction was the cross-path verification step, which required diagnosing the stale-cache defect and working around it with a cache-buster URL to complete the consistency check.

**Issues Found:** 5

**Major Blockers:** No blockers for the eval task itself. One High-severity defect blocked naive cross-path verification: htmlsnapshot capture/export/get/X-SQL served a 3-day-old cached copy of the page (missing the footer with its links and images) for the previously-seen URL, reporting empty results with exit code 0 and a 're-capture' hint that cannot refresh it. Workaround: append a unique query parameter (?fresh=1 / ?cb=<ts>) to force a new cache key.

**Most Confusing Aspects:** For a first-time user: (1) eval and htmlsnapshot disagreeing about basic page facts (3 links exist per eval/snapshot, but 'get all text "a"' returns [] with 'No elements matched') with no mention of caching anywhere in the htmlsnapshot docs; (2) the 'captured <current time>' label on htmlsnapshot output while the exported HTML is internally stamped three days earlier; (3) snapshot full-page output being headed as 'currently viewing viewport 0'; (4) a script that console.logs its results printing only 'null'.

**Most Valuable Improvements:** 1) Make htmlsnapshot captures of the current live page bypass the stale webdb cache (live-DOM capture with persistence, as the X-SQL live-seed path already does) and surface cache hits/staleness in output. 2) Expose a --refresh/--fresh option on the htmlsnapshot family and document cache behavior in SKILL.md. 3) Add references/eval.md so eval's rich flag set and caveats (console.log, arrow-function --ref) are discoverable from the skill docs. 4) Capture console output (or hint) in eval. 5) Fix snapshot header labeling so full-page dumps are not presented as viewport 0.

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

#### Issue 1: htmlsnapshot capture/export/query serve a days-old cached page for a same-URL page and label it as captured now

1) ./b4w.ps1 goto "http://localhost:18080/generated/interactive-1.html"
2) ./b4w.ps1 htmlsnapshot   # header prints 'captured 2026-09-06 02:01:12', summary '6 interactive elements' with 0 links
3) ./b4w.ps1 htmlsnapshot get all text "a" --stdout   # prints [] 'No elements matched "a"', exit code 0
4) ./b4w.ps1 htmlsnapshot export .test-sessions/x.html   # exported HTML has no footerLinkTop/pageFooter and embeds PulsarMetaInformation date-time="2026/9/3 04:21:09" (3 days stale)
5) ./b4w.ps1 eval 'document.querySelectorAll("a").length'   # 3 — live DOM does contain the footer links; snapshot -i also shows them
6) Repeat step 2 on the same URL plus a query param (?fresh=1): the same pipeline now reports '2 images · 3 links · 10 interactive elements' and get all text "a" returns the 3 links.

#### Issue 2: Documentation never mentions that htmlsnapshot reads a server-side cached page for previously-seen URLs, and the empty-result recovery hint cannot actually fix it

Read skills/browser4-cli/SKILL.md sections 4a/5 (htmlsnapshot = 'captures the current live DOM at capture time', 'Re-capture... to reflect JS updates') and run `./b4w.ps1 htmlsnapshot --help` (full help reviewed: no cache/refresh flag exists). Then run the Issue-1 repro: get all text "a" -> [] with hint 'The snapshot may be stale — it reflects the DOM at capture time... re-capture with htmlsnapshot first' — but re-capturing repeatedly still returns [] for the same URL. The only refresh mechanism (-refresh / -expires in references/load-options-guide.md) is documented solely for crawl/load workflows, not for the htmlsnapshot command family, and is not reachable from any htmlsnapshot flag.

#### Issue 3: eval has no reference file and an empty link in SKILL.md's Command Map; full semantics live only in `eval --help`

1) grep eval in skills/browser4-cli/SKILL.md — Command Map row 'eval ... Live DOM access, complex transforms | —' with an empty reference column; Section 7 Reference Map contains no eval entry.
2) ls skills/browser4-cli/references/ — no eval.md.
3) The only complete usage documentation (--file/--stdin/--base64/--js alias, --await, --wait-selector, console.log caveat, arrow-function requirement for --ref, examples) is produced by `./b4w.ps1 eval --help`.

#### Issue 4: eval silently discards console.log output, so scripts that 'log' values print a bare null

Write a snippet whose only side effect is logging, e.g. 'console.log(document.querySelectorAll("a").length);' and run it:
1) ./b4w.ps1 eval 'console.log(document.querySelectorAll("a").length)'
2) Output is the single token 'null' (the return value of console.log is undefined), with no log lines and no hint that logging was discarded. (Same for eval --file with only console.log statements — observed while running .test-sessions/page_info.js: the three console.log lines produced no output; only the explicitly returned object was printed.)

#### Issue 5: snapshot full-page output is headed '# Viewport State ... You are currently viewing viewport 0' although it contains the entire page

1) ./b4w.ps1 snapshot --stdout   (page taller than one viewport, e.g. interactive-1.html with total height 1497px)
2) The output opens with '# Viewport State / processingViewport: 0 / viewportHeight: 1080px / viewportsTotal: 2 / hiddenBottomHeight: 417px' and closes with 'You are currently viewing viewport 0 (absolute)', yet the tree includes elements far below y=1080 (footer content at y=1330+ with its links).
3) Compare ./b4w.ps1 snapshot -v 0 --stdout, which genuinely excludes that footer content (0 footerLinkTop matches) — proving the no-flag output is a full-page tree wearing a viewport-0 header.



---

## Processing Log (2026-09-07)

Handled per Human Review decisions. CLI changes verified with full `cargo test --bin browser4-cli`; docs under `skills/browser4-cli/`.

| Issue | Decision | Resolution |
|---|---|---|
| 1 — htmlsnapshot capture/export/query serve days-old cached page (High) | ACCEPT | Partially fixed in HEAD before this batch: `htmlsnapshot` capture/export/query now read the LIVE document of the session tab first (commit 4ef3c78c77 + follow-ups) instead of the webdb record — the stale-record repro no longer applies to the current-page path. Remaining: a refresh control for URL-scoped independent loads and staleness labeling (cache-hit notice) are still open; `--refresh`/`-expires` LoadOptions already documented for crawl/load workflows. |
| 2 — docs never mention the server-side cache; recovery hint can't fix it (Medium) | ACCEPT | Fixed (docs): htmlsnapshot.md now explicitly documents that capture is cached and invalidated by the next capture/navigation, that `query` does NOT use that cache (live DOM for the current page), and cross-references the refresh mechanisms. |
| 3 — eval has no reference file (Low) | ACCEPT | Fixed: new `skills/browser4-cli/references/eval.md` (invocation forms, `--ref` arrow-function rule, `--json` envelope, console.log caveat, patterns), linked from SKILL.md Command Map and Reference Map. |
| 4 — eval silently discards console.log (Low) | ACCEPT | Fixed (CLI + docs): the CLI now prints a stderr reminder when the expression contains `console.log` and the value would otherwise read as `null`; eval.md and help text already document the caveat. Full console capture at the tool boundary remains backend-side. |
| 5 — full-page snapshot headed "viewport 0" (Low) | ACCEPT | Fixed: the CLI's snapshot header now labels unfiltered output as the full page tree ("no viewport filter") instead of "current viewport", so the scope stated matches the content. |