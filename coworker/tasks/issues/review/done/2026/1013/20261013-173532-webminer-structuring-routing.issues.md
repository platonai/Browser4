# Issues: webminer-structuring-routing

> **Source:** `20261013-173532-webminer-structuring-routing.issues.json` | **Date:** 20261013-173532 | **Mode:** dev

## Scenario Background

### Task

## AC3 — Acquire pages from single-page browsing ✅

Created `.test-sessions/20261013T0154334271610Z/corpus-single/` and exported three MockSite product detail pages:

| Page | Title captured | File |
|---|---|---|
| `/ec/dp/B0E000001` | Product: 4K OLED TV 55 | `B0E000001.html` (15,757 B) |
| `/ec/dp/B0E000002` | Product: Wireless Noise-Cancelling Headphones | `B0E000002.html` (15,768 B) |
| `/ec/dp/B0E000003` | Product: Portable Bluetooth Speaker | `B0E000003.html` (14,713 B) |

Workflow: `-s wm-eval goto <url>` → `htmlsnapshot export --file <path>` per page. The docs' `goto` + `htmlsnapshot export` pairing worked exactly as written (each export captures the fresh tab snapshot itself — no separate capture step needed).

## AC1 — Small/medium corpus WebMiner pipeline ✅

`./b4w.ps1 webminer all <corpus-single>` completed succeed (exit 0): encoded 3 documents (69-dim feature vectors, 205 DOM nodes) → SMILE KMeans auto-k (k=72, silhouette 0.624) → views (interactive HTML report `p20261014012158.html`, `.all.html`, `.xlsx`, `.json`). Re-verified at 8 pages (k=66, silhouette 0.426) and on a simulated 50-file corpus. Also verified the pipeline runs **with the Browser4 backend stopped** — the "no Browser4 server needed" claim holds.

One important wrinkle: **all outputs are not in one place** — clustering results land in `--output` (`<html-dir>-ml-output/kmeans-result/<project>/`), while the encoded dataset CSV and the interactive report/xlsx land in `%LOCALAPPDATA%\Temp\webminer-pereg\...`. The report can be rebuilt into the result dir with `webminer views <result-dir>` (verified working). See Issue 2 and Issue 3.

## AC4 — Bulk acquisition from known URLs ✅

Built `seed-urls.txt` (8 product URLs, discovered via `http://localhost:18080/ec/sitemap.xml` as documented) and ran `crawl --seed-file seed-urls.txt --depth 0 --refresh`: **8/8 pages found, exit 0, 16.1 s wall, peak 4 units in flight**, no link discovery — exactly the right path for known URLs. The fetched HTML was staged out of the page store with `webdb export "<urls>" corpus-bulk/` (8/8 files, `localhost_18080_ec_dp_*.htm`) as the crawl reference recommends for WebMiner input.

## AC5 — High-throughput acquisition with swarm ✅

`swarm create --display-mode HEADLESS` (contexts boot ~30–60 s, CLI warns about this) → `swarm query --sql @swarm-query.sql --seed-file seed-swarm.txt --refresh --wait` with a 12-URL seed file → **12/12 jobs completed in 98 s (batch window 1 m 19 s)**; `swarm result <id>` returned `{url, title, price}` rows, `swarm list --batch <id>` showed the full batch; `swarm close` released the session. Staged the 12 swarm-fetched pages to `corpus-swarm/` via `webdb export` (12/12). Recorded: swarm is the preferred acquisition path when throughput matters (parallel browser contexts, thousands of URLs); for a *small* local batch, plain `crawl` was actually faster (8 pages/16 s vs 12 pages/98 s including swarm cold start) — except for the bad DURATION metrics (Issue 5).

## AC2 — Production-scale decision point ✅ (documented, not executed)

- MockSite's whole catalog is 122 pages (101 products + 20 categories + home). A **1,200-page daily product export ≈ 10× the entire catalog**.
- SKILL.md §4d and `references/decision-trees.md` both draw the line at **< 1,000 pages → `webminer all` (free, local SMILE, zero tokens); > 1,000 pages → WebMiner Commercial (Apache Spark ML, same encode → cluster → views pipeline distributed, 100K+ pages/day)**. The sibling `skills/browser4-web-miner/SKILL.md` reinforces: the free tier's stated limit is < 1,000 pages and it can crash on large corpora.
- **Decision:** a 1,200-page/day export exceeds the free-tier guidance → route the same acquired HTML corpus to the commercial Spark deployment; acquisition patterns stay identical (steps 3–4: `crawl --seed-file` / `swarm query`), only the structuring backend changes.
- Two operational caveats surfaced by this run: the free tier's practical entry point caps at **40 files by default** (Issue 3), and the free-tier artifact layout is split between the output dir and `%TEMP%` (Issue 2).

