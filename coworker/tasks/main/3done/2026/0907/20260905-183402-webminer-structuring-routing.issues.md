# Issues: webminer-structuring-routing

> **Source:** `20260905-183402-webminer-structuring-routing.full.md` | **Date:** 20260905-183402 | **Mode:** dev

## Scenario Background

### Task

**All five acceptance criteria were met successfully** (MockSite on localhost:18080, backend auto-started via `./b4w.ps1`, WebMiner v0.0.8 installed through the first-class `webminer` CLI command):

- **AC1 ✅ (free tier, correct branch):** Built a 5-page cross-category corpus (`.test-sessions/corpus-small/`: book, 2× electronics, home, toys) and ran `webminer all .test-sessions/corpus-small`. Full pipeline succeeded: encode (5 docs, 344 nodes → CSV), SMILE KMeans (k auto-detect), views. All documented artifacts verified on disk: `corpus-small-ml-output/kmeans-result/p20260906022638/{3×result.csv, clusteringInfo.txt}` plus the views set (index.html, `p<ts>.all/.qualified/.perfect.html`, `.xlsx`, `.json`) in the documented temp task-output root. 5 pages « 1,000 → SMILE branch confirmed correct. A second run over the crawl-derived corpus (8 `.htm` files) and a `webminer views <result-dir>` rebuild also succeeded.
- **AC2 ✅:** Decision point documented in `.test-sessions/ac2-production-scale-decision.md` — a 1,200-page/day corpus exceeds the "< 1,000 pages" free-tier guidance, so it maps to the WebMiner Commercial (Apache Spark ML) pipeline; the same crawl/swarm acquisition patterns feed the distributed deployment instead of the local SMILE engine.
- **AC3 ✅:** 5 MockSite detail pages (`/ec/dp/{B0B000001,B0E000001,B0E000002,B0H000001,B0T000001}`) each visited with `goto`, captured with `htmlsnapshot`, exported to distinct files with `htmlsnapshot export --file …`; directory verified to contain 5 distinct complete product-page HTML files with correct titles.
- **AC4 ✅:** 8-URL seed file → `crawl --seed-file … --depth 0 --refresh --verbose` fetched all 8 pages (titles verified). HTML staged outside the cache via `webdb export` into `.test-sessions/corpus-crawl/` (8 `.htm` files) and successfully re-run through `webminer all`. Depth-0 seed-file crawling confirmed as the correct known-URL path.
- **AC5 ✅:** `swarm create --display-mode HEADLESS` → `swarm query --seed-file … --sql @q.sql --refresh --wait` — all 8 jobs completed in 22 s with correct url/title/price rows per page; `swarm result` and `swarm close` behaved as documented. A controlled experiment further proved swarm fetches persist into webdb (URL never fetched before → swarm fetch → session closed → `webdb export` succeeds), so the swarm branch can produce an on-disk HTML corpus.

**Issues found:** 5 (1 Medium reliability, 4 Low) — none blocked the task; one required a documented workaround (comma-separated `webdb export` URLs, Issue 1).

### Execution Context

**Key Commands:**

**Key decisions & workarounds:**
1. **webdb export delimiter (main workaround):** the one-line help (`webdb export <urls> <output-dir>`) doesn't state the delimiter; a space-separated URL list exported only 1 of 8 pages while reporting success. After reading per-command help and `webdb.md` (comma-separated), re-ran comma-separated → all 8 exported. Filed as Issue 1.
2. **Per-page capture/export sequencing** for AC3 because `htmlsnapshot` stores one snapshot per page (latest wins) — capture must precede each export.
3. **All temporary artifacts** (corpora, seeds, SQL, logs, this report) confined to `.test-sessions/`.
4. **/ec/dp/ index 404s** and `/ec/b` is query-driven — used product-detail URLs only.
5. **Java noise** on every webminer run (`Picked up JAVA_TOOL_OPTIONS…`, `WARNING: package sun.security.action not in java.base` on auto-detected JDK 25) — harmless, not filed.



