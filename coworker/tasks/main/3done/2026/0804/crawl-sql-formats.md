# Issues: crawl-sql-formats

> **Source:** `20260804-183525-crawl-sql-formats.full.md` | **Date:** 20260804-183525 | **Mode:** dev

## Scenario Background

### Task

**Partially Successful** — Both acceptance criteria were demonstrated with a workaround:

- **AC5 (SQL from file with CSV output):** `crawl --seed-file <path> --sql @extract.sql --format csv -o results.csv` successfully produced a CSV with product title, price, and URL for both seed URLs. Required running each seed URL in a separate crawl invocation due to a protocol handler bug (see Issue 1).

- **AC6 (SQL from stdin with table format):** `crawl --seed-file <path> --sql-stdin --format table < extract.sql` successfully displayed table-formatted extraction results with identical content to the `--sql @file` method.

- **Extracted Data:**
  - Widget Alpha / $10.00 (from product/1.html)
  - Widget Beta / $20.00 (from product/2.html)

### Execution Context

**Key Commands:**

```
./b4w.sh crawl --seed-file .test-sessions/seed-single1.txt --depth 0 --sql @.test-sessions/extract-fresh.sql --format csv -o .test-sessions/r1.csv --refresh
./b4w.sh crawl --seed-file .test-sessions/seed-single2.txt --depth 0 --sql @.test-sessions/extract-fresh.sql --format csv -o .test-sessions/r2.csv --refresh
./b4w.sh crawl --seed-file .test-sessions/seed-single1.txt --depth 0 --sql-stdin --format table --refresh < .test-sessions/extract-fresh.sql
./b4w.sh crawl --seed-file .test-sessions/seed-single2.txt --depth 0 --sql-stdin --format table --refresh < .test-sessions/extract-fresh.sql
```

---

