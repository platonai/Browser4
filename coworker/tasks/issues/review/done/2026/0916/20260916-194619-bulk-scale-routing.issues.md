# Issues: bulk-scale-routing

> **Source:** `20260916-194619-bulk-scale-routing.full.md` | **Date:** 20260916-194619 | **Mode:** dev

## Scenario Background

### Task

Every branch of **SKILL.md §4b — Choosing Bulk/Scale Approach** was exercised end-to-end against MockSite on `localhost:18080`, and **all six acceptance criteria passed**.

| AC | Approach | Command | Result |
|---|---|---|---|
| AC1 | Single list page | `htmlsnapshot query --sql @listing.sql --format table` | ✅ 6 rows — title + price + rating + url, correlated per product card |
| AC2 | Multiple known URLs | `crawl --seed-file seed-urls.txt --depth 0 --sql @detail.sql --format table --refresh` | ✅ 3/3 seed URLs → one structured row each |
| AC3 | Crawl from start URL | `crawl <hub> -d 2 -ol "a.product" -olp "/product/"` | ✅ 10 pages (seed + 3 at depth 1 + 6 at depth 2); no category/utility links |
| AC4 | Parallel swarm | `swarm create --max-browser-contexts 2 --max-open-tabs 4` → `swarm query --seed-file ... --refresh` → `status` → `result` → `close` | ✅ 3/3 tasks completed, all 3 result payloads correct |
| AC5 | Repeated monitoring | `loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "…"` | ✅ 2 iterations, both `$899.99`; `--list`/`--status` visible mid-run; state auto-cleaned |
| AC6 | Few URLs in a shell loop | `bash few-urls.sh` (open once → goto + htmlsnapshot + `htmlsnapshot get text "#productTitle"` + `sleep 2`) | ✅ 3/3 titles extracted, no crawl/swarm/loop needed |

AC1 was verified in both documented forms — live-DOM (no URL) and explicit-URL independent scrape — with identical results.

For AC3 I additionally ran a **control crawl** (`-ol "a.category-link"`), which returned the 3 category pages. This proves the exclusion came from the selector/pattern filters rather than the category links simply not existing in the browser DOM.

### Execution Context

**Preparation.** Confirmed cwd, read `help` output and all of `skills/browser4-cli/SKILL.md`, plus the `crawl.md`, `swarm.md` and `loop.md` references. MockSite was already running on 18080 (`./bin/test.ps1 mock-site` reported the port held by PID 58160); all three fixture endpoints returned 200.

**Execution.** Discovered selectors rather than guessing: `htmlsnapshot inspect` on the listing page reported 6 repeats of `.product-card` with `★ 6/6 (100%)` coverage for `.product-title` / `.product-price` / `.product-rating`, which became the X-SQL columns. I checked that inspect's suggested `div.product-title` / `img.product-img` looked wrong against the curl HTML (`h2.product-title` / `img.product-image`) — but the live browser DOM confirms inspect is right and MockSite serves a hydrated var...

(truncated — see full.md for complete trace)

---

## Issues Found (4 issues)

### Issue 1: Swarm task DURATION reports ~0 ms for tasks that took seconds

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
./b4w.ps1 swarm query --sql @detail.sql --seed-file seed-urls.txt --refresh
./b4w.ps1 swarm list --batch <batch-id>
./b4w.ps1 swarm list --batch <batch-id> --json

#### Expected Behavior

DURATION should reflect the wall-clock work each task performed. The swarm md reference advertises the column as answering 'how long did each task take?' directly, and swarm status <batch-id> summarises it as 'slowest task'.

#### Actual Behavior

DURATION shows 0ms / 1ms / 2ms for all three tasks, and swarm status prints 'slowest task: e92da9d7 (2ms)'. --json exposes the cause: started_at 19:40:56.766748100Z and completed_at 19:40:56.769092900Z (2 ms apart) for a task whose submitted_at was 19:40:35.668945900Z and whose result carries pageContentBytes: 15296. The batch window simultaneously reports 5.3s, so two numbers in the same report disagree by three orders of magnitude.

#### Root Cause Analysis