**Overall Assessment (Section D summary):** Task completion **Successful**, success rate ~100% of criteria. 5 issues found — 1 Medium (Reliability), 4 Low (Documentation/UX/Product). No blockers. First-time-user confusion centered on the `webdb export` delimiter trap, the missing swarm→HTML staging step, and two-root WebMiner output locations. Most valuable fixes: separator-tolerant `webdb export` with validation, opt-in capture hints, and §4d cross-links. The CLI itself (auto-start, help, crawl, swarm, webminer integration, exit messaging) was smooth, consistent, and well-documented — **usability rating 8/10**. Full prose of Sections A/B and all per-run logs live in `.test-sessions/webminer-usability-evaluation.md`, `.test-sessions/wm-*-run.log`, and `.test-sessions/ac2-production-scale-decision.md`.

---

## Issues Found (5 issues)

### Issue 1: webdb export silently exports only the first page when URLs are space-separated, reporting success

**Severity:** Medium
**Category:** Reliability

#### Reproduction

1) Fetch 8 pages: `./b4w.ps1 crawl --seed-file seed.txt --depth 0 --refresh` where seed.txt holds 8 MockSite product URLs. 2) `./b4w.ps1 webdb export "http://localhost:18080/ec/dp/B0B000001 http://localhost:18080/ec/dp/B0E000001 ... http://localhost:18080/ec/dp/B0H000001" .test-sessions/corpus-crawl` (space-separated list, the natural reading of the one-line help `webdb export <urls> <output-dir>` which shows no delimiter). 3) Inspect the output dir.

#### Expected Behavior

Either export all 8 URLs, or reject the input with a validation error stating that URLs must be comma-separated (as documented in `webdb export --help` and webdb.md).

#### Actual Behavior

Output was {"total":1,"succeeded":1,"failed":0,...} and only B0B000001.htm was written — 1 of 8 pages exported, reported as full success with no warning. The whole space-joined string appears as a single entry in results[].url. A user staging an HTML corpus for WebMiner would silently feed a 1-page corpus downstream.

#### Root Cause Analysis

browser4-rest/.../agent/tool/WebDbToolExecutor.kt export() splits the urls argument strictly on ',' (line ~83: urls.split(",")). A space-joined string becomes one lookup URL; URL normalization silently truncates at the first space (spaces are invalid URL characters), so the first page's cached entry is found, exported, and reported ok. Remaining 7 URLs are never looked up and no count mismatch is surfaced. The comma requirement is documented only in the per-command help (ArgDef 'Comma-separated URLs to export') and webdb.md, not in the top-level `browser4-cli help` one-liner, so a first-time user following the top-level help gets the partial export.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/WebDbToolExecutor.kt:83 (export(): urls.split(",") + exportPage normalize/truncate); help summary text at cli/browser4-cli/src/commands.rs:2152`

#### AI Suggested Improvement

- In WebDbToolExecutor.export(), split on commas AND whitespace (e.g. urls.split(Regex("[,\\s]+"))) so shell-natural space-separated lists work like the crawl/swarm seed-file convention
- Or validate that no single URL token contains whitespace and fail loudly, printing the expected comma-separated format
- Add the delimiter hint to the one-line command summary in `browser4-cli help` (CommandDef description) so the top-level help is unambiguous

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] Silent partial export reported as success is a genuine reliability defect (1-of-8 pages → downstream corpus corruption) and must be fixed. Prefer the fail-loud option — validate tokens contain no whitespace and surface a count mismatch — over permissive whitespace splitting, since accepting both delimiters can mask pasted inputs that mix conventions; either way the one-line `commands.rs:2152` help must state the delimiter.

---

### Issue 2: htmlsnapshot capture prints an ~11-line 'Try these next' hint block to stdout on every run, contradicting the docs that tips are suppressed by default

**Severity:** Low
**Category:** UX

#### Reproduction

`./b4w.ps1 htmlsnapshot` (or `htmlsnapshot capture`) on any page; observe stdout. Repeat for a loop of pages (e.g. the AC3 workflow goto -> htmlsnapshot -> htmlsnapshot export per page).

#### Expected Behavior

Capture output stays brief; onboarding hints appear at most once per session or only with the opt-in `-tip/--show-tip` flag. SKILL.md Output Modes section states 'Tips are suppressed by default; use this flag to enable them.'

#### Actual Behavior

Every capture prints a hardcoded block to stdout after the summary: 'ℹ️ The live page is still accessible...' plus a '💡 Try these next:' section with up to 8 usage example lines (~33 stdout lines per capture on a MockSite product page, about a third of it boilerplate hints). During the 5-page AC3 loop this added ~40 lines of repeated guidance, burying the metadata a script/user needs. `-q` does silence it, but the default contradicts the documented tip policy.

