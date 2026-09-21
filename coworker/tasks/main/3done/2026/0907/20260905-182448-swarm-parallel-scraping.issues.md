# Issues: swarm-parallel-scraping

> **Source:** `20260905-182448-swarm-parallel-scraping.full.md` | **Date:** 20260905-182448 | **Mode:** dev

## Scenario Background

### Task

All 9 steps of the swarm workflow completed successfully with correct data, and the usability evaluation is complete.

1. ✅ `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` → session `SWARM` created (auto-cleaned 3 completed stale tasks from a prior session)
2. ✅ Seed file with 10 MockSite product URLs created (6 Electronics `B0E000001–B0E000006` + 4 Home `B0H000001–B0H000004`)
3. ✅ X-SQL query file written (title/price/image scoped to `#product-page`)
4. ✅ `swarm query --sql @file --seed-file ... --refresh` → 10 jobs, each with a UUID
5. ✅ `swarm submit <url> --refresh` → 1 plain scrape job
6. ✅ Polled status until completion (`isDone: false → queued/processing → completed`)
7. ✅ **Results: 10/10 query jobs returned full rows** (`url`, `title`, `price`, `image_url`) — values verified against the live MockSite HTML (e.g. `4K OLED TV 55`, `$899.99`, `https://picsum.photos/seed/1250857624/200/140`). The plain scrape returned `resultSet: [{"url": ...}]` with `pageContentBytes: 15294`.
8. ✅ `swarm list` → 11 total, 11 completed, correct STARTED/FINISHED columns
9. ✅ `swarm close` → session closed, browser terminated, results retained

Notably, the Reliability issues reported in the 2026-07-09 evaluation (broken `isDone`, `--wait` timeouts, empty resultSets, `swarm list` stuck on "pending", `DOM_FIRST_IMG`) are all **fixed** in this build — statuses transition correctly and extraction was 100% reliable here.

### Execution Context

