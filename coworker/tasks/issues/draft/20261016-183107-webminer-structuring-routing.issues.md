# Issues: webminer-structuring-routing

> **Source:** `20261016-183107-webminer-structuring-routing.issues.json` | **Date:** 20261016-183107 | **Mode:** dev

## Scenario Background

### Task

All five acceptance criteria were exercised end-to-end against the running MockSite (`http://localhost:18080`) with the locally-built backend (dev mode, port 8282), and every branch of SKILL.md §4d was covered.

**AC3 — single-page acquisition: PASS.** Three product detail pages (`/ec/dp/B0A000001..3`) were visited with `goto` and each was exported with `htmlsnapshot export --file`, producing a 3-file corpus (~14 KB each) in `.test-sessions/20261015T0901431699046Z/corpus-small/`.

**AC1 — free WebMiner pipeline, small corpus: PASS.** `webminer all corpus-small` completed in ~5 s: encode (3 documents, 145 nodes, 180,977 bytes → CSV), SMILE KMeans (k=59 auto-detected, silhouette 0.6941), views. All three artifact classes exist: encoded dataset CSV **and** the views under `%TEMP%\webminer-pereg\…` (as documented), clustering results under `corpus-small-ml-output/kmeans-result/p20261017021812/` (3× `result.csv` + `clusteringInfo.txt`). The `webminer views <result-dir>` rebuild branch also works (3.6 s, views land in-tree next to the results). The same pipeline ran on the 8-page crawled corpus (478 nodes, k=65). This is confirmed as the correct branch — both corpora are far below the 1,000-page free-tier guidance.

**AC4 — bulk acquisition from known URLs: PASS (with a reliability incident).** An 8-URL seed file was fetched with `crawl --seed-file … --depth 0 --refresh` (8/8 OK in 9.9 s, titles confirmed). The documented staging step — crawling stores HTML in webdb — was completed with `webdb export` (8/8 `ok`, `.htm` files), and WebMiner consumed that directory unchanged, so the crawl → WebMiner handoff works. Mid-session the fetch layer wedged: every *fresh* fetch began failing with `PrivacyException`/status 1601 (13/24 in one crawl, then 4/6 reproducibly, then any new URL, even alone), while `goto`/`htmlsnapshot` stayed healthy. Only a full `stop` + backend restart restored fetching (all 6 previously-failing URLs then returned 200); the 8-URL crawl was re-verified 8/8 afterwards.

**AC5 — swarm acquisition: PASS (functionally), but the "high-throughput" premise did not hold.** `swarm create --display-mode HEADLESS` (2 contexts × 8 tabs) followed by `swarm query --seed-file … --sql @query.sql --refresh` submitted 8 jobs (batch id returned, all 8 completed in ~26 s) and a 24-URL batch (24/24 completed, 0 failed, 113 s). `swarm result <task-id>` returned correct X-SQL rows (title/price/rating/rating_count). However, on identical input the swarm path was ~3× slower than plain crawl for fresh fetches (8 URLs: ~26 s vs 9.9 s), and cached swarm tasks complete in ~10 ms — the per-task durations ramp in a near-perfect ~2.5 s stagger, i.e. fresh loads appear to execute one at a time despite 2 contexts × 8 tabs.

**AC2 — production-scale decision point: PASS (documented, with a doc gap).** A 1,200-file corpus was assembled from real MockSite page captures. Running `webminer all` with defaults encoded only 40 files, dropped 1,160, and still printed `PIPELINE COMPLETE` — exactly the documented trap — mitigated by a clear stderr warning naming the exact re-run command (`--max-files 1200`). Per SKILL.md §4d and `decision-trees.md`, >1,000 pages maps to the commercial Apache Spark pipeline; the free local SMILE workflow is not the right tool at that scale. Gap: no bundled document says *how* to route a corpus to the commercial deployment (no link, contact, or procedure).

### Execution Context

Key commands (all via `./b4w.ps1`, run from the repo root, session `-s wm`):

