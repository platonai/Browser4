# Issues: swarm-parallel-scraping

> **Source:** `20260726-214110-swarm-parallel-scraping.full.md` | **Date:** 20260726-214110 | **Mode:** dev

## Scenario Background

### Task

Successfully extracted product data from 10 MockSite product pages using the swarm:

| Category | Product ID | Title | Price | Image |
|---|---|---|---|---|
| Electronics | B0E000001 | 4K OLED TV 55 | $899.99 | /ec/static/img/placeholder.png |
| Electronics | B0E000002 | Wireless Noise-Cancelling Headphones | $199.99 | /ec/static/img/placeholder.png |
| Electronics | B0E000003 | Portable Bluetooth Speaker | $49.99 | /ec/static/img/placeholder.png |
| Electronics | B0E000004 | Smartphone 128GB | $599.00 | /ec/static/img/placeholder.png |
| Electronics | B0E000005 | USB-C Hub 7-in-1 | $29.95 | /ec/static/img/placeholder.png |
| Electronics | B0E000006 | Wireless Mouse | $24.99 | /ec/static/img/placeholder.png |
| Home | B0H000001 | Vacuum Cleaner Smart | $159.99 | /ec/static/img/placeholder.png |
| Home | B0H000002 | Air Purifier | $129.50 | /ec/static/img/placeholder.png |
| Home | B0H000003 | Essential Oil Diffuser | $22.99 | /ec/static/img/placeholder.png |
| Home | B0H000004 | LED Desk Lamp | $35.99 | /ec/static/img/placeholder.png |

The plain `swarm submit` job correctly returned `pageContentBytes: 4569` with an empty `resultSet` (only a `url` field), confirming the page was fetched but no extraction was performed — exactly as documented.

---

### Execution Context

**Key Commands:**

```
./b4w.ps1 help
./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
./b4w.ps1 swarm query --sql @extract_products.sql --seed-file ./seed_urls.txt --refresh
./b4w.ps1 swarm submit http://localhost:18080/ec/dp/B0E000001 --refresh
./b4w.ps1 swarm status <task-id>
./b4w.ps1 swarm result <task-id>
./b4w.ps1 swarm list
./b4w.ps1 swarm list --clear
./b4w.ps1 swarm query http://localhost:18080/ec/dp/B0E000001 --sql @extract_products.sql --refresh --wait
./b4w.ps1 swarm query --sql @extract_products.sql --seed-file ./seed_urls.txt --refresh --wait
./b4w.ps1 swarm submit http://localhost:18080/ec/dp/B0E000001 --refresh --wait
./b4w.ps1 doctor --verbose
./b4w.ps1 doctor log pulsar --tail
./b4w.ps1 swarm close
```

**Workarounds Applied During Task:**