| Step | Command | Outcome |
|---|---|---|
| Prep | `./b4w.ps1 help`; read `SKILL.md` + `references/swarm.md` | Docs clear; swarm workflow documented end-to-end |
| Prep | `curl localhost:18080/ec/dp/...`; inspected MockSite HTML | Selectors `#productTitle`, `#product-price`, `#product-image` confirmed; Home product IDs return 200 |
| 1 | `./b4w.ps1 "swarm" "create" "--display-mode" "HEADLESS" "--max-browser-contexts" "2" "--max-open-tabs" "4"` | `Swarm session created: SWARM` (auto-cleaned 3 stale tasks) |
| 2–3 | Wrote `.test-sessions/mocksite-products-seed.txt` (10 URLs) + `.test-sessions/product-extract.sql` | `SELECT DOM_BASE_URI…, DOM_FIRST_TEXT(#productTitle)…, DOM_FIRST_ATTR(#product-image,'src') FROM DOM_LOAD_AND_SELECT(@url, '#product-page')` |
| 4 | `swarm query --sql @…product-...

(truncated — see full.md for complete trace)

---

## Issues Found (5 issues)

### Issue 1: Fresh swarm session has a silent ~60s 'queued' startup phase; docs' >30s stall heuristic would make users kill a healthy session

**Severity:** Medium
**Category:** UX

#### Reproduction

1) ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
2) ./b4w.ps1 swarm query --sql @q.sql --seed-file seed.txt --refresh
3) Poll swarm status/list immediately; observe: tasks submitted at 02:19:51 stayed queued until 02:20:51 (~60s) with no progress signal; first 4 jobs then took 41-72s each, remaining 7 finished in <1s. swarm.md 'Errors & Recovery' says: 'If all tasks show queued for >30s, the worker pool may be stalled — try swarm list --clear, then swarm close and swarm create to restart'.

#### Expected Behavior

After swarm create returns, the CLI should either confirm browser contexts are ready, or submissions should show a 'workers starting up' progress state so a first-time user does not mistake warm-up for a stall. The docs' stall threshold should not fire during normal cold start.

#### Actual Behavior

swarm create printed only 'Swarm session created: SWARM'; the 2 HEADLESS contexts took ~60s to come online in the background. All jobs read 'queued' (201) with no cue about context boot, and the documented >30s stall-recovery procedure (clear + close + recreate) points squarely at this healthy period.

#### Root Cause Analysis

Browser contexts/worker pool initialize lazily after the create call returns (session creation and context spawn are decoupled; first job pickup waits on Chrome cold start). CLI side has no readiness handshake and prints no guidance; swarm.md's 30s heuristic was calibrated for warm sessions. Exact lazy-spawn point needs confirmation in the backend (likely ensureSwarmSession/SwarmService worker startup), but observed timestamps (created 02:19:51, first STARTED 02:20:51, first-wave jobs 41-72s vs later <1s) point to context boot charged to the first jobs.

#### Code Pointer

`cli/browser4-cli/src/main.rs:10190 (handle_swarm_create); docs: skills/browser4-cli/references/swarm.md 'Errors & Recovery' table; backend: browser4-rest/.../api/service/SwarmService.kt`

#### AI Suggested Improvement

- After swarm create, poll backend session readiness and print e.g. 'Browser contexts starting (2 contexts, ~60s) — jobs will be queued until ready' before returning, or make create block until contexts are up
- Show context-boot progress in swarm status/list (e.g. lifecycleState 'starting' for the pool, not per-task 'queued')
- Reword the swarm.md recovery row: distinguish 'queued >30s on a session created moments ago (normal warm-up)' from 'queued >30s with no workers after warm-up', and raise the threshold / add 'check once after 60-90s first'
- Optionally warm up contexts eagerly at swarm create time so first submissions start instantly

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The documented >30s recovery procedure (`swarm.md:194`) tells users to destroy a healthy session during a normal ~60s cold start — a genuine, first-user-visible hazard. The backend lazy-spawn point is admittedly unconfirmed, but the doc reword (distinguish cold-start queueing from a true stall) and a "contexts starting" cue in create/status output are safe and implementable regardless.

---

### Issue 2: swarm create defaults to GUI display mode, contradicting the CLI's headless-first convention for agents

**Severity:** Medium
**Category:** Documentation

#### Reproduction

1) Run: ./b4w.ps1 swarm create (no --display-mode flag)
2) Observe: swarm.md option table documents --display-mode default 'GUI'; SKILL.md §Display Mode and the bundled tip (tips.rs:231 'Use --display-mode HEADLESS for swarm operations to reduce resource usage') both state headless is the default convention for AI agents. The mode is fixed for the session lifetime — it cannot be changed without close + recreate.

#### Expected Behavior

Consistent defaults: either swarm create defaults to HEADLESS (matching open/goto and the CLI's own documented convention), or the documentation clearly explains why swarm alone defaults to visible GUI windows.

#### Actual Behavior

The swarm session opens visible browser windows by default. This evaluation's task explicitly required --display-mode HEADLESS 'to run without a visible browser window' — precisely because the bare command does not do that. A first-time agent user following 'headless is the default' guidance elsewhere would unknowingly pop GUI Chrome windows.

#### Root Cause Analysis

The CLI only forwards displayMode to the backend when the flag is provided (build_swarm_create_capabilities inserts nothing by default), and the backend session capabilities default to GUI for swarm sessions — unlike the headless default used by the open/goto session path. The default lives on the backend side of the capabilities contract; CLI-side code never normalizes it.

#### Code Pointer

`cli/browser4-cli/src/main.rs:884 (build_swarm_create_capabilities — could default displayMode to HEADLESS); backend default in swarm session capabilities (browser4-rest/.../api/controller/SwarmController.kt open() / session manager); doc: skills/browser4-cli/references/swarm.md create-options table`

#### AI Suggested Improvement

- Default --display-mode to HEADLESS in build_swarm_create_capabilities when absent, mirroring the open/goto convention, and update the swarm.md default column
- If GUI stays the backend default for legacy reasons, print the effective mode in swarm create output ('Session SWARM created (HEADLESS, 2 contexts, 4 tabs max)') so users see what they got
- Add a create-time warning when a GUI session is created by a non-interactive/agent context

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Real inconsistency: the CLI normalizes `profileMode` to SEQUENTIAL but leaves `displayMode` to the backend's GUI default (`main.rs:922-929`), contradicting the headless-first convention stated in swarm.md's own Quick Start and SKILL.md. Fix by defaulting HEADLESS CLI-side and updating the swarm.md:62 default column, or if GUI stays for legacy reasons, at minimum print the effective mode (shared fix surface with Issue 5).

---

### Issue 3: swarm submit/query --help show broken doubled-quote inline X-SQL examples that fail on copy-paste

**Severity:** Low
**Category:** Documentation

#### Reproduction

Run: ./b4w.ps1 swarm submit --help (and swarm query --help); observe the inline-SQL examples:
  browser4-cli swarm submit "https://www.amazon.com/dp/B08PP5MSVB" --sql ""SELECT DOM_BASE_URI(DOM) AS url, DOM_FIRST_TEXT(DOM, '#productTitle') AS title ""FROM DOM_LOAD_AND_SELECT(@url, 'body')""
Copy the example verbatim into bash or PowerShell and execute.

#### Expected Behavior

Help examples must be valid, copy-pasteable commands (e.g. --sql "SELECT ... FROM DOM_LOAD_AND_SELECT(@url, 'body')" as a single quoted argument, or simply --sql @query.sql as the next example already shows).

#### Actual Behavior

The doubled quote marks (""..."") split the SQL argument in both shells; even when the shell accepts it, the query text becomes syntactically invalid X-SQL. The example also violates the SKILL.md §5 rule against inline double-quoted CSS selectors on Windows.

#### Root Cause Analysis

help.rs builds these example lines by concatenating raw-string fragments that each begin/end with a literal double quote intended to delimit the --sql value, producing adjacent doubled quotes at the fragment boundaries ('--sql "" + ""SELECT … title "" + ""FROM …'"" + ""').

#### Code Pointer

`cli/browser4-cli/src/help.rs:1395-1399 (swarm submit example) and help.rs:1446-1450 (swarm query example)`

#### AI Suggested Improvement

- Rebuild both examples as single-line strings with exactly one pair of delimiting quotes around the SQL
- Prefer the file form (--sql @query.sql) in the examples, since SKILL.md itself warns against inline SQL on Windows
- Add a unit test asserting every 'browser4-cli …' example line in generated help contains balanced, non-doubled quotes

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified the raw-string concatenation in `help.rs:1394-1397` genuinely emits doubled quotes that break copy-paste in both shells and produce invalid X-SQL. Trivial to fix by rebuilding both examples as single strings with one delimiting quote pair, plus the suggested unit test asserting balanced quotes in generated help.

---

### Issue 4: Docs and help claim swarm submit without --sql returns an empty resultSet; it actually returns one {url} row

**Severity:** Low
**Category:** Documentation

#### Reproduction

1) ./b4w.ps1 swarm submit http://localhost:18080/ec/dp/B0E000001 --refresh
2) ./b4w.ps1 swarm result <task-id>
3) Compare with swarm.md: 'Without --sql, swarm submit only fetches and loads the page — no data is extracted. The resultSet will be empty.' and the CLI help: 'the resultSet will be empty.'

#### Expected Behavior

Documentation matches observed behavior: either an empty resultSet, or a description that states what submit actually returns.

#### Actual Behavior

resultSet contains one row: {"url": "http://localhost:18080/ec/dp/B0E000001"} with pageContentBytes 15294. The behavior is reasonable (a URL-only row is a useful success signal) but contradicts both the reference doc and the CLI help text, which would confuse scripts written to detect 'no extraction' via an empty array.

#### Root Cause Analysis

SwarmController.submit() wraps every plain-URL payload into an X-SQL query: 'select dom_base_uri(dom) as url from load_and_select('<url>', ':root')', which always yields exactly one row with the url column. The doc/help text predates or ignores this wrapping.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/SwarmController.kt (submit(), URL→X-SQL wrapping); doc text in skills/browser4-cli/references/swarm.md §2 and CLI help string in cli/browser4-cli/src/help.rs`

