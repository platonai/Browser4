---

## A. Task Result

The task was **partially completed** with significant workarounds.

**Completed:**
1. ✅ Extracted 10 links from `http://books.toscrape.com/` matching `a[href*=catalogue]` with `.*catalogue.*` regex filter — results in table, CSV (`crawl-results.csv`), and JSON (`crawl-results.json`)
2. ✅ Created seed file with 3 book detail URLs (`seed-urls.txt`)
3. ✅ Seed-file crawl at depth 0 successfully found 3 pages (with `--refresh` workaround)
4. ✅ Crawl task list displayed

**Failed:**
- ❌ `crawl` command with depth 1 (link discovery mode) — perpetually stuck in "pending", never processes

---

## B. Execution Trace

| Step | Command | Result |
|------|---------|--------|
| Help | `cargo run ... -- --help` | Works, good output |
| Crawl depth 1 (attempt 1) | `crawl "http://books.toscrape.com/" -d 1 -ol "a[href*=catalogue]" -olp ".*catalogue.*" -tl 10 --format table` | Stuck 144s, returned "0 pages found" |
| Crawl depth 1 (attempt 2) | Same with HTTPS | Timed out after 600s |
| Diagnose | Inspected server log, found 5 stale pending tasks from prior sessions | Queue blocked |
| Workaround | Cleared `async-tasks.json`, used `kill-all` | Fresh state |
| Crawl depth 1 (attempt 3) | `--background` mode | Submitted but perpetually "pending" |
| Diagnose | Read `CrawlService.kt`, `CrawlController.kt` source | Found root causes: deadlocked `AgenticContexts.await()`, URL-args concatenation bug, `args` nullability bug |
| Manual approach | `goto` + `eval` JavaScript | Successful: extracted 10 links matching selector/pattern |
| Format output | Python script from eval JSON | Table, CSV, JSON all produced |
| Seed-file crawl (attempt 1) | `crawl --seed-file ... --depth 0 --format table` | HTTP 400: `args` parameter null |
| Workaround | Added `--refresh` flag | **Success**: 3 pages crawled |
| Seed-file crawl (attempt 2) | Same + `--refresh` | **3 pages found** |
| Crawl list | `crawl list` | Shows 2 tracked tasks (stuck pending) |

---

## C. Issues Found

### Issue 1: Crawl with link discovery (depth >= 1) perpetually stuck in "pending"

**Severity:** Critical

**Category:** Reliability

**Reproduction:**
```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- crawl "http://books.toscrape.com/" --depth 1 --out-link-selector "a[href*=catalogue]" --out-link-pattern ".*catalogue.*" --top-links 10 --format table
```

**Expected:** Crawl completes, discovers links matching the selector, loads linked pages, returns results in table format.

**Actual:** Task remains "pending" forever. CLI shows "Still waiting for crawl to start..." repeatedly until timeout. Server log shows the task was submitted and a `StreamingTaskLoop` was created, but the coroutine never starts processing. One attempt found 0 out-links (loaded URL with args appended), another attempt timed out at 600s.

**Root Cause:** Two interacting bugs:
1. In `CrawlService.crawlDepth1()`, `session.load(request.url, options)` and/or `session.loadDocument(portalUrl, normOptions)` internally concatenates the URL with args string (e.g., `http://books.toscrape.com/ -outLinkPattern ... -outLinkSelector ... -topLinks 10`), producing an invalid URL. The page load fails silently and returns no out-links.
2. Each crawl submission creates a new `StaticAgenticContext` that accumulates in `PulsarContexts`. The `AgenticContexts.await()` in `crawlDepth1` waits on the global `activeContext`, which may not be the crawl's own context, causing a deadlock.

**Code Pointer:** `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:crawlDepth1()` and `extractOutLinks()`.

**AI Suggested Improvement:**
- Separate URL from LoadOptions args in `session.load()` and `session.loadDocument()` calls — do not concatenate them
- Scope `AgenticContexts.await()` to the crawl's own context, not the global active context
- Add proper error propagation — if `loadDocument` fails or returns empty, surface a clear error instead of silently returning 0 links

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: CrawlRequest `args` parameter null when `--args` flag not provided

**Severity:** High

**Category:** Reliability

**Reproduction:**
```
cargo run ... -- crawl --seed-file seed-urls.txt --depth 0 --format table
```

**Expected:** Crawl starts normally with empty args.

**Actual:** HTTP 400 Bad Request: `Cannot construct instance of ai.platon.pulsar.rest.api.service.CrawlRequest, problem: Parameter specified as non-null is null: method CrawlRequest.<init>, parameter args`.

**Root Cause:** In `cli/browser4-cli/src/commands.rs`, the `args` key is only added to the params map when load options are non-empty (`if !load_opts.is_empty()`). When no load options are specified, `args` is absent from the JSON body. The backend's `CrawlRequest` data class declares `args: String = ""` but Jackson deserialization receives `null` for the missing key, which violates Kotlin's non-null constraint.

**Code Pointer:** `cli/browser4-cli/src/commands.rs:2212-2213` — the conditional `if !load_opts.is_empty() { p["args"] = json!(...); }`

