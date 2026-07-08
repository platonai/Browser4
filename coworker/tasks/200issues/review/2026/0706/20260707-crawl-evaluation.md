# Browser4-CLI Crawl Evaluation Report

**Date:** 2026-07-07
**Evaluator:** First-time user perspective
**Task:** Crawl books.toscrape.com with link discovery, seed files, X-SQL extraction, and multi-format output
**Task Status:** ⚠️ Partially completed (4/9 steps succeeded, 3/9 with workarounds, 2/9 blocked)

---

## A. Task Result

The crawl task was partially completed. The **bulk fetch mode** (depth 0 + seed file) worked correctly with X-SQL extraction and all three output formats (table, CSV, JSON). However, the **link discovery mode** (depth >= 1 with `--out-link-selector`) consistently failed with "Crawl completed. 0 pages found" regardless of the CSS selector used or site targeted. Several infrastructure issues (Maven version incompatibility, stale queue congestion, hidden commands) complicated the workflow.

### What worked:
- ✅ Bulk fetch crawl (seed-file + depth 0): loaded and listed 3 pages successfully
- ✅ X-SQL extraction with table format
- ✅ X-SQL extraction with CSV format and `--output` file
- ✅ X-SQL extraction with JSON format
- ✅ `crawl list` shows tracked tasks (though all as "pending")
- ✅ `swarm submit` as an alternative to crawl

### What did NOT work:
- ❌ Link discovery mode (depth >= 1 with `--out-link-selector`): consistently returns 0 pages
- ❌ `crawl clear` / `crawl cancel`: commands exist in code but are not discoverable or usable
- ❌ Local Maven build: system Maven 3.0.2 is too old (needs 3.6.3+)
- ❌ `--args` flag: spaces in values cause parsing errors
- ❌ Task status tracking: all tasks show "pending" even after completion

---

## B. Execution Trace

### Commands Used

| # | Command | Result |
|---|---|---|
| 1 | `./cli/.../browser4-cli --help` | Full command listing displayed |
| 2 | `./cli/.../browser4-cli crawl --help` | Crawl-specific help with all flags |
| 3 | `./cli/.../browser4-cli crawl "http://books.toscrape.com/" -ol "a[href*='catalogue']" -olp ".*catalogue.*" -tl 10 --format table` | 0 pages found |
| 4 | `./cli/.../browser4-cli goto "http://books.toscrape.com/"` | Page loaded, session opened |
| 5 | `./cli/.../browser4-cli snapshot -i -u` | Snapshot captured with 58KB accessibility tree |
| 6 | `./cli/.../browser4-cli crawl "https://books.toscrape.com/" -d 1 -ol "a" -tl 3` | 0 pages found |
| 7 | `./cli/.../browser4-cli crawl "https://example.com/" -d 1 -ol "a" -tl 3 --refresh` | Stuck in queue (66 pending tasks) |
| 8 | `./cli/.../browser4-cli stop` | Server stopped (then failed to restart due to Maven) |
| 9 | `BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1 ./cli/.../browser4-cli goto "https://books.toscrape.com/"` | Server restarted with installed runtime |
| 10 | `BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1 ./cli/.../browser4-cli crawl "https://books.toscrape.com/" -ol "a" -tl 5` | 0 pages found |
| 11 | Same with `httpbin.org/links/10` | 0 pages found |
| 12 | `./cli/.../browser4-cli crawl-clear` | "Unsupported command form" |
| 13 | `./cli/.../browser4-cli crawl clear` | "Unknown command: 'crawl-clear'" |
| 14 | `BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1 ./cli/.../browser4-cli crawl --seed-file books-seed-urls.txt -d 0 --refresh --format table` | **3 pages found!** |
| 15 | Same with `--sql @extract-books.sql --format table` | Table output with extracted data |
| 16 | Same with `--sql @extract-books.sql --format csv -o crawl-results.csv` | CSV file written |
| 17 | Same with `--sql @extract-books.sql --format json` | JSON output |
| 18 | Same with link discovery `-d 1 -ol "a" -olp "catalogue" -tl 10 --refresh --store-content` | 0 pages found |
| 19 | `BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1 ./cli/.../browser4-cli crawl list` | 70 tracked tasks, ALL "pending" |

