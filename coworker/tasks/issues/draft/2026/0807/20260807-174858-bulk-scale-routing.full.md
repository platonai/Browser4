---

# A. Task Result

**Partially Successful.** 4 of 6 acceptance criteria completed successfully; 2 encountered persistent infrastructure-level failures in the local environment.

| AC | Status | Summary |
|----|--------|---------|
| AC1 | ✅ Complete | `htmlsnapshot query` with `DOM_LOAD_AND_SELECT` extracted 6 product rows (title + price + rating + URL) from the listing page |
| AC2 | ✅ Complete | `crawl --depth 0 --seed-file` with X-SQL extracted 3 product rows from seed URLs |
| AC3 | ⚠️ Partial | Link discovery concept demonstrated via `htmlsnapshot get all attr` — `a.product` selector correctly isolates 3 product links while `a.category-link` (3 links) and `a.utility-link` (3 links) are excluded. However, `crawl --depth >= 1` fails to load `/generated/crawl/index.html` (timeout), and `crawl --depth 0` on the product pages fails for 2 of 3 products with "Protocol not found (1600)" |
| AC4 | ❌ Failed | `swarm create` succeeds but worker pool never picks up tasks — all jobs stay "queued" indefinitely. Root cause: swarm browser session is "unhealthy" (active=false, driver=N/A) |
| AC5 | ✅ Complete | Loop with `--shell` wrapping `./b4w.sh -s price-watch eval "..."` correctly executed 2 iterations returning `$899.99` each time. Subcommand mode (`-- eval ...`) is broken with multi-word arguments |
| AC6 | ✅ Complete | Bash `for` loop with `goto` → `htmlsnapshot` → `htmlsnapshot get text` correctly extracted title and price from 3 product URLs |

# B. Execution Trace

## Commands Used (core sequence)

1. `./b4w.sh help` — read CLI help
2. `pwsh ./bin/test.ps1 mock-site -Dmock.site.port=18080` — start MockSite
3. AC1: `./b4w.sh goto "http://localhost:18080/ec/b?node=1292115012"` → `htmlsnapshot inspect` → wrote `.test-sessions/ac1-listing-query.sql` → `htmlsnapshot query ... --sql @.test-sessions/ac1-listing-query.sql`
4. AC2: wrote `.test-sessions/ac2-seeds.txt` + `.test-sessions/ac2-extract.sql` → `crawl --seed-file ... --depth 0 --sql @... --format table --refresh`
5. AC3: multiple `crawl ... --depth 1 --out-link-selector "a.product"` attempts (all timed out on `/generated/crawl/`) → workaround: `goto` + `htmlsnapshot get all attr "a.product" href` confirmed link isolation → wrote seed file from discovered links → `crawl --depth 0 --seed-file ... --sql @...`
6. AC4: `swarm create --display-mode HEADLESS ...` → `swarm query --sql @... --seed-file ... --wait` (×3 attempts with different configs; all tasks stuck "queued")
7. AC5: `-s price-watch goto ...` → `loop --name ... --count 2 -i 10 --shell './b4w.sh -s price-watch eval "..."'` (workaround for broken `--` subcommand mode)
8. AC6: `for url in ...; do ./b4w.sh goto "$url"; ./b4w.sh htmlsnapshot; ./b4w.sh htmlsnapshot get text ...; sleep 2; done`

## Key Workarounds

- **Invocation syntax**: Task instructions said `$(./b4w.ps1)` which is bash command substitution (fails). Used `./b4w.sh` per SKILL.md guidance for Linux
- **DOM_TEXT → DOM_FIRST_TEXT**: SKILL.md quickstart template uses `DOM_TEXT` but actual function is `DOM_FIRST_TEXT`
- **Loop subcommand mode**: `-- eval "..."` fails with "too many arguments"; worked around with `--shell './b4w.sh -s name eval "..."'`
- **AC3 crawl timeout**: Used `htmlsnapshot get all attr` to demonstrate selector isolation, then depth-0 seed file crawl for extraction

## Important Decisions

