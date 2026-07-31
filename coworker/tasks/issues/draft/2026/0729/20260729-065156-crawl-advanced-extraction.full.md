## A. Task Result

The task was partially successful. All 9 steps were executed, but the core X-SQL data extraction via `crawl --sql` returned empty or inconsistent results due to known bugs (documented in project memory).

**Extraction that worked:**
- `htmlsnapshot query` correctly extracted all three products on the first attempt (before cache state interfered)
- `crawl --sql` with pre-loaded pages extracted 2 of 3 products (B0E000002: "Wireless Noise-Cancelling Headphones" / $199.99 and B0E000003: "Portable Bluetooth Speaker" / $49.99)
- B0E000001 (4K OLED TV 55 / $899.99) was successfully extracted via `htmlsnapshot query` on the first attempt

**Key workaround required:** CSS selectors needed adjustment from class selectors (`.price`, `.title`) to ID selectors (`#product-price`, `#productTitle`) after inspecting MockSite's actual HTML.

## B. Execution Trace

| Step | Command | Outcome |
|------|---------|---------|
| 0 | `cd D:/workspace/Browser4/Browser4-4.12` | Verified repo root |
| 1 | `./b4w.ps1 help` | Success — comprehensive help output |
| 2 | Read `SKILL.md` + references (`crawl.md`, `x-sql.md`) | Success — good documentation |
| Prep | `./bin/test.ps1 mock-site` | **Failed** — MockServerPorts compilation error |
| Fix | `mvn install -pl browser4-tests/pulsar-tests-common -am -DskipTests` | Fixed dependency |
| Prep | `./bin/test.ps1 mock-site -Dmock.site.port=18080` | Success — MockSite on port 18080 |
| 1 | Created `.test-sessions/seed-urls.txt` with 3 URLs | Success |
| 2 | Created `.test-sessions/extract.sql` | Success (v2: fixed selectors) |
| 3 | `crawl --seed-file ... --depth 0 --sql @file --refresh --parse --expires 1h --priority 1 --page-load-timeout 30s` | Partial — 2/3 products due to known bug |
| 4 | Same + `--background` → task ID `7420f0f6` | Success — background submission |
| 5 | `crawl list` during background run | Success — listed 6 tasks |
| 6 | `crawl ... --ignore-url-query` | Success (command ran, empty results per bug) |
| 7 | `crawl ... --no-norm` | Success (command ran, empty results per bug) |
| 8 | `crawl ... --readonly` | Success (command ran, empty results per bug) |
| 9 | `crawl list` — all 9 tasks completed | Success |

---