### Major Steps

1. Learned commands from `--help`, `crawl --help`, and `cli/README.md`
2. Attempted link discovery crawl — consistently returned 0 pages
3. Verified site reachability with `goto` and `snapshot` (confirmed links exist)
4. Checked `crawl list` — found 66 stale pending tasks congesting the queue
5. Restarted server — Maven build failure blocked local JAR usage
6. Switched to installed runtime (`BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1`)
7. Discovered `crawl clear`/`crawl cancel` exist in source code but are not discoverable
8. Discovered link discovery mode is broken; bulk fetch mode works
9. Successfully used seed-file + depth 0 for the crawl
10. Added X-SQL extraction to demonstrate all three output formats
11. Verified CSV output file on disk
12. Listed crawl tasks to verify tracking

### Workarounds Required

- **Link discovery broken:** Had to use `--seed-file` with `--depth 0` instead of URL + `--out-link-selector`
- **Maven version:** Had to use `BROWSER4_CLI_FORCE_REMOTE_BUNDLE=1` to bypass local JAR build
- **Queue congestion:** Had to restart server to clear the backlog (66 stale pending tasks)
- **crawl-clear unavailable:** No way to clear individual tasks or the queue from CLI
- **CSS selector format:** Could never verify if `-ol` selectors are correctly transmitted to the server

---

## C. Issues Found

---

### Issue 1: Link Discovery Mode (depth >= 1) Returns 0 Pages

**Severity:** Critical

**Category:** Reliability

**Reproduction:**
```
browser4-cli crawl "https://books.toscrape.com/" -d 1 -ol "a" -tl 5
browser4-cli crawl "https://httpbin.org/links/10" -d 1 -ol "a" -tl 3
browser4-cli crawl "https://books.toscrape.com/" -d 1 -ol "a[href*='catalogue']" -tl 10
browser4-cli crawl "https://books.toscrape.com/" -d 1 -ol "article.product_pod h3 a" -tl 5
```
All return: `Crawl completed. 0 pages found.`

**Expected:** Links matching the CSS selector should be discovered on the page, loaded, and reported. The books.toscrape.com homepage has dozens of links matching `a[href*='catalogue']`.

**Actual:** All link discovery crawls return 0 pages found, regardless of the CSS selector or target site. The same server can successfully load and extract data from pages in bulk fetch mode (depth 0 + seed-file).

**Root Cause:** The `--out-link-selector` value is packaged into a LoadOptions `-args` string (e.g., `-outLink "a[href*='catalogue']"`) and sent to the server. The server appears to not parse or apply these LoadOptions for link discovery. The `submit_crawl` function correctly receives a task ID and polls successfully, but the server returns an empty pages array. This suggests the server's `CrawlController` either ignores the `outLink` LoadOption or fails to resolve links from it. The installed server version is v4.11.17; the CLI is v0.1.28 — a version mismatch or bug in the server's crawl handler is the likely cause.

**Code Pointer:** `cli/browser4-cli/src/commands.rs:2136-2137` — where `out-link-selector` is converted to `-outLink` LoadOptions arg. Server-side fix needed in `CrawlController` or the LoadOptions parser.

**AI Suggested Improvement:**
- Investigate server-side `CrawlController.startCrawl()` to verify it correctly applies `-outLink` from the LoadOptions args string
- Add validation: if link discovery is requested (`-ol` provided, depth >= 1) but no links are found, the result should include a diagnostic message like "Link discovery enabled but no matching links found — verify your CSS selector matches elements on the page"
- Consider sending `outLink`, `outLinkPattern`, and `topLinks` as direct JSON parameters to the crawl API instead of packaging them into a LoadOptions args string, avoiding parsing ambiguity
- Add an integration test that validates link discovery works end-to-end with a known-good HTML page

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 2: crawl-clear / crawl-cancel Commands Not Discoverable or Functional

**Severity:** High

**Category:** Discoverability / Product

