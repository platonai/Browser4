The final crawl also returned 0 pages — confirming the JS-class issue. Let me deliver the complete evaluation.

---

# Browser4-CLI Evaluation Report — Bulk/Scale Approach (SKILL.md §4b)

## A. Task Result

The task covered all six branches of SKILL.md §4b. Four of six acceptance criteria succeeded; two failed due to product/reliability issues.

| AC | Description | Status |
|----|-------------|--------|
| AC1 | Single list page via `htmlsnapshot query` | ✅ Success |
| AC2 | Multiple known URLs via `crawl --seed-file` | ✅ Success |
| AC3 | Crawl from start URL with link discovery | ❌ Failed |
| AC4 | Parallel execution with swarm | ❌ Failed |
| AC5 | Repeated monitoring with loop | ✅ Success (workaround) |
| AC6 | Few URLs in shell script | ✅ Success |

### AC1 — Single list page: ✅ SUCCESS
- Navigated to MockSite listing page, discovered `.product-card`, `.product-title`, `.product-price` selectors
- X-SQL query with `DOM_LOAD_AND_SELECT(@url, '.product-card')` returned 6 product rows with correlated fields
- Minor issue: `DOM_FIRST_HREF` returned empty; `DOM_FIRST_ATTR(DOM, '.product-link', 'href')` worked

### AC2 — Multiple known URLs via seed file: ✅ SUCCESS
- Crawl extracted 3 rows (url, title, price) from 3 MockSite product detail pages
- Took 428 seconds for 3 lightweight local pages

### AC3 — Crawl with link discovery: ❌ FAILED
- Crawl hub page loads in browser and `a.product` elements exist (confirmed via eval)
- Crawl's page-loader (non-JS HTTP fetch) does NOT see `.product` class — diagnostic: "The page has 12 anchors but `a.product` matched zero elements"
- Same issue confirmed with `htmlsnapshot query` via scrape API

### AC4 — Swarm parallel execution: ❌ FAILED
- Worker pool consistently stalls: 1/3 jobs completed first attempt, 0/3 second attempt
- `--wait` flag timed out at 300s with no completions

### AC5 — Loop monitoring: ✅ SUCCESS (with workaround)
- Loop subcommand cannot pass `-s <name>` through `--` separator
- Workaround: `session-default` to set target session, then loop works correctly

### AC6 — Shell loop: ✅ SUCCESS
- Bash loop iterated over 3 URLs, extracting title + price correctly

---

## B. Execution Trace

**Commands used:** `goto`, `htmlsnapshot`, `htmlsnapshot get`, `htmlsnapshot query`, `eval`, `crawl`, `swarm create`, `swarm query`, `swarm status`, `swarm result`, `loop`, `session-default`, `list`, `tab-list`, `status`

**Workarounds required:** `session-default` for loop flag passing, `DOM_FIRST_ATTR` instead of `DOM_FIRST_HREF`, 12-minute Maven build for MockSite

---

## C. Issues Found

