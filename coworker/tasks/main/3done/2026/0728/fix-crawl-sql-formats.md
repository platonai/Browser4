# Issues: crawl-sql-formats

> **Source:** `20260726-212346-crawl-sql-formats.full.md` | **Date:** 20260726-212346 | **Mode:** dev

## Scenario Background

### Task

**Partial completion.** The task could not be fully completed through the documented `crawl --sql` / `crawl --sql-stdin` CLI path due to multiple blocking bugs. However, X-SQL extraction was successfully demonstrated via the REST API (bypassing the CLI's MCP dispatch bug) and via `htmlsnapshot query` with explicit URLs.

**What worked:**
- `htmlsnapshot query "http://localhost:18080/generated/crawl/product/1.html" --sql @extract_url.sql` → correctly extracted `{"title":"Widget Alpha","price":"$10.00"}` (before backend state degradation)
- REST API `POST /api/crawl` with `"sql"` field in CrawlRequest → correctly extracted product 1 data

**What did NOT work:**
- `crawl --seed-file ... --sql @extract.sql --format csv -o results.csv` → always returned "No extracted data." (two root causes found)
- `crawl --seed-file ... --sql-stdin --format table < extract.sql` → not testable since the same code path is broken
- Product 2+ consistently returned 0 bytes content from the crawl fetch mechanism
- `htmlsnapshot` capture timed out

---

### Execution Context

| # | Command | Result |
|---|---------|--------|
| 1 | `./b4w.ps1 help` | Help displayed; noted PowerShell `-o` flag interception |
| 2 | Read `SKILL.md`, `crawl.md`, `x-sql.md` | Documentation reviewed |
| 3 | Verified MockSite running | HTTP 200 on all product pages |
| 4 | Created `seed_urls_crawl_eval.txt` + `extract.sql` | Files prepared |
| 5 | `./b4w.ps1 crawl --seed-file ... --sql @extract.sql --format csv -o results.csv` | PowerShell error: `-o` ambiguous |
| 6 | `./b4w.sh crawl ... --output results.csv` | Completed instantly; "No extracted data." |
| 7 | Debugged via `crawl result` → 0 pages, 0 bytes | Root cause: no `--depth 0` + SQL dropped |
| 8 | `./b4w.sh crawl ... --depth 0 --sql @extract.sql ...` | 2 pages found, 0 bytes content each |
| 9 | `./b4w.sh crawl ... --depth 0 ...

(truncated — see full.md for complete trace)

---

## Issues Found (10 issues)

### Issue 1: CrawlToolExecutor drops `--sql` parameter — X-SQL extraction silently disabled

**Severity:** Critical
**Category:** Product

#### Reproduction

```bash
./b4w.sh crawl --seed-file urls.txt --depth 0 --sql @query.sql --format csv -o out.csv
```
The CLI submits `crawl_submit` with `--sql=SELECT...` but the backend never receives it as `CrawlRequest.sql`.

#### Expected Behavior

X-SQL query is executed against each crawled page; extracted data appears in output.

#### Actual Behavior

Crawl completes with 0 extracted rows. CSV contains "No extracted data." Log confirms "X-SQL extraction: enabled" (CLI-side cosmetic check) but no extraction occurs.

#### Root Cause Analysis

`CrawlToolExecutor.callFunctionOn()` extracts `url`, `urls`, `depth`, and `args` from the MCP tool call parameters but never extracts `sql`. The `CrawlRequest` is constructed without `sql`, so `crawlDepth0()` sees `request.sql == null` and skips extraction.

#### Code Pointer

``browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/CrawlToolExecutor.kt:67-71` — `submit` handler`

#### AI Suggested Improvement

- Add `val sql = paramString(args, "sql", functionName, required = false)` to the submit handler and pass it to `CrawlRequest(sql = sql)`
- Add a similar extraction for `sqlStdin` and `sqlBase64` flags
- Add a unit test that verifies `sql` is included in the `CrawlRequest` passed to `crawlService.submit()`

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Clear code defect — `CrawlToolExecutor` never extracts `sql`/`sqlStdin`/`sqlBase64` from MCP params, so X-SQL extraction is always silently skipped regardless of CLI flags. Fix is a one-line addition at the identified code pointer plus a unit test.

---

### Issue 2: Crawl fetch returns 0 bytes for all URLs except the first

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```bash
curl -X POST http://localhost:8182/api/crawl \
  -H "Content-Type: application/json" \
  -d '{"urls": ["http://localhost:18080/generated/crawl/product/1.html",
                "http://localhost:18080/generated/crawl/product/2.html"], "depth": 0, "sql": "..."}'
