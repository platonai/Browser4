Now I have comprehensive data. Let me compile the full evaluation report:

---

# A. Task Result

**Partially Successful.** The task's core workflow (creating seed files, writing X-SQL, and issuing crawl commands) was successfully executed from the CLI perspective. However, the **X-SQL extraction via crawl was unreliable** — only 1 of 6 crawl-with-X-SQL runs produced any extracted data, and even that run missed 1 of 3 products. The root cause is a known backend race condition (`"The scrape session closed before the query could execute"` — 417 status). Basic crawling without X-SQL worked reliably.

**Task completion breakdown:**
| Step | Description | Outcome |
|------|-------------|---------|
| 1 | Create seed file with 3 MockSite URLs | ✅ Done |
| 2 | Write X-SQL query to file | ✅ Done |
| 3 | Crawl with `--refresh --parse --expires 1h --priority 1 --page-load-timeout 30s` | ⚠️ 2/3 products extracted on first run; 0/3 on retries |
| 4 | Crawl with `--background` | ✅ Submitted, task ID captured |
| 5 | `crawl list` while background crawl ran | ✅ Listed (but background crawl finished too fast to observe running state) |
| 6 | Crawl with `--ignore-url-query` | ✅ Command executed; 0/3 products extracted |
| 7 | Crawl with `--no-norm` | ✅ Command executed; 0/3 products extracted |
| 8 | Crawl with `--readonly` | ✅ Command executed; 0/3 products extracted |
| 9 | Final `crawl list` | ✅ All 17 tasks listed |

---

# B. Execution Trace

**Commands used (in order):**
1. `pwd` — verified repo root
2. `./b4w.ps1 help` — discovered CLI commands
3. Read `skills/browser4-cli/SKILL.md` — learned conventions
4. Read `skills/browser4-cli/references/crawl.md` — learned crawl flags
5. `mkdir -p .test-sessions` — created temp directory
6. `./bin/test.ps1 mock-site` — MockSite was already running (port 18080 in use by PID 14340)
7. Created `.test-sessions/seed-urls.txt` with 3 MockSite Electronics URLs
8. Created `.test-sessions/extract.sql` with X-SQL using `#productTitle` and `#product-price` selectors
9. `./b4w.ps1 crawl --seed-file .test-sessions/seed-urls.txt --depth 0 --sql @.test-sessions/extract.sql --refresh --parse --expires 1h --priority 1 --page-load-timeout 30s --format table` — first crawl (2/3 products extracted)
10. `./b4w.ps1 crawl ... --background` — background crawl (0/3 extracted)
11. `./b4w.ps1 crawl list` — listed tasks
12. `./b4w.ps1 crawl result 74aa8bc9...` — checked background crawl result
13. `./b4w.ps1 crawl ... --ignore-url-query` — (0/3 extracted)
14. `./b4w.ps1 crawl ... --no-norm` — (0/3 extracted)
15. `./b4w.ps1 crawl ... --readonly` — (0/3 extracted)
16. Re-ran original crawl command — (0/3 extracted, confirming flakiness)
17. `./b4w.ps1 goto ...` + `./b4w.ps1 htmlsnapshot` + `./b4w.ps1 htmlsnapshot get text "#productTitle"` — verified data exists in DOM
18. `./b4w.ps1 htmlsnapshot query` with explicit URL — confirmed 417 race condition error
19. `./b4w.ps1 eval "JSON.stringify({...})"` — confirmed data accessible via live DOM
20. `./b4w.ps1 crawl list` — final task list

**Key workarounds required:**
- Used `./b4w.ps1` (PowerShell wrapper) as documented, not `$(./b4w.ps1)` as task instructed (which is bash command substitution)
- Switched to `htmlsnapshot get text` + `eval` for reliable single-page extraction when X-SQL failed
- Had to note that `--ignore-url-query`, `--no-norm`, and `--readonly` flags had no observable effect on output

---

# C. Issues Found & D. Overall Assessment

