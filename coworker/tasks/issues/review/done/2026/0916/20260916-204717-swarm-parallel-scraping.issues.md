# Issues: swarm-parallel-scraping

> **Source:** `20260916-204717-swarm-parallel-scraping.full.md` | **Date:** 20260916-204717 | **Mode:** dev

## Scenario Background

### Task

All 10 MockSite product pages were extracted in parallel by a swarm session, plus one plain scrape job for comparison. Every returned row was cross-checked against the live HTML: **11/11 matched exactly**.

| url | title | price | image_url |
|---|---|---|---|
| /ec/dp/B0E000001 | 4K OLED TV 55 | $899.99 | picsum.photos/seed/1250857624/200/140 |
| /ec/dp/B0E000002 | Wireless Noise-Cancelling Headphones | $199.99 | …/1250857625/200/140 |
| /ec/dp/B0E000003 | Portable Bluetooth Speaker | $49.99 | …/1250857626/200/140 |
| /ec/dp/B0E000004 | Smartphone 128GB | $599.00 | …/1250857627/200/140 |
| /ec/dp/B0E000005 | USB-C Hub 7-in-1 | $29.95 | …/1250857628/200/140 |
| /ec/dp/B0E000006 | Wireless Mouse | $24.99 | …/1250857629/200/140 |
| /ec/dp/B0H000001 | Vacuum Cleaner Smart | $159.99 | …/-381598629/200/140 |
| /ec/dp/B0H000002 | Air Purifier | $129.50 | …/-381598628/200/140 |
| /ec/dp/B0H000003 | Essential Oil Diffuser | $22.99 | …/-381598627/200/140 |
| /ec/dp/B0H000004 | LED Desk Lamp | $35.99 | …/-381598626/200/140 |

**Submission-method comparison (step 5).** The documented distinction held exactly: `swarm query --sql` returned `resultSet` rows carrying `url/title/price/image_url`, while plain `swarm submit` returned only `[{"url": …}]` plus `pageContentBytes: 15296` — a fetch-confirmation marker with no data columns. Both are tracked identically in `swarm list` (COMMAND column distinguishes `swarm-query` from `swarm-submit`).

### Execution Context

Commands: `swarm create` → seed/query files → `swarm query --sql @file --seed-file --refresh` → `swarm submit <url> --refresh` → 3 rounds of per-task `swarm status` → 11× `swarm result` → `swarm list` → `swarm close`, plus `--help` variants and error-path probes.

Key decisions: (1) I inspected the raw product HTML first and scoped the query to `#product-page` rather than `:root`, because the page also contains `.recommendation-card` blocks with their own `h3`/price — a document-wide selector would have silently picked up recommendation data. (2) I pre-flighted the X-SQL with `htmlsnapshot query` against one URL before spending 10 swarm jobs on it. (3) I verified results against the live pages rather than trusting `statusCode: 200`.