```
Observe that product 1 gets `contentLength: 3226` with extracted data, while all subsequent URLs get `contentLength: 0`.

#### Expected Behavior

All seed URLs are fetched with full content; X-SQL extraction works for every page.

#### Actual Behavior

Only the first URL in the crawl receives content; subsequent URLs consistently return 0 bytes. Backend logs show `WARN FetchComponent - Protocol not found | http://.../product/2.html` and `ProtoNotFound(1600)` status for all non-first URLs. Reproduced across 4 crawl runs with different URL orderings — the first URL always works, the rest always fail.

#### Root Cause Analysis

The internal HTTP fetch mechanism (`FetchComponent`) logs "Protocol not found" for URLs after the first. This suggests either: (a) the HTTP protocol handler is being deregistered or corrupted after the first use in a `StaticAgenticSession`, (b) a connection pool resource is exhausted after a single use, or (c) a stateful cache stores the 0-byte result from an earlier failed fetch and `-refresh` doesn't fully bypass it. The `fc:` (fetch count) counter increments with each attempt, suggesting a cumulative state issue.

#### Code Pointer

``ai.platon.pulsar.skeleton.workflow.component.FetchComponent` (referenced in log as `a.p.p.s.w.c.FetchComponent`) — protocol resolution logic`

#### AI Suggested Improvement

- Investigate why "Protocol not found" is logged for valid `http://` URLs — the HTTP protocol handler should be statically registered and available for all requests
- Add a health check before each fetch to verify the protocol handler is available
- Ensure `-refresh` completely bypasses all caching layers, including protocol-level caches
- Add retry logic with exponential backoff when `ProtoNotFound(1600)` is encountered
- Consider resetting the HTTP client/connection pool between crawl batch items

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Reproducible critical bug — `FetchComponent` logs "Protocol not found" for every URL after the first in a `StaticAgenticSession`, returning 0 bytes. Suggests the HTTP protocol handler is deregistered or corrupted after first use. This is the most concrete manifestation of the backend degradation pattern; Issue 5 is the same root cause observed over time (see DUPLICATE below).

---

### Issue 3: Crawl queue congestion — tasks take 2+ minutes to start or get stuck permanently

**Severity:** High
**Category:** Reliability

#### Reproduction

Submit any crawl task via CLI or REST API. The CLI shows repeated "Still waiting for crawl to start... (16s/32s/48s... elapsed)" messages. REST API tasks remain in `"status": "Created"` for 2-4 minutes before execution begins.

#### Expected Behavior

Crawl tasks begin executing within seconds of submission.

#### Actual Behavior

Tasks take 2-4 minutes to transition from "Created" to executing. Some tasks (like REST API task `905dcdbb`) never start and remain permanently in "Created" state. The CLI times out at 600s showing 35+ "Still waiting" messages, creating a poor experience. After `crawl clear`, 13 tasks remain in the list with "not found" status from days ago.

#### Root Cause Analysis

Likely a coroutine dispatcher bottleneck. The `crawlScope` launches coroutines but the underlying dispatcher appears to have limited concurrency. Multiple prior failed crawl tasks may have consumed worker threads. The `crawl clear` command only removes terminal-state tasks from the in-memory cache but doesn't clean up the persistence file properly — stale entries revive on restart (confirmed by log: `Restored 32 entry/entries from crawl-tasks.jsonl`).

#### Code Pointer

``browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:174` — `crawlScope.launch`; line 84 — `JsonlPersistence` initialization`

