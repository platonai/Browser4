# Browser4 CLI Usability Evaluation — Crawl Link Discovery Scenario

## A. Task Result

| AC | As specified | Outcome |
|---|---|---|
| AC1 — depth 0 | ✅ PASS | "Crawl completed. 1 pages found." — exactly the hub ("Crawl Test Hub"), no Widget content |
| AC2 — `-d 2 -ol "a.product" -olp "/product/"` | ✅ PASS | 10 pages: hub + Alpha/Beta/Gamma + Delta/Epsilon/Zeta/Lambda/Mu/Nu; no category pages; count > 1 |
| AC3 — `--depth 3 --refresh` | ❌ FAIL as written | Returns **1 page** (hub only). The command omits `-ol`, and link discovery is disabled without it. **Workaround:** adding `-ol "a.product"` fetched 15 pages including all five "Deep Widget" depth-3 pages (Theta, Iota, Kappa, Lambda Prime, Mu Pro) — but only because depth limiting is broken (see Issue 1) |
| AC4 — `--seed-file` depth 0 | ✅ PASS | "URLs: 2"; Widget Alpha + Widget Gamma only; no Beta, no hub |

**Overall: 3/4 acceptance criteria pass as written; AC3's intent is achievable with a one-flag workaround (`-ol "a.product"`).** A Critical bug was discovered: `--depth` is silently ignored for depth ≥ 2 (verified by a depth-2 crawl returning the full 15-page graph including depth-3 pages, and a depth-1 crawl returning 3 pages that omits the seed page entirely).

## B. Execution Trace

**Preparation:** Verified cwd = repo root; created/used `.test-sessions/` for all temp files (logs, seed files). `./b4w.ps1 help` — rich, well-structured output; `help crawl` shows the exact AC2 flag pattern as an example. Read `skills/browser4-cli/SKILL.md` and `references/crawl.md` in full. Started MockSite via `pwsh ./bin/test.ps1 mock-site` (background, ~5 min incl. Maven build); polled port 18080 until the crawl hub served HTTP 200.

**Commands executed (all via `./b4w.ps1`):**
1. `crawl http://localhost:18080/generated/crawl/index.html --depth 0 --refresh` → 1 page ✓ (AC1)
2. `crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"` → 10 pages ✓ (AC2)
3. `crawl http://localhost:18080/generated/crawl/index.html --depth 3 --refresh` → 1 page, "Note: Link discovery disabled (no --out-link-selector)" (AC3 as specified fails)
4. `crawl <hub> -d 2 -ol "a.product"` → **15 pages** (depth limit violated — decisive test)
5. `crawl <hub> -d 3 -ol "a.product" --refresh` → 15 pages incl. Deep Widget pages (AC3 intent, workaround)
6. `crawl <hub> -d 1 -ol "a.product"` → 3 pages, **hub omitted** (decisive test)
7. `crawl --seed-file .test-sessions/ac4-seeds.txt --depth 0 --refresh` → 2 pages ✓ (AC4)
8. `crawl list`, `crawl status <id>`, `crawl --seed-file … --verbose`, `crawl … --json`, bare `crawl <hub>`, empty seed file, `crawl` with no args — exercised subcommands/error paths.

**Decisions:** Ran the AC3 command verbatim first (to observe real behavior), then ran a corrected variant and documented the discrepancy. Added two discriminating crawls (depth 2 & depth 1 without pattern) to prove depth limiting is broken rather than assume it. Read backend code (`CrawlService.kt`, `NormURL.kt`, CLI `main.rs`/`commands.rs`) to confirm root causes. **Workaround needed for AC3:** prepend `-ol "a.product"` to the documented command.