### Execution Context

**Key Commands:**

1. `help` (×4 command pages: `webminer`, `webminer install|all`, `htmlsnapshot`, `webdb`, `stop`), `webminer` (status), `webminer install` (already-installed, exit 0)
2. `-s wm-eval goto http://localhost:18080/ec/dp/{B0E000001,B0E000002,B0E000003}` + `-s wm-eval htmlsnapshot export --file …` → `corpus-single/`
3. `webminer all corpus-single` → encode/cluster/views; `webminer views <kmeans-result>` → views rebuilt into the output dir
4. `crawl --seed-file seed-urls.txt --depth 0 --refresh` (8 URLs, 16 s) → `-s wm-eval webdb export "<8 urls>" corpus-bulk`
5. `swarm create --display-mode HEADLESS` → `swarm query --sql @swarm-query.sql --seed-file seed-swarm.txt --refresh --wait` → `swarm result/status/list --batch/close`
6. Scale simulation: 50-file corpus → `webminer all` (default, truncated to 40) and `webminer all --max-files 50 --output …` (all 50)
7. Offline check: `stop` → `webminer all` (works) → backend auto-restart via `webdb export` / `-s persist-test goto`
8. State forensics: `list`, `list --all --verbose`, `-s wm-eval page-info`, inspection of `~/.browser4/**/cli-state.json`, `sessions/*.json`, `session-registry.json`; source inspection in `cli/browser4-cli/src/{main,state,webminer,commands}.rs` and `browser4-plugins/browser4-swarm/…`

**Key decisions:** chose the free WebMiner branch for all executed pipelines (< 1,000 pages); used `webdb export` as the documented staging path from the Browser4 page store to WebMiner input dirs; discovered product URLs from `/ec/sitemap.xml` instead of guessing IDs (docs cover this well); used a distinct named session (`-s wm-eval`) to avoid the shared default session.

**Workarounds required:** (a) `webdb export` failed without a session → retried with `-s wm-eval`; (b) WebMiner report landed in `%TEMP%` → rebuilt into the output dir with `webminer views`; (c) B0E000001 etc. IDs had to come from the sitemap (bare `/ec/dp/` 404s, as the crawl reference documents).

**Side effects/state:** backend left running; one `persist-test` session record exists; all run artifacts are inside `.test-sessions/20261013T0154334271610Z/` (note: that directory already contained files from an earlier, unrelated run — the ones I created this run are `corpus-*`, `*-ml-output*`, `seed-*.txt`, `swarm-query.sql`, `sitemap.xml`, `webdb-nosession.out`).

---

## Issues Found (6 issues)

### Issue 1: webminer all silently caps the corpus at 40 files, contradicting the documented <1,000-page free tier

**Severity:** High
**Category:** Documentation

#### Reproduction

1) Build a corpus of 50 (or any >40) HTML files.
2) ./b4w.ps1 webminer all <corpus-dir>
Observed: 'Found 50 HTML file(s), will encode up to 40 valid document(s)' ... 'Encoded 40 document(s)' ... 'Scanned 40 of 50 HTML file(s)' ... 'PIPELINE COMPLETE'.
3) ./b4w.ps1 webminer all <corpus-dir> --max-files 50 → 'Encoded 50 document(s)', 'Scanned 50 of 50'.
SKILL.md §4d and references/decision-trees.md say: '< 1,000 pages → webminer all' with no mention of the 40-file default.

#### Expected Behavior

Following the decision tree for any corpus between 41 and 999 pages should process the whole corpus (the free-tier guidance range), or the guidance should state that --max-files must be raised because the default is 40.

#### Actual Behavior

Only the first 40 files are encoded/clustered; the pipeline still ends with PIPELINE COMPLETE. A user following SKILL.md §4d for, e.g., a 900-page corpus gets results from 4% of it. The truncation appears only as one mid-log line ('Scanned 40 of 50'), and the final summary does not repeat it.

#### Root Cause Analysis

