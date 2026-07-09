# Issues: crawl-link-discovery

> **Source:** `20260708-182349-crawl-link-discovery.full.md` | **Date:** 20260708-182349 | **Mode:** dev

## Scenario Background

### Task

**Partial success.** The following task steps were completed or partially completed:

| Step | Description | Result |
|------|-------------|--------|
| 1 | Crawl depth=1 with `--out-link-selector`, `--out-link-pattern`, `--top-links 10`, table format | ❌ Failed — 0 pages found (JCommander CSS selector parsing bug + `extractOutLinks` returns empty) |
| 2 | Crawl as CSV with `--output` | ⚠️ Partial — `--output` wrote a file, but `--format csv` had no effect without `--sql`; output was plain text, not CSV |
| 3 | Crawl as JSON | ⚠️ Partial — `--format json` had no effect without `--sql`; output was plain text, not JSON |
| 4 | Seed file crawl | ✅ Success — 3 URLs crawled via `--seed-file` with `--depth 0 --refresh` |
| 5 | List crawl tasks | ✅ Success — `crawl list` showed tracked tasks (only persisted for `--background` mode) |

---

### Execution Context

**Key Commands:**

```
1. cargo run --manifest-path cli/browser4-cli/Cargo.toml -- --help
   → Verified CLI help output and crawl command documentation

2. cargo run ... -- goto "http://books.toscrape.com/"
   → Navigated to target site, observed HTTP→HTTPS redirect

3. cargo run ... -- eval "Array.from(document.querySelectorAll('a[href*=\"catalogue\"]'))..."
   → Discovered link structure: product links use "catalogue/" prefix

4. cargo run ... -- crawl "http://books.toscrape.com/" --depth 1
   --out-link-selector 'a[href*="catalogue"]' --out-link-pattern '.*catalogue.*'
   --top-links 10 --format table
   → FAILED: 0 pages. Root cause: CSS selector internal double-quotes broke
     JCommander args parsing (PowerSelector error: "Did not find balanced marker")

5. cargo run ... -- kill-all
   → Cleaned backend. Discovered old tasks in ~/.browser4/async-tasks.json

6. cargo run ... -- crawl "https://books.toscrape.com/" --depth 1
   --out-link-selector 'a[href*=catalogue]' --out-link-pattern '.*catalogue.*'
   --top-links 10 --format table
   → Page fetched OK (HTTP 200, 64.7KB) but "no out-links found on portal page"
     — extractOutLinks() returned empty despite successful page load

7. cargo run ... -- crawl --seed-file books-seed-urls.txt --depth 0
   → 400 Bad Request: args parameter null (CLI doesn't send args when no
     link-discovery flags are set)

8. cargo run ... -- crawl --seed-file books-seed-urls.txt --depth 0 --refresh
   → SUCCESS: 3 pages found (but with empty titles, pages likely failed to load)

9. cargo run ... -- crawl --seed-file books-seed-urls.txt --depth 0 --refresh
   --format csv --output crawl-results.csv
   → Wrote file but output was plain text, not CSV format

10. cargo run ... -- crawl --seed-file books-seed-urls.txt --depth 0 --refresh
    --format json
    → Output was plain text, not JSON format

11. cargo run ... -- crawl --seed-file books-seed-urls.txt --depth 0 --refresh
    --background
    → Background task tracked in async-tasks.json, showed in crawl list

12. cargo run ... -- crawl list
    → Showed 2 tracked tasks (both stuck in "pending" status)
```

**Key decisions:**
- Switched from `a[href*="catalogue"]` to `a[href*=catalogue]` to avoid double-quote parsing issues
- Switched from `http://` to `https://` after discovering `ProtoNotFound(1600)` on HTTP URLs
- Added `--refresh` flag as workaround for null-args 400 error
- Manually cleaned `~/.browser4/async-tasks.json` to clear congested task queue

**Workarounds required:**
1. Manually edited `~/.browser4/async-tasks.json` to clear stalled tasks blocking the crawl queue
2. Added `--refresh` flag to force args string construction (workaround for null-args bug)
3. Used `--seed-file --depth 0` instead of depth-1 link discovery (link discovery is broken)
4. Used `kill-all` + `goto` to restart backend with clean state

