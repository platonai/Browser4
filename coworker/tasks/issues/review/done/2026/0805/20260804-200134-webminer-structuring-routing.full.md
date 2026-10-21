## A. Task Result

### Overall: Partially Successful (~50% of acceptance criteria met)

| Criterion | Status | Summary |
|---|---|---|
| **AC3** — Single-page acquisition | ✅ Success | 3 product pages exported via `goto` + `htmlsnapshot capture` + `htmlsnapshot export` |
| **AC1** — WebMiner free pipeline | ⚠️ Partial | encode + cluster stages completed; views stage crashed (POI error with too few docs); only 1/3 files encoded |
| **AC4** — Crawl bulk acquisition | ❌ Failed | "Protocol not found" for localhost URLs; crawl took ~2 min/page; CLI timed out |
| **AC5** — Swarm high-throughput | ❌ Failed | All 6 jobs stuck in "queued" state; same "Protocol not found" issue |
| **AC2** — Production-scale decision | ✅ Documented | Decision analysis completed (theoretical, as specified) |

The fundamental blocker: both `crawl` and `swarm` use the Pulsar internal scrape API which cannot fetch `http://localhost` URLs (returns "Protocol not found"), while `goto` works perfectly via the browser-based CDP navigation path.

---

## B. Execution Trace

### Commands used (in order)