The scent-miner.jar 'all' subcommand defaults --max-files to 40 and the CLI forwards it verbatim (cli/browser4-cli/src/webminer.rs run_pipeline passes args through; the default is documented in the CLI's own 'webminer all' help but nowhere in the SKILL.md decision layer that the workflow is driven from). The conflict is between the CLI/jar default (40) and the skill's stated free-tier capacity (<1,000).

#### Code Pointer

`skills/browser4-cli/SKILL.md (§4d, line ~277) and skills/browser4-cli/references/decision-trees.md (WebMiner tiers); CLI side: cli/browser4-cli/src/webminer.rs:run_pipeline`

#### AI Suggested Improvement

- State the 40-file default explicitly in SKILL.md §4d and decision-trees.md: 'webminer all <dir> --max-files <n> (default 40 — raise it for larger corpora)'.
- Have the CLI print a prominent warning (or require confirmation) when the number of discovered files exceeds --max-files, and repeat the truncation in the final PIPELINE COMPLETE summary.
- Consider raising the jar/CLI default (or defaulting to 'all files found') since the documented free-tier range is <1,000 pages.
- Add the truncation count to scripts/exit-code semantics so automation can detect a partial run.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: WebMiner writes the interactive report and encoded dataset to the OS temp dir, not the --output directory

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 webminer all .test-sessions/20261013T0154334271610Z/corpus-single
Then inspect: (a) <corpus-single>-ml-output/kmeans-result/<project>/ contains only clusteringInfo.txt and predictionAnd*/result.csv; (b) %LOCALAPPDATA%\Temp\webminer-pereg\ml\dataset\predict\<project>\dataset-<project>.csv holds the encoded data; (c) %LOCALAPPDATA%\Temp\webminer-pereg\ml\tasks\unsupervised\result\<project>\predictionAndMinimalFeatures.views\ holds index.html, <project>.html/.all.html, *.xlsx, *.json.

#### Expected Behavior

AC1's 'expected local outputs — encoded data, clustered results, and generated views/report artifacts' collected under the run's output directory (--output, default <html-dir>-ml-output), or an explicit statement that only clustering results go to --output while data/views live in %TEMP% and can be rebuilt with `webminer views`.

#### Actual Behavior

The headline deliverables (interactive HTML report, Excel spreadsheets) and the encoded CSV live in a per-user temp directory subject to OS cleanup, outside both the corpus tree and --output. The end-of-run summary does print those paths, and `webminer views <result-dir>` can rebuild the views into the result dir (verified) — but the split is surprising and undocumented in SKILL.md, which says 'everything local, zero cost'.

#### Root Cause Analysis

The scent-miner pipeline writes its dataset/task workspace under %TEMP%/<app>-pereg (the CLI sets -Dapp.name so the views root is %TEMP%/webminer-pereg — see comment at cli/browser4-cli/src/webminer.rs:134), and only the kmeans-result directory is mapped to --output. The views step writes next to its task workspace rather than to --output.

#### Code Pointer

`cli/browser4-cli/src/webminer.rs:run_pipeline (arg forwarding) and the scent-miner 'all' pipeline path mapping (external JAR); docs: skills/browser4-cli/SKILL.md §4d`

#### AI Suggested Improvement

- After a successful `webminer all`, run the views step against the result dir (equivalent to `webminer views <output>/kmeans-result/<project>`) so report artifacts land under --output, or copy/symlink the views + dataset CSV there.
- Document in SKILL.md §4d and `webminer all` help that views/dataset are written to %TEMP%/webminer-pereg and how to relocate them (`webminer views <result-dir>`).
- Print the output-dir location of the rebuilt views, not only the temp path, in the final summary.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: webdb export requires an active CLI session; sessionless invocation boots the backend then fails

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 webdb export "http://localhost:18080/ec/dp/B0E000001" <out-dir>   (with no stored session in this checkout)
Observed: backend auto-starts (~11 s), then '🔐 Session required ... No active session is currently stored for this CLI context.' with exit code 1. Re-running the identical command with `-s wm-eval` succeeds (8/8 and 12/12 exports in this run).

#### Expected Behavior

Either webdb export works without a session (it reads the server-side page store: pages fetched by a sessionless `crawl` were exportable under any session), or the session precondition is checked before spending ~11 s starting a JVM, and the error names the sessions that exist and suggests `-s <name>`.

#### Actual Behavior

The command starts the whole backend first and only then fails its session check. The hint text suggests `open <url>` / `list` but never `-s <name>`, even when named sessions exist. The hint also prints the internal binary name 'browser4-cli.exe' (cli_binary_name() uses argv[0]) rather than the documented `browser4-cli`.

#### Root Cause Analysis

The generic session-required preflight used by tool-backed commands (main.rs no_active_session_message, reached via the webdb-export command spec in commands.rs) runs after the ensure-server step, and webdb-export is registered as session-bound even though its data source is the server-wide page store. The hint templates hardcode `open <url>` and use argv[0] for the program name.

#### Code Pointer

`cli/browser4-cli/src/main.rs:no_active_session_message (line ~658) and the backend-ensure ordering in the command dispatch; command spec cli/browser4-cli/src/commands.rs:2869 (webdb-export)`

#### AI Suggested Improvement

- Drop the session requirement for webdb export (it is a page-store read), or move the precondition check before backend startup.
- When sessions exist, name them in the error and suggest `-s <name> <command>`; the registry already has the name→id map.
- Use the documented program name (`browser4-cli`) in guidance text instead of argv[0], so hints are correct when invoked through wrappers.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: stop silently deletes persisted session records; sessions vanish from list instead of showing as Stale/Refresh

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 -s persist-test goto http://localhost:18080/ec/dp/B0E000002 → ~/.browser4/workspaces/Browser4-4.14-04e5248d/sessions/persist-test.json is created; `list` shows it (Active, Next open=Reuse).
2) ./b4w.ps1 stop  ('Gracefully stop the Browser4 server')
3) sessions/persist-test.json is now deleted; ./b4w.ps1 list prints 'No active browser sessions.'; ./b4w.ps1 -s persist-test page-info fails with 'No active session is currently stored for this CLI context.'
4) ./b4w.ps1 -s persist-test goto <url> recreates the record with the SAME session id (the global ~/.browser4/session-registry.json name→uuid mapping survives).

