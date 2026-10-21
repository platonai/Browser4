# Issues: bulk-scale-routing

> **Source:** `20261013-132610-bulk-scale-routing.issues.json` | **Date:** 20261013-132610 | **Mode:** dev

## Scenario Background

### Task

All six acceptance criteria were completed and verified against MockSite on localhost:18080:

| AC | Approach | Result |
|---|---|---|
| AC1 | `goto` listing page → `htmlsnapshot` → `htmlsnapshot query --sql @listing-query.sql --format table` with `DOM_LOAD_AND_SELECT(@url, '.product-card')` | ✅ 6 correlated rows (title + price + rating + link) from one page |
| AC2 | Seed file (3 product URLs) + `detail-query.sql` (`DOM_BASE_URI`, `#productTitle`, `#product-price`) → `crawl --seed-file ... --depth 0 --sql @... --format table --refresh` | ✅ 3/3 rows, one per seed URL, exit 0 |
| AC3 | `crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/"` | ✅ 10 product pages (depth 0–2) discovered; the 3 `category/*.html` links present on the hub were correctly excluded |
| AC4 | `swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4` → `swarm query --seed-file ... --refresh` → `swarm status` (batch) → `swarm result` → `swarm close` | ⚠️/✅ Initially blocked by **503 "Swarm backend is not installed"**; after repairing the local runtime bundle (see Issue 1) all 3 tasks completed in 864 ms with url/title/price verified, session closed |
| AC5 | `-s price-watch goto <product>` → `loop --name mock-price-watch --count 2 -i 10 -- -s price-watch eval "..."` | ✅ Both iterations returned `$899.99`; `loop --list`/`--status` observed live at 0/2 → 1/2; state auto-cleaned, exit 0 |
| AC6 | Bash loop over 3 product URLs calling `goto` + `htmlsnapshot` + `htmlsnapshot get text "#productTitle"` with `sleep 2` | ✅ 3/3 titles extracted; no crawl/swarm/loop needed |

### Execution Context

**Preparation:** Confirmed repo root, read `./b4w.ps1 help` and the full `skills/browser4-cli/SKILL.md`, plus `crawl.md`, `swarm.md`, `loop.md`, `decision-trees.md` (the §4b branch docs). MockSite was already running on :18080 (verified `/ec/sitemap.xml`, `/generated/crawl/index.html`).

**AC1:** `goto` auto-started the dev backend cleanly (~10 s, bundle pre-built). `htmlsnapshot` captured 9 KB / 22 interactive elements. `htmlsnapshot inspect` auto-discovered the repeating `div.product-card` pattern with `.product-title`/`.product-price`/`.product-rating` selectors — genuinely useful discovery output. Wrote `listing-query.sql` in the scratch dir and ran it; table output was clean.