#### AI Suggested Improvement

- Update swarm.md §2 and the swarm submit help text: 'Without --sql, each URL is fetched and the resultSet contains a single row with only the page URL' (or align backend to truly return an empty resultSet if empty is the contract)
- Add a small note that pageContentBytes confirms the fetch
- Consider documenting the wrapping in a code comment near the payload-to-SQL conversion

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Verified `commands.rs:2839` and `swarm.md:83` claim an empty resultSet while `SwarmController.kt:75-76` always returns one `{url}` row — and since main.rs already treats empty-resultSet-with-5xx as a failure signal, scripts may misread behavior. Keep the backend behavior, update doc + help text to describe the single URL row, and add a comment at the wrapping site. (Note: the issue's pointer to help.rs is off — the text is in commands.rs.)

---

### Issue 5: swarm create prints no confirmation of the applied session options

**Severity:** Low
**Category:** UX

#### Reproduction

1) ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
2) Observe output: 'Swarm session created: SWARM' (nothing else).
3) Try to verify the options afterwards: swarm list shows tasks only; no swarm command reports the session's display mode, context count, or tab limit.

#### Expected Behavior

The create output (or a follow-up swarm status/list field) should echo the effective configuration — display mode, max contexts, max tabs — so users can confirm their tuning flags were honored and diagnose surprises (e.g. an accidental GUI session).