1. `help`, `--help webminer`, `--help webminer all/views`, `--help crawl`, `--help swarm/query/create`, `--help htmlsnapshot`, `--help webdb/export`; read `skills/browser4-cli/SKILL.md` plus `references/decision-trees.md`, `references/webdb.md`, `references/swarm.md`, and the sibling `skills/browser4-web-miner/SKILL.md`. MockSite was already running on 18080 (verified with curl; product IDs discovered from `/ec/sitemap.xml`, 101 products).
2. AC3: `goto` → `htmlsnapshot export --file corpus-small/B0A00xxxx.html` ×3.
3. AC1: `webminer version` (already installed, v0.0.8, Java 17+ auto-detected — no `webminer install` needed); `webminer all <corpus-small>`; verified CSV/kmeans/views; `webminer views <result-dir>`.
...

(truncated — see 20261016-183107-webminer-structuring-routing.issues.json for the complete execution trace)

---

## Issues Found (10 issues)

### Issue 1: Fetch layer wedges: fresh crawl fetches fail with PrivacyException until the backend is restarted

**Severity:** High
**Category:** Reliability

#### Reproduction

1) ./b4w.ps1 -s wm swarm create --display-mode HEADLESS
2) ./b4w.ps1 -s wm crawl --seed-file seeds/products-24.txt --depth 0 --refresh   -> 'Crawl completed. 24 pages found.' but Summary: ok: 11, failed: 13 (PrivacyException)
3) ./b4w.ps1 -s wm crawl --seed-file seeds/unseen-6.txt --depth 0 --refresh     -> 4 of 6 fail, reproducible across runs
4) ./b4w.ps1 -s wm crawl "http://localhost:18080/ec/dp/B0HLT0001" --depth 0 --refresh  -> a brand-new URL fails alone
5) ./b4w.ps1 -s wm goto "http://localhost:18080/ec/dp/B0HLT0003"  -> still works (session browser is healthy)
6) ./b4w.ps1 swarm close  -> no effect;  ./b4w.ps1 stop  then retry -> fetching works again

#### Expected Behavior

Fresh fetches succeed (all URLs return HTTP 200 and are served by a local mock server), and if the pool is temporarily exhausted the system recovers on its own (the error text says 'retry once the browser pool recovers').

#### Actual Behavior

Every fresh fetch returns status 1601 with 'fetch returned 0 bytes: the fetch layer queued a retry (PrivacyException) - usually the privacy/browser layer refusing the fetch before any network I/O; retry once the browser pool recovers'. The crawl runner retries each page 3 times then marks it failed; the pool never recovered on its own (~6 minutes observed). Cached/store reads keep working, so failures are easily masked. Only a full backend restart (./b4w.ps1 stop + next command) restored fetching - after which all 6 previously-failing URLs returned 200.

#### Root Cause Analysis

MultiPrivacyContextManager.run() cannot obtain a ready privacy context for FetchTasks (tryGetNextReadyPrivacyContext throws PrivacyException); the log shows repeated 'PrivacyException, retry later (1/5)' while the crawl-level runner gives up after 3 attempts, so pages fail even though the privacy layer's own retry budget is not exhausted. Contexts held by long-lived sessions (the wm session plus a swarm session with 2 browser contexts) appear to exhaust or stall the pool, and nothing replenishes it. The FetchTask.nRetries counter is only incremented inside the PrivacyException branch, so the underlying 'no ready context' condition is never surfaced with its own message. Exact trigger (pool sizing, context retirement, swarm context life-cycle) needs investigation with backend logging at DEBUG for MultiPrivacyContextManager.

#### Code Pointer

`browser4-core/browser4-protocol/src/main/kotlin/ai/platon/pulsar/protocol/browser/emulator/context/MultiPrivacyContextManager.kt (run(), tryGetNextReadyPrivacyContext, isPrivacyRetryAllowed); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/crawl/CrawlRoundRunner.kt (per-page 3-attempt retry) and CrawlSupport.kt:670 (error text)`