**AI Suggested Improvement:**
- Always include `args` in the JSON body, defaulting to `""` when no load options are specified
- Change line 2212-2213 to: `p["args"] = json!(load_opts.join(" "));` (always set, remove conditional)
- Alternatively, make the backend `CrawlRequest.args` nullable: `val args: String? = ""`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: No way to cancel/clear stuck crawl tasks from CLI

**Severity:** High

**Category:** Product

**Reproduction:**
1. Submit a crawl task that gets stuck
2. Run `crawl --help` — no cancel/stop/clear subcommands listed
3. Run `crawl list` — shows stuck tasks with no action available

**Expected:** Users should be able to cancel stuck tasks from the CLI (e.g., `crawl cancel <id>`, `crawl clear`).

**Actual:** The backend REST API supports `POST /api/crawl/{id}/cancel` and `POST /api/crawl/clear`, but these are not exposed through the CLI. Users must manually `curl` the REST API to cancel tasks.

**Root Cause:** The `crawl` CLI command only exposes `crawl [url]` and `crawl list`. The cancel, status, result, and clear subcommands exist in the backend controller but are not wired up in the CLI's command definitions.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — crawl command definition missing `cancel`, `clear`, `status`, `result` subcommands. Backend endpoints at `browser4-rest/.../CrawlController.kt:cancelCrawl()`, `clearCrawls()`.

**AI Suggested Improvement:**
- Add `crawl cancel <id>`, `crawl clear`, `crawl status <id>`, and `crawl result <id>` subcommands to the CLI
- Ensure `crawl list` shows task status with actionable advice when tasks are stuck
- Add a timeout hint in the "waiting to start" message: "Run `crawl cancel <id>` to cancel this task"

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Stale async task state blocks new crawl submissions

**Severity:** High

**Category:** Reliability

**Reproduction:**
1. Run several crawl commands in one session
2. Stop the server with `kill-all`
3. Restart the server
4. Submit a new crawl — stuck in "Still waiting for crawl to start..."

**Expected:** Fresh server start should have a clean task queue.

**Actual:** The backend creates new `StaticAgenticContext` instances for each crawl submission, which accumulate in the static `PulsarContexts.contexts` set. These contexts persist in the JVM process even after the server is "restarted" (same JVM, just re-registering contexts). The `AgenticContexts.await()` call blocks on these stale contexts.

**Root Cause:** `PulsarContexts` is a global singleton that accumulates contexts indefinitely. The `crawlDepth1` method uses `AgenticContexts.await()` which delegates to `PulsarContexts.await()` -> `activeContext?.await()`. But `activeContext` is set to the most recently created context, not necessarily the crawl's own context. Meanwhile, `crawlDispatcher` has `limitedParallelism(5)`, so if worker threads are blocked on stale contexts, new tasks starve.

**Code Pointer:** `browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/context/PulsarContexts.kt:await()` and `browser4-rest/.../CrawlService.kt:crawlDepth1()`.

**AI Suggested Improvement:**
- Create a per-crawl `CompletableDeferred` or `Job` instead of relying on the global `PulsarContexts.await()`
- Ensure `kill-all` properly cleans up all `PulsarContexts` and `AgenticContexts`
- Add a configurable per-crawl timeout with clear error reporting
- Consider making `crawlDispatcher` concurrency configurable

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: `crawl list` only shows CLI-tracked tasks, not backend tasks

**Severity:** Medium

**Category:** Product

**Reproduction:**
1. Submit crawl via `--background`
2. Kill server, restart
3. Submit another crawl
4. Run `crawl list`

**Expected:** Shows all crawl tasks known to the backend.

**Actual:** `crawl list` reads from the CLI's local `async-tasks.json`, not the backend. Tasks tracked by the backend but not in the CLI's file are invisible. Tasks from previous backend instances may show but with stale status.

**Root Cause:** The CLI maintains its own task tracking file (`~/.browser4/async-tasks.json`) separate from the backend's in-memory `taskStore` (`ConcurrentHashMap`). The `crawl list` command reads only the local file. The disconnect means the CLI and backend have different views of the task state.

**Code Pointer:** `cli/browser4-cli/src/main.rs:handle_crawl_list()` — reads from local file; should also query backend.

**AI Suggested Improvement:**
- `crawl list` should query the backend API (`GET /api/crawl/{id}/status`) for each tracked task to get live status
- Merge local tracking with backend status for a unified view
- Add a `crawl list --all` flag that queries the backend for all known tasks

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: `--args` / `-a` flag quoting is confusing and error-prone

**Severity:** Medium

**Category:** UX

**Reproduction:**
```
cargo run ... -- crawl "https://books.toscrape.com/" -d 1 -a "-outLink \"a[href*=catalogue]\"" -tl 10 --format table
```

**Expected:** `-a` flag passes the args string through to the backend.

**Actual:** Error: `too many arguments: expected 1, received 2`. Shell quoting of nested quotes inside the `-a` value is nearly impossible to get right, especially when also wrapping in `cargo run`.

