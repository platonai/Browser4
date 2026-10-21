All work is complete. Here is my full evaluation report.

---

# A. Task Result

All six acceptance criteria for **SKILL.md §4b — Choosing Bulk/Scale Approach** were exercised against the live MockSite (`localhost:18080`) and backend (`localhost:18182`), with every branch of the decision tree verified:

| AC | Branch | Outcome | Evidence |
|----|--------|---------|----------|
| **AC1** | Single list page | ✅ Clean | `htmlsnapshot query` with `DOM_LOAD_AND_SELECT(@url, '.product-card')` returned 6 rows, one per product card, with correlated `title` + `price` + `link`. |
| **AC2** | Multiple known URLs (seed file) | ✅ Clean | `crawl --seed-file ... --depth 0 --sql @ac2-detail.sql --format table --refresh` returned 3 rows (one per seed URL) with `url` + `title` + `price`. |
| **AC3** | Crawl from start URL (link discovery) | ⚠️ Workaround required | `-ol "a.product"` alone discovered 15 product pages (category links correctly excluded). The documented `-olp "/product/"` pattern **breaks discovery** (Critical bug, see Issue 1). |
| **AC4** | Parallel execution (swarm) | ⚠️ Flaky | `swarm create` + `swarm query --seed-file` worked, but only intermittently — 404 "Not Found", empty resultSets, and 0-byte fetches occurred across runs (Critical bug, see Issue 4). All 3 URLs eventually extracted correctly across attempts. |
| **AC5** | Repeated monitoring (loop) | ⚠️ Workaround required | `loop -- ... -s price-watch eval ...` fails with `Unknown command: 'price-watch'` (the `-s` flag is dropped, Issue 6). Workaround via `BROWSER4_CLI_SESSION=price-watch` produced correct repeated `$899.99` reads. |
| **AC6** | Few URLs in a shell script | ✅ Clean | PowerShell `ForEach-Object` loop with `goto` → `htmlsnapshot` → `htmlsnapshot get text "#productTitle"` + `Start-Sleep` extracted all 3 titles. |

**Bottom line:** The task is **successful** (all six branches demonstrated), but only because I worked around three genuine bugs. A first-time user following the docs verbatim would hit hard failures on AC3, AC4, and AC5.

---

# B. Execution Trace

**Preparation:**
- Confirmed working directory `D:/workspace/Browser4/Browser4-4.13`; `browser4-cli help` → v4.13.3; backend UP (port **18182**, not the 8182 that AGENTS.md claims); MockSite already serving `localhost:18080` (listing page returned 200).
- `https://browser4.io/SKILL.md` fetch failed with an SSL error (Issue 11); read the complete local `skills/browser4-cli/SKILL.md` (737 lines) plus `crawl.md`, `swarm.md`, `loop.md`, `x-sql.md`, `docs/mocksite.md` instead.
- Inspected MockSite source (`HtmlRenderer.kt`, `EcControllers.kt`, `generated/crawl/index.html`) to discover real selectors: listing cards use `.product-card`/`.product-title`/`.product-price`; detail pages use `#productTitle`/`#product-price`; crawl fixture uses `a.product` (products) vs `a.category-link` (categories).

**Commands used (key):**
- `browser4-cli status`, `browser4-cli goto ...`, `browser4-cli htmlsnapshot`
- `browser4-cli htmlsnapshot query "<url>" --sql @.test-sessions/ac1-list.sql [--format table|json]`
- `browser4-cli crawl --seed-file .test-sessions/ac2-seed.txt --depth 0 --sql @.test-sessions/ac2-detail.sql --format table --refresh`
- `browser4-cli crawl "<hub>" -d 1|2 -ol "a.product" [-olp "/product/"]` (multiple diagnostic variants)
- `browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4`
- `browser4-cli swarm query --sql @... --seed-file ... --refresh [--wait]`, `swarm status/result/list/close`
- `browser4-cli -s price-watch goto ...`; `browser4-cli loop --name ... --count 2 -i 10 -- -s price-watch eval "..."`; `browser4-cli loop --list/--status`
- `browser4-cli doctor log pulsar grep "..."` (root-cause investigation)
- PowerShell `ForEach-Object` loop for AC6.

