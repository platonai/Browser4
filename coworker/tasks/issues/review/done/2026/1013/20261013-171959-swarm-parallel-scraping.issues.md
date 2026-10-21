# Issues: swarm-parallel-scraping

> **Source:** `20261013-171959-swarm-parallel-scraping.issues.json` | **Date:** 20261013-171959 | **Mode:** dev

## Scenario Background

### Task

All 9 scenario steps completed successfully against the local dev-mode backend and MockSite:

1. **Swarm session created** — `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` → session `SWARM (display=HEADLESS, contexts=2, max-tabs=4)`. The 3 completed tasks left over from a prior session were auto-cleaned.
2. **Seed file** created with the 10 required MockSite product URLs (6 Electronics `B0E000001–6`, 4 Home `B0H000001–4`); all 10 verified reachable (HTTP 200) before starting.
3. **X-SQL query file** written to extract `url`, `title`, `price`, `image` (validated selectors against a fetched copy of a product page: `#productTitle`, `#product-price`, `#product-image`).
4. **10 extraction jobs submitted** via `swarm query --sql @file --seed-file <file> --refresh` → all 10 accepted, batch ID `879773d4-c6bf-47e5-a6eb-11272a77d3b0` returned.
5. **Plain scrape job submitted** via `swarm submit <url> --refresh` → task `5ec81984-…`; its result confirmed the documented difference: `resultSet` contains only the URL marker (no columns), with `pageContentBytes` proving the fetch.
6. **Polled to completion** — batch status went 3/7 → 9/1 → 11/11; after `swarm close`, every one of the 11 jobs was individually re-polled and reported `isDone: true, statusCode: 200, lifecycleState: completed`.
7. **Results reviewed** — all 10 X-SQL rows contain correct, distinct data (e.g. `4K OLED TV 55 | $899.99 | picsum…/1250857624`, `Vacuum Cleaner Smart | $159.99`, `LED Desk Lamp | $35.99 | …`), 11.6–15.3 KB page content each.
8. **Task history listed** — `swarm list` showed all 11 tasks (10 `swarm-query` + 1 `swarm-submit`) with per-task durations and a batch window.
9. **Session closed** — `swarm close` reported browser terminated, results retained.

Overall: the swarm workflow worked first-try exactly as documented. The issues found are reporting/consistency defects, not blockers.

### Execution Context

**Key Commands:**

```
./b4w.ps1 help                                       # overview + category map
./b4w.ps1 help swarm / --help swarm create / --help swarm query
./b4w.ps1 swarm list                                 # found 3 stale tasks from prior session
./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
./b4w.ps1 "swarm" "query" "--sql" "@…/swarm-product-query.sql" "--seed-file" "…/swarm-seeds.txt" "--refresh"
./b4w.ps1 swarm submit "http://localhost:18080/ec/dp/B0E000001" --refresh
./b4w.ps1 swarm status <batch-id> / <task-id>        # repeated polling
./b4w.ps1 swarm result <task-id>                     # ×11, saved to files
./b4w.ps1 swarm list / --batch <id> / --json         # task history
./b4w.ps1 swarm close
```

**Decisions & notes:**
- Per the swarm reference's Git Bash guidance, arguments with `--`/`@` were individually quoted in the `swarm query` invocation; the plain unquoted form also worked in this environment for `create`/`submit`/`list`.
- Did not use `--wait` (the scenario explicitly required manual status polling); polled the batch, sampled individual tasks mid-run, then re-polled all 11 after close.
- X-SQL used `DOM_BASE_URI`, `DOM_FIRST_TEXT`, and `DOM_FIRST_ATTR(…, 'abs:src')` so image URLs come back absolute.
- **Workarounds required: none** for failures (none occurred). Root-caused two display anomalies directly in the CLI source (`main.rs`, `state.rs`).

**Artifacts** (all in `.test-sessions/20261013T0154334271610Z/`): `swarm-seeds.txt`, `swarm-product-query.sql`, `swarm-task-ids.txt`, `swarm-result-*.json` (11), `swarm-list.json`, `swarm-product-sample.html`, `swarm-evidence.md`.

---

---

## Issues Found (4 issues)

### Issue 1: swarm list prints the batch window in UTC while task rows are local time — 8-hour offset within a single output

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 swarm list   (on any machine whose timezone is not UTC; here UTC+8)
Compare the 'Batch window:' header with the STARTED/FINISHED columns of the table rows.

#### Expected Behavior

Every timestamp in one command's output uses the same time base (ideally local time with a date/zone suffix), so the window and the rows can be compared directly.

#### Actual Behavior

The header shows 'Batch window: 17:12:05 -> 17:13:09' while the same tasks' rows show '2026-10-14 01:12:05' / '01:13:09' — the same instants rendered 8 hours apart. `swarm list --json` confirms the mapping: started_at 2026-10-13T17:12:06.729Z corresponds to the 01:12:06 row. `swarm status` additionally prints UTC ISO strings (lastModifiedTime ...Z), so the command family mixes two conventions.

