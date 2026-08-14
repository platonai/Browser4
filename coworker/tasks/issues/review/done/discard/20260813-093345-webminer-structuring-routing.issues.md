# Issues: webminer-structuring-routing

> **Source:** `20260813-093345-webminer-structuring-routing.full.md` | **Date:** 20260813-093345 | **Mode:** production

## Scenario Background

### Task

All five acceptance criteria were completed against a running MockSite (`http://localhost:18080`) and the installed WebMiner free tier (`scent-miner.jar` v0.0.7):

- **AC1 — Small/medium corpus (<1,000 pages):** Built a 3-page HTML corpus from MockSite product detail pages and ran the free WebMiner pipeline. The `encode → cluster → views` pipeline ran to completion and produced encoded CSVs (`predictionAndFinalFeatures`/`MinimalFeatures`/`OriginalFeatures`), clustering results (`clusteringInfo.txt`, `kmeans-result/p<ts>/`), and views/report artifacts (`index.html`, `.xlsx`, `.json`). *(Caveat: the views artifacts are written to a temp directory, not the documented output tree — see Issue 1.)*
- **AC2 — Production scale (>1,000 pages):** Documented that a ~1,200-page/day corpus exceeds the free-tier (<1,000 pages) guidance and maps to the Apache Spark commercial tier (see `.test-sessions/ac2-production-scale-decision.md`).
- **AC3 — Single-page acquisition:** Exported 3 product pages via `goto` + `htmlsnapshot export` into `.test-sessions/html-corpus/`.
- **AC4 — Bulk known-URL acquisition:** `crawl --seed-file .test-sessions/ac4-seeds.txt --depth 0 --refresh` fetched 6 pages; `webdb export` staged the fetched HTML into a WebMiner input directory.
- **AC5 — High-throughput acquisition:** `swarm create --display-mode HEADLESS` + `swarm query --seed-file ... --sql @... --refresh --wait` submitted 6 jobs, all completed, and structured extraction (title/price/url) was verified via `swarm result`.

### Execution Context

**Key Commands:**

1. `browser4-cli help` — read full command reference.
2. Attempted `https://browser4.io/SKILL.md` (3 methods: Invoke-WebRequest, curl, TLS1.2) — all failed with SSL/schannel `SEC_E_NO_CREDENTIALS`. Fell back to the identical local `skills/browser4-cli/SKILL.md` (read §4a–4e) and `skills/scent-miner/SKILL.md` (read fully).
3. `browser4-cli help htmlsnapshot`, `htmlsnapshot export --help`, `crawl --help`, `swarm --help`, `swarm create --help`, `swarm query --help` — learned exact flags.
4. Discovered MockSite product route is `/ec/dp/{productId}` (dynamic controller `EcommerceController.kt`), not the bare `/ec/dp/` (which 404s). Product IDs sourced from `products.json`.
5. AC3: `goto http://localhost:18080/ec/dp/B0E000001|002|003` → `htmlsnapshot` → `htmlsnapshot export --file .test-sessions/html-corpus/<id>.html` (3 files).
6. AC1: `pwsh -File skills/scent-miner/scripts/webminer.ps1 all .test-sessions/html-corpus` (launcher), then `java -jar .../scent-miner.jar all ...` (direct) and `... views <result-dir>` (manual) to investigate output locations.
7. AC4: wrote `.test-sessions/ac4-seeds.txt` (6 URLs) → `browser4-cli crawl --seed-file ... --depth 0 --refresh` → `browser4-cli webdb export "<3 urls>" .test-sessions/webminer-input`.
8. AC5: `browser4-cli swarm create --display-mode HEADLESS` → `browser4-cli swarm query --seed-file .test-sessions/ac4-seeds.txt --sql "@.test-sessions/ac5-query.sql" --refresh --wait` → `swarm result <id>` (verified `resultSet`).
9. AC2: wrote `.test-sessions/ac2-production-scale-decision.md`.

**Key decisions/workarounds:**
- Quoted `--sql "@path"` because unquoted `@.test-sessions/...` triggered a PowerShell parser error.
- Ran WebMiner via the direct `java -jar` path (and an explicit `views <dir>` step) because the launcher's output and the documented views location were both misleading.
- Used the actual product route `/ec/dp/{id}` after the bare `/ec/dp/` path 404'd.
- Left the SWARM session open (no requirement to close); noted stale-task warnings.

# C & D. Issues and Assessment