**AC2:** Created `seed-urls.txt` + `detail-query.sql` in the scratch dir. Crawl completed in ~30 s (3 pages, ...

(truncated — see 20261013-132610-bulk-scale-routing.issues.json for the complete execution trace)

---

## Issues Found (6 issues)

### Issue 1: Dev-mode runtime bundle ships without the browser4-swarm plugin — all swarm job endpoints return 503

**Severity:** High
**Category:** Reliability

#### Reproduction

1. Fresh dev checkout + backend started via ./b4w.ps1 goto <url>.
2. ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4 --clear-stale  → prints 'Swarm session created: SWARM', exit 0.
3. ./b4w.ps1 swarm query --sql "@query.sql" --seed-file seed-urls.txt --refresh  → 'Error: HTTP request failed with status 503 Service Unavailable: Swarm backend is not installed. Install the browser4-swarm plugin and restart the server.' exit 1.
4. ./b4w.ps1 doctor status → Plugins: total 0.
Repair used: mvn install -pl browser4-plugins/browser4-swarm -DskipTests; ./b4w.ps1 stop; MAVEN_REPO_LOCAL='D:\Users\pereg\.m2\repository' BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 ./b4w.ps1 goto http://localhost:18080/ec/ → Plugins: total 1; swarm query then succeeds.

#### Expected Behavior

Swarm works out of the box in dev mode (AC4 requires it), or the CLI refuses/warns at the first swarm command with a concrete fix recipe.

#### Actual Behavior

The whole AC4 branch of SKILL.md §4b was dead on arrival: swarm create looked successful, swarm query 503'd. The bundle build's own warning ('browser4-swarm plugin JAR not found in the local Maven repo … will return 503 until the plugin is installed') was never surfaced in the CLI output — the rebuild log the user sees says only 'Local Browser4 runtime bundle built successfully'.

#### Root Cause Analysis

build-runtime-bundle.ps1 resolves the Maven local repository as `${MAVEN_REPO_LOCAL:-$HOME/.m2/repository}`. On this machine $HOME/C:\Users\pereg\.m2\settings.xml sets <localRepository>D:\Users\pereg\.m2\repository</localRepository>, so Maven (and `mvn install`) write to D:, while the bundle build scans C:\Users\pereg\.m2\repository, finds no browser4-swarm JAR, and copies nothing into the bundle's plugins/ directory. The backend then loads 0 plugins and the thin REST facade throws SwarmNotInstalledException (503).

#### Code Pointer

`browser4-apps/browser4-bundle/build-runtime-bundle.ps1 (the $localRepo/$swarmGroupDir block, ~lines 975-994)`

#### AI Suggested Improvement

- Resolve the local repository from Maven itself (mvn -q help:evaluate -Dexpression=settings.localRepository -DforceStdout) or parse settings.xml, instead of assuming ~/.m2/repository.
- Surface the missing-plugin Write-Warning to the CLI user (currently invisible), and/or have `doctor status` flag 'Plugins: total 0' as a swarm-affecting defect.
- Match the plugin artifact to the project version when copying (see separate issue) and fail the bundle assembly loudly when no match exists.
- Document the dev-mode plugin recipe (mvn install -pl browser4-plugins/browser4-swarm + forced bundle rebuild, incl. MAVEN_REPO_LOCAL for redirected repos) in swarm.md and SKILL.md.
- Consider building the plugin as part of the same reactor/bundle assembly so repo-location mismatches cannot drop it silently.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Runtime bundle picks the browser4-swarm JAR by newest file mtime, bundling a version-mismatched plugin (rc.8 into an rc.10 backend)

**Severity:** Medium
**Category:** Product

#### Reproduction

With several browser4-swarm-4.14.0-rc.* versions present in the Maven repo, force a bundle rebuild (BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 after ./b4w.ps1 stop), then list browser4-apps/browser4-bundle/target/runtime-bundle/_work/.../plugins/.

#### Expected Behavior

The bundled plugin matches the checkout version (4.14.0-rc.10, per VERSION/pom and doctor's reported backend build).

#### Actual Behavior

browser4-swarm-4.14.0-rc.8.jar was bundled (its file mtime was ~8 minutes newer than the rc.10 JAR in the same repo). It happened to load and work, but the backend now runs a plugin from a different release train.

#### Root Cause Analysis

build-runtime-bundle.ps1 (~line 985) does Get-ChildItem browser4-swarm-*.jar | Sort-Object LastWriteTime -Descending | Select-Object -First 1 — 'newest file wins' with no filter against the project version.

#### Code Pointer

`browser4-apps/browser4-bundle/build-runtime-bundle.ps1:985`

#### AI Suggested Improvement

- Filter candidates by the project version (read VERSION / project.version) before falling back to newest-mtime.
- When only mismatched versions exist, print an explicit warning naming the versions, or fail the bundle build.
- Record the bundled plugin version in doctor's Plugins report so skew is visible.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: `swarm create` reports success (and doctor shows an active SWARM session) even when the swarm backend is not installed

**Severity:** Medium
**Category:** Reliability

#### Reproduction

On a backend without the plugin: ./b4w.ps1 swarm create --display-mode HEADLESS → 'Swarm session created: SWARM (display=HEADLESS, contexts=2, max-tabs=4)', exit 0. Then any ./b4w.ps1 swarm query … → 503. doctor status simultaneously reports '-- Swarm -- session: SWARM (status: active), tasks: total=2 done=2 running=0'.

#### Expected Behavior

swarm create should detect the missing swarm backend and fail or warn immediately (the session is useless — every job will 503), or at minimum not present a healthy-looking active swarm state.

#### Actual Behavior

False-positive success; the user only discovers the problem one step later when a job submission 503s. Wasted a session teardown + investigation cycle.

#### Root Cause Analysis

`swarm create` only creates/attaches a browser session named SWARM (Rust CLI, main.rs ~13868) — it never consults the plugin-backed REST facade. Job submission is the first call that reaches SwarmController, which throws SwarmNotInstalledException. The doctor Swarm section appears to report session/task state from local tracking, so it also looks healthy without the backend.

#### Code Pointer

`cli/browser4-cli/src/main.rs:13868 (swarm create handler); browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/SwarmController.kt`

#### AI Suggested Improvement

- Have swarm create probe the backend (e.g. GET /api/swarm/status) and fail fast with the 'not installed' message and remediation.
- Make doctor status mark the Swarm section as unavailable when the plugin is absent instead of showing an active session + task counts.
- Reuse one shared error path so the 503 message always includes the dev-mode install/rebuild recipe.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: `list` shows URL '-' for active sessions with live pages although the backend is reachable

**Severity:** Medium
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s price-watch goto http://localhost:18080/ec/dp/B0E000001; then ./b4w.ps1 list --verbose (or --json). Sessions show URL '-', 'backend_reachable': true, Status Active, Next open Reuse. ./b4w.ps1 -s price-watch page-info immediately shows 'URL: http://localhost:18080/ec/dp/B0E000001'.

#### Expected Behavior

Per SKILL.md §2, `list` shows every session's current page URL (the URL comes 'from the backend', truncated to 48 chars); '-' is documented to mean 'the backend is unreachable or the saved session is stale'.

#### Actual Behavior

Both live sessions (price-watch + default, both used seconds earlier) render '-' in the URL column and in --json, while the backend is reachable and page-info reports real URLs. The documented meaning of '-' actively misleads: a user checking session state would conclude the sessions are stale.

#### Root Cause Analysis

The URL is taken from the backend's persisted session record (main.rs ~4959: 'Current page URL from the backend record; "-" when unavailable (backend unreachable, older backend, or a stale local session)'). In this build that record is evidently never populated/updated — sessions navigated via goto keep an empty URL — and the CLI silently degrades to the same '-' used for the unreachable case.

#### Code Pointer

`cli/browser4-cli/src/main.rs:handle_list() (~line 5180) with session URL resolution ~4915-4960; backend session registry DTO in browser4-rest`

#### AI Suggested Improvement

- Populate/update the backend session record on navigation, or have `list` resolve the URL from the live tab (page-info path) for active sessions.
- If the URL truly cannot be resolved, use a distinct marker (e.g. '?') and a stderr note instead of reusing the documented unreachable/stale '-'.
- Add an e2e test asserting a session that just navigated shows its URL in `list --json`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 is a silent no-op while the server is already running

**Severity:** Low
**Category:** UX

#### Reproduction

With the backend already running: MAVEN_REPO_LOCAL='D:\Users\pereg\.m2\repository' BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 ./b4w.ps1 goto http://localhost:18080/ec/ → normal command output, no rebuild, no message. After ./b4w.ps1 stop, the same env var triggers 'Assembling Browser4 runtime bundle …'.

#### Expected Behavior

Either rebuild (stop/rebuild/restart) or an explicit notice that the forced rebuild was skipped because the server is already running, and how to do it.

#### Actual Behavior

The flag is silently ignored; the user believes a rebuild happened when nothing changed (this cost a diagnostic cycle during the plugin fix).

#### Root Cause Analysis

The force-rebuild path lives inside the server-start branch (daemon.rs should_force_rebuild_bundle / FORCE_REBUILD_BUNDLE_ENV, ~lines 2558-2570) which is never reached when a healthy managed server already exists; there is no else-branch reporting the skip.

#### Code Pointer

`cli/browser4-cli/src/daemon.rs:2558 (should_force_rebuild_bundle and its call site)`

#### AI Suggested Improvement

- When the env var is set and a server is already running, print 'ignoring BROWSER4_CLI_FORCE_REBUILD_BUNDLE: server already running; run `browser4-cli stop` first'.
- Or auto-restart: stop the server, rebuild, start, and say so.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Git Bash: the documented `-olp "/product/"` example fails on the MSYS path-conversion guard; examples don't show the required env prefix

**Severity:** Low
**Category:** Documentation

#### Reproduction

From Git Bash: ./b4w.ps1 crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/" → 'Error: argument C:/Program Files/Git/product/ … was rewritten by Git Bash's MSYS path conversion', exit 1. Works as MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 … (or with -olp "product/").

#### Expected Behavior

The scenario/crawl.md example runs as written from Git Bash, or the example shows the required invocation.

#### Actual Behavior

Fast, accurate failure (the guard is correct and even names both workarounds), but the copy-paste example in the docs and scenario doesn't work unmodified in the reference Git Bash environment — a first-time user hits a wall on step one of AC3.

#### Root Cause Analysis

Git Bash rewrites leading-'/' argument values into Windows paths before pwsh starts; the original token is unrecoverable, so b4w.ps1 refuses (by design) while crawl.md documents the pattern with a leading '/'.

#### Code Pointer

`b4w.ps1 (MSYS mangled-argument guard, ~lines 86-144); skills/browser4-cli/references/crawl.md (Git Bash caveat + link-discovery examples)`

#### AI Suggested Improvement

- Change Git Bash examples to `-olp "product/"` (no leading '/') or prefix them with MSYS2_ARG_CONV_EXCL='*'.
- Mention the workaround next to the quick-start crawl examples, not only in the caveat block at the bottom of crawl.md.

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

**Completion Status:** Successful — all six acceptance criteria completed and verified against MockSite (AC4 only after repairing the local dev-mode runtime bundle).

**Success Rate:** 90% — every AC passed; AC4 required an out-of-band bundle repair, and the exact AC3 command needed the documented MSYS workaround.

**Issues Found:** 6

**Major Blockers:** AC4/swarm was completely blocked by the dev runtime bundle shipping without the browser4-swarm plugin (every /api/swarm job call returned 503). Root-caused to build-runtime-bundle.ps1 not honoring Maven's configured localRepository (settings.xml redirects it to D:\Users\pereg\.m2 while the script scans C:\Users\pereg\.m2). Repaired with mvn install of the plugin + a forced bundle rebuild with MAVEN_REPO_LOCAL set; swarm then worked end-to-end.

**Most Confusing Aspects:** 1) `swarm create` reporting success and doctor showing an active SWARM session while the swarm backend was entirely absent — the failure surfaced only on job submission. 2) The forced-rebuild env var silently doing nothing until the server is stopped. 3) `list` showing '-' URLs (documented as 'backend unreachable / stale session') for sessions that were verifiably live. 4) Discovering that the local Maven repo is redirected to D: and that the bundle script assumes ~/.m2. 5) The AC3 example pattern needing an env-var prefix under Git Bash.

