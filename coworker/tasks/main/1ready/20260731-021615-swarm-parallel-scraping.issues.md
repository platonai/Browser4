# Issues: swarm-parallel-scraping

> **Source:** `20260731-021615-swarm-parallel-scraping.full.md` | **Date:** 20260731-021615 | **Mode:** dev

## Scenario Background

### Task

**Task: Swarm-based product data extraction from MockSite**

The task was completed successfully:

1. **Swarm session created** with `--display-mode HEADLESS`, `--max-browser-contexts 2`, `--max-open-tabs 4`. Session ID: `SWARM`.

2. **Seed file created** with 10 product URLs (6 Electronics: B0E000001–B0E000006; 4 Home: B0H000001–B0H000004).

3. **X-SQL query** written to extract `DOM_BASE_URI(DOM) AS url`, `DOM_FIRST_TEXT(DOM, '#productTitle') AS title`, `DOM_FIRST_TEXT(DOM, '#product-price') AS price`, and `DOM_FIRST_ATTR(DOM, '#product-image', 'src') AS image_url` from each product page.

4. **10 X-SQL extraction jobs** submitted via `swarm query --sql @file --seed-file --refresh`. All 10 completed successfully, returning structured data for every product.

5. **1 plain scrape job** submitted via `swarm submit` (no `--sql`). Completed, returning page confirmation (4626 bytes fetched) but no extracted fields — demonstrating the difference between the two submission methods.

6. **All jobs polled and results retrieved.** All 10 X-SQL jobs returned title, price, and image_url for their respective products. The plain scrape job returned only the URL.

7. **Task history listed** via `swarm list` — 22 total tracked tasks (11 new + 11 from prior sessions).

8. **Swarm session closed** — resources released, confirmed by `list` showing no active sessions.

**Extracted data summary:**

| Product ID | Title | Price |
|---|---|---|
| B0E000001 | 4K OLED TV 55 | $899.99 |
| B0E000002 | Wireless Noise-Cancelling Headphones | $199.99 |
| B0E000003 | Portable Bluetooth Speaker | $49.99 |
| B0E000004 | Smartphone 128GB | $599.00 |
| B0E000005 | USB-C Hub 7-in-1 | $29.95 |
| B0E000006 | Wireless Mouse | $24.99 |
| B0H000001 | Vacuum Cleaner Smart | $159.99 |
| B0H000002 | Air Purifier | $129.50 |
| B0H000003 | Essential Oil Diffuser | $22.99 |
| B0H000004 | LED Desk Lamp | $35.99 |

---

### Execution Context

**Key Commands:**

1. `curl` — Verified MockSite running on localhost:18080
2. `curl` — Explored product page HTML structure (B0E000001, B0H000001) to identify CSS selectors
3. `pwsh ... b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` — Created swarm session
4. Created seed file (`.test-sessions/seed-urls.txt`) and X-SQL query (`.test-sessions/extract-products.sql`) via bash heredocs
5. `pwsh ... b4w.ps1 swarm query --sql @file --seed-file --refresh` — Submitted 10 X-SQL extraction jobs
6. `pwsh ... b4w.ps1 swarm submit <url> --refresh` — Submitted 1 plain scrape job
7. `pwsh ... b4w.ps1 swarm list` — Listed all tracked tasks
8. `pwsh ... b4w.ps1 swarm status <id>` (×11) — Polled each job until `isDone: true`
9. `pwsh ... b4w.ps1 swarm result <id>` (×11) — Retrieved results for all jobs
10. `pwsh ... b4w.ps1 swarm close` — Closed swarm session (timed out at 30s but succeeded)
11. `pwsh ... b4w.ps1 list` — Verified no active sessions remain

**Important decisions:**
- Used `pwsh -NoProfile -ExecutionPolicy Bypass -File` invocation because `./b4w.sh` failed to resolve the PowerShell path from Git Bash (Unix-style `/d/...` path not recognized by PowerShell)
- Used individual argument quoting for flags like `"--sql"`, `"--seed-file"` to prevent PowerShell parameter binding issues
- Used `:root` as the DOM_LOAD_AND_SELECT scope selector since product pages are detail pages (single product), not list pages
- Used `DOM_FIRST_ATTR` instead of `DOM_ABS_SRC` for image URL — returned relative paths