```json
{
  "issues": [
    {
      "title": "WebMiner 'all' writes views output (index.html/.xlsx) to a temp directory, not the documented <html-dir>-ml-output tree",
      "severity": "High",
      "category": "Documentation",
      "reproduction": "Run `java -jar scent-miner.jar all .test-sessions/html-corpus` (or `./webminer.ps1 all .test-sessions/html-corpus`), then look for the interactive report at `.test-sessions/html-corpus-ml-output/kmeans-result/p<ts>/predictionAndMinimalFeatures.views/` as documented in skills/scent-miner/SKILL.md.",
      "expected": "The views (index.html, *.xlsx, *.json) should appear at `<html-dir>-ml-output/kmeans-result/p<ts>/predictionAndMinimalFeatures.views/`, alongside the encode/cluster CSVs, per the documented output tree.",
      "actual": "Only encode/cluster outputs land in `<html-dir>-ml-output/kmeans-result/p<ts>/` (result.csv + clusteringInfo.txt). The views are written to a temp-based app data dir: `$TEMP/<app>-<user>/ml/tasks/unsupervised/result/p<ts>/predictionAndMinimalFeatures.views/` (e.g. `...\\Temp\\dsh-...\\webminer-pereg\\...` when launched via the launcher's `-Dapp.name=webminer`, or `pulsar-pereg` when run directly). A user following the docs finds no index.html where documented, and the report lives in a temp dir that the OS may clean up.",
      "rootCause": "The views stage resolves its output to the Pulsar/WebMiner 'unsupervised task result' directory (app-scoped, defaults under TEMP), which is decoupled from the `--output`-derived `<html-dir>-ml-output` directory used by encode/cluster. The explicit `views <result-dir>` subcommand writes correctly to the passed directory, but `all` does not pass the `<html-dir>-ml-output/kmeans-result/p<ts>` path to the views stage. The launcher's `-Dapp.name=webminer` additionally changes the app-scoped dir name (webminer-pereg vs pulsar-pereg).",
      "codePointer": "skills/scent-miner/SKILL.md (lines 75-91 output tree) and the WebMiner `all`/`views` stage output resolution in the external platonai/web-miner project; launcher flag at skills/scent-miner/scripts/webminer.ps1:283-287.",
      "suggestion": "- Make `all` pass the `<html-dir>-ml-output/kmeans-result/p<ts>` result dir to the views stage so all outputs land in one documented tree.\n- Update skills/scent-miner/SKILL.md to document the actual views location, or the explicit `views <result-dir>` follow-up step.\n- Print the absolute views output path at the end of the `all` run so users can find index.html."
    },
    {
      "title": "WebMiner launcher suppresses Java stdout — no progress feedback during pipeline",
      "severity": "Medium",
      "category": "UX",
      "reproduction": "Run `pwsh -File skills/scent-miner/scripts/webminer.ps1 all .test-sessions/html-corpus`.",
      "expected": "The same stage-by-stage progress (encode → cluster → views, per-file counts, silhouette score) that `java -jar scent-miner.jar all <dir>` prints.",
      "actual": "Output is only `[WebMiner] Launching ...` plus a JVM warning; none of the pipeline progress appears, even with `*> log.txt` capture. The pipeline does run (artifacts are produced), but the user gets no visible progress or confirmation.",
      "rootCause": "The launcher invokes java via `& $javaExe @javaArgs` after setting `[Console]::OutputEncoding = UTF8` (webminer.ps1:289-300). In this nested `pwsh -File` → native-java context the Java stdout is not forwarded (stderr warning does surface). Exact mechanism (OutputEncoding manipulation vs nested-pipe capture) needs investigation; may be PowerShell-version/harness-specific.",
      "codePointer": "skills/scent-miner/scripts/webminer.ps1:Invoke-WebMiner (lines 275-302).",
      "suggestion": "- Avoid mutating `[Console]::OutputEncoding` around the native call, or restore before/after more defensively.\n- Prefer `Start-Process -NoNewWindow -Wait -PassThru` with explicit stdout/stderr passthrough, or stream `$javaExe` output to the host explicitly.\n- At minimum, print a final `[WebMiner] done (project p<ts>)` line so the launcher always gives visible completion feedback."
    },
    {
      "title": "swarm list omits tasks submitted via swarm query (stale list / state mismatch)",
      "severity": "Medium",
      "category": "Reliability",
      "reproduction": "`swarm create` then `swarm query --seed-file urls.txt --sql @q.sql --refresh --wait` (6 jobs complete with task IDs), then run `swarm list`.",
      "expected": "`swarm list` shows the 6 just-submitted tasks (plus any prior tracked tasks) with completed status.",
      "actual": "`swarm list` reports 'Status: 2 total' and lists only two stale `swarm-query` tasks dated 2026-08-05 from a prior session. The 6 new task IDs are absent from `swarm list`, yet `swarm status <id>` and `swarm result <id>` return correct 'completed' data for them.",
      "rootCause": "Needs investigation — `swarm list` appears to enumerate a different store/filter than the submission store that `swarm query`/`swarm status`/`swarm result` read from, so recently completed query jobs are invisible to the list view.",
      "codePointer": "",
      "suggestion": "- Reconcile the swarm task store used by `list` with the one used by `query`/`status`/`result` so they are consistent.\n- Add a `--clear-stale` hint (already present at create time) that actually removes the stale entries, or auto-expire them."
    },
    {
      "title": "SKILL.md §4d omits the webdb export step needed to turn crawled/swarmed pages into an HTML directory for WebMiner",
      "severity": "Medium",
      "category": "Documentation",
      "reproduction": "Follow §4d 'Need to acquire pages first' → 'Bulk download: crawl --seed-file urls.txt --depth 0' or 'High throughput: swarm create → swarm query --seed-file ...', then try to 'feed the HTML directory to WebMiner'.",
      "expected": "A documented mechanism that yields a directory of HTML files consumable by WebMiner.",
      "actual": "`crawl --depth 0` and `swarm query` store fetched content in the Browser4 web-database/cache (swarm returns JSON `resultSet` + `pageContentBytes`), not on-disk HTML files. The only way to produce a local HTML directory is `webdb export <urls> <output-dir>` (which works and writes `<host>_<path>.htm` files), but that command is not referenced anywhere in §4d or the scent-miner SKILL.md.",
      "rootCause": "The acquisition guidance describes fetch paths but never connects them to the HTML-file output that WebMiner requires; the `webdb export` staging command is undocumented in this workflow.",
      "codePointer": "skills/browser4-cli/SKILL.md §4d (lines 353-357); skills/scent-miner/SKILL.md (lines 99-100 'Offline only').",
      "suggestion": "- Add `webdb export <urls> <dir>` (or an equivalent) as the explicit staging step after crawl/swarm in §4d.\n- Clarify that `swarm query` returns extracted JSON, not HTML files, so the phrase 'feed the HTML directory to WebMiner' is not misleading."
    },
    {
      "title": "webminer.ps1 launcher is not at the repo root despite SKILL.md instructing `.\webminer.ps1`",
      "severity": "Medium",
      "category": "Discoverability",
      "reproduction": "From the repo root, run `.\webminer.ps1 install` or `.\webminer.ps1 all <dir>` as instructed by skills/scent-miner/SKILL.md and skills/browser4-cli/SKILL.md §4d.",
      "expected": "The launcher resolves from the repo root as documented.",
      "actual": "No `webminer.ps1` exists at the repo root (`Test-Path .\\webminer.ps1` → False). The script lives at `skills/scent-miner/scripts/webminer.ps1`. The browser4-cli SKILL.md does note the script 'ships with the web-miner project, not this repo', but the scent-miner SKILL.md and §4d code blocks use the bare `.\webminer.ps1` form, which fails for a first-time user at the repo root.",
      "rootCause": "Inconsistent/ambiguous launcher location guidance across the two SKILL.md files; the examples assume the launcher is on PATH or in the cwd.",
      "codePointer": "skills/scent-miner/SKILL.md (lines 10-27, 48); skills/browser4-cli/SKILL.md (line 366).",
      "suggestion": "- Make every example use a resolvable form: either `java -jar scent-miner.jar ...` or the fully-qualified `skills/scent-miner/scripts/webminer.ps1`.\n- Clearly state the launcher's actual path (or that it must be installed/placed on PATH) in one canonical place."
    },
    {
      "title": "K auto-detection reports K=72 for a 3-page corpus; 'groups similar web pages' is misleading",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Run WebMiner `all` on 3 HTML files and read `clusteringInfo.txt` (e.g. 'K = 72, Web pages = 3').",
      "expected": "Cluster count consistent with the number of pages (K ≤ 3), matching the SKILL.md description 'WebMiner groups similar web pages together'.",
      "actual": "K=72 for 3 pages. The encode stage reports '205 nodes' across 3 documents; KMeans runs on DOM-element feature rows (`label,prediction,top,left,width,height,seq,text,url`), so K is a count of element/visual-block clusters, not page clusters.",
      "rootCause": "The clustering unit is DOM elements (visual blocks), not whole pages. The docs describe page-level grouping, but the implementation clusters elements; auto-detected K is therefore unrelated to page count.",
      "codePointer": "skills/scent-miner/SKILL.md (lines 1-6); skills/browser4-cli/SKILL.md §4d (lines 343-360).",
      "suggestion": "- Clarify in both SKILL.md files that clustering operates on page elements/visual blocks (and the report groups pages by shared element clusters), and explain what auto-detected K means."
    },
    {
      "title": "Unquoted `--sql @.test-sessions/...` fails with a cryptic PowerShell parser error",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run `browser4-cli swarm query --seed-file urls.txt --sql @.test-sessions/query.sql --refresh`.",
      "expected": "The CLI reads the query file (the `@` prefix means 'read from file').",
      "actual": "PowerShell raises `ParserError: Unrecognized token in source text` at the `@` because `@.` is parsed as invalid splatting; the user must quote (`--sql \"@.test-sessions/query.sql\"`). The bare-filename example `--sql @query.sql` in help works only when the file is in cwd and the token has no leading `.`/path separator.",
      "rootCause": "The `@`-prefix convention collides with PowerShell splatting/tokenization when the following character is `.` or a path separator; the error surfaces as a shell parser error with no CLI guidance.",
      "codePointer": "cli/browser4-cli (argument parsing for --sql); help text in skills/browser4-cli/SKILL.md and commands.rs.",
      "suggestion": "- Add a dedicated `--sql-file <path>` option that takes a plain path (no `@` prefix) to avoid the shell ambiguity.\n- Document quoting for paths starting with `.` or `/`, and note the bare `@query.sql` form only applies to cwd-relative filenames."
    },
    {
      "title": "Launcher emits an invalid `--add-opens` JVM warning on JDK 17",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "Run the launcher: `pwsh -File skills/scent-miner/scripts/webminer.ps1 all <dir>`.",
      "expected": "No JVM warnings; the module-opens list is valid for JDK 17.",
      "actual": "Every launcher invocation prints `WARNING: package sun.security.action not in java.base` — the package is not present in java.base on this JDK 17 build.",
      "rootCause": "The launcher's `$ModuleOpts` includes `--add-opens=java.base/sun.security.action=ALL-UNNAMED` (webminer.ps1:267), referencing a package that has moved/doesn't exist in java.base on JDK 17.",
      "codePointer": "skills/scent-miner/scripts/webminer.ps1:267.",
      "suggestion": "- Remove or guard the `sun.security.action` module-open (it is unnecessary on JDK 17)."
    },
    {
      "title": "CLI falls back to writing state into the repo (.browser4-cli-state) with a permission warning",
      "severity": "Low",
      "category": "UX",
      "reproduction": "Run any browser4-cli command that mutates CLI state (e.g. `swarm create`) in this environment.",
      "expected": "Silent state persistence to the user config dir.",
      "actual": "Prints `browser4-cli: warning: cannot write CLI state to C:\\Users\\pereg\\.browser4 (permission denied)` and falls back to `D:\\workspace\\Browser4\\Browser4-4.13\\.browser4-cli-state`, polluting the repo working tree with a state directory.",
      "rootCause": "`~/.browser4` is not writable in this environment; the documented fallback (`BROWSER4_CLI_STATE_DIR` / `./.browser4-cli-state`) engages, but the warning is noisy and the fallback location is inside the repo root.",
      "codePointer": "cli/browser4-cli (state-dir resolution; see BROWSER4_CLI_STATE_DIR handling).",
      "suggestion": "- Suppress the warning after the first occurrence, and prefer an OS-temp fallback over the repo root."
    },
    {
      "title": "webdb export prints a spurious '### Page / ### Snapshot' section after its JSON result",
      "severity": "Low",
      "category": "Product",
      "reproduction": "Run `browser4-cli webdb export \"<urls>\" <output-dir>`.",
      "expected": "Only the export JSON summary.",
      "actual": "The JSON result is followed by a `### Page` / `### Snapshot` block (page URL/title + snapshot path + tip), indicating an unrelated page navigation/snapshot side-effect during the export.",
      "rootCause": "Likely the export command (or its backend handler) navigates the current session to one of the exported URLs and the CLI's post-command page-info/snapshot output fires; needs investigation.",
      "codePointer": "cli/browser4-cli (webdb export dispatch / post-command snapshot output).",
      "suggestion": "- Suppress page-info/snapshot output for storage/export commands that should not change the active page."
    },
    {
      "title": "Bare `http://localhost:18080/ec/dp/` returns 404 (task/doc path ambiguity)",
      "severity": "Low",
      "category": "Documentation",
      "reproduction": "Open `http://localhost:18080/ec/dp/` (as the task's 'under /ec/dp/' phrasing suggests).",
      "expected": "A product listing or a discoverable set of product detail pages.",
      "actual": "HTTP 404. Product detail pages live at `/ec/dp/{productId}` (e.g. `/ec/dp/B0E000001`); the bare path has no controller mapping.",
      "rootCause": "`EcommerceController` maps `@GetMapping(\"/dp/{productId}\")` and a fallback 404s the bare `/dp/`; the task instruction 'pages under http://localhost:18080/ec/dp/' is slightly ambiguous.",
      "codePointer": "browser4-tests/pulsar-tests-common/src/main/kotlin/ai/platon/pulsar/test/server/ec/EcommerceController.kt:78-91.",
      "suggestion": "- Clarify the task/doc wording to '/ec/dp/{productId}' and point to the sitemap (`/ec/sitemap.xml`) which lists all product URLs."
    },
    {
      "title": "Remote https://browser4.io/SKILL.md unreachable (SSL schannel failure) in this environment",
      "severity": "Low",
      "category": "Reliability",
      "reproduction": "`Invoke-WebRequest https://browser4.io/SKILL.md` or `curl.exe -sS https://browser4.io/SKILL.md`.",
      "expected": "The markdown is fetched for the 'read the docs' preparation step.",
      "actual": "All attempts fail: `The SSL connection could not be established` / `schannel: AcquireCredentialsHandle failed: SEC_E_NO_CREDENTIALS`. Worked around by reading the identical local `skills/browser4-cli/SKILL.md`.",
      "rootCause": "Environment-specific — the sandbox's Windows schannel has no usable TLS credentials for outbound HTTPS; not a browser4-cli defect.",
      "codePointer": "",
      "suggestion": "- N/A for browser4-cli (environment). Consider hosting the canonical SKILL.md at an additional fetch path if first-time users on restricted networks are expected to reach it."
    }
  ],
  "assessment": {
    "completionStatus": "Successful — all five acceptance criteria (AC1–AC5) were completed with evidence; the free WebMiner pipeline ran encode→cluster→views, crawl and swarm acquisition paths both completed, and the production-scale decision point was documented.",
    "successRate": "90% — every task step succeeded; the notable deviations were (a) WebMiner views output landing in a temp dir instead of the documented location, (b) needing to quote the --sql @path argument, and (c) swarm list state mismatch.",
    "issuesFound": 12,
    "majorBlockers": "None — the task was completed end-to-end. The WebMiner 'views' output location (Issue 1) is the closest thing to a blocker: the interactive report is written to a temp directory rather than the documented output tree, which would mislead a user following the SKILL.md.",
    "mostConfusingAspects": "1) Where WebMiner's index.html/.xlsx actually land (temp vs documented dir). 2) Which acquisition command produces HTML files (crawl/swarm return cached content or JSON; only `webdb export` yields an HTML directory). 3) The launcher's real location and the near-silent pipeline output.",
    "mostValuableImprovements": "1) Make `all` write views into the same documented `<html-dir>-ml-output/kmeans-result/p<ts>/` tree (and print the absolute path). 2) Document `webdb export` as the staging step from crawl/swarm to WebMiner. 3) Fix `swarm list` to reflect submitted query jobs, and clarify the launcher location.",
    "usabilityRating": 6
  }
}
```

---

**Summary of key findings:** The browser4-cli surface itself is strong — `help` output is thorough, every command I needed (`goto`, `htmlsnapshot export`, `crawl`, `swarm create/query`, `webdb export`) worked on first or second try, and error messages were mostly clear. The friction concentrated in the **WebMiner integration** documented in SKILL.md §4d: the pipeline's most valuable output (the interactive report) is written to a temp directory rather than the documented location, the launcher is not where the docs say it is, the acquisition paths don't state how to actually produce an HTML directory (`webdb export`), and `swarm list` showed stale state instead of the jobs just submitted. All findings are recorded above with root-cause analysis and fix pointers.

---

## Issues Found (0)

No issues could be parsed from Section C of the agent output.

See `20260813-093345-webminer-structuring-routing.full.md` for the complete evaluation output.