#### Actual Behavior

No command in the swarm workflow ever surfaces the session configuration. A user passing flags gets zero feedback about whether they applied; combined with the GUI default (Issue above) and the silent ~60s context warm-up, a first-time user cannot tell what session they actually got until windows appear or jobs stall.

#### Root Cause Analysis

handle_swarm_create prints only the session id from the backend SessionResponse; the CLI discards capabilities it sent and the response's session metadata is not rendered. No later swarm subcommand queries session configuration.

#### Code Pointer

`cli/browser4-cli/src/main.rs:10190 (handle_swarm_create — extend success output with effective capabilities); optionally surface session config in swarm list`

#### AI Suggested Improvement

- Print 'Swarm session created: SWARM (display=HEADLESS, contexts=2, max-tabs=4)' when flags were supplied (or always, showing defaults)
- Include session capabilities in swarm list's summary line so configuration remains inspectable after creation
- Accept --json on swarm create so scripts can capture the echoed configuration

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] With no command ever surfacing session configuration, users cannot verify their tuning flags were honored — compounding Issues 1 and 2 (no readiness cue, invisible GUI default). This is not a duplicate of Issue 2 but shares its fix surface: extend `handle_swarm_create` output to echo display mode/contexts/tabs, which resolves both, and optionally include capabilities in `swarm list`.

---

## Overall Assessment

**Completion Status:** Successful — all 9 task steps completed; both submission methods produced correct, verifiable results (10/10 X-SQL rows with title/price/image_url matching live MockSite HTML; plain scrape returned page bytes + URL row). Prior-evaluation reliability defects (isDone, empty resultSets, stale 'pending' list) were confirmed fixed in this 4.13.14-SNAPSHOT build.

**Success Rate:** 100% — every step succeeded on the first attempt with no workarounds required

**Issues Found:** 5

**Most Confusing Aspects:** 1) After submission, all jobs show 'queued' for ~60s on a fresh session with no progress signal, while the reference doc's recovery table says a >30s queued state means the worker pool is stalled — a first-time user following the docs would tear down a healthy session. 2) swarm create defaults to GUI windows despite the CLI's headless-first convention, so the bare command contradicts the SKILL.md guidance. 3) swarm create gives no echo of the options it applied, so there is no way to verify --max-browser-contexts/--max-open-tabs/--display-mode took effect.

**Most Valuable Improvements:** 1) Surface worker-pool warm-up state (or block create until contexts are ready) and fix the swarm.md >30s stall heuristic so it does not fire during normal cold start. 2) Default swarm create display mode to HEADLESS (or echo effective options + warn on GUI). 3) Fix the broken doubled-quote SQL examples in swarm submit/query help. 4) Align the 'empty resultSet' doc claim with the actual URL-row behavior of plain swarm submit.

**Usability Rating:** 8/10

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