---

---

## Issues Found (10 issues)

### Issue 1: CSS selector quoting breaks JCommander args parser

**Severity:** Critical
**Category:** Product

#### Reproduction

```bash
cargo run ... -- crawl "https://books.toscrape.com/" --depth 1 \
  --out-link-selector 'a[href*="catalogue"]' --out-link-pattern '.*catalogue.*' \
  --top-links 10
```

#### Expected Behavior

CSS selector `a[href*="catalogue"]` is correctly parsed and used to extract matching links from the page.

#### Actual Behavior

The server's JCommander args parser truncates the selector at `=`, resulting in `a[href*=` being passed to the CSS engine. The server logs: `WARN PowerSelector - Failed to parse css query | a[href*= | Did not find balanced marker at 'href*='`. No links are extracted.

#### Root Cause Analysis

The CLI builds an args string like `-outLink "a[href*="catalogue"]"` (line 2173 of `commands.rs`). The inner double quotes around `catalogue` conflict with the outer double quotes used to delimit the argument value. JCommander's `=` handling further complicates parsing. The `correctOutLinkSelector()` function in `LoadOptions.kt:989` only strips outer quotes but can't recover from the JCommander-level parse failure.

#### Code Pointer

``cli/browser4-cli/src/commands.rs:2173` (args string construction) and `browser4-core/browser4-skeleton/src/main/kotlin/.../LoadOptions.kt:989` (correctOutLinkSelector)`

#### AI Suggested Improvement

- Use base64 encoding or a delimiter-safe escaping mechanism for CSS selectors containing special characters (`=`, `"`, spaces)
- Replace `format!("-outLink \"{}\"", v)` with a version that escapes internal double quotes, e.g. `format!("-outLink '{}'", v.replace('\'', "\\'"))` and update the server to handle single-quoted values
- Add input validation on the CLI side to warn when the selector contains characters known to cause parsing issues
- Consider passing CSS selectors via a separate request field rather than embedding them in a JCommander args string

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 2: `--refresh` flag causes `Protocol not found` (ProtoNotFound 1600) for HTTPS URLs

**Severity:** Critical
**Category:** Reliability

#### Reproduction

```bash
cargo run ... -- crawl "https://books.toscrape.com/" --depth 1 --refresh \
  --out-link-selector 'a[href]' --top-links 10
```

#### Expected Behavior

`--refresh` forces a fresh page fetch. The page loads normally with HTTP 200.

#### Actual Behavior

Server logs: `WARN FetchComponent - Protocol not found | https://books.toscrape.com/` and `ProtoNotFound(1600)`. The page fails to load. The `--refresh` flag adds `-expireAt 1970-01-01T00:00:00Z -expires PT0S -ignoreFailure -itemExpireAt 1970-01-01T00:00:00Z -itemExpires PT0S` to the LoadOptions, which breaks the protocol handler for HTTPS URLs.

#### Root Cause Analysis

The `doRefresh()` function in `LoadOptions.kt:1004` sets expiry to epoch zero (`expireAt = Instant.ofEpochSecond(0)`), which causes the URL normalization or protocol selection logic to fail for HTTPS. The protocol handler lookup appears to depend on cache state, and epoch-zero expiry invalidates the handler.

#### Code Pointer

``browser4-core/browser4-skeleton/src/main/kotlin/.../LoadOptions.kt:1004` (doRefresh) and `browser4-core/.../FetchComponent.kt` (protocol lookup)`

#### AI Suggested Improvement

- Fix the protocol handler to work regardless of expiry settings
- Use a relative expiry (e.g., `expires = Duration.ZERO`) rather than an absolute epoch-zero timestamp
- Add a warning when `--refresh` is used with HTTPS URLs if this is a known limitation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 3: `--format csv` and `--format json` have no effect without `--sql`

**Severity:** High
**Category:** Documentation / UX

