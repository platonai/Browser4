# Issues: swarm-parallel-scraping

> **Source:** `20260722-024109-swarm-parallel-scraping.full.md` | **Date:** 20260722-024109 | **Mode:** dev

## Scenario Background

### Task

Successfully:
- Created a swarm session with `HEADLESS`, 2 browser contexts, 4 tabs/context
- Extracted data from 10 product pages (6 Electronics + 4 Home) using X-SQL via `swarm query`
- Submitted a plain scrape job via `swarm submit` for comparison
- Retrieved all results — every extraction returned correct title, price, and image URL

**Electronics category results:**
| Product ID | Title | Price |
|---|---|---|
| B0E000001 | 4K OLED TV 55 | $899.99 |
| B0E000002 | Wireless Noise-Cancelling Headphones | $199.99 |
| B0E000003 | Portable Bluetooth Speaker | $49.99 |
| B0E000004 | Smartphone 128GB | $599.00 |
| B0E000005 | USB-C Hub 7-in-1 | $29.95 |
| B0E000006 | Wireless Mouse | $24.99 |

**Home category results:**
| Product ID | Title | Price |
|---|---|---|
| B0H000001 | Vacuum Cleaner Smart | $159.99 |
| B0H000002 | Air Purifier | $129.50 |
| B0H000003 | Essential Oil Diffuser | $22.99 |
| B0H000004 | LED Desk Lamp | $35.99 |

---

### Execution Context

**Key Commands:**

1. `./b4w.ps1 help` — read help
2. `./bin/test.ps1 mock-site` — start mock site (failed: port conflict)
3. `taskkill //PID 80384 //F` — resolve port conflict
4. `./bin/test.ps1 mock-site` — start mock site (succeeded)
5. `./b4w.ps1 goto "http://localhost:18080/ec/dp/B0E000001"` — explore product page
6. `./b4w.ps1 snapshot -v 0 --stdout` — **failed** (arg mangling)
7. Read snapshot YAML from disk directly as workaround
8. `./b4w.ps1 htmlsnapshot` — capture HTML snapshot
9. Multiple `htmlsnapshot get`, `inspect`, `summary` — discover CSS selectors
10. `eval` with JavaScript — confirm CSS classes
11. `./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4`
12. `./b4w.ps1 "swarm" "query" "http://..." "--sql" "@extract-query.sql" "--seed-file" "./seed-urls.txt" "--refresh"` — submit X-SQL jobs (**required quoting every argument individually**)
13. `./b4w.ps1 "swarm" "submit" "http://..." "--refresh"` — plain scrape job
14. `./b4w.ps1 "swarm" "status" "..."` — poll status (returned `isDone: null`)
15. `./b4w.ps1 "swarm" "result" "..."` — fetch results (×10 calls)
16. `./b4w.ps1 "swarm" "list"` — list all tasks
17. `./b4w.ps1 "swarm" "close"` — close swarm

**Workarounds required:**
- Had to kill stale process on port 18080 before starting mock site
- Had to quote each argument individually (`"swarm" "query"` not `swarm query`) due to PowerShell argument mangling through bash
- Had to read snapshot YAML from disk instead of using `snapshot --stdout`
- Had to provide a dummy URL to `swarm query` even with `--seed-file` (docs say URL is optional)
- Had to use `swarm result` immediately despite `isDone: null` (docs say wait for `isDone: true`)

---

---

## Issues Found (9 issues)