ScrapeResponse.startedTime is stamped lazily by the first refresh(...) call rather than at worker pickup. For X-SQL swarm tasks the first refresh happens inside the onLoaded / executeXSQL handlers (XSQLScrapeHyperlink.kt), i.e. after the page has already been fetched and parsed, and finishTime is set moments later. durationMillis therefore measures only the trailing bookkeeping, not the task. There is no SC_PROCESSING refresh when the task is dequeued. The same model is shared by the htmlsnapshot query path: an X-SQL error envelope returned startedTime 19:45:11.244185900Z -> finishTime 19:45:11.246185600Z (1.7 ms) for a query with pageContentBytes: 10148.

#### Code Pointer

`browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/crawl/Models.kt:130 (ScrapeResponse.refresh) — stamp startedTime when the worker dequeues the task, e.g. via the existing onWillLoad hook in XSQLScrapeHyperlink.CrawlEventHandlers`

#### AI Suggested Improvement

- Stamp startedTime at worker pickup instead of on first status refresh: call response.refresh(ResourceStatus.SC_PROCESSING) from the onWillLoad hook in XSQLHyperlink.CrawlEventHandlers (the hook already exists and currently only emits an event).
- Add a regression test asserting durationMillis >= 1000 for a task whose load took seconds, so a future re-ordering cannot silently reintroduce the 0 ms reading.
- Consider reporting the wait separately (queuedMillis = startedTime - createdTime) so queue delay and work time are both visible rather than conflated.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Crawl's documented 'measured peak overlap' report never prints — backend omits parallelTabs / maxConcurrentFetches from the REST response

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 crawl --seed-file seed-urls.txt --depth 0 --sql @detail.sql --format table --refresh --parallel 3
curl -s http://localhost:18182/api/crawl/<task-id>/result | python -c "import sys,json;print(sorted(json.load(sys.stdin).keys()))"

#### Expected Behavior

The crawl completion output includes a line such as 'Parallelism: budget 3 tab(s), peak N unit(s) in flight', as promised by crawl.md (Parallelism section: 'The reported peak is measured, not claimed... A peak of 1 on a multi-unit crawl means the collection was serial — that is called out explicitly'), by help.rs:1680, and by the tip in tips.rs:219.

#### Actual Behavior

Only 'Parallel tabs: 3' is printed at submit (the requested budget). The completion output ends at '3 pages crawled, 3 rows extracted.' with no peak line. The raw REST response contains neither parallelTabs nor maxConcurrentFetches: the top-level keys are exactly [createdAt, finishTime, linksDiscovered, pages, pagesFound, seedStatuses, startedTime, status, taskId, taskTTLMinutes]. Reproduced on all three crawls run (depth 0 with --parallel 3, depth 0 with server default, depth 2 link-discovery).

#### Root Cause Analysis