#### Root Cause Analysis

The hint block is emitted unconditionally by handle_html_snapshot in the CLI (cli_println! sequence), independent of the opt-in rotating-tip mechanism (-tip/tips.rs). It was likely intended as one-time onboarding but is not session-gated or flag-gated.

#### Code Pointer

`cli/browser4-cli/src/main.rs:6610 (handle_html_snapshot '💡 Try these next:' block)`

#### AI Suggested Improvement

- Gate the block behind -tip/--show-tip (or a --no-hints flag) to match the documented 'tips suppressed by default' policy
- Or print it once per CLI state directory (first htmlsnapshot capture ever), then stay quiet on subsequent captures
- Ensure -q already suppresses it (verified it does) and document that as the script-mode escape hatch

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The unconditional hint block directly contradicts the documented "tips suppressed by default" policy, so behavior should be aligned to the docs; gating behind `-tip/--show-tip` is the cleanest fix since the mechanism already exists. Printing once per state directory would preserve onboarding value, but flag-gating is simpler and matches SKILL.md exactly.

---

### Issue 3: swarm submit help text is inaccurate: without --sql the resultSet is not empty but holds one URL-only row per submitted URL

**Severity:** Low
**Category:** Documentation

#### Reproduction

`./b4w.ps1 swarm create --display-mode HEADLESS`, then `./b4w.ps1 swarm submit "http://localhost:18080/ec/b?node=1292115013" --wait`, then `./b4w.ps1 swarm result <task-id>`.

#### Expected Behavior

Per the documented help ('Without --sql, each URL is fetched but no data is extracted — the resultSet will be empty'), the resultSet should be empty.

#### Actual Behavior

resultSet contains one row per URL: [{"url":"http://localhost:18080/ec/b?node=1292115013"}]. Harmless (arguably useful as a marker row), but the help text is wrong, which erodes trust in the reference while scripting around it.

#### Root Cause Analysis

The scrape/submit path returns a marker row per submitted URL rather than an empty resultSet; the CLI help description at commands.rs:2839 documents the intended (empty) contract and was not updated when the backend behavior changed. Backend row construction needs tracing from SwarmToolExecutor.kt submit()/ScrapeRequest into the swarm service to confirm where the url row is added.

#### Code Pointer

`cli/browser4-cli/src/commands.rs:2839 (swarm-submit description); browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/SwarmToolExecutor.kt:80 (submit dispatch)`

#### AI Suggested Improvement

- Update the description to: 'Without --sql, each URL is fetched but no data columns are extracted — the resultSet contains only a url row per page'
- Update the same sentence in swarm.md reference if it repeats the claim

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Pure help-text inaccuracy with a low-risk, well-scoped fix; the marker row is arguably useful, so update the description to the proposed 'URL-only row per page' wording rather than changing backend behavior. Apply the same correction to swarm.md wherever it repeats the 'resultSet will be empty' claim.

---

### Issue 4: SKILL.md §4d swarm acquisition branch never says how the fetched HTML becomes an on-disk corpus for WebMiner

**Severity:** Low
**Category:** Documentation

#### Reproduction

Follow the §4d decision tree: 'High throughput: browser4-cli swarm create -> swarm query --seed-file ... Then feed the HTML directory to WebMiner'. Run the swarm query with --sql: you receive structured rows; no HTML directory is produced, and nothing in the tree or swarm.md mentions exporting the fetched pages.

#### Expected Behavior

The acquisition branch should state the concrete staging step so a user can actually 'feed the HTML directory to WebMiner' after swarm acquisition.

#### Actual Behavior

swarm query returns extracted rows only. The HTML files are nevertheless recoverable: a controlled experiment (URL with no prior webdb entry -> `swarm submit` -> `swarm close` -> `webdb export <url> <dir>`) succeeded, proving swarm fetches persist into webdb and can be exported — but this path is undocumented in the §4d tree, so a first-time user cannot discover it from the docs.

#### Root Cause Analysis

Documentation gap: SKILL.md §4d lists swarm as an acquisition alternative but omits the webdb-export staging step that links it to the WebMiner input directory; webdb.md covers export in isolation without cross-referencing the WebMiner flow.

#### Code Pointer

`skills/browser4-cli/SKILL.md §4d acquisition tree (lines ~357-361)`