**Root Cause:** The `-a` flag requires a single string argument, but the LoadOptions syntax uses space-separated flags with quoted values. The shell and CLI parser interact badly: the inner quotes get consumed by the shell, and the spaces inside the value are treated as argument separators by the CLI parser.

**Code Pointer:** `cli/browser4-cli/src/args.rs` and `cli/browser4-cli/src/commands.rs:2131` — the args option definition.

**AI Suggested Improvement:**
- Accept `--args` from file: `--args @loadopts.txt` (like `--sql @file.sql`)
- Accept `--args-stdin` to read from stdin (like `--sql-stdin`)
- Document in `crawl --help` that complex args should use `@file` or `--stdin` patterns
- Consider accepting multiple `-a` flags that get concatenated: `-a -refresh -a -nMaxRetry 5`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: `htmlsnapshot get` requires re-capture after each navigation

**Severity:** Low

**Category:** UX / Documentation

**Reproduction:**
1. `goto url1` → `htmlsnapshot` → `htmlsnapshot get text "h1"` — works
2. `goto url2` → `htmlsnapshot get text "h1"` — returns "No elements matched" without warning

**Expected:** Either auto-capture on first `get` after navigation, or a clear error telling the user to run `htmlsnapshot` first.

**Actual:** Silent failure — "No elements matched" which could mean "the selector is wrong" rather than "the snapshot is from the previous page."

**Root Cause:** `htmlsnapshot get` reads from the last captured HTML snapshot in page storage. After `goto`, the snapshot is stale but no warning is given.

**Code Pointer:** `browser4-rest/.../HtmlSnapshotController.kt` — get endpoint should check snapshot freshness.

**AI Suggested Improvement:**
- Auto-capture a snapshot when `htmlsnapshot get` is called after navigation (if snapshot is stale)
- Or, return a clear error: "No snapshot captured for this page. Run `htmlsnapshot` first."
- Add a `--capture` flag to `htmlsnapshot get` for one-shot capture-and-extract

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: `crawl` with link discovery returns 0 pages silently on partial failure

**Severity:** Medium

**Category:** UX

**Reproduction:**
```
crawl "http://books.toscrape.com/" -d 1 -ol "a[href*=catalogue]" -olp ".*catalogue.*" -tl 10 --format table
```

**Expected:** If out-link extraction fails, report the specific reason (selector matched 0 elements, page load failed, etc.).

**Actual:** "Crawl completed. 0 pages found." — no indication that the seed page loaded successfully but link extraction failed.

**Root Cause:** In `crawlDepth1`, when `extractOutLinks` returns empty, the crawl returns `emptyList()` with only a log message ("no out-links found on portal page"). This log never reaches the CLI user. The user sees "0 pages" and has no way to know whether the page failed to load, the selector didn't match, or something else went wrong.

**Code Pointer:** `browser4-rest/.../CrawlService.kt:crawlDepth1()` lines 291-294.

**AI Suggested Improvement:**
- Include diagnostic information in the crawl response when 0 pages are found: "Seed page loaded. Selector 'a[href*=catalogue]' matched 0 out-links. Check the selector."
- Report CSS selector match count in the crawl output
- Add a `--verbose` crawl flag for detailed per-page diagnostics

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

## D. Overall Assessment

### Task Completion Status
**Partially completed.** The core `crawl` command with link discovery (depth >= 1) is broken. The bulk fetch mode (depth 0) works with a workaround (`--refresh` flag). The task objectives were achieved through manual workarounds using `goto` + `eval` for link extraction, and manual file formatting for CSV/JSON output.

### Estimated Task Success Rate
**40%** — a first-time user would likely fail at the first `crawl` command and not know how to recover.

### Number of Issues Found
**8 issues** (2 Critical, 3 High, 2 Medium, 1 Low)

### Major Blockers
1. **Crawl link discovery completely broken** — the primary command for this task doesn't work
2. **No way to cancel stuck tasks from CLI** — once stuck, the queue is permanently blocked
3. **Stale state accumulation** — requires manual intervention to clear

### Most Confusing Aspects
1. The "Still waiting for crawl to start..." message repeating every 16 seconds with no progress indicator or estimated time
2. The "0 pages found" result with no explanation of WHY
3. `crawl list` showing tasks that can't be acted upon
4. `htmlsnapshot get` failing after navigation without indicating the snapshot is stale

### Most Valuable Improvements
1. **Fix crawl link discovery** — the core feature must work reliably
2. **Add crawl task management** — cancel, status, result subcommands
3. **Better error reporting** — tell users WHY a crawl returned 0 pages
4. **Auto-cleanup of stale contexts** — prevent queue blocking across sessions
5. **`--args` from file** — eliminate shell quoting nightmare

### Overall Usability Rating: **4/10**

**What works well:**
- `goto`, `eval`, `snapshot` commands are solid and reliable
- Help output is comprehensive and well-organized
- `htmlsnapshot` extraction works when used correctly
- Server auto-start is seamless
- Documentation (SKILL.md, references) is thorough

**What needs work:**
- The `crawl` command is functionally broken for its primary use case
- Error recovery is non-existent — stuck tasks require manual API calls
- State management between CLI and backend is fragile
- Shell quoting for complex arguments is painful
- Silent failures erode trust
