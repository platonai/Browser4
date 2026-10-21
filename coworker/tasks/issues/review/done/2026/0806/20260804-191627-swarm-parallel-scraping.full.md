All tasks complete. Now let me compile the evaluation report.

---

## A. Task Result

All 10 MockSite product URLs were successfully scraped using `swarm query` with X-SQL extraction. Each product yielded its **title**, **price text** (from the buybox), **image URL**, and **page URL**. A plain `swarm submit` job was also completed for comparison — it returned only the URL in the resultSet (no extracted fields), confirming the documented difference between the two submission methods.

**Extracted Products:**

| Category | Product ID | Title | Price |
|----------|-----------|-------|-------|
| Electronics | B0E000001 | 4K OLED TV 55 | $899.99 |
| Electronics | B0E000002 | Wireless Noise-Cancelling Headphones | $199.99 |
| Electronics | B0E000003 | Portable Bluetooth Speaker | $49.99 |
| Electronics | B0E000004 | Smartphone 128GB | $599.00 |
| Electronics | B0E000005 | USB-C Hub 7-in-1 | $29.95 |
| Electronics | B0E000006 | Wireless Mouse | $24.99 |
| Home | B0H000001 | Vacuum Cleaner Smart | $159.99 |
| Home | B0H000002 | Air Purifier | $129.50 |
| Home | B0H000003 | Essential Oil Diffuser | $22.99 |
| Home | B0H000004 | LED Desk Lamp | $35.99 |

---

## B. Execution Trace

**Commands used (chronologically):**
1. `goto "http://localhost:18080/ec/dp/B0E000001"` — Navigate to a product page for structure exploration
2. `htmlsnapshot` — Capture static HTML snapshot
3. `htmlsnapshot inspect` — Discover CSS selectors on the page
4. `htmlsnapshot get text "h1"`, `"#product-page h1"`, `".buybox"` — Trial extractions to verify selectors
5. `htmlsnapshot summary` — Get page structure overview
6. `htmlsnapshot get attr "#product-page img" src` — Verify image URL extraction
7. `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` — Create swarm session
8. `swarm list --clear` — Clear stale tasks from prior sessions
9. `swarm query --sql @.test-sessions/extract-query.sql --seed-file .test-sessions/seed-urls.txt --refresh` — Submit 10 extraction jobs
10. `swarm submit "http://localhost:18080/ec/dp/B0E000001" --refresh` — Submit plain scrape job
11. `swarm list` (repeated) — Poll job status
12. `swarm status <id>` — Check individual task status
13. `swarm result <id>` (×11) — Retrieve results from all completed jobs
14. `swarm close` — Close the swarm session

**Major decisions:**
- Used `#product-page` as the X-SQL scoping selector (the article wrapping the product detail)
- Used `DOM_FIRST_TEXT(DOM, 'h1')` for title, `DOM_FIRST_TEXT(DOM, '.buybox')` for price, `DOM_FIRST_ATTR(DOM, 'img', 'src')` for image
- Required one full debug-and-resubmit cycle because the initial query used `DOM_ABS_SRC(DOM_FIRST_IMG(...))` which caused silent 417 failures

**Workarounds required:**
- Fixed a compilation error in `cli/browser4-cli/src/main.rs:16656` (malformed `let` inside constructor arguments)
- Replaced `DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img'))` with `DOM_FIRST_ATTR(DOM, 'img', 'src')` after step-by-step X-SQL debugging
- Cleared stale swarm tasks and recreated the swarm session between attempts
- Used `htmlsnapshot query` (direct, non-swarm) to debug X-SQL syntax before resubmitting to swarm
- Waited out 2 jobs stuck in "queued" for ~2.5 minutes

---

## C & D. Issues Found and Overall Assessment