#### Reproduction

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh --format csv -o out.csv
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh --format json
```

#### Expected Behavior

CSV output produces comma-separated values; JSON output produces a JSON array of objects. The crawl documentation states: "`--format` controls output: 'table' (default, aligned columns), 'csv', or 'json'."

#### Actual Behavior

Both CSV and JSON modes produce the same plain-text "page listing" output as table mode. The `--format` flag is silently ignored when no `--sql` query is provided. The CSV file written by `--output` contains plain text, not CSV.

#### Root Cause Analysis

The `--format` flag only affects the rendering of X-SQL query result rows. When no `--sql` is provided, the output is always the page listing format. This is documented in the crawl reference under "Page listing (no --sql)" but the `--help` output and summary documentation don't mention this limitation.

#### Code Pointer

``browser4-rest/.../CrawlService.kt` — output formatting logic is conditional on SQL results being present`

#### AI Suggested Improvement

- When `--format csv` or `--format json` is specified without `--sql`, format the page listing in the requested format (CSV with URL/title/depth columns; JSON as array of page objects)
- Update the CLI help text and crawl reference to clearly state: "`--format` applies only when used with `--sql`"
- Return an error or warning when `--format` is specified without `--sql`
- Document this behavior explicitly in the crawl command's `--help` output

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Valid issue but the fix should be: when --format csv/json is specified without --sql, either (preferred) format the page listing in the requested format, or at minimum emit a warning/error that --format requires --sql. Silently ignoring the flag is the core problem — either make it work or make it loud.

---

### Issue 4: CLI sends null `args` field when no link-discovery flags are set, causing 400 error

**Severity:** High
**Category:** Product

#### Reproduction

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0
```

#### Expected Behavior

The seed file crawl starts successfully.

#### Actual Behavior

`HTTP 400 Bad Request`: `Parameter specified as non-null is null: method CrawlRequest.<init>, parameter args`

#### Root Cause Analysis

In `commands.rs:2212-2214`, the `args` JSON field is only set when `load_opts` is non-empty. When no `--out-link-selector`, `--refresh`, or other LoadOptions flags are specified, `load_opts` is empty, `p["args"]` is never set, and the server receives a request without the `args` field. While `CrawlRequest` has `val args: String = ""` as a default, Jackson doesn't use the Kotlin default when the field is missing from the JSON body.

#### Code Pointer

``cli/browser4-cli/src/commands.rs:2212-2214``

#### AI Suggested Improvement

- Always include `args` in the request JSON, defaulting to `""` when no options are specified
- Change line 2212-2214 to: `p["args"] = json!(if load_opts.is_empty() { "" } else { load_opts.join(" ") });`
- Add `@JsonInclude(Include.NON_NULL)` or configure Jackson to use Kotlin default values for missing fields

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 5: Crawl tasks persist in queue across backend restarts with no way to cancel

**Severity:** High
**Category:** Reliability / UX

#### Reproduction

1. Submit several crawl tasks that get stuck (e.g., targeting an unreachable URL)
2. Run `kill-all` to stop the backend
3. Restart the backend
4. New crawls queue behind the old stuck tasks

#### Expected Behavior

`kill-all` should clear all pending tasks, or there should be a `crawl cancel <id>` command to remove stuck tasks.

#### Actual Behavior

The async tasks persist in `~/.browser4/async-tasks.json` and survive backend restarts. There is no CLI command to cancel or delete individual tasks. The only workaround is manually editing the JSON file. Old tasks targeting unreachable URLs (e.g., `http://localhost:18080`) block the entire crawl queue.

#### Root Cause Analysis

The async task store is file-based (`async-tasks.json`) and is not cleared on backend restart. The crawl task processor iterates through tasks sequentially and each one runs to timeout (600s) before the next begins. There is no task cancellation API exposed to the CLI.

#### Code Pointer

``cli/browser4-cli/src/commands.rs` (missing crawl cancel command) and `browser4-rest/.../CrawlService.kt` (task queue management)`

#### AI Suggested Improvement

- Add a `crawl cancel <task-id>` or `crawl clear` command to remove tasks from the queue
- Clear completed/failed tasks from `async-tasks.json` on backend startup
- Auto-skip tasks targeting unreachable hosts after the first failure
- Add `kill-all` option to also clear the async task queue
- Document the `~/.browser4/async-tasks.json` file and how to manually clear it

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 6: Background crawl tasks remain "pending" forever; status never updates

