All done. Here is the complete deliverable.

---

## A. Task Result

All five acceptance criteria were completed with verified artifacts:

- **AC1 — Free WebMiner pipeline on small corpus ✅** — 4 MockSite product pages exported via `goto` + `htmlsnapshot export` into `.test-sessions/corpus-single/`, then `java -jar ~/.scent/webminer/lib/scent-miner.jar all corpus-single` completed the full pipeline: **encode** (69-dim features, 253 nodes → CSV) → **SMILE in-process KMeans** (k auto-detected) → **views** (interactive `index.html` + `.xlsx` + `.json`). Clustered results landed at `.test-sessions/corpus-single-ml-output/kmeans-result/p20260814223650/`; views at the printed temp path `/tmp/pulsar-vincent/ml/tasks/unsupervised/result/p20260814223650/predictionAndMinimalFeatures.views/`. Confirmed the right branch: corpus is comfortably below 1,000 pages → free SMILE tier, zero tokens.
- **AC2 — Production-scale decision point ✅** — documented in `.test-sessions/ac2-production-scale-decision.md`: a 1,210-page/day export (121 unique MockSite pages × 10 daily snapshots) exceeds the free-tier `<1,000 pages` guidance → routes to the **Apache Spark commercial tier** (scales to 100K+ pages/day). Acquisition patterns from AC3–AC5 stay identical; only the ML backend changes. Proprietary pipeline not executed locally, as expected.
- **AC3 — Single-page acquisition ✅** — 4 product pages (`/ec/dp/B0A000001`, `B0APP0002`, `B0GAM0001`, `B0BAB0001`) each captured with `htmlsnapshot` and exported to a distinct file; titles verified.
- **AC4 — Bulk known URLs ✅** — 7-URL seed file → `crawl --seed-file … --depth 0 --refresh` fetched 7/7 pages in ~26 s; HTML staged via `webdb export` into `.test-sessions/corpus-crawl/` (7/7 `.htm` files, titles verified).
- **AC5 — Swarm high throughput ✅** — `swarm create --display-mode HEADLESS` → `swarm query --seed-file … --sql @swarm-extract.sql --refresh --wait`: 7/7 jobs completed in ~20 s; every result payload verified with correct title + price + URL (after a selector fix — first attempt with `h1`/`.price` returned the site header title and empty price; `htmlsnapshot inspect` discovered `#productTitle`/`.price-row`). Swarm session closed.

## B. Execution Trace

**Commands used (all `./b4w.ps1` from repo root; every temp file under `.test-sessions/`):** `help` · `goto` ×4 · `htmlsnapshot` ×4 · `htmlsnapshot export --file …` ×4 · `eval "typeof __pulsar_utils__" --json` · `open --fresh --headless <url>` · `crawl --seed-file … --depth 0 --refresh` · `webdb export "<urls>" <dir>` · `swarm create --display-mode HEADLESS` · `swarm list --clear` · `swarm query --seed-file … --sql @… --refresh --wait` ×2 · `swarm result <id>` ×~15 (full + short IDs) · `swarm status <id>` · `swarm list` · `swarm close` · `status` · `doctor` — plus `java -jar …/scent-miner.jar all corpus-single` and curl checks of MockSite.

**Major steps:** verified MockSite on :18080 (101 products + 20 categories via `/ec/sitemap.xml` and `EcommerceController.kt`); used the already-installed `scent-miner.jar` v0.0.7 (launcher script ships with the web-miner project, not this repo — jar path is valid per the SKILL); discovered selectors with `htmlsnapshot inspect` before the successful swarm extraction.

**Key decisions:** used `java -jar` instead of `.\webminer.ps1` (jar already installed locally); used `webdb export` to stage crawl HTML per the task's hint + webdb.md; cleared 21 stale swarm tasks per the documented warning before submitting.

**Workarounds required:** (1) `open --fresh` to escape the stale-session `__pulsar_utils__` capture failure; (2) re-discovery of selectors via `htmlsnapshot inspect` after the first X-SQL attempt returned wrong fields (the docs explicitly say to do this — it worked exactly as designed); (3) always using **full** task IDs for `swarm result` after discovering truncated IDs fail.