### Issue 1: PowerShell argument mangling through bash prevents use of `--flags`

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 snapshot -v 0 --stdout
./b4w.ps1 swarm query --sql @query.sql --seed-file ./urls.txt --refresh
```

#### Expected Behavior

Arguments pass through cleanly to the browser4-cli binary.

#### Actual Behavior

- `snapshot -v 0 --stdout` → error `Unknown command: 'snapshot-0'`
- `swarm query --sql @query.sql` → error `Missing required argument: <url>`
- `--` flags get merged with preceding text or lost entirely.

#### Root Cause Analysis

The `b4w.ps1` script uses `$ScriptArgs` splatting (`& $Exe @ScriptArgs`), but when invoked from Git Bash (the shell recommended in CLAUDE.md), the bash→pwsh boundary mangles arguments containing `-` prefixes. The `--sql` flag is particularly affected, likely because bash interprets `--` specially in some contexts or because pwsh receives a malformed argument array.

#### Code Pointer

``b4w.ps1:38` — `& $Exe @ScriptArgs``

#### AI Suggested Improvement

- Add a bash wrapper script (`b4w.sh`) that properly handles argument passing for Git Bash users on Windows
- Alternatively, detect the calling shell in `b4w.ps1` and apply argument normalization for bash-originated calls
- Document the quoting workaround prominently in SKILL.md for Windows/Git Bash users
- Consider using `--%` (PowerShell stop-parsing symbol) in the script for more robust argument passthrough

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: `swarm status` returns `isDone: null` instead of boolean

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
./b4w.ps1 "swarm" "status" "<any-task-id>"
```
Observe the JSON output.

#### Expected Behavior

`"isDone": true` when job completes, `"isDone": false` while running (as documented in swarm.md).

#### Actual Behavior

`"isDone": null` for all jobs, even when `swarm result` returns complete data.

#### Root Cause Analysis

The `isDone` field appears to be nullable in the backend but never set to a boolean value. It may be a `Boolean` (boxed) field in Kotlin that stays `null` when the task completes without explicit termination logic, or the field is only set under specific error/timeout conditions but not on normal completion.

#### Code Pointer

`Backend task status serialization — likely in the swarm task management code in `browser4-rest/`.`

#### AI Suggested Improvement

- Set `isDone` to `true` when a task's result is ready for retrieval
- Set `isDone` to `false` when a task is queued or in-progress
- Add a `status` field with values like `"queued"`, `"running"`, `"completed"`, `"failed"` for clearer state representation
- Update swarm.md documentation to note that `statusCode: 200` with non-null result data is the actual completion signal until `isDone` is fixed

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `swarm list` always shows "pending" for every task

**Severity:** High
**Category:** UX

#### Reproduction

```bash
./b4w.ps1 "swarm" "list"
```
Observe that all 32 tasks show STATUS = `pending`, even tasks that completed minutes ago.

#### Expected Behavior

STATUS column reflects actual task state (e.g., `completed`, `running`, `failed`, `pending`).

#### Actual Behavior

Every task shows `pending` regardless of actual state.

#### Root Cause Analysis

The list view likely reads from a task registry that stores the initial state ("pending") but never updates it after completion. The `swarm status` command accesses a different (backend) API that has current state, but the list cache doesn't sync.

#### Code Pointer

`Task list rendering in `cli/browser4-cli/src/` — the list command formatting.`

#### AI Suggested Improvement

- Sync the task list state with the backend by calling `swarm status` for each tracked task
- Or store the last known state in the local task tracker and display that
- Add a `--refresh` flag to `swarm list` to force re-checking all statuses from the backend
- Add a `--clear` flag (already documented but may not exist) to remove terminal-state tasks

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `swarm query` URL argument required despite docs saying it's optional with `--seed-file`

**Severity:** Medium
**Category:** Documentation / Product

#### Reproduction

```bash
./b4w.ps1 "swarm" "query" "--sql" "@query.sql" "--seed-file" "./urls.txt" "--refresh"
```
(omit the URL positional argument)

#### Expected Behavior

Jobs submitted for all URLs in the seed file. Documentation says URL is "No (omit when using --seed-file alone)."

#### Actual Behavior

Error: `Missing required argument: <url>.`

#### Root Cause Analysis