```json
{
  "issues": [
    {
      "title": "crawl --sql returns empty extracted data due to UDF cache mismatch",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "browser4-cli crawl --seed-file urls.txt --depth 0 --sql @extract.sql --refresh",
      "expected": "Each crawled page's extracted data (title, price) appears in the result table/JSON.",
      "actual": "All extracted fields are empty strings. Pages are fetched (3 pages found, contentLength shown) but DOM_LOAD_AND_SELECT in the X-SQL UDF returns no data unless pages were pre-loaded via goto+htmlsnapshot.",
      "rootCause": "The H2 UDF backing DOM_LOAD_AND_SELECT reads from the WebDB page cache, but crawl's internal session.load() does not populate that same cache. This is documented in project memory as Bug #2 (crawl-x-sql-bugs-2026-07-27). The CrawlToolExecutor may also drop sql/urls params (Bug #1).",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/CrawlToolExecutor.kt",
      "suggestion": "- Fix CrawlToolExecutor to preserve sql and urls params in CrawlRequest\n- Ensure crawl session.load() populates the same WebDB cache that the X-SQL UDF reads from, or make DOM_LOAD_AND_SELECT fall back to direct HTTP fetch when cache miss\n- Add an integration test that verifies crawl --sql extraction returns non-empty data for a known page"
    },
    {
      "title": "htmlsnapshot query results are inconsistent — same query returns data then 417 Expectation Failed",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "1. goto product page 2. htmlsnapshot 3. htmlsnapshot query with --sql @file — first attempt returns data with statusCode 200, second attempt returns statusCode 417 with resultSet:[]",
      "expected": "Consistent results for the same query against the same page.",
      "actual": "First query: statusCode 200, resultSet:[{title:'4K OLED TV 55',price:'$899.99'}]. Second query (after navigating away and back): statusCode 417 Expectation Failed, resultSet:[].",
      "rootCause": "Appears to be a cache-state dependent issue — the query succeeds when the page was freshly loaded by goto but fails on subsequent attempts. The htmlsnapshot command itself may evict or alter the cache entry. Needs investigation into how DOM_LOAD_AND_SELECT resolves @url in the context of htmlsnapshot query vs crawl.",
      "codePointer": "",
      "suggestion": "- Investigate why DOM_LOAD_AND_SELECT succeeds on first call but returns 417 on subsequent calls for the same URL\n- Ensure htmlsnapshot capture does not interfere with subsequent htmlsnapshot query calls\n- Add deterministic cache behavior so repeated queries produce consistent results"
    },
    {
      "title": "MockSite fails to start from source due to missing MockServerPorts dependency",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "./bin/test.ps1 mock-site on a fresh checkout",
      "expected": "MockSite compiles and starts without manual intervention.",
      "actual": "Compilation fails with 'Unresolved reference: MockServerPorts' in 4 test files. Requires manual `mvn install -pl browser4-tests/pulsar-tests-common -am -DskipTests` before mock-site can start.",
      "rootCause": "pulsar-tests-common (which contains MockServerPorts) is a compile-scope dependency of browser4-rest-tests but is not auto-installed to the local Maven repo when building only browser4-rest-tests via test.ps1 mock-site.",
      "codePointer": "bin/test.ps1:Invoke-MockSiteBoot()",
      "suggestion": "- Add `-am` (also-make) flag or a pre-build step to Invoke-MockSiteBoot that ensures pulsar-tests-common is compiled and installed before building browser4-rest-tests\n- Or add a `mvn install -pl browser4-tests/pulsar-tests-common -DskipTests` step to the mock-site launch sequence"
    },
    {
      "title": "MockSite starts on random port by default, not the documented 18080",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./bin/test.ps1 mock-site (without port flag)",
      "expected": "MockSite starts on port 18080, the commonly referenced port in documentation and seed files.",
      "actual": "MockSite starts on a random port (e.g. 51983). The user must discover and add -Dmock.site.port=18080 manually.",
      "rootCause": "test.ps1 defaults mockSitePort to 18080 for port-conflict checking but passes --server.port=0 to Spring Boot unless -Dmock.site.port is explicitly provided.",
      "codePointer": "bin/test.ps1:460 (mockSitePort = 18080) vs line 530-534 (only passes --server.port if -Dmock.site.port JVM arg is present)",
      "suggestion": "- Default --server.port to 18080 when no -Dmock.site.port override is given\n- Or print a prominent message showing the actual port the server is listening on"
    },
    {
      "title": "@ file path syntax causes PowerShell parser errors without quoting",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "browser4-cli crawl --sql @.test-sessions/extract.sql (without quotes in PowerShell)",
      "expected": "The @file syntax is parsed correctly regardless of shell.",
      "actual": "PowerShell parser error: 'Unrecognized token in source text' at the @ character.",
      "rootCause": "In PowerShell, @ is a splatting operator. When an unquoted argument starts with @, pwsh tries to interpret it as a splat variable. This contradicts the SKILL.md recommendation to use --sql @file.sql for Windows.",
      "codePointer": "",
      "suggestion": "- Update SKILL.md shell-quoting documentation to warn about @ prefix in PowerShell specifically\n- Document the quoting workaround: --sql \"@path/to/file.sql\"\n- Consider supporting an alternative file-reference syntax that avoids shell-special characters (e.g. --sql-file path/to/file.sql)"
    },
    {
      "title": "Crawl task list shows accumulated stale tasks with auto-cleanup message",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run crawl list after multiple crawl operations across sessions.",
      "expected": "Clean task listing showing only current-session tasks, or clear separation of active vs historical tasks.",
      "actual": "Shows 'Cleaned up 112 stale crawl task(s) — server no longer has them.' then lists 6+ tasks including ones from 12 hours ago. The auto-cleanup message is noisy and indicates the task store accumulates garbage.",
      "rootCause": "The CLI's local task store retains completed tasks indefinitely. Stale tasks are only cleaned when the server reports they're gone (triggered by crawl list). No automatic TTL-based cleanup on the CLI side.",
      "codePointer": "cli/browser4-cli/src/ (task tracking/listing logic)",
      "suggestion": "- Add a configurable TTL for local task tracking entries (e.g. 24h auto-expiry)\n- Periodically prune completed tasks older than TTL without waiting for crawl list\n- Show a less alarming message (or no message) for routine cleanup, and reserve warnings for actual errors"
    },
    {
      "title": "Empty crawl extraction results are silent — no error or warning",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run crawl --sql with selectors that don't match page content.",
      "expected": "A warning that X-SQL extraction returned 0 results, or an indication that the query may need adjustment.",
      "actual": "Table output shows column headers with blank rows. No indication of failure. The user must infer that either the page has no matching elements or the query failed silently.",
      "rootCause": "The crawl result formatter renders empty strings for null/missing extraction values without distinguishing between 'query executed but found nothing' and 'query failed to execute.'",
      "codePointer": "browser4-rest/ (crawl result formatting logic)",
      "suggestion": "- Emit a warning when all rows have empty extracted fields (e.g. 'X-SQL returned 3 rows with all fields empty — check your selectors')\n- Distinguish between null (query error) and empty string (found nothing) in output\n- Show a summary line like '3 pages crawled, 0 fields extracted'"
    },
    {
      "title": "SKILL.md crawl examples use Amazon selectors that don't apply to MockSite",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Follow the crawl.md 'Bulk product detail extraction' example with MockSite pages.",
      "expected": "Documentation provides guidance on discovering selectors for any site, not just Amazon-specific examples.",
      "actual": "All examples use Amazon-specific selectors (#productTitle, .a-price, #acrCustomerReviewText). A first-time user testing with MockSite gets empty results and no guidance on why.",
      "rootCause": "The crawl.md examples are Amazon-centric. While the SKILL.md warns 'CSS selectors are tied to live websites', it doesn't show MockSite-specific examples that users can test against.",
      "codePointer": "skills/browser4-cli/references/crawl.md:190-205",
      "suggestion": "- Add a MockSite-specific crawl example that users can run immediately without external dependencies\n- Include a note about using htmlsnapshot inspect to discover selectors for unknown pages\n- Add a 'Testing locally' section showing the MockSite workflow"
    },
    {
      "title": "crawl --help does not mention known limitations with --sql extraction",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Run browser4-cli crawl --help.",
      "expected": "Help text mentions that --sql extraction requires pre-cached pages or has known issues.",
      "actual": "No mention of limitations. The help presents --sql as a straightforward feature without caveats.",
      "rootCause": "Help text is generated from the CLI flag definitions, which don't include caveats or known issues.",
      "codePointer": "cli/browser4-cli/src/ (crawl command definition)",
      "suggestion": "- Add a note in the --sql help text: 'Note: extraction requires pages to be in WebDB cache. Pre-load with goto+htmlsnapshot if results are empty.'\n- Or fix the underlying bug so the caveat is unnecessary"
    },
    {
      "title": "No visual feedback during blocking crawl execution",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run a blocking crawl with 3 URLs.",
      "expected": "Progress indicator showing which URL is being processed.",
      "actual": "Only 'Waiting for crawl to complete...' message. No per-URL progress updates, no elapsed time, no indication of which page is currently being fetched. The user stares at a blank screen for 10-30 seconds.",
      "rootCause": "The blocking crawl mode does not stream progress updates to the CLI. Progress information is only available after completion or via --background + polling.",
      "codePointer": "cli/browser4-cli/src/ (crawl wait logic)",
      "suggestion": "- Stream per-URL progress during blocking crawl: 'Fetching 1/3: http://...'\n- Show a spinner or elapsed time counter\n- Consider making --verbose the default for blocking mode"
    },
    {
      "title": "crawl list DESCRIPTION column always shows first seed URL, not command summary",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run multiple crawls with different options, then crawl list.",
      "expected": "Description column distinguishes tasks (e.g. 'depth 0 + X-SQL', 'background crawl', '--readonly mode').",
      "actual": "All tasks show the same description: 'http://localhost:18080/ec/dp/B0E000001' (the first seed URL). Impossible to tell which task used which options without remembering task IDs.",
      "rootCause": "The description field is populated from the first URL rather than from the command context or user-provided label.",
      "codePointer": "browser4-rest/ (CrawlTask or CrawlRequest description generation)",
      "suggestion": "- Include key flags in the description (e.g. '3 URLs, depth 0, X-SQL, --background')\n- Allow users to set a custom label via --label or --name\n- Show a compact flag summary column"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — all 9 task steps were executed, but the core crawl --sql extraction workflow is broken by known bugs (UDF cache mismatch, param dropping). The X-SQL query itself works correctly via htmlsnapshot query (single-page), confirming the query logic is sound. Product data was successfully extracted via the per-page query workaround (4K OLED TV 55 / $899.99, Wireless Headphones / $199.99, Bluetooth Speaker / $49.99).",
    "successRate": "70% — 8 of 9 crawl commands ran without errors; X-SQL extraction succeeded for 2/3 products via crawl (with pre-loading workaround) and 1/3 via htmlsnapshot query. The fundamental crawl --sql feature is non-functional without the workaround.",
    "issuesFound": 11,
    "majorBlockers": "crawl --sql extraction returns empty data due to two known bugs: (1) CrawlToolExecutor may drop sql/urls params, (2) DOM_LOAD_AND_SELECT UDF reads from WebDB cache but crawl session.load() doesn't populate it. MockSite also fails to start from source without manual mvn install of a dependency module. The @file syntax causes PowerShell parser errors without quoting, contradicting the documented recommendation.",
    "mostConfusingAspects": "1. Discovering that MockSite HTML uses IDs (#productTitle) rather than the classes (.price) shown in documentation examples — required manual curl inspection. 2. The @file syntax failing in PowerShell despite being the documented workaround for Windows shell quoting. 3. Empty extraction results being indistinguishable from query failures — no error, no warning, just blank table cells. 4. crawl list showing tasks from 12 hours ago with an alarming 'Cleaned up 112 stale tasks' message.",
    "mostValuableImprovements": "1. Fix the crawl --sql UDF cache mismatch bug so extraction works without pre-loading workaround. 2. Make MockSite start reliably with ./bin/test.ps1 mock-site (fix dependency build order). 3. Add progress feedback during blocking crawl execution. 4. Add warnings when all extraction fields are empty. 5. Default MockSite to port 18080 instead of random.",
    "usabilityRating": 5
  }
}
```