**Workarounds required:**
- Task instructions specified `$(./b4w.ps1)` invocation but this doesn't work in bash — used direct `pwsh` invocation instead
- `./b4w.sh` wrapper broken in Git Bash due to path translation — bypassed entirely
- `swarm close` hung beyond 30s timeout; retried with `close-all` (returned 0 sessions), then `swarm close` again (returned "Session required"), confirming the first invocation actually succeeded
- Had to poll individual task statuses because `swarm list` showed stale "queued" labels even after jobs completed

---

---

## Issues Found (9 issues)

### Issue 1: b4w.sh wrapper fails in Git Bash due to Unix/Windows path mismatch

**Severity:** High
**Category:** Reliability

#### Reproduction

Run `./b4w.sh help` from Git Bash in the repo root.

#### Expected Behavior

Browser4 CLI help output.

#### Actual Behavior

Error: "The term '/d/workspace/Browser4/Browser4-4.12/b4w.ps1' is not recognized as a name of a cmdlet, function, script file, or executable program." PowerShell cannot resolve Unix-style paths produced by `pwd` in bash.

#### Root Cause Analysis

`b4w.sh` line 37 uses `SCRIPT_DIR="$(cd "$(dirname "$0")" && pwd)"` which produces a Unix-style path like `/d/workspace/Browser4/...`. This path is passed to `pwsh -File` which cannot resolve it. The fix needs to translate the path to Windows format (e.g., `D:/workspace/...` or `D:\workspace\...`) before passing to pwsh.

#### Code Pointer

`b4w.sh:37 — SCRIPT_DIR assignment needs cygpath/mountpoint translation for Git Bash on Windows`

#### AI Suggested Improvement

- On Git Bash, use `cygpath -w "$SCRIPT_DIR"` or `cmd //c cd` to produce a Windows-compatible path before passing to pwsh
- Alternatively, use `pwsh -Command` with a working-directory parameter instead of resolving the script path
- Document that Git Bash users should use `pwsh -File` directly as a workaround

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Legitimate path-translation bug in the Git Bash wrapper — `pwd` produces `/d/workspace/...` which pwsh can't resolve. A `cygpath -w` or `cmd //c cd` translation is the correct fix.

---

### Issue 2: swarm close command hangs indefinitely

**Severity:** High
**Category:** Reliability

#### Reproduction

Run `swarm close` after a swarm session with completed jobs.

#### Expected Behavior

Quick confirmation that the swarm session was closed and resources released.

#### Actual Behavior

Command exceeded 30s timeout. On retry, `swarm close` returned 'Session required' error (session already closed). The initial close succeeded but produced no visible output before the timeout.

#### Root Cause Analysis

The swarm close operation may be waiting for browser context cleanup or worker thread termination. The HTTP request likely completed successfully but the CLI output was delayed or lost. The error message on retry ('Session required') is misleading — it should say 'No active swarm session' or 'Session already closed'.

#### Code Pointer

`browser4-rest/ — SwarmController or equivalent: close endpoint cleanup logic; cli/browser4-cli — swarm close command output handling`

#### AI Suggested Improvement

- Add a timeout for browser context cleanup during close (e.g., force-kill after 10s)
- Ensure close response is sent immediately after session invalidation, even if browser cleanup continues in background
- Return a distinct error message for "session already closed" instead of the generic 'Session required'
- Consider adding a `--force` flag to skip graceful shutdown

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Root cause analysis is plausible (cleanup hangs waiting for browser context teardown) but speculative — needs confirmation. The misleading "Session required" error on retry is independently a clear bug. Add a cleanup timeout and a distinct "already closed" error message; the `--force` flag suggestion is worth including.

---