#### AI Suggested Improvement

- Increase the concurrency of the crawl dispatcher or use a dedicated thread pool
- Add a watchdog that cancels tasks stuck in "Created" state for more than N seconds
- Fix `crawl clear` to also clean up the JSONL persistence file so stale tasks don't revive on restart
- Add a `crawl clear --all` flag to force-remove all tasks including non-terminal ones
- Consider adding a queue depth indicator so users can see how many tasks are ahead of theirs

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Crawl queue dispatcher bottleneck causes multi-minute delays and permanently stuck tasks. The `crawl clear` command also fails to purge the JSONL persistence file, so stale tasks revive on restart (28+ restored entries observed). The `htmlsnapshot` timeout in Issue 4 is a downstream symptom of this same dispatcher/resource exhaustion (see DUPLICATE below).

---

### Issue 4: `htmlsnapshot` capture times out after 60 seconds

**Severity:** High
**Category:** Reliability

#### Reproduction

```bash
./b4w.sh goto "http://localhost:18080/generated/crawl/product/1.html"
./b4w.sh htmlsnapshot
```

#### Expected Behavior

HTML snapshot is captured and stored for later querying.

#### Actual Behavior

`Error: HTTP request timed out [tool=html_snapshot_capture, endpoint=http://localhost:8182/mcp/call-tool, timeout=60s]`

#### Root Cause Analysis

The backend endpoint `/mcp/call-tool` is unresponsive for the `html_snapshot_capture` tool. This may be related to the same backend congestion affecting the crawl queue (Issue 3). After multiple crawl operations, the backend appears to enter a degraded state where tool calls time out.

#### Code Pointer

`Backend MCP tool dispatch — likely the same dispatcher/thread pool bottleneck as Issue 3`

#### AI Suggested Improvement

- Investigate the shared thread pool between crawl and snapshot operations
- Add circuit-breaker logic to reject new tool calls when the backend is overloaded rather than timing out silently
- Provide a `doctor --fix` option to reset the backend to a healthy state without full restart

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] The 60s timeout on `html_snapshot_capture` is a symptom of the backend thread-pool congestion described in Issue 3, as the issue itself identifies. The shared dispatcher that's saturated by stuck crawl tasks also blocks MCP tool dispatch. Fix Issue 3 first, then verify whether htmlsnapshot still times out independently.

---

### Issue 5: Backend state degradation over time — internal HTTP fetch eventually fails for all URLs

**Severity:** High
**Category:** Reliability

#### Reproduction

Run multiple crawl and `htmlsnapshot query` operations over 20-30 minutes. Observe that operations that initially succeeded (e.g., `htmlsnapshot query` with product 1 returning 3226 bytes, status OK) later fail with `pageStatusCode: 1600`, `pageContentBytes: 3226` but `resultSet: null`, `status: "Created"`.

#### Expected Behavior

Backend remains stable; repeated operations produce consistent results.

#### Actual Behavior

The backend's internal HTTP fetch mechanism degrades. Initially, `htmlsnapshot query` with explicit URLs works correctly. After several crawl operations, the same query returns `pageStatusCode: 1600` (matching `ProtoNotFound` log pattern) with `status: "Created"` instead of "OK", and `resultSet: null` despite getting content bytes. Eventually, even product 1 returns 0 bytes.

#### Root Cause Analysis

The backend accumulates state from failed crawl tasks (stale contexts, unreleased connections, or corrupted protocol handlers). The log shows `StaticAgenticContext` instances accumulating (context #1 through #9+) without being properly cleaned up. This suggests a resource leak where each crawl operation creates a new context that isn't fully released.

#### Code Pointer

``CrawlService.kt:329-361` `crawlDepth0()` — creates `AgenticContexts.createSession()` but may not fully clean up on all code paths`

#### AI Suggested Improvement

- Add a `finally` block that ensures `AgenticContexts` resources are released even when exceptions occur
- Implement a periodic context cleanup that prunes stale/leaked `StaticAgenticContext` instances
- Add backend health metrics (active contexts, connection pool status) to `doctor metrics`
- Consider a watchdog that restarts the backend if context count exceeds a threshold

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] Both describe the same `ProtoNotFound(1600)` / "Protocol not found" failure in `FetchComponent`. Issue 2 captures the immediate, reproducible trigger (non-first URL in a batch always fails); Issue 5 describes the progressive degradation where the same error spreads to all URLs over time. Same root cause — the protocol handler leak/cleanup problem in `StaticAgenticContext`. Merge into Issue 2's fix scope.