**Reproduction:**
```
browser4-cli crawl clear
browser4-cli crawl-clear
browser4-cli --help | grep -i "cancel\|clear"
```

**Expected:** `crawl clear` and `crawl cancel` should be listed in `crawl --help` and `--help` output. Running them should clear/cancel crawl tasks. They should work as documented in the source code dispatch table (main.rs lines 10934-10939).

**Actual:**
1. `crawl clear` produces: `Unknown command: 'crawl-clear'`
2. `crawl-clear` produces: `Unsupported command form: crawl-clear. Use 'browser4-cli crawl clear' instead.`
3. Neither the `--help` output nor `crawl --help` mentions `cancel` or `clear` subcommands
4. `crawl list` shows all tasks as "pending" with no way to clean up

**Root Cause:** The command definitions `crawl-clear` and `crawl-cancel` are present in the dispatch table (main.rs lines 10934-10939) and in `rewrite_prefixed_command` (line 9084), but they are NOT registered in `commands.rs`'s `commands_map()`. The dispatch flow at main.rs line 10232-10253 checks the commands map first, and if the rewritten command (e.g., `crawl-clear`) is not found, it prints "Unknown command" before reaching the dispatch match statement. The fix requires adding CommandDef entries for `crawl-clear` and `crawl-cancel` in commands.rs.

**Code Pointer:** `cli/browser4-cli/src/commands.rs` — add `crawl-clear` and `crawl-cancel` CommandDef entries. Also `cli/browser4-cli/src/help.rs` — include these in help output.

**AI Suggested Improvement:**
- Add `crawl-clear` and `crawl-cancel` (and `crawl-status`, `crawl-result`) CommandDef entries in `commands.rs` so they pass the dispatch validation at main.rs line 10232
- Include them in the `crawl --help` output and the main `--help` command listing
- Consider adding a `--prune` or `--clear` flag to `crawl list` for bulk cleanup: `crawl list --clear-completed`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 3: Crawl Task Status Always Shows "pending" — Never Updates to Completed

**Severity:** High

**Category:** Reliability

**Reproduction:**
1. Run any successful crawl (e.g., `crawl --seed-file urls.txt -d 0` — 3 pages found)
2. Run `browser4-cli crawl list`
3. Observe the task status

**Expected:** Completed crawl tasks should show "completed" or "3 pages" status. Tasks that returned promptly (0 pages) should show their final status, not "pending".

**Actual:** All 70 tracked tasks show `STATUS: pending`, including those that completed with "Crawl completed. 3 pages found." and those that returned "0 pages found." There is no way to distinguish completed from truly pending tasks.

**Root Cause:** Looking at the code at main.rs lines 6741-6747, the `update_async_task_status` call updates the local tracking file with the status. However, `crawl list` reads from a different source or the status update is not persisted correctly. The status should change from "pending" to something like "completed (3 pages)" or "0 pages found." The track file may not be written/read properly on Windows, or there's a path mismatch between write and read.

**Code Pointer:** `cli/browser4-cli/src/main.rs:6741-6747` — `update_async_task_status` call. Also check `cli/browser4-cli/src/state.rs` for the async task tracking implementation.

**AI Suggested Improvement:**
- Debug the `update_async_task_status` function to verify it writes to the correct file and that `crawl list` reads from the same file
- Add a test that submits a crawl, waits for completion, and verifies `crawl list` shows the correct status
- Consider differentiating terminal statuses: "completed", "failed", "timeout", "cancelled" vs "pending", "running"

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 4: Maven Version Incompatibility Blocks Local Dev Server Startup

**Severity:** High

**Category:** Installation & Setup

**Reproduction:**
1. Have Apache Maven 3.0.2 (or any version < 3.6.3) installed
2. Run `browser4-cli stop` to stop the server
3. Run any command that triggers auto-start (e.g., `browser4-cli goto`)
4. Observe build failure

**Expected:** The `mvnw` wrapper (bundled with the project) should be used instead of the system `mvn` to ensure a compatible Maven version. Or, if the existing JAR is up-to-date, no rebuild should be needed.

