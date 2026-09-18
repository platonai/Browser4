# Issues: webminer-structuring-routing

> **Source:** `20260831-210954-webminer-structuring-routing.issues.json` | **Date:** 20260831-210954 | **Mode:** dev

## Scenario Background

### Task

**All 5 acceptance criteria completed successfully** (95% success rate):

| AC | Result |
|---|---|
| **AC3** — single-page acquisition | 3 MockSite product pages (`/ec/dp/B0E000001..003`) captured via `goto` → `htmlsnapshot` → `htmlsnapshot export --file` → `.test-sessions/corpus-single/*.html` (3 files, verified full product HTML) |
| **AC1** — free WebMiner pipeline | `webminer all` completed encode → cluster → views: 3 docs/205 nodes → 69-dim CSV, SMILE KMeans k=72 (silhouette 0.6243), interactive views (index.html, .all.html, .perfect.html, .qualified.html, .xlsx, .json). Also ran on the 6-file bulk corpus (k=71) and verified `webminer views` rebuild |
| **AC4** — bulk known-URL acquisition | `crawl --seed-file product-seeds.txt --depth 0 --refresh` → 6/6 pages fetched in 156 s; `webdb export` staged 6 `.htm` files into `.test-sessions/corpus-bulk/` |
| **AC5** — swarm high-throughput | `swarm create --display-mode HEADLESS --clear-stale` → `swarm query --seed-file ... --sql @product-query.sql --refresh` → 6/6 jobs in ~75 s; `swarm result` returned `{"title":"4K OLED TV 55","price":"$899.99"}` + `pageContentBytes: 15292` |
| **AC2** — production decision point | Documented: 1,200-page daily export → Spark commercial tier (>1,000 free-tier ceiling); acquisition patterns unchanged; written to `.test-sessions/ac2-production-scale-decision.md` |

**One blocker hit and worked around:** the documented bare `webminer install` crashed with a Rust stack overflow in **9 of 10 fresh-state attempts**; `webminer install v0.0.8` (explicit version) succeeded 5/5.

### Execution Context

**Prep:** verified cwd, `mkdir -p .test-sessions`, MockSite confirmed (HTTP 200), ran `./b4w.ps1 help`, read SKILL.md + decision-trees.md (§4d) + crawl.md + swarm.md + webdb.md + AGENTS.md.

**Diagnosis of the install crash** (key findings): bare `install` crash output was only `thread 'main' (PID) has overflowed its stack`; `RUST_BACKTRACE=full` produced nothing; running via `cargo run` directly revealed `error: process didn't exit successfully: browser4-cli.exe webminer install (exit code: 0xc00000fd, STATUS_STACK_OVERFLOW)` — a genuine CLI stack overflow that b4w.ps1 masks as exit 0. The crash never occurred with an explicit version, with `webminer version`, or in the already-installed early-return path — so it sits in the bare-install download/checksum branch.

**Steps:** goto+htmlsnap...

(truncated — see 20260831-210954-webminer-structuring-routing.issues.json for the complete execution trace)

---

## Issues Found (8 issues)

### Issue 1: webminer install (bare) crashes with Rust stack overflow (STATUS_STACK_OVERFLOW)

**Severity:** High
**Category:** Reliability

#### Reproduction

Uninstall webminer (./b4w.ps1 webminer uninstall), then run ./b4w.ps1 webminer install. In this session: 9 of 10 fresh-state attempts crashed. Crash output: "thread 'main' (PID) has overflowed its stack" and nothing else; cargo reports exit code 0xc00000fd (STATUS_STACK_OVERFLOW). webminer install v0.0.8 (explicit version) always succeeded (5/5), as did webminer version and bare install when already installed.

#### Expected Behavior

webminer install downloads and verifies scent-miner.jar (v0.0.8) and reports '✓ webminer v0.0.8 installed to C:\Users\pereg\.scent\webminer'.

#### Actual Behavior

The CLI process crashes with a stack overflow in the main thread before printing any [webminer] progress output; nothing is installed; the wrapper still reports exit code 0. The documented first step of the WebMiner workflow (SKILL.md §4d, decision-trees.md) is therefore unusable as written.