- **Major:** First batch submission without `--wait` caused all 11 parallel tasks to fail with H2 session closure race condition. Workaround: use `--wait` flag which appears to serialize or properly manage H2 session lifecycle.
- **Major:** `swarm list --clear` needed before recreating swarm to remove stale failed tasks (docs mention this but it's easy to miss).

---

---

## Issues Found (6 issues)

### Issue 1: H2 session race condition causes mass task failure without `--wait`

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```
./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
./b4w.ps1 swarm query --sql @query.sql --seed-file ./urls.txt --refresh
```
Submit 10+ URLs via seed file to swarm query without `--wait`. Most or all tasks fail with `statusCode: 417` / `lifecycleState: "failed (expectation failed)"`.

#### Expected Behavior

All tasks should complete successfully, regardless of whether `--wait` is used. `--wait` should only affect whether the CLI blocks, not whether the backend succeeds.

#### Actual Behavior

Without `--wait`, 8 out of 10 tasks failed immediately (within 1 second). With `--wait`, all 10 succeeded. The backend log reveals the root cause:
```
WARN a.p.p.a.c.s.AbstractBrowser4SQLContext - Session is already closed | #5/24
org.h2.jdbc.JdbcSQLException: The object is already closed [90007-197]
```

#### Root Cause Analysis

When swarm workers execute X-SQL queries in parallel, the H2 database session pool in `AbstractBrowser4SQLContext` is being closed prematurely. The `--wait` flag appears to alter the execution path (possibly serializing or keeping the session alive long enough). The `AbstractBrowser4SQLContext.getSession()` at line 158 is returning an already-closed H2 session.

#### Code Pointer

``browser4-agentic/.../AbstractBrowser4SQLContext.kt:158` — `getSession()` method returns closed session. Also `browser4-agentic/.../H2SessionFactory.kt:99` — `getSession()` in the factory.`

#### AI Suggested Improvement

- Ensure H2 session lifecycle is tied to the swarm session, not individual task completion — sessions should not be closed while queued/processing tasks remain
- Add a check in `getSession()` to create a new session if the existing one is closed, rather than returning a closed session
- Add integration test that submits 10+ parallel X-SQL tasks to swarm without `--wait` and verifies all complete

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Well-documented critical race condition with clear reproduction, root cause, and code pointers. The H2 session lifecycle must be tied to the swarm session, not individual task completion. Fixing this also resolves the core symptom behind Issue 4.

---

### Issue 2: Failed tasks report empty error message and `isDone: false`

**Severity:** High
**Category:** Reliability

#### Reproduction

Trigger any swarm task failure (e.g., Issue 1 above). Run `swarm status <failed-task-id>` and `swarm result <failed-task-id>`.

#### Expected Behavior

`swarm status` should show `isDone: true` for terminal states (failed). `message` or `error` should contain a human-readable error description.

#### Actual Behavior

```json
{
  "isDone": false,
  "lifecycleState": "failed (expectation failed)",
  "message": "",
  "statusCode": 417
}
```
And `swarm result` returns `"error": null` with an empty `resultSet`. The user sees no explanation of what failed. The actual error (`org.h2.jdbc.JdbcSQLException: The object is already closed`) is only visible in the backend log.

#### Root Cause Analysis

The swarm task status reporting has two bugs: (1) `isDone` is not set to `true` when `lifecycleState` transitions to a terminal `failed` state — the condition that sets `isDone` likely only triggers on `completed` lifecycle state. (2) The exception message from the H2 error is not propagated to the task's `message` or `error` fields.

#### Code Pointer

`Backend swarm task status endpoint — the logic mapping lifecycleState to isDone. Also `XSQLScrapeHyperlink.kt` or `AbstractScrapeHyperlink.kt` — error handling that fails to populate `message`/`error`.`

#### AI Suggested Improvement

- Set `isDone: true` for ALL terminal lifecycle states (failed, completed, cancelled), not just `completed`
- Propagate the root exception message to the `message` field when a task fails — even a truncated version is infinitely better than an empty string
- Consider adding an `errorCode` field (e.g., `H2_SESSION_CLOSED`) for programmatic error handling

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Distinct from Issue 1 — even after fixing the H2 race, error propagation needs to work. Two independent bugs: `isDone` incorrectly gated to `completed` only, and exception messages not plumbed to the `message` field. Both are straightforward fixes with clear code pointers.

---

### Issue 3: `swarm submit` vs `swarm query` naming confusion

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

Read the swarm documentation and try to understand when to use each command.

#### Expected Behavior

Command names should clearly signal their purpose. A new user should immediately understand the difference.

#### Actual Behavior

The distinction between `swarm submit` and `swarm query` is subtle:
- `swarm submit` — fetches pages, optionally with `--sql` (but docs say "prefer `swarm query`")
- `swarm query` — requires `--sql`, designed for structured extraction
- Both accept URLs, `--seed-file`, `--refresh`, `--wait`

The doc itself says: *"Prefer `swarm query` over `swarm submit --sql` for X-SQL extraction — it enforces `--sql` as required."* This implies `swarm submit` also accepts `--sql`, creating an overlapping API surface. A new user reading the help output sees both and doesn't know which to pick.

#### Root Cause Analysis

The two commands evolved separately rather than being designed as a coherent pair. `submit` was likely the original command, then `query` was added as a specialized variant. The docs acknowledge the overlap but don't resolve it.

#### Code Pointer

`N/A — design issue.`

#### AI Suggested Improvement

- Consider consolidating into a single `swarm submit` command where `--sql` is optional and clearly documented: without `--sql` = fetch only, with `--sql` = fetch + extract
- Or rename `swarm query` to `swarm extract` to better signal its purpose vs `swarm submit` (fetch-only)
- Add a "Quick comparison" table to `swarm --help` showing the two commands side by side

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The naming confusion is real and the docs acknowledge it ("Prefer `swarm query` over `swarm submit --sql`"), but consolidation into a single command risks breaking existing workflows. The suggestion to add a comparison table to `--help` is low-cost and should be done regardless. The consolidation/rename decision needs a design discussion weighing backward compatibility against simplification.

---

### Issue 4: `--wait` documentation doesn't mention its impact on reliability

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read the swarm reference doc section on `--wait`. It says: *"Block until all submitted jobs complete (polls every 2s, 5-minute timeout).*"

#### Expected Behavior

Documentation should mention if `--wait` changes execution behavior beyond just blocking the CLI. If it affects task scheduling or resource lifecycle, that should be documented.

#### Actual Behavior

The docs present `--wait` as purely a convenience flag ("block until done"). But in practice, using `--wait` vs not using it changed whether 0% or 100% of tasks succeeded (see Issue 1). This is either a bug or an undocumented behavioral difference.

#### Root Cause Analysis

The docs describe `--wait` as a client-side polling convenience, but it may trigger a different code path on the backend (e.g., the CLI keeping a connection open which prevents premature H2 session cleanup).

#### Code Pointer

``swarm.md` lines 62, 77, 91 — the `--wait` documentation sections.`

#### AI Suggested Improvement

- Fix the underlying race condition (Issue 1) so `--wait` is purely a convenience flag as documented
- If `--wait` genuinely changes backend behavior, document this explicitly: "Using `--wait` is recommended for batches of 5+ URLs to ensure proper resource lifecycle management"
- Add a warning in the docs: "For seed files with many URLs, use `--wait` to avoid task failures"

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] This is a documentation workaround for Issue 1, not a standalone concern. If Issue 1 is fixed (H2 session lifecycle tied to swarm session), `--wait` becomes purely a client-side convenience as documented, and the warning is unnecessary. Add the temporary warning only if Issue 1 cannot be fixed in the current cycle.