---

### Issue 6: Misleading "no outLinkSelector" warning when using `--sql` without `--depth 0`

**Severity:** Medium
**Category:** UX

#### Reproduction

```bash
./b4w.sh crawl --seed-file urls.txt --sql @query.sql --format table
```
(omitting `--depth 0`, which defaults to depth=1)

#### Expected Behavior

Either: (a) crawl works in bulk-fetch mode automatically when `--sql` is present but `--out-link-selector` is absent, or (b) a clear error message explains that `--depth 0` is needed for X-SQL extraction without link discovery.

#### Actual Behavior

Crawl completes instantly with 0 pages and "No extracted data." The CLI says "X-SQL extraction: enabled" (misleading). The backend log shows `no outLinkSelector provided, returning empty result` — a warning that only makes sense in link-discovery context. The user has no indication that adding `--depth 0` would fix the problem.

#### Root Cause Analysis

The default `depth=1` triggers link-discovery mode which requires `--out-link-selector`. When `--sql` is present but no out-link-selector is provided, the crawl short-circuits to an empty result. The code at `CrawlService.kt:373-376` checks `options.outLinkSelector.isNullOrBlank()` and returns empty — but should check whether `--sql` is present and auto-switch to depth=0 behavior.

#### Code Pointer

``browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:373-376` — `crawlDepth1` outLinkSelector check`

#### AI Suggested Improvement

- When `--sql` is provided but `--out-link-selector` is absent, automatically treat the crawl as depth=0 (bulk fetch with extraction) rather than depth=1 (link discovery)
- Alternatively, emit a clear CLI-side error: "X-SQL extraction requires --depth 0 when --out-link-selector is not specified"
- Update the CLI `--help` for `crawl` to note this interaction explicitly

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] When `--sql` is present without `--out-link-selector` and `--depth` defaults to 1, the crawl short-circuits with a misleading warning. The code should auto-detect: if `--sql` is provided but no link selector is given, treat as depth=0 (bulk fetch + extraction) instead of depth=1 (link discovery) that immediately returns empty. Clear, low-risk fix with high UX impact.

---

### Issue 7: PowerShell `-o` short flag intercepted by parameter binder

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```powershell
./b4w.ps1 crawl ... --format csv -o results.csv
```

#### Expected Behavior

The `-o` flag is passed through to browser4-cli as the `--output` short form.

#### Actual Behavior

PowerShell error: `Parameter cannot be processed because the parameter name 'o' is ambiguous. Possible matches include: -OutVariable -OutBuffer.`

#### Root Cause Analysis

PowerShell's parameter binder intercepts `-o` before it reaches the browser4-cli process. This is a known issue documented in SKILL.md under "PowerShell wrapper tip," but the workaround (using `--` separator or `b4w.sh`) is not obvious to first-time users.

#### Code Pointer

`N/A (PowerShell runtime behavior)`

#### AI Suggested Improvement

- Add a prominent warning in `b4w.ps1` when common intercepted flags are detected (`-o`, `-i`, `-v`)
- Document that `--output` (long form) is the recommended cross-platform way to specify output file
- Consider renaming `b4w.ps1` to avoid confusion, or adding a startup banner about flag interception
- Update the crawl examples in documentation to use `--output` instead of `-o`

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid PowerShell-specific issue, but the suggested fix of renaming `b4w.ps1` is excessive. Keep the fix scoped to: (a) add a startup warning in `b4w.ps1` when `-o`, `-i`, or `-v` are detected in `$args`, and (b) use `--output` long-form in all documentation examples. The `--` separator workaround should also be documented prominently.