#### Root Cause Analysis

Unconfirmed. The crash is a genuine Rust stack overflow in the CLI binary (debug build via cargo run), intermittent but heavily correlated with the fresh-state bare-install path (fetch_latest_release -> install_release with download). The 'already installed' early-return path through the same fetch_latest_release does not crash, so the fault is likely in the download/checksum branch or is response/timing-dependent (GitHub API payload variance). No backtrace is produced (Rust stack-overflow handler aborts; RUST_BACKTRACE=full has no effect). Suggested follow-up: run under a debugger (or a release build with backtraces), or bisect by stubbing fetch_latest_release / install_release to find the recursing call; check whether a release build reproduces it at all.

#### Code Pointer

`cli/browser4-cli/src/webminer.rs:install() / fetch_latest_release() / install_release()`

#### AI Suggested Improvement

- Add a catch: run the install flow on a thread with a large explicit stack (e.g. std::thread::Builder::stack_size) so the crash cannot occur, and/or build the CLI with a larger main-thread stack on Windows.
- Instrument the install path to print a progress line before each phase so a future crash is localizable.
- Investigate whether the dev-mode debug build (cargo run) is the only affected configuration; if so, document it and fix the recursion regardless.
- Consider catching/printing a backtrace on stack overflow via an alternate signal/exception handler on Windows (SetUnhandledExceptionFilter) to surface the faulting frame.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 2: b4w.ps1 masks CLI crash exit codes as 0 (silent failure)

**Severity:** Medium
**Category:** Reliability

#### Reproduction

Run ./b4w.ps1 webminer install in the crashing state; then echo $?. The crash prints 'thread 'main' has overflowed its stack' but the exit code is 0. Running the same command via cargo run directly reports 'error: process didn't exit successfully ... exit code: 0xc00000fd' (failure is visible there).

#### Expected Behavior

A crashed CLI invocation should propagate a non-zero exit code so scripts and CI detect the failure.

#### Actual Behavior

b4w.ps1 exits 0 even when the underlying CLI process aborts with STATUS_STACK_OVERFLOW, making the failure silent to automation; combined with the missing error output, the first-time user believes the install succeeded.

#### Root Cause Analysis

The abort code 0xc00000fd from the child is lost across the pwsh cargo-run / Invoke-Expression boundary before `exit $LASTEXITCODE` runs (PowerShell may normalize or drop the NTSTATUS abort code). Needs verification of the exact $LASTEXITCODE value at the b4w.ps1 exit path.

#### Code Pointer

`b4w.ps1 (cargo run / Invoke-Expression branch, `exit $LASTEXITCODE`)`

#### AI Suggested Improvement

- Explicitly test $LASTEXITCODE against known abort codes and map any non-zero NTSTATUS to a conventional non-zero exit (e.g. 1 or 101).
- Add a final 'last command failed' check before `exit` in the run branch.
- Consider detecting the 'has overflowed its stack' stderr marker and failing loudly.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 3: WebMiner decision tree omits the webdb export bridging step (crawl output is not an HTML directory)

**Severity:** Medium
**Category:** Documentation

#### Reproduction

Follow SKILL.md §4d / references/decision-trees.md 'Need to acquire pages first? → Bulk download: browser4-cli crawl --seed-file urls.txt --depth 0 … Then feed the HTML directory to WebMiner'. After the crawl, no HTML directory exists on disk — crawl stores pages in the webdb cache and prints only titles.

#### Expected Behavior

The decision tree should name the bridge command (webdb export <urls> <output-dir>) between crawl/swarm acquisition and `webminer all <html-dir>`, since it is required to materialize an HTML directory.

#### Actual Behavior

A first-time user cannot complete the documented flow: `webminer all <html-dir>` requires a directory of HTML files, and crawl leaves none. The bridge is documented only in references/webdb.md (not linked from the decision tree). I discovered it by reading the reference map.

#### Root Cause Analysis

The decision tree compresses the pipeline and omits the cache→disk export step; webdb.md is not referenced in the WebMiner section of decision-trees.md.

#### Code Pointer

`skills/browser4-cli/references/decision-trees.md (WebMiner tiers section)`

#### AI Suggested Improvement