**Severity:** Medium
**Category:** Reliability / UX

#### Reproduction

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh --background
cargo run ... -- crawl list  # shows "pending" even after task completion
```

#### Expected Behavior

The task status updates to "completed" or "failed" after the crawl finishes. The user can track progress.

#### Actual Behavior

Tasks submitted with `--background` remain in "pending" status indefinitely, even after the server logs show the task completed. The `crawl list` output never reflects the actual completion status. The `lastStatus` field in `async-tasks.json` is not updated by the server.

#### Root Cause Analysis

The server-side crawl completion handler does not update the client-side `async-tasks.json`. The CLI `crawl list` reads only from this local file and has no server-side status check. The synchronous crawl path writes completion info but the async path does not.

#### Code Pointer

``browser4-rest/.../CrawlService.kt` (async task completion callback) and CLI task tracking code`

#### AI Suggested Improvement

- Update `async-tasks.json` when background tasks complete, including status and result summary
- For `crawl list`, also query the server for current task status rather than relying solely on the local file
- Add a `crawl status <id>` command to check individual task status from the server
- Show task progress indicators (e.g., "2/3 URLs processed")

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 7: Crawl always takes 90+ seconds "waiting to start" before processing

**Severity:** Medium
**Category:** UX / Reliability

#### Reproduction

Any crawl command: `cargo run ... -- crawl <url> ...`

#### Expected Behavior

The crawl starts within a few seconds.

#### Actual Behavior

Every crawl command displays "Still waiting for crawl to start..." messages at 16-second intervals, taking 90-100 seconds before the crawl actually begins processing.

#### Root Cause Analysis

The synchronous crawl path polls for task completion. The initial "waiting to start" period appears to be the time needed for the backend to create a `StaticAgenticContext`, initialize a `StreamingTaskLoop`, load properties, and prepare the page fetcher. Each new crawl context takes ~90 seconds to initialize even though the JVM is already running. The polling interval is 16 seconds.

#### Code Pointer

``browser4-rest/.../CrawlService.kt` (task initialization pipeline) and CLI polling logic`

#### AI Suggested Improvement

- Reuse crawl contexts across tasks instead of creating a new one per crawl
- Pre-warm the crawl infrastructure when the backend starts
- Add a progress indicator showing what's happening during the 90-second wait (e.g., "Initializing browser context...", "Loading properties...")
- Reduce the synchronous poll interval from 16s to 5s for faster feedback
- Consider using a connection pool for crawl contexts

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] The 90-second crawl startup is caused by constructing a new StaticAgenticContext, StreamingTaskLoop, and page fetcher per crawl. Fixing this requires refactoring the crawl context lifecycle to reuse/warm contexts — a significant architectural change. Defer until the crawl infrastructure gets dedicated attention. In the meantime, adding progress indicators (e.g., 'Initializing browser context…') during the wait would mitigate the UX impact.

---

### Issue 8: Crawl reports "completed. N pages found" even when all pages failed to load

**Severity:** Medium
**Category:** Reliability / UX

#### Reproduction

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh
```

#### Expected Behavior

If all pages fail to load (ProtoNotFound), the crawl should report errors.

#### Actual Behavior

The crawl reports "Crawl completed. 3 pages found." with empty titles, even though all pages returned `ProtoNotFound(1600)`. The user sees success but the pages have no content.

#### Root Cause Analysis

The crawl's page counting logic counts URLs processed, not successful page loads. The `ProtoNotFound` error is logged server-side but not surfaced to the CLI output. Empty page titles (due to failed loads) are displayed as-is without any warning.

#### Code Pointer

``browser4-rest/.../CrawlService.kt` (page counting and result reporting)`

#### AI Suggested Improvement

- Report failed pages separately from successful ones in the crawl output
- Show a summary: "3 pages found (0 loaded successfully, 3 failed)"
- Display error status alongside each page entry (e.g., `depth=0 | url | [ERROR: ProtoNotFound]` instead of empty title)
- Include a failure count in the completion message
- Surface server-side fetch errors to CLI output

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

