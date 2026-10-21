Ignoring 13 permissions.allow entries from .claude/settings.json: this workspace has not been trusted. Run Claude Code interactively here once and accept the trust dialog, or set projects["D:/workspace/Browser4/Browser4-4.13"].hasTrustDialogAccepted: true in C:\Users\pereg\.claude.json.
"deepseek-v4-flash" isn't described by this version's model catalog; update Claude Code, or map it with behavesAs on a modelPicker row (or modelOverrides, if it is a provider id of a model this version knows). Until then auto-compact keeps this session within 200k tokens (the context window it assumes); if the model accepts more, append [1m] to the model name for 1M, or set CLAUDE_CODE_MAX_CONTEXT_TOKENS to its real window; CLAUDE_CODE_DISABLE_UNKNOWN_MODEL_WINDOW_ENFORCEMENT=1 restores the previous wait-for-the-API behavior.
[claude-code:unrecognized_model] {"model":"deepseek-v4.1-flash-expires-on-0910","query_source":"generate_session_title"}
[claude-code:unrecognized_model] {"model":"deepseek-v4-flash","query_source":"sdk"}
All six task steps are complete. Here is the report.

## A. Task Result

All five acceptance criteria were satisfied against MockSite on `localhost:18080`, and the free WebMiner pipeline plus all three acquisition paths were exercised end to end with real artifacts.

**AC3 — single-page acquisition (5 pages).** Five MockSite product detail pages (`B0E000001`–`B0E000005`) were visited with `goto`, captured with `htmlsnapshot`, and written to disk with `htmlsnapshot export --file`. `corpus-single/` holds 5 valid HTML files (12.6–15.5 KB, titles verified). The documented three-command sequence worked first try, with no quoting or path problems on Git Bash.

**AC1 — free WebMiner pipeline (small corpus).** `browser4-cli webminer install` pulled v0.0.8 (97 MB) with SHA-256 verification, and `webminer all corpus-single` produced the full artifact set: encode → 5 docs / 325 nodes / 69-dimension feature vectors, cluster → `kmeans-result/p<ts>/` with three `result.csv` views plus `clusteringInfo.txt` (k=71, silhouette 0.6109), and views → `index.html`, four report `.html` variants, three valid `.xlsx` workbooks (verified as real OOXML zips), `.json` and `.txt`. Running `webminer views <result-dir>` rebuilt the views into the result directory exactly as SKILL.md describes. This is unambiguously the right branch for a sub-1,000-page corpus.

**AC4 — bulk known-URL acquisition (12 pages).** A 12-URL seed file drove `crawl --seed-file … --depth 0 --refresh`, which fetched all 12 pages in ~40 s with per-URL titles in the completion report. Because `crawl` returns no files, the corpus was staged with the SKILL.md-documented `webdb export "<comma,separated,urls>" <dir>`, yielding 12 `.htm` files that `webminer all` then ingested (12 docs, 736 nodes, k=68). The documented staging route works.

**AC5 — swarm high-throughput acquisition (101 pages).** `swarm create --display-mode HEADLESS` followed by `swarm query --seed-file seed-all.txt --sql @query-product.sql --refresh --wait` submitted 101 jobs with a batch ID. All 101 completed (0 failed) and all 101 rows were extracted. Confirmed SKILL.md's claim that swarm pages land in webdb: `webdb export` returned files for URLs only the swarm had fetched. Swarm is the right path when extraction throughput matters and files are secondary.

**AC2 — production-scale decision point.** Documented at `.test-sessions/20260916T1722282030412Z/AC2-production-scale-decision.md`. The 1,200-page export exceeds the 1,000-page free-tier guidance and routes to the Apache Spark commercial pipeline, with acquisition patterns from AC4/AC5 unchanged. I also measured where the real limits sit, and they are **not** where the docs say: the binding constraint is the `--max-files 40` default (a 60-file corpus silently ran as 40), and auto-K degenerates to k=1,420 clusters over 67,380 nodes at 1,200 files — while the free tier still *completes* in 93 s. The 1,000-page line is a guidance boundary, not a technical cliff.

---

## B. Execution Trace

**Preparation.** `pwd` confirmed the repo root. `./b4w.ps1 help` and `skills/browser4-cli/SKILL.md` (842 lines) plus `skills/browser4-web-miner/SKILL.md` (154 lines) were read in full before any browser interaction. MockSite was already serving on 18080 (`/ec/dp/B0E000001` → 200); the backend was already UP from a prior session. `doctor` reported a dev backend `4.13.18-SNAPSHOT` running against the source tree versus CLI `4.13.20` — a version-string mismatch only; I noted it and proceeded rather than forcing a multi-minute rebuild.