**Files produced:** `.test-sessions/corpus-single/` (4 HTML), `.test-sessions/corpus-crawl/` (7 HTM), `.test-sessions/corpus-single-ml-output/` (clustered CSVs), `.test-sessions/seed-products.txt`, `.test-sessions/swarm-extract.sql`, `.test-sessions/ac2-production-scale-decision.md`, `.test-sessions/20260814-webminer-structuring-evaluation.md`, `.test-sessions/20260814-webminer-structuring-issues.json`.

```json
{
  "issues": [
    {
      "title": "htmlsnapshot fails on a reused/stale session with opaque ReferenceError: __pulsar_utils__ is not defined",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "1) Ensure a DEFAULT session already exists that was created before the current backend start (e.g. a session surviving from a previous day's run). 2) Run `./b4w.ps1 goto http://localhost:18080/ec/dp/B0A000001` (CLI prints \"Using existing session DEFAULT\"). 3) Run `./b4w.ps1 htmlsnapshot` — retry once: same failure. 4) `./b4w.ps1 eval \"typeof __pulsar_utils__\" --json` returns \"undefined\".",
      "expected": "htmlsnapshot should capture the page, or fail with an actionable message telling the user the page context is missing the injected utility script and how to recover (e.g. `open --fresh`).",
      "actual": "`ERROR: html_snapshot_capture failed: ReferenceError: __pulsar_utils__ is not defined at <anonymous>:1:1 ... help: html_snapshot.capture(Arg(name=sessionId, type=String, defaultValue=null))`. Two consecutive attempts failed identically. `open --fresh --headless <url>` immediately fixed it; all subsequent captures worked.",
      "rootCause": "The htmlsnapshot capture tool evaluates JS in the page that depends on a `__pulsar_utils__` object injected by the browser engine on document load. In the observed environment the pre-existing DEFAULT session's browser contexts had been created before the currently-running backend (JVM started 20:31:54 local, jars built 20:07–20:15; the failing session predated the eval session), so its page contexts never received the injection (`eval` confirmed `typeof __pulsar_utils__ === \"undefined\"`). The backend does not verify the injection before capture and the CLI error surface exposes only the raw ReferenceError with no recovery hint. Exact trigger (session surviving a backend restart vs. older backend build) needs confirmation: re-create by starting a backend, opening a session, restarting the backend, then running goto + htmlsnapshot against the surviving session.",
      "codePointer": "cli/browser4-cli/src/main.rs:handle_html_snapshot_capture (error path should detect the ReferenceError/missing-injection signature and suggest `open --fresh`); backend side: the html_snapshot.capture MCP tool in browser4-agentic/browser4-agent-tools should check for the utils object before evaluating dependent JS.",
      "suggestion": "- In handle_html_snapshot_capture, special-case ReferenceError mentioning __pulsar_utils__ and print: \"The page context is missing Browser4's injected utilities (stale session?). Run `open --fresh <url>` to recreate the session, or `reload` and retry.\"\n- In the backend capture tool, preflight `typeof __pulsar_utils__ === 'undefined'` and return a structured error (code + hint) instead of a raw JS exception\n- Document this failure mode under §5 Critical Warnings of SKILL.md (stale-session recovery)"
    },
    {
      "title": "swarm result with a truncated task ID silently returns an empty result with a misleading 'dropped or evicted' note",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "1) `./b4w.ps1 swarm query --seed-file urls.txt --sql @q.sql --refresh --wait` — note the completion table prints 8-char truncated IDs (e.g. `done 264fe01b ...`). 2) Run `./b4w.ps1 swarm result 264fe01b`. 3) Run `./b4w.ps1 swarm result 264fe01b-32b4-40cf-9f13-7dbfd4f1bc0c`.",
      "expected": "The truncated ID printed by the CLI's own summary should work, or `swarm result` should reject short IDs with 'task ID not found — use the full ID from `swarm list`'. Never a silent fake-empty result.",
      "actual": "Short ID: `{\"id\":\"264fe01b\",\"resultSet\":[],\"pageContentBytes\":0,\"error\":null}` plus note \"resultSet is empty and pageContentBytes is 0 — the page was never fetched. The task may have been dropped or evicted.\" — repeated on every retry (not a timing race). Full UUID returns the correct resultSet (title/price/url). All 8 short IDs from both batches behaved the same.",
      "rootCause": "Backend `SwarmService.getStatus` (browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/SwarmService.kt:~232) does an exact-key Guava cache lookup `responseCache.getIfPresent(request.id)`; a truncated ID never matches, so it returns a NOT_FOUND placeholder ScrapeResponse (statusCode SC_NOT_FOUND, null resultSet/pageContentBytes). The CLI's `handle_swarm_result` (cli/browser4-cli/src/main.rs:9795-9848) ignores statusCode entirely and, whenever resultSet is empty and pageContentBytes<=0, prints the 'dropped or evicted' guidance — which is wrong for a lookup miss. The CLI's own `--wait` completion table (swarm_wait_for_jobs, main.rs:~10016) prints only the first 8 chars of each task ID, teaching users to copy IDs that cannot resolve.",
      "codePointer": "cli/browser4-cli/src/main.rs:9825-9844 (handle_swarm_result empty-result note — must check statusCode/404 first); cli/browser4-cli/src/main.rs:swarm_wait_for_jobs (print FULL task IDs in the completion table); optionally browser4-rest SwarmService.getStatus (prefix-aware lookup or explicit not-found error body).",
      "suggestion": "- In handle_swarm_result, check the response statusCode: when SC_NOT_FOUND/404 (or `error` present), print \"Task ID not found. Use the full ID from `swarm list`\" instead of the eviction note\n- Print full UUIDs in the `--wait` completion table (or resolve prefixes client-side against the tracked-task store before calling the backend)\n- Add a regression test: short-ID lookup returns a not-found error, not a fake empty payload"
    },
    {
      "title": "All-digit task IDs are dropped by the CLI parser: 'swarm result 74126843' → 'Task ID is required.'",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "`./b4w.ps1 swarm result 74126843` (or `swarm status 74126843`; quoting does not help). Compare: `./b4w.ps1 swarm result 74126843-xxxx` works; `./b4w.ps1 swarm result x74126843` works.",
      "expected": "The positional task ID is accepted verbatim as a string regardless of whether it is digit-only.",
      "actual": "\"Error: Task ID is required.\" — the positional never reaches the id parameter. Real-world collision: a task with ID 74126843-cac3-4ebf-86bd-871e369dc60c exists; the CLI's completion table prints exactly `74126843` as its truncated ID, so copying that row's ID produces this hard error (~2.3% of 8-char prefixes are all-digit, so this will recur).",
      "rootCause": "The CLI arg parser stores digit-only positional tokens as JSON numbers rather than strings (cf. comment in cli/browser4-cli/src/main.rs:7380 \"Numeric positional args (e.g. `899`) get stored as JSON numbers\"). The swarm-result CommandDef's tool_params_fn (cli/browser4-cli/src/commands.rs:2705-2707) reads the value with `get_str(args, \"id\")`, which returns None for a JSON number, so the id is empty and handle_swarm_result errors out.",
      "codePointer": "cli/browser4-cli/src/commands.rs:2705-2707 (swarm-result tool_params_fn — accept numeric JSON values too, or better: fix the parser to keep digit-only positionals as strings for string-typed args); same for swarm-status at commands.rs:2691-2693.",
      "suggestion": "- In the parser, do not auto-convert positional tokens to JSON numbers for commands whose positional is declared as a string arg\n- Defensively, in tool_params_fn use `get_str(args,\"id\").or_else(|| args.get(\"id\").and_then(|v| v.as_f64()).map(|n| n.to_string()))`\n- Add unit test: `swarm result 12345678` passes the ID through (commands.rs test block, cf. test_swarm_result_tool_name at commands.rs:4213)"
    },
    {
      "title": "Bulk crawl gives no hint where fetched HTML is stored or how to stage it for downstream tools",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "`./b4w.ps1 crawl --seed-file urls.txt --depth 0 --refresh` — read the completion output; then try to locate the fetched HTML files from the docs/help alone.",
      "expected": "SKILL.md §4d presents `crawl --seed-file urls.txt --depth 0` as the 'bulk download' acquisition path for WebMiner; a first-time user expects to end up with HTML files (or at least a pointer to where the pages are cached and how to export them).",
      "actual": "Crawl completion lists page titles only. The HTML is silently stored in the Browser4 webdb cache; exporting requires discovering `webdb export <urls> <dir>` separately (it is documented in references/webdb.md and the [Storage] help section, but nothing in the crawl output, crawl.md, or SKILL.md §4d cross-links it). The scenario itself had to hint: 'If your workflow stores fetched HTML outside the Browser4 cache, stage those files into a WebMiner input directory.'",
      "rootCause": "Documentation/workflow gap: crawl.md describes extraction modes (--sql) but not the post-crawl HTML staging flow; the §4d decision tree treats crawl as producing a corpus but the HTML lives in the cache. No cross-link between crawl output and webdb export.",
      "codePointer": "skills/browser4-cli/references/crawl.md (add a 'Getting the fetched HTML' section); skills/browser4-cli/SKILL.md §4d (add webdb export step); optionally a one-line hint in the crawl completion output (cli/browser4-cli/src/main.rs:handle_crawl).",
      "suggestion": "- Add to crawl completion output: \"Pages are cached in webdb — use `webdb export \\\"<urls>\\\" <dir>` to write the HTML to disk.\"\n- Add a 'Getting the fetched HTML' section to crawl.md showing the crawl → webdb export → downstream-tool flow\n- Extend SKILL.md §4d bulk-download branch with the webdb export command"
    },
    {
      "title": "scent-miner SKILL.md views-path documentation is Windows-centric and doesn't match Linux reality",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "On Linux run `java -jar scent-miner.jar all <dir>` and compare the printed views path against skills/scent-miner/SKILL.md's Output section (`%TEMP%\\<app>-pereg\\ml\\tasks\\...`, `<app>` = `pulsar` for direct jar runs).",
      "expected": "The documented path template should match observed output on non-Windows platforms (or explicitly say the pattern is Windows-only).",
      "actual": "Actual views path was `/tmp/pulsar-vincent/ml/tasks/unsupervised/result/p20260814223650/predictionAndMinimalFeatures.views` — app prefix `pulsar-vincent` (not `pulsar`) and no `-pereg` segment anywhere. The doc's template only fits Windows; the Linux layout differs in both the temp root and the app-name derivation.",
      "rootCause": "The doc generalizes a Windows-specific observed path (`%TEMP%\\<app>-pereg\\…`). On Linux the temp root is /tmp, the app dir is derived differently (e.g. `<app>-<user>`), and the `-pereg` subdir naming does not appear. The end-of-run printout does state the correct absolute path, which mitigates it, but the reference table is misleading for Linux users.",
      "codePointer": "skills/scent-miner/SKILL.md (Output section) — update the path template to cover Linux/macOS or state it is Windows-specific and Linux users should rely on the printed path.",
      "suggestion": "- Rewrite the Output section: \"on Windows: %TEMP%\\<app>-pereg\\…; on Linux/macOS: $TMPDIR or /tmp/<app>-<user>/… — the exact path is always printed at the end of the run\"\n- Add a Linux example path to the section"
    },
    {
      "title": "webdb export mixes a JSON summary with a human-readable auto-snapshot block in default output",
      "severity": "Low",
      "category": "UX",
      "reproduction": "`./b4w.ps1 webdb export \"<urls>\" <dir>` — observe stdout: the JSON summary is followed by `### Page`, `### Snapshot`, and a `💡 Tip` block.",
      "expected": "Default output should be one coherent format — either the human summary or the JSON envelope, not both interleaved.",
      "actual": "The JSON summary ({\"total\":7,\"succeeded\":7,…}) is immediately followed by a snapshot section (Page URL/Title, snapshot file path, tip). Scripting users capturing stdout get human text appended after the JSON; interactive users get an unrelated snapshot dump. (The command triggered an automatic post-action snapshot.)",
      "rootCause": "The webdb export handler returns its JSON result but the command also triggers the CLI's automatic post-command snapshot path, which appends its human-readable block to stdout. Either the handler doesn't suppress the auto-snapshot (unlike other storage commands), or the snapshot printer isn't gated by the command's output mode.",
      "codePointer": "cli/browser4-cli/src/main.rs — the webdb-export handler and/or the automatic post-action snapshot printer (auto-snapshot after commands that modify browser state); verify whether --json suppresses it.",
      "suggestion": "- Suppress the auto-snapshot block for `webdb export` (it does not change page state for the user's purposes), or route it to stderr like tips\n- Ensure `--json` mode prints only the JSON envelope"
    },
    {
      "title": "Invocation guidance diverges between SKILL.md (b4w.sh on Linux) and the evaluation task templates (b4w.ps1)",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "On a Linux machine with pwsh installed, compare skills/browser4-cli/SKILL.md's invocation table (Linux/macOS → ./b4w.sh) with task instructions that mandate `./b4w.ps1 <command>`.",
      "expected": "One canonical invocation story per platform across docs and templates.",
      "actual": "Both work on this machine (b4w.ps1 has a pwsh shebang), but the task template's mandated `./b4w.ps1` contradicts the SKILL's Linux recommendation of `./b4w.sh`, and `./b4w.ps1` silently fails on Linux hosts without pwsh. A first-time Linux user following one source gets behavior that contradicts the other.",
      "rootCause": "Two wrappers exist for good reason (pwsh-native vs bash-safe quoting), but platform guidance is split across SKILL.md and task templates; there is no statement that b4w.ps1 is also valid on Linux when pwsh is installed.",
      "codePointer": "skills/browser4-cli/SKILL.md (Invocation table) — add a row/note for \"Linux with pwsh: ./b4w.ps1\" and state the pwsh prerequisite; align task templates.",
      "suggestion": "- Add to the invocation table: \"Linux/macOS with pwsh installed → ./b4w.ps1 <command> (same as Windows)\"\n- Note the prerequisite: \"requires PowerShell 7+ on PATH\""
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all five acceptance criteria (AC1–AC5) were completed with verified artifacts: 4-page and 7-page corpora acquired three different ways (single-page export, crawl seed-file + webdb staging, swarm query), the free SMILE WebMiner pipeline ran end-to-end (encode → cluster → views with index.html/xlsx/json), and the production-scale (>1,000 pages/day) decision point was documented mapping to the Apache Spark commercial tier. Three workarounds were required along the way (fresh session, selector re-discovery, full task IDs).",
    "successRate": "90% — every scenario step ultimately succeeded; the failures encountered were environmental/UX (stale-session capture failure, misleading short-ID result lookup, digit-only ID parsing) plus one self-inflicted selector miss that the documented `htmlsnapshot inspect` workflow corrected",
    "issuesFound": 7,
    "majorBlockers": "No true blockers. The most disruptive incident was the htmlsnapshot ReferenceError on a reused pre-existing session, which required discovering the `open --fresh` workaround by experimentation; the error message gave no hint.",
    "mostConfusingAspects": "1) The `swarm result` empty payload + 'dropped or evicted' note when using the truncated task IDs that the CLI's own completion table prints. 2) The htmlsnapshot `__pulsar_utils__` ReferenceError on a stale session with no recovery guidance. 3) Where crawl stores fetched HTML (webdb cache) and how to get it out (webdb export) — discoverable only by reading the webdb reference doc. 4) The WebMiner views output path living in a temp dir far from the clustered results (mitigated by the end-of-run path printout).",
    "mostValuableImprovements": "1) Fix swarm task-ID handling: print full IDs in the completion table, and make `swarm result` distinguish 'not found' from 'empty result' (currently the #1 data-loss scare). 2) Detect the missing __pulsar_utils__ injection in htmlsnapshot capture and suggest `open --fresh`. 3) Cross-link crawl → webdb export in SKILL.md §4d and the crawl completion output. 4) Keep digit-only positionals as strings in the arg parser.",
    "usabilityRating": 7
  }
}
```

**Highlights beyond the issues:** the `help` output (Quick Start box, workflow recipes, ★ markers), the SKILL.md decision trees (which map 1:1 onto this scenario), `htmlsnapshot inspect`'s automatic selector discovery, and swarm `--wait` all worked impressively well for a first-time user. All temporary artifacts are under `.test-sessions/`; the swarm session was closed and no repo files were modified.