#### AI Suggested Improvement

- Make pool exhaustion self-healing: recreate/retire privacy contexts when none are ready instead of failing tasks, and expose the condition in `doctor status --section browsers`
- Surface the real cause in the CLI error (e.g. 'no ready privacy context; run `browser4-cli stop` to reset the browser pool') instead of only the generic PrivacyException wrapper
- Add a CLI recovery command (reset/recycle the browser pool) so users do not need a full backend restart that also risks in-flight work
- Align the retry budgets: the crawl runner's 3 attempts should not be able to exhaust the privacy layer's 5-attempt budget silently

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Swarm fresh fetches appear serialized - ~3x slower than crawl for the same seed set

**Severity:** Medium
**Category:** Product

#### Reproduction

SAME 8-URL seed file, fresh pages (--refresh):
  time ./b4w.ps1 -s wm crawl --seed-file seeds/products.txt --depth 0 --refresh        -> ~9.9s, 8/8 ok
  time ./b4w.ps1 swarm query --seed-file seeds/products.txt --sql @product-query.sql --refresh --wait  -> ~26s for 8/8
  ./b4w.ps1 swarm list --limit 8   -> per-task durations ramp 6.9s, 8.3s, 10.9s, 13.5s, 16.0s, 18.6s, 22.7s, 25.3s (~2.6s stagger)
  (same pattern with 24 URLs: 0 failed, 113s, durations ramp 13.7s -> 1m52s)
  Control: ./b4w.ps1 swarm query --seed-file seeds/products-4.txt --sql @product-query.sql --wait -> cached hits complete in 9-13ms each, in parallel

#### Expected Behavior

Per SKILL.md and references/swarm.md, swarm is the high-throughput path: 'Jobs are enqueued and distributed across contexts automatically' / 'parallel execution across multiple browser contexts for high throughput', preferred when acquisition throughput matters more than sequential crawling.

#### Actual Behavior

With a swarm session of 2 contexts x 8 tabs, fresh page loads complete one at a time at a near-constant ~2.5s interval regardless of context/tab budget - i.e. effectively serial. Cached task results are returned in parallel (milliseconds), so the serialization is specific to the fresh-load path. Net effect: for a local/known-URL corpus, crawl (default parallel 4) is ~3-7x faster end-to-end than swarm for the same URLs.

#### Root Cause Analysis