---

### Issue 8: `crawl clear` leaves stale "not found" tasks in the task list

**Severity:** Medium
**Category:** Reliability

#### Reproduction

```bash
./b4w.sh crawl clear  # clears 20 tasks
./b4w.sh crawl list   # still shows 10+ tasks with "not found" status
```

#### Expected Behavior

After `crawl clear`, the task list is empty or only contains actively running tasks.

#### Actual Behavior

10-13 tasks remain in the list with "not found" status, dating back to July 10–20. These tasks survive `crawl clear` and even server restart because they're persisted in `crawl-tasks.jsonl`. The "not found" status indicates the in-memory task was cleared but the persistence entry wasn't removed, creating orphaned entries.

#### Root Cause Analysis

`crawl clear` calls `clearTerminal()` which only removes tasks with terminal statuses (OK, TIMEOUT, ERROR) from the in-memory Caffeine cache. "not found" tasks are synthetic responses generated by `getResult()` when a task ID isn't in the cache — they're never actually stored, but the persistence file retains the original task entries, causing them to reappear in listings.

#### Code Pointer

``browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:274-283` — `clearTerminal()``

#### AI Suggested Improvement

- Add a `crawl clear --all` flag that also rewrites the JSONL persistence file from scratch with only the currently active tasks
- Filter "not found" entries from the `crawl list` output — they're not real tasks
- Periodically purge the persistence file of entries older than TTL

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] `crawl clear` only removes terminal-state tasks from the in-memory Caffeine cache; orphaned JSONL persistence entries produce synthetic "not found" responses that survive both `clear` and server restart. Fix should: (a) rewrite the JSONL file from scratch during `clear` to drop stale entries, (b) filter "not found" from `crawl list` output, and (c) add a `--all` flag to force-clear non-terminal tasks.

---

### Issue 9: `status` command reports false version mismatch when dev backend is running

**Severity:** Low
**Category:** UX

#### Reproduction

```bash
./b4w.sh status
```
When running the locally-built backend (v4.12.0-SNAPSHOT) alongside the CLI built from the same source.

#### Expected Behavior

Version check confirms CLI and backend are compatible.

#### Actual Behavior

```
⚠  Version mismatch: CLI is 4.12.0 but installed backend is v4.11.15.
   The CLI was built from local source while the backend runs from a pre-installed bundle.
```
The message is misleading — the backend is actually running v4.12.0-SNAPSHOT from the local build (confirmed in server logs: `Starting Browser4BundleApplicationKt v4.12.0-SNAPSHOT`). The status check is comparing the CLI version against a separately-installed runtime bundle version, not the running backend.

#### Root Cause Analysis

The `status` command checks the version of a pre-installed runtime bundle rather than querying the actual running backend for its version. This is a dev-mode false positive.

#### Code Pointer

`CLI `status` command handler — version comparison logic should query the running server, not the installed bundle`

#### AI Suggested Improvement

- Query the running backend's actual version (e.g., via a `/api/version` endpoint or health check response) instead of checking the installed bundle version
- Or, detect dev mode (local build) and skip the version mismatch check entirely
- At minimum, clarify the message: "Running backend may differ from installed version" instead of the definitive-sounding warning

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The `status` command compares CLI version against an installed runtime bundle path rather than querying the running backend's actual version (available from server logs or a `/actuator/info`-style endpoint). This produces false "version mismatch" warnings in dev environments. Fix: query the live backend (add a `/api/version` endpoint or parse the health-check response) instead of checking a filesystem bundle.

---

### Issue 10: Cross-platform script inconsistency — `b4w.sh` vs `b4w.ps1` behavior differences

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Running `./b4w.ps1` in bash (via `./b4w.ps1 help`) vs `./b4w.sh`.

#### Expected Behavior

Both scripts provide equivalent functionality.

#### Actual Behavior