```json
{
  "issues": [
    {
      "title": "Multi-seed crawl X-SQL extraction fails for subsequent seed URLs due to protocol handler not recovering",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "1. Create a seed file with 2+ product URLs pointing to localhost MockSite\n2. Run: ./b4w.sh crawl --seed-file urls.txt --depth 0 --sql @extract.sql --format table --refresh\n3. Observe: first seed URL extracts correctly, subsequent URLs return empty fields or 0 rows",
      "expected": "All seed URLs in a multi-seed crawl should have their X-SQL extraction data populated correctly.",
      "actual": "Only the first seed URL extracts data correctly. Subsequent URLs get 0-byte responses (after 3 retries) or empty extraction fields, with 'Protocol not found' errors in the logs. Status code 1600 (ProtoNotFound) appears consistently for each subsequent seed URL.",
      "rootCause": "When a crawl processes multiple seed URLs, each seed URL creates a new session. When the first session is closed, the HTTP protocol handler is deregistered. The next session's protocol handler fails to re-register before the page load attempt, causing 'Protocol not found' (status 1600). The retry logic in CrawlService.crawlDepth0() (3 retries with 500ms delay) is insufficient to allow protocol handler re-registration. The --refresh flag, which is always added for depth=0, may exacerbate this by invalidating caches that would otherwise serve stale but usable content.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:crawlDepth0() — The SEED_INTERVAL_MS (100ms) delay between seeds and the FETCH_RETRY_DELAY_MS (500ms) may need to be increased, or the session creation logic needs to ensure protocol handler availability before attempting page loads.",
      "suggestion": "- Increase SEED_INTERVAL_MS from 100ms to 2000ms to give protocol handlers more time to re-register between seed URL sessions\n- Add a pre-flight health check (e.g., load about:blank) before the actual page load to verify the protocol handler is ready\n- Increase MAX_FETCH_RETRIES from 3 to 5 with exponential backoff (500ms, 1000ms, 2000ms, 4000ms, 8000ms)\n- Consider reusing a single session for all depth=0 seed URLs instead of creating a new session per URL\n- Add user-facing warning when protocol handler recovery fails: 'Protocol handler not ready for <url>. Try running fewer seed URLs per crawl or increasing delays between requests.'"
    },
    {
      "title": "Task instruction invocation syntax $(./b4w.ps1) incompatible with Git Bash",
      "severity": "High",
      "category": "Documentation",
      "reproduction": "In Git Bash, run: $(./b4w.ps1) help\nResult: 'b4w: command not found'",
      "expected": "The command should invoke browser4-cli correctly.",
      "actual": "$(./b4w.ps1) in bash performs command substitution — it runs ./b4w.ps1, captures its output (which is the string 'b4w' or similar), and tries to execute that as a command. This fails with 'command not found'.",
      "rootCause": "The task instructions specify $(./b4w.ps1) as the invocation method, but this is bash command substitution syntax. The SKILL.md correctly documents this issue and recommends ./b4w.sh or pwsh ./b4w.ps1 instead. The task template needs updating to use the correct invocation for the target shell.",
      "codePointer": "",
      "suggestion": "- Update task instruction templates to use ./b4w.sh <command> for bash/Git Bash environments\n- Add a platform-detection note: 'Use ./b4w.sh on bash/Linux/macOS, ./b4w.ps1 on PowerShell'\n- The SKILL.md already has this documented correctly — just need to update task templates"
    },
    {
      "title": "Crawl X-SQL extraction returns empty fields without --refresh flag",
      "severity": "High",
      "category": "Product",
      "reproduction": "1. Run: ./b4w.sh crawl --seed-file urls.txt --depth 0 --sql @extract.sql --format csv -o out.csv (without --refresh)\n2. Check CSV: all data columns are empty, only URL column is populated",
      "expected": "X-SQL extraction should work without requiring the --refresh flag. The pages are being fetched fresh (not from browser cache).",
      "actual": "Without --refresh, DOM_FIRST_TEXT and DOM_TEXT return empty strings for all selectors. DOM_BASE_URI still works. With --refresh, the first page extracts correctly. This suggests the page content is loaded but the X-SQL UDF engine cannot access it unless the page is loaded with the -refresh flag, which may be due to how the page is cached in the session's WebDB.",
      "rootCause": "In CrawlService.executeSqlQuery(), the page is pre-loaded with '-refresh' before X-SQL execution. However, the initial page load in crawlDepth0() may store the page in a cache layer that the X-SQL UDFs cannot access. The -refresh flag forces a fresh fetch that populates the correct cache. Without it, the UDFs may be looking at an empty or stale cache entry. This is likely a mismatch between the cache layer used by session.load() and the cache layer used by the DOM_LOAD_AND_SELECT UDF.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:executeSqlQuery() — The pre-load with '-refresh' at line 809 may be working around a cache consistency issue. The root cause is likely in how session.load() and DOM_LOAD_AND_SELECT UDF share the WebDB cache.",
      "suggestion": "- Investigate why the WebDB cache populated by session.load() is not visible to the DOM_LOAD_AND_SELECT UDF without -refresh\n- Consider making -refresh the default for X-SQL extraction, or at least warning the user when extraction returns all-empty rows\n- Add a diagnostic message when all extracted fields are empty: 'All extraction fields are empty. Try adding --refresh to force fresh page loads.'"
    },
    {
      "title": "htmlsnapshot query fails with 417 Expectation Failed for all tested pages",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./b4w.sh htmlsnapshot query \"http://localhost:18080/generated/crawl/product/1.html\" --sql @query.sql",
      "expected": "X-SQL query executes successfully and returns extracted data.",
      "actual": "Returns 417 Expectation Failed with message 'The scrape session closed before the query could execute.' After 3 retries with exponential backoff (500ms, 1000ms, 2000ms), all attempts fail. The log shows 'X-SQL scrape session closed (417). Pre-loading... and retrying...'",
      "rootCause": "The ScrapeService uses the SWARM_SESSION_ID session. When this session is closed or recreated between requests, the WebDB cache becomes empty. The retry logic pre-loads the page but the session may still not be ready. This may be a race condition in the session lifecycle between the REST API call and the scrape hyperlink execution.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/ScrapeService.kt:executeQuery() — The retry loop and session management need investigation.",
      "suggestion": "- Investigate the SWARM session lifecycle — why is the session being closed/recreated between htmlsnapshot query calls?\n- Consider using a dedicated session for htmlsnapshot query instead of sharing the SWARM session\n- Add a health check before executing the query to verify the session is ready\n- The --result-only flag on htmlsnapshot query should be documented more prominently as a way to skip session validation"
    },
    {
      "title": "Unclear relationship between --refresh flag and X-SQL extraction success",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Running crawl with X-SQL extraction without --refresh silently produces empty data rows.",
      "expected": "The CLI should warn when extraction returns empty fields, or --refresh should be the default for X-SQL extraction, or the documentation should explicitly state that --refresh is required.",
      "actual": "Crawl completes with '2 rows extracted' but all data columns are empty. No warning or hint is shown. The user must discover --refresh through trial and error.",
      "rootCause": "The crawl reports success ('N rows extracted') based on row count, not field content. Empty extracted fields are not treated as an error or warning condition. The --refresh flag affects internal cache behavior in non-obvious ways.",
      "codePointer": "cli/browser4-cli/src/commands.rs — crawl result formatting logic could detect all-empty extracted fields and suggest --refresh",
      "suggestion": "- After crawl completes with extracted data, check if all extracted fields (excluding URL) are empty and show: 'Note: Extracted fields are empty. Try adding --refresh for fresh page loads.'\n- Consider making --refresh the default when --sql is present\n- Document the --refresh requirement in the crawl --help output and the SKILL.md crawl reference"
    },
    {
      "title": "CSV output column order differs from SQL SELECT column order",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "SQL: SELECT title, price, url FROM ... Expected CSV header: title,price,url. Actual CSV header varies — in some runs it's title,price,url; in the initial run it was title,price,url (matching SQL order). However, the first crawl run without --refresh had url,title,price order.",
      "expected": "CSV column order should consistently match the SQL SELECT column order.",
      "actual": "The column order appears to depend on internal result set processing. Without --refresh, the URL column appeared first (url,title,price). With --refresh, the order matched SELECT (title,price,url). This inconsistency makes programmatic CSV parsing fragile.",
      "rootCause": "ResultSetUtils.getTextEntitiesFromResultSet() returns a List<Map<String, Any?>>. The Map iteration order may not preserve column ordering from the SQL query, depending on the Map implementation (LinkedHashMap vs HashMap). The column ordering may also be affected by how rows are merged in XSQLHyperlink.doExtract() which uses linkedSetOf to collect all keys.",
      "codePointer": "browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/crawl/common/XSQLScrapeHyperlink.kt:doExtract() — The allKeys collection uses linkedSetOf which preserves insertion order but may not match SQL column order",
      "suggestion": "- Use JDBC ResultSetMetaData to determine column order and preserve it throughout the extraction pipeline\n- Use LinkedHashMap consistently in getTextEntitiesFromResultSet\n- Document the expected CSV column order in the crawl --help"
    },
    {
      "title": "No --sql-stdin example in crawl --help output",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "Run: ./b4w.sh help crawl (or crawl --help)",
      "expected": "The help output should show an example of --sql-stdin usage, similar to how --sql @file is documented.",
      "actual": "--sql-stdin is listed as an option but no usage example is shown in the help output. Users must discover the syntax from the SKILL.md reference docs.",
      "rootCause": "The crawl command definition in commands.rs includes --sql-stdin as an option but the help text generation doesn't include a usage example for this pattern.",
      "codePointer": "cli/browser4-cli/src/commands.rs — crawl command definition around line 2741",
      "suggestion": "- Add a usage example to the crawl help: 'browser4-cli crawl --seed-file urls.txt --sql-stdin --format table < query.sql'\n- Consider adding a 'Common patterns' section to crawl --help showing both --sql @file and --sql-stdin examples"
    },
    {
      "title": "No progress indication during multi-page X-SQL extraction",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run crawl with --sql and multiple seed URLs. During the 'Crawling...' phase, the output shows elapsed time and queued URLs count but no per-page extraction progress.",
      "expected": "Show per-page extraction status: 'Page 1/2: extracted 1 row (Widget Alpha / $10.00), Page 2/2: extracting...'",
      "actual": "Only shows 'Crawling... waiting for first page (Xs elapsed, N URLs queued)' then 'N pages crawled, M rows extracted.' No intermediate feedback on extraction results.",
      "rootCause": "The CLI polling loop only reports aggregate counts. Individual page extraction results are not streamed back during the crawl.",
      "codePointer": "cli/browser4-cli/src/commands.rs — crawl foreground polling loop",
      "suggestion": "- Stream per-page results as they complete during the polling loop\n- Show extracted row previews during progress updates: 'Page 1/2 done: \"Widget Alpha\" — $10.00'"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — Both AC5 and AC6 were demonstrated and verified, but required a workaround (separate crawl invocations per seed URL) due to a critical protocol handler bug that prevents multi-seed crawl X-SQL extraction from working correctly.",
    "successRate": "75% — The core X-SQL extraction functionality works correctly (both --sql @file and --sql-stdin produce correct data; CSV and table formats render properly). However, multi-seed crawl extraction fails silently without --refresh, and the protocol handler bug blocks processing more than one URL per crawl invocation.",
    "issuesFound": 8,
    "majorBlockers": "Protocol handler recovery failure between seed URLs (Issue 1) — prevents processing more than one seed URL per crawl invocation. Every seed URL after the first gets 0 bytes or empty extraction data. Workaround: run each seed URL as a separate crawl invocation. Also, --refresh flag is effectively required for any X-SQL extraction to work (Issue 3).",
    "mostConfusingAspects": "1) The crawl reports 'N rows extracted' successfully even when all data fields are empty — this silent failure is extremely confusing. 2) The relationship between --refresh and extraction success is non-obvious and undocumented. 3) htmlsnapshot query (the simpler single-page X-SQL path) consistently fails with 417 errors, pushing users toward the more complex crawl command even for single-page extraction. 4) The difference between '2 pages crawled, 2 rows extracted' (with empty data) and '2 pages crawled, 2 rows extracted' (with correct data) is invisible in the CLI output.",
    "mostValuableImprovements": "1) Fix the protocol handler recovery between seed URLs — this is the single biggest blocker. 2) Make --refresh the default when --sql is present, or at minimum warn when all extracted fields are empty. 3) Add per-page progress indication during crawl with X-SQL extraction. 4) Fix htmlsnapshot query 417 errors to provide a simpler single-page X-SQL path. 5) Add --sql-stdin usage example to crawl --help.",
    "usabilityRating": 5
  }
}
```

