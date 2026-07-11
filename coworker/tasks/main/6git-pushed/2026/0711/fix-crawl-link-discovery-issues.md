# Issues: crawl-link-discovery

> **Source:** `20260709-220827-crawl-link-discovery.full.md` | **Date:** 20260709-220827 | **Mode:** dev

## Scenario Background

### Task

The task was **partially completed** with significant workarounds.

**Completed:**
1. ✅ Extracted 10 links from `http://books.toscrape.com/` matching `a[href*=catalogue]` with `.*catalogue.*` regex filter — results in table, CSV (`crawl-results.csv`), and JSON (`crawl-results.json`)
2. ✅ Created seed file with 3 book detail URLs (`seed-urls.txt`)
3. ✅ Seed-file crawl at depth 0 successfully found 3 pages (with `--refresh` workaround)
4. ✅ Crawl task list displayed

**Failed:**
- ❌ `crawl` command with depth 1 (link discovery mode) — perpetually stuck in "pending", never processes

### Execution Context

| Step | Command | Result |
|------|---------|--------|
| Help | `cargo run ... -- --help` | Works, good output |
| Crawl depth 1 (attempt 1) | `crawl "http://books.toscrape.com/" -d 1 -ol "a[href*=catalogue]" -olp ".*catalogue.*" -tl 10 --format table` | Stuck 144s, returned "0 pages found" |
| Crawl depth 1 (attempt 2) | Same with HTTPS | Timed out after 600s |
| Diagnose | Inspected server log, found 5 stale pending tasks from prior sessions | Queue blocked |
| Workaround | Cleared `async-tasks.json`, used `kill-all` | Fresh state |
| Crawl depth 1 (attempt 3) | `--background` mode | Submitted but perpetually "pending" |
| Diagnose | Read `CrawlService.kt`, `CrawlController.kt` source | Found root causes: deadlocked `AgenticContexts.await()`, URL-args concatenation bug, `args` nullabili...

(truncated — see full.md for complete trace)

---

## Issues Found (8 issues)
> **Review complete:** 6 approved, 2 deferred/rejected

### Issue 2: CrawlRequest `args` parameter null when `--args` flag not provided

**Severity:** High
**Category:** Reliability

#### Overview

**Severity:** High
**Category:** Reliability

#### Reproduction

```
cargo run ... -- crawl --seed-file seed-urls.txt --depth 0 --format table
```

#### Expected Behavior

Crawl starts normally with empty args.

#### Actual Behavior

HTTP 400 Bad Request: `Cannot construct instance of ai.platon.pulsar.rest.api.service.CrawlRequest, problem: Parameter specified as non-null is null: method CrawlRequest.<init>, parameter args`.

#### Root Cause Analysis

In `cli/browser4-cli/src/commands.rs`, the `args` key is only added to the params map when load options are non-empty (`if !load_opts.is_empty()`). When no load options are specified, `args` is absent from the JSON body. The backend's `CrawlRequest` data class declares `args: String = ""` but Jackson deserialization receives `null` for the missing key, which violates Kotlin's non-null constraint.

#### Code Pointer

``cli/browser4-cli/src/commands.rs:2212-2213` — the conditional `if !load_opts.is_empty() { p["args"] = json!(...); }``

#### AI Suggested Improvement

- Always include `args` in the JSON body, defaulting to `""` when no load options are specified
- Change line 2212-2213 to: `p["args"] = json!(load_opts.join(" "));` (always set, remove conditional)
- Alternatively, make the backend `CrawlRequest.args` nullable: `val args: String? = ""`

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 3: No way to cancel/clear stuck crawl tasks from CLI

**Severity:** High
**Category:** Product

#### Overview

**Severity:** High
**Category:** Product

#### Reproduction

1. Submit a crawl task that gets stuck
2. Run `crawl --help` — no cancel/stop/clear subcommands listed
3. Run `crawl list` — shows stuck tasks with no action available

#### Expected Behavior

Users should be able to cancel stuck tasks from the CLI (e.g., `crawl cancel <id>`, `crawl clear`).

#### Actual Behavior

The backend REST API supports `POST /api/crawl/{id}/cancel` and `POST /api/crawl/clear`, but these are not exposed through the CLI. Users must manually `curl` the REST API to cancel tasks.

#### Root Cause Analysis