#### Expected Behavior

SKILL.md §Sessions: 'A locally-saved session the backend no longer recognizes is listed as Stale / Refresh (the same row close-all counts), rather than being hidden.' After a routine `stop`, `list` (and `list --all`) should still surface the named session (URL '-', Next open=Refresh), and CLI commands should be able to reconnect by name with a clear hint.

#### Actual Behavior

`stop` wipes sessions/*.json, so sessions are hidden entirely; no Stale/Refresh row appears. Users may conclude their named sessions and stored profile state were destroyed. The names survive only in the global registry, and only `goto`/`open` by name revive them — the CLI never explains this.

#### Root Cause Analysis

handle_stop (main.rs:22754) calls finalize_global_cleanup (main.rs:22772 → 4706) which calls clear_all_state(None) → clear_session_files_in_dir (state.rs:~603) removes every sessions/*.json. `stop` is documented only as 'Gracefully stop the Browser4 server' — record deletion is undocumented and defeats the documented stale-session discovery flow.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_stop (22754) / finalize_global_cleanup (4706); cli/browser4-cli/src/state.rs:clear_all_state / clear_session_files_in_dir (~603)`

#### AI Suggested Improvement

- On `stop`, keep session records (mark them stale/unknown) instead of deleting them, so `list` shows the documented Stale/Refresh rows and `close-all` counting still works; reserve full deletion for `close`/`close-all`/`kill-all`.
- If deletion on stop is intentional, document it in `help stop` and in the Sessions section, and make the next `list` say sessions were cleared by `stop` rather than showing an empty table.
- Have session-scoped commands that cannot reconnect (e.g. page-info) suggest `goto -s <name> <url>` when a registry entry exists.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: swarm list / swarm status DURATION metrics are meaningless (single-digit ms) and 'slowest task' is misleading

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 swarm query --sql @swarm-query.sql --seed-file seed-swarm.txt --refresh --wait   (12 MockSite URLs; all completed in 98s, batch window 1m19s)
Then: ./b4w.ps1 swarm list --batch <batch-id>  → every row DURATION 3–11ms, STARTED == FINISHED to the second; ./b4w.ps1 swarm status <batch-id> → 'slowest task: 97d6da28 (11ms)'.
JSON confirms: e.g. task submitted 17:23:33.846, started_at 17:23:53.374, completed_at 17:23:53.380, duration_ms=6.

#### Expected Behavior

Docs (references/swarm.md §4b) promise 'swarm list answers how long did each task take? — DURATION (started → finished)' with values like '27.4s'. Each task here demonstrably occupied a worker for several seconds (12 tasks spread over 79s, each fetching a page and running X-SQL).

#### Actual Behavior

Per-task durations of 3–11ms; started_at lands at/near the completion timestamp, so started→finished collapses to the last update gap. Batch-level 'slowest task' therefore identifies nothing. Any throughput tuning based on these numbers (e.g. 'which pages are slow?') is impossible.

#### Root Cause Analysis

startedTime is set by the first ScrapeResponse.refresh(...) call ('Record the first time a worker touches this task', browser4-agent-tools/.../crawl/Models.kt:119-127). For swarm-query tasks the first refresh apparently happens at the terminal update, so startedTime and finishTime are stamped together. The batch summary (browser4-plugins/browser4-swarm/.../SwarmService.kt:381-384) computes durations from those timestamps. Needs investigation on the worker path that should stamp startedTime at pickup.

#### Code Pointer

`browser4-plugins/browser4-swarm/src/main/kotlin/ai/platon/pulsar/swarm/service/SwarmService.kt:381 (batch status) / :282-300 (staleness); browser4-agent-tools/src/main/kotlin/ai/platon/pulsar/agentic/tools/advanced/crawl/Models.kt:119 (ScrapeResponse.refresh)`

#### AI Suggested Improvement

- Stamp startedTime when the worker actually picks the task up (before load/extract), not on the first terminal refresh.
- If that cannot be done reliably, stop advertising DURATION/slowest-task in the CLI output and docs rather than showing ~0 values.
- Add a regression check: a task whose started/completed timestamps differ by <100ms while its batch window spans seconds should be flagged, or the batch-status duration should fall back to submitted→finished with an explicit label.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: swarm create reports 'contexts=default, max-tabs=default' without resolving the effective values

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 swarm create --display-mode HEADLESS
Output: 'Swarm session created: SWARM (display=HEADLESS, contexts=default, max-tabs=default)'

#### Expected Behavior

Print the effective values (docs say defaults are 2 browser contexts / 8 tabs per context), so a first-time user can reason about expected parallelism without reading references/swarm.md.

#### Actual Behavior

The literal string 'default' is printed; the number of browser contexts actually launched (the throughput knob) is not visible anywhere in the create output.

#### Root Cause Analysis

The create confirmation formats the raw option values, echoing 'default' when the flag is omitted instead of substituting the server-side defaults (contexts=2, max-tabs=8).

#### Code Pointer

`cli/browser4-cli/src/main.rs (swarm create handler; the confirmation line with contexts=/max-tabs=)`

#### AI Suggested Improvement

- Resolve omitted options to their effective defaults before printing ('contexts=2, max-tabs=8').
- Optionally say how to change them in the same line (e.g. '--max-browser-contexts <n>').

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful — all five acceptance criteria were exercised end-to-end on real MockSite pages with the free WebMiner pipeline: single-page export corpus (3 pages), free pipeline runs (3/8/50-file corpora), crawl seed-file bulk acquisition (8/8), swarm query batch (12/12), and the production-scale decision point documented against the 1,000-page tier boundary. The only intentional non-execution was the proprietary Spark pipeline (per the scenario).

**Success Rate:** 95% — every command in the scenario's happy path worked; letdowns were the 40-file default cap discovered on the simulated larger corpus, the webdb export session gate, the temp-dir artifact split, and the misleading swarm duration metrics.

**Issues Found:** 6

**Major Blockers:** None blocking; every step had a working path. The strongest friction: (1) `webdb export` refuses to run without a stored session and spends ~11s starting the backend before saying so; (2) `webminer all`'s 40-file default silently contradicts the documented <1,000-page free tier.

**Most Confusing Aspects:** For a first-time user: (a) where WebMiner outputs actually live — clustering results in --output, but the interactive report/xlsx and encoded CSV in %TEMP%, so a naive 'check the output folder' verification looks incomplete; (b) why `webminer all` on a >40-file corpus reports success while processing only part of it; (c) why `list` shows nothing after `stop` when the docs promise Stale/Refresh rows — it is hard to tell whether sessions/profiles were lost (they were not); (d) swarm DURATION values of '6ms' for tasks that clearly took seconds, undermining the batch-monitoring story.

**Most Valuable Improvements:** 1) Make `webminer all` land all three output classes (dataset CSV, clustering, views/report) under --output, or document the %TEMP% split plus the `webminer views` relocation step in SKILL.md §4d. 2) Mention the --max-files 40 default wherever the <1,000-page free-tier decision is stated. 3) Drop the session requirement for `webdb export` (or check it before booting the backend) and name existing sessions in the error. 4) Keep session records across `stop` so `list` shows the documented Stale/Refresh rows. 5) Fix the swarm per-task started/completed timestamps so duration reporting is truthful.

**Usability Rating:** 7/10

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` (PowerShell) or `./b4w.sh` (Bash / Git Bash), which auto-build from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root:

   - **PowerShell:** `./b4w.ps1 <command>`
   - **Bash / Git Bash:** `./b4w.sh <command>`
   - **Direct:** `browser4-cli <command>` (if installed globally)

   > **Note:** `$(./b4w.ps1)` is command substitution in bash — do NOT use it.

### Per-Issue Reproduction Steps

#### Issue 1: webminer all silently caps the corpus at 40 files, contradicting the documented <1,000-page free tier

1) Build a corpus of 50 (or any >40) HTML files.
2) ./b4w.ps1 webminer all <corpus-dir>
Observed: 'Found 50 HTML file(s), will encode up to 40 valid document(s)' ... 'Encoded 40 document(s)' ... 'Scanned 40 of 50 HTML file(s)' ... 'PIPELINE COMPLETE'.
3) ./b4w.ps1 webminer all <corpus-dir> --max-files 50 → 'Encoded 50 document(s)', 'Scanned 50 of 50'.
SKILL.md §4d and references/decision-trees.md say: '< 1,000 pages → webminer all' with no mention of the 40-file default.

#### Issue 2: WebMiner writes the interactive report and encoded dataset to the OS temp dir, not the --output directory

./b4w.ps1 webminer all .test-sessions/20261013T0154334271610Z/corpus-single
Then inspect: (a) <corpus-single>-ml-output/kmeans-result/<project>/ contains only clusteringInfo.txt and predictionAnd*/result.csv; (b) %LOCALAPPDATA%\Temp\webminer-pereg\ml\dataset\predict\<project>\dataset-<project>.csv holds the encoded data; (c) %LOCALAPPDATA%\Temp\webminer-pereg\ml\tasks\unsupervised\result\<project>\predictionAndMinimalFeatures.views\ holds index.html, <project>.html/.all.html, *.xlsx, *.json.