**Actual:** The `build-runtime-bundle.ps1` script uses the system `mvn` directly instead of `./mvnw`. Maven 3.0.2 fails with: `The plugin org.apache.maven.plugins:maven-dependency-plugin:3.9.0 requires Maven version 3.6.3`. After a `stop`, restarting triggers a rebuild even though the JAR was already built and used successfully before stopping.

**Root Cause:** `browser4-apps/browser4-bundle/build-runtime-bundle.ps1` uses the system `mvn` command, not the project-bundled Maven wrapper (`mvnw`). Additionally, the daemon's auto-start logic re-triggers the build script after a stop/restart cycle even when the JAR hasn't changed.

**Code Pointer:** `browser4-apps/browser4-bundle/build-runtime-bundle.ps1` — replace `mvn` with `./mvnw` or auto-detect wrapper. `cli/browser4-cli/src/daemon.rs` — add logic to skip rebuild when JAR exists and source hasn't changed.

**AI Suggested Improvement:**
- Update `build-runtime-bundle.ps1` to use `./mvnw` (Maven wrapper) instead of system `mvn`, or auto-detect and prefer the wrapper
- Add a hash/signature check in the daemon to skip rebuild when the existing JAR matches the current source
- Document the Maven version requirement (3.6.3+) in the README and troubleshooting guide
- Consider bundling Maven wrapper verification into `browser4-cli doctor`

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 5: Crawl Task Queue Persists Across Sessions with No Automatic Cleanup

**Severity:** Medium

**Category:** UX / Reliability

**Reproduction:**
1. Run many crawl commands over multiple sessions
2. Run `browser4-cli crawl list`
3. Observe 70 tracked tasks, all "pending"
4. New crawls get stuck behind stale tasks in the queue

**Expected:** Stale/completed tasks should be automatically cleaned up after a configurable TTL, or at least not block new tasks. There should be a way to view only active/in-progress tasks.

**Actual:** All tasks from all sessions accumulate indefinitely. With 66 stale "pending" tasks in the queue, new crawls show "Still waiting for crawl to start... (N s elapsed). If the queue is congested, try stopping old tasks or using --background." — but there's no way to stop old tasks since `crawl cancel` is broken (Issue 2).

**Root Cause:** The crawl task tracking is file-based and persists across sessions. There's no automatic pruning of old/stale tasks. The crawl queue on the server side processes tasks in FIFO order, so a backlog of stale tasks blocks new ones.

**Code Pointer:** `cli/browser4-cli/src/state.rs` — async task persistence. Server-side `CrawlController` — queue processing order.

**AI Suggested Improvement:**
- Auto-prune completed/failed crawl tasks older than 24 hours
- Skip or deprioritize tasks that point to unreachable hosts
- Add a `--status` filter to `crawl list` (e.g., `crawl list --status pending` vs `--status completed`)
- Add a `crawl prune` command to clean up completed tasks

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 6: X-SQL Extraction Returns Empty Data for Some Pages Without Diagnostic

**Severity:** Medium

**Category:** Reliability

**Reproduction:**
1. Create a seed file with 3 book detail page URLs
2. Run: `crawl --seed-file books-seed-urls.txt -d 0 --refresh --sql @extract-books.sql --format table`
3. Observe that 2 of 3 pages have empty extracted data

**Expected:** All 3 pages should return title and price, or clear error messages explaining why extraction failed for specific pages.

**Actual:** Two pages returned empty strings for `title` and `price` with no explanation:
```
  price   | title      | url
  --------+------------+----------------------------------------------------------------
          |            | https://books.toscrape.com/catalogue/a-light-in-the-attic...
          |            | https://books.toscrape.com/catalogue/tipping-the-velvet...
  £50.10  | Soumission | https://books.toscrape.com/catalogue/soumission_998...
```

**Root Cause:** The first two pages may have been fetched earlier (before `--refresh` was used) and cached without content. The server returns empty extracted data instead of reporting a cache hit or fetch failure. The `--refresh` flag should invalidate the cache, but it may not work correctly for previously-failed page loads.

**Code Pointer:** Server-side `CrawlController` — page fetch and caching logic. CLI-side `handle_crawl` in `main.rs` — could add warnings when extracted data is empty for some pages.