**Most Valuable Improvements:** Fix the bundle build to resolve Maven's real local repository and to version-match the swarm plugin (and surface the missing-plugin warning to the user); make swarm create/doctor report a missing swarm backend immediately with the dev-mode fix recipe; make `list` show real URLs for active sessions; make BROWSER4_CLI_FORCE_REBUILD_BUNDLE warn instead of silently skipping; correct the Git Bash crawl examples. Core flows (goto, snapshot/inspect, X-SQL query, seed-file crawl, link-discovery crawl, loop, and the few-URL shell pattern) all worked first try and matched the documentation — the discoverability tooling (`htmlsnapshot inspect`) and error messages were notably good.

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

#### Issue 1: Dev-mode runtime bundle ships without the browser4-swarm plugin — all swarm job endpoints return 503

1. Fresh dev checkout + backend started via ./b4w.ps1 goto <url>.
2. ./b4w.ps1 swarm create --display-mode HEADLESS --max-browser-contexts 2 --max-open-tabs 4 --clear-stale  → prints 'Swarm session created: SWARM', exit 0.
3. ./b4w.ps1 swarm query --sql "@query.sql" --seed-file seed-urls.txt --refresh  → 'Error: HTTP request failed with status 503 Service Unavailable: Swarm backend is not installed. Install the browser4-swarm plugin and restart the server.' exit 1.
4. ./b4w.ps1 doctor status → Plugins: total 0.
Repair used: mvn install -pl browser4-plugins/browser4-swarm -DskipTests; ./b4w.ps1 stop; MAVEN_REPO_LOCAL='D:\Users\pereg\.m2\repository' BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 ./b4w.ps1 goto http://localhost:18080/ec/ → Plugins: total 1; swarm query then succeeds.