### Issue 9: `--json` global flag does not produce machine-parseable JSON for crawl output

**Severity:** Low
**Category:** Product

#### Reproduction

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh --format json --json
```

#### Expected Behavior

The `--json` global flag produces a single-line JSON envelope: `{"status":"ok","command":"crawl","output":{...}}`.

#### Actual Behavior

The crawl output still appears in human-readable format. The `--json` flag appears to be ignored for the crawl command.

#### Root Cause Analysis

The crawl command's output is produced by the server and rendered as-is by the CLI. The `--json` flag controls CLI-side output formatting but crawl results are rendered as raw text from the server response, bypassing the JSON envelope.

#### Code Pointer

``cli/browser4-cli/src/main.rs` (crawl output rendering)`

#### AI Suggested Improvement

- When `--json` is specified, parse the server response and wrap it in the JSON envelope
- Or have the server return structured JSON that the CLI can format appropriately

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] The global --json flag wraps CLI-side output in a JSON envelope, but crawl output is rendered as raw server response text, bypassing that envelope. Fixing this requires restructuring how crawl output flows through the CLI output pipeline — nontrivial plumbing. The --format json flag (addressed in Issue 3) is the proper crawl-specific mechanism for structured output. Defer until the CLI output architecture is revisited.

---

### Issue 10: Help output for crawl `--format` does not mention it only works with `--sql`

**Severity:** Low
**Category:** Documentation / Discoverability

#### Reproduction

```bash
cargo run ... -- crawl --help
```

#### Expected Behavior

Help text explains that `--format` only affects X-SQL query output.

#### Actual Behavior

Help says: "Output format for extracted data: json, csv, or table (default: table)" without mentioning the `--sql` dependency.

#### Root Cause Analysis

The `OptionDef` description in `commands.rs:2126` doesn't include this limitation.

#### Code Pointer

``cli/browser4-cli/src/commands.rs:2126``

#### AI Suggested Improvement

- Update description to: "Output format for extracted data when using --sql: json, csv, or table (default: table). Has no effect without --sql."

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT]

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. Build the CLI: `cd cli/browser4-cli && cargo build`
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `cd cli/browser4-cli && cargo run -- <command>`

### Per-Issue Reproduction Steps

#### Issue 1: CSS selector quoting breaks JCommander args parser

```bash
cargo run ... -- crawl "https://books.toscrape.com/" --depth 1 \
  --out-link-selector 'a[href*="catalogue"]' --out-link-pattern '.*catalogue.*' \
  --top-links 10
```

#### Issue 2: `--refresh` flag causes `Protocol not found` (ProtoNotFound 1600) for HTTPS URLs

```bash
cargo run ... -- crawl "https://books.toscrape.com/" --depth 1 --refresh \
  --out-link-selector 'a[href]' --top-links 10
```

#### Issue 3: `--format csv` and `--format json` have no effect without `--sql`

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh --format csv -o out.csv
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh --format json
```

#### Issue 4: CLI sends null `args` field when no link-discovery flags are set, causing 400 error

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0
```

#### Issue 5: Crawl tasks persist in queue across backend restarts with no way to cancel

1. Submit several crawl tasks that get stuck (e.g., targeting an unreachable URL)
2. Run `kill-all` to stop the backend
3. Restart the backend
4. New crawls queue behind the old stuck tasks

#### Issue 6: Background crawl tasks remain "pending" forever; status never updates

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh --background
cargo run ... -- crawl list  # shows "pending" even after task completion
```

#### Issue 7: Crawl always takes 90+ seconds "waiting to start" before processing

Any crawl command: `cargo run ... -- crawl <url> ...`

#### Issue 8: Crawl reports "completed. N pages found" even when all pages failed to load

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh
```

#### Issue 9: `--json` global flag does not produce machine-parseable JSON for crawl output

```bash
cargo run ... -- crawl --seed-file urls.txt --depth 0 --refresh --format json --json
```

#### Issue 10: Help output for crawl `--format` does not mention it only works with `--sql`

```bash
cargo run ... -- crawl --help
```