```json
{
  "issues": [
    {
      "title": "--depth limit is silently ignored for depth>=2 crawls (unbounded traversal)",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol \"a.product\"  → 15 pages including depth3/*.html \"Deep Widget\" pages. Identical result set to -d 3. Expected: 10 pages (hub + 9 product pages), no depth3 pages.",
      "expected": "A depth-2 crawl fetches hub (d0), products 1-3 (d1), products 4-9 (d2) and stops. A depth-3 crawl additionally fetches depth3/8-12.",
      "actual": "Both -d 2 and -d 3 return the same 15 pages: the full connected graph. Depth limiting does nothing; traversal is bounded only by URL dedup (on cyclic real sites this accidentally stops; on deep acyclic sites the crawl runs to exhaustion). AC2's correct-looking 10-page result was an accident of the -olp \"/product/\" pattern filtering depth3 links — depth limiting played no role.",
      "rootCause": "crawlDepthN (CrawlService.kt:718) embeds depth as a synthetic '-depth N' flag in the URL args string (buildArgsForDepth, CrawlService.kt:1107). LoadOptions has no 'depth' field, so when the page is fetched the arg is dropped and page.configuredUrl (NormURL.kt:66, urlSpec = url + options.toString()) no longer contains '-depth N'. extractDepth() (CrawlService.kt:1098) regex-matches page.configuredUrl, always fails, and falls back to currentDepth=1 for every page. The recursion guard 'if (currentDepth < maxDepth)' is then always true, so every page submits its children with '-depth 2' args regardless of requested depth.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:crawlDepthN() and :extractDepth()",
      "suggestion": "- Track depth in a ConcurrentHashMap<String, Int> keyed by normalizeForVisit(url) inside crawlDepthN, set at submit time (seed=0, children=currentDepth+1), and read it in the parseHandler instead of regex-parsing configuredUrl\n- Remove the '-depth N' embedding from buildArgsForDepth since it is a no-op (or add a real 'depth' field to LoadOptions if that is the intended channel)\n- Add a regression e2e test using the mock-site /generated/crawl/ fixtures asserting a depth-2 crawl returns 10 pages and no depth3/*.html URLs\n- Update the 'No links found (depth >= 1)' row in crawl.md error-handling table to note depth is enforced per-page"
    },
    {
      "title": "Every page in multi-level crawl output is mislabeled depth=1 (including the hub)",
      "severity": "High",
      "category": "Product",
      "reproduction": "crawl http://localhost:18080/generated/crawl/index.html -d 3 -ol \"a.product\" --refresh  → all 15 result rows print 'depth=1', including the hub page (should be 0) and the depth-3 pages (should be 3).",
      "expected": "Result rows show the actual crawl depth per page: hub depth=0, products 1-3 depth=1, products 4-9 depth=2, depth3/* depth=3.",
      "actual": "All rows show depth=1, making it impossible to verify multi-level traversal from the output (AC3's 'results include intermediate pages from depth 1 and 2' is unverifiable). The depth= column is effectively decorative.",
      "rootCause": "Same as Issue 1: extractDepth(page) ?: 1 fallback fires for every page because '-depth N' never survives LoadOptions re-serialization. crawlDepth0 hardcodes depth=0 (correct) and crawlDepth1 hardcodes depth=1 for out-links, so only the depth-N path is affected.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:extractDepth() (line 1098) and crawlDepthN() parseHandler (line 742)",
      "suggestion": "- Fix once by the Issue 1 fix (depth map); the recorded depth then feeds the displayed depth automatically\n- Add a unit test for extractDepth-adjacent logic covering seed and child pages\n- Consider grouping or sorting output by depth so users can see the level structure at a glance"
    },
    {
      "title": "Depth-1 crawls (and bare 'crawl <url>') omit the seed page from results; help's first example returns '0 pages found'",
      "severity": "High",
      "category": "UX",
      "reproduction": "1) crawl http://localhost:18080/generated/crawl/index.html -d 1 -ol \"a.product\"  → 'Crawl completed. 3 pages found.' lists only the 3 product pages; the hub (which was fetched) is absent. 2) crawl http://localhost:18080/generated/crawl/index.html  (the first example in 'crawl --help') → prints 'Note: Link discovery disabled... Processing seed URLs only.' then 'Crawl completed. 0 pages found.'",
      "expected": "The seed page appears in results at depth=0 in both cases (depth-0 and depth>=2 crawls do include the seed), and the help example should produce a non-empty, non-confusing result.",
      "actual": "crawlDepth1 records only out-link pages (CrawlService.kt:662-684) and never adds the portal page; with no -ol it returns emptyList() before fetching anything (CrawlService.kt:592-593). The bare command's own messages contradict each other: 'Processing seed URLs only' followed by '0 pages found'. A first-time user following the help's first crawl example gets an empty result and generic tips that do not say to add -ol.",
      "rootCause": "crawlDepth1 (CrawlService.kt:569) has no code path that records the portal page as a result. The depth-0 path (crawlDepth0) records seeds and the depth-N path records everything, so result-set semantics vary wildly by the depth argument: d0=[seed], d1=[out-links only], d2+=[seed+all]. The 0-pages case is the documented 'no selector' behavior, but the CLI's 'Processing seed URLs only' note over-promises what the backend returns.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:crawlDepth1() (line 569); cli/browser4-cli/src/main.rs:10763 (the Note text)",
      "suggestion": "- Add the portal page as a depth=0 result entry in crawlDepth1 (title/extraction for it too), making all depth modes consistent\n- When no -ol is given and no --sql, either return the seed page as a depth-0 result (honoring the Note) or change the Note to 'Link discovery disabled (no --out-link-selector). Nothing to crawl — add -ol \"a\" to follow all links.'\n- Update the crawl help example list: annotate 'crawl <url>' with '(returns 0 pages without --out-link-selector)' or replace it with a working example"
    },
    {
      "title": "Scenario AC3 spec contradicts documented and actual product behavior (missing -ol flag)",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Run the task's AC3 command verbatim: crawl http://localhost:18080/generated/crawl/index.html --depth 3 --refresh  → 1 page ('Crawl Test Hub'), no traversal, no Deep Widget pages.",
      "expected": "Per the acceptance criteria, this command should traverse 4 levels (0-3) and reach depth-3 'Deep Widget' pages.",
      "actual": "The CLI prints 'Note: Link discovery disabled (no --out-link-selector). Processing seed URLs only.' and returns only the seed page. The product help text and crawl.md both state -ol is required for link discovery, so the product is self-consistent; the scenario spec is wrong. AC3 is only achievable by adding -ol \"a.product\".",
      "rootCause": "Scenario/task documentation was written assuming a default out-link selector (e.g. a[href]) exists; no such default exists in CrawlService (blank selector returns empty results) or the CLI (it only warns).",
      "codePointer": "",
      "suggestion": "- Fix the scenario doc: 'crawl <url> --depth 3 -ol \"a.product\" --refresh' (the fixture's product chain reaches the depth3 pages via a.product links)\n- Or, as a product decision, default -ol to 'a[href]' when depth >= 1 — this would also fix the bare-crawl zero-result trap in Issue 3 (needs a doc + test update either way)\n- Add a scenario note that depth limiting is currently broken for depth >= 2 (see Issue 1) so depth-3 acceptance can be verified via Deep Widget presence rather than exact page counts"
    },
    {
      "title": "Confusing crawl progress output: 'N/1 pages found' denominator and inconsistent message formats",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any multi-level crawl. Observed lines: 'Crawling... waiting for first page (6s elapsed, 1 URLs queued)' repeated every 10s, then 'Crawling... 15/1 pages found (86s elapsed)'. Depth-0 seed-file crawls show alternating 'Crawling... 1 pages found so far' and 'Crawling... 1/2 pages found (6s elapsed)'.",
      "expected": "Consistent progress lines where the denominator means something (e.g. total submitted URLs or expected pages), and stable formatting across polls.",
      "actual": "'15/1 pages found' reads as if the crawl overshot its own target — the '1' is the initial seed count, not a goal. Three different message formats appear depending on poll state and mode, and 'pages found' can exceed the 'queued' figure, which looks like a bug to a new user.",
      "rootCause": "The CLI renders several distinct polling states with different templates; the denominator comes from the seed-URL count while the numerator is the live pagesFound counter (cli/browser4-cli/src/main.rs around the crawl polling loop, e.g. lines 10980-11020 region).",
      "codePointer": "cli/browser4-cli/src/main.rs (crawl polling/progress rendering)",
      "suggestion": "- Use a single template like 'Crawling... {found} pages found, {queued} URLs queued' and drop the misleading 'N/M' ratio when M is just the seed count\n- Distinguish phases explicitly: 'loading portal page...' → 'extracting links...' → 'processing page N of M...' to fill the ~80s of identical 'waiting for first page' lines\n- Sort or dedupe the alternating formats seen in depth-0 polling"
    },
    {
      "title": "--verbose shows no extra output for depth-0 seed-file crawls despite documented 'per-URL processing status'",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "crawl --seed-file <2-url file> --depth 0 --refresh --verbose  → output byte-identical to the non-verbose run; no 'Seed URL Status:' section appears.",
      "expected": "Per 'crawl --help' (--verbose: Show per-URL processing status in crawl results), verbose should add per-URL status rows.",
      "actual": "No difference. The verbose rendering branch only prints seedStatuses, and the backend's depth-0 response contains no seedStatuses array (confirmed via --json: output has only task_id, pages, pages_found).",
      "rootCause": "crawlDepth0 does not populate seedStatuses in the CrawlResponse, while the CLI's verbose branch (main.rs:11096) keys entirely off that field. The depth-1 path does populate it (observed via crawl status), so verbose works there but not in the most common bulk-fetch mode.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:crawlDepth0(); cli/browser4-cli/src/main.rs:11096",
      "suggestion": "- Populate seedStatuses (url/status/pagesReturned) in crawlDepth0 responses so --verbose renders for bulk fetches\n- Or render per-page contentLength/depth when verbose regardless of seedStatuses\n- Update help text to say which modes support verbose detail if intentionally limited"
    },
    {
      "title": "crawl status prints raw pretty-printed JSON, not the human-readable summary crawl.md describes",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "crawl status <task-id>  → emits a raw JSON object (pages array, pagesFound, seedStatuses, createdAt as epoch millis, finishTime as ISO) with no status summary line.",
      "expected": "Per crawl.md: 'Shows whether the task is CREATED, PROCESSING, or completed (OK), along with pages found so far' — a short human summary.",
      "actual": "Raw JSON dump; the human status (CREATED/PROCESSING/OK) described in the docs is not visibly rendered, and createdAt epoch-millis is unreadable. Works for machine consumption but contradicts the documented UX; contrast with 'crawl list' which renders a clean table.",
      "rootCause": "The CLI renders the backend status response as-is for crawl status (unlike crawl list which is formatted); docs describe an intended rendering that doesn't exist.",
      "codePointer": "cli/browser4-cli/src/main.rs (crawl status handler)",
      "suggestion": "- Render a one-line summary (task id, status, pages found, elapsed) and show raw JSON only under --json\n- Convert createdAt to local time in any display\n- Update crawl.md if JSON output is intentional"
    },
    {
      "title": "Slow first page in multi-level crawls (~85-95s even for a 15-page localhost fixture) with no phase detail",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "crawl http://localhost:18080/generated/crawl/index.html -d 3 -ol \"a.product\" --refresh  → 'waiting for first page' repeats for ~96s before any page is found; the full 15-page crawl takes ~98s on localhost.",
      "expected": "For 15 static localhost pages, a crawl should complete in a few seconds after the initial browser session is up, and progress should indicate what phase is running.",
      "actual": "Roughly 90s of identical 'waiting for first page (Xs elapsed, 1 URLs queued)' lines. Likely acceptable overhead of sequential browser loading + parse pipeline, but the progress gives no signal whether the crawl is hung or progressing (a user may abort a healthy crawl).",
      "rootCause": "Not definitively isolated: sequential session loadDocument pipeline and per-page browser overhead; no instrumentation surfaced to the CLI progress stream. Note the crawl actually finished shortly after the first page appeared, suggesting the portal-page load + link extraction dominates.",
      "codePointer": "",
      "suggestion": "- Emit phase-level progress (portal fetch, link extraction, per-page processing) to stderr during foreground crawls\n- Profile the depth-N path for the localhost case; check whether the portal page is loaded through a fresh browser each run\n- Add a 'elapsed' heartbeat distinct from 'pages found' so users can distinguish slow from stuck"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — AC1, AC2 and AC4 passed exactly as specified. AC3 as written is unsatisfiable (missing required -ol flag per product docs) and fails with a 1-page result; its intent (reaching depth-3 Deep Widget pages) was achieved with the workaround 'crawl <url> -d 3 -ol \"a.product\" --refresh'. Investigation also exposed a Critical depth-limiting bug that invalidates the depth semantics the scenario is meant to verify.",
    "successRate": "75% — 3 of 4 acceptance criteria pass as written; AC3 requires a one-flag workaround. Underlying crawl correctness for multi-level traversal is broken (depth limit ignored), so confidence in the results is low even where pages were returned.",
    "issuesFound": 8,
    "majorBlockers": "AC3 command in the scenario spec cannot traverse (link discovery requires --out-link-selector, contradicting the spec). The --depth flag is silently ignored for depth>=2: a depth-2 crawl returned the full 15-page graph including depth-3 pages, so depth semantics cannot be trusted.",
    "mostConfusingAspects": "1) Why '--depth 3' alone fetches only one page (no -ol → no link discovery). 2) Every page labeled depth=1 in multi-level results. 3) 'Crawl completed. 3 pages found.' not including the start page, and the help's first crawl example returning '0 pages found'. 4) Progress lines like '15/1 pages found' whose denominator is just the seed count.",
    "mostValuableImprovements": "1) Fix depth tracking in crawlDepthN (carry depth alongside submissions instead of parsing it from the URL args string) — one fix restores depth limiting AND correct depth labels. 2) Make crawlDepth1 include the seed page so result sets are consistent across depth modes and bare 'crawl <url>' stops being a zero-result trap. 3) Align the scenario documentation with the required -ol flag (or introduce a default selector). 4) Unify progress output with phase-level messages.",
    "usabilityRating": 5
  }
}
```