```json
{
  "issues": [
    {
      "title": "Compilation error: `let` statement inside `CliError` constructor",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "Modify any Rust source file in cli/browser4-cli/src/, then run any `./b4w.ps1` command that triggers a rebuild.",
      "expected": "Clean compilation with no errors.",
      "actual": "Compilation fails with 'expected expression, found `let` statement' at main.rs:16656. The `let bin = cli_binary_name();` is placed inside the `CliError(ExitCode::Session, ...)` constructor arguments list, which is not valid Rust syntax.",
      "rootCause": "At main.rs:16654-16661, a `let` binding is placed inside a struct constructor argument list. The `let` statement must be extracted to a separate line before the `CliError(...)` construction. This appears to be a merge/rebase artifact or incomplete refactor.",
      "codePointer": "cli/browser4-cli/src/main.rs:16654-16661 — `ensure_server_running` or surrounding function",
      "suggestion": "- Move `let bin = cli_binary_name();` to a separate line before the `CliError(...)` construction\n- Add a CI check that runs `cargo check` on every commit to catch compilation errors early\n- Consider adding a pre-commit hook that runs `cargo check`"
    },
    {
      "title": "X-SQL `DOM_ABS_SRC(DOM_FIRST_IMG(...))` causes silent 417 failure with misleading error message",
      "severity": "High",
      "category": "Product",
      "reproduction": "Create an X-SQL query with `DOM_ABS_SRC(DOM_FIRST_IMG(DOM, 'img'))` and run it via `htmlsnapshot query` or `swarm query`.",
      "expected": "Either a clear error message explaining that `DOM_FIRST_IMG` returns a string, not a DOM element (and thus can't be passed to `DOM_ABS_SRC`), or the function should work transparently.",
      "actual": "Returns statusCode 417 with message: 'The scrape session closed before the query could execute.' The error message is misleading — the session didn't close, the X-SQL function type mismatch caused the failure. The `message` field in the status response is empty.",
      "rootCause": "`DOM_FIRST_IMG(DOM, selector)` returns a `String` (the src attribute value). `DOM_ABS_SRC(DOM)` expects a `ValueDom` (a DOM element node). When passed a string instead of a DOM element, the X-SQL function evaluator throws an exception that isn't caught with a helpful error message. The generic 417 handler assumes a session-closure scenario.",
      "codePointer": "browser4-core/ — X-SQL function evaluator, likely in DomFunctions or the X-SQL query execution path",
      "suggestion": "- Add type validation in the X-SQL function binder: if `DOM_ABS_SRC` receives a non-DOM argument, produce a clear error like 'DOM_ABS_SRC expects a DOM element, but DOM_FIRST_IMG returns a string (the src attribute). Use DOM_FIRST_ATTR(DOM, 'img', 'src') instead.'\n- Make `DOM_ABS_SRC` accept both DOM elements and string URLs (auto-detect and resolve if relative, passthrough if absolute)\n- Improve the 417 error message to include the actual X-SQL evaluation error rather than always assuming session closure\n- Add a function compatibility table to the X-SQL documentation showing which functions accept/return DOM elements vs. strings"
    },
    {
      "title": "417 X-SQL errors have empty `message` field in status response",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "Submit an X-SQL query with invalid function composition via `swarm query` and check `swarm status <id>`.",
      "expected": "The `message` field should contain a diagnostic error explaining what went wrong (e.g., 'Type mismatch: DOM_ABS_SRC expects ValueDom, got String').",
      "actual": "`message` field is an empty string in the JSON response. The CLI prints a generic 'scrape session closed' message, but the raw response has no diagnostic information.",
      "rootCause": "The X-SQL evaluation exception is either not being caught and attached to the task status message, or the exception message is being swallowed/discarded before the status response is built. Investigation needed in the X-SQL evaluation pipeline and the task status reporting code.",
      "codePointer": "browser4-rest/ — MCPToolController or the swarm task execution service; browser4-core/ — X-SQL query evaluator",
      "suggestion": "- Capture the X-SQL evaluation exception message and include it in the task status `message` field\n- Add structured error codes to distinguish X-SQL syntax errors, function type errors, CSS selector mismatches, and session failures\n- Display the backend error message in the CLI output instead of the generic fallback"
    },
    {
      "title": "`isDone` field inconsistent between `swarm status` and `swarm list` for failed tasks",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "Submit an X-SQL query that will fail (e.g., with invalid selectors), then compare `swarm status <id>` and `swarm list`.",
      "expected": "Both commands should agree on whether the task is done. A task with statusCode 417 should show `isDone: true`.",
      "actual": "`swarm status` showed `isDone: false` for tasks that `swarm list` displayed as 'failed (expectation failed)' with a finish time. The lifecycleState field correctly showed 'failed', but isDone remained false, creating ambiguity.",
      "rootCause": "The `isDone` flag appears to be gated on statusCode 200 only, rather than on task completion (any terminal state). Tasks that finish with error codes (417, 4xx, 5xx) may not have `isDone` set to true.",
      "codePointer": "browser4-rest/ — task status response builder, likely in a SwarmTaskService or TaskStatusController",
      "suggestion": "- Set `isDone: true` for any task that has reached a terminal state (completed, failed, cancelled, expired), not just successful ones\n- Add a `terminalState` boolean field separate from `isDone` for clarity, or rename `isDone` to `isTerminal`"
    },
    {
      "title": "Jobs can get stuck in 'queued' state while newer jobs complete",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "Submit 11 jobs (10 swarm-query + 1 swarm-submit) to a swarm session with --max-browser-contexts 2. Observe that ~2 jobs remain 'queued' for 2+ minutes while newer jobs (submitted in the same batch) complete.",
      "expected": "Jobs should be processed in FIFO order, or at least all jobs should be picked up promptly. No job should remain queued for minutes while workers are actively completing other jobs.",
      "actual": "The swarm-submit job (eeab73ac) and one swarm-query job (7a1d9617) remained in 'queued' (statusCode 201) for approximately 2.5 minutes. During this time, 8 other jobs submitted in the same batch completed successfully. The stuck jobs eventually completed after ~2.5 minutes.",
      "rootCause": "Possible causes: (a) the worker pool uses non-FIFO dequeuing (e.g., LIFO or random selection from the queue), (b) swarm-submit and swarm-query jobs go to different internal queues with separate worker allocation, (c) a race condition in the job dispatcher where some submissions aren't properly enqueued. Investigation needed in the swarm job dispatcher.",
      "codePointer": "browser4-rest/ — swarm job dispatcher / worker pool implementation",
      "suggestion": "- Audit the job dispatcher for dequeuing fairness — ensure FIFO ordering or at least bounded wait time\n- Add a 'stuck job' detector: if a job has been queued for >60s while other jobs complete, log a warning and prioritize it\n- Consider using a single unified queue with tagged job types rather than separate queues"
    },
    {
      "title": "ResultSet can be empty on first fetch but populated on retry (race condition)",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "Call `swarm result <id>` immediately after the job shows as 'completed' in `swarm list`. In some cases, the resultSet is empty. Fetch the same result again after a delay and it may contain data.",
      "expected": "Once a task shows as 'completed' (with a finish time), the resultSet should be atomically available. A fetch should never return an empty resultSet for a successfully completed X-SQL query.",
      "actual": "B0H000003 (7ff933fc) returned an empty resultSet on first fetch via `swarm result` (after swarm list showed it as completed). On a subsequent fetch several minutes later, the resultSet contained the expected data (title, price_text, image_url, page_url).",
      "rootCause": "The resultSet write and the task status update (marking 'completed') are likely not atomic. The X-SQL result data may be written asynchronously after the status is updated, creating a window where the task appears done but the data isn't available yet.",
      "codePointer": "browser4-rest/ — swarm task completion handler, where resultSet is persisted and status is updated",
      "suggestion": "- Make resultSet persistence atomic with the status transition to 'completed' — either write the result before marking done, or use a transaction\n- Add a `resultSetReady` boolean flag separate from `isDone` to indicate when data is available\n- Document this behavior in the swarm.md reference: 'After a task completes, wait 1-2 seconds before fetching results to ensure data is fully persisted'"
    },
    {
      "title": "Stale swarm task warnings on every `swarm create` are confusing for new users",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run `swarm create` after having used swarm in a previous session (or after clearing tasks incompletely). The warning appears: 'Note: N swarm task(s) from prior sessions are still tracked.'",
      "expected": "Either auto-clear stale tasks on session creation, or provide more actionable guidance. The `--clear-stale` flag should be discoverable from the warning message.",
      "actual": "The warning message tells the user what might happen ('jobs get stuck') and suggests `swarm list --clear` or `swarm create --clear-stale`. However, new users may not understand what 'tracked' means or why stale tasks persist across sessions. The warning is also easy to miss among other CLI output.",
      "rootCause": "Swarm task tracking persists across CLI sessions (likely stored in the backend's task store). When a new swarm session is created, it detects tasks from prior sessions and warns. This is a valid concern but the UX could be more helpful.",
      "codePointer": "cli/browser4-cli/src/ — swarm create command handler",
      "suggestion": "- Auto-clear stale tasks when creating a new swarm session (add `--no-clear-stale` for the rare case where this isn't desired)\n- If auto-clear is too aggressive, make the warning more prominent (e.g., colored/bold) and offer an interactive 'Clear now? [Y/n]' prompt even in non-TTY mode via a --yes flag\n- Add a note in the Quick Start section of swarm.md about this behavior"
    },
    {
      "title": "`swarm list` output reorders on each invocation making visual tracking difficult",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run `swarm list` multiple times. Observe that the task display order changes between invocations.",
      "expected": "Tasks should display in a consistent order (e.g., sorted by submission time ascending or descending, or by task ID) so users can visually track job progress across refreshes.",
      "actual": "The task list order changes on each invocation. Tasks jump around in the display, making it hard to spot which ones changed status. The user must scan by task ID rather than position.",
      "rootCause": "The backend likely returns tasks in an undefined order (HashMap iteration order, or database query without ORDER BY). The CLI displays them as received without sorting.",
      "codePointer": "cli/browser4-cli/src/ — swarm list display formatting; browser4-rest/ — swarm task list endpoint",
      "suggestion": "- Sort tasks by submission time (earliest first) or by task ID before display\n- Add a `--sort` flag to allow sorting by different columns (status, duration, URL)\n- Consider grouping by status: queued first, then processing, then completed/failed"
    },
    {
      "title": "Parallel `./b4w.ps1` invocations cause cargo build file lock contention",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run two `./b4w.ps1` commands in quick succession (e.g., in parallel bash jobs). The second command will show: 'Blocking waiting for file lock on build directory'.",
      "expected": "Running independent CLI commands in parallel should not cause build contention, or the build should be smart enough to skip when the binary is already up-to-date.",
      "actual": "Each `./b4w.ps1` invocation checks if Rust sources changed and triggers `cargo build` if needed. When multiple commands run concurrently, one acquires the cargo build lock and the others block. This adds significant latency (5-15s per blocked command).",
      "rootCause": "`b4w.ps1` unconditionally (or too broadly) checks for source changes on each invocation. Cargo's build directory lock serializes these checks. Consider making the change detection more granular or caching the last build timestamp.",
      "codePointer": "b4w.ps1 — build check logic",
      "suggestion": "- Cache a hash of source files after a successful build and skip rebuild when sources haven't changed\n- Use `cargo check` instead of `cargo build` for the change detection (faster)\n- Add a `--no-build` flag to skip the build check entirely when the user knows the binary is current"
    },
    {
      "title": "X-SQL documentation doesn't clearly document which functions return DOM elements vs. strings",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Read x-sql.md and x-sql-dom-functions.md. Try to determine whether `DOM_FIRST_IMG` returns a DOM element or a string. The function index shows 'Returns: String' for `DOM_FIRST_IMG` and 'Returns: String' for `DOM_ABS_SRC` — but `DOM_ABS_SRC` actually expects a DOM node input, not a string.",
      "expected": "The function reference should clearly distinguish between: (a) functions that take/return DOM elements (ValueDom), and (b) functions that take/return scalar values (strings, floats). The 'Returns' column should indicate the actual return type category.",
      "actual": "The X-SQL function index shows return types like 'String', 'ValueDom', 'ValueArray', etc. but these are SQL-level type names that don't clearly communicate to users whether the result can be composed with other functions. `DOM_ABS_SRC` shows 'Returns: String' but it requires a DOM element input — this input type requirement is undocumented in the index.",
      "rootCause": "The documentation lists SQL return types but doesn't document function parameter types or composability constraints. Users must experiment or read source code to understand which functions can be chained together.",
      "codePointer": "skills/browser4-cli/references/x-sql.md and related files",
      "suggestion": "- Add a 'Parameter Type' column to the function index showing what each function expects as input\n- Add a 'Composable With' section for each function listing which other functions' outputs can be passed to it\n- Create a visual diagram or table showing the function composition graph (DOM → scalar, scalar → scalar, DOM → DOM)\n- Add explicit examples of common composition mistakes (like DOM_ABS_SRC + DOM_FIRST_IMG) and their correct alternatives"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — All 10 product URLs were scraped with structured data extraction (title, price, image, URL). The swarm-submit comparison job completed successfully. The swarm session was properly closed. One full debug-and-resubmit cycle was needed due to an X-SQL function composition error.",
    "successRate": "85% — 10/10 extraction tasks eventually succeeded after fixing the X-SQL query. However, the first attempt had 8/10 failures due to a silent X-SQL type error, requiring a complete debug-and-resubmit cycle. 2/11 jobs were stuck in 'queued' for ~2.5 minutes. A compilation error had to be fixed before the CLI would work.",
    "issuesFound": 10,
    "majorBlockers": "Compilation error in main.rs (Critical) — the CLI cannot be used after any source change without fixing this bug. X-SQL function composition errors produce silent 417 failures with no diagnostic message (High) — without step-by-step debugging via htmlsnapshot query, a user would have no way to determine why their query failed.",
    "mostConfusingAspects": "1) The 417 'Expectation Failed' error with 'scrape session closed' message is completely misleading — the real problem was a type mismatch in X-SQL function arguments. 2) The `isDone` field being false for failed tasks contradicts the task appearing as 'failed' with a finish time. 3) Tasks completing out of order with some stuck in 'queued' while newer tasks finish is counterintuitive. 4) The distinction between DOM elements and strings in X-SQL function composition is not obvious from documentation.",
    "mostValuableImprovements": "1) Fix the compilation error (one-line fix, already applied). 2) Add clear error messages to X-SQL function evaluation failures — include the actual exception in the task status message. 3) Make `DOM_ABS_SRC` accept both DOM elements and string URLs (auto-detect). 4) Fix the job dispatcher to ensure FIFO queuing. 5) Add a function composability guide to the X-SQL documentation.",
    "usabilityRating": 6
  }
}
```