1. `./b4w.sh help` — learned available commands
2. `./b4w.sh goto "http://localhost:18080/ec/dp/B0E000001"` — navigate to product page 1
3. `./b4w.sh htmlsnapshot capture` — capture static HTML (note: needed explicitly; goto doesn't auto-capture htmlsnapshot)
4. `./b4w.sh htmlsnapshot export --file .test-sessions/html-corpus/B0E000001.html` — export HTML
5. `./b4w.sh goto "http://localhost:18080/ec/dp/B0E000002"` — product page 2
6. `./b4w.sh htmlsnapshot export --file .test-sessions/html-corpus/B0E000002.html` — export
7. `./b4w.sh goto "http://localhost:18080/ec/dp/B0H000001"` + `htmlsnapshot export` — product page 3
8. `pwsh ./skills/scent-miner/scripts/webminer.ps1 install` — failed (Windows-only path)
9. `curl -fsSL ... -o ~/.scent/webminer/lib/scent-miner.jar` — manual JAR download
10. `java -jar scent-miner.jar all .test-sessions/html-corpus/` — pipeline ran; views stage crashed
11. `./b4w.sh crawl --seed-file .test-sessions/seed-urls.txt --depth 0 --refresh` — timed out
12. `./b4w.sh swarm create --display-mode HEADLESS --clear-stale` — session created
13. `./b4w.sh swarm query --seed-file ... --sql @query.sql --refresh --wait` — all jobs stuck queued

### Workarounds required

- **WebMiner install:** Downloaded JAR directly from GitHub releases (webminer.ps1 is Windows-only)
- **htmlsnapshot capture:** Had to run explicit `htmlsnapshot capture` before export (not obvious that goto doesn't do this)
- **Crawl/swarm:** Neither worked for localhost; had to cancel and move on

### Key decision: Manual JAR download

The `webminer.ps1` script hardcodes `$env:USERPROFILE\.scent\webminer` which doesn't exist on Linux. Downloaded the JAR directly from `https://github.com/platonai/web-miner/releases/latest/download/scent-miner.jar` to `~/.scent/webminer/lib/`.

---

## C. Issues Found

```json
{
  "issues": [
    {
      "title": "webminer.ps1 installer hardcodes Windows-only $env:USERPROFILE path",
      "severity": "High",
      "category": "Product",
      "reproduction": "pwsh ./skills/scent-miner/scripts/webminer.ps1 install",
      "expected": "Installer works cross-platform (Linux, macOS, Windows) since Java is cross-platform.",
      "actual": "Join-Path fails with 'Cannot bind argument to parameter Path because it is null' because $env:USERPROFILE does not exist on Linux. The script should use $HOME or ~ on Unix platforms.",
      "rootCause": "The PowerShell script at line 56 uses $env:USERPROFILE which is a Windows-only environment variable. On Linux/macOS, the home directory is in $HOME or ~.",
      "codePointer": "skills/scent-miner/scripts/webminer.ps1:56",
      "suggestion": "- Replace $env:USERPROFILE with a cross-platform expression: if ($IsWindows) { $env:USERPROFILE } else { $env:HOME }\n- Or use Join-Path ~ '.scent/webminer' which PowerShell resolves correctly on all platforms\n- Add a fallback check: if neither env var exists, error with a clear message telling the user to set HOME"
    },
    {
      "title": "goto does not automatically capture htmlsnapshot — requires separate explicit capture step",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1. ./b4w.sh goto 'http://localhost:18080/ec/'\n2. ./b4w.sh htmlsnapshot inspect '.product-card'\n3. Observe: 0 matches (no snapshot captured)\n4. ./b4w.sh htmlsnapshot capture  (explicit step)\n5. ./b4w.sh htmlsnapshot inspect '.product-card'\n6. Observe: 5 matches",
      "expected": "goto should either auto-capture htmlsnapshot, or the error message should clearly say 'No HTML snapshot has been captured yet. Run htmlsnapshot capture first.'",
      "actual": "After goto, htmlsnapshot inspect reports '0 matches' with a generic message about checking the CSS selector. It does not tell the user that no snapshot was ever captured.",
      "rootCause": "goto auto-captures an accessibility tree (AXTree) snapshot for element refs but does NOT trigger htmlsnapshot capture (which stores the static HTML DOM). The htmlsnapshot inspect/get/summary commands read from a separate storage that is only populated by explicit htmlsnapshot capture. Users naturally expect the navigation to capture both.",
      "codePointer": "cli/browser4-cli/src/commands.rs or the goto handler — where post-goto auto-snapshot is triggered",
      "suggestion": "- Consider auto-capturing htmlsnapshot after goto, or at minimum after the first htmlsnapshot command following a navigation\n- At minimum, improve the '0 matches' error message to include: 'No HTML snapshot found. Run htmlsnapshot capture first.'\n- Document in the goto output tip that htmlsnapshot capture is needed for content extraction"
    },
    {
      "title": "Scrape API returns 'Protocol not found' for localhost URLs — breaks crawl and swarm",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "./b4w.sh crawl --seed-file seed-urls.txt --depth 0 --refresh  (with localhost:18080 URLs)\n./b4w.sh swarm query --seed-file seed-urls.txt --sql @query.sql --refresh --wait",
      "expected": "Crawl and swarm should fetch localhost URLs the same way goto does, completing quickly.",
      "actual": "Crawl: each URL takes ~2 minutes with 'Protocol not found' warning before eventually succeeding. Swarm: all jobs stuck in 'queued' state indefinitely with 'Protocol not found' warnings. The goto command works instantly for the same URLs.",
      "rootCause": "goto uses the browser's CDP-based navigation (which handles any protocol the browser supports), while crawl and swarm use the Pulsar internal fetch component (FetchComponent) which appears to lack a protocol handler for http://localhost. The warning 'Protocol not found | http://localhost:18080/...' in the logs (pulsar log, FetchComponent class) confirms this.",
      "codePointer": "browser4-core/browser4-browser/...FetchComponent.kt (the class emitting 'Protocol not found' warnings)",
      "suggestion": "- Register an HTTP protocol handler for localhost in the Pulsar fetch component\n- As a workaround, use 127.0.0.1 instead of localhost (though this should be tested)\n- Add a clear CLI-level error message when localhost URLs are detected, suggesting the goto path instead\n- Consider falling back to browser-based fetch when the protocol handler is missing for a URL scheme"
    },
    {
      "title": "WebMiner views stage crashes with empty-sheet POI exception when too few documents are encoded",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "Run java -jar scent-miner.jar all on a directory with only 3 HTML files, where only 1 gets encoded.",
      "expected": "Graceful error message: 'Not enough documents encoded (1) to build meaningful views. Need at least N documents.'",
      "actual": "java.lang.IllegalArgumentException: Sheet index (0) is out of range (no sheets) at XSSFWorkbook.validateSheetIndex — a raw Java stack trace with no user-facing explanation.",
      "rootCause": "The ContextualColumnsBuilder.loadRevisedRecords() method at line 86 tries to read sheet 0 from a workbook that has no sheets. This happens when there are too few encoded documents to produce meaningful Excel output. There is no guard clause checking whether the workbook has any sheets before accessing them.",
      "codePointer": "ai.platon.scent.ml.ContextualColumnsBuilder.loadRevisedRecords (ContextualColumnsBuilder.kt:86)",
      "suggestion": "- Add a guard clause before accessing sheet 0: check workbook.getNumberOfSheets() > 0\n- Surface a user-friendly error: 'Cannot build views: clustering produced no grouped results. Try with more input documents.'\n- Document minimum document count requirements in the SKILL.md (e.g., 'at least 10-20 documents recommended')"
    },
    {
      "title": "WebMiner encoder silently skips HTML files without clear explanation",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run scent-miner.jar all with 3 HTML files. Output shows 'Found 3 HTML file(s)' but 'Encoded 1 document(s)' with no explanation for the 2 skipped files.",
      "expected": "Clear per-file status: which files were skipped and why (e.g., 'B0E000001.html: skipped — no valid text nodes found', 'B0E000002.html: skipped — encoding failed with X error').",
      "actual": "The pipeline reports 'Scanned 3 of 3 HTML file(s)' and 'Encoded 1 document(s)' with no indication of which files failed or why. The user cannot diagnose the problem.",
      "rootCause": "The encoder's progress reporting only shows counts (scanned vs encoded) without per-file status. Failed/skipped files are silently dropped during the encoding loop, possibly in the HTML parsing or feature extraction phase where exceptions are caught and files are skipped without logging.",
      "codePointer": "ai.platon.scent.miner.WebMinerEngine or the encoder stage implementation",
      "suggestion": "- Log each skipped file with a reason at WARN level during the encoding loop\n- Add a summary at the end: 'Skipped 2 files: B0E000001.html (no text content), B0E000002.html (parse error)'\n- Consider lowering the validity threshold for very small corpora to avoid skipping files"
    },
    {
      "title": "Task instructions specify $(./b4w.ps1) which does not work on Linux/bash",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Run $(./b4w.ps1) goto 'http://example.com' in bash on Linux.",
      "expected": "Task instructions should specify a working cross-platform invocation or note the platform-specific wrapper.",
      "actual": "SKILL.md already documents this as a known issue: 'The $(./b4w.ps1) <command> syntax shown in some task instructions does not work in bash — $(…) is command substitution, not invocation.' Nevertheless, the task prompt itself uses this syntax.",
      "rootCause": "Task instructions were written assuming Windows/PowerShell. In bash, $(...) is command substitution which tries to execute the output of ./b4w.ps1 as a command, which fails.",
      "codePointer": "",
      "suggestion": "- Update task templates to use platform-agnostic or multi-platform invocation patterns\n- Or document at the top of task instructions: 'On Linux/macOS, replace $(./b4w.ps1) with ./b4w.sh'"
    },
    {
      "title": "Swarm jobs stuck in 'queued' state with no progress or clear error feedback",
      "severity": "High",
      "category": "UX",
      "reproduction": "Submit swarm query jobs against localhost URLs. Wait 5+ minutes.",
      "expected": "Jobs should either: (a) execute and complete, (b) fail with a clear error message explaining why, or (c) show incremental progress.",
      "actual": "All 6 jobs remain in 'queued' state (statusCode 201 Created) indefinitely. The CLI polling shows '0/6 completed' with elapsed time. The only clue is the 'Protocol not found' warning buried in server logs. No error surfaced to the CLI user.",
      "rootCause": "The swarm worker picks up the job, encounters 'Protocol not found' during fetch, but the job stays in 'Created'/'queued' state instead of transitioning to a failed/error state with a descriptive message. The statusCode 201 (Created) is misleading — it suggests the job was just created, not that it's stuck.",
      "codePointer": "browser4-rest/src/.../CrawlService.kt or swarm task lifecycle management",
      "suggestion": "- When a fetch fails with 'Protocol not found' or any fatal fetch error, transition the job to a 'failed' state with the error message\n- Include the failure reason in swarm status output: 'Failed: Protocol not found for http://localhost:18080/...'\n- Add a timeout for queued jobs: if a job stays in 'queued' for >60s, surface a warning in swarm list"
    },
    {
      "title": "Crawl progress display shows no per-URL status or error details",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run crawl --seed-file ... --depth 0 and watch the 'Crawling... waiting for first page' output.",
      "expected": "Show per-URL progress: 'Fetching 1/6: http://localhost:18080/ec/dp/B0E000001 (10s elapsed)' with status updates.",
      "actual": "Repeated 'Crawling... waiting for first page (Xs elapsed)' with no per-URL details. After 4 minutes only shows the same message. The user has no idea which URL is being processed or whether anything is happening.",
      "rootCause": "The crawl CLI polling uses a generic 'waiting for first page' message that doesn't update with per-URL status from the backend. The backend does log per-URL progress ('processing seed URL 1/6') but this is only visible in server logs, not in the CLI output.",
      "codePointer": "cli/browser4-cli/src/commands.rs — the crawl polling/display logic",
      "suggestion": "- Enhance the polling display to show per-URL status: current URL, completed count, failed count\n- When a crawl URL takes >30s, show a warning with the URL: 'Still fetching http://... — may be slow or unreachable'\n- Add a --verbose flag to show per-URL timing and status from the backend"
    },
    {
      "title": "No distinction between primary and secondary product URLs — B0E000003 unexpectedly worked",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "The home page showed 5 trending products (B0E000001, B0E000002, B0H000001, B0T000001, B0B000001). B0E000003 was not listed but was still accessible.",
      "expected": "Documentation or the mock site should clearly indicate which product IDs are available and what URL patterns exist.",
      "actual": "B0E000003 existed as 'Portable Bluetooth Speaker' even though it wasn't linked from the home page. A user has no way to discover available URLs on the mock site without guessing.",
      "rootCause": "The MockSite generates product pages programmatically with sequential IDs. Some are linked from the home page, others exist but are undiscoverable without browsing category pages or guessing IDs.",
      "codePointer": "",
      "suggestion": "- Document the MockSite URL structure in a readme or the test fixture description\n- Add a sitemap endpoint (e.g., /ec/sitemap.xml) listing all available product URLs\n- List all available product IDs in the test scenario instructions"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — AC3 (single-page export) and AC2 (documentation) completed. AC1 (WebMiner pipeline) partially completed — encode and cluster stages succeeded but views stage crashed. AC4 (crawl) and AC5 (swarm) both failed due to the same root cause: the Pulsar scrape API cannot handle localhost URLs, while browser-based navigation (goto) works fine.",
    "successRate": "50% — 2.5 out of 5 acceptance criteria met (AC3 fully, AC2 fully, AC1 partially, AC4 failed, AC5 failed)",
    "issuesFound": 9,
    "majorBlockers": "The 'Protocol not found' error in the Pulsar FetchComponent for localhost URLs is the critical blocker. It prevented crawl and swarm from working, which are the two primary bulk-acquisition paths documented in SKILL.md §4d. Without these, the scenario cannot demonstrate the full acquisition → pipeline workflow.",
    "mostConfusingAspects": "1. The goto command works instantly for localhost URLs but crawl/swarm fail silently — there's no indication these use different fetch mechanisms. 2. htmlsnapshot capture is not automatic after goto, leading to confusing '0 matches' results from inspect. 3. The webminer.ps1 installer fails on Linux with a PowerShell error that doesn't explain the platform issue. 4. Swarm jobs show 'queued' status (201 Created) indefinitely with no error — a first-time user would just keep waiting.",
    "mostValuableImprovements": "1. Fix the localhost protocol handler in the Pulsar fetch component (Critical — unblocks crawl and swarm for local testing). 2. Auto-capture htmlsnapshot after goto or provide a clear error when inspect/get is used without a captured snapshot. 3. Surface fetch errors (Protocol not found, timeouts) directly in swarm/crawl status output instead of burying them in server logs. 4. Add per-URL progress display to crawl polling output. 5. Make webminer.ps1 cross-platform.",
    "usabilityRating": 5
  }
}
```