**Workarounds Applied During Task:**

1. **Multi-seed crawl extraction failure:** Each seed URL must be crawled in a separate `browser4-cli` invocation to avoid "Protocol not found" errors on subsequent URLs
2. **`--refresh` is essential:** Without `--refresh`, even the first page's X-SQL extraction returns empty fields

---

## Issues Found (8 issues)

### Issue 1: Multi-seed crawl X-SQL extraction fails for subsequent seed URLs due to protocol handler not recovering

**Severity:** Critical
**Category:** Reliability

#### Reproduction

1. Create a seed file with 2+ product URLs pointing to localhost MockSite
2. Run: ./b4w.sh crawl --seed-file urls.txt --depth 0 --sql @extract.sql --format table --refresh
3. Observe: first seed URL extracts correctly, subsequent URLs return empty fields or 0 rows

#### Expected Behavior

All seed URLs in a multi-seed crawl should have their X-SQL extraction data populated correctly.

#### Actual Behavior

Only the first seed URL extracts data correctly. Subsequent URLs get 0-byte responses (after 3 retries) or empty extraction fields, with 'Protocol not found' errors in the logs. Status code 1600 (ProtoNotFound) appears consistently for each subsequent seed URL.

#### Root Cause Analysis

When a crawl processes multiple seed URLs, each seed URL creates a new session. When the first session is closed, the HTTP protocol handler is deregistered. The next session's protocol handler fails to re-register before the page load attempt, causing 'Protocol not found' (status 1600). The retry logic in CrawlService.crawlDepth0() (3 retries with 500ms delay) is insufficient to allow protocol handler re-registration. The --refresh flag, which is always added for depth=0, may exacerbate this by invalidating caches that would otherwise serve stale but usable content.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:crawlDepth0() — The SEED_INTERVAL_MS (100ms) delay between seeds and the FETCH_RETRY_DELAY_MS (500ms) may need to be increased, or the session creation logic needs to ensure protocol handler availability before attempting page loads.`