**AI Suggested Improvement:**
- When a page returns empty extraction results, include a diagnostic note: "Warning: 2 of 3 pages returned no extracted data (possibly cached empty responses — try with --refresh)"
- Ensure `--refresh` properly invalidates ALL cached state for the requested URLs
- Add per-page status to crawl results (e.g., "loaded, no match" vs "cached, no match" vs "error")

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 7: --args Flag Cannot Handle Spaces in Values

**Severity:** Medium

**Category:** CLI Experience

**Reproduction:**
```
browser4-cli crawl "https://example.com" -d 1 -a "-outLink a -topLinks 3 -refresh"
```

**Expected:** The args string should be passed as-is to the server.

**Actual:** `Error: error: too many arguments: expected 1, received 2`

**Root Cause:** The `-a` flag is defined as taking a single string argument, but the shell splits `-outLink a -topLinks 3 -refresh` into multiple arguments. The equals-sign workaround (`-a=-outLink...`) submits successfully but doesn't fix the underlying link discovery issue. Even with equals sign, the args string may still be parsed incorrectly by the CLI arg parser if it contains spaces.

**Code Pointer:** `cli/browser4-cli/src/commands.rs:2173-2175` — how `--args` value is appended to `load_opts`. The CLI argument parser may need to handle quoted multi-word values.

**AI Suggested Improvement:**
- Support quoted values: `-a "-outLink a -topLinks 3"` should be treated as a single argument
- Document the need for `=` syntax with multi-word args in the help text
- Add a validation warning if the args string contains characters that might cause parsing issues

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 8: Crawl Link Discovery Documentation Gap — No Diagnostic Help for "0 pages found"

**Severity:** Medium

**Category:** Documentation / UX

**Reproduction:**
1. Run any link discovery crawl that returns "0 pages found"
2. Look for diagnostic information in the output

**Expected:** The output should include actionable diagnostics: "Link discovery enabled but no matching links found. Verify your CSS selector matches elements on the page. Try `browser4-cli snapshot -u` to see available links."

**Actual:** The output simply says "Crawl completed. 0 pages found." with no indication of WHY no pages were found. The documentation (crawl.md line 296) does mention this case but no user-facing diagnostic is shown. A new user would not know if:
- The CSS selector was wrong
- The regex pattern filtered everything out
- The server failed silently
- The site was unreachable

**Root Cause:** The "0 pages found" message (main.rs line 6716) is generic and does not differentiate between "no links discovered" and "no pages could be loaded." The crawl.md reference doc (lines 26-28, 296) provides guidance but this isn't surfaced in the CLI output.

**Code Pointer:** `cli/browser4-cli/src/main.rs:6716` — add context-dependent diagnostics.

**AI Suggested Improvement:**
- Differentiate the "0 pages found" message: "0 pages found (seed page could not be loaded)" vs "0 pages found (no links matched your selector and pattern)"
- When link discovery returns 0 links, suggest: "Tip: Run `browser4-cli snapshot -u` to inspect available links on the page"
- Add a `--verbose` flag to the crawl command that shows which links were considered and filtered out

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 9: crawl list Description Column Truncated Without Ellipsis Context

**Severity:** Low

**Category:** UX

**Reproduction:**
1. Run `browser4-cli crawl list`
2. Observe truncated URLs in the description column

**Expected:** URLs should either be fully displayed or truncated with enough context to identify the task.

**Actual:** URLs are truncated to ~37 characters with `…` appended, but the column width varies and important URL path segments are lost:
```
b9b91075-f0b0-4d50-90c5-f1fad30b1200  crawl     https://books.toscrape.com/catalogue/a-…  pending
```

**Root Cause:** The task description is truncated to a fixed width without considering the URL structure. The most distinguishing part of the URL (the path suffix) is lost.

**Code Pointer:** `cli/browser4-cli/src/main.rs` — `handle_crawl_list` function and its formatting logic.