- Restarted Browser4 server (`stop` then `goto`) between attempts to clear stuck workers
- Used non-headless swarm after headless mode failed
- All temporary files placed in `.test-sessions/` as required

---

```json
{
  "issues": [
    {
      "title": "SKILL.md quickstart template uses DOM_TEXT but actual X-SQL function is DOM_FIRST_TEXT",
      "severity": "High",
      "category": "Documentation",
      "reproduction": "Copy the X-SQL template from SKILL.md §4e: SELECT DOM_TEXT(DOM, 'h2') AS title FROM DOM_LOAD_AND_SELECT(@url, '.product-card'). Run against any page.",
      "expected": "Query executes successfully and returns text values.",
      "actual": "statusCode 417 with 'Data conversion error converting ...'. The DOM_TEXT function does not exist; the correct function is DOM_FIRST_TEXT.",
      "rootCause": "SKILL.md §4e 'X-SQL Quickstart Template' uses DOM_TEXT() in its example, but the actual X-SQL DomSelectFunctions expose DOM_FIRST_TEXT, DOM_ALL_TEXTS, DOM_NTH_TEXT. DOM_TEXT is not a valid function name. This is the #1 user mistake with element-scoped eval-equivalent in X-SQL per the SKILL.md warning section.",
      "codePointer": "skills/browser4-cli/SKILL.md:298-304 — the X-SQL Quickstart Template section uses DOM_TEXT instead of DOM_FIRST_TEXT",
      "suggestion": "- Update SKILL.md §4e quickstart template to use DOM_FIRST_TEXT(DOM, 'h2') instead of DOM_TEXT(DOM, 'h2')\n- Add DOM_TEXT as an alias in the X-SQL engine for backward compatibility\n- Add a 'Common mistakes' entry: 'Function not found' → 'Use DOM_FIRST_TEXT not DOM_TEXT'"
    },
    {
      "title": "Task instructions require $(./b4w.ps1) invocation which is bash command substitution — fails on Linux",
      "severity": "High",
      "category": "Documentation",
      "reproduction": "In bash on Linux: $(./b4w.ps1) help",
      "expected": "The browser4-cli help output.",
      "actual": "/bin/bash: line 1: b4w: command not found. $(./b4w.ps1) resolves to the string 'b4w' (the script output), and bash tries to execute 'b4w help' which fails because b4w is not in PATH.",
      "rootCause": "The task instructions template uses $(./b4w.ps1) <command> syntax which is bash command substitution — it runs ./b4w.ps1 and substitutes its output ('b4w') as a command name. This is explicitly warned against in SKILL.md line 26-28. The task instructions should be platform-aware or use ./b4w.sh.",
      "codePointer": "",
      "suggestion": "- Update evaluation task templates to use ./b4w.sh on Linux/macOS and ./b4w.ps1 on PowerShell\n- Add platform detection to task templates: 'if bash, use ./b4w.sh; if pwsh, use ./b4w.ps1'\n- Remove the $(...) wrapper entirely — it's never correct in any shell"
    },
    {
      "title": "Crawl depth >= 1 fails to load /generated/crawl/index.html with timeout after 300s",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./b4w.sh crawl 'http://localhost:18080/generated/crawl/index.html' --depth 1 --out-link-selector 'a.product' --refresh",
      "expected": "Page loads, 3 product links discovered, product pages crawled.",
      "actual": "Timeout after 300s: 'Timed out waiting for 300000 ms'. The start page never loads in the crawler. However, goto (interactive browser) and curl both load the page instantly (10ms, 5KB).",
      "rootCause": "The crawl engine's page loading mechanism (WebDriver/FetchComponent) fails to load pages from the /generated/crawl/ path on localhost. Server logs show 'Protocol not found (1600)' errors. This is likely a protocol handler registration issue — the internal HTTP client uses different protocol handlers based on URL paths, and /generated/ is not properly registered. Additionally, the first attempt worked (loaded page, 0 links found), but subsequent attempts all timeout — suggesting a worker pool deadlock after initial failure.",
      "codePointer": "",
      "suggestion": "- Investigate FetchComponent protocol handler registration for localhost paths\n- Add better error messages when a page load fails (not just generic 'timed out')\n- Implement worker pool health checks to detect and recover from stuck workers\n- Consider falling back to the interactive browser loading mechanism when the internal fetcher fails"
    },
    {
      "title": "Swarm worker pool never picks up tasks — all jobs stuck 'queued' indefinitely",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "1) swarm create --max-browser-contexts 1 --max-open-tabs 2\n2) swarm query 'http://localhost:18080/ec/dp/B0E000001' --sql @query.sql --refresh --wait",
      "expected": "Task transitions from 'queued' → 'processing' → 'completed' with extracted data.",
      "actual": "All tasks stay 'queued' forever. After 300s --wait timeout: '0 of 1 job(s) completed'. Server logs show: 'Session SWARM is unhealthy: session active=false, browser healthy=N/A, driver healthy=N/A'. The swarm browser context never initializes.",
      "rootCause": "The swarm session's underlying browser driver/Chrome instance fails to initialize. Server logs show 'session active=false' and 'browser healthy=N/A, driver healthy=N/A' immediately after creation. The DISPLAY environment variable is detected (:0) but the Chrome browser for swarm never starts successfully. The loading web driver pool metrics show only 1 offer and 1 success, suggesting the pool can create a session but the session is immediately unhealthy.",
      "codePointer": "",
      "suggestion": "- Add explicit health check on swarm session creation — fail fast if browser/driver are unhealthy\n- Surface the 'session unhealthy' warning to the CLI user (currently only visible in server logs)\n- Implement automatic retry with backoff for swarm browser context initialization\n- Add a 'swarm doctor' command to diagnose why browser contexts won't start\n- Consider detecting the 'chrome not available' condition and suggesting fallback to crawl --depth 0"
    },
    {
      "title": "Loop subcommand mode (--) fails with multi-word arguments",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.sh loop --name test --count 1 -i 10 -- -s price-watch eval 'document.title'",
      "expected": "Loop runs eval on the named session.",
      "actual": "Error: too many arguments: expected 1, received 3. The loop parser treats '-s', 'price-watch', and 'eval' as separate task arguments when it expects exactly 1.",
      "rootCause": "The loop command's argument parser expects exactly 1 argument after -- (treated as a single command). Multi-word subcommands fail because the parser doesn't collect all remaining args into a subcommand array. This contradicts the documentation which shows examples like 'loop -- eval \"document.title\" -i 300' with multiple words.",
      "codePointer": "cli/browser4-cli/src/ (loop argument parser — the subcommand arg collection logic)",
      "suggestion": "- Fix the loop argument parser to collect ALL args after -- as subcommand arguments, not just 1\n- Add a test for multi-word subcommand: loop -- status --json\n- Document the workaround: use --shell mode wrapping browser4-cli call for complex subcommands"
    },
    {
      "title": "htmlsnapshot capture times out on listing page with 60s default timeout",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "goto 'http://localhost:18080/ec/b?node=1292115012' then htmlsnapshot",
      "expected": "HTML snapshot captured within a few seconds.",
      "actual": "HTTP request timed out [tool=html_snapshot_capture, timeout=60s]. However, htmlsnapshot query (which uses DOM_LOAD_AND_SELECT/@url independent of stored snapshot) works fine.",
      "rootCause": "The htmlsnapshot capture tool uses a 60s default timeout and fails on the e-commerce listing page. The page is small (10KB) but the capture operation may involve additional processing that exceeds 60s. The error message doesn't explain why it timed out or suggest alternatives.",
      "codePointer": "",
      "suggestion": "- Increase the default htmlsnapshot capture timeout to match other tools (120s+)\n- Add a note in the error message: 'try htmlsnapshot query with @url instead — it fetches pages independently'\n- Consider making the timeout configurable per-invocation"
    },
    {
      "title": "X-SQL result columns appear twice — once uppercase (null) and once lowercase (with values)",
      "severity": "Low",
      "category": "Product",
      "reproduction": "Run any htmlsnapshot query with AS lowercase aliases: SELECT DOM_FIRST_TEXT(DOM, '.title') AS title FROM DOM_LOAD_AND_SELECT(@url, '.card')",
      "expected": "Single set of columns with the specified alias names.",
      "actual": "resultSet contains both TITLE: null and title: 'actual value'. The H2 SQL engine creates both uppercase (identifier) and lowercase (alias) versions of each column.",
      "rootCause": "H2 SQL engine normalizes unquoted identifiers to uppercase but preserves alias casing. The query SELECT DOM_FIRST_TEXT(...) AS title produces both TITLE (H2's normalized form) and title (the alias). The JSON serialization includes both keys.",
      "codePointer": "Browser4 backend — the H2 SQL result set serialization to JSON",
      "suggestion": "- Filter duplicate columns in the JSON serialization, keeping only the lowercased alias form\n- Consider using quoted identifiers consistently to avoid H2's case normalization\n- Document this behavior so users know to expect duplicate keys"
    },
    {
      "title": "Swarm session hijacks default browser session",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1) goto any URL (creates DEFAULT session)\n2) swarm create\n3) list",
      "expected": "Separate isolated sessions: DEFAULT (interactive browser) and SWARM (swarm session).",
      "actual": "The DEFAULT session is replaced by SWARM. list shows only: (default) | SWARM. After swarm close, goto creates a new DEFAULT session, but while swarm is active, the interactive browser session is unavailable.",
      "rootCause": "Swarm creates a session with sessionId=SWARM that also registers as the default session, replacing the existing DEFAULT session. The swarm reference mentions 'The swarm session uses fixed session ID SWARM — it doesn't share state with named or default sessions' but in practice it occupies the default session slot.",
      "codePointer": "",
      "suggestion": "- Keep the DEFAULT interactive session separate from the SWARM session\n- Allow both to coexist: 'list' should show both DEFAULT and SWARM\n- Auto-restore the previous DEFAULT session when swarm closes\n- Document the session takeover behavior and its implications for mixed interactive/swarm workflows"
    },
    {
      "title": "Version mismatch warning on every command",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any browser4-cli command: status, goto, etc.",
      "expected": "Clean output without version mismatch warnings.",
      "actual": "⚠ Version mismatch: CLI is 4.13.0 but running backend is 4.13.0-SNAPSHOT. Rebuild both to match: mvn install ... This appears on every command output.",
      "rootCause": "The local build produces 4.13.0-SNAPSHOT backend JAR but the CLI Cargo.toml is versioned 4.13.0. In dev mode from source, these should be considered compatible.",
      "codePointer": "cli/browser4-cli/ (CLI version check) and browser4-rest/ (backend version)",
      "suggestion": "- In dev mode, treat SNAPSHOT suffixes as matching their release version (4.13.0-SNAPSHOT ≈ 4.13.0)\n- Show the warning only once per session, not on every command\n- Or: align the version strings so dev mode shows no warning"
    },
    {
      "title": "First-run startup takes 10-13 seconds — acceptable but spinner feedback is verbose",
      "severity": "Low",
      "category": "UX",
      "reproduction": "First browser4-cli command after server stop or fresh start.",
      "expected": "Quick startup with concise progress indication.",
      "actual": "~40 lines of spinner output showing JVM loading → TCP port waiting → Spring Boot startup → MCP tools ready. The spinner changes every 100ms producing dense output. Total: ~13s with ~120 spinner frames.",
      "rootCause": "The spinner renders at 10fps with stage-level messages. For a 13s startup, this produces many lines of output that obscure the actual command result. The stage transition messages are useful but the per-frame spinner output is excessive.",
      "codePointer": "cli/browser4-cli/src/ (server startup progress display)",
      "suggestion": "- Use a single-line animated spinner (overwrite in place) instead of new-line-per-frame\n- Show only stage transitions (JVM → Spring Boot → MCP), not every second\n- Consider using terminal escape codes for a progress bar\n- Add a '--quiet-startup' flag that suppresses spinner output"
    },
    {
      "title": "Crawl on /generated/crawl/ path returns 'Protocol not found (1600)' for some URLs",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "crawl --seed-file (containing /generated/crawl/product/1.html through 3.html) --depth 0 --refresh",
      "expected": "All 3 product pages fetched successfully with structured extraction.",
      "actual": "Product 1: fetched 3226 bytes (after 'Protocol not found' warning → retried successfully). Product 2: 'fetch returned 0 bytes after 3 attempts — Protocol not found (1600)'. Product 3: same failure. Only 1 of 3 pages extracted.",
      "rootCause": "The FetchComponent uses protocol handlers that are not registered for the /generated/ URL path. Server logs show 'Protocol not found (1600)' for all three URLs. Product 1 succeeded on retry (the protocol handler was registered during the retry), but products 2 and 3 consistently failed. This is a race condition in protocol handler initialization. The /ec/ path works perfectly because its protocol handler is pre-registered.",
      "codePointer": "browser4-core/ FetchComponent — protocol handler registration and discovery for custom URL paths",
      "suggestion": "- Pre-register protocol handlers for all supported URL schemes/paths at server startup\n- Implement a retry-with-backoff for ProtoNotFound errors that waits for handler registration\n- Add a 'protocol handler warmup' step before accepting crawl/swarm requests\n- Surface 'ProtoNotFound' errors to the CLI user with actionable advice"
    },
    {
      "title": "No htmlsnapshot inspect output when page not yet captured — unclear error",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "Run htmlsnapshot inspect without first running htmlsnapshot (capture).",
      "expected": "Clear error: 'No HTML snapshot available. Run htmlsnapshot first.'",
      "actual": "The error message depends on server state. When the swarm session had taken over, the error was a timeout rather than a clear 'no snapshot available' message.",
      "rootCause": "The htmlsnapshot tool family has a split: query works independently (re-fetches via DOM_LOAD_AND_SELECT), but capture/inspect/get require the stored snapshot. The error messages don't clearly distinguish these two paths or tell the user which commands need capture first and which don't.",
      "codePointer": "",
      "suggestion": "- Add a table to SKILL.md showing which htmlsnapshot commands need capture first vs. which work independently\n- Improve error messages: 'No HTML snapshot in storage. Either: a) run htmlsnapshot first, or b) use htmlsnapshot query with @url for independent fetching'\n- Consider making htmlsnapshot inspect auto-capture if no snapshot exists"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — 4 of 6 ACs fully successful (AC1, AC2, AC5, AC6); AC3 demonstrated core concept via workaround; AC4 failed due to infrastructure-level swarm worker pool failure that prevented any task processing.",
    "successRate": "67% — 4 ACs fully successful, 1 partially successful with workaround (AC3), 1 failed (AC4)",
    "issuesFound": 12,
    "majorBlockers": "1) Swarm worker pool never initializes — browser session is 'unhealthy' immediately after creation, all jobs stuck 'queued' forever. 2) Crawl depth >= 1 on /generated/crawl/ path times out (300s); depth 0 on same path has 'Protocol not found (1600)' failures for 2/3 URLs. 3) X-SQL quickstart template uses wrong function name (DOM_TEXT vs DOM_FIRST_TEXT).",
    "mostConfusingAspects": "1) The disconnect between SKILL.md quickstart templates (which use DOM_TEXT) and actual working function names (DOM_FIRST_TEXT) — the most common error for new users. 2) The swarm session silently hijacking the default session with no warning — breaks mixed interactive+parallel workflows. 3) The htmlsnapshot capture requirement — query works without capture, but inspect/get require it, with unclear errors when capture is missing. 4) The $(./b4w.ps1) invocation syntax in task instructions which SKILL.md explicitly says doesn't work in bash.",
    "mostValuableImprovements": "1) Fix the X-SQL quickstart template to use DOM_FIRST_TEXT instead of DOM_TEXT. 2) Implement swarm worker pool health checks with user-visible diagnostics (not just hidden in server logs). 3) Add protocol handler pre-registration to fix ProtoNotFound(1600) errors. 4) Fix loop subcommand mode to accept multi-word arguments as documented. 5) Keep swarm and default sessions independent.",
    "usabilityRating": 6
  }
}
```