#### AI Suggested Improvement

- Increase SEED_INTERVAL_MS from 100ms to 2000ms to give protocol handlers more time to re-register between seed URL sessions
- Add a pre-flight health check (e.g., load about:blank) before the actual page load to verify the protocol handler is ready
- Increase MAX_FETCH_RETRIES from 3 to 5 with exponential backoff (500ms, 1000ms, 2000ms, 4000ms, 8000ms)
- Consider reusing a single session for all depth=0 seed URLs instead of creating a new session per URL
- Add user-facing warning when protocol handler recovery fails: 'Protocol handler not ready for <url>. Try running fewer seed URLs per crawl or increasing delays between requests.'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The protocol-handler deregistration root cause is plausible and the suggested fix direction (pre-flight health check, exponential backoff, session reuse for depth=0) is sound. However, prefer session reuse for all depth=0 seeds over just increasing timeouts — it's architecturally cleaner and eliminates the race condition entirely rather than papering over it. Also note Issue 4 may share this root cause in the ScrapeService path.

---

### Issue 2: Task instruction invocation syntax $(./b4w.ps1) incompatible with Git Bash

**Severity:** High
**Category:** Documentation

#### Reproduction

In Git Bash, run: $(./b4w.ps1) help
Result: 'b4w: command not found'

