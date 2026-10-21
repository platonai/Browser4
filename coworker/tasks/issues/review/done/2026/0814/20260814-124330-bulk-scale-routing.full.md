# A. Task Result

**All 6 acceptance criteria completed successfully.** Every branch of SKILL.md §4b (Choosing Bulk/Scale Approach) was exercised against the MockSite at `localhost:18080`, and each extraction result was verified for correctness:

| AC | Approach | Result |
|----|----------|--------|
| AC1 | `htmlsnapshot query` + `DOM_LOAD_AND_SELECT` on listing page | **6 rows**, one per `.product-card`, with correlated title + price + link (e.g. "4K OLED TV 55" / $899.99 / …/dp/B0E000001) |
| AC2 | `crawl --seed-file … --depth 0 --sql @query --format table --refresh` | **3 rows**, exactly one per seed URL, url/title/price correlated |
| AC3 | `crawl <url> -d 2 -ol "a.product" -olp "/product/"` | **10 pages** (hub + 9 product pages); category/promo/utility links correctly excluded |
| AC4 | `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` + `swarm query --seed-file` + `status`/`result` | 3 tasks → all `completed`; each `resultSet` had the expected row; swarm closed afterward |
| AC5 | `loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "…"` | 2 iterations at 10s intervals, both returning `$899.99` from the named session; `loop --list`/`--status` showed live progress |
| AC6 | Bash `for` loop: `goto` → `htmlsnapshot` → `htmlsnapshot get text "#productTitle"` + `sleep 2` | 3 product titles extracted correctly; no crawl/swarm/loop needed |

# B. Execution Trace

**Commands used (all via `./b4w.ps1`):** `help`, `status`, `list`, `goto`, `htmlsnapshot`, `htmlsnapshot query --sql @file`, `crawl` (seed-file, depth-0, and link-discovery variants), `swarm create|query|status|result|close|list`, `loop --list|--status`, `loop --name … -- … -s … eval`, plus a bash `for` loop script.

**Major steps:**
1. Verified cwd, ran `./b4w.ps1 help`, read `skills/browser4-cli/SKILL.md` and the crawl/swarm/loop/X-SQL reference docs.
2. Started MockSite with `pwsh ./bin/test.ps1 mock-site` (background); it came up on port 18080 after ~4 min (Maven preflight + spring-boot:run).
3. Inspected fixture sources (`EcommerceController.kt`, `HtmlRenderer.kt`, `generated/crawl/index.html`) to confirm selectors (`.product-card/.product-title/.product-price` on listings; `#productTitle`/`#product-price` on details) before writing queries — docs matched reality here.
4. AC1: `goto` auto-built the local runtime bundle and started the backend (17s, spinner showed JVM → Spring Boot → MCP stages); captured snapshot; ran X-SQL with `@url` placeholder → 6 correlated rows.
5. AC2: seed file + detail query file → 3-row table output with live progress lines ("1/3 seeds done…").
6. AC3: link-discovery crawl with selector+pattern filters → 10 pages, categories excluded.
7. AC4: headless swarm (2 contexts / 4 tabs) over the same seed+query files; polled statuses until `completed`, fetched all 3 resultSets, closed swarm.
8. AC5: named session `price-watch`; named loop in subcommand mode; verified `loop --list` and `--status` mid-run; confirmed 2 iterations of `$899.99`.
9. AC6: bash for-loop script (in `.test-sessions/`) with `sleep 2` between pages → 3 titles.
10. Root-caused the notable issues in the CLI/backend source (see below).

**Decisions & workarounds:**
- All scratch files (seed/query files, shell script, deliverable JSON) created under `.test-sessions/`; repo root kept clean.
- The first `htmlsnapshot` after backend start timed out at 60s — retried once and it succeeded (recorded as a Reliability issue).
- Used `swarm create --clear-stale`-style non-interactive behavior implicitly (the stale-task warning was informational; jobs ran fine).
- Left sessions and backend running for the harness to reuse.