**AI Suggested Improvement:**
- Increase the description column width or make it dynamic based on terminal width
- Add a `--full` flag to `crawl list` that shows complete URLs
- Show task age or submission time to help identify stale vs. fresh tasks

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 10: No Built-in Way to Discover Suitable CSS Selectors for out-link-selector

**Severity:** Low

**Category:** Discoverability

**Reproduction:**
1. Navigate to a page
2. Try to determine the correct CSS selector for `--out-link-selector`
3. Look for guidance in help/docs

**Expected:** A built-in workflow or suggestion for discovering link selectors: "Use `htmlsnapshot inspect` to discover repeating link patterns, or `snapshot --urls` to list available links."

**Actual:** The user must manually inspect the page (snapshot, htmlsnapshot, or browser dev tools) and guess the correct CSS selector. The `htmlsnapshot inspect` command exists and can discover repeating patterns, but there's no cross-reference from `crawl --help`.

**Root Cause:** The crawl documentation doesn't reference the selector discovery tools (`htmlsnapshot inspect`, `snapshot -u`). A new user would need to discover these commands independently.

**Code Pointer:** `cli/browser4-cli/src/help.rs` — crawl help text. `cli/browser4-cli/src/commands.rs` — crawl command description.

**AI Suggested Improvement:**
- Add a tip to the crawl help: "Tip: Use `htmlsnapshot inspect` to discover CSS selectors for repeating content patterns"
- Add an example showing how to use `snapshot -u` to verify links before crawling
- Consider an `--auto-discover` flag for crawl that uses `htmlsnapshot inspect` internally

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 11: HTTP to HTTPS Redirect Not Handled in Crawl URL

**Severity:** Low

**Category:** UX / Reliability

**Reproduction:**
1. Run `browser4-cli crawl "http://books.toscrape.com/"` (with `-ol "a"`)
2. Site redirects to `https://books.toscrape.com/`
3. Observe result

**Expected:** The crawl should follow the redirect and crawl the HTTPS version, or at least warn about the redirect.

**Actual:** The crawl completed but returned 0 pages. It's unclear whether the redirect was followed. The `goto` command DOES follow the redirect (the opened page shows `https://`), but the crawl may have handled it differently. The seed URL starts as HTTP but the visited URLs (when checked via `snapshot`) show HTTPS.

**Root Cause:** The crawl server may not follow redirects when loading seed URLs, or the URL normalization treats HTTP and HTTPS as different URLs, causing a mismatch.

**Code Pointer:** Server-side `CrawlController` and LoadOptions URL resolution.

**AI Suggested Improvement:**
- Ensure the crawl follows HTTP→HTTPS redirects for seed URLs
- Add a warning if the final URL differs from the seed URL: "Redirected: http://books.toscrape.com/ → https://books.toscrape.com/"
- Document redirect behavior in the crawl reference

**Human Review:**
- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- **Notes:**

---

### Issue 12: Backend Auto-Restart Triggers Unnecessary Maven Rebuild

**Severity:** Low

**Category:** Reliability

**Reproduction:**
1. Use the server successfully (local JAR auto-started fine)
2. Run `browser4-cli stop`
3. Run any command that auto-starts the server
4. Watch the build fail because of Maven version incompatibility

**Expected:** If the JAR was already built and used successfully, restarting should reuse it without rebuilding. The daemon should detect that the JAR is current and skip the Maven build.

**Actual:** After stopping and restarting, the daemon re-runs the build script, which fails due to Maven version incompatibility — even though a working JAR already exists at the expected path.

**Root Cause:** The daemon's startup logic (`daemon.rs`) re-evaluates whether to build the bundle on every start, rather than checking if the existing JAR is sufficient.

**Code Pointer:** `cli/browser4-cli/src/daemon.rs` — server startup sequence.

**AI Suggested Improvement:**
- Before running the build script, check if the target JAR exists and is newer than the source files
- Add a `--no-rebuild` flag to skip the build step and use the existing JAR as-is
- Cache the last successful build hash and skip rebuild if source hasn't changed

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
⚠️ **Partially completed** — Bulk fetch and X-SQL extraction worked correctly with all three output formats (table, CSV, JSON). The seed file crawl also worked. However, the link discovery mode (the core `crawl` feature) is completely broken, forcing the use of workarounds (pre-collected seed URLs instead of discovered links).