#### Expected Behavior

The command should invoke browser4-cli correctly.

#### Actual Behavior

$(./b4w.ps1) in bash performs command substitution — it runs ./b4w.ps1, captures its output (which is the string 'b4w' or similar), and tries to execute that as a command. This fails with 'command not found'.

#### Root Cause Analysis

The task instructions specify $(./b4w.ps1) as the invocation method, but this is bash command substitution syntax. The SKILL.md correctly documents this issue and recommends ./b4w.sh or pwsh ./b4w.ps1 instead. The task template needs updating to use the correct invocation for the target shell.

#### AI Suggested Improvement

- Update task instruction templates to use ./b4w.sh <command> for bash/Git Bash environments
- Add a platform-detection note: 'Use ./b4w.sh on bash/Linux/macOS, ./b4w.ps1 on PowerShell'
- The SKILL.md already has this documented correctly — just need to update task templates

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Clear documentation/shell-syntax bug. `$(./b4w.ps1)` is bash command substitution and will never invoke the CLI correctly in Git Bash. The SKILL.md already documents the correct invocation (`./b4w.sh` or `pwsh ./b4w.ps1`); only the task templates need updating. Low-effort, high-impact fix.

---

### Issue 3: Crawl X-SQL extraction returns empty fields without --refresh flag

**Severity:** High
**Category:** Product

#### Reproduction

1. Run: ./b4w.sh crawl --seed-file urls.txt --depth 0 --sql @extract.sql --format csv -o out.csv (without --refresh)
2. Check CSV: all data columns are empty, only URL column is populated

#### Expected Behavior

X-SQL extraction should work without requiring the --refresh flag. The pages are being fetched fresh (not from browser cache).

#### Actual Behavior

Without --refresh, DOM_FIRST_TEXT and DOM_TEXT return empty strings for all selectors. DOM_BASE_URI still works. With --refresh, the first page extracts correctly. This suggests the page content is loaded but the X-SQL UDF engine cannot access it unless the page is loaded with the -refresh flag, which may be due to how the page is cached in the session's WebDB.

#### Root Cause Analysis