Suspected: the fresh page-load path relies on a shared resource (browser instance / loader / tab pool) that serializes, or per-task context/tab setup dominates; needs measurement of scheduler and context utilisation during a swarm run. Not explained by CLI flags (--max-browser-contexts 2, --max-open-tabs 8 were in effect). A weaker alternative explanation (slow image loads amortised by crawl's parallelism) does not explain the cached-vs-fresh contrast.

#### Code Pointer

`browser4-rest swarm controller / browser driver-pool scheduling (e.g. the SwarmController query path); CLI side: cli/browser4-cli/src/main.rs (swarm query submission/polling)`

#### AI Suggested Improvement

- Measure and fix the fresh-load path so tasks actually run in parallel across the configured contexts, or document the real throughput characteristics
- Until then, soften the 'high throughput / preferred when acquisition throughput matters' guidance for small single-machine corpora, where `crawl --depth 0 --parallel 8` is measurably faster
- Add a swarm status output that shows per-context utilisation so users can tell whether jobs are running in parallel

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Backend process died mid-crawl and the CLI only reported a raw HTTP transport error

**Severity:** Medium
**Category:** Reliability

#### Reproduction

After ./b4w.ps1 stop (which auto-restarts the backend on the next command):
  ./b4w.ps1 -s wm crawl --seed-file seeds/unseen-6.txt --depth 0 --refresh --format table
  -> pages 1..4 of 6 fetched OK, then: 'Error: HTTP request failed: error sending request for url (http://127.0.0.1:8282/api/crawl/b52978ba-.../result)'
  ./b4w.ps1 status -> 'The backend is not running'
  tail of the backend pulsar.log ends abruptly at 02:27:56 with no shutdown lines (log: browser4-apps/browser4-bundle/target/runtime-bundle/_work/.../logs/pulsar.log)

#### Expected Behavior

Either the crawl completes, or the CLI reports that the backend died (and how to recover), rather than a raw client-transport error, leaving the user unable to distinguish a backend death from a network glitch; the interrupted crawl should be resumable via the documented `crawl list`/`crawl resume` path.

#### Actual Behavior

The JVM died abruptly mid-crawl with no error in its own log and no hs_err_*.log found; the CLI printed only 'error sending request for url (...)'. A later `crawl list` worked once the backend auto-restarted, but nothing told the user the server had died or that recovery was automatic.

#### Root Cause Analysis

Unknown - needs investigation. Two plausible causes: (a) a JVM/Chrome-driver hard crash (no dump found, so possibly an external kill); (b) a race with the preceding `stop`, whose process-tree cleanup may have killed the backend that the very next command had just auto-started (~10s earlier). Reproduce by running `stop` immediately followed by a long crawl and watching for a second backend death.

#### Code Pointer

`cli/browser4-cli/src/main.rs (backend health/error handling for crawl result polling)`

#### AI Suggested Improvement

- Detect 'connection refused/reset' during command polling and print 'the backend appears to have stopped; it will restart on the next command' plus the interrupted task id
- Ensure `stop`'s managed-process cleanup cannot race with an auto-start of a new backend
- Promote the crawl task to 'interrupted' so the documented `crawl resume <id>` path is offered explicitly

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `webminer all --help` omits the two operation-critical caveats (default truncation; views land in the temp dir)

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 --help webminer all

#### Expected Behavior

The command help should carry the same warnings the skill carries: the corpus is truncated at --max-files (default 40) and the run still ends with PIPELINE COMPLETE, and the interactive report/encoded dataset are written under the OS temp dir (not under --output).

#### Actual Behavior

Only Arguments/Options are listed ('--max-files <n> Maximum number of HTML files to process (default: 40)'). The truncation trap and the temp-dir output location appear only in SKILL.md section 4d, references/decision-trees.md and the sibling browser4-web-miner skill. A first-time user who reads only `--help` (the natural first step) can silently process 40 of N files. (Mitigated at runtime: the CLI prints a clear stderr warning with the exact re-run command when truncating.)

#### Root Cause Analysis

The webminer CommandDefs in cli/browser4-cli/src/commands.rs have no notes/help body, unlike e.g. crawl whose CommandDef carries a Notes block. The warnings were added to the docs and to the runtime warning path, but not to the help text.

#### Code Pointer

`cli/browser4-cli/src/commands.rs (CommandDef 'webminer-all' ~line 1073 and the webminer command panel)`

#### AI Suggested Improvement

- Add a Notes section to `webminer all --help` mirroring SKILL.md section 4d (truncation behaviour + example `--max-files`, temp-dir views, `webminer views` rebuild)
- Add the same note to the `webminer` family panel
- Consider printing the truncation warning to stdout when the pipeline finishes, not only stderr

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: No documented route to the commercial Spark WebMiner tier (AC2 decision point has no next step)

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Read skills/browser4-cli/SKILL.md section 4d, skills/browser4-cli/references/decision-trees.md (WebMiner tiers), skills/browser4-web-miner/SKILL.md; grep -ri 'commercial' across skills/ and README.md

#### Expected Behavior

When a corpus exceeds the free-tier guidance (>1,000 pages), the docs should say how to proceed - a product page, contact, deployment guide, or at minimum a named next step for routing the HTML corpus.

#### Actual Behavior

Only the tier description exists ('WebMiner Commercial (Apache Spark ML) - Distributed clustering for production workloads. Scales to 100K+ pages/day. Same pipeline, enterprise throughput') plus an error-table row 'see the commercial Spark tier for scale'. There is no URL, contact, procedure or CLI entry point anywhere in the bundled documentation, so the documented production-scale branch cannot actually be executed by a reader.

#### Root Cause Analysis

Documentation content gap - the tier comparison was written without a forward pointer, and no commercial deployment surface exists in the CLI or docs.

#### Code Pointer

`skills/browser4-cli/SKILL.md (section 4d), skills/browser4-cli/references/decision-trees.md (WebMiner tiers), skills/browser4-web-miner/SKILL.md (Errors & Recovery table)`

#### AI Suggested Improvement

- Add a concrete next step (doc link / contact / sales URL / deployment guide) wherever 'WebMiner Commercial' is mentioned
- State what the user should do with the acquired HTML corpus in the meantime (e.g. archive it in a documented layout)
- Note explicitly that no CLI command exists for the commercial tier so users do not search for one

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: `--help webminer all --examples` promises runnable examples but returns 'command maps to no single tool'

**Severity:** Low
**Category:** Discoverability

#### Reproduction

./b4w.ps1 --help webminer all
  -> ends with: 'Runnable examples: browser4-cli webminer all --help --examples.'
  ./b4w.ps1 --help webminer all --examples
  -> 'No examples available for 'webminer all' - the command maps to no single tool.'

#### Expected Behavior

Either the promised runnable examples are printed, or the help page does not advertise them.

#### Actual Behavior

The help page advertises examples; the follow-up command denies they exist, using an internal rationale ('maps to no single tool') that is meaningless to a user. Same for the bare `webminer` command.

#### Root Cause Analysis

webminer CommandDefs have an empty tool_name_fn, so main.rs's examples_or_reason() falls back to the generic 'no single tool' reason; the standard 'Runnable examples:' trailer is emitted unconditionally for commands with a help page (cli/browser4-cli/src/main.rs:24180).

#### Code Pointer

`cli/browser4-cli/src/main.rs (examples_or_reason at ~line 24180; help trailer rendering), cli/browser4-cli/src/commands.rs (webminer CommandDefs)`

#### AI Suggested Improvement

- Add real examples for the webminer family (install / all --max-files / views) and serve them from --examples
- When a command legitimately has no examples, suppress the trailer instead of printing a broken pointer

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: `webminer all` prints 'PIPELINE COMPLETE' even when most of the corpus was dropped

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 webminer all .test-sessions/<run>/corpus-large   # 1,200 HTML files, default --max-files 40

#### Expected Behavior

The completion banner should state the scope of the run (e.g. 'encoded 40 of 1,200 files', 'results cover 3% of the corpus') so a user skimming stdout cannot mistake a truncated run for a full one.

#### Actual Behavior

stdout prints 'Encoded 40 document(s)' (a small line in the encode stage) and later a bold 'PIPELINE COMPLETE' summary listing project/CSV/KMeans/Views paths with no file count. The '1,160 file(s) left out' fact appears only in the CLI's stderr warning and in a second stderr line from the JAR.

#### Root Cause Analysis

The PIPELINE COMPLETE banner is produced by scent-miner.jar and carries no corpus counts; the CLI wrapper adds a correct stderr warning, but stdout (the artifact scripts and users capture) remains silent about the truncation.

#### Code Pointer

`cli/browser4-cli/src (webminer subcommand wrapper, which already computes the truncation warning) / scent-miner.jar (external, platonai/web-miner)`

#### AI Suggested Improvement

- Have the CLI append 'encoded N of M files (truncated; raise --max-files to process the rest)' to the stdout summary when it detects truncation
- Include the truncation fact in the exit-0 summary rather than only a stderr side channel

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Crawl headline 'N pages found' counts attempted URLs, not successful fetches

**Severity:** Low
**Category:** UX

#### Reproduction

./b4w.ps1 -s wm crawl --seed-file seeds/products-24.txt --depth 0 --refresh   # 13 of 24 pages fail
  First output lines: 'Crawling... 14 pages found (6s elapsed)' then 'Crawl completed. 24 pages found.'

#### Expected Behavior

A headline that reflects the outcome (e.g. 'Crawl completed with errors: 11 of 24 pages fetched').

#### Actual Behavior

The headline says '24 pages found'. The failures are only visible via per-URL warning lines, a later '⚠ 13 of 24 page(s) had fetch or extraction errors', and the Summary/Error lines (exit non-zero). The codebase even contains a comment acknowledging the confusion ('Without this warning, users see "Crawl completed. N pages found." and assume all succeeded'), yet the headline itself still counts attempts.

#### Root Cause Analysis

page_count counts returned page records (including empty/failed ones) rather than pages with content; the corrective warning is emitted later in the output instead of shaping the headline (cli/browser4-cli/src/main.rs ~line 20014).

#### Code Pointer

`cli/browser4-cli/src/main.rs (page_lines.push(format!("Crawl completed. {} page{} found."...)) ~line 20014)`

#### AI Suggested Improvement

- When failures > 0, change the headline itself (e.g. 'Crawl completed with errors: 11 of 24 pages fetched')
- Prefix failed rows with the ⚠ marker in the same line as the URL so a scan of the listing is enough

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 9: Main skill does not link to the sibling browser4-web-miner skill that contains the critical output/limits details

**Severity:** Low
**Category:** Discoverability

#### Reproduction

Read skills/browser4-cli/SKILL.md section 4d (WebMiner) and section 7 (Reference Map); then discover skills/browser4-web-miner/SKILL.md via `./b4w.ps1 skills`.

#### Expected Behavior

The main skill's WebMiner paragraph should point at the dedicated skill, which holds the full details: the two output locations, the warning that the real report is <project>.html and not index.html, the 'pipeline crashes on large corpora' free-tier limit, and the resume/offline tips.

#### Actual Behavior

SKILL.md section 4d only links to references/decision-trees.md. The sibling skill is discoverable only by listing bundled skills; a user following the WebMiner branch never learns that index.html is a directory listing and that <project>.html is the real report (verified: index.html is an auto-generated 'Index of predictionAndMinimalFeatures.views' page).

#### Root Cause Analysis

Missing cross-reference between the decision-layer skill and the procedure-tier sibling skill.

#### Code Pointer

`skills/browser4-cli/SKILL.md (section 4d and the section 7 reference map)`

#### AI Suggested Improvement

- Link browser4-web-miner from SKILL.md section 4d and from the reference map
- Cross-link back from the sibling skill to the CLI reference for acquisition commands (crawl/swarm/webdb export)

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 10: WebMiner auto-K produces cluster counts far exceeding page counts, with no explanation of the clustering unit

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 webminer all corpus-small   -> 'SMILE KMeans complete: k=59, silhouette=0.6941, pages=3, rows=145'
./b4w.ps1 webminer all corpus-bulk    -> 'k=65, silhouette=0.4554, pages=8, rows=478'
./b4w.ps1 webminer all corpus-large   -> 'k=330, silhouette=0.9142, pages=8, rows=2390'  (40 files encoded)

#### Expected Behavior

The docs say 'The cluster count is always auto-detected from the data - this produces better results than guessing a number', so the reported numbers should be interpretable; a user should be able to tell whether clustering was meaningful.

#### Actual Behavior

k is of the same order as the node (row) count and tens of times the page count (59 clusters for 3 pages); 'pages=8' is reported even when 40 files were encoded (content-deduplicated, apparently silently). Nothing in the CLI output or docs explains that KMeans clusters DOM nodes (not pages), nor how to read k/silhouette at these scales.

#### Root Cause Analysis

KMeans runs over feature rows (DOM nodes) in scent-miner.jar, while the summary labels the file count as 'pages' and the documentation describes the pipeline as grouping 'pages' into clusters; identical documents are deduplicated for this count without being announced.

#### Code Pointer

`scent-miner.jar (external; clustering stage) - documentation side: skills/browser4-web-miner/SKILL.md (How It Works / Options)`

#### AI Suggested Improvement

- Document the clustering unit explicitly (per-page feature rows vs per-page clusters) and how to read k/silhouette
- Label the reported counts unambiguously (e.g. 'documents=40, unique=8, rows=2390, k=330')
- Surface the docs' 'pages are grouped into clusters' wording so it cannot be read as page-level clustering

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

**Completion Status:** Successful - all five acceptance criteria were executed and verified end-to-end against the local MockSite and the locally built backend: AC3 (3 single-page exports), AC1 (free pipeline on the small corpus + views rebuild), AC4 (8-URL seed crawl, 8/8, plus webdb export staging), AC5 (swarm session + seed-file submission, 8/8 and 24/24 completed with correct X-SQL results), AC2 (1,200-page corpus demonstrating the free-tier truncation and the documented commercial Spark decision point). One major reliability incident (fetch-layer PrivacyException wedge) interrupted AC4 mid-session and was recovered by restarting the backend.

**Success Rate:** 95% - every scripted step produced the expected result; the losses were transient failures inside one crawl (13 of 24 pages) before the fetch layer was diagnosed and reset.

**Issues Found:** 10

**Major Blockers:** The fetch layer wedged mid-session: every fresh crawl fetch failed with 'PrivacyException / status 1601' while goto/htmlsnapshot still worked, and no CLI command recovered it - only ./b4w.ps1 stop (full backend restart) restored fetching. A second, related incident: the backend JVM died abruptly during a crawl, surfacing only as 'HTTP request failed: error sending request for url (...)'.

**Most Confusing Aspects:** 1) Where the WebMiner artifacts actually live - the encoded dataset and the interactive report go to %TEMP%\webminer-pereg\... while --output receives only the clustering results, and the browsable index.html is a directory listing rather than the report (<project>.html). 2) How crawled HTML gets from crawl/swarm into WebMiner - the skill says 'feed the HTML directory in' but never mentions that pages land in webdb and must be staged with webdb export (found only in the webdb reference). 3) The default --max-files 40 truncation that still reports PIPELINE COMPLETE. 4) Why swarm, the documented 'high-throughput' path, is measurably slower than plain crawl for the same URLs, and why its error text ('retry once the browser pool recovers') never comes true without a restart.