#### Issue 3: webdb export requires an active CLI session; sessionless invocation boots the backend then fails

./b4w.ps1 webdb export "http://localhost:18080/ec/dp/B0E000001" <out-dir>   (with no stored session in this checkout)
Observed: backend auto-starts (~11 s), then '🔐 Session required ... No active session is currently stored for this CLI context.' with exit code 1. Re-running the identical command with `-s wm-eval` succeeds (8/8 and 12/12 exports in this run).

#### Issue 4: stop silently deletes persisted session records; sessions vanish from list instead of showing as Stale/Refresh

1) ./b4w.ps1 -s persist-test goto http://localhost:18080/ec/dp/B0E000002 → ~/.browser4/workspaces/Browser4-4.14-04e5248d/sessions/persist-test.json is created; `list` shows it (Active, Next open=Reuse).
2) ./b4w.ps1 stop  ('Gracefully stop the Browser4 server')
3) sessions/persist-test.json is now deleted; ./b4w.ps1 list prints 'No active browser sessions.'; ./b4w.ps1 -s persist-test page-info fails with 'No active session is currently stored for this CLI context.'
4) ./b4w.ps1 -s persist-test goto <url> recreates the record with the SAME session id (the global ~/.browser4/session-registry.json name→uuid mapping survives).

#### Issue 5: swarm list / swarm status DURATION metrics are meaningless (single-digit ms) and 'slowest task' is misleading

./b4w.ps1 swarm query --sql @swarm-query.sql --seed-file seed-swarm.txt --refresh --wait   (12 MockSite URLs; all completed in 98s, batch window 1m19s)
Then: ./b4w.ps1 swarm list --batch <batch-id>  → every row DURATION 3–11ms, STARTED == FINISHED to the second; ./b4w.ps1 swarm status <batch-id> → 'slowest task: 97d6da28 (11ms)'.
JSON confirms: e.g. task submitted 17:23:33.846, started_at 17:23:53.374, completed_at 17:23:53.380, duration_ms=6.

#### Issue 6: swarm create reports 'contexts=default, max-tabs=default' without resolving the effective values

./b4w.ps1 swarm create --display-mode HEADLESS
Output: 'Swarm session created: SWARM (display=HEADLESS, contexts=default, max-tabs=default)'