In CrawlService.executeSqlQuery(), the page is pre-loaded with '-refresh' before X-SQL execution. However, the initial page load in crawlDepth0() may store the page in a cache layer that the X-SQL UDFs cannot access. The -refresh flag forces a fresh fetch that populates the correct cache. Without it, the UDFs may be looking at an empty or stale cache entry. This is likely a mismatch between the cache layer used by session.load() and the cache layer used by the DOM_LOAD_AND_SELECT UDF.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:executeSqlQuery() — The pre-load with '-refresh' at line 809 may be working around a cache consistency issue. The root cause is likely in how session.load() and DOM_LOAD_AND_SELECT UDF share the WebDB cache.`

#### AI Suggested Improvement

- Investigate why the WebDB cache populated by session.load() is not visible to the DOM_LOAD_AND_SELECT UDF without -refresh
- Consider making -refresh the default for X-SQL extraction, or at least warning the user when extraction returns all-empty rows
- Add a diagnostic message when all extracted fields are empty: 'All extraction fields are empty. Try adding --refresh to force fresh page loads.'

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The cache-consistency hypothesis (session.load() populates one cache layer, DOM UDFs read from another) is the core bug here. The suggested `-refresh` default is a reasonable workaround, but the real fix should trace the WebDB cache layering to understand _why_ the two paths diverge. Making `-refresh` the default without understanding the root cause risks masking other cache-coherence bugs.

---

### Issue 4: htmlsnapshot query fails with 417 Expectation Failed for all tested pages

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.sh htmlsnapshot query "http://localhost:18080/generated/crawl/product/1.html" --sql @query.sql

#### Expected Behavior

X-SQL query executes successfully and returns extracted data.

#### Actual Behavior

Returns 417 Expectation Failed with message 'The scrape session closed before the query could execute.' After 3 retries with exponential backoff (500ms, 1000ms, 2000ms), all attempts fail. The log shows 'X-SQL scrape session closed (417). Pre-loading... and retrying...'

#### Root Cause Analysis

The ScrapeService uses the SWARM_SESSION_ID session. When this session is closed or recreated between requests, the WebDB cache becomes empty. The retry logic pre-loads the page but the session may still not be ready. This may be a race condition in the session lifecycle between the REST API call and the scrape hyperlink execution.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/ScrapeService.kt:executeQuery() — The retry loop and session management need investigation.`

#### AI Suggested Improvement

- Investigate the SWARM session lifecycle — why is the session being closed/recreated between htmlsnapshot query calls?
- Consider using a dedicated session for htmlsnapshot query instead of sharing the SWARM session
- Add a health check before executing the query to verify the session is ready
- The --result-only flag on htmlsnapshot query should be documented more prominently as a way to skip session validation

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Real reliability issue — 417 after 3 retries means the session lifecycle is broken for htmlsnapshot query. Likely shares root cause with Issue 1 (HTTP protocol handler deregistration on session close). The investigation should start with the session lifecycle: is something closing/recreating the SWARM session between REST calls? Related to Issue 1 — fix the session lifecycle once and both crawl multi-seed and htmlsnapshot query should benefit.

---

### Issue 5: Unclear relationship between --refresh flag and X-SQL extraction success

**Severity:** Medium
**Category:** UX

#### Reproduction

Running crawl with X-SQL extraction without --refresh silently produces empty data rows.

#### Expected Behavior

The CLI should warn when extraction returns empty fields, or --refresh should be the default for X-SQL extraction, or the documentation should explicitly state that --refresh is required.

#### Actual Behavior

Crawl completes with '2 rows extracted' but all data columns are empty. No warning or hint is shown. The user must discover --refresh through trial and error.

#### Root Cause Analysis

The crawl reports success ('N rows extracted') based on row count, not field content. Empty extracted fields are not treated as an error or warning condition. The --refresh flag affects internal cache behavior in non-obvious ways.

#### Code Pointer

`cli/browser4-cli/src/commands.rs — crawl result formatting logic could detect all-empty extracted fields and suggest --refresh`

#### AI Suggested Improvement

- After crawl completes with extracted data, check if all extracted fields (excluding URL) are empty and show: 'Note: Extracted fields are empty. Try adding --refresh for fresh page loads.'
- Consider making --refresh the default when --sql is present
- Document the --refresh requirement in the crawl --help output and the SKILL.md crawl reference

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [x] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DUPLICATE] This is the UX symptom of Issue 3's cache-consistency bug. The diagnostic warning ("All extraction fields are empty. Try adding --refresh") is already covered by Issue 3's suggested improvements. Fixing the root cause in Issue 3 makes the UX gap less critical; adding the warning is a good interim measure but shouldn't be tracked separately.