**Most Valuable Improvements:** 1) Make the privacy-context pool self-healing and give the CLI a way to reset it (and surface the condition in doctor/status) - this is the difference between 'flaky' and 'trustworthy' for the bulk-acquisition paths. 2) Put the critical WebMiner caveats (default truncation, temp-dir outputs, <project>.html vs index.html) into `webminer all --help` and print the truncation count in the stdout completion banner. 3) Add the crawl/swarm -> webdb export -> webminer staging recipe to SKILL.md section 4d, plus a concrete pointer for the commercial tier. 4) Fix or soften the swarm 'high throughput' guidance, or fix the serialized fresh-load path that makes it slower than crawl at small scale.

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

#### Issue 1: Fetch layer wedges: fresh crawl fetches fail with PrivacyException until the backend is restarted

1) ./b4w.ps1 -s wm swarm create --display-mode HEADLESS
2) ./b4w.ps1 -s wm crawl --seed-file seeds/products-24.txt --depth 0 --refresh   -> 'Crawl completed. 24 pages found.' but Summary: ok: 11, failed: 13 (PrivacyException)
3) ./b4w.ps1 -s wm crawl --seed-file seeds/unseen-6.txt --depth 0 --refresh     -> 4 of 6 fail, reproducible across runs
4) ./b4w.ps1 -s wm crawl "http://localhost:18080/ec/dp/B0HLT0001" --depth 0 --refresh  -> a brand-new URL fails alone
5) ./b4w.ps1 -s wm goto "http://localhost:18080/ec/dp/B0HLT0003"  -> still works (session browser is healthy)
6) ./b4w.ps1 swarm close  -> no effect;  ./b4w.ps1 stop  then retry -> fetching works again