#### Issue 1: Fresh swarm session has a silent ~60s 'queued' startup phase; docs' >30s stall heuristic would make users kill a healthy session

1) ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
2) ./b4w.ps1 swarm query --sql @q.sql --seed-file seed.txt --refresh
3) Poll swarm status/list immediately; observe: tasks submitted at 02:19:51 stayed queued until 02:20:51 (~60s) with no progress signal; first 4 jobs then took 41-72s each, remaining 7 finished in <1s. swarm.md 'Errors & Recovery' says: 'If all tasks show queued for >30s, the worker pool may be stalled — try swarm list --clear, then swarm close and swarm create to restart'.

#### Issue 2: swarm create defaults to GUI display mode, contradicting the CLI's headless-first convention for agents

1) Run: ./b4w.ps1 swarm create (no --display-mode flag)
2) Observe: swarm.md option table documents --display-mode default 'GUI'; SKILL.md §Display Mode and the bundled tip (tips.rs:231 'Use --display-mode HEADLESS for swarm operations to reduce resource usage') both state headless is the default convention for AI agents. The mode is fixed for the session lifetime — it cannot be changed without close + recreate.

#### Issue 3: swarm submit/query --help show broken doubled-quote inline X-SQL examples that fail on copy-paste

Run: ./b4w.ps1 swarm submit --help (and swarm query --help); observe the inline-SQL examples:
  browser4-cli swarm submit "https://www.amazon.com/dp/B08PP5MSVB" --sql ""SELECT DOM_BASE_URI(DOM) AS url, DOM_FIRST_TEXT(DOM, '#productTitle') AS title ""FROM DOM_LOAD_AND_SELECT(@url, 'body')""
Copy the example verbatim into bash or PowerShell and execute.

#### Issue 4: Docs and help claim swarm submit without --sql returns an empty resultSet; it actually returns one {url} row

1) ./b4w.ps1 swarm submit http://localhost:18080/ec/dp/B0E000001 --refresh
2) ./b4w.ps1 swarm result <task-id>
3) Compare with swarm.md: 'Without --sql, swarm submit only fetches and loads the page — no data is extracted. The resultSet will be empty.' and the CLI help: 'the resultSet will be empty.'

#### Issue 5: swarm create prints no confirmation of the applied session options

1) ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
2) Observe output: 'Swarm session created: SWARM' (nothing else).
3) Try to verify the options afterwards: swarm list shows tasks only; no swarm command reports the session's display mode, context count, or tab limit.



---

## Processing Log (2026-09-07)

Handled per Human Review decisions. CLI changes verified with full `cargo test --bin browser4-cli`; docs under `skills/browser4-cli/references/swarm.md`.

| Issue | Decision | Resolution |
|---|---|---|
| 1 — fresh session ~60s queued startup phase vs >30s stall heuristic (Medium) | ACCEPT | Fixed: `swarm create` now prints a cold-start note ("browser contexts starting ~30-60s; jobs stay queued until ready") and swarm.md's Errors & Recovery distinguishes cold-start queueing from a true stall (check after 60-90s first). |
| 2 — `swarm create` defaults to GUI (Medium) | ACCEPT | Fixed: `build_swarm_create_capabilities` now defaults `displayMode` to HEADLESS (only an explicit `--display-mode GUI` opens visible windows), matching the CLI's headless-first convention; swarm.md default column updated; unit test added. |
| 3 — broken doubled-quote inline SQL help examples (Low) | ACCEPT | Fixed: swarm submit/query help examples rebuilt as single-quoted-argument commands (`--sql "SELECT …"`), copy-paste safe in both shells. |
| 4 — docs/help claim empty resultSet without --sql (Low) | ACCEPT | Fixed: `swarm-submit` description and swarm.md now state the resultSet holds a single `url` row per submitted URL (fetch-confirmation marker), matching `SwarmController.submit` wrapping. |
| 5 — create prints no confirmation of applied options (Low) | ACCEPT | Fixed: `swarm create` output echoes the effective configuration: `Swarm session created: SWARM (display=HEADLESS, contexts=2, max-tabs=4)` (+ cold-start note from issue 1). |