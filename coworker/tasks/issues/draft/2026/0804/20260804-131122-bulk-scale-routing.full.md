Here is the complete evaluation report:

---

## A. Task Result

The task evaluated all six bulk/scale approaches from SKILL.md §4b against the MockSite fixture. Four approaches succeeded fully, one required a workaround, and one was blocked by a known product issue.

| AC | Approach | Status | Outcome |
|----|----------|--------|---------|
| AC1 | Single list page — `htmlsnapshot query` with `DOM_LOAD_AND_SELECT` | ✅ Passed | 6 product cards extracted with correlated title+price+URL |
| AC2 | Multiple known URLs — `crawl --seed-file --depth 0 --sql` | ✅ Passed | 3 rows, one per seed URL |
| AC3 | Crawl from start URL — link discovery with `-ol`/`-olp` | ❌ Blocked | TaskLoops bean missing in dev-mode server |
| AC4 | Parallel execution — `swarm create` → `swarm query` → `swarm result` | ✅ Passed | 3 URLs extracted in parallel, all results correct |
| AC5 | Repeated monitoring — `loop` with eval | ⚠️ Workaround | Subcommand mode (--) fails with -s flag; shell mode works |
| AC6 | Few URLs in shell script — PowerShell foreach loop | ✅ Passed | 3 product pages extracted via goto + htmlsnapshot + get |

## B. Execution Trace

**Setup:** Required `mvn install -pl browser4-dependencies -N` and `mvn install -pl browser4-rest -am -DskipTests` before MockSite could start, because `browser4-dependencies:4.12.3-SNAPSHOT` POM was not in the local Maven repository.

**Key commands used:**
- `pwsh ./b4w.ps1 help` — discover available commands
- `pwsh ./b4w.ps1 goto "http://localhost:18080/ec/b?node=1292115012"` — navigate to listing page
- `pwsh ./b4w.ps1 htmlsnapshot` + `pwsh ./b4w.ps1 htmlsnapshot inspect` — discover CSS selectors
- `pwsh ./b4w.ps1 htmlsnapshot query "..." --sql @query.sql` — X-SQL extraction
- `pwsh ./b4w.ps1 crawl --seed-file ... --depth 0 --sql @... --format table --refresh` — bulk fetch
- `pwsh ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` — parallel setup
- `pwsh ./b4w.ps1 swarm query --sql @... --seed-file ... --refresh` — parallel extraction
- `pwsh ./b4w.ps1 loop --name ... --count 2 -i 10 --shell "..."` — repeated monitoring (workaround)
- PowerShell `foreach` loop with `goto`, `htmlsnapshot`, `htmlsnapshot get text` — ad-hoc pattern

**Workarounds required:**
1. AC3 blocked entirely — no workaround (backend infrastructure missing)
2. AC5 required `--shell` mode instead of `--` subcommand mode
3. All commands needed `pwsh ./b4w.ps1` prefix rather than the `$(./b4w.ps1)` syntax documented in the task

**Important decisions:**
- Switched from `$(./b4w.ps1)` syntax to `pwsh ./b4w.ps1` after discovering the bash substitution does not work
- Used `--shell` mode for AC5 loop after confirming the subcommand mode bug
- Wrote X-SQL queries to files with `@file` prefix to avoid Windows shell quoting issues (as recommended by docs)
- Used raw binary (`./cli/browser4-cli/target/debug/browser4-cli.exe`) for debugging loop subcommand mode

---