#### Issue 2: Swarm fresh fetches appear serialized - ~3x slower than crawl for the same seed set

SAME 8-URL seed file, fresh pages (--refresh):
  time ./b4w.ps1 -s wm crawl --seed-file seeds/products.txt --depth 0 --refresh        -> ~9.9s, 8/8 ok
  time ./b4w.ps1 swarm query --seed-file seeds/products.txt --sql @product-query.sql --refresh --wait  -> ~26s for 8/8
  ./b4w.ps1 swarm list --limit 8   -> per-task durations ramp 6.9s, 8.3s, 10.9s, 13.5s, 16.0s, 18.6s, 22.7s, 25.3s (~2.6s stagger)
  (same pattern with 24 URLs: 0 failed, 113s, durations ramp 13.7s -> 1m52s)
  Control: ./b4w.ps1 swarm query --seed-file seeds/products-4.txt --sql @product-query.sql --wait -> cached hits complete in 9-13ms each, in parallel

#### Issue 3: Backend process died mid-crawl and the CLI only reported a raw HTTP transport error

After ./b4w.ps1 stop (which auto-restarts the backend on the next command):
  ./b4w.ps1 -s wm crawl --seed-file seeds/unseen-6.txt --depth 0 --refresh --format table
  -> pages 1..4 of 6 fetched OK, then: 'Error: HTTP request failed: error sending request for url (http://127.0.0.1:8282/api/crawl/b52978ba-.../result)'
  ./b4w.ps1 status -> 'The backend is not running'
  tail of the backend pulsar.log ends abruptly at 02:27:56 with no shutdown lines (log: browser4-apps/browser4-bundle/target/runtime-bundle/_work/.../logs/pulsar.log)