The `crawl` CLI command only exposes `crawl [url]` and `crawl list`. The cancel, status, result, and clear subcommands exist in the backend controller but are not wired up in the CLI's command definitions.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` — crawl command definition missing `cancel`, `clear`, `status`, `result` subcommands. Backend endpoints at `browser4-rest/.../CrawlController.kt:cancelCrawl()`, `clearCrawls()`.`

#### AI Suggested Improvement

- Add `crawl cancel <id>`, `crawl clear`, `crawl status <id>`, and `crawl result <id>` subcommands to the CLI
- Ensure `crawl list` shows task status with actionable advice when tasks are stuck
- Add a timeout hint in the "waiting to start" message: "Run `crawl cancel <id>` to cancel this task"

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 4: Stale async task state blocks new crawl submissions

**Severity:** High
**Category:** Reliability

#### Overview

**Severity:** High
**Category:** Reliability

#### Reproduction

1. Run several crawl commands in one session
2. Stop the server with `kill-all`
3. Restart the server
4. Submit a new crawl — stuck in "Still waiting for crawl to start..."

#### Expected Behavior

Fresh server start should have a clean task queue.

#### Actual Behavior

The backend creates new `StaticAgenticContext` instances for each crawl submission, which accumulate in the static `PulsarContexts.contexts` set. These contexts persist in the JVM process even after the server is "restarted" (same JVM, just re-registering contexts). The `AgenticContexts.await()` call blocks on these stale contexts.

#### Root Cause Analysis

`PulsarContexts` is a global singleton that accumulates contexts indefinitely. The `crawlDepth1` method uses `AgenticContexts.await()` which delegates to `PulsarContexts.await()` -> `activeContext?.await()`. But `activeContext` is set to the most recently created context, not necessarily the crawl's own context. Meanwhile, `crawlDispatcher` has `limitedParallelism(5)`, so if worker threads are blocked on stale contexts, new tasks starve.

#### Code Pointer

``browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/context/PulsarContexts.kt:await()` and `browser4-rest/.../CrawlService.kt:crawlDepth1()`.`

#### AI Suggested Improvement

- Create a per-crawl `CompletableDeferred` or `Job` instead of relying on the global `PulsarContexts.await()`
- Ensure `kill-all` properly cleans up all `PulsarContexts` and `AgenticContexts`
- Add a configurable per-crawl timeout with clear error reporting
- Consider making `crawlDispatcher` concurrency configurable

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 5: `crawl list` only shows CLI-tracked tasks, not backend tasks

**Severity:** Medium
**Category:** Product

#### Overview

**Severity:** Medium
**Category:** Product

#### Reproduction

1. Submit crawl via `--background`
2. Kill server, restart
3. Submit another crawl
4. Run `crawl list`

#### Expected Behavior

Shows all crawl tasks known to the backend.

#### Actual Behavior

`crawl list` reads from the CLI's local `async-tasks.json`, not the backend. Tasks tracked by the backend but not in the CLI's file are invisible. Tasks from previous backend instances may show but with stale status.

#### Root Cause Analysis

The CLI maintains its own task tracking file (`~/.browser4/async-tasks.json`) separate from the backend's in-memory `taskStore` (`ConcurrentHashMap`). The `crawl list` command reads only the local file. The disconnect means the CLI and backend have different views of the task state.

#### Code Pointer

``cli/browser4-cli/src/main.rs:handle_crawl_list()` — reads from local file; should also query backend.`

#### AI Suggested Improvement

- `crawl list` should query the backend API (`GET /api/crawl/{id}/status`) for each tracked task to get live status
- Merge local tracking with backend status for a unified view
- Add a `crawl list --all` flag that queries the backend for all known tasks

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 6: `--args` / `-a` flag quoting is confusing and error-prone

**Severity:** Medium
**Category:** UX

#### Overview

**Severity:** Medium
**Category:** UX

#### Reproduction

```
cargo run ... -- crawl "https://books.toscrape.com/" -d 1 -a "-outLink \"a[href*=catalogue]\"" -tl 10 --format table
```

#### Expected Behavior

`-a` flag passes the args string through to the backend.

#### Actual Behavior

Error: `too many arguments: expected 1, received 2`. Shell quoting of nested quotes inside the `-a` value is nearly impossible to get right, especially when also wrapping in `cargo run`.

#### Root Cause Analysis

The `-a` flag requires a single string argument, but the LoadOptions syntax uses space-separated flags with quoted values. The shell and CLI parser interact badly: the inner quotes get consumed by the shell, and the spaces inside the value are treated as argument separators by the CLI parser.

#### Code Pointer

``cli/browser4-cli/src/args.rs` and `cli/browser4-cli/src/commands.rs:2131` — the args option definition.`