Workarounds needed: none blocking. `--sql @file` and `--...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)

### Issue 1: swarm list DURATION reports ~0ms for tasks that actually took 16-66s

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 swarm query --sql @product-extract.sql --seed-file seed-products.txt --refresh
./b4w.ps1 swarm list
./b4w.ps1 swarm list --batch cfb81172-eeb8-4baa-abd4-a3b22ed697bb --json

#### Expected Behavior

DURATION reflects how long each task took. swarm.md section 5 states: "swarm list answers 'how long did each task take?' directly - every row carries STARTED, FINISHED and DURATION (started -> finished; tasks that never ran fall back to submitted -> finished)".

#### Actual Behavior

Every task showed DURATION 0ms-3ms while the same output reported a 49.9s batch window. The JSON proves the timestamps are wrong: submitted_at=2026-09-16T20:43:15.237175300+00:00, started_at=2026-09-16T20:43:38.533348800Z, completed_at=2026-09-16T20:43:38.534346600Z, duration_ms=0. started_at is stamped ~1ms BEFORE completed_at, so the real 23.3s queue-to-finish latency is invisible. The same root cause distorts the batch window (20:43:31 -> 20:44:21 = 49.9s, computed from min(started_at)); the true submission-to-completion window was 20:43:15 -> 20:44:21 = 66s. swarm status <batch-id> also prints a bogus "slowest task: 132416a6 (2ms)".

#### Root Cause Analysis

ScrapeResponse.refresh() sets `startedTime = now` on the FIRST refresh call (guarded by `if (startedTime == null)`). The comment says "Record the first time a worker touches this task", but for these swarm jobs the first refresh happens when the result is written - i.e. at completion - not when the worker dequeues the task. durationMillis is then computed as (finishTime - startedTime), giving ~1ms. The backend model documents startedTime as "Set when a worker first picks up the task (first PROCESSING transition)", but no code path stamps it at dequeue for this flow. Needs confirmation of the worker dequeue path (AbstractScrapeHyperlink / DegenerateXSQLScrapeHyperlink) to see whether a PROCESSING transition ever happens before the terminal write.

#### Code Pointer

`browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/crawl/Models.kt:118 (ScrapeResponse.refresh) and browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/crawl/common/AbstractScrapeHyperlink.kt:55`

#### AI Suggested Improvement

- Stamp startedTime at worker dequeue (the first PROCESSING transition), not inside the generic refresh() helper that also runs at terminal write.
- If a genuine dequeued-at timestamp is unavailable for legacy tasks, fall back to createdTime for the duration so the value stays meaningful rather than reporting 0ms.
- Add a regression test asserting duration_ms > 0 for a task whose submitted_at and completed_at differ by a measurable margin.
- Have the CLI's batch-window computation (cli/browser4-cli/src/main.rs:12156) prefer submitted_at for the window start, since started_at can be absent or unreliable.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: swarm list prints the batch window in UTC but the task table in local time

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 swarm list

#### Expected Behavior

All timestamps in one command output share a single time base, ideally local time with an explicit offset or timezone marker.

#### Actual Behavior

The same invocation printed "Batch window: 20:43:31 -> 20:44:21 (49.9s), 11 task(s)" and table rows reading "2026-09-17 04:43:54". On this box local time is UTC+8 (verified: `date` -> 04:44:55 +0800, `date -u` -> 20:44:55), so the window line is UTC while the rows are local - a reader sees a batch that appears to have run 8 hours before its own tasks. swarm status <batch-id> has the same UTC window line.

#### Root Cause Analysis

cli/browser4-cli/src/main.rs:12177 formats the batch window with `started.format("%H:%M:%S")` directly on the chrono DateTime<Utc> returned by state::parse_timestamp (which normalizes to Utc), while the table rows go through state::format_timestamp_display, which explicitly converts with `dt.with_timezone(&chrono::Local)`. The window path simply skips the local conversion.

#### Code Pointer

`cli/browser4-cli/src/main.rs:12177 (Batch window println) vs cli/browser4-cli/src/state.rs:1311 (format_timestamp_display)`

#### AI Suggested Improvement

- Convert the window endpoints to local time before formatting, matching format_timestamp_display.
- Include the date (or at least a timezone marker such as a trailing +08:00/UTC label) in the batch window line, so a window is unambiguous even across a day boundary.
- Add a CLI test that asserts the window line and the table rows derive from the same formatting helper.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: swarm status and swarm result exit 0 on a 404, making failures indistinguishable from empty results

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 swarm result 00000000-0000-0000-0000-000000000000; echo "exit=$?"
./b4w.ps1 swarm status 00000000-0000-0000-0000-000000000000; echo "exit=$?"

#### Expected Behavior

Per swarm.md's Errors & Recovery table ("All subcommands exit non-zero | Check stderr for details") and the same exit-code contract htmlsnapshot query honours, an unresolvable task id should exit non-zero so scripts can detect the failure without parsing JSON.

#### Actual Behavior

Both commands printed a 404 envelope ("Swarm task not found: 00000000-...") and exited 0. swarm result additionally emitted "resultSet": [] - which is byte-identical in shape to a legitimate zero-row query result, so `swarm result $id | jq .resultSet` reports "no rows" for a task that does not exist. Control tests confirm the wrapper propagates exit codes correctly (config set timeout 0 -> exit 1, unknown command -> exit 2), so the fault is in the swarm subcommands.

#### Root Cause Analysis

The swarm status/result handlers print the response envelope and return success for any HTTP status that yields a parseable body, rather than checking statusCode/isDone/error before choosing the process exit code. Because the not-found response is a well-formed envelope with statusCode 404 rather than a transport error, it never reaches the error path.

#### Code Pointer

`cli/browser4-cli/src/main.rs (swarm status / swarm result handlers; cf. the exit-code contract implemented for htmlsnapshot query)`

#### AI Suggested Improvement

- Exit non-zero when the response statusCode is >= 400, consistent with htmlsnapshot query.
- Distinguish "task not found" from "task completed with an empty resultSet" in the human-readable output (e.g. a `Task <id> was not found` line rather than an empty array).
- Consider surfacing the not-found case on stderr so a piped stdout consumer never sees it as data.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: lifecycleState reads "completed" while isDone is still false

**Severity:** Medium
**Category:** Documentation

#### Reproduction

./b4w.ps1 swarm query --sql @product-extract.sql --seed-file seed-products.txt --refresh
sleep 10 && ./b4w.ps1 swarm status 81085d25-7690-4f08-ac49-5ba142ba34f0

#### Expected Behavior

swarm.md section 4 states "Terminal is not equal to successful, and statusCode: 200 is not equal to finished" and directs clients to use isDone, while the Task Lifecycle States table tells users to "Use lifecycleState for programmatic checks". A task that has not finished should not be labelled completed under either field.

#### Actual Behavior

During the first poll round the task returned "isDone": false together with "statusCode": 200, "status": "OK", "lifecycleState": "completed". A script following the documented advice to check lifecycleState would have consumed the task one round early, before resultSet was attached; isDone only became true in the following round.

#### Root Cause Analysis

lifecycleState is derived from statusCode (200 -> "completed"), and statusCode is set to SC_OK when the X-SQL stage begins rather than when the result is attached - exactly the hazard the isDone warning describes. Because lifecycleState inherits that mapping, the documented "use this for programmatic checks" field carries the same flaw it was meant to shield callers from.

#### Code Pointer

`skills/browser4-cli/references/swarm.md (section 4 note and Task Lifecycle States table); backend mapping in browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/SwarmService.kt`

#### AI Suggested Improvement

- Derive lifecycleState from isDone as well as statusCode, so "completed" is only reported once the task is terminal.
- Update the Task Lifecycle States table to note that a 200 statusCode can occur before isDone, and mark isDone as the only authoritative completion signal.
- Remove or qualify the "Use lifecycleState for programmatic checks" tip so it does not contradict the isDone warning three sections earlier.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: swarm close reports results are retained, but the next swarm create silently discards them

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 swarm create --display-mode HEADLESS   # run a batch
./b4w.ps1 swarm close
./b4w.ps1 swarm create --display-mode HEADLESS

#### Expected Behavior

The retention promise made by swarm close should hold until the user explicitly clears history with the documented `swarm list --clear`.

#### Actual Behavior

swarm close printed "All 11 tracked task(s) had already completed - results retained; use `swarm list --clear` to remove." The very next swarm create printed "Auto-cleaned 11 completed swarm task(s) from prior sessions." and swarm list then reported "No tracked async tasks." - the retained history was gone without any --clear-stale flag and without a prompt (correct for a non-TTY, but undocumented for this case).

#### Root Cause Analysis

Two behaviours with opposite intent: close deliberately retains the tracked task list, while create's stale-task cleanup treats any completed task from a prior session as stale and removes it unconditionally in non-interactive mode. swarm.md documents the interactive prompt and --clear-stale but not this non-TTY auto-clean, and does not reconcile it with the retention message.

#### Code Pointer

`cli/browser4-cli/src/main.rs (swarm create stale-cleanup vs swarm close retention message)`

#### AI Suggested Improvement

- Make the close message accurate: state that completed tasks are kept until the next swarm create, or that they will be cleared on the next session.
- Only auto-clean in non-interactive mode when --clear-stale is passed; otherwise retain completed tasks as the close message promises.
- Document the non-TTY cleanup behaviour in swarm.md's TIP about stale tasks.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: help with a quoted multi-word subcommand resolves to a useless stub

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help "swarm create"   # quoted form
./b4w.ps1 help swarm create       # unquoted form

#### Expected Behavior

Both invocations show the swarm create options, since SKILL.md documents `browser4-cli --help <command>` as the way to learn a command.

#### Actual Behavior

`help "swarm create"` printed only:
  swarm create subcommands:

    swarm create                  Create a swarm scrape session with parallel browser contexts
with no options, no Notes and no Examples - and exited 0, so nothing signals that the argument was not understood as a subcommand path. `help swarm create` printed the full option list. The quoted form is the natural thing to type in PowerShell, where an unquoted multi-word argument is easy to get wrong.

#### Root Cause Analysis

The help dispatcher splits the argument into category and command using separate argv tokens, so a single token containing a space is matched as a category name rather than split into its two parts. The fallback then renders the domain heading plus its subcommand index instead of erroring or re-parsing.

#### Code Pointer

`cli/browser4-cli/src/main.rs (help argument parsing / category resolution)`

#### AI Suggested Improvement

- Split a single help argument on whitespace before resolving it, so `help "swarm create"` behaves like `help swarm create`.
- When a help argument matches no command, print a short "unknown command" hint naming the nearest match instead of a bare domain stub.
- List the swarm create options (`--display-mode`, `--max-browser-contexts`, `--max-open-tabs`, `--clear-stale`) in the top-level `help swarm` output, which currently shows only a one-line description per subcommand.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: swarm.md omits the --sql-stdin and --sql-base64 flags that swarm query --help documents

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 swarm query --help

#### Expected Behavior

The reference document and the CLI's own help describe the same option set for swarm query.

#### Actual Behavior

swarm query --help lists --sql-stdin ("Read X-SQL query from stdin") and --sql-base64 alongside --sql, and its Notes explain that --seed-file takes a direct path while only --sql uses the @ prefix. swarm.md's swarm query option table lists only --sql/--seed-file/--refresh/--wait, so the Windows quoting workarounds the rest of the skill emphasises (shell-quoting.md) appear unavailable for swarm query if the reference is the only source consulted.

#### Root Cause Analysis

The reference document lags the CLI's option surface; the CLI help is generated from the argument definitions while swarm.md is hand-maintained, so newly added flags are not reflected.

#### Code Pointer

`skills/browser4-cli/references/swarm.md (section 3, Submit X-SQL Extraction Jobs)`

#### AI Suggested Improvement

- Add --sql-stdin and --sql-base64 to the swarm query option table, mirroring the htmlsnapshot query documentation.
- Note that --seed-file takes a plain path while --sql uses the @ prefix, since that asymmetry is a plausible source of user error.
- Consider a doc test that diffs the documented flag list against the CLI's --help output.

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

**Completion Status:** Successful - all 9 steps completed. The swarm session was created with the requested options, 10 product pages were extracted by X-SQL in parallel plus one plain-scrape job for comparison, every job was polled to completion, all 11 result payloads were retrieved and independently verified against the live pages (11/11 exact matches), the task history was listed, and the session was closed with resources released.

**Success Rate:** 95% - every task step succeeded and all extracted data was correct. The deducted margin is for the status and listing output: the DURATION column reported 0ms for tasks that took up to 66s, the batch window mixed time bases, and lifecycleState briefly contradicted isDone. None of these produced wrong extracted data, but all of them would mislead anyone using the CLI to judge throughput or completion.

**Issues Found:** 7

**Major Blockers:** None. The full workflow ran end to end on the documented commands with no retries, no workarounds and no failures. The swarm contexts were ready fast enough that jobs began completing about 16s after submission, well inside the documented 30-60s cold-start allowance.

**Most Confusing Aspects:** For a first-time user the most confusing aspect is that swarm list, the primary progress view, cannot be trusted for timing: it shows 0ms durations and a batch window whose clock disagrees with its own table rows by eight hours, so there is no way to tell from the output how long the run actually took. Close behind is the completion signal - lifecycleState said completed while isDone said false, and a missing task id exits 0 with an empty resultSet, so a script has no reliable way to distinguish 'finished', 'still running' and 'never existed'.

**Most Valuable Improvements:** Fix the startedTime stamp so DURATION and the batch window reflect real elapsed time, since that is a documented feature returning silently wrong numbers. Then make the reporting self-consistent: one time base per output, one authoritative terminal flag, and non-zero exits for error envelopes so scripts can detect failure without parsing JSON. All three are small, localised changes in the CLI's rendering and the ScrapeResponse model.

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

#### Issue 1: swarm list DURATION reports ~0ms for tasks that actually took 16-66s

./b4w.ps1 swarm query --sql @product-extract.sql --seed-file seed-products.txt --refresh
./b4w.ps1 swarm list
./b4w.ps1 swarm list --batch cfb81172-eeb8-4baa-abd4-a3b22ed697bb --json

#### Issue 2: swarm list prints the batch window in UTC but the task table in local time

./b4w.ps1 swarm list

#### Issue 3: swarm status and swarm result exit 0 on a 404, making failures indistinguishable from empty results

./b4w.ps1 swarm result 00000000-0000-0000-0000-000000000000; echo "exit=$?"
./b4w.ps1 swarm status 00000000-0000-0000-0000-000000000000; echo "exit=$?"

#### Issue 4: lifecycleState reads "completed" while isDone is still false

./b4w.ps1 swarm query --sql @product-extract.sql --seed-file seed-products.txt --refresh
sleep 10 && ./b4w.ps1 swarm status 81085d25-7690-4f08-ac49-5ba142ba34f0

#### Issue 5: swarm close reports results are retained, but the next swarm create silently discards them

./b4w.ps1 swarm create --display-mode HEADLESS   # run a batch
./b4w.ps1 swarm close
./b4w.ps1 swarm create --display-mode HEADLESS

#### Issue 6: help with a quoted multi-word subcommand resolves to a useless stub

./b4w.ps1 help "swarm create"   # quoted form
./b4w.ps1 help swarm create       # unquoted form

#### Issue 7: swarm.md omits the --sql-stdin and --sql-base64 flags that swarm query --help documents

./b4w.ps1 swarm query --help