#### Issue 2: Runtime bundle picks the browser4-swarm JAR by newest file mtime, bundling a version-mismatched plugin (rc.8 into an rc.10 backend)

With several browser4-swarm-4.14.0-rc.* versions present in the Maven repo, force a bundle rebuild (BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 after ./b4w.ps1 stop), then list browser4-apps/browser4-bundle/target/runtime-bundle/_work/.../plugins/.

#### Issue 3: `swarm create` reports success (and doctor shows an active SWARM session) even when the swarm backend is not installed

On a backend without the plugin: ./b4w.ps1 swarm create --display-mode HEADLESS → 'Swarm session created: SWARM (display=HEADLESS, contexts=2, max-tabs=4)', exit 0. Then any ./b4w.ps1 swarm query … → 503. doctor status simultaneously reports '-- Swarm -- session: SWARM (status: active), tasks: total=2 done=2 running=0'.

#### Issue 4: `list` shows URL '-' for active sessions with live pages although the backend is reachable

./b4w.ps1 -s price-watch goto http://localhost:18080/ec/dp/B0E000001; then ./b4w.ps1 list --verbose (or --json). Sessions show URL '-', 'backend_reachable': true, Status Active, Next open Reuse. ./b4w.ps1 -s price-watch page-info immediately shows 'URL: http://localhost:18080/ec/dp/B0E000001'.

#### Issue 5: BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 is a silent no-op while the server is already running

With the backend already running: MAVEN_REPO_LOCAL='D:\Users\pereg\.m2\repository' BROWSER4_CLI_FORCE_REBUILD_BUNDLE=1 ./b4w.ps1 goto http://localhost:18080/ec/ → normal command output, no rebuild, no message. After ./b4w.ps1 stop, the same env var triggers 'Assembling Browser4 runtime bundle …'.

#### Issue 6: Git Bash: the documented `-olp "/product/"` example fails on the MSYS path-conversion guard; examples don't show the required env prefix

From Git Bash: ./b4w.ps1 crawl http://localhost:18080/generated/crawl/index.html -d 2 -ol "a.product" -olp "/product/" → 'Error: argument C:/Program Files/Git/product/ … was rewritten by Git Bash's MSYS path conversion', exit 1. Works as MSYS2_ARG_CONV_EXCL='*' ./b4w.ps1 … (or with -olp "product/").