#### Root Cause Analysis

Two formatting paths that never agree outside UTC: the batch window is formatted straight from chrono::DateTime<Utc> with .format("%H:%M:%S") (cli/browser4-cli/src/main.rs, batch-summary block around lines 15155-15163), while per-task rows are converted with .with_timezone(&chrono::Local) inside summarize_async_tasks (cli/browser4-cli/src/state.rs:1548 and :1566). On a whole-hour offset the minute/second values still look self-consistent, which makes the mismatch easy to miss and easy to misread as a window from a different day/batch.

#### Code Pointer

`cli/browser4-cli/src/main.rs:15155-15163 (batch window formatting in the swarm list handler); compare cli/browser4-cli/src/state.rs:1497 summarize_async_tasks (rows, uses chrono::Local)`

#### AI Suggested Improvement

- Convert the batch-window instants with .with_timezone(&chrono::Local) before formatting, or route both the header and rows through one shared timestamp formatter
- Include the date and an explicit zone marker in the header (e.g. 'Batch window (local): 2026-10-14 01:12:05 -> 01:13:09')
- Add a regression test that asserts the header and the first/last row render the same clock time under a non-UTC TZ
- Decide the convention for `swarm status` too (it emits UTC 'Z' timestamps) and document it, so users correlating with external logs are not surprised

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: DURATION column understates task time-to-result by ~3 orders of magnitude — queue wait is invisible

**Severity:** Low
**Category:** UX

#### Reproduction

1. Submit a batch: ./b4w.ps1 swarm query --sql @q.sql --seed-file seeds.txt
2. Poll until done, then compare `./b4w.ps1 swarm list` with `./b4w.ps1 swarm list --json`.

#### Expected Behavior

The DURATION column either reflects how long each job actually took (submit to result), or the output makes it explicit that only worker-processing time is counted.

#### Actual Behavior

Task 71eb6240 was submitted at 17:11:51.862Z and completed at 17:12:06.740Z (~15 s to result), but the table shows DURATION 10ms; the whole 10-URL batch spanned 1m03s while row durations are 5-38ms. A page-scraping job completing in 10 ms is implausible if a browser context loaded the page, so either the metric timestamps only a late pipeline phase or a non-browser fetch path served the pages; the table gives no way to tell queue time from work time.

#### Root Cause Analysis

duration_ms is computed strictly as started_at -> completed_at (as documented in swarm.md), excluding queue wait; the table has no column for submitted->completed elapsed even though --json carries submitted_at. Whether started_at is stamped when real processing begins (vs just before completion) requires backend instrumentation in the browser4-swarm task runner to confirm.

#### Code Pointer

`cli/browser4-cli/src/state.rs:1497 summarize_async_tasks (DURATION column); backend timing lives in the browser4-swarm plugin task runner`

#### AI Suggested Improvement

- Add a WAITED/QUEUED column (submitted_at to started_at) or an ELAPSED column (submitted_at to completed_at) so time-to-result is visible without --json
- If the processing window is intentionally only the fetch/parse phase, label the column accordingly (e.g. PROCESS) and state in `--help swarm list` that queue time is excluded
- Verify server-side what started_at actually marks; if it is stamped immediately before completion, fix the instrumentation so the metric reflects real processing duration

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: swarm list --batch header reads as a mismatch: 'Batch <id>: 10 of 11 tracked swarm task(s)'

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 swarm list --batch 879773d4-c6bf-47e5-a6eb-11272a77d3b0   (with at least one other tracked task, e.g. a swarm-submit job, present)

#### Expected Behavior

The header states plainly how many tasks the batch contains and how many exist overall, e.g. 'Batch <id>: 10 task(s) (11 tracked in total)'.

#### Actual Behavior

It prints 'Batch 879773d4-...: 10 of 11 tracked swarm task(s)', which reads like 10 of 11 selected/failed; the 11th row actually belongs to a different batch (the plain scrape job). The same 'N of M tracked' pattern appears on the lifecycle-filter line. Additionally, the unfiltered list still labels its summary 'Batch window' even when the visible tasks span several batches, where 'Batch' is ambiguous.

#### Root Cause Analysis

The label is built as '{filtered.len()} of {before} tracked swarm task(s)', where `before` is the count of ALL tracked tasks captured before the batch filter is applied — cli/browser4-cli/src/main.rs:15098 (same pattern at 15116 for the status filter); the window label is at 15158.

#### Code Pointer

`cli/browser4-cli/src/main.rs:15098 (batch filter header), :15116 (status filter header), :15158 (batch window label)`

#### AI Suggested Improvement

- Reword to 'Batch <id>: N task(s) (M tracked in total)'
- Print the batch header only when --batch is given; when listing everything, label the summary 'Tracked window' or 'Submission window'
- Apply the same wording fix to the lifecycle-filter line

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: swarm create silently auto-cleans completed tasks from prior sessions in non-TTY runs; the documented prompt/--clear-stale model doesn't cover it