### Task Step Summary

| Step | Status | Notes |
|---|---|---|
| 1. Crawl depth 1 with -ol, -olp, -tl, table | ❌ Blocked | Link discovery returns 0 pages |
| 2. Re-run with CSV + --output | ✅ Workaround | Used seed-file + depth 0 instead |
| 3. Re-run with JSON format | ✅ Workaround | Used seed-file + depth 0 instead |
| 4. Seed file with 2-3 book URLs | ✅ Complete | Bulk fetch mode works correctly |
| 5. List crawl tasks | ✅ Complete | But all statuses show "pending" |

### Estimated Task Success Rate
**20%** — A new user following the documented crawl workflow (URL + --out-link-selector) would fail immediately. The bulk fetch workaround (seed-file + depth 0) works but is a different feature than what the task explicitly requests.

### Number of Issues Found: 12
- **Critical:** 1 (link discovery broken)
- **High:** 3 (hidden commands, status tracking, Maven incompatibility)
- **Medium:** 4 (queue congestion, empty extraction, --args parsing, diagnostics)
- **Low:** 4 (truncated output, selector discovery, redirect handling, unnecessary rebuild)

### Major Blockers
1. **Link discovery is completely non-functional** — the primary feature of `crawl` with depth >= 1
2. **Cannot clear or cancel crawl tasks** — the queue accumulates stale tasks with no cleanup mechanism
3. **Local dev server startup is broken** — Maven version incompatibility prevents local JAR builds
4. **Task status tracking is broken** — all tasks show "pending" forever

### Most Confusing Aspects
1. "Crawl completed. 0 pages found." gives no hint about WHY no pages were found — is it the selector, the pattern, the site, or a server bug?
2. `crawl clear` exists in source code but both `crawl clear` and `crawl-clear` fail with different error messages — neither works
3. The relationship between `--out-link-selector` (CLI flag) and `-outLink` (LoadOptions arg) is invisible to the user; there's no way to verify the correct args string is being constructed
4. Bulk fetch works but link discovery doesn't — same server, same URLs — makes debugging very confusing

### What Worked Well
1. **Bulk fetch mode (depth 0 + seed-file):** Reliably loaded and processed pages
2. **X-SQL extraction:** All three formats (table, CSV, JSON) produced correct output
3. **CSV --output:** File was written correctly to disk
4. **crawl --help:** Comprehensive documentation of all flags and modes
5. **crawl.md reference:** Excellent in-depth documentation with examples
6. **Backend auto-start (when it works):** Seamless server lifecycle management
7. **`swarm submit` as alternative:** Worked when crawl link discovery didn't

### Most Valuable Improvements
1. **Fix link discovery** — it's the primary crawl feature and doesn't work
2. **Register crawl subcommands** (clear, cancel, status, result) in the commands map so they're discoverable and functional
3. **Fix task status tracking** — completed tasks should not show "pending"
4. **Add diagnostic output for "0 pages found"** — tell the user WHY: bad selector? no matches? server error?
5. **Use Maven wrapper for local builds** — the project has `mvnw` but doesn't use it

### Overall Usability Rating: **4.5 / 10**

**Strengths:** Excellent documentation (crawl.md reference), clean help output, flexible output formats (table/CSV/JSON), bulk fetch mode works reliably, good X-SQL integration, swarm infrastructure is solid.

**Weaknesses:** Core feature (link discovery) is broken, critical commands are hidden/broken, task lifecycle management is absent, local dev setup has environmental dependencies that aren't handled, diagnostic messages are unhelpful.

**Verdict:** The crawl subsystem shows thoughtful design in its documentation and output formatting, but the implementation has critical reliability gaps that make the primary use case (link discovery) unusable. The bulk fetch + X-SQL extraction works well and could serve as a solid foundation. Fixing link discovery, completing the command registration, and adding proper task lifecycle management would transform this from a 4.5 to an 8+ experience.

---

*Generated with Claude Code as part of browser4-cli usability evaluation*