- Add `webdb export <comma-separated-urls> <out-dir>` to the 'Need to acquire pages first?' branch and to SKILL.md §4d.
- Add a one-line note in crawl.md/swarm.md that fetched pages live in the webdb cache and must be exported for offline pipelines.
- Consider adding `crawl --output-dir` style support so crawl can write HTML directly (product enhancement).

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 4: Broken documentation link: web-miner/SKILL.md does not exist in the repository

**Severity:** Low
**Category:** Documentation

#### Reproduction

In references/decision-trees.md, click 'See [web-miner/SKILL.md](../../browser4-web-miner/SKILL.md) for the full reference' (WebMiner tiers section). The target file/path browser4-web-miner/SKILL.md does not exist anywhere in the checkout (glob **/web-miner/SKILL.md returns nothing).

#### Expected Behavior

The referenced full WebMiner reference should exist in the repo (or the link should point to a real location).

#### Actual Behavior

404 for the linked skill file; the only in-repo WebMiner documentation is decision-trees.md, the CLI help, and the CLI source comments.

#### Root Cause Analysis

The browser4-web-miner skill directory is not part of this repository/checkout while decision-trees.md still links to it.

#### Code Pointer

`skills/browser4-cli/references/decision-trees.md (WebMiner tiers section)`

#### AI Suggested Improvement

- Point the link to the shipped `webminer` CLI help (`browser4-cli webminer` bare status view) or the web-miner GitHub releases page.
- Add the missing browser4-web-miner/SKILL.md to the repo or remove the link.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 5: webminer all pipeline banner 'Output' path is misleading: CSV and views land in %TEMP%, only KMeans goes to the stated output dir

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 webminer all .test-sessions/corpus-single. The banner states 'Output : .test-sessions\corpus-single-ml-output', but the pipeline summary shows the encoded CSV at %TEMP%\webminer-pereg\ml\dataset\predict\... and Views at %TEMP%\webminer-pereg\ml\tasks\unsupervised\result\... — only 'KMeans output' lands in the stated -ml-output directory.

#### Expected Behavior

The banner 'Output' should describe all artifact locations (or the artifacts should be written under the stated output directory).

#### Actual Behavior

A user hunting for 'the outputs' in the stated directory finds only the KMeans result CSVs; the encoded dataset and interactive views live in the temp task-output tree (documented only in the CLI source comment about -Dapp.name).

#### Root Cause Analysis

webminer's JAR writes CSV/views into the task-output root (%TEMP%/<app>-pereg/ml/...) while the CLI passes the output dir only for the clustering result; the banner prints only the KMeans location.

#### Code Pointer

`cli/browser4-cli/src/webminer.rs:run_pipeline() (and the external scent-miner.jar)`

#### AI Suggested Improvement

- Update the pipeline banner/summary to show the CSV and views temp paths up front (they are printed at the end, but the banner is what users read).
- Consider documenting the %TEMP%/webminer-pereg task-output location in decision-trees.md or the webminer help.
- Optionally add a --output flag to webminer all that relocates all artifacts into the output dir.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 6: Every webminer JVM launch prints a spurious warning about sun.security.action

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 webminer all <dir> or ./b4w.ps1 webminer views <dir> on JDK 25. The first line of output is: 'WARNING: package sun.security.action not in java.base'.

#### Expected Behavior

Clean startup output; the CLI launches the JAR with only valid --add-opens flags for the detected JVM.

#### Actual Behavior

Every run starts with a JVM warning that looks like an error to a first-time user; it appears even on otherwise successful runs.

#### Root Cause Analysis

MODULE_OPENS in webminer.rs includes '--add-opens=java.base/sun.security.action=ALL-UNNAMED', but on newer JDKs (e.g. 25) that package no longer lives in java.base (it moved to java.security.jgss), so the JVM warns. The flag list appears to be a fixed set copied from the old launcher without JVM-version awareness.

#### Code Pointer

`cli/browser4-cli/src/webminer.rs:MODULE_OPENS`

#### AI Suggested Improvement

- Detect the Java version (or probe with `java --list-modules`) and omit --add-opens flags whose target package does not exist.
- At minimum, drop the stale sun.security.action entry from the fixed list if the stack no longer needs it, or split the list per JDK major version.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 7: webdb export mixes unrelated page/snapshot output into the JSON result