**Severity:** Low
**Category:** Documentation

#### Reproduction

1. Have completed swarm tasks tracked from a prior session (visible in ./b4w.ps1 swarm list).
2. From a non-TTY context (script, CI, piped shell), run: ./b4w.ps1 swarm create --display-mode HEADLESS
3. Observe 'Auto-cleaned 3 completed swarm task(s) from prior sessions.' and the prior tasks disappearing from swarm list.

#### Expected Behavior

Per skills/browser4-cli/references/swarm.md, swarm create warns/prompts in a TTY and --clear-stale is the mechanism 'for non-interactive/scripted use'; the non-TTY behavior (auto-clean without the flag) should be documented so scripts relying on retained tracking are not surprised.

#### Actual Behavior

Without --clear-stale and with stdin not a TTY, the CLI removed the prior completed tasks' tracking entries automatically (one informational line, no prompt, exit 0). The docs describe only the interactive prompt, implying non-interactive runs without --clear-stale keep the old tasks.

#### Root Cause Analysis

The non-TTY code path of swarm create auto-clears completed tasks instead of prompting or skipping; swarm.md's 'List Tracked Tasks' tip (and the --help swarm create notes) were not updated for this behavior. Which lifecycle states are eligible (completed only, or failed/queued too) was not determined in this run.

#### Code Pointer

`cli/browser4-cli/src/main.rs (swarm create handling / stale-task clearing); skills/browser4-cli/references/swarm.md ('List Tracked Tasks' tip) and --help swarm create notes (cli/browser4-cli/src/help.rs)`

#### AI Suggested Improvement

- Document the non-TTY auto-clean behavior in swarm.md and in the swarm create notes
- State exactly which tasks are auto-cleaned (completed only vs failed/queued as well)
- If retention matters for scripts, offer a --keep/--no-clean flag or print the cleaned task ids so they can be reviewed

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

**Completion Status:** Successful — all 9 scenario steps completed end-to-end: swarm session created with the requested HEADLESS/2-context/4-tab options, 10-URL seed file and X-SQL query file written, 10 extraction jobs submitted with --sql @file --seed-file --refresh and all polled to completion, one plain scrape job submitted via swarm submit for comparison, all results retrieved and verified (10/10 rows contain correct distinct title/price/absolute image URL), full task history listed, session closed. No step failed and no retry was needed.

**Success Rate:** 100%

**Issues Found:** 4

**Most Confusing Aspects:** The batch-window header and the task rows in the same `swarm list` output use different time bases (UTC vs local, 8 hours apart here), which makes the output look internally inconsistent at first glance. The 'Batch <id>: 10 of 11 tracked swarm task(s)' header reads like a selection/failure count. DURATION values of 5-38 ms for page-scraping tasks contradict the ~1-minute wall clock of the batch, leaving users unsure what the column measures. There is also no visible queue-time metric despite the documented ~30-60 s cold start of a fresh swarm session.

**Most Valuable Improvements:** 1) Make all timestamps in swarm list (and across swarm status) use one consistent, zone-labeled time base. 2) Surface queue wait / time-to-result per task (a WAITED or ELAPSED column) so DURATION is not misread. 3) Clarify batch/status filter header wording. 4) Document the non-TTY swarm create auto-clean behavior. Everything else — command discovery via help, seed-file comment handling, batch ids, status/result polling, and resource cleanup — worked exactly as documented and needed no improvement.

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

#### Issue 1: swarm list prints the batch window in UTC while task rows are local time — 8-hour offset within a single output

./b4w.ps1 swarm list   (on any machine whose timezone is not UTC; here UTC+8)
Compare the 'Batch window:' header with the STARTED/FINISHED columns of the table rows.

#### Issue 2: DURATION column understates task time-to-result by ~3 orders of magnitude — queue wait is invisible

1. Submit a batch: ./b4w.ps1 swarm query --sql @q.sql --seed-file seeds.txt
2. Poll until done, then compare `./b4w.ps1 swarm list` with `./b4w.ps1 swarm list --json`.

#### Issue 3: swarm list --batch header reads as a mismatch: 'Batch <id>: 10 of 11 tracked swarm task(s)'

./b4w.ps1 swarm list --batch 879773d4-c6bf-47e5-a6eb-11272a77d3b0   (with at least one other tracked task, e.g. a swarm-submit job, present)

#### Issue 4: swarm create silently auto-cleans completed tasks from prior sessions in non-TTY runs; the documented prompt/--clear-stale model doesn't cover it

1. Have completed swarm tasks tracked from a prior session (visible in ./b4w.ps1 swarm list).
2. From a non-TTY context (script, CI, piped shell), run: ./b4w.ps1 swarm create --display-mode HEADLESS
3. Observe 'Auto-cleaned 3 completed swarm task(s) from prior sessions.' and the prior tasks disappearing from swarm list.