#### AI Suggested Improvement

- Accept `--args` from file: `--args @loadopts.txt` (like `--sql @file.sql`)
- Accept `--args-stdin` to read from stdin (like `--sql-stdin`)
- Document in `crawl --help` that complex args should use `@file` or `--stdin` patterns
- Consider accepting multiple `-a` flags that get concatenated: `-a -refresh -a -nMaxRetry 5`

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 8: `crawl` with link discovery returns 0 pages silently on partial failure

**Severity:** Medium
**Category:** UX

#### Overview

**Severity:** Medium
**Category:** UX

#### Reproduction

```
crawl "http://books.toscrape.com/" -d 1 -ol "a[href*=catalogue]" -olp ".*catalogue.*" -tl 10 --format table
```

#### Expected Behavior

If out-link extraction fails, report the specific reason (selector matched 0 elements, page load failed, etc.).

#### Actual Behavior

"Crawl completed. 0 pages found." — no indication that the seed page loaded successfully but link extraction failed.

#### Root Cause Analysis

In `crawlDepth1`, when `extractOutLinks` returns empty, the crawl returns `emptyList()` with only a log message ("no out-links found on portal page"). This log never reaches the CLI user. The user sees "0 pages" and has no way to know whether the page failed to load, the selector didn't match, or something else went wrong.

#### Code Pointer

``browser4-rest/.../CrawlService.kt:crawlDepth1()` lines 291-294.`

#### AI Suggested Improvement

- Include diagnostic information in the crawl response when 0 pages are found: "Seed page loaded. Selector 'a[href*=catalogue]' matched 0 out-links. Check the selector."
- Report CSS selector match count in the crawl output
- Add a `--verbose` crawl flag for detailed per-page diagnostics

#### Human Review

- [x] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- [ ] **DUPLICATE**
- **Notes:**

---

### Issue 1: Crawl with link discovery (depth >= 1) perpetually stuck in "pending"

**Severity:** Critical
**Category:** Reliability

#### Review Result

**Decision:** DUPLICATE

**Summary:** - Separate URL from LoadOptions args in `session.load()` and `session.loadDocument()` calls — do not concatenate them

---

### Issue 7: `htmlsnapshot get` requires re-capture after each navigation

**Severity:** Low
**Category:** UX / Documentation

#### Review Result

**Decision:** DEFER

**Summary:** - Auto-capture a snapshot when `htmlsnapshot get` is called after navigation (if snapshot is stale)

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Crawl with link discovery (depth >= 1) perpetually stuck in "pending"

```
cargo run --manifest-path cli/browser4-cli/Cargo.toml -- crawl "http://books.toscrape.com/" --depth 1 --out-link-selector "a[href*=catalogue]" --out-link-pattern ".*catalogue.*" --top-links 10 --format table
```

#### Issue 2: CrawlRequest `args` parameter null when `--args` flag not provided

```
cargo run ... -- crawl --seed-file seed-urls.txt --depth 0 --format table
```

#### Issue 3: No way to cancel/clear stuck crawl tasks from CLI

1. Submit a crawl task that gets stuck
2. Run `crawl --help` — no cancel/stop/clear subcommands listed
3. Run `crawl list` — shows stuck tasks with no action available

#### Issue 4: Stale async task state blocks new crawl submissions

1. Run several crawl commands in one session
2. Stop the server with `kill-all`
3. Restart the server
4. Submit a new crawl — stuck in "Still waiting for crawl to start..."

#### Issue 5: `crawl list` only shows CLI-tracked tasks, not backend tasks

1. Submit crawl via `--background`
2. Kill server, restart
3. Submit another crawl
4. Run `crawl list`

#### Issue 6: `--args` / `-a` flag quoting is confusing and error-prone

```
cargo run ... -- crawl "https://books.toscrape.com/" -d 1 -a "-outLink \"a[href*=catalogue]\"" -tl 10 --format table
```

#### Issue 7: `htmlsnapshot get` requires re-capture after each navigation

1. `goto url1` → `htmlsnapshot` → `htmlsnapshot get text "h1"` — works
2. `goto url2` → `htmlsnapshot get text "h1"` — returns "No elements matched" without warning

#### Issue 8: `crawl` with link discovery returns 0 pages silently on partial failure

```
crawl "http://books.toscrape.com/" -d 1 -ol "a[href*=catalogue]" -olp ".*catalogue.*" -tl 10 --format table
```

#auto-approve