#### Issue 4: `webminer all --help` omits the two operation-critical caveats (default truncation; views land in the temp dir)

./b4w.ps1 --help webminer all

#### Issue 5: No documented route to the commercial Spark WebMiner tier (AC2 decision point has no next step)

Read skills/browser4-cli/SKILL.md section 4d, skills/browser4-cli/references/decision-trees.md (WebMiner tiers), skills/browser4-web-miner/SKILL.md; grep -ri 'commercial' across skills/ and README.md

#### Issue 6: `--help webminer all --examples` promises runnable examples but returns 'command maps to no single tool'

./b4w.ps1 --help webminer all
  -> ends with: 'Runnable examples: browser4-cli webminer all --help --examples.'
  ./b4w.ps1 --help webminer all --examples
  -> 'No examples available for 'webminer all' - the command maps to no single tool.'

#### Issue 7: `webminer all` prints 'PIPELINE COMPLETE' even when most of the corpus was dropped

./b4w.ps1 webminer all .test-sessions/<run>/corpus-large   # 1,200 HTML files, default --max-files 40

#### Issue 8: Crawl headline 'N pages found' counts attempted URLs, not successful fetches

./b4w.ps1 -s wm crawl --seed-file seeds/products-24.txt --depth 0 --refresh   # 13 of 24 pages fail
  First output lines: 'Crawling... 14 pages found (6s elapsed)' then 'Crawl completed. 24 pages found.'

#### Issue 9: Main skill does not link to the sibling browser4-web-miner skill that contains the critical output/limits details

Read skills/browser4-cli/SKILL.md section 4d (WebMiner) and section 7 (Reference Map); then discover skills/browser4-web-miner/SKILL.md via `./b4w.ps1 skills`.

#### Issue 10: WebMiner auto-K produces cluster counts far exceeding page counts, with no explanation of the clustering unit

./b4w.ps1 webminer all corpus-small   -> 'SMILE KMeans complete: k=59, silhouette=0.6941, pages=3, rows=145'
./b4w.ps1 webminer all corpus-bulk    -> 'k=65, silhouette=0.4554, pages=8, rows=478'
./b4w.ps1 webminer all corpus-large   -> 'k=330, silhouette=0.9142, pages=8, rows=2390'  (40 files encoded)