### Issue 3: Task invocation syntax `$(./b4w.ps1)` is ambiguous/incorrect in bash

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read the task instructions which say to invoke as `$(./b4w.ps1) <command>`.

#### Expected Behavior

A working bash command.

#### Actual Behavior

`$(./b4w.ps1)` is bash command-substitution syntax — it tries to execute b4w.ps1 as a binary and substitute its stdout. This fails because (a) PowerShell scripts aren't bash-executable, and (b) command substitution misinterprets the invocation intent.

#### Root Cause Analysis

The `$(...)` notation was probably intended as a placeholder meaning "substitute the appropriate wrapper" but bash interprets it as literal command substitution syntax.

#### AI Suggested Improvement

- In task instructions, use a bash-compatible pattern like `./b4w.sh` (Git Bash) or `pwsh -File b4w.ps1` (PowerShell)
- Use angle-bracket notation like `<wrapper> <command>` to clearly indicate substitution without bash ambiguity

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The `$(...)` syntax is bash command substitution and produces a confusing failure. This is a pure documentation fix — replace with `<wrapper>` angle-bracket notation or reference the actual `./b4w.sh` / `pwsh -File b4w.ps1` commands. Low effort, high clarity payoff.

---

### Issue 4: swarm list shows stale status labels after jobs complete

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Submit swarm jobs, wait a few seconds, run `swarm list`.

#### Expected Behavior

Status column reflects actual job state (queued → processing → completed).

#### Actual Behavior

`swarm list` showed all 11 new jobs as 'queued' even though `swarm status` showed 8 of them as completed (`isDone: true`). The list only updated to 'completed' after all jobs finished.

#### Root Cause Analysis

`swarm list` appears to cache or batch its status updates, or the live backend query for each tracked task isn't triggered on every invocation. Individual `swarm status` calls return the correct live state immediately.

#### Code Pointer

`cli/browser4-cli/src/ — swarm list command implementation; may need to force a backend status refresh per task`

#### AI Suggested Improvement

- Make `swarm list` perform a live backend query for every tracked task on each invocation (as documented: "queries the backend for live status")
- Or add a `--refresh` flag to `swarm list` to force re-query
- The current behavior contradicts the documentation which says it queries live status on every invocation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Behavior contradicts the documented claim that "swarm list queries the backend for live status on each invocation." Individual `swarm status` calls return live state but `swarm list` appears to cache. Needs investigation: is this a deliberate batch vs. per-item querying shortcut, or an unintended caching bug?

---

### Issue 5: DOM_FIRST_ATTR returns relative image URLs; no obvious way to get absolute URLs from docs

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Use `DOM_FIRST_ATTR(DOM, '#product-image', 'src')` in an X-SQL query.

#### Expected Behavior

Absolute image URL or clear documentation on how to get one.

#### Actual Behavior

Returns `/ec/static/img/placeholder.png` (relative path). `DOM_ABS_SRC` exists but is not prominently documented in the quick-start patterns or swarm examples.

#### Root Cause Analysis

The X-SQL reference documents `DOM_ABS_SRC` in the function index table but the swarm.md quick-start examples only show `DOM_FIRST_SRC` or `DOM_FIRST_ATTR`. Users who copy the examples get relative URLs.

#### Code Pointer

`skills/browser4-cli/references/swarm.md — quick-start examples should use DOM_ABS_SRC or note the distinction`

#### AI Suggested Improvement

- Update swarm.md and x-sql.md quick-start examples to use `DOM_ABS_SRC` or `DOM_FIRST_ATTR(... , 'src')` with a note about absolute vs relative
- Add a tip in the results section that relative URLs can be resolved by prefixing `DOM_BASE_URI(DOM)`
- Consider adding a `DOM_ABS_ATTR` function for consistency

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Documentation gap — the quick-start examples use `DOM_FIRST_SRC`/`DOM_FIRST_ATTR` (relative URLs) but `DOM_ABS_SRC` exists and is in the function index. Updating swarm.md examples to use the absolute variants (or add a prominent note) is a low-effort doc fix. The `DOM_ABS_ATTR` function suggestion is a nice-to-have for consistency but not required for this fix.