---

### Issue 6: CSV output column order differs from SQL SELECT column order

**Severity:** Medium
**Category:** Product

#### Reproduction

SQL: SELECT title, price, url FROM ... Expected CSV header: title,price,url. Actual CSV header varies — in some runs it's title,price,url; in the initial run it was title,price,url (matching SQL order). However, the first crawl run without --refresh had url,title,price order.

#### Expected Behavior

CSV column order should consistently match the SQL SELECT column order.

#### Actual Behavior

The column order appears to depend on internal result set processing. Without --refresh, the URL column appeared first (url,title,price). With --refresh, the order matched SELECT (title,price,url). This inconsistency makes programmatic CSV parsing fragile.

#### Root Cause Analysis

ResultSetUtils.getTextEntitiesFromResultSet() returns a List<Map<String, Any?>>. The Map iteration order may not preserve column ordering from the SQL query, depending on the Map implementation (LinkedHashMap vs HashMap). The column ordering may also be affected by how rows are merged in XSQLHyperlink.doExtract() which uses linkedSetOf to collect all keys.

#### Code Pointer

`browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/crawl/common/XSQLScrapeHyperlink.kt:doExtract() — The allKeys collection uses linkedSetOf which preserves insertion order but may not match SQL column order`

#### AI Suggested Improvement

- Use JDBC ResultSetMetaData to determine column order and preserve it throughout the extraction pipeline
- Use LinkedHashMap consistently in getTextEntitiesFromResultSet
- Document the expected CSV column order in the crawl --help

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Column-order inconsistency is a real correctness bug for programmatic CSV consumers. Using JDBC ResultSetMetaData to determine and preserve column order through the extraction pipeline is the right approach. The linkedSetOf in `XSQLScrapeHyperlink.doExtract()` may not match SQL order if keys are discovered in a different traversal order. This also affects JSON and table output formats, not just CSV.

---

### Issue 7: No --sql-stdin example in crawl --help output

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Run: ./b4w.sh help crawl (or crawl --help)

#### Expected Behavior

The help output should show an example of --sql-stdin usage, similar to how --sql @file is documented.

#### Actual Behavior

--sql-stdin is listed as an option but no usage example is shown in the help output. Users must discover the syntax from the SKILL.md reference docs.

#### Root Cause Analysis

The crawl command definition in commands.rs includes --sql-stdin as an option but the help text generation doesn't include a usage example for this pattern.

#### Code Pointer

`cli/browser4-cli/src/commands.rs — crawl command definition around line 2741`

#### AI Suggested Improvement

- Add a usage example to the crawl help: 'browser4-cli crawl --seed-file urls.txt --sql-stdin --format table < query.sql'
- Consider adding a 'Common patterns' section to crawl --help showing both --sql @file and --sql-stdin examples

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Straightforward documentation gap. Adding a `--sql-stdin` example to the crawl help text is low-effort and directly improves discoverability. The `--help` output is the first place users look; relying on SKILL.md alone misses the CLI-native discovery path.

---

### Issue 8: No progress indication during multi-page X-SQL extraction

**Severity:** Low
**Category:** UX

#### Reproduction

Run crawl with --sql and multiple seed URLs. During the 'Crawling...' phase, the output shows elapsed time and queued URLs count but no per-page extraction progress.

#### Expected Behavior

Show per-page extraction status: 'Page 1/2: extracted 1 row (Widget Alpha / $10.00), Page 2/2: extracting...'

#### Actual Behavior

Only shows 'Crawling... waiting for first page (Xs elapsed, N URLs queued)' then 'N pages crawled, M rows extracted.' No intermediate feedback on extraction results.

#### Root Cause Analysis

The CLI polling loop only reports aggregate counts. Individual page extraction results are not streamed back during the crawl.

#### Code Pointer

`cli/browser4-cli/src/commands.rs — crawl foreground polling loop`

#### AI Suggested Improvement