**Severity:** Low
**Category:** UX

#### Reproduction

Run: ./b4w.ps1 webdb export "http://localhost:18080/ec/dp/B0E000001,...,B0E000006" .test-sessions/corpus-bulk. Output contains the JSON envelope ({"total":6,"succeeded":6,...}) immediately followed by an unrelated '### Page' + '### Snapshot' + tip block for one of the URLs (B0E000003).

#### Expected Behavior

The export command's stdout should contain only the export result (JSON or a clean summary).

#### Actual Behavior

A human-oriented navigation block (auto-goto side effect used for server-side URL normalization/redirect resolution) leaks into stdout after the JSON, breaking clean machine consumption and confusing the user.

#### Root Cause Analysis

webdb export (or its normalization step) triggers a goto/session interaction whose automatic post-navigation snapshot output is written to stdout instead of being suppressed or routed to stderr.

#### Code Pointer

`cli/browser4-cli/src/main.rs (webdb-export handler / automatic snapshot after navigation)`

#### AI Suggested Improvement

- Suppress the automatic '### Page/Snapshot/Tip' block when the command that triggered navigation is not an interactive navigation command.
- Route side-effect navigation output to stderr, or emit it only with --show-tip/verbose.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

### Issue 8: crawl pacing on a local site: 6 pages took 156 s with no per-URL progress detail

**Severity:** Low
**Category:** UX

#### Reproduction

Run ./b4w.ps1 crawl --seed-file .test-sessions/seed/product-seeds.txt --depth 0 --refresh against localhost:18080 (6 MockSite pages, one already cached). Observed progress: pages 1-3 'found' immediately, page 4 completed at ~126 s, page 5 at ~136 s, page 6 at ~146-156 s — roughly 10 s of apparent delay per newly fetched page.

#### Expected Behavior

For a local mock site, a 6-URL bulk fetch should complete in a few seconds; progress output should show which URL is being fetched and why delays occur.

#### Actual Behavior

The crawl completes (correctly) but takes 156 s and the progress lines just repeat '3 pages found so far' every 10 s without indicating which URL is being fetched or that a rate-limit delay applies.

#### Root Cause Analysis

Likely the built-in polite-scraping rate limiting between page loads (documented as 1-3 s guidance in crawl.md) combined with fetch/parse time, or a fixed ~10 s poll/backoff in the crawl worker; not confirmed — needs backend timing measurement. The progress output hides the wait reason.

#### Code Pointer

`cli/browser4-cli/src/main.rs (crawl progress reporting) / browser4-rest crawl worker`

#### AI Suggested Improvement

- Show the current URL and elapsed time per page in progress lines (e.g. 'fetching B0E000004 ... (3/6, 34s elapsed)').
- Make the inter-page delay configurable (e.g. crawl --page-delay) and document the default for local/offline targets.

#### Human Review

- [ ] **ACCEPT**
- [ ] **ACCEPT with improvements**
- [ ] **DEFER**
- [ ] **WONTFIX**
- [ ] **REJECT**
- **Notes:**

---

## Overall Assessment

**Completion Status:** Successful - all 5 acceptance criteria (AC1-AC5) completed; the free WebMiner pipeline, crawl bulk acquisition, swarm high-throughput acquisition, and the production-scale decision point were all verified with real outputs. One blocker (bare webminer install crash) was worked around with the explicit-version install.

**Success Rate:** 95% - every scenario step succeeded; the only failure was the intermittent bare `webminer install` crash (9/10 fresh-state attempts), recovered via `webminer install v0.0.8`.

**Issues Found:** 8

**Major Blockers:** None persistent: the documented `webminer install` (bare) command crashed with a Rust stack overflow in ~90% of fresh-state attempts (0xc00000fd, no error output, exit code masked as 0 by the wrapper). Worked around with `webminer install v0.0.8` (5/5 success). This is the top fix priority.