---

### Issue 6: close-all does not close the SWARM session

**Severity:** Low
**Category:** UX

#### Reproduction

After a swarm session, run `close-all`.

#### Expected Behavior

All sessions including SWARM are closed.

#### Actual Behavior

"Closed 0 session(s)" — the SWARM session is not counted or closed by `close-all`. The user must use `swarm close` specifically.

#### Root Cause Analysis

The swarm session uses a special session ID (`SWARM`) that is managed separately from named/default sessions tracked by `close-all`.

#### Code Pointer

`browser4-rest/ — session management: SWARM session should be included in close-all scope, or close-all help should document this exclusion`

#### AI Suggested Improvement

- Include the SWARM session in `close-all` enumeration
- Or document in `close-all` help that swarm sessions require `swarm close`
- Or add a dedicated `close-all --include-swarm` flag

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The "Closed 0 session(s)" output when a SWARM session exists is misleading UX regardless of whether the exclusion is intentional. Minimum fix: document the exclusion in `close-all` help text. Better fix: include SWARM in the enumeration. Related to Issues 2 and 7 (all touch swarm session lifecycle management).

---

### Issue 7: swarm create warns about stale tasks but offers no guided resolution

**Severity:** Low
**Category:** UX

#### Reproduction

Run `swarm create` when stale tasks from prior sessions exist.

#### Expected Behavior

A clear prompt or option to clean stale tasks.

#### Actual Behavior

Warning: '11 swarm task(s) from prior sessions are still tracked.' followed by advice to run `swarm list --clear` or `swarm create --clear-stale`. This requires the user to abort, clear, and recreate — a multi-step recovery.

#### Root Cause Analysis

Stale task tracking persists across sessions. The create command detects this but doesn't offer an interactive resolution.

#### Code Pointer

`cli/browser4-cli/src/ — swarm create command: should support interactive prompt or auto-clean`

#### AI Suggested Improvement

- Offer an interactive prompt: 'Clear 11 stale tasks? [Y/n]' during `swarm create`
- Or auto-clear stale tasks when creating a new swarm (with a note)
- Add the warning to `swarm query` and `swarm submit` as well, since new users may not run `swarm create` separately

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Good UX suggestion — the multi-step "abort, clear, recreate" flow is friction that an interactive `[Y/n]` prompt would eliminate. Related to Issue 2 (swarm lifecycle). The suggestion to add the stale-task warning to `swarm query` and `swarm submit` as well is solid defensive UX.

---

### Issue 8: CLI/backend version mismatch warning on every command

**Severity:** Low
**Category:** UX

#### Reproduction

Run `status` from a dev build.

#### Expected Behavior

Clean status output or a suppressed version mismatch for dev builds.

#### Actual Behavior

Warning: 'CLI is 4.12.2 but running backend is 4.12.2-SNAPSHOT. The CLI and backend were built from different versions of the source tree.' This appears on every `swarm status` output, polluting JSON parsing.

#### Root Cause Analysis

The dev-mode CLI auto-builds from source producing a release version while the backend runs from a SNAPSHOT JAR. The version check compares literal strings and treats the SNAPSHOT suffix as a mismatch.

#### Code Pointer

`cli/browser4-cli/src/ — version check logic: should normalize SNAPSHOT suffixes or suppress mismatch for local builds`

#### AI Suggested Improvement

- Treat X.Y.Z matching X.Y.Z-SNAPSHOT as compatible (ignore the -SNAPSHOT suffix)
- Or suppress the warning when running from a dev build (detected via build profile or env var)
- Or print the warning on stderr only, so `--json` output is not polluted

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] SNAPSHOT suffix comparison is overly strict for dev builds — X.Y.Z should be treated as compatible with X.Y.Z-SNAPSHOT. Related to Issue 9 (both concern output quality): fixing the comparison here eliminates the warning at the source, while Issue 9 covers the fallback of routing remaining diagnostics to stderr.