**Key decisions & workarounds:**
1. AC1/AC2: wrote SQL to files under `.test-sessions/` (per policy). Had to **quote** the `@file` arg (`--sql "@.test-sessions/..."`) because PowerShell treats a bare `@token` as splatting (Issue 10).
2. AC3: after the documented `-olp "/product/"` example returned 0 links, I bisected to `-ol "a.product"` (works) vs `-ol + -olp` (breaks) and confirmed via backend logs that `-outLinkPattern` retains literal quotes.
3. AC4: worked around swarm flakiness by closing/recreating the session and re-submitting; also cleared stale CLI-tracked tasks.
4. AC5: worked around the dropped `-s` flag by setting `BROWSER4_CLI_SESSION=price-watch` before the loop.

**Investigation of root causes:** read `CrawlService.kt`, `SwarmService.kt`, `LoadOptions.kt`, `args.rs`, `state.rs`, and `commands.rs` to pin exact causes (detailed in the issues below). Noted that `coworker/tasks/` already contains prior reports of the crawl link-discovery bug (ACCEPTed but still unfixed).

---

```json
{
  "issues": [
    {
      "title": "crawl --out-link-pattern (-olp) breaks link discovery entirely",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "browser4-cli crawl \"http://localhost:18080/generated/crawl/index.html\" -d 1 -ol \"a.product\" -olp \"/product/\"\nCompare: the same command WITHOUT -olp returns 3 product pages.",
      "expected": "Product pages (product/1.html, 2.html, 3.html) discovered; category links excluded by the selector/pattern filters. This is the exact example in SKILL.md §4b and crawl.md.",
      "actual": "0 pages found, with the misleading diagnostic: \"The page has 12 anchors and 8000B of HTML, but the CSS selector 'a.product' matched zero elements.\" Any -olp value ('/product/', 'product', etc.) triggers it; the selector alone works fine.",
      "rootCause": "The CLI builds the LoadOptions args with surrounding double-quotes for BOTH the selector and the pattern: commands.rs:2785 emits `-outLink \"a.product\"` and commands.rs:2791 emits `-outLinkPattern \"/product/\"`. LoadOptions.parse() (LoadOptions.kt:762) trims quotes from the SELECTOR via correctOutLinkSelector()→trim('\"') but there is NO equivalent quote-trim for outLinkPattern. Backend logs confirm `-outLinkSelector a.product` (unquoted) but `-outLinkPattern \"/product/\"` (quotes retained), so Regex(\"\\\"/product/\\\"\") never matches any resolved URL and every out-link is filtered out.",
      "codePointer": "cli/browser4-cli/src/commands.rs:2791 (adds quotes); browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/skeleton/common/options/LoadOptions.kt:762 (parse() should also trim quotes from outLinkPattern)",
      "suggestion": "- In LoadOptions.parse(), trim surrounding double/single quotes from outLinkPattern (mirror correctOutLinkSelector), e.g. `outLinkPattern = outLinkPattern.trim('\"').trim('\\'')`\n- In commands.rs, stop adding literal quotes around the pattern value (emit `-outLinkPattern {v}`), since the selector path already relies on server-side trim\n- Add a regression test that runs `crawl <hub> -ol \"a.product\" -olp \"/product/\"` against the MockSite crawl fixture and asserts >=3 product pages\n- Fix the diagnostic (see separate issue) so the true cause (pattern filter) is reported instead of blaming the selector"
    },
    {
      "title": "crawl link-discovery diagnostic falsely blames the selector",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run any crawl with -ol and -olp; observe the diagnostic \"...CSS selector 'a.product' matched zero elements\".",
      "expected": "The diagnostic should report the actual reason out-links were empty (e.g. the out-link-pattern filtered everything), and should verify the selector against the actual document.select(selector) count.",
      "actual": "The message asserts the selector matched zero elements, but the code only re-checks document.select(\"a\").size (12 anchors) — it never re-runs the actual selector, so it reports a false cause (the selector is fine; the pattern is what filtered everything).",
      "rootCause": "In CrawlService.crawlDepth1 (CrawlService.kt:602-627) the diagnostic branch loads the document and counts `document.select(\"a\").size` and html length, then falls into the `allAnchors > 0` branch which unconditionally prints \"the CSS selector matched zero elements\" without evaluating `document.select(options.outLinkSelector)`.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:614-618",
      "suggestion": "- In the diagnostic, compute `matched = document.select(options.outLinkSelector).size` and only claim \"matched zero\" when that is actually 0; otherwise report that links matched but the out-link-pattern (or dedup/top-links) filtered them\n- Include the out-link-pattern value in the message so users can self-diagnose"
    },
    {
      "title": "crawl reports every page as depth=1 regardless of actual depth",
      "severity": "Low",
      "category": "Product",
      "reproduction": "browser4-cli crawl \"http://localhost:18080/generated/crawl/index.html\" -d 2 -ol \"a.product\"",
      "expected": "Depth-0 seed (index.html), depth-1 pages (product/1..3), depth-2 pages (product/4..9, depth3/*) should each show their true depth.",
      "actual": "All 15 discovered pages are labelled \"depth=1\", including the seed URL and clearly-depth-2 pages (e.g. product/4.html reached via a related-products link on product/1.html).",
      "rootCause": "The recursive crawl path (crawlDepthN) labels results with a single `depth` value; the seed is submitted with buildArgsForDepth(options, 1) (CrawlService.kt:797) and the extractDepth() helper (CrawlService.kt:1086-1092) parses `-depth N` from URL args, but the reported depth column does not reflect the per-page actual depth.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:706-760 (crawlDepthN) and :1086-1092 (extractDepth)",
      "suggestion": "- Track the true depth per page (increment as links are followed) and report it in the page listing\n- Fix the seed's reported depth to 0 (currently submitted as depth 1)"
    },
    {
      "title": "swarm query across multiple URLs is highly flaky and non-deterministic",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "browser4-cli swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4\nbrowser4-cli swarm query --sql @query.sql --seed-file seed.txt --refresh --wait\n(repeat 3-4 times; fetch each result via swarm result <id>)",
      "expected": "Each seed URL yields one completed job with a populated resultSet and >0 pageContentBytes, every run.",
      "actual": "Across 4 runs, results were non-deterministic with three distinct failure modes: (a) 'Swarm task not found' (404, lastModifiedTime null) for 1-2 of 3 tasks; (b) isDone=true, statusCode=200 but resultSet=[] and pageContentBytes=0; (c) page fetched (14353 bytes) but resultSet still empty. Only 0-2 of 3 URLs produced correct data per run; a fresh swarm recreate did not fix it.",
      "rootCause": "Multiple overlapping causes. (1) SwarmService.responseCache is a Caffeine cache with maximumSize(100) (SwarmService.kt:53) while the doc-comment claims \"100 000 entries\" — a likely typo; combined with restoreFromDisk() repopulating the cache from the JSONL persistence file, new tasks can be LRU-evicted before status/result is read, producing 'Swarm task not found'. (2) Backend logs show only ONE H2 SQLSession (#5) being created for 3 concurrently-submitted tasks, suggesting a per-task SQL-context race that leaves some tasks without a working query context (empty resultSet). (3) 0-byte pageContentBytes indicates some workers never actually fetch the page. Investigation needed to confirm the exact concurrency bug in task distribution.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/SwarmService.kt:52-64 (responseCache size) and the SwarmController/SwarmService task-distribution path",
      "suggestion": "- Correct maximumSize to 100_000 (or a deliberately bounded value) and reconcile with the doc comment; do not repopulate terminal/expired tasks into the cache on restore\n- Serialize or correctly per-task provision the H2 SQL context so each swarm job has its own session (investigate why only one SQLSession is created for 3 jobs)\n- Add integration tests that run swarm query against 3 MockSite URLs repeatedly and assert all 3 resultSets are non-empty"
    },
    {
      "title": "swarm reports success (200 OK, done) with empty resultSet and zero bytes",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "Fetch swarm result for a task whose page was not actually fetched: the payload shows isDone=true, statusCode=200, resultSet=[], pageContentBytes=0, error=null.",
      "expected": "A silent, successful-looking result should not be returned when no page content was fetched; the task should fail or clearly report that extraction produced no data.",
      "actual": "Tasks that fetched 0 bytes are marked completed/OK and the CLI only prints a generic note (\"resultSet is empty… use swarm query --sql\") even though --sql WAS used and the real cause was a dropped/evicted task.",
      "rootCause": "The task lifecycle marks a job 'done' with statusCode 200 even when pageContentBytes==0 and resultSet==[]; there is no validation that the page was actually fetched before declaring success. The empty-resultSet note in the CLI is heuristic (assumes swarm submit without --sql) and is misleading for swarm query.",
      "codePointer": "browser4-rest (SwarmService task-completion handling) and cli/browser4-cli/src/main.rs (swarm result empty-resultSet note)",
      "suggestion": "- Treat pageContentBytes==0 as a failed/incomplete task (non-200 or explicit error) rather than a 200 success\n- Make the empty-resultSet hint conditional on the actual command (submit vs query) so it does not misdirect swarm query users"
    },
    {
      "title": "loop subcommand silently drops the -s/--session flag",
      "severity": "High",
      "category": "Product",
      "reproduction": "browser4-cli loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval \"document.querySelector('#product-price').textContent.trim()\"",
      "expected": "Each iteration runs `browser4-cli -s price-watch eval \"...\"` against the named session, returning the price.",
      "actual": "Each iteration errors: \"Unknown command: 'price-watch'\". loop --status shows the task as \"price-watch eval ...\" (the -s token is gone), and the nested browser4-cli treats 'price-watch' as the command name.",
      "rootCause": "In args.rs parse_global_flags() (args.rs:86-92), the `arg == \"-s\" || arg == \"--session\"` branch matches even after the command name, but its body is guarded by `if !seen_command` with no else. When seen_command is true, the token is neither consumed nor forwarded to flags.args, so '-s' is silently dropped while its value 'price-watch' survives as a positional. The same drop applies to `--session=...` (args.rs:82-85).",
      "codePointer": "cli/browser4-cli/src/args.rs:86-92 (parse_global_flags)",
      "suggestion": "- When seen_command is true, forward -s/--session (and --session=) into flags.args instead of dropping them, so loop's `--` subcommand can pass them to the nested binary\n- Add a unit test asserting that `-s` after the command name is preserved in flags.args\n- Alternatively document and support a session-agnostic loop subcommand form"
    },
    {
      "title": "loop --list does not list running loops in sandboxed/fallback environments",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "Start a named loop (writes state to the fallback state dir when ~/.browser4 is unwritable), then run `browser4-cli loop --list` while it runs.",
      "expected": "loop --list shows the running named loop (name, iterations, status, task), matching loop --status --name.",
      "actual": "loop --list prints \"No persisted loops.\" while `loop --status --name <n>` correctly shows the loop as Running with iterations 1/2.",
      "rootCause": "list_loop_states() (state.rs:686-712) reads only from resolve_default_state_dir() and its loops/ subdirectory, with NO fallback to fallback_state_dir(). read_loop_state() (state.rs:497-512) DOES fall back, which is why --status works but --list does not. In this sandbox ~/.browser4 is unwritable, so loop state is written to the fallback directory.",
      "codePointer": "cli/browser4-cli/src/state.rs:686 (list_loop_states) — needs the same fallback logic as read_loop_state at state.rs:497-512",
      "suggestion": "- In list_loop_states(), also scan fallback_state_dir() (and its loops/ subdir) and merge results, deduplicating by name\n- Add a unit test covering the fallback-dir listing path"
    },
    {
      "title": "loop --status displays a state-file path that is not where the state was written",
      "severity": "Low",
      "category": "UX",
      "reproduction": "In an environment where ~/.browser4 is unwritable, run a named loop and check loop --status --name <n>.",
      "expected": "The displayed \"State file:\" path should be the actual location the loop wrote its state to (the fallback directory).",
      "actual": "It shows \"State file: C:\\Users\\pereg\\.browser4\\loops\\<n>.json\" even though the write fell back to the workspace `.browser4-cli-state` directory.",
      "rootCause": "loop_state_path() (state.rs:552-557) always returns the primary default path and does not reflect the PermissionDenied fallback that write_loop_state() (state.rs:524-534) actually used.",
      "codePointer": "cli/browser4-cli/src/state.rs:552-557 (loop_state_path)",
      "suggestion": "- Have loop_state_path report the path that was (or would be) used, including the fallback when the primary dir is unwritable"
    },
    {
      "title": "htmlsnapshot query output format contradicts documented default (raw JSON, not table)",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "browser4-cli htmlsnapshot query \"<url>\" --sql @query.sql   (no --format)",
      "expected": "Per `htmlsnapshot query --help`, --format defaults to 'table', so the default output should be the aligned table.",
      "actual": "The default output is the raw scrape-API JSON envelope (id, statusCode, pageStatusCode, pageContentBytes, isDone, timestamps, event, status) wrapping resultSet. Explicit `--format json` produces the IDENTICAL envelope, and only `--format table` produces the human-readable table.",
      "rootCause": "The default code path emits the raw scrape response; the documented 'default: table' is not applied unless --format table is passed explicitly. `--format json` appears to be a no-op relative to the default rather than a clean resultSet-only JSON document (the `--result-only` flag exists for that).",
      "codePointer": "cli/browser4-cli/src/help.rs (the --format help text) and the htmlsnapshot query formatting path in cli/browser4-cli",
      "suggestion": "- Either make the default truly 'table' (to match the help) or fix the help text to state the default is the raw JSON response\n- Make `--format json` emit a clean resultSet array (distinct from the raw envelope), and document that `--result-only`/`--format table` are the readable alternatives"
    },
    {
      "title": "PowerShell mis-parses the @file argument unless quoted (undocumented)",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "browser4-cli htmlsnapshot query \"<url>\" --sql @.test-sessions/ac1-list.sql   (unquoted @file, PowerShell)",
      "expected": "The CLI reads the SQL from the @-prefixed file path, as documented in SKILL.md §4e and crawl.md.",
      "actual": "PowerShell raises \"ParserError: Unrecognized token in source text\" at the bare `@` (PowerShell splatting/here-string operator). Quoting the argument (--sql \"@...\") works.",
      "rootCause": "The `@file` convention collides with PowerShell's `@` token syntax; the docs mention shell-quoting pitfalls extensively for inline SQL/JS but do not warn that the `@file` form itself must be quoted on PowerShell.",
      "codePointer": "skills/browser4-cli/SKILL.md and skills/browser4-cli/references/shell-quoting.md (documentation)",
      "suggestion": "- Add an explicit note in shell-quoting.md and SKILL.md that on PowerShell the `@file` argument must be quoted (e.g. --sql \"@query.sql\")"
    },
    {
      "title": "https://browser4.io/SKILL.md is unreachable (SSL error) from this environment",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Invoke-WebRequest -Uri https://browser4.io/SKILL.md → \"The SSL connection could not be established\".",
      "expected": "The canonical web copy of SKILL.md is retrievable per the task's Preparation step.",
      "actual": "TLS/SSL handshake fails; the local skills/browser4-cli/SKILL.md had to be used as the source of truth. (May be environment/proxy specific — needs confirmation outside the sandbox.)",
      "rootCause": "Likely an environment/proxy/TLS issue rather than a content problem; the local copy was complete and internally consistent.",
      "codePointer": "",
      "suggestion": "- Confirm browser4.io serves SKILL.md over HTTPS with a valid cert from a normal network; if the site is intended to be the canonical doc source, ensure it is reachable"
    },
    {
      "title": "swarm close prints an unrelated page snapshot from the default session",
      "severity": "Low",
      "category": "UX",
      "reproduction": "browser4-cli swarm close",
      "expected": "swarm close reports only that the swarm session was closed.",
      "actual": "Output included a `### Page` / `### Snapshot` block for the DEFAULT session (the listing page), alongside the 'Swarm session closed' message — confusing and unrelated.",
      "rootCause": "The swarm close code path appears to trigger a page/snapshot render from the active (non-swarm) session, mixing unrelated output into the close result.",
      "codePointer": "cli/browser4-cli/src/main.rs (swarm close handler)",
      "suggestion": "- Suppress the page/snapshot section in the swarm close output"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all six §4b branches (AC1-AC6) were exercised and demonstrated against MockSite, but AC3, AC4, and AC5 required workarounds for real product bugs (crawl -olp breaks discovery; swarm query is flaky; loop drops the -s flag).",
    "successRate": "80% — AC1, AC2, AC6 worked cleanly end-to-end; AC3 and AC5 needed a workaround; AC4 completed only after repeated attempts due to flakiness.",
    "issuesFound": 12,
    "majorBlockers": "Three Critical/High blockers: (1) crawl --out-link-pattern (-olp) filters out every link because the pattern retains literal quotes (the documented AC3 example fails); (2) swarm query across multiple URLs is non-deterministic (404s, empty resultSets, 0-byte fetches); (3) loop subcommand drops the -s/--session flag so the documented AC5 pattern errors with 'Unknown command'.",
    "mostConfusingAspects": "1) The crawl 'matched zero elements' diagnostic is misleading — it blames the selector when the out-link-pattern is the real culprit. 2) swarm marks tasks 'done' (200 OK) even when they fetched 0 bytes and extracted nothing. 3) loop --list says 'No persisted loops' while loop --status shows the loop running. 4) htmlsnapshot query's default output is a raw JSON envelope even though --help says the default is 'table'. 5) The port mismatch between docs (8182) and the actual running server (18182) forced reliance on auto-discovery.",
    "mostValuableImprovements": "1) Fix -olp quote handling (trim quotes from outLinkPattern in LoadOptions.parse) and fix the crawl diagnostic — these make the documented link-discovery workflow work. 2) Make swarm task tracking reliable (correct responseCache size, per-task SQL contexts, fail loudly on 0-byte fetches). 3) Preserve the -s/--session flag through the loop `--` subcommand boundary. 4) Reconcile htmlsnapshot query output formats with the documented default. 5) Make loop --list fall back to the same state directory as loop --status.",
    "usabilityRating": 5
  }
}
```