**Most Confusing Aspects:** 1) `webminer install` crashing silently (crash message + exit 0 + nothing installed). 2) The WebMiner decision tree tells you to 'feed the HTML directory' to webminer after crawl, but crawl produces no HTML directory - the webdb export bridge is hidden in an unreferenced doc. 3) The pipeline banner 'Output' path does not contain the CSV/views artifacts (they land in %TEMP%). 4) The spurious JVM warning on every webminer run looks like a startup failure.

**Most Valuable Improvements:** 1) Fix/isolate the webminer install stack overflow (or print a clear error + non-zero exit). 2) Add the webdb export bridge to the WebMiner decision tree / SKILL.md §4d. 3) Make b4w.ps1 propagate crash exit codes. 4) Fix the misleading 'Output' banner and the stale --add-opens JVM flag.

**Usability Rating:** 6/10

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

#### Issue 1: webminer install (bare) crashes with Rust stack overflow (STATUS_STACK_OVERFLOW)

Uninstall webminer (./b4w.ps1 webminer uninstall), then run ./b4w.ps1 webminer install. In this session: 9 of 10 fresh-state attempts crashed. Crash output: "thread 'main' (PID) has overflowed its stack" and nothing else; cargo reports exit code 0xc00000fd (STATUS_STACK_OVERFLOW). webminer install v0.0.8 (explicit version) always succeeded (5/5), as did webminer version and bare install when already installed.

#### Issue 2: b4w.ps1 masks CLI crash exit codes as 0 (silent failure)

Run ./b4w.ps1 webminer install in the crashing state; then echo $?. The crash prints 'thread 'main' has overflowed its stack' but the exit code is 0. Running the same command via cargo run directly reports 'error: process didn't exit successfully ... exit code: 0xc00000fd' (failure is visible there).

#### Issue 3: WebMiner decision tree omits the webdb export bridging step (crawl output is not an HTML directory)

Follow SKILL.md §4d / references/decision-trees.md 'Need to acquire pages first? → Bulk download: browser4-cli crawl --seed-file urls.txt --depth 0 … Then feed the HTML directory to WebMiner'. After the crawl, no HTML directory exists on disk — crawl stores pages in the webdb cache and prints only titles.

#### Issue 4: Broken documentation link: web-miner/SKILL.md does not exist in the repository

In references/decision-trees.md, click 'See [web-miner/SKILL.md](../../browser4-web-miner/SKILL.md) for the full reference' (WebMiner tiers section). The target file/path browser4-web-miner/SKILL.md does not exist anywhere in the checkout (glob **/web-miner/SKILL.md returns nothing).

#### Issue 5: webminer all pipeline banner 'Output' path is misleading: CSV and views land in %TEMP%, only KMeans goes to the stated output dir

Run ./b4w.ps1 webminer all .test-sessions/corpus-single. The banner states 'Output : .test-sessions\corpus-single-ml-output', but the pipeline summary shows the encoded CSV at %TEMP%\webminer-pereg\ml\dataset\predict\... and Views at %TEMP%\webminer-pereg\ml\tasks\unsupervised\result\... — only 'KMeans output' lands in the stated -ml-output directory.

#### Issue 6: Every webminer JVM launch prints a spurious warning about sun.security.action

Run ./b4w.ps1 webminer all <dir> or ./b4w.ps1 webminer views <dir> on JDK 25. The first line of output is: 'WARNING: package sun.security.action not in java.base'.

#### Issue 7: webdb export mixes unrelated page/snapshot output into the JSON result

Run: ./b4w.ps1 webdb export "http://localhost:18080/ec/dp/B0E000001,...,B0E000006" .test-sessions/corpus-bulk. Output contains the JSON envelope ({"total":6,"succeeded":6,...}) immediately followed by an unrelated '### Page' + '### Snapshot' + tip block for one of the URLs (B0E000003).

#### Issue 8: crawl pacing on a local site: 6 pages took 156 s with no per-URL progress detail

Run ./b4w.ps1 crawl --seed-file .test-sessions/seed/product-seeds.txt --depth 0 --refresh against localhost:18080 (6 MockSite pages, one already cached). Observed progress: pages 1-3 'found' immediately, page 4 completed at ~126 s, page 5 at ~136 s, page 6 at ~146-156 s — roughly 10 s of apparent delay per newly fetched page.