#### AI Suggested Improvement

- Add one line under the swarm bullet: 'swarm fetches are cached in webdb — stage them with `webdb export <comma-separated URLs> <dir>` (URLs must be comma-separated)'
- Or point to crawl --depth 0 when on-disk files are the goal and note swarm shines for extraction throughput
- Cross-link webdb.md from the §4d tree

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] The §4d tree dead-ends — swarm acquisition has no documented path to the on-disk corpus WebMiner consumes, and the verified webdb-export recovery step is undiscoverable. Note the coupling with Issue 1: if #1 ships, the recommended doc line must not carry a stale '(URLs must be comma-separated)' caveat — write the doc text only after #1's delimiter behavior is decided, or reference `webdb export --help` for the format instead of hardcoding it.

---

### Issue 5: webminer all reports auto-detected K near the DOM-node count and result.csv's label column is empty, making page-level clustering results hard to interpret

**Severity:** Low
**Category:** Product

#### Reproduction

`./b4w.ps1 webminer all <dir>` on the 5-page corpus (344 nodes) and the 8-page corpus (521 nodes), then inspect `<output>/kmeans-result/p<ts>/predictionAndOriginalFeatures/result.csv` and clusteringInfo.txt.

#### Expected Behavior

For a corpus of 5-8 product pages the docs imply clusters of related pages ('WebMiner groups similar web pages together... auto-detected K... produces better results than guessing'). The CSV should be self-describing for Excel analysis.

#### Actual Behavior

K was auto-detected at 71 (5 pages, 344 rows) and 69 (8 pages, 521 rows) — roughly one cluster per 7-8 DOM-node rows, i.e. node-granularity fragmentation with each page spread across dozens of clusters. In result.csv the first data column 'label' is empty on every row and clustering operates on DOM nodes (rows carry a page `url` only in the far-right column). A user opening the spreadsheet cannot directly see 'which pages grouped together'. Views artifacts do exist and contain extracted content, but the CSV/k summary is misleading at page level.

#### Root Cause Analysis

Behavior originates in the external scent-miner.jar ML engine (clusters DOM-node rows, auto-K heuristic scales with node count) surfaced verbatim through the first-class CLI. Needs upstream analysis in the web-miner project to confirm whether page-level aggregation is intended to be visible in result.csv; the CLI only forwards stdout. Uncertain — further investigation required.

#### AI Suggested Improvement

- Document in web-miner SKILL.md / §4d that clustering operates at DOM-node granularity and that result.csv rows are nodes attributed to pages via the `url` column, so expectations match observed artifacts
- Consider upstream: aggregate predictions to page level (e.g. dominant cluster per page) in an additional summary artifact so 'clusters of related pages' is verifiable
- Fill the label column or drop it from the CSV rather than emitting an always-empty header

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [x] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: DEFER] Root cause lives in the external scent-miner.jar ML engine and the CLI is a faithful stdout forwarder, so an in-repo fix is out of scope without upstream confirmation of intended granularity — file an upstream issue for page-level aggregation and the empty label column. Meanwhile, the cheapest correct action is the SKILL.md/§4d note documenting node-granular clustering and the `url` column as the page attribution key, which can land without the upstream change.

---

## Overall Assessment

**Completion Status:** Successful — all five acceptance criteria (AC1-AC5) verified end-to-end against the local backend and MockSite; free SMILE pipeline ran twice over different corpora, crawl and swarm acquisition both completed and were staged/exported, and the production-scale decision point was documented.

**Success Rate:** 100% of acceptance criteria passed; 95% of individual steps succeeded first-try (one workaround: comma-separated webdb export URLs, Issue 1)

**Issues Found:** 5

**Major Blockers:** None. One Medium reliability defect (webdb export silent partial export with space-separated URLs) required a documented-format workaround.

**Most Confusing Aspects:** For a first-time user: (1) `webdb export <urls>` accepts only comma-separated URLs but the top-level help shows no delimiter hint, and wrong input silently exports a subset; (2) after swarm acquisition the docs say 'feed the HTML directory to WebMiner' but no directory is produced — the webdb export staging step is undiscoverable; (3) WebMiner outputs land in two places (corpus-adjacent -ml-output dir for clustering CSVs, %TEMP% views root for the interactive report) and K auto-detection returns ~node-count clusters, which looks wrong for tiny corpora.