`b4w.sh` prints a warning on every invocation: "It is strongly recommended to launch `pwsh` and run the .ps1 commands directly within the `pwsh` terminal." This message appears on EVERY command, adding noise. Meanwhile, `b4w.ps1` has the PowerShell flag interception issues (Issue 5). Users are caught between a noisy warning and broken short flags.

#### Root Cause Analysis

The `b4w.sh` wrapper is a convenience script but its design intent is unclear — it warns users to use `pwsh` instead, which creates confusion about which script is the "correct" one to use.

#### AI Suggested Improvement

- Remove the "strongly recommended" warning from `b4w.sh` or make it appear only once per session
- Clearly document in SKILL.md when to use each script: `b4w.sh` for bash/Linux, `b4w.ps1` for PowerShell
- Add a `b4w` symlink or alias that auto-detects the shell environment

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Two real problems: (a) `b4w.sh` prints a loud "use pwsh instead" warning on every invocation, and (b) `b4w.ps1` has the flag interception documented in Issue 7. The suggested symlink fix isn't portable on Windows. Instead: remove the per-invocation warning from `b4w.sh` (keep it in docs/SKILL.md), document the shell-specific trade-offs once at install time, and ensure both scripts produce equivalent exit codes and stdout/stderr behavior.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: CrawlToolExecutor drops `--sql` parameter — X-SQL extraction silently disabled

```bash
./b4w.sh crawl --seed-file urls.txt --depth 0 --sql @query.sql --format csv -o out.csv
```
The CLI submits `crawl_submit` with `--sql=SELECT...` but the backend never receives it as `CrawlRequest.sql`.

#### Issue 2: Crawl fetch returns 0 bytes for all URLs except the first

```bash
curl -X POST http://localhost:8182/api/crawl \
  -H "Content-Type: application/json" \
  -d '{"urls": ["http://localhost:18080/generated/crawl/product/1.html",
                "http://localhost:18080/generated/crawl/product/2.html"], "depth": 0, "sql": "..."}'
```
Observe that product 1 gets `contentLength: 3226` with extracted data, while all subsequent URLs get `contentLength: 0`.

#### Issue 3: Crawl queue congestion — tasks take 2+ minutes to start or get stuck permanently

Submit any crawl task via CLI or REST API. The CLI shows repeated "Still waiting for crawl to start... (16s/32s/48s... elapsed)" messages. REST API tasks remain in `"status": "Created"` for 2-4 minutes before execution begins.

#### Issue 4: `htmlsnapshot` capture times out after 60 seconds

```bash
./b4w.sh goto "http://localhost:18080/generated/crawl/product/1.html"
./b4w.sh htmlsnapshot
```

#### Issue 5: Backend state degradation over time — internal HTTP fetch eventually fails for all URLs

Run multiple crawl and `htmlsnapshot query` operations over 20-30 minutes. Observe that operations that initially succeeded (e.g., `htmlsnapshot query` with product 1 returning 3226 bytes, status OK) later fail with `pageStatusCode: 1600`, `pageContentBytes: 3226` but `resultSet: null`, `status: "Created"`.

#### Issue 6: Misleading "no outLinkSelector" warning when using `--sql` without `--depth 0`

```bash
./b4w.sh crawl --seed-file urls.txt --sql @query.sql --format table
```
(omitting `--depth 0`, which defaults to depth=1)

#### Issue 7: PowerShell `-o` short flag intercepted by parameter binder

```powershell
./b4w.ps1 crawl ... --format csv -o results.csv
```

#### Issue 8: `crawl clear` leaves stale "not found" tasks in the task list

```bash
./b4w.sh crawl clear  # clears 20 tasks
./b4w.sh crawl list   # still shows 10+ tasks with "not found" status
```

#### Issue 9: `status` command reports false version mismatch when dev backend is running

```bash
./b4w.sh status
```
When running the locally-built backend (v4.12.0-SNAPSHOT) alongside the CLI built from the same source.

#### Issue 10: Cross-platform script inconsistency — `b4w.sh` vs `b4w.ps1` behavior differences

Running `./b4w.ps1` in bash (via `./b4w.ps1 help`) vs `./b4w.sh`.