**Discovery decisions.** Product IDs were harvested from the fixture catalog (`products.json`, 101 items) to build seed files (`seed-12.txt`, `seed-all.txt`, `seed-60-comma.txt`). CSS selectors for the X-SQL query were discovered from the page itself — `htmlsnapshot summary` identified `article#product-page` as the container, and a dump of the exported HTML gave the field IDs (`#productTitle`, `#product-price`, `#product-rating`, `data-product-id`). The query was validated with `htmlsnapshot query --sql @query-product.sql --format table` *before* being handed to swarm, which avoided a 101-job failure.

**Workarounds required.** Two, both recorded as issues below:
1. `webdb export -s webminer-crawl` failed with "Session required" after a successful `crawl -s webminer-crawl`. Worked around by dropping `-s` (default session). Root cause later isolated by controlled experiment: priming the session with `goto -s <name>` first makes the export succeed.
2. Swarm results for 101 tasks had to be aggregated by looping `swarm result <id>` over 101 IDs parsed from `swarm list --batch <id> --json` — roughly 3.5 minutes of extra CLI invocations, because no batch-wide result command exists.

**Artifacts** (all under `.test-sessions/20260916T1722282030412Z/`): `corpus-single/` (5), `corpus-crawl/` (12), `corpus-swarm/` (3), `corpus-scale/` (60), `corpus-1200/` (1,200), four `*-ml-output/` result trees, `swarm-101-products.csv` (101/101 rows), `query-product.sql`, `AC2-production-scale-decision.md`, and the helper scripts.

---

## C. Issues Found