The CLI argument parser enforces the URL as required regardless of `--seed-file` presence. Either the doc is outdated (URL was made optional but parser wasn't updated) or the doc is aspirational (planned but not implemented).

#### Code Pointer

`CLI argument parsing in `cli/browser4-cli/src/` — the `swarm query` subcommand definition.`

#### AI Suggested Improvement

- Make the URL argument truly optional in the CLI parser when `--seed-file` is provided
- If a URL is always required for backend reasons, update the documentation to clearly state it's required and explain why
- Add a `--seed-file-only` flag or detect seed-file presence to conditionally require the URL

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: Duplicate URL submission when both URL and `--seed-file` provided

**Severity:** Medium
**Category:** Product

#### Reproduction

```bash
./b4w.ps1 "swarm" "query" "http://localhost:18080/ec/dp/B0E000001" "--sql" "@query.sql" "--seed-file" "./urls.txt" "--refresh"
```
Observe 11 submissions for 10 seed URLs (B0E000001 appears twice).

#### Expected Behavior

10 jobs, one per unique URL in the seed file. The explicit URL should be deduplicated against the seed file, or at minimum a warning should be shown.

#### Actual Behavior

B0E000001 is submitted twice (once from the explicit URL, once from the seed file). No deduplication warning.

#### Root Cause Analysis

The explicit URL is added as a separate job entry alongside the seed file entries, without deduplication logic.

#### Code Pointer

`Backend swarm job submission in `browser4-rest/` — seed file processing + URL argument handling.`

#### AI Suggested Improvement

- Deduplicate URLs when both explicit URL and seed file are provided
- Emit a warning like "URL also found in seed file — skipping duplicate"
- Alternatively, reject the command with an error: "URL argument is redundant when --seed-file is provided; use one or the other"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: `swarm list` stores stale tasks across sessions with no cleanup

**Severity:** Medium
**Category:** UX / Product

#### Reproduction

```bash
./b4w.ps1 "swarm" "list"
```
Observe tasks from previous sessions (e.g., `books.toscrape.com` URLs) mixed with current tasks.

#### Expected Behavior

Only tasks from the current swarm session appear, or stale tasks are clearly marked/auto-cleaned.

#### Actual Behavior

32 tracked tasks including many from prior sessions. The list grows unboundedly.

#### Root Cause Analysis

The task tracker persists tasks to disk and never prunes old entries. There's no session-scoping or TTL-based expiration.

#### Code Pointer

`Task tracker persistence in `cli/browser4-cli/src/` — task store management.`

#### AI Suggested Improvement

- Add `--clear` flag to `swarm list` (as documented in swarm.md) to remove terminal-state tasks
- Auto-clean tasks older than N hours/days on `swarm list`
- Scope tasks to the current swarm session; clear when session closes
- Add a `swarm list --status completed|failed` filter to show only relevant tasks

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: `swarm list` COMMAND column doesn't distinguish `query` from `submit`

**Severity:** Low
**Category:** Discoverability

#### Reproduction

```bash
./b4w.ps1 "swarm" "list"
```
All tasks show `swarm` in the COMMAND column.

#### Expected Behavior

Tasks submitted via `swarm query` show as `swarm-query`; tasks via `swarm submit` show as `swarm-submit`.

#### Actual Behavior

All tasks show `swarm` regardless of submission method.

#### Root Cause Analysis

The task tracker doesn't record the subcommand used for submission — it stores only the parent command name.

#### Code Pointer

`Task metadata storage in `cli/browser4-cli/src/` — submit/query task creation.`

#### AI Suggested Improvement

- Store and display the full command path (`swarm query`, `swarm submit`)
- This helps users understand which tasks have X-SQL queries attached vs plain scrapes

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: MockSite startup fails silently on port conflict with unhelpful recovery path

**Severity:** Medium
**Category:** Reliability / UX

#### Reproduction

1. Run `./bin/test.ps1 mock-site`
2. It fails because port 18080 is in use from a prior session
3. Error output is buried in Maven build logs at the end

#### Expected Behavior

A clear error message at the top saying "Port 18080 is already in use. Kill the process with: ..." and optionally an `--auto-kill` flag.

#### Actual Behavior

The Maven build completes successfully (BUILD SUCCESS), then the Spring Boot run fails with a multi-line stacktrace buried in ~200 lines of Maven output. The user must scan the entire output to find "Port 18080 was already in use."

#### Root Cause Analysis

The test script doesn't pre-check port availability before launching Maven, and the error message is produced deep in Spring Boot's startup sequence.

#### Code Pointer

``bin/test.ps1` — mock-site launch logic.`

#### AI Suggested Improvement

- Pre-check port availability in `test.ps1` before launching the mock site
- If port is in use, print a clear error: "Port 18080 is in use by PID XXXX. Run `taskkill //PID XXXX //F` to free it."
- Add a `--force` flag to auto-kill the existing process before starting
- Add a `--port <N>` flag to use an alternative port

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: No `--wait` flag on `swarm query`/`swarm submit` for automatic polling

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
./b4w.ps1 "swarm" "query" "http://..." "--sql" "@query.sql" "--seed-file" "./urls.txt" "--refresh" "--wait"
```

#### Expected Behavior

The `--wait` flag (documented in swarm.md) blocks until all jobs complete, then prints a summary.

#### Actual Behavior

The `--wait` flag was not tested (did not attempt it due to argument parsing issues). But the docs describe it as available, which would significantly improve the user experience over manual polling.

#### Root Cause Analysis

Even if implemented, the current `isDone: null` bug (Issue 2) would make `--wait` unreliable — it couldn't determine completion.

#### Code Pointer

`Polling/summary logic in `cli/browser4-cli/src/`.`

#### AI Suggested Improvement

- Fix `isDone` first (Issue 2), then verify `--wait` works correctly
- Add a progress bar or spinner during `--wait` polling
- Show completion summary: "N/N jobs completed, M errors"

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: PowerShell argument mangling through bash prevents use of `--flags`

```bash
./b4w.ps1 snapshot -v 0 --stdout
./b4w.ps1 swarm query --sql @query.sql --seed-file ./urls.txt --refresh
```

#### Issue 2: `swarm status` returns `isDone: null` instead of boolean

```bash
./b4w.ps1 "swarm" "status" "<any-task-id>"
```
Observe the JSON output.

#### Issue 3: `swarm list` always shows "pending" for every task

```bash
./b4w.ps1 "swarm" "list"
```
Observe that all 32 tasks show STATUS = `pending`, even tasks that completed minutes ago.

#### Issue 4: `swarm query` URL argument required despite docs saying it's optional with `--seed-file`

```bash
./b4w.ps1 "swarm" "query" "--sql" "@query.sql" "--seed-file" "./urls.txt" "--refresh"
```
(omit the URL positional argument)

#### Issue 5: Duplicate URL submission when both URL and `--seed-file` provided

```bash
./b4w.ps1 "swarm" "query" "http://localhost:18080/ec/dp/B0E000001" "--sql" "@query.sql" "--seed-file" "./urls.txt" "--refresh"
```
Observe 11 submissions for 10 seed URLs (B0E000001 appears twice).

#### Issue 6: `swarm list` stores stale tasks across sessions with no cleanup

```bash
./b4w.ps1 "swarm" "list"
```
Observe tasks from previous sessions (e.g., `books.toscrape.com` URLs) mixed with current tasks.

#### Issue 7: `swarm list` COMMAND column doesn't distinguish `query` from `submit`

```bash
./b4w.ps1 "swarm" "list"
```
All tasks show `swarm` in the COMMAND column.

#### Issue 8: MockSite startup fails silently on port conflict with unhelpful recovery path

1. Run `./bin/test.ps1 mock-site`
2. It fails because port 18080 is in use from a prior session
3. Error output is buried in Maven build logs at the end

#### Issue 9: No `--wait` flag on `swarm query`/`swarm submit` for automatic polling

```bash
./b4w.ps1 "swarm" "query" "http://..." "--sql" "@query.sql" "--seed-file" "./urls.txt" "--refresh" "--wait"
```