```json
{
  "issues": [
    {
      "title": "MockSite cannot start — browser4-dependencies SNAPSHOT POM not in Maven Central",
      "severity": "High",
      "category": "Product",
      "reproduction": "Run `./bin/test.ps1 mock-site` on a clean checkout without running `mvn install` first.",
      "expected": "MockSite starts successfully from source for local testing.",
      "actual": "Maven fails with: `Non-resolvable import POM: ai.platon.pulsar:browser4-dependencies:pom:4.12.3-SNAPSHOT was not found in https://repo1.maven.org/maven2/`. Eight dependency version errors follow. MockSite does not start.",
      "rootCause": "The `browser4-dependencies` POM (version 4.12.3-SNAPSHOT) is referenced as a BOM in the parent POM but is not published to Maven Central (SNAPSHOT artifacts are not published). It must be installed locally first with `mvn install -pl browser4-dependencies -N`. Additionally, the failure is cached in Maven's local repository, so even after installing, `-U` flag or cache deletion is needed.",
      "codePointer": "pom.xml:561 — the dependencyManagement import of browser4-dependencies. The fix could either publish SNAPSHOT builds to a snapshot repository, or the bin/test.ps1 script should auto-build dependencies before launching MockSite.",
      "suggestion": "- Add a pre-flight build step to bin/test.ps1 that runs `mvn install -N` before launching MockSite\n- Or publish browser4-dependencies SNAPSHOT builds to a snapshot repository accessible to developers\n- Add a clear error message: 'Run mvn install -N first to install the parent POM and dependency BOM locally'\n- Consider making bin/test.ps1 mock-site auto-trigger the needed Maven install steps"
    },
    {
      "title": "Link discovery (crawl depth >= 1) blocked by missing TaskLoops Spring bean",
      "severity": "Critical",
      "category": "Product",
      "reproduction": "crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol \"a.product\" -olp \"/product/\"",
      "expected": "Pages at depth 1 discovered and fetched. Product links followed, multi-page results returned.",
      "actual": "Server log shows link discovery finds out-links but crashes with: `No qualifying bean of type 'ai.platon.pulsar.loop.TaskLoops' available`. CLI reports only 1 page (seed) with no indication of the backend crash.",
      "rootCause": "crawlDepth1 and crawlDepthN call session.submit(hyperlink) which internally requires a TaskLoops Spring bean. The dev-mode runtime bundle (browser4-bundle) does not wire this bean. The CrawlService catches the exception and reports the page as 'failed' but does not propagate the root cause to the CLI output.",
      "codePointer": "browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:609 (session.submit). The TaskLoops bean is defined at browser4-core/browser4-skeleton/src/main/kotlin/ai/platon/pulsar/loop/TaskLoops.kt:6.",
      "suggestion": "- Wire the TaskLoops bean into the dev-mode Spring context, or provide a no-op/default implementation when not available\n- Propagate the underlying error to the CLI output instead of silently swallowing it\n- Add a pre-flight check: if link discovery (depth >= 1) is requested but TaskLoops is unavailable, fail fast with a clear message"
    },
    {
      "title": "CLI incorrectly warns 'no --out-link-selector' even when flag is correctly specified",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "crawl <url> --out-link-selector \"a.product\" -d 2",
      "expected": "No warning. The --out-link-selector was provided and should be recognized.",
      "actual": "Prints 'Note: Link discovery disabled (no --out-link-selector). Processing seed URLs only.' despite the flag being present and correctly parsed.",
      "rootCause": "In main.rs:10272-10276, the check looks for tool_params[\"out-link-selector\"], but this key is never set in tool_params. The commands.rs tool_params_fn stores the selector in the args LoadOptions string (as -outLink), not as a separate tool_params key.",
      "codePointer": "cli/browser4-cli/src/main.rs:10272 — has_out_link_selector check should read from the args string or be removed entirely.",
      "suggestion": "- Fix the detection: check the resolved args string for -outLink instead of looking for a non-existent tool_params key\n- Or remove the check entirely — let the server report whether link discovery happened\n- Add a unit test that verifies the warning is NOT emitted when --out-link-selector is specified"
    },
    {
      "title": "Loop subcommand mode (--) fails with 'too many arguments' when -s session flag is used",
      "severity": "High",
      "category": "Product",
      "reproduction": "browser4-cli loop --name test --count 1 -i 1 -- -s my-session eval document.title",
      "expected": "The nested browser4-cli process executes `-s my-session eval document.title` successfully, same as invoking it directly.",
      "actual": "Error: error: too many arguments: expected 1, received 2. Direct invocation `browser4-cli -s my-session eval document.title` works correctly.",
      "rootCause": "The loop subcommand mode spawns the binary with task_tokens collected after --. When -s is in the tokens, parse_global_flags in the spawned process does NOT consume it (because it's already past the 'first non-flag = command' marker). The -s flag and its value end up being treated as positional arguments to the eval command instead of global flags.",
      "codePointer": "cli/browser4-cli/src/args.rs:parse_global_flags() at line 119 — the 'seen_command = true' gate prevents -s from being consumed when it appears after another non-flag. Or cli/browser4-cli/src/main.rs:11197 — run_browser4_cli should prepend session flags as global flags rather than passing them as positional tokens.",
      "suggestion": "- In run_browser4_cli, detect known global flags in task_tokens and handle them before spawning (e.g., extract -s and pass it as an environment variable or reconstruct the args with -s before the command)\n- Or modify the spawned process to re-parse -s even after the command marker\n- Document this limitation prominently in loop.md until fixed\n- Add an integration test: loop subcommand mode with -s flag"
    },
    {
      "title": "b4w.sh wrapper fails on Windows Git Bash with 'term not recognized' error",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "In Git Bash: `./b4w.sh crawl <url>`",
      "expected": "Command executes successfully via the bash wrapper.",
      "actual": "Exit code 1: 'The term /d/workspace/.../b4w.ps1 is not recognized as a name of a cmdlet, function, script file, or executable program.' PowerShell cannot resolve Unix-style paths.",
      "rootCause": "b4w.sh passes the script path using Unix-style forward slashes (/d/workspace/...). PowerShell on Windows does not resolve paths starting with /d/ — it requires Windows-style paths (D:\\...) or the script to be on PATH.",
      "codePointer": "b4w.sh (repo root) — the SCRIPT_DIR resolution and subsequent pwsh invocation.",
      "suggestion": "- Convert SCRIPT_DIR to a Windows-style path (using cygpath -w or equivalent) before passing to pwsh\n- Add a pre-flight check in b4w.sh to verify pwsh is available and provide a clear error if not\n- Document that Git Bash users should use `pwsh ./b4w.ps1` directly instead of b4w.sh on Windows"
    },
    {
      "title": "Task instruction invocation syntax $(./b4w.ps1) is non-functional in bash",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "In Git Bash: `$(./b4w.ps1) help`",
      "expected": "The b4w.ps1 script is invoked and browser4-cli help is displayed.",
      "actual": "Exit code 127: 'b4w: command not found'. The $(...) substitution expands b4w.ps1's output (help text), not a command name. The script must be explicitly invoked with `pwsh ./b4w.ps1`.",
      "rootCause": "The task instructions assume `$(./b4w.ps1)` produces a runnable command, but b4w.ps1 outputs help text when run without arguments, and `./b4w.ps1` by itself doesn't execute as a command in bash (it's a PowerShell script).",
      "codePointer": "",
      "suggestion": "- Update task instructions to use `pwsh ./b4w.ps1` as the canonical invocation\n- Or provide a bash-compatible wrapper that emits the correct invocation string\n- Document the correct invocation method prominently in SKILL.md for non-PowerShell environments"
    },
    {
      "title": "Stale swarm tasks persist across sessions and produce confusing warnings",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Create a swarm session, run tasks, close session. Create a new swarm session days later.",
      "expected": "Clean slate or clear guidance to clean up.",
      "actual": "Warning: '22 swarm task(s) from prior sessions are still tracked. If new jobs get stuck in \"Created\" status, run swarm list --clear...' This is confusing for a first-time user who has never used swarm before.",
      "rootCause": "Swarm task tracking state is persisted across sessions. A previous evaluation (or team member) left 22 tracked tasks that the new user must clear manually.",
      "codePointer": "cli/browser4-cli/src/main.rs — swarm task tracking and auto-cleanup logic.",
      "suggestion": "- Auto-expire swarm tasks older than a configurable threshold (e.g., 24 hours)\n- Offer `swarm create --clear-stale` as a more prominent option in the warning message\n- Default to clearing stale tasks when creating a new swarm if the tasks are from a different day\n- Reduce the warning from a prominent note to a one-line informational message"
    },
    {
      "title": "Shell quoting chain (bash → pwsh → b4w.ps1 → Rust binary) is fragile on Windows",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Attempt to pass JS expressions with quotes, dollar signs, or hash marks through the invocation chain on Git Bash.",
      "expected": "Arguments arrive at the Rust binary exactly as the user typed them.",
      "actual": "Multiple layers of quote interpretation (bash, pwsh -Command, b4w.ps1 Invoke-Expression with manual double-quoting) cause arguments to be mangled. $variables are expanded by bash, #comments are treated as shell comments, quotes are consumed or doubled. Workarounds (--file, --base64) are documented but add friction.",
      "rootCause": "The invocation chain: bash → pwsh -Command → b4w.ps1 ($SafeArgs double-quoting + Invoke-Expression) → binary. Each layer has its own quoting rules. There is no single escaping strategy that works reliably for all argument types across this chain.",
      "codePointer": "b4w.ps1:679-684 — the SafeArgs double-quoting approach; b4w.sh:38-43 — the bash-side quoting loop.",
      "suggestion": "- Prefer --file and @file patterns as the primary workflow; make the docs steer users toward these more prominently\n- Consider a simpler launch path: a native binary launcher that bypasses pwsh\n- Add an `args echo` debug command that shows exactly what args the binary received\n- Document the safe patterns more prominently in a 'Windows Quoting' section at the top of SKILL.md"
    },
    {
      "title": "htmlsnapshot query outputs raw JSON without --json flag (inconsistent with other commands)",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run `htmlsnapshot query <url> --sql @query.sql`",
      "expected": "Human-readable table output by default (like crawl --format table), or JSON only with --json flag.",
      "actual": "Raw JSON output is produced. Other commands like `snapshot`, `crawl`, `tab-list` produce human-readable output by default and require --json for machine-readable format. This inconsistency surprises users expecting formatted output.",
      "rootCause": "The htmlsnapshot query backend returns JSON natively, and the CLI does not format it into a table/readable output before displaying. The crawl command has explicit --format support (table/csv/json), but htmlsnapshot query does not.",
      "codePointer": "cli/browser4-cli/src/main.rs — htmlsnapshot query result rendering path.",
      "suggestion": "- Add --format table|csv|json support to htmlsnapshot query, defaulting to table\n- Or document that htmlsnapshot query always returns JSON and add a --pretty flag for human-readable formatting\n- Align with crawl's behavior for consistency"
    },
    {
      "title": "First-time user must discover flag formats independently for each command",
      "severity": "Low",
      "category": "Discoverability",
      "reproduction": "Try to run `htmlsnapshot query` with inline SQL. The SQL contains quoted selectors.",
      "expected": "Clear guidance or automatic handling of common patterns.",
      "actual": "User must independently discover: (1) CSS selectors in X-SQL use single quotes (double quotes are SQL identifiers), (2) @file prefix reads query from file, (3) @url placeholder must be unquoted, (4) DOM_LOAD_AND_SELECT is the only valid FROM source. Each of these is documented but spread across multiple reference files. A wrong attempt produces opaque SQL errors.",
      "rootCause": "The X-SQL syntax has non-standard constraints (single-quoted selectors, unquoted @url, no CTEs/joins) that differ from user expectations. Error messages are H2 SQL engine errors that reference internal table names, not the user's query.",
      "codePointer": "",
      "suggestion": "- Add a `--validate` flag to htmlsnapshot query that checks X-SQL syntax without executing\n- Improve error messages: catch common mistakes (double-quoted selectors, quoted @url, missing DOM_LOAD_AND_SELECT) and provide specific guidance\n- Add a quickstart template to SKILL.md §4: a minimal working query that users can modify"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — 4 of 6 ACs passed fully, 1 required workaround (AC5), 1 blocked by known product issue (AC3). The core extraction patterns (X-SQL, crawl depth 0, swarm, ad-hoc loops) work well. Link discovery, the primary value proposition of the crawl command, is non-functional in dev mode.",
    "successRate": "83% — 5 of 6 acceptance criteria produced correct results (AC5 with workaround counts as success). AC3 (link discovery) is completely blocked.",
    "issuesFound": 10,
    "majorBlockers": "1) Link discovery (crawl depth >= 1) requires TaskLoops Spring bean not wired in dev-mode server — blocks AC3 entirely. 2) Loop subcommand mode (--) fails when -s session flag is used — requires workaround via --shell mode for AC5. 3) MockSite requires manual mvn install steps before first launch.",
    "mostConfusingAspects": "1) The $(./b4w.ps1) invocation syntax in the task instructions doesn't work — significant time lost discovering correct pwsh ./b4w.ps1 invocation. 2) The 'Note: Link discovery disabled (no --out-link-selector)' warning appears despite the flag being correctly passed — misleading error masks the real TaskLoops issue. 3) The shell quoting chain (bash → pwsh → b4w.ps1 → binary) makes every non-trivial argument fragile; the @file pattern is essential but not obvious to new users. 4) htmlsnapshot query returns raw JSON while crawl returns formatted tables — inconsistency in output formats.",
    "mostValuableImprovements": "1) Wire TaskLoops or provide fallback for link discovery in dev mode — this is the crawl command's core feature. 2) Fix loop subcommand mode with -s flag — enables the documented workflow without workarounds. 3) Fix the false 'no --out-link-selector' warning — reduces confusion and helps users trust the tool. 4) Add auto-build step to bin/test.ps1 mock-site — eliminates the setup friction. 5) Add a unified 'Windows Quoting' guide at the top of SKILL.md — reduces cognitive load for the most common pain point.",
    "usabilityRating": 6
  }
}
```