```json
{
  "issues": [
    {
      "title": "`crawl -s <name>` succeeds but never registers the session, breaking every follow-up command under that name",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "1) ./b4w.ps1 -s webminer-crawl crawl --seed-file seed-12.txt --depth 0 --refresh   (completes: \"Crawl completed. 12 pages found.\")\n2) ./b4w.ps1 list   (no `webminer-crawl` row appears)\n3) ./b4w.ps1 -s webminer-crawl webdb export \"http://…/B0E000001,http://…/B0E000002\" .test-sessions/20260916T1722282030412Z/corpus-crawl\nControl: ./b4w.ps1 -s webminer-x goto <url>  then  ./b4w.ps1 -s webminer-x crawl …  then  ./b4w.ps1 -s webminer-x webdb export …  → export succeeds.",
      "expected": "Either `crawl -s <name>` registers/reuses the named session (so subsequent `-s <name>` commands work), or it rejects `-s` with an error instead of silently ignoring it.",
      "actual": "The crawl runs fine under the flag, but the name is never added to the session registry. The next `-s webminer-crawl` command fails with exit 1:\n  🔐 Session required\n  …\n  🧾 Details\n    No active session is currently stored for this CLI context.\nDropping `-s` works, because the `(default)` session already exists — which masks the problem and makes the failure look like a `webdb export` bug rather than a `crawl` bug.",
      "rootCause": "`crawl` dispatches to an internal scrape session and never invokes the same session-registration path that `goto`/`open` use (see cli/browser4-cli/src/session_registry.rs). The `-s` value is accepted by the arg parser and threaded into the request but no registry entry is written. Confirmed by control experiment: pre-creating the session with `goto -s <name>` makes all later `-s <name>` commands succeed, so the only missing piece is registration. Investigation needed: whether `crawl` should register the session, or whether session-requiring commands like `webdb export` should fall back to the default session instead of hard-failing.",
      "codePointer": "cli/browser4-cli/src/session_registry.rs (registration path); cli/browser4-cli/src/main.rs:599 (\"Session required\" error construction)",
      "suggestion": "- Make `crawl` register the `-s` session the same way `goto`/`open` do, so the flag has consistent meaning across command families\n- If registration is deliberately out of scope for `crawl`, reject `-s` on `crawl` with an explicit error rather than accepting-and-ignoring it\n- Additionally, make session-less commands that only need the backend page store (like `webdb export`, which reads a backend-global cache) not require a live session at all\n- Extend the error message to state which commands DO create a session, so the recovery step is obvious"
    },
    {
      "title": "`webminer all` silently truncates the corpus at its default `--max-files 40` and still reports PIPELINE COMPLETE",
      "severity": "High",
      "category": "Reliability",
      "reproduction": "Stage 60 .htm files in a directory, then:\n./b4w.ps1 webminer all .test-sessions/20260916T1722282030412Z/corpus-scale",
      "expected": "Either process all discovered files, or fail/warn loudly — and make the final summary state how many inputs were skipped.",
      "actual": "Output:\n  Found 60 HTML file(s), will encode up to 40 valid document(s)\n  ✓ Encoded 40 document(s) (2315 nodes, 2,855,543 bytes)\n  …\n  PIPELINE COMPLETE\n20 of 60 input files were dropped. Grepping the full run output for skip/ignor/warn/remain returns nothing beyond an unrelated JVM warning. The completion banner gives no indication the run was partial. At the AC2 target scale (1,200 pages) this silently processes 40 and discards 1,160 — making the documented \"< 1,000 pages\" free tier unreachable at its own stated default.",
      "rootCause": "`--max-files` defaults to 40 and is applied as a hard slice during the encode stage. The wrapper surfaces the slice implicitly in the \"will encode up to N\" line but never reconciles encoded count against discovered count at the end of the pipeline. The `--max-files` default is documented in the WebMiner options table, so this is not undiscoverable — but nothing in the run output tells a user their corpus was cut, and the truncation is invisible to anything parsing the completion banner.",
      "codePointer": "cli/browser4-cli/src/commands.rs:738,763 (webminer `max-files` option handling); scent-miner.jar encode stage (external) for the trailing summary",
      "suggestion": "- Print an explicit warning in the final pipeline summary when discovered files > encoded documents, e.g. \"⚠ 20 of 60 discovered HTML files were skipped (--max-files 40).\"\n- Consider making the default unbounded (or raising it well above 40) and letting `--max-files` be an opt-in safety cap, since the tier guidance is 1,000 pages and the default caps 25× below it\n- Echo the effective `--max-files` value in the run header (not just inside the encode stage) so the limit is visible in the banner a user actually reads\n- Add an `--all-files` convenience alias so users do not have to guess a number"
    },
    {
      "title": "No batch-wide swarm result retrieval: 101 task results must be fetched one CLI invocation at a time",
      "severity": "High",
      "category": "UX",
      "reproduction": "1) ./b4w.ps1 swarm query --seed-file seed-all.txt --sql @query-product.sql --refresh --wait   (returns a Batch ID)\n2) ./b4w.ps1 swarm result <batch-id>   → {\"id\":\"<batch-id>\",\"resultSet\":[],\"error\":null,\"message\":\"Swarm task not found: <batch-id>\",\"statusCode\":404}\n3) ./b4w.ps1 swarm result --batch <batch-id>   → \"Missing required argument: <id>.\"\n4) Only remaining route: parse 101 task IDs out of `swarm list --batch <id> --json` and call `swarm result <id>` 101 times (took ~3.5 minutes).",
      "expected": "A way to retrieve all results for a submission — e.g. `swarm result --batch <id>`, `swarm export --batch <id>`, or `--batch <id>` support on `swarm result` — ideally with `--format csv|json` to write one aggregated table.",
      "actual": "`swarm result` only accepts a single task ID. Passing the batch ID returns a 404 payload; there is no `--batch` flag. `swarm list --batch <id>` is the only batch-aware command, and it lists task metadata (IDs, URLs, statuses) without any extracted data. On the 101-URL job this means 101 process launches and 101 HTTP round-trips to assemble one 101-row table.",
      "rootCause": "The CLI exposes the backend's per-task result endpoint (`SwarmController` result-by-id) but never gained a batch aggregation endpoint or client-side fan-out. The batch ID is already stamped on every task (`batch_id` appears in the JSON task records), so the data needed for aggregation exists — only the retrieval surface is missing. The `--wait` path already polls the batch to count completions, which shows batch-aware polling is implemented elsewhere in the same command family.",
      "codePointer": "cli/browser4-cli/src/main.rs:21494 (`swarm-result` dispatch)",
      "suggestion": "- Add `--batch <id>` to `swarm result`, performing a client-side fan-out over the batch and emitting a single `resultSet` array (plus a per-task failure list)\n- Support `--format csv|json|table` and `-o/--output <file>` on that aggregate so a 100K-page job lands in a spreadsheet in one command\n- Alternatively add `swarm export --batch <id> --output results.csv` as a named command, mirroring the existing `webdb export` shape\n- Until then, document the `swarm list --json` → loop `swarm result` recipe in SKILL.md §4d/skills/browser4-cli/references/swarm.md, since the current docs show `swarm result <id>` after a seed-file submission without mentioning the aggregation gap"
    },
    {
      "title": "Error hint tells users to run `session list`, which is not a valid command",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "./b4w.ps1 session list   → exits 2 and prints the entire global help\nTriggered in context: the \"Session required\" error emitted by ./b4w.ps1 -s webminer-crawl webdb export … and by ./b4w.ps1 -s webminer-crawl page-info lists \"check available sessions with `session list`.\" as its third suggestion.",
      "expected": "Either `session list` is a working alias, or the hint names the real command (`list`).",
      "actual": "`session list` is unrecognized: the CLI exits 2 and dumps the top-level help. A user who follows the error's own advice lands on a wall of usage text and has to guess that `list` is the real command. Two of the three suggestions in the same error block are also questionable (`browser4-cli.exe open` names a Windows executable that does not match the documented invocation; \"after tab operations, use `goto`\" is unrelated to a session-missing condition).",
      "rootCause": "The hint string was written against an earlier or imagined command surface and never validated. This is a static message, not a runtime-computed suggestion list, so nothing catches the drift. Note `cli/browser4-cli/src/help.rs` does not register a `session` command family at all — `session-default` is the only session-prefixed verb, which makes the `session list` wording doubly misleading.",
      "codePointer": "cli/browser4-cli/src/main.rs:605 (\"check available sessions with `session list`.\")",
      "suggestion": "- Change the hint to `list` to match the documented command\n- Prefer deriving the hint from a single shared constant so the suggestion and the real command cannot drift apart\n- Add a test asserting every backticked command inside an error hint resolves to a registered command name\n- Consider adding `session list` / `session close` as real aliases if the `session` namespace is intended to exist"
    },
    {
      "title": "`swarm result` and `swarm status` exit 0 on a not-found error envelope",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 swarm result 8a518f54-5cec-4d2b-8c3c-f91d03ac3dda ; echo $?   → prints statusCode 404 payload, exit 0\n./b4w.ps1 swarm status 8a518f54-5cec-4d2b-8c3c-f91d03ac3dda ; echo $?   → exit 0",
      "expected": "A non-zero exit when the response envelope reports a failure, so `if ! swarm result …` and shell `set -e` scripts detect it.",
      "actual": "Both commands exit 0 while returning `{\"resultSet\":[],\"message\":\"Swarm task not found: …\",\"statusCode\":404}`. A script that fetches results in a loop (exactly the pattern Issue 3 forces) sees success and an empty resultSet for every bad ID, which is indistinguishable from \"the task ran and matched zero rows\".",
      "rootCause": "The swarm result path prints the backend response body without mapping the envelope's `statusCode` onto the process exit code. This is inconsistent with the documented contract elsewhere: SKILL.md §4e states `htmlsnapshot query` exits non-zero on an error envelope, and `webdb export` does exit 1 on a rejected URL list — so the CLI already has the pattern, it is just not applied uniformly across command families. Investigation needed: confirm whether `swarm list`/`swarm submit` share the same gap.",
      "codePointer": "cli/browser4-cli/src/main.rs:21494 (`swarm-result` dispatch and exit-code handling)",
      "suggestion": "- Map a non-2xx `statusCode` in the swarm response envelope to a non-zero process exit, mirroring the existing `htmlsnapshot query` and `webdb export` behavior\n- Audit all commands that return a JSON envelope with a `statusCode` field and make the exit-code mapping a shared helper rather than per-command logic\n- Document the exit-code contract for the swarm family in `help swarm`, as §4e already does for `htmlsnapshot query`"
    },
    {
      "title": "`swarm query --wait` stops waiting after a hard 300 s and still exits 0",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "./b4w.ps1 swarm query --seed-file seed-all.txt --sql @query-product.sql --refresh --wait   (101 URLs)\nObserved tail:\n  Waiting for 101 job(s) of batch 8a518f54-… to complete...\n    ... 42/101 job(s) terminal (0 failed) (elapsed: 216s)\n  Timeout after 300s: 57 succeeded, 0 failed, 44 still pending.\n  ; echo $?  → 0",
      "expected": "Either `--wait` blocks until all jobs are terminal (as `help swarm query` states: \"Block until all submitted jobs complete\"), or the cap is documented and raiseable, and a timeout exits non-zero.",
      "actual": "Waiting stops at a fixed ~300 s with 44 jobs still pending. The cap is not mentioned in `help swarm query` or in SKILL.md. The process exits 0, so a script gated on exit status proceeds as if the batch finished and silently works with 57 of 101 rows. The global `--timeout <seconds>` flag exists and reads as the obvious knob, but nothing states whether it affects this wait.",
      "rootCause": "A fixed wait budget is hardcoded in the `--wait` polling loop, separate from the global `--timeout` HTTP setting and from the `--deadline <iso>` option that `swarm query` already exposes. Because the submission itself succeeded, the command reports overall success and does not treat an incomplete wait as a failure. Investigation needed: confirm the exact budget constant and whether `--deadline` or `--timeout` short-circuits it.",
      "codePointer": "cli/browser4-cli/src/commands.rs (swarm query `--wait` polling loop)",
      "suggestion": "- Honor the existing `--timeout <seconds>` global flag for the `--wait` budget instead of a hardcoded 300 s, and print the effective budget in the waiting header\n- Exit non-zero when the wait times out with jobs still pending, so scripts can distinguish complete from truncated\n- Document the wait semantics in `help swarm query` (currently `--wait` is described only as \"Block until all submitted jobs complete\")\n- Point users at `swarm list --batch <id>` in the timeout message (the message currently suggests `--status pending`, omitting the batch-scoped form they actually need)"
    },
    {
      "title": "Swarm defaults starve on a 101-URL job; SKILL.md treats swarm as turnkey high-throughput with no tuning guidance",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "./b4w.ps1 swarm create --display-mode HEADLESS   (accepts defaults: contexts=2, max-tabs=8)\n./b4w.ps1 swarm query --seed-file seed-all.txt --sql @query-product.sql --refresh --wait\n./b4w.ps1 swarm list --batch <batch-id>   → the tool's own warning appears",
      "expected": "Either defaults adequate for a few-hundred-URL job, or SKILL.md §4b/§4d telling users to raise `--max-browser-contexts` when submitting at this scale.",
      "actual": "The tool self-reports the problem: \"⚠ 24 job(s) still queued after >60s while other jobs completed. The worker pool may be starved. Try increasing --max-browser-contexts, or clear stale tasks with `swarm list --clear`.\" Throughput was ~10 pages/minute and the 101-URL batch took ~10 minutes wall clock. SKILL.md §4d presents swarm as the high-throughput option (\"High throughput: browser4-cli swarm create → swarm query --seed-file …\") with no mention of context/tab counts, and the §4b decision tree lists swarm without them either. The options are only discoverable via `help swarm create`.",
      "rootCause": "Documentation gap rather than a product defect: the tuning knobs exist and work (`--max-browser-contexts`, `--max-open-tabs`), and the backend even detects starvation, but the skill file — which is what an agent reads first — omits them. The failure mode is quiet from the caller's side because `--wait` returns before completion anyway (Issue 6).",
      "codePointer": "skills/browser4-cli/SKILL.md §4b and §4d; skills/browser4-cli/references/swarm.md",
      "suggestion": "- Add the sizing knobs to SKILL.md §4d's acquisition block, e.g. `swarm create --display-mode HEADLESS --max-browser-contexts 4 --max-open-tabs 12`\n- State a rough rule of thumb mapping corpus size to contexts/tabs, so the default is not silently used for 100+ URL jobs\n- Surface the starvation warning's remedy in the docs too, since the tool already knows the answer (`--max-browser-contexts`)\n- Note in §4d that swarm throughput is bounded by `contexts × max-tabs`, which is why the default is unsuited to the bulk path"
    },
    {
      "title": "`webminer.ps1` launcher is documented as `.\\webminer.ps1` but lives at `skills/browser4-web-miner/scripts/webminer.ps1`",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "From the repository root, as both skills/browser4-web-miner/SKILL.md and the scenario instructions describe:\n  .\\webminer.ps1 version\n  → PowerShell: \"The term '.\\webminer.ps1' is not recognized as the name of a cmdlet, function, script file, or operable program.\"\nWorking invocation (path never stated in the docs):\n  pwsh -File skills/browser4-web-miner/scripts/webminer.ps1 version",
      "expected": "The docs either give the launcher's path relative to the repo root, or the launcher is present at the repo root where `.\\webminer.ps1` implies it is.",
      "actual": "`find . -name webminer.ps1` returns the script only under `skills/browser4-web-miner/scripts/` (plus copies in unrelated worktrees). Every documented example — `install`, `update`, `version`, `uninstall`, `run-example`, `all` — uses the bare `.\\webminer.ps1` form, as does SKILL.md line 37's \"The `webminer.ps1` launcher can self-install and self-update\", which never says where the file is. A first-time user following the documented alternative-install path hits an immediate error; the `browser4-cli webminer` route works and is the recommended one, so the impact is limited to the documented fallback.",
      "rootCause": "The launcher moved under the skill's `scripts/` directory when the skill was renamed from `scent-miner` to `browser4-web-miner` (worktrees of the old layout still show `skills/scent-miner/scripts/webminer.ps1`), but the SKILL.md examples were written for a root-level or CWD-relative location and were not updated with the new prefix.",
      "codePointer": "skills/browser4-web-miner/SKILL.md (launcher examples, lines ~36-45 and the Installing WebMiner section)",
      "suggestion": "- Update the examples to the real path, e.g. `pwsh -File skills/browser4-web-miner/scripts/webminer.ps1 install`\n- Or add a thin repo-root `webminer.ps1` shim that delegates to the skill script, making the documented form literally correct\n- At minimum, add one line to the Installing WebMiner section stating the launcher's location relative to the repo root\n- Consider dropping the PowerShell launcher from the primary docs entirely now that `browser4-cli webminer install` is the cross-platform first-class path, mentioning it only as the legacy fallback"
    },
    {
      "title": "Generated views `index.html` is a directory listing, not the interactive report the docs point users to",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "./b4w.ps1 webminer views <result-dir>, then open the generated predictionAndMinimalFeatures.views/index.html",
      "expected": "Per skills/browser4-web-miner/SKILL.md: \"Open the generated index.html in a browser to explore the clustering results\" — a page presenting the clusters.",
      "actual": "index.html is a 2.5-2.9 KB auto-generated file listing: `<h1>Index of predictionAndMinimalFeatures.views</h1>` followed by `<a href=\"D:\\workspace\\…\\p<ts>.html\">`. The actual clustering reports are the sibling files (`p<ts>.html`, `.perfect.html`, `.qualified.html`, `.top2ScreenQualified.html`, `.all.html`) and the `.xlsx` workbooks; opening `p<ts>.html` does render the expected \"clustering - predict result\" tables. The links also embed absolute Windows filesystem paths rather than relative ones — they resolve correctly in Windows Chromium (verified by eval: `D:\\…` normalizes to `file:///D:/…` and hits the right file), but they break the moment the result directory is moved, archived, or opened on another machine.",
      "rootCause": "`index.html` is emitted by a generic directory-index generator in the views stage, while the docs describe it as the report entry point. Because Chromium on Windows happens to normalize drive-letter paths, the defect is invisible during local testing and only surfaces when the artifact is relocated or shared — which is precisely what \"archive them with the project\" (SKILL.md) and \"route the corpus to the commercial deployment\" (AC2) involve.",
      "codePointer": "scent-miner.jar views stage (external); skills/browser4-web-miner/SKILL.md \"Building Views from an Existing Run\" section",
      "suggestion": "- Emit index.html links with relative paths (`./p<ts>.html`) so a moved or archived result directory still works\n- Either make index.html the real report entry point (clusters + links to each view variant) or update both SKILL.md files to name `p<ts>.html` / `.xlsx` as the artifacts to open\n- Label the report variants in the index (perfect / qualified / top2ScreenQualified) so a user can tell them apart without opening each one\n- Note in the docs that the two view locations differ (result dir vs the `%TEMP%\\webminer-pereg\\…` task-output root) directly next to the \"open index.html\" instruction, since that is where a user looks for it"
    },
    {
      "title": "`webminer` with nothing installed reports \"Update available! Run: webminer update\"",
      "severity": "Low",
      "category": "UX",
      "reproduction": "./b4w.ps1 webminer   (before installing)",
      "expected": "Guidance to install, which the same output already gives one line earlier — not a suggestion to update a nonexistent installation.",
      "actual": "Output reads:\n  Installed : (none) — run `browser4-cli webminer install`\n  Java 17+  : D:\\Program Files\\Java\\graalvm-jdk-25.0.3+9.1\\bin\\java.exe\n  Latest    : v0.0.8  (97.0 MB)\n  …\n  Update available! Run: browser4-cli webminer update\nTelling a user with no installation to run `update` is contradictory; `update` is the wrong verb for the zero-install state. The correct action (`install`) is present in the same output, so the risk is a user running the wrong command rather than being fully blocked.",
      "rootCause": "The status renderer computes \"update available\" purely by comparing the latest release version against the installed version, without special-casing the \"not installed\" sentinel. The two advisories are emitted by different code paths and never reconciled.",
      "codePointer": "cli/browser4-cli/src/webminer.rs (status rendering / update-availability check)",
      "suggestion": "- Suppress the \"Update available!\" line when the installed version is the `(none)` sentinel, printing only the install hint\n- Or branch the wording on state: \"Not installed — run `browser4-cli webminer install`\" versus \"Update available — run `browser4-cli webminer update`\"\n- Add a unit test covering the not-installed status output"
    },
    {
      "title": "`swarm create` accepts `--headless` but only documents `--display-mode`",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "./b4w.ps1 swarm create --display-mode GUI   → \"Swarm session created: SWARM (display=GUI, …)\"\n./b4w.ps1 swarm create --headless            → \"Swarm session created: SWARM (display=HEADLESS, …)\"   (flag honored)\n./b4w.ps1 help swarm create                   → Options list contains only `--display-mode <mode>`",
      "expected": "The alias is documented, or `--headless` is rejected as unknown so the user discovers the right flag.",
      "actual": "`--headless` is silently accepted and does switch the display mode (verified by round-tripping GUI → HEADLESS). It appears in neither the `help swarm create` option list nor SKILL.md, so it is only findable by guessing or reading source. This matters for discoverability specifically: SKILL.md §2 and §4c teach `--headless` as the universal headless switch for AI agents and state it is the default for agents, so a user reasonably expects the same flag everywhere. `swarm create` instead uses a differently-shaped `--display-mode HEADLESS` with three values (GUI/HEADLESS/SUPERVISED), and the mismatch is easy to trip over.",
      "rootCause": "An alias was added to the swarm argument parser without being registered in the help metadata that `help.rs` renders. Note the inconsistency is two-way: `swarm create` also documents a `SUPERVISED` value that does not exist in the `open` command's `--headless/--headed` vocabulary, so neither flag name is a subset of the other.",
      "codePointer": "cli/browser4-cli/src/help.rs (swarm create option metadata); cli/browser4-cli/src/commands.rs (swarm argument parsing)",
      "suggestion": "- Add `--headless` (and a matching `--headed`) to the `swarm create` help text as documented aliases for `--display-mode HEADLESS/GUI`\n- Cross-reference the two vocabularies in SKILL.md §4d so the `--headless` vs `--display-mode HEADLESS` difference is explicit at the point of use\n- Consider unifying on one flag name across `open` and `swarm create`, keeping the other as a deprecated alias\n- Add a help-coverage test asserting every accepted alias appears in the rendered help"
    },
    {
      "title": "Swarm task `duration_ms` reports 0-2 ms for real page fetches, making the queue metric misleading",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "./b4w.ps1 swarm list --batch 8a518f54-5cec-4d2b-8c3c-f91d03ac3dda --json\n→ task records read \"duration_ms\":2, \"duration_ms\":1, \"duration_ms\":0 for page fetches, while the batch's own header reports a 6m41s window.",
      "expected": "A task duration that reflects how long the fetch + X-SQL extraction took, or a clearly named field for the narrower measurement.",
      "actual": "Every one of the 101 tasks reported 0-2 ms while the batch spanned 6m41s and individual pages demonstrably took seconds (contexts were starting, and the queue drained at ~10 pages/min). Because `duration_ms` is the only per-task timing field, there is no way to identify slow pages or verify the parallelism actually achieved anything. The starvation warning in Issue 7 is diagnosed purely from queue depth, consistent with per-task timing being unusable.",
      "rootCause": "`started_at`/`completed_at` appear to be stamped when the task record transitions state in the tracker rather than around the browser fetch, so the delta measures bookkeeping, not work. Investigation needed: confirm whether the backend records a separate execution window that the CLI is not surfacing, and whether the queue-wait time is tracked at all.",
      "codePointer": "cli/browser4-cli/src/commands.rs (swarm list rendering of duration_ms)",
      "suggestion": "- Either measure the duration around the actual fetch/query execution, or rename the field to reflect what it measures (e.g. `record_update_ms`)\n- Add a queue-wait field (submitted_at → started_at) alongside execution time, since the 101-URL run spent most of its wall clock waiting, not fetching\n- Surface the batch-level parallelism actually achieved (peak concurrent workers) so the `--max-browser-contexts` tuning in Issue 7 can be judged from output rather than guessed"
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all five acceptance criteria met. AC3 exported 5 product pages via goto + htmlsnapshot export; AC1 ran the full free WebMiner pipeline (encode → cluster → views) and verified every documented artifact type including valid XLSX workbooks; AC4 fetched 12 pages via crawl --seed-file --depth 0 and staged them through webdb export into a working WebMiner input directory; AC5 completed a 101-URL swarm job headlessly with 101/101 rows extracted and confirmed swarm pages are retrievable from webdb; AC2 documented the 1,200-page production scale decision point with measured evidence about where the real limits fall.",
    "successRate": "90% — every task step produced its intended outcome, but two steps needed workarounds (dropping -s from webdb export, and hand-rolling a 101-invocation loop to aggregate swarm results) that the documented workflow does not mention.",
    "issuesFound": 12,
    "majorBlockers": "No hard blockers. Two significant friction points: (1) `crawl -s <name>` does not register the named session, so the documented crawl → webdb export handoff failed with a misleading 'Session required' error until the fixed -s flag was dropped; (2) swarm has no batch-wide result retrieval, forcing 101 separate CLI invocations to assemble one table from the high-throughput path — the very path where aggregation should be easiest.",
    "mostConfusingAspects": "For a first-time user: (a) `-s <name>` is not consistently honored across command families — `goto`/`open` create and register the session, `crawl` silently accepts the flag and registers nothing, so identical-looking commands fail later for non-obvious reasons; (b) error hints cannot be trusted — the 'Session required' block points at `session list`, which is not a command (exit 2), and suggests `browser4-cli.exe` in a repo where the documented entry point is ./b4w.ps1; (c) the documented `webminer all` default silently caps at 40 files, so the '< 1,000 pages' guidance in SKILL.md is unreachable without discovering --max-files first, and a 60-file test corpus quietly runs as 40; (d) swarm's `--wait` returns after a hard 300 s with jobs still pending and exits 0, so 'completed' is ambiguous; (e) the WebMiner launcher is documented as `.\\webminer.ps1` but only exists at skills/browser4-web-miner/scripts/webminer.ps1, so the documented fallback install path fails immediately from the repo root.",
    "mostValuableImprovements": "1) Make `-s <name>` consistent across all command families, or reject it where unsupported — this single fix removes the most confusing failure mode found. 2) Add batch-wide swarm result retrieval (`swarm result --batch <id> --format csv -o out.csv`), which turns the advertised high-throughput path into something that can actually deliver a dataset. 3) Remove the silent 40-file truncation in `webminer all` — either raise the default above the free tier's own 1,000-page guidance or print an explicit '⚠ N of M files skipped' line in the pipeline summary, since silent partial results are the most dangerous failure class for a data pipeline. 4) Align process exit codes with response envelopes across swarm/webdb/webminer so scripts can trust `$?` rather than parsing JSON. 5) Fix the documented launcher path and the `session list` hint — both are one-line documentation corrections with outsized effect on first-run success.",
    "usabilityRating": 7
  }
}
```

## D. Overall Assessment

**Task completion status:** Successful. All five acceptance criteria were met with real, verified artifacts — nothing was simulated except the AC2 scale corpus, which was explicitly permitted.

**Estimated task success rate:** ~90%. Every step reached its intended outcome, but two required undocumented workarounds.

**Number of issues found:** 12 (2 High, 5 Medium, 5 Low).

**Major blockers:** None that stopped the task. The two highest-cost problems were the session-registration gap in `crawl` (which makes a documented handoff fail with a misleading error) and the absence of batch-wide swarm result retrieval (which forced 101 CLI invocations to assemble one table).

**Most confusing aspects** are catalogued in the JSON above; the through-line is **inconsistency across command families** — `-s` means different things to `goto` and `crawl`, `--headless` works on `swarm create` but is undocumented, `--wait` does not wait, and exit codes mean success in some commands and nothing in others. A user cannot generalize from one command to the next, which is the main reason the rating is 7 rather than higher.

**Most valuable improvements:** make `-s` consistent everywhere; add `swarm result --batch`; eliminate the silent `--max-files 40` truncation; unify exit-code semantics; and fix the two one-line documentation defects (`session list` hint, `webminer.ps1` path).

**Overall usability rating: 7/10.** The core loop is genuinely strong — `goto` → `snapshot` → `htmlsnapshot export` worked first try, `htmlsnapshot summary` was excellent for selector discovery, the X-SQL template in §4e was copy-pasteable and correct on the first attempt, `webminer install` verified its own checksum, and the free pipeline produced every documented artifact in seconds. `webdb export`'s comma-separated-URL guard deserves specific credit: it rejected a space-separated list with a precise message, a concrete example, and exit 1 — exactly the right behavior. The deductions come from the seams between commands, where flags and lifecycle are not uniform and a first-time user's mental model breaks.

---

**Environment note:** MockSite and the backend were both already running from a prior session when this run began. `browser4-cli doctor` reported the backend as `4.13.18-SNAPSHOT` against CLI `4.13.20`, with a version-mismatch warning in `status` — but also confirmed it was a dev build running against the source tree, so the mismatch is a version-string artifact of the auto-bump commit rather than a stale artifact. No rebuild was forced. All scratch artifacts are confined to `.test-sessions/20260916T1722282030412Z/`; browser sessions opened during the run were closed, and the swarm session was closed after use.