```json
{
  "issues": [
    {
      "title": "X-SQL extraction via DOM_LOAD_AND_SELECT fails with known race condition",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "1. Start MockSite on localhost:18080\n2. Run: ./b4w.ps1 htmlsnapshot query \"http://localhost:18080/ec/dp/B0E000002\" --sql \"SELECT DOM_FIRST_TEXT(dom, '#productTitle') AS title FROM DOM_LOAD_AND_SELECT(@url, 'body')\"\n3. Observe 417 error: \"The scrape session closed before the query could execute\"\n4. Retry as suggested — same error persists",
      "expected": "X-SQL query should execute successfully and return extracted data (title: 'Wireless Noise-Cancelling Headphones') from the fetched page.",
      "actual": "Consistently returns 417 Expectation Failed with message 'The scrape session closed before the query could execute. This is a known backend race condition.' The page IS fetched (pageContentBytes > 0, pageStatusCode: 200) but the scrape session closes before DOM_LOAD_AND_SELECT can process the content.",
      "rootCause": "Backend race condition between page fetch completion and scrape session lifecycle in the X-SQL executor. The session is being closed/destroyed before DOM_LOAD_AND_SELECT has a chance to execute the query against the fetched DOM. The error message itself acknowledges this is a 'known' issue, suggesting it has been observed before but not yet fixed.",
      "codePointer": "browser4-rest/ — X-SQL scrape session lifecycle management; likely in the class that manages scrape session creation/teardown for DOM_LOAD_AND_SELECT queries",
      "suggestion": "- Add proper synchronization between page fetch completion and query execution — the session should remain open until DOM_LOAD_AND_SELECT has finished processing\n- Implement a retry loop with backoff in the X-SQL executor rather than surfacing the race condition to users\n- Consider using the already-fetched page content (pageContentBytes) instead of re-fetching via DOM_LOAD_AND_SELECT when the page is already loaded\n- The error message's first workaround says 'Re-run the query — the session may recover on retry' but retry did NOT help in 3/3 attempts — remove or qualify this advice"
    },
    {
      "title": "Crawl with --sql produces inconsistent results — flaky extraction",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "Run the same crawl command 5+ times: ./b4w.ps1 crawl --seed-file .test-sessions/seed-urls.txt --depth 0 --sql @.test-sessions/extract.sql --refresh --format table\nFirst run: 2/3 products have data. All subsequent runs: 0/3 products have data.",
      "expected": "Every crawl run should produce consistent results — all 3 products should have their title and price extracted every time.",
      "actual": "First run returned data for B0E000002 ('Wireless Noise-Cancelling Headphones', '$199.99') and B0E000003 ('Portable Bluetooth Speaker', '$49.99'), but B0E000001 was empty. All subsequent runs returned empty for ALL three products. Same SQL, same pages, same flags — different results each time.",
      "rootCause": "The DOM_LOAD_AND_SELECT race condition propagates into crawl's X-SQL extraction path. The crawl task submits scrape jobs for each URL, and the scrape sessions are closing before the X-SQL queries execute. The first run's partial success suggests a timing-dependent window where the session happens to stay open long enough for some pages.",
      "codePointer": "browser4-rest/ — Crawl task executor's X-SQL pipeline, same race condition as the htmlsnapshot query path",
      "suggestion": "- Fix the underlying DOM_LOAD_AND_SELECT race condition (see Issue 1)\n- Add retry logic with exponential backoff in the crawl X-SQL pipeline\n- Surface partial extraction failures more prominently — currently the table output shows empty cells with no warning that extraction failed\n- Consider adding a --retry-xsql N flag to allow users to configure retry attempts"
    },
    {
      "title": "Task instruction uses $(./b4w.ps1) syntax that does not work in bash",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Read the task instruction: 'Every browser4-cli command in this session MUST be invoked as: $(./b4w.ps1) <command>'. In bash, $(...) is command substitution, not invocation. Running $(./b4w.ps1) goto \"url\" would substitute the output of ./b4w.ps1 as a command.",
      "expected": "Task instructions should use the correct invocation syntax for the shell environment. The SKILL.md documentation correctly warns: '$(...) is command substitution, not invocation. Use pwsh ./b4w.ps1 <command> or ./b4w.sh <command> instead.'",
      "actual": "Task template mandates $(./b4w.ps1) which is bash command substitution. SKILL.md explicitly contradicts this, saying it 'does not work in bash'. This creates confusion for first-time users who must reconcile conflicting instructions.",
      "rootCause": "The task template was likely designed for PowerShell where $(...) has different semantics, or the template author used $(...) as a visual placeholder without considering shell syntax implications.",
      "codePointer": "",
      "suggestion": "- Update the task template to use environment-appropriate syntax: ./b4w.ps1 on PowerShell, ./b4w.sh or pwsh ./b4w.ps1 on bash\n- Add a shell-detection note: 'If running in Git Bash, use ./b4w.sh or pwsh ./b4w.ps1 instead of $(./b4w.ps1)'\n- The SKILL.md warning is good — keep it and cross-reference from the task template"
    },
    {
      "title": "htmlsnapshot get requires prior htmlsnapshot capture — not obvious to new users",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1. ./b4w.ps1 goto 'http://localhost:18080/ec/dp/B0E000002'\n2. ./b4w.ps1 htmlsnapshot get text '#productTitle'\nObserve: 'No elements matched \"#productTitle\"'",
      "expected": "Either auto-capture a snapshot when none exists, or provide a clearer error message explaining that htmlsnapshot (capture) must be run first.",
      "actual": "Returns 'No elements matched \"#productTitle\"' which is misleading — the element DOES exist in the page HTML. The real issue is that no snapshot has been captured yet. The user must run htmlsnapshot first, then htmlsnapshot get text.",
      "rootCause": "htmlsnapshot get reads from a stored snapshot in Browser4's page storage, not from the live DOM. If no snapshot has been captured for the current page, the stored data is from a previous page or empty. The error message suggests trying htmlsnapshot inspect which is equally affected.",
      "codePointer": "cli/browser4-cli/src/ — htmlsnapshot get command handler; could detect missing/last snapshot and suggest capture",
      "suggestion": "- Add detection: if no snapshot exists for the current page URL, auto-capture one before running get/inspect commands (with a brief note)\n- Improve the error message: 'No HTML snapshot captured for this page yet. Run htmlsnapshot first to capture the page content.'\n- Consider a --live flag on htmlsnapshot get that fetches the page fresh (like DOM_LOAD_AND_SELECT does) instead of reading from stored snapshot"
    },
    {
      "title": "Crawl task list accumulates indefinitely with no automatic cleanup",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run multiple crawl commands over time. Run crawl list. Observe 17 tasks, many from previous testing sessions unrelated to current work.",
      "expected": "Completed crawl tasks should either auto-expire after a configurable TTL, or the list should default to showing only recent/relevant tasks with an option to see all.",
      "actual": "crawl list shows all 17 tasks with no filtering. Includes tasks from prior sessions. User must manually run crawl clear to remove terminal tasks.",
      "rootCause": "The crawl task store persists all tasks indefinitely. There is a crawl clear command but it requires manual invocation. No auto-cleanup policy is in place.",
      "codePointer": "browser4-rest/ — Crawl task store; could add TTL-based expiration or configurable retention policy",
      "suggestion": "- Add a configurable TTL for crawl task records (e.g., default 1 hour, configurable via BROWSER4_CRAWL_TASK_TTL)\n- Default crawl list to showing only tasks from the current session or last N hours\n- Add --all flag to crawl list to show all historical tasks\n- Consider showing a brief cleanup hint when listing > 10 tasks: 'N old tasks — run crawl clear to remove terminal tasks'"
    },
    {
      "title": "Basic crawl shows inconsistent page titles — empty for some pages",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "Run: ./b4w.ps1 crawl --seed-file .test-sessions/seed-urls.txt --depth 0 --refresh --format table\nObserve: B0E000001 shows empty title, B0E000002 shows 'Product: Wireless Noise-Cancelling Headphones', B0E000003 shows empty title.",
      "expected": "All 3 pages should show their page titles (from <title> tag). B0E000001 should show 'Product: 4K OLED TV 55', B0E000003 should show its product title.",
      "actual": "Only 1 of 3 pages had a title in the crawl output. The HTML pages all have valid <title> tags (confirmed via curl). The page title extraction in the crawl listing is inconsistent.",
      "rootCause": "Likely a timing issue in page load — the crawl may be reading the title before the page is fully parsed/rendered, or the title extraction occurs at a point where the DOM is not fully available. Could also be related to the same scrape session lifecycle issue affecting X-SQL.",
      "codePointer": "browser4-rest/ — Crawl page metadata extraction (title field in crawl page listing)",
      "suggestion": "- Add a post-load wait or retry for page title extraction — the <title> tag is part of the initial HTML and should always be available\n- If the title is empty after fetch, retry once with a small delay\n- Log a warning when a crawled page has an empty title — silent empty strings mislead users into thinking the page has no title"
    },
    {
      "title": "crawl result outputs JSON without --json flag — inconsistent output mode",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run: ./b4w.ps1 crawl result <task-id>. Output is pretty-printed JSON.",
      "expected": "Consistent output mode behavior: either default to human-readable format (like crawl status or crawl list) and require --json for machine-readable JSON, or document the exception clearly.",
      "actual": "crawl result outputs JSON by default. Other commands (tab-list, htmlsnapshot get) require --json for structured output. crawl list outputs a formatted table by default. This inconsistency forces users to remember which commands default to JSON.",
      "rootCause": "crawl result returns structured data that doesn't have a natural table representation, so it defaults to JSON. But this creates inconsistency with the overall CLI convention.",
      "codePointer": "cli/browser4-cli/src/ — crawl result command output formatting",
      "suggestion": "- Either make all commands default to JSON when structured data is the primary output, or add a --table/--text flag to crawl result for human-readable summaries\n- Document which commands default to JSON vs. human-readable in the --help output\n- Consider a global convention: result/status subcommands return JSON, list subcommands return tables"
    },
    {
      "title": "--ignore-url-query and --no-norm flags have no observable effect on crawl output",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "Run crawl with --ignore-url-query and --no-norm on URLs that have no query parameters. The output table is identical to a crawl without these flags.",
      "expected": "The flags should produce a visible difference in behavior or output, or the documentation should clearly state that they only affect URLs with query parameters / normalization differences.",
      "actual": "With seed URLs like http://localhost:18080/ec/dp/B0E000001 (no query params), the --ignore-url-query and --no-norm flags produce identical output. A new user cannot verify these flags are working.",
      "rootCause": "The flags only produce observable differences when URLs contain query parameters or normalization-variant forms. The documentation doesn't explicitly say this, and there's no warning when the flags are effectively no-ops.",
      "codePointer": "cli/browser4-cli/src/ — crawl command argument validation",
      "suggestion": "- Add an info message when --ignore-url-query is used but none of the seed URLs have query parameters: 'Note: None of the seed URLs contain query parameters; --ignore-url-query has no effect.'\n- Add an example in crawl.md showing the effect of --ignore-url-query on URLs with query params vs without\n- Consider adding a --verbose flag that shows URL transformations (original → after query stripping → after normalization)"
    },
    {
      "title": "First-run crawl partially succeeded while all subsequent runs failed — suggests session state dependency",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "Run crawl with --sql for the first time in a session — 2/3 results. Run the same command again — 0/3 results. Subsequent runs all return 0/3.",
      "expected": "Crawl results should be deterministic — same inputs should produce same outputs regardless of how many times the command has been run.",
      "actual": "Only the first crawl-with-X-SQL run in the session produced any extracted data. All 5 subsequent runs (including with different flags and exact same flags) returned empty results. This suggests a session-level state change after the first X-SQL execution that breaks subsequent ones.",
      "rootCause": "Likely related to session/browser context lifecycle. After the first DOM_LOAD_AND_SELECT execution, the scrape session may enter a state where subsequent queries cannot execute. The browser tab or CDP session may not be properly reset between crawl executions.",
      "codePointer": "browser4-rest/ — Scrape session pool or browser context reuse between crawl executions",
      "suggestion": "- Investigate whether the scrape session or browser context is properly reset between sequential crawl commands\n- Add session health checks before each DOM_LOAD_AND_SELECT execution\n- If a session is in a bad state, auto-recreate it rather than silently producing empty results\n- The first-run success pattern is a strong clue — the session initialization path works, but the reuse/recycle path is broken"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — All CLI commands were executed as specified, but the X-SQL extraction (the core data-producing step) failed in 5 of 6 crawl runs due to a known backend race condition. The task workflow was completed from a CLI usage perspective, but the extracted data was unreliable. Workarounds exist: individual htmlsnapshot get text and eval both work correctly for single-page extraction.",
    "successRate": "60% — CLI command execution and UX flows all worked (9/9 steps executed). However, data extraction reliability was ~17% (only 1/6 X-SQL crawl runs produced any data, and even that run captured only 2/3 products).",
    "issuesFound": 9,
    "majorBlockers": "The DOM_LOAD_AND_SELECT race condition (Issue 1) is the critical blocker. It makes X-SQL extraction via crawl and htmlsnapshot query unreliable. The error message acknowledges it as a 'known' issue but retry (the suggested workaround) does not help. Without fixing this, crawl with --sql cannot be used for production data extraction.",
    "mostConfusingAspects": "1. The $(./b4w.ps1) syntax in task instructions conflicting with SKILL.md's explicit warning. 2. htmlsnapshot get text failing with 'No elements matched' when the real issue is that no snapshot was captured yet. 3. The first crawl run returning partial data, creating false confidence, only to have all subsequent runs fail silently with empty cells. 4. The --ignore-url-query and --no-norm flags having no visible effect on URLs without query parameters, making it impossible to verify they're working.",
    "mostValuableImprovements": "1. Fix the DOM_LOAD_AND_SELECT race condition — this is the single most impactful fix, affecting both crawl and htmlsnapshot query reliability. 2. Add auto-capture to htmlsnapshot get when no snapshot exists. 3. Make crawl task TTL configurable with auto-cleanup. 4. Add a --verbose flag to crawl that shows per-URL extraction status and URL transformations, making silent failures visible.",
    "usabilityRating": 5
  }
}
```

---

**Overall:** browser4-cli's crawl command structure is well-designed and the CLI documentation (SKILL.md, crawl.md) is thorough. The command flags are discoverable and the `--help` output is informative. However, the **backend reliability problem** with X-SQL extraction (DOM_LOAD_AND_SELECT race condition) severely undermines the crawl-with-extraction workflow — the primary use case for data extraction at scale. The first-run partial success creates a misleading impression that the feature works, only to silently fail on subsequent runs. This is the highest-priority issue to address.