- Stream per-page results as they complete during the polling loop
- Show extracted row previews during progress updates: 'Page 1/2 done: "Widget Alpha" — $10.00'

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Reasonable UX enhancement. Streaming per-page extraction results during the polling loop would significantly improve the experience for multi-page crawls. The suggested format ("Page 1/2 done: 'Widget Alpha' — $10.00") is concrete and implementable. Lower priority than the reliability issues (1, 3, 4) but worth tracking.

---

## Overall Assessment

**Completion Status:** Partially Successful — Both AC5 and AC6 were demonstrated and verified, but required a workaround (separate crawl invocations per seed URL) due to a critical protocol handler bug that prevents multi-seed crawl X-SQL extraction from working correctly.

**Success Rate:** 75% — The core X-SQL extraction functionality works correctly (both --sql @file and --sql-stdin produce correct data; CSV and table formats render properly). However, multi-seed crawl extraction fails silently without --refresh, and the protocol handler bug blocks processing more than one URL per crawl invocation.

**Issues Found:** 8

**Major Blockers:** Protocol handler recovery failure between seed URLs (Issue 1) — prevents processing more than one seed URL per crawl invocation. Every seed URL after the first gets 0 bytes or empty extraction data. Workaround: run each seed URL as a separate crawl invocation. Also, --refresh flag is effectively required for any X-SQL extraction to work (Issue 3).

**Most Confusing Aspects:** 1) The crawl reports 'N rows extracted' successfully even when all data fields are empty — this silent failure is extremely confusing. 2) The relationship between --refresh and extraction success is non-obvious and undocumented. 3) htmlsnapshot query (the simpler single-page X-SQL path) consistently fails with 417 errors, pushing users toward the more complex crawl command even for single-page extraction. 4) The difference between '2 pages crawled, 2 rows extracted' (with empty data) and '2 pages crawled, 2 rows extracted' (with correct data) is invisible in the CLI output.

**Most Valuable Improvements:** 1) Fix the protocol handler recovery between seed URLs — this is the single biggest blocker. 2) Make --refresh the default when --sql is present, or at minimum warn when all extracted fields are empty. 3) Add per-page progress indication during crawl with X-SQL extraction. 4) Fix htmlsnapshot query 417 errors to provide a simpler single-page X-SQL path. 5) Add --sql-stdin usage example to crawl --help.

**Usability Rating:** 5/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Multi-seed crawl X-SQL extraction fails for subsequent seed URLs due to protocol handler not recovering

1. Create a seed file with 2+ product URLs pointing to localhost MockSite
2. Run: ./b4w.sh crawl --seed-file urls.txt --depth 0 --sql @extract.sql --format table --refresh
3. Observe: first seed URL extracts correctly, subsequent URLs return empty fields or 0 rows

#### Issue 2: Task instruction invocation syntax $(./b4w.ps1) incompatible with Git Bash

In Git Bash, run: $(./b4w.ps1) help
Result: 'b4w: command not found'

#### Issue 3: Crawl X-SQL extraction returns empty fields without --refresh flag

1. Run: ./b4w.sh crawl --seed-file urls.txt --depth 0 --sql @extract.sql --format csv -o out.csv (without --refresh)
2. Check CSV: all data columns are empty, only URL column is populated

#### Issue 4: htmlsnapshot query fails with 417 Expectation Failed for all tested pages

./b4w.sh htmlsnapshot query "http://localhost:18080/generated/crawl/product/1.html" --sql @query.sql

#### Issue 5: Unclear relationship between --refresh flag and X-SQL extraction success

Running crawl with X-SQL extraction without --refresh silently produces empty data rows.

#### Issue 6: CSV output column order differs from SQL SELECT column order

SQL: SELECT title, price, url FROM ... Expected CSV header: title,price,url. Actual CSV header varies — in some runs it's title,price,url; in the initial run it was title,price,url (matching SQL order). However, the first crawl run without --refresh had url,title,price order.

#### Issue 7: No --sql-stdin example in crawl --help output

Run: ./b4w.sh help crawl (or crawl --help)

#### Issue 8: No progress indication during multi-page X-SQL extraction

Run crawl with --sql and multiple seed URLs. During the 'Crawling...' phase, the output shows elapsed time and queued URLs count but no per-page extraction progress.