```json
{
  "issues": [
    {
      "title": "Task instruction uses $(./b4w.ps1) invocation that does not work in bash",
      "severity": "High",
      "category": "Documentation",
      "reproduction": "On Linux/bash, follow task instruction to use $(./b4w.ps1) goto <url>. The $(...) is command substitution in bash, not invocation.",
      "expected": "Either the task should specify the correct Linux invocation (./b4w.sh) or the wrapper scripts should be unified across platforms.",
      "actual": "SKILL.md §Invocation explicitly warns: 'The $(./b4w.ps1) <command> syntax shown in some task instructions does NOT work in bash — $(…) is command substitution, not invocation.' Yet the task instructions mandate this exact syntax.",
      "rootCause": "The task template hardcodes a Windows/PowerShell-specific invocation pattern that is incompatible with Linux/bash. The SKILL.md documentation correctly identifies this issue but the task instructions contradict it.",
      "codePointer": "",
      "suggestion": "- Update task templates to use platform-appropriate invocation (./b4w.sh on Linux, ./b4w.ps1 on Windows)\n- Add a wrapper that auto-detects platform and selects the right script\n- Consider a single ./b4w entry point that works cross-platform"
    },
    {
      "title": "MockSite requires undocumented 12+ minute Maven local build prerequisite",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Run ./bin/test.ps1 mock-site from a fresh checkout. It fails because browser4-rest and pulsar-tests-common JARs are not in local Maven repo. Requires ./mvnw install -pl browser4-rest -am -DskipTests (12+ minutes).",
      "expected": "The task should document that MockSite depends on local Maven artifacts that must be built first, or provide a one-command setup step.",
      "actual": "MockSite failed to start with dependency resolution errors. Required a 12-minute multi-module Maven build before MockSite could launch.",
      "rootCause": "MockSite's Maven POM depends on sibling modules (browser4-rest, pulsar-tests-common) that aren't published to Maven Central. They must be mvn installed locally first.",
      "codePointer": "",
      "suggestion": "- Add a setup step to the task: './mvnw install -pl browser4-rest -am -DskipTests && pwsh ./bin/test.ps1 mock-site'\n- Document expected build time (~12 minutes) in task prerequisites\n- Consider pre-building these artifacts in CI to eliminate this step"
    },
    {
      "title": "Crawl execution is extremely slow — 428s for 3 URLs, 600s timeout for link discovery",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "Run crawl --seed-file urls.txt --depth 0 --sql @query.sql with 3 local MockSite URLs. Observe a 428-second runtime. Run crawl with link discovery (-d 1 or 2) and observe 600s timeout.",
      "expected": "Crawling 3 lightweight local pages should complete in under 30 seconds.",
      "actual": "3 local URLs took 428 seconds (2+ minutes per page). Link discovery crawl timed out at 600 seconds while still waiting for the first page.",
      "rootCause": "The crawl mechanism appears to use a full browser page load with extensive waiting. The 'waiting for first page' polling message suggests the page load is stalling or the load-detection heuristic is too conservative.",
      "codePointer": "",
      "suggestion": "- Reduce default page load timeout for simple static pages\n- Add a 'fast mode' for local/static pages that skips heavy load detection\n- Profile the page load pipeline to identify the bottleneck\n- Consider using the scrape API (SimpleHttpFetcher) for depth-0 bulk fetches"
    },
    {
      "title": "Crawl link discovery cannot see JavaScript-added CSS classes",
      "severity": "High",
      "category": "Product",
      "reproduction": "1. Open crawl hub in browser — eval confirms a.product elements exist. 2. Run crawl with -ol a.product — diagnostic: 'The page has 12 anchors but a.product matched zero elements.'",
      "expected": "Link discovery should work with the same selectors that work in the browser DOM.",
      "actual": "The crawl's page-loading mechanism produces different HTML than the browser DOM. JavaScript-added CSS classes are invisible. X-SQL DOM_LOAD_AND_SELECT confirmed same issue.",
      "rootCause": "The crawl uses a non-JS HTTP fetch that captures server-rendered HTML without executing JavaScript. Client-side class additions are invisible. This is a fundamental architectural gap.",
      "codePointer": "",
      "suggestion": "- Document clearly that crawl link selectors must match server-rendered HTML, not JS-modified DOM\n- Add a --browser-links flag to extract links from the live browser DOM\n- Update the diagnostic to mention this JS vs static HTML distinction\n- Consider a 'crawl inspect' command that shows what the crawl fetcher sees"
    },
    {
      "title": "Swarm worker pool stalls — jobs stuck as 'queued' indefinitely",
      "severity": "Critical",
      "category": "Reliability",
      "reproduction": "1. swarm create --display-mode HEADLESS. 2. swarm query --sql @q.sql --seed-file urls.txt --refresh. 3. Observe 1/3 complete, 2 stuck. 4. Clear, recreate, resubmit. 5. 0/3 complete, all stuck for 300+s.",
      "expected": "All swarm jobs should be picked up by workers and complete within a reasonable time.",
      "actual": "First attempt: 1/3 completed, 2 stuck. Second attempt: 0/3 completed, all stuck. --wait timed out at 300s.",
      "rootCause": "The swarm worker pool appears to have a race condition or initialization failure. The worker pool may depend on session state that was corrupted, or headless browser contexts may fail to initialize silently.",
      "codePointer": "",
      "suggestion": "- Add health-check endpoint for swarm worker pool status\n- Surface worker initialization errors in swarm status output\n- Add automatic worker restart on stall detection\n- Add a --timeout-per-job flag instead of only the global --wait timeout"
    },
    {
      "title": "Loop subcommand cannot pass -s <session> flag through -- separator",
      "severity": "Medium",
      "category": "Product",
      "reproduction": "./b4w.sh loop --name test --count 2 -i 10 -- -s price-watch eval 'document.title'. Observe: 'Error: Unknown command: price-watch'",
      "expected": "The -s price-watch should be passed as arguments to the nested browser4-cli process.",
      "actual": "The nested CLI interprets 'price-watch' as a command name. Argument parsing after -- drops flag-value association.",
      "rootCause": "The argument tokenization after -- splits -s and price-watch into separate tokens that are passed independently. The nested CLI sees 'price-watch' as a positional argument.",
      "codePointer": "cli/browser4-cli/src/loop.rs — function that assembles subcommand arguments for the nested browser4-cli process",
      "suggestion": "- Fix argument assembly to preserve flag-value pairs\n- Add a test case for loop subcommand with -s flag\n- Document the known limitation in loop.md\n- Consider adding --session-name as a loop-level flag"
    },
    {
      "title": "DOM_FIRST_HREF returns empty while DOM_FIRST_ATTR with 'href' works",
      "severity": "Low",
      "category": "Product",
      "reproduction": "In X-SQL query scoped to .product-card, DOM_FIRST_HREF(DOM, '.product-link') returns empty. DOM_FIRST_ATTR(DOM, '.product-link', 'href') returns correct relative URL.",
      "expected": "DOM_FIRST_HREF should return the href attribute value.",
      "actual": "DOM_FIRST_HREF returned empty for all 6 product cards. DOM_FIRST_ATTR works correctly.",
      "rootCause": "DOM_FIRST_HREF may use a different DOM traversal or attribute resolution strategy than DOM_FIRST_ATTR. The scoped DOM context may affect how href resolution works.",
      "codePointer": "",
      "suggestion": "- Investigate the implementation difference between DOM_FIRST_HREF and DOM_FIRST_ATTR for href\n- Add a test case for DOM_FIRST_HREF with scoped DOM and relative URLs\n- Document any known limitations in x-sql.md"
    },
    {
      "title": "htmlsnapshot capture HTTP timeout at 60s is too short and --timeout flag doesn't affect it",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "./b4w.sh htmlsnapshot (after stale session). Observe 'HTTP request timed out [tool=html_snapshot_capture, timeout=60s]'. Using --timeout 120 does not change the 60s value.",
      "expected": "The global --timeout flag should override the default 60s tool timeout.",
      "actual": "Capture timed out at exactly 60 seconds. Passing --timeout 120 did not change the timeout value in the error message.",
      "rootCause": "The HTTP client timeout for MCP tool calls appears to be separate from the --timeout global flag.",
      "codePointer": "",
      "suggestion": "- Make --timeout flag override the MCP HTTP request timeout\n- Increase default htmlsnapshot capture timeout to 120s\n- Document which timeouts --timeout affects"
    },
    {
      "title": "Constant Rust compilation overhead on every command invocation (~0.5s)",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any ./b4w.sh command. Observe 'Compiling browser4-cli ... Finished dev profile in 0.XXs' before every command.",
      "expected": "In dev mode, the binary should be compiled once and reused, or a daemon mode should be available.",
      "actual": "Every command invocation recompiles the Rust binary (~0.4-0.6s).",
      "rootCause": "The ./b4w.sh wrapper uses cargo run which has per-invocation overhead.",
      "codePointer": "b4w.sh — the cargo run invocation",
      "suggestion": "- Build the binary once with cargo build and use the artifact directly in dev mode\n- Add a 'dev install' command that builds and symlinks the binary"
    },
    {
      "title": "dead_code compiler warning clutters every command output",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any ./b4w.sh command. Observe: 'warning: constant QUICK_START_COMMANDS is never used --> src/help.rs:83:7'",
      "expected": "Compiler warnings should be suppressed in normal operation.",
      "actual": "Every command output includes a 7-line Rust compiler warning about an unused constant.",
      "rootCause": "QUICK_START_COMMANDS in src/help.rs is defined but never referenced.",
      "codePointer": "cli/browser4-cli/src/help.rs:83 — QUICK_START_COMMANDS constant",
      "suggestion": "- Either use the QUICK_START_COMMANDS constant or remove it\n- Add #[allow(dead_code)] attribute\n- Consider cargo build --quiet in the wrapper script"
    },
    {
      "title": "session-default with swarm interaction causes session loss",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "1. swarm create. 2. session-default price-watch. 3. swarm close. 4. list — shows 'No active browser sessions' even though price-watch session should still exist.",
      "expected": "Closing the swarm session should not affect other named sessions.",
      "actual": "After swarm close + session-default changes, all sessions disappeared. The price-watch named session was lost.",
      "rootCause": "Session lifecycle management has an interaction between session-default, swarm create (which sets default to SWARM), and swarm close.",
      "codePointer": "",
      "suggestion": "- Add session isolation guarantees — closing one session should never affect others\n- Document the interaction between session-default and swarm session management\n- Add a warning when session-default is about to overwrite an existing default"
    },
    {
      "title": "Commands compile on every invocation making shell scripts slow",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run the AC6 shell loop script. Each ./b4w.sh invocation recompiles the Rust binary, adding ~0.5s per command. A 3-URL loop with 4 commands per URL adds ~6s of overhead.",
      "expected": "The binary should be compiled once and reused across invocations.",
      "actual": "Each ./b4w.sh invocation independently runs cargo, adding noticeable compilation overhead in scripts.",
      "rootCause": "The ./b4w.sh script uses cargo run which has per-invocation overhead. No persistent dev server mode.",
      "codePointer": "b4w.sh",
      "suggestion": "- Build the binary once with cargo build and use the artifact directly\n- Implement a daemon mode where the CLI stays resident"
    }
  ],
  "assessment": {
    "completionStatus": "Partially Successful — 4 of 6 ACs passed (AC1, AC2, AC5, AC6), 2 failed (AC3 crawl link discovery, AC4 swarm). Both failures stem from backend reliability issues.",
    "successRate": "67% — 4 of 6 acceptance criteria fully satisfied. AC2 and AC5 required workarounds.",
    "issuesFound": 12,
    "majorBlockers": "1) Swarm worker pool consistently stalls — critical for any parallel extraction workflow. 2) Crawl link discovery cannot see JS-added CSS classes — makes crawl with link discovery unreliable for modern pages. 3) MockSite requires 12-minute local Maven build that is undocumented.",
    "mostConfusingAspects": "1) Task says to use $(./b4w.ps1) but SKILL.md says this doesn't work in bash. 2) Crawl and X-SQL query use a different page-loading mechanism than the browser, causing selector mismatches. 3) Session lifecycle management becomes confusing when mixing swarm, named sessions, and session-default. 4) The per-command Rust compilation overhead is unexpected for first-time users.",
    "mostValuableImprovements": "1) Fix swarm worker pool stalling. 2) Document or fix crawl/browser DOM mismatch for JS-added classes. 3) Fix loop subcommand -s flag passing. 4) Eliminate per-command Rust compilation overhead. 5) Remove dead_code warning from output. 6) Add a pre-built binary mode for dev use.",
    "usabilityRating": 4
  }
}
```

---

## D. Overall Assessment

- **Task completion status:** Partially Successful
- **Estimated task success rate:** 67% (4/6 ACs)
- **Number of issues found:** 12 (1 Critical, 4 High, 4 Medium, 3 Low)
- **Major blockers:** Swarm worker pool stalling (Critical), crawl link discovery JS-class mismatch (High), crawl extreme slowness (High)
- **Most confusing aspects:** Task/docs invocation contradiction, crawl/browser DOM mismatch, session lifecycle complexity, per-command compilation overhead
- **Most valuable improvements:** Fix swarm workers, document JS-class limitation, fix loop `-s` passing, eliminate compilation overhead, suppress compiler warnings
- **Overall usability rating:** **4/10**

The core functionality (navigation, X-SQL extraction, single-page queries) works well. The documentation (SKILL.md) is comprehensive and well-structured. However, the bulk/scale features (crawl link discovery, swarm parallel execution) have critical reliability issues that make them unusable without workarounds. The dev-mode experience is hampered by per-command compilation overhead and compiler warning noise.