---

### Issue 9: No --json support on swarm status/result for machine-parsable output

**Severity:** Low
**Category:** UX

#### Reproduction

Run `swarm status <id> --json` or `swarm result <id> --json`.

#### Expected Behavior

Clean JSON output without the version-mismatch warning and other human-readable text mixed in.

#### Actual Behavior

`swarm status` already outputs JSON by default, but it includes the version-mismatch warning on stdout (not stderr), which breaks JSON parsing. `--json` flag doesn't suppress these warnings.

#### Root Cause Analysis

Version mismatch warning is printed to stdout alongside JSON output rather than stderr.

#### Code Pointer

`cli/browser4-cli/src/ — version check warning should route to stderr`

#### AI Suggested Improvement

- Route all warnings, tips, and diagnostic messages to stderr, not stdout
- Ensure `--json` mode suppresses all non-JSON output on stdout
- Add a `--quiet` mode that suppresses even stderr diagnostics

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Separate concern from Issue 8 — even after fixing the SNAPSHOT comparison, any future warning/diagnostic mixed into stdout will break JSON consumers. The fix is orthogonal: route all diagnostics to stderr and make `--json` mode suppress non-JSON stdout. Complements Issue 8 rather than duplicating it.

---

## Overall Assessment

**Completion Status:** Successful — all task steps completed. 10 X-SQL extraction jobs and 1 plain scrape job all completed. Results retrieved for all products (10 Electronics and Home products). Swarm session closed.

**Success Rate:** 90% — core extraction workflow worked perfectly. 10/10 extraction jobs and 1/1 scrape job succeeded. The only partial failure was `swarm close` timeout (session actually closed, just output not captured).

**Issues Found:** 9

**Major Blockers:** None for the core task. The b4w.sh wrapper failure in Git Bash required using direct pwsh invocation as a workaround, but this did not block progress. The swarm close timeout is a reliability concern but the session closed successfully.

**Most Confusing Aspects:** 1) The `$(./b4w.ps1)` invocation syntax in task instructions is ambiguous in bash. 2) The `b4w.sh` wrapper silently fails without a clear error about path translation. 3) `swarm list` status labels lag behind actual job state. 4) Stale task accumulation from prior sessions creates noise for first-time users.

**Most Valuable Improvements:** 1) Fix b4w.sh path translation for Git Bash on Windows (critical — blocks the documented workflow). 2) Make swarm list show live status on every invocation as documented. 3) Reduce swarm close latency or add graceful timeout. 4) Route all warnings to stderr for JSON-safe stdout. 5) Auto-clean or interactively prompt to clear stale swarm tasks on create.

**Usability Rating:** 6/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: b4w.sh wrapper fails in Git Bash due to Unix/Windows path mismatch

Run `./b4w.sh help` from Git Bash in the repo root.

#### Issue 2: swarm close command hangs indefinitely

Run `swarm close` after a swarm session with completed jobs.

#### Issue 3: Task invocation syntax `$(./b4w.ps1)` is ambiguous/incorrect in bash

Read the task instructions which say to invoke as `$(./b4w.ps1) <command>`.

#### Issue 4: swarm list shows stale status labels after jobs complete

Submit swarm jobs, wait a few seconds, run `swarm list`.

#### Issue 5: DOM_FIRST_ATTR returns relative image URLs; no obvious way to get absolute URLs from docs

Use `DOM_FIRST_ATTR(DOM, '#product-image', 'src')` in an X-SQL query.

#### Issue 6: close-all does not close the SWARM session

After a swarm session, run `close-all`.

#### Issue 7: swarm create warns about stale tasks but offers no guided resolution

Run `swarm create` when stale tasks from prior sessions exist.

#### Issue 8: CLI/backend version mismatch warning on every command

Run `status` from a dev build.

#### Issue 9: No --json support on swarm status/result for machine-parsable output

Run `swarm status <id> --json` or `swarm result <id> --json`.