---

### Issue 5: Debug output leaks into user-facing CLI output

**Severity:** Low
**Category:** UX

#### Reproduction

```
./b4w.ps1 swarm query --sql @query.sql --seed-file ./urls.txt --refresh
```

#### Expected Behavior

Clean, structured output with task IDs and URLs.

#### Actual Behavior

The line `Finding browser4 root from "/home/vincent/workspace/Browser4-4.12"` appears in stdout before the actual task submission output. This looks like a debug log line that leaked into production output.

#### Root Cause Analysis

A `println` or `logger.info` statement in the CLI's startup/initialization path is writing to stdout instead of being gated behind a verbose/debug flag. The `--quiet` flag presumably suppresses it, but it shouldn't appear in default output mode.

#### Code Pointer

`CLI code that resolves the browser4 root directory — likely in the startup/init path before command dispatch.`

#### AI Suggested Improvement

- Gate the "Finding browser4 root" message behind `--verbose` flag or debug logging level
- Or move it to stderr so it doesn't pollute structured stdout output

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Straightforward logging hygiene fix. The root-resolution message should be gated behind `--verbose` or directed to stderr. A single-line change with no architectural impact.

---

### Issue 6: `pageContentBytes` returned as raw integer — not human-readable

**Severity:** Low
**Category:** UX

#### Reproduction

Run `swarm result <id>` for any completed task.

#### Expected Behavior

Page size shown in human-readable format (e.g., "4.5 KB") alongside or instead of raw bytes.

#### Actual Behavior

`"pageContentBytes": 4569` — the user must mentally divide by 1024 to understand the page size.

#### Root Cause Analysis

The result JSON serializes the raw byte count from the backend without any formatting layer.

#### Code Pointer

`Backend result serialization for swarm task results.`

#### AI Suggested Improvement

- Add a `pageContentSize` field with human-readable formatting (e.g., `"4.5 KiB"`)
- Or format it in the CLI's default (non-JSON) output mode while keeping raw bytes in `--json` mode

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Simple UX polish. Add a human-readable `pageContentSize` field (e.g., `"4.5 KiB"`) alongside the raw `pageContentBytes`. Low effort, clear user benefit, no breaking change.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: H2 session race condition causes mass task failure without `--wait`

```
./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4
./b4w.ps1 swarm query --sql @query.sql --seed-file ./urls.txt --refresh
```
Submit 10+ URLs via seed file to swarm query without `--wait`. Most or all tasks fail with `statusCode: 417` / `lifecycleState: "failed (expectation failed)"`.

#### Issue 2: Failed tasks report empty error message and `isDone: false`

Trigger any swarm task failure (e.g., Issue 1 above). Run `swarm status <failed-task-id>` and `swarm result <failed-task-id>`.

#### Issue 3: `swarm submit` vs `swarm query` naming confusion

Read the swarm documentation and try to understand when to use each command.

#### Issue 4: `--wait` documentation doesn't mention its impact on reliability

Read the swarm reference doc section on `--wait`. It says: *"Block until all submitted jobs complete (polls every 2s, 5-minute timeout).*"

#### Issue 5: Debug output leaks into user-facing CLI output

```
./b4w.ps1 swarm query --sql @query.sql --seed-file ./urls.txt --refresh
```

#### Issue 6: `pageContentBytes` returned as raw integer — not human-readable

Run `swarm result <id>` for any completed task.