CrawlService.writeCompleted (CrawlService.kt:584-585) and publishIncremental (lines 532-533) both set parallelTabs = task.parallelTabs and maxConcurrentFetches = task.peakInFlight.get(), and the completion logger even writes 'parallel budget {} (peak {} in flight)'. They never reach the client: they are the last fields declared on CrawlResponse (CrawlModels.kt:46) and are absent from the serialized payload, consistent with a non-ALWAYS Jackson inclusion policy in the shared mapper. HTMLSnapshotToolExecutor.kt:360 and :460 both set JsonInclude.Include.ALWAYS explicitly, suggesting this mapper behaviour is already known and worked around elsewhere. The CLI side is correct but never fires: crawl_parallelism_note() (main.rs:13384) returns None as soon as parsed["parallelTabs"] is <= 0. The exact mapper rule still needs confirmation in the shared pulsarObjectMapper(). The same omission drops pagesExpected, which breaks the documented accounting invariant pagesFound + failedPages.size == pagesExpected for API consumers.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/crawl/CrawlModels.kt:46 (CrawlResponse) and CrawlService.kt:584 (writeCompleted); CLI consumer at cli/browser4-cli/src/main.rs:13384 (crawl_parallelism_note)`

#### AI Suggested Improvement

- Make the accounting fields survive serialization: annotate parallelTabs, maxConcurrentFetches and pagesExpected with @JsonInclude(JsonInclude.Include.ALWAYS) on CrawlResponse, or fix the inclusion policy in the shared pulsarObjectMapper().
- Add a REST-level test that asserts the /api/crawl/{id}/result payload contains parallelTabs, maxConcurrentFetches and pagesExpected — the existing CrawlParallelTabsTest asserts on the Kotlin object, which is why the serialization drop slipped through.
- Until the field is populated, consider having crawl_parallelism_note() fall back to the local --parallel value the CLI already knows, so the user is at least told the budget was not verifiable.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: X-SQL 'not found' error prints a quoting fix for an unknown-function error

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "SELECT NOPE(DOM) AS x FROM DOM_LOAD_AND_SELECT(@url, 'body')" --format table

#### Expected Behavior

The failure block should name the real cause — an unknown/unsupported X-SQL function name — or fall back to the generic guidance.

#### Actual Behavior

Alongside the correctly surfaced server message ('Function "NOPE" not found'), the CLI prints '- Fix: CSS selectors in DOM_* functions must use SINGLE quotes (H2 treats double quotes as SQL identifiers).' The query contains no double-quoted selector, so the advice does not apply and points the user away from the actual problem. The same generic tip block ('Use --sql @file.sql to avoid shell quoting issues...') is appended on the empty-result path too.

#### Root Cause Analysis

main.rs:7977 branches on reason.contains("not found"), which matches three different H2 errors at once: 'Column "h2" not found' and 'Table "..." not found' (genuine quoting mistakes, for which the advice is correct) and 'Function "NOPE" not found' (an unknown function, for which it is wrong). The substring test is too coarse to distinguish them.

#### Code Pointer

`cli/browser4-cli/src/main.rs:7977 (the `reason.contains("not found")` branch in the htmlsnapshot query 417 failure rendering); tip text at cli/browser4-cli/src/help.rs:739`

#### AI Suggested Improvement

- Match the diagnostic more precisely: handle "Function" + "not found" separately from "Column"/"Table" + "not found", and for the function case list valid DOM_* function names or point at the x-sql reference.
- Order the branches most-specific-first so a future message containing both words cannot fall into the quoting branch.
- Suppress the generic shell-quoting tip block when --sql @file was already used, since the advice cannot apply.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: -olp "/product/" fails under ./b4w.ps1 from Git Bash (documented command, documented wrapper)

**Severity:** Low
**Category:** Documentation

#### Reproduction

cd D:/workspace/Browser4/Browser4-4.13 && ./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"

#### Expected Behavior

The crawl runs and filters discovered links by the /product/ pattern, as shown verbatim in the crawl.md example and in this scenario's acceptance criteria. The SKILL.md invocation table presents ./b4w.ps1 as the primary dev wrapper for Git Bash without noting any argument restrictions.

#### Actual Behavior

The command aborts before doing any work: "Error: argument 'C:/Program Files/Git/product/' (probably typed as '/product/') was rewritten by Git Bash's MSYS path conversion before PowerShell started. The original value is unrecoverable... Run this command via ./b4w.sh instead (it disables the conversion). Or export MSYS2_ARG_CONV_EXCL='*' and re-run". The crawl only succeeds after applying that workaround.

#### Root Cause Analysis

MSYS2 rewrites arguments that look like POSIX paths when a native Windows binary is exec'd, so the leading-'/' pattern is rewritten before PowerShell starts. b4w.sh exports MSYS2_ARG_CONV_EXCL='*' to disable this; b4w.ps1 does not, and instead detects the rewrite after the fact and refuses. The refusal is the right call — crawl.md:441 documents that a shell-mangled pattern otherwise filters out every link and reports a misleadingly small crawl — but it means the primary wrapper cannot run its own documentation's example from Git Bash.

#### Code Pointer

`b4w.ps1 (argument preflight) and skills/browser4-cli/references/crawl.md:226 (the Git Bash caveat, currently scoped to b4w.sh only)`

#### AI Suggested Improvement

- Have b4w.ps1 export MSYS2_ARG_CONV_EXCL='*' when MSYSTEM is set, matching b4w.sh, so the two documented entry points behave identically.
- If that is too broad, surface the restriction in the SKILL.md invocation table: mark ./b4w.ps1 as failing on '-olp /...' style values and point at ./b4w.sh for those.
- Alternative: make the preflight auto-retry the command with conversion disabled, so the user never sees the error for a case the wrapper can repair itself.

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

**Completion Status:** Successful — all six acceptance criteria for SKILL.md §4b passed: single list page via htmlsnapshot query + DOM_LOAD_AND_SELECT (6 correlated rows), seed-file crawl --depth 0 (3/3 rows), link-discovery crawl -d 2 (10 pages, category links correctly excluded), swarm parallel extraction (3/3, results fetched, session closed), loop in subcommand mode (2 iterations on a named session), and an ad-hoc shell loop over 3 URLs.

**Success Rate:** 100% of task steps succeeded (6/6 ACs). One step, AC3, required the MSYS2_ARG_CONV_EXCL workaround described in Issue 4; every other command worked on the first attempt.

**Issues Found:** 4

**Major Blockers:** None. The backend and daemon were already running and every command completed. The only step that failed outright was AC3's link-discovery crawl, which was blocked by Git Bash argument mangling and resolved immediately by the workaround the CLI itself printed.

**Most Confusing Aspects:** 1) Two different reports of the same duration disagree by three orders of magnitude: swarm list shows DURATION 0-2ms while the batch window on the same screen shows 5.3s, with nothing to indicate which to trust. 2) A documented feature can be silently absent — the parallelism peak is promised in three separate places (crawl.md, help, tips) and never appears, with no warning that it was skipped, so a first-time user cannot tell 'not supported here' from 'I misread the docs'. 3) Git Bash and the CLI's own documentation disagree about which wrapper to use: SKILL.md presents b4w.ps1 as the primary wrapper, while crawl.md's own example only works under b4w.sh. 4) The setup layer is opaque: the backend auto-start worked well, but the config (C:/Users/pereg/.browser4/config.json), loop state (the same tree) and snapshots (<repo>/.browser4-cli/snapshot/) live in three separate places, so 'where is my state?' has no single answer.

**Most Valuable Improvements:** 1) Fix the task-duration accounting (Issue 1) — it is the number users rely on to judge swarm throughput, and it is currently off by ~1000x. 2) Restore the crawl parallelism fields to the REST payload (Issue 2); the CLI code, the backend fields and the docs all already exist, so this is a serialization fix that switches on a feature that is 90% built. 3) Make b4w.ps1 and b4w.sh argument handling identical (Issue 4) so the documented examples run under either wrapper. 4) Tone down the error-path tips (Issue 3) so a confident but inapplicable fix is not printed next to a correct diagnosis.

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

#### Issue 1: Swarm task DURATION reports ~0 ms for tasks that took seconds

./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
./b4w.ps1 swarm query --sql @detail.sql --seed-file seed-urls.txt --refresh
./b4w.ps1 swarm list --batch <batch-id>
./b4w.ps1 swarm list --batch <batch-id> --json

#### Issue 2: Crawl's documented 'measured peak overlap' report never prints — backend omits parallelTabs / maxConcurrentFetches from the REST response

./b4w.ps1 crawl --seed-file seed-urls.txt --depth 0 --sql @detail.sql --format table --refresh --parallel 3
curl -s http://localhost:18182/api/crawl/<task-id>/result | python -c "import sys,json;print(sorted(json.load(sys.stdin).keys()))"

#### Issue 3: X-SQL 'not found' error prints a quoting fix for an unknown-function error

./b4w.ps1 htmlsnapshot query "http://localhost:18080/ec/b?node=1292115012" --sql "SELECT NOPE(DOM) AS x FROM DOM_LOAD_AND_SELECT(@url, 'body')" --format table

#### Issue 4: -olp "/product/" fails under ./b4w.ps1 from Git Bash (documented command, documented wrapper)

cd D:/workspace/Browser4/Browser4-4.13 && ./b4w.ps1 crawl "http://localhost:18080/generated/crawl/index.html" -d 2 -ol "a.product" -olp "/product/"