**Most Valuable Improvements:** Fix webdb export separator handling/validation so space-separated URL lists export fully or error loudly (highest value — silent data loss); make htmlsnapshot hints opt-in or one-time to reduce loop noise; document the swarm -> webdb export staging step in §4d; correct the swarm submit 'empty resultSet' help text.

**Usability Rating:** 8/10

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

#### Issue 1: webdb export silently exports only the first page when URLs are space-separated, reporting success

1) Fetch 8 pages: `./b4w.ps1 crawl --seed-file seed.txt --depth 0 --refresh` where seed.txt holds 8 MockSite product URLs. 2) `./b4w.ps1 webdb export "http://localhost:18080/ec/dp/B0B000001 http://localhost:18080/ec/dp/B0E000001 ... http://localhost:18080/ec/dp/B0H000001" .test-sessions/corpus-crawl` (space-separated list, the natural reading of the one-line help `webdb export <urls> <output-dir>` which shows no delimiter). 3) Inspect the output dir.

#### Issue 2: htmlsnapshot capture prints an ~11-line 'Try these next' hint block to stdout on every run, contradicting the docs that tips are suppressed by default

`./b4w.ps1 htmlsnapshot` (or `htmlsnapshot capture`) on any page; observe stdout. Repeat for a loop of pages (e.g. the AC3 workflow goto -> htmlsnapshot -> htmlsnapshot export per page).

#### Issue 3: swarm submit help text is inaccurate: without --sql the resultSet is not empty but holds one URL-only row per submitted URL

`./b4w.ps1 swarm create --display-mode HEADLESS`, then `./b4w.ps1 swarm submit "http://localhost:18080/ec/b?node=1292115013" --wait`, then `./b4w.ps1 swarm result <task-id>`.

#### Issue 4: SKILL.md §4d swarm acquisition branch never says how the fetched HTML becomes an on-disk corpus for WebMiner

Follow the §4d decision tree: 'High throughput: browser4-cli swarm create -> swarm query --seed-file ... Then feed the HTML directory to WebMiner'. Run the swarm query with --sql: you receive structured rows; no HTML directory is produced, and nothing in the tree or swarm.md mentions exporting the fetched pages.

#### Issue 5: webminer all reports auto-detected K near the DOM-node count and result.csv's label column is empty, making page-level clustering results hard to interpret

`./b4w.ps1 webminer all <dir>` on the 5-page corpus (344 nodes) and the 8-page corpus (521 nodes), then inspect `<output>/kmeans-result/p<ts>/predictionAndOriginalFeatures/result.csv` and clusteringInfo.txt.



---

## Processing Log (2026-09-07)

Handled per Human Review decisions. Backend changes verified by compiling `browser4-rest`; CLI help verified in full CLI test suite.

| Issue | Decision | Resolution |
|---|---|---|
| 1 — `webdb export` silently exports only the first page with space-separated URLs (Medium) | ACCEPT with improvements | Fixed (fail-loud per review): `WebDbToolExecutor.export()` now rejects any URL token containing whitespace with an error naming the expected comma-separated format (previously 1-of-N pages exported as full success); top-level command description also states the comma rule. |
| 2 — htmlsnapshot capture hint block on stdout (Low) | ACCEPT | Fixed: gated behind `--show-tip`/`-tip` on stderr — same change as extraction-method-routing issue 2. |
| 3 — swarm submit help text: empty resultSet claim (Low) | ACCEPT | Fixed: description now says resultSet contains a url row per page — same change as swarm-parallel-scraping issue 4 (shared `commands.rs` text + swarm.md). |
| 4 — SKILL §4d swarm acquisition never says how HTML becomes an on-disk corpus (Low) | ACCEPT with improvements | Fixed (docs): §4d now documents the staging step — swarm fetches persist in webdb, export with `webdb export "<comma-separated URLs>" <dir>` (comma-separated; see webdb.md), then feed the directory to WebMiner; crawl `--depth 0` noted as the on-disk-first alternative. |
| 5 — webminer K near node count, empty label column (Low) | DEFER | Per review: upstream scent-miner.jar behavior, deferred for upstream confirmation. A SKILL.md note documenting node-granular clustering (rows are DOM nodes; page attribution via the `url` column; views are the page-level artifacts) was added as the cheap actionable piece. |