```json
{
  "issues": [
    {
      "title": "snapshot list triggers a post-command auto-snapshot (read-only command writes a new file)",
      "severity": "High",
      "category": "Product",
      "reproduction": "./b4w.ps1 snapshot list (with an active session; backend running)",
      "expected": "Only the list of saved snapshot files should be printed; no new snapshot file should be created.",
      "actual": "The list prints correctly, then a second block is appended (### Page / ### Snapshot / Tip) and a new snapshot-*.yml file is written to .browser4-cli/snapshot — the read-only 'list' command performs a capture as a side effect.",
      "rootCause": "The CLI runs a post-command auto-snapshot for most commands. The exclusion list no_snapshot_commands() in cli/browser4-cli/src/main.rs contains \"snapshot\" and \"snapshot-grep\" but not the subcommand names \"snapshot-list\" and \"snapshot-clean\", so snapshot list falls through to the auto-capture path (auto_snapshot_after_command, which saves a file and prints the Page/Snapshot block).",
      "codePointer": "cli/browser4-cli/src/main.rs:no_snapshot_commands() — add \"snapshot-list\" and \"snapshot-clean\"",
      "suggestion": "- Add \"snapshot-list\" and \"snapshot-clean\" to no_snapshot_commands() in cli/browser4-cli/src/main.rs\n- Add a regression test asserting snapshot list does not create a new snapshot file\n- Audit the subcommand-name convention: other subcommands (snapshot grep is listed as \"snapshot-grep\") may have the same gap"
    },
    {
      "title": "First htmlsnapshot after backend start times out at the 60s default HTTP timeout",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "Fresh backend: ./b4w.ps1 goto <url> (starts daemon), then immediately ./b4w.ps1 htmlsnapshot",
      "expected": "The capture completes within the default timeout on a fresh backend.",
      "actual": "Error: HTTP request timed out [tool=html_snapshot_capture, endpoint=http://localhost:8182/mcp/call-tool, timeout=60s]. An immediate retry of the identical command succeeded.",
      "rootCause": "Likely cold-start latency: the first html_snapshot_capture tool call initializes the browser tab DOM-snapshot machinery (parse + PowerCSS feature computation) while the JVM is still warming up, exceeding the CLI's default 60s HTTP timeout. Needs confirmation via backend profiling; the error message gives no hint that the server is merely warming up.",
      "codePointer": "cli/browser4-cli/src/http.rs (default tool-call timeout) and/or the backend html_snapshot_capture executor; warm-up logic in cli/browser4-cli/src/daemon.rs",
      "suggestion": "- Warm up after server readiness: fire a cheap tool call (e.g. a trivial eval or about:blank capture) during daemon start so the first user command is not the cold one\n- On a tool-call timeout, append a hint: \"the server may still be warming up — retry, or use --timeout 120\"\n- Consider a higher first-call timeout (e.g. 120s) right after a fresh server start"
    },
    {
      "title": "Crawl page-listing depth labels are wrong (every page shows depth=1 in a depth-2 crawl)",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.ps1 crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol \"a.product\" -olp \"/product/\"",
      "expected": "Seed URL labeled depth=0, pages discovered from the seed labeled depth=1, pages discovered from those labeled depth=2.",
      "actual": "All 10 rows printed \"depth=1\", including the seed (index.html) and the six product pages (4-9) that are only reachable at depth 2 (index links only to products 1-3).",
      "rootCause": "CrawlService.crawlDepthN computes depth via extractDepth(page) ?: 1. extractDepth() regex-parses \"-depth N\" out of page.configuredUrl, but the args string built by buildArgsForDepth() is evidently not retained in configuredUrl after submission (args are consumed at submit time), so the regex never matches and every page falls back to 1. The seed page additionally has no -depth arg at all, so it defaults to 1 instead of 0.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:extractDepth() (~line 1098) and crawlDepthN() (~line 718); buildArgsForDepth()",
      "suggestion": "- Track depth explicitly per submitted hyperlink (e.g. a URL→depth map in crawlDepthN) instead of re-parsing configuredUrl\n- Label the seed page depth=0\n- Add a regression test asserting depth values for a depth-2 crawl of the generated/crawl fixture"
    },
    {
      "title": "Server-start spinner floods non-TTY output with ~40 animation-frame lines",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.ps1 goto <url> 2>&1 | cat (any piped/redirected invocation that triggers daemon start)",
      "expected": "A few concise progress lines when stdout is not a terminal.",
      "actual": "Every spinner frame is emitted as a separate line (⠋/⠙/⠹… \"Starting server... (0s) — JVM loading…\"), producing tens of noisy lines per command in logs and piped output.",
      "rootCause": "The spinner redraws with carriage returns without checking whether stdout is a TTY; when piped, every frame becomes a line.",
      "codePointer": "CLI daemon-start spinner (search \"Starting server\" in cli/browser4-cli/src/, likely daemon.rs or main.rs)",
      "suggestion": "- Detect non-TTY stdout (std::io::IsTerminal) and print stage-change or time-bucketed progress lines instead of per-frame redraws\n- Cap total progress lines (e.g. one line per 5s or per stage transition: JVM → Spring Boot → MCP tools)"
    },
    {
      "title": "Snapshot state directory naming does not match documentation and splits state across two dirs",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Run ./b4w.ps1 goto <url> and observe the printed snapshot path; compare with the help text and SKILL.md.",
      "expected": "The help text (BROWSER4_CLI_STATE_DIR 'falls back to ./.browser4-cli-state when unwritable') and SKILL.md to match the actual directory used.",
      "actual": "Snapshots are written to ./.browser4-cli/snapshot in the workspace while loop state, config, and CLI state live in ~/.browser4/ (which was writable — the documented fallback condition did not even apply). The fallback name in the docs (./.browser4-cli-state) differs from the actual name (.browser4-cli).",
      "rootCause": "snapshot::snapshot_dir() resolves to a workspace-relative .browser4-cli directory by design (or via a default that predates the documented fallback), while session/loop state uses ~/.browser4. The help/SKILL.md text describes a single state dir with a fallback name that no longer matches the code.",
      "codePointer": "cli/browser4-cli/src/snapshot.rs:snapshot_dir(); docs in cli/browser4-cli/src/help.rs and skills/browser4-cli/SKILL.md §2",
      "suggestion": "- Align the documented fallback directory name with the code (or rename the code path) — pick ./.browser4-cli-state or ./.browser4-cli consistently\n- Document explicitly that snapshots are workspace-local while session/loop state is home-relative, and which env var moves each\n- Add the snapshot dir to 'config' output or 'snapshot list' header so users can always find it"
    },
    {
      "title": "No discoverable way to start the backend from status/list — auto-start is undocumented",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "On a machine with no running backend: ./b4w.ps1 status → \"Server health: UNREACHABLE … Start the backend to compare against the live server version.\" — no command is named to do this.",
      "expected": "The status message (or a start command) should tell the user how to start the backend.",
      "actual": "Only goto/open auto-start the backend; status and list silently report it as down. A first-time user has to infer from the Quick Start (goto's \"auto-starts server & session\" note) that navigation commands double as server starters. There is no `start` or `server start` command in the help.",
      "rootCause": "Auto-start is wired only into session-requiring command paths; status/list are passive readers and their messages assume knowledge of the auto-start behavior. No dedicated start command exists in the command table.",
      "codePointer": "cli/browser4-cli/src/main.rs (status handler / handle_status) and cli/browser4-cli/src/help.rs",
      "suggestion": "- Add a hint to the status/list output: \"Run `goto <url>` or `open <url>` to start the server automatically\"\n- Optionally add an explicit `start` (server) command for symmetry with `stop`\n- Document the auto-start rule once in SKILL.md §1 (currently only implied by the Quick Start box)"
    },
    {
      "title": "SKILL.md output-mode docs conflict with htmlsnapshot query help (JSON is the default output)",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Compare SKILL.md §2 Output Modes with ./b4w.ps1 help htmlsnapshot query.",
      "expected": "Consistent description of htmlsnapshot query's output modes.",
      "actual": "SKILL.md §2 presents --json as the opt-in machine-readable mode for htmlsnapshot query, implying human-readable output by default. The command help states the default IS the raw JSON scrape envelope and human-readable output requires --format table (or --result-only for the bare resultSet). SKILL.md §4e never mentions --format table or --result-only.",
      "rootCause": "The query output default (raw JSON envelope) changed without updating SKILL.md §2/§4e; the --result-only/--format flags for query are documented only in the command help.",
      "codePointer": "skills/browser4-cli/SKILL.md §2 Output Modes and §4e; cli/browser4-cli/src/help.rs (htmlsnapshot query help)",
      "suggestion": "- Update SKILL.md §2 to state that htmlsnapshot query returns the JSON envelope by default and --format table / --result-only produce human-readable output\n- Add a --format table example to the §4e X-SQL Quickstart template\n- Align terminology: reserve '--json' claims for commands where the default is human-readable"
    },
    {
      "title": "Link-discovery crawl waits ~66s before the first page is fetched",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol \"a.product\" -olp \"/product/\" on a warm backend (an identical depth-0 crawl minutes earlier reached its first page in ~6s).",
      "expected": "First page fetched within a few seconds, similar to the depth-0 crawl.",
      "actual": "Output showed 'Crawling... waiting for first page' from 6s to 66s before the first page was recorded; total crawl then completed normally.",
      "rootCause": "Unclear; likely one-time initialization in the depth>=1 path (crawlDepthN creates a new agentic session and calls PulsarSettings.withSequentialBrowsers()/maxOpenTabs(8) before the seed load — sequential-browser init can be slow on first use). Needs profiling to confirm.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:crawlDepthN() (~line 718)",
      "suggestion": "- Log stage progress during crawl session setup so the 'waiting for first page' window explains itself (e.g. 'initializing crawl session…')\n- Consider pre-warming the sequential-browser session at backend start or on first crawl\n- Investigate whether withSequentialBrowsers init can be deferred/parallelized"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all 6 acceptance criteria (AC1–AC6) of the SKILL.md §4b bulk/scale decision-tree scenario were completed and verified against MockSite on localhost:18080.",
    "successRate": "95% — every documented workflow worked; the only hiccups were one transient 60s timeout on the first htmlsnapshot capture (retry succeeded) and cosmetic/reporting issues (depth labels, spinner noise).",
    "issuesFound": 8,
    "majorBlockers": "None. MockSite took ~4 minutes to come up via ./bin/test.ps1 mock-site (Maven preflight + spring-boot:run), which is documented behavior, not a blocker. The browser4-cli daemon auto-started the locally-built backend on first goto and stayed healthy for all subsequent commands.",
    "mostConfusingAspects": "1) Which commands auto-start the backend (status/list report it down with no start hint); 2) htmlsnapshot query's default output being the raw JSON envelope while SKILL.md implies human-readable default; 3) CLI state split across ~/.browser4 (loops/config) and ./.browser4-cli (snapshots) with a documented fallback name that doesn't match reality; 4) crawl depth labels all showing 1 regardless of true discovery depth.",
    "mostValuableImprovements": "1) Fix snapshot list's capture side effect (High); 2) warm-up or timeout guidance for the first tool call after server start (Medium); 3) correct per-page depth reporting in crawl output (Medium); 4) non-TTY spinner suppression and the doc inconsistencies above.",
    "usabilityRating": 8
  }
}
```

The deliverable JSON is also saved at `.test-sessions/evaluation-deliverable-bulk-scale-20260814.json` for machine processing.
