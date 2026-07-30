---

# Browser4-CLI Crawl Usability Evaluation — Final Report

## A. Task Result

**Partially Successful.** The crawl command's core features — link discovery, page content fetching, and X-SQL extraction — are all broken. Only administrative commands (`crawl list`, `crawl status`, `crawl clear`) and seed-file argument parsing function correctly. The actual crawl task goals were blocked by backend bugs.

## B. Execution Trace

**Commands executed:** `help`, `goto`, `htmlsnapshot`, `eval`, `crawl` (5× with various `--out-link-selector` values), `crawl --seed-file` (6× with various formats), `crawl list`, `crawl status`, `crawl clear`, `htmlsnapshot get all`.

**Workarounds:** (1) Used `eval` JavaScript to manually extract product URLs instead of `--out-link-selector`. (2) Used `--json` global flag to see actual crawl errors hidden by human-readable output. (3) Accepted that `--format` flags are non-functional without `--sql`.

**Key discovery:** An existing issues report from yesterday (20260728-153336) documents the same bugs with ACCEPT verdicts — they remain unfixed. The protocol-handler bug has **worsened**: yesterday 1 of 10 pages loaded; today 0 of 10.

---

## C & D: Issues Found & Overall Assessment

```json
{
  "issues": [
    {
      "title": "Crawl link discovery returns 0 elements for ALL CSS selectors — depth >= 1 completely broken",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1 --top-links 5 --refresh --format table",
      "expected": "Links matching the 'a' CSS selector extracted from the portal page (~94 links visible via htmlsnapshot).",
      "actual": "Crawl completes with 0 pages found. Diagnostic: 'The page loaded but the CSS selector 'a' matched zero elements.' Multiple selectors tested ('a', 'h3 a', 'a[href*=\"catalogue\"]') all produce 0 results despite 94 anchors confirmed via htmlsnapshot and eval.",
      "rootCause": "The crawl link-discovery path uses a Jsoup fetch/parse pipeline (session.loadDocument() → document.select(selector)) that differs from the browser-based DOM used by htmlsnapshot/goto. The Jsoup pipeline either fails to fetch the page or produces a document model where selectors don't match. The appendSelectorIfMissing() function in LoadOptions.kt may also corrupt selectors. Previously reported 2026-07-28, ACCEPTed, still unfixed.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:extractOutLinks() ~line 780; browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/options/LoadOptions.kt:correctOutLinkSelector() ~line 988",
      "suggestion": "- Investigate why Jsoup's document.select() returns 0 results when browser DOM shows 94 anchors — the fetch/parse pipeline may not be rendering the page correctly\n- Debug appendSelectorIfMissing() in pulsar-dom external JAR to confirm it does not corrupt CSS selectors\n- Add integration test verifying link discovery on books.toscrape.com (94 expected links)\n- When link discovery returns 0 results, check document.select('*').size and document.html.length before blaming the selector"
    },
    {
      "title": "Crawl fetch returns 0 bytes for ALL pages — 'protocol handler not ready' on every single URL",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 --json crawl --seed-file .test-sessions/books-seed.txt --depth 0 (10 book detail URLs from books.toscrape.com)",
      "expected": "All 10 pages load with content (12KB+ each) and titles.",
      "actual": "All 10 pages return contentLength: 0 with extractionError: 'fetch returned 0 bytes (possible protocol handler not ready)'. Text-mode output silently reports 'Crawl completed. 10 pages found.' with empty titles — misleading the user. JSON mode reveals the truth. This has worsened since yesterday when 1 of 10 pages worked.",
      "rootCause": "The HTTP protocol handler used for page fetching (likely OkHttp or equivalent) is never properly initialized or crashes before any request. The error 'protocol handler not ready' suggests the handler lifecycle is not managed: it may be a singleton that fails to initialize, or a per-thread resource that isn't set up for crawl worker threads. Previously reported 2026-07-28, ACCEPTed, still unfixed and now affects ALL pages (was partial before).",
      "codePointer": "browser4-core — FetchComponent or protocol handler initialization and lifecycle management",
      "suggestion": "- Investigate protocol handler lifecycle — ensure proper initialization before first fetch in crawl context\n- Add retry logic for 'protocol handler not ready' errors (at least 3 retries with exponential backoff)\n- Consider instantiating a fresh protocol handler per request rather than sharing a singleton\n- Add a pre-flight health check before each crawl to verify the handler is ready, failing fast with a clear error"
    },
    {
      "title": "Crawl --sql flag returns 'No extracted data' — X-SQL extraction through crawl is non-functional",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --sql @.test-sessions/extract-books.sql --format table (query uses load_and_select(@url, 'body') with dom_first_text for title, price, availability)",
      "expected": "Structured table output with url, title, price, and availability columns from 10 book detail pages.",
      "actual": "'No extracted data.' — crawl completes but returns zero rows. Known from memory as: 'CrawlToolExecutor drops sql/urls params (critical), and load_and_select needs cached pages (high)'.",
      "rootCause": "CrawlToolExecutor drops or mishandles the --sql and URL parameters passed to the X-SQL query engine. Additionally, load_and_select may require pages to be pre-cached (via htmlsnapshot capture or prior fetch), which the crawl pipeline doesn't do. Previously documented, still unfixed.",
      "codePointer": "browser4-rest — CrawlToolExecutor SQL parameter handling; browser4-core — load_and_select cache dependency",
      "suggestion": "- Fix CrawlToolExecutor to correctly forward --sql and URL parameters to the X-SQL engine\n- Remove or document the load_and_select cached-page dependency — either auto-cache pages before query, or switch to a fetch-on-demand model\n- Add a clear error message when --sql fails, distinguishing 'query syntax error' from 'parameter forwarding failure' from 'page not cached'"
    },
    {
      "title": "--format csv and --format json silently produce plain text when used without --sql",
      "severity": "High",
      "category": "UX",
      "reproduction": "./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --format csv --output results.csv",
      "expected": "CSV output contains comma-separated values with headers, or at minimum a loud warning that --format requires --sql.",
      "actual": "Output file contains human-readable plain text: 'Crawl completed. 10 pages found.' followed by pipe-separated lines. No CSV structure. The --format flag is silently ignored without --sql. Previously reported 2026-07-28, ACCEPTed, still unfixed.",
      "rootCause": "--format controls how X-SQL result sets are formatted. Without --sql, there is no structured result set, so the flag is a no-op. The design is intentional but the flag documentation doesn't mention the --sql dependency, and no warning is emitted.",
      "codePointer": "cli/browser4-cli/src/commands.rs — crawl output formatting logic; skills/browser4-cli/references/crawl.md — --format flag documentation",
      "suggestion": "- When --format csv/json is specified without --sql, emit structured output from page metadata (url, title, content_length) as default columns\n- At minimum, emit a prominent warning: 'Warning: --format has no effect without --sql. Use --sql to produce structured output.'\n- Document the --sql dependency explicitly in the --format flag description in help output and crawl.md"
    },
    {
      "title": "Crawl startup delay — consistently 80-100 seconds before processing begins",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "Any crawl invocation with seed file (3-10 URLs).",
      "expected": "Crawl processing begins within a few seconds of submission.",
      "actual": "Every crawl shows 'Still waiting for crawl to start...' at 16-second intervals. Minimum wait was ~80 seconds for a 3-URL crawl. The 10-URL crawl with --sql took ~100 seconds. Previously reported 2026-07-28, ACCEPTed, still present.",
      "rootCause": "The crawl task scheduler uses a 16-second polling interval and the worker pool has contention or serialization issues. New tasks queue behind existing ones even after they complete.",
      "codePointer": "browser4-rest — crawl task scheduler, worker pool, or session lifecycle",
      "suggestion": "- Reduce the polling interval from 16s to 2-3s for initial task pickup\n- Ensure completed tasks immediately release worker threads and session resources\n- Investigate whether crawl sessions share a global lock that causes serialization\n- Add a progress indicator during the waiting period (e.g., 'Queue position: 3')"
    },
    {
      "title": "Diagnostic message misleading — blames user's CSS selector when backend parsing is the root cause",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "./b4w.ps1 crawl 'https://books.toscrape.com/' --out-link-selector 'a' --depth 1",
      "expected": "Clear error indicating the backend failed to parse the page, with actionable debugging guidance.",
      "actual": "Diagnostic says 'The page loaded but the CSS selector 'a' matched zero elements. Verify the selector or check that the page content loaded correctly.' It suggests trying 'snapshot' or 'htmlsnapshot' — implying the user's selector is wrong when the page clearly has 94 anchors via htmlsnapshot. Wastes user time trying different selectors. Previously reported 2026-07-28, ACCEPTed.",
      "rootCause": "CrawlService.kt diagnostic logic assumes document.select(selector) returning 0 means the selector is the problem. It never checks whether the document itself has any DOM elements at all.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:~525-537",
      "suggestion": "- Before blaming the selector, verify the document has DOM elements: check document.select('a').size or document.html.length\n- If document has 0 anchors total, report a PARSE FAILURE, not a 'selector wrong' message\n- Add a 'document diagnostic' section: anchors found, HTML size, base URI"
    },
    {
      "title": "htmlsnapshot get all fails with internal error 'Unsupported html_snapshot method: scrapeAll'",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 goto 'https://books.toscrape.com/' → ./b4w.ps1 htmlsnapshot → ./b4w.ps1 htmlsnapshot get all text 'a' --limit 5",
      "expected": "Array of text content from first 5 anchor elements on the page.",
      "actual": "ERROR: html_snapshot_scrape_all failed: Unsupported html_snapshot method: scrapeAll. Even after explicitly running `htmlsnapshot` first as suggested by the error tip, the same error persists.",
      "rootCause": "The 'get all' subcommand uses an internal method called 'scrapeAll' which is not implemented or registered in the backend's htmlsnapshot method dispatcher. The error message suggests running 'htmlsnapshot' first, but this doesn't help since the method itself is missing.",
      "codePointer": "browser4-rest — htmlsnapshot method dispatcher (scrapeAll method registration); browser4-core — HtmlSnapshot component",
      "suggestion": "- Implement the scrapeAll method in the htmlsnapshot backend, or fix the method routing to use the correct implementation\n- If scrapeAll is intentionally unsupported, replace the error with a clear deprecation/unsupported notice pointing to the working alternative (eval with querySelectorAll)\n- Update the 'htmlsnapshot' tips section which currently recommends 'get all' as a working command"
    },
    {
      "title": "Crawl text-mode output silently hides 0-byte fetch errors — user sees success when all pages failed",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./b4w.ps1 crawl --seed-file .test-sessions/books-seed.txt --depth 0 --format table",
      "expected": "Clear indication that pages failed to load (0 bytes, protocol handler errors).",
      "actual": "Output shows 'Crawl completed. 10 pages found.' with a list of URLs and empty title columns. No mention of fetch errors. Only the JSON output (via --json flag) reveals 'contentLength: 0' and 'extractionError: fetch returned 0 bytes' for every page. A user relying on text output would believe the crawl succeeded.",
      "rootCause": "The human-readable output formatter only shows the page listing; extraction errors are suppressed. The success message 'X pages found' counts pages that failed to load alongside working ones — 'found' is ambiguous.",
      "codePointer": "cli/browser4-cli/src/commands.rs — crawl output formatting",
      "suggestion": "- Show a warning summary in text output when pages have errors: '10 pages processed (10 had fetch errors)'\n- Distinguish 'pages found' from 'pages successfully loaded' in the completion message\n- Add --verbose flag to show per-page error details in text mode\n- Make 0-byte fetches more visible in the listing (e.g., 'ERROR' instead of empty title)"
    },
    {
      "title": "Crawl list shows truncated descriptions instead of useful command summaries",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.ps1 crawl list (after running multiple crawls)",
      "expected": "DESCRIPTION column shows the first seed URL or seed file path, or URL count — enough to identify each task.",
      "actual": "DESCRIPTION column truncates long URLs with '...' (e.g., 'https://books.toscrape.com/catalogue/a-…'). The seed-file crawls show the first URL from the file rather than the seed file path or count, making tasks indistinguishable.",
      "rootCause": "The crawl list output truncates description to a fixed width. For seed-file crawls, the description is the concatenated URL list rather than the file path or count.",
      "codePointer": "cli/browser4-cli/src/ — crawl list output formatting",
      "suggestion": "- Show seed file path (or 'N URLs' for inline URLs) as description rather than the first URL\n- Add a 'URLs' column showing count\n- Support --verbose flag to show untruncated descriptions"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — The crawl command's core features (link discovery, page fetching, X-SQL extraction, structured output) are all broken by unfixed backend bugs. Only administrative commands (crawl list, status, clear) and seed-file argument parsing work correctly. The task goals requiring link discovery, content extraction, and formatted output were blocked. Seed-file crawling, crawl list/status/clear, and the workaround-based data extraction path (goto → eval → manual seed file) were the only functioning workflows.",
    "successRate": "20% — 1 of 5 task steps fully succeeded (crawl list). Seed-file creation worked but crawl couldn't fetch content. Link discovery, CSV/JSON output, and X-SQL extraction all failed due to backend bugs.",
    "issuesFound": 9,
    "majorBlockers": "Three Critical bugs make crawl unusable: (1) Link discovery returns 0 elements for all CSS selectors — the core depth>=1 crawl feature is completely broken. (2) Protocol handler returns 0 bytes for ALL pages — not a single page loaded successfully. (3) --sql X-SQL extraction returns 'No extracted data' — structured extraction through crawl is non-functional. Combined, these mean crawl can neither discover links, load pages, nor extract data — the three fundamental crawl operations are all broken.",
    "mostConfusingAspects": "For a first-time user: (a) The diagnostic blames your CSS selector when the backend is the problem — hours could be wasted trying different selectors. (b) Text output says '10 pages found' when all 10 pages have 0 bytes and fetch errors — only the --json flag reveals the truth. (c) --format csv/json produce identical plain-text output with no warning. (d) Goto and htmlsnapshot work fine on the same page, but crawl can't find a single link — the inconsistency is baffling. (e) The 80-100 second startup delay feels like a hang with no useful progress indicator.",
    "mostValuableImprovements": "1) Fix link discovery so depth>=1 crawl works — this is crawl's documented primary use case. 2) Fix the protocol handler lifecycle so pages actually load. 3) Fix --sql parameter forwarding in CrawlToolExecutor. 4) Make --format emit a clear warning when used without --sql, or generate structured output from page metadata. 5) Show fetch errors in text-mode output — don't silently report success when pages have 0 bytes. 6) Reduce the 16s polling interval to 2-3s. 7) Fix htmlsnapshot get all's 'scrapeAll' internal error.",
    "usabilityRating": 2
  }
}
```
