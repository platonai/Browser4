# Issues: crawl-link-options

> **Source:** `20260726-205705-crawl-link-options.full.md` | **Date:** 20260726-205705 | **Mode:** dev

## Scenario Background

### Task

**Partial completion.** Two of four acceptance criteria were successfully verified after fixing critical bugs in the codebase:

| AC | Description | Status | Notes |
|---|---|---|---|
| AC1 | Basic crawl (depth 0) | ✅ PASS | 1 page found, no link discovery |
| AC2 | Link selector + pattern (depth 2) | ❌ FAIL | `crawlDepthN` requires `TaskLoops` bean (missing); depth 1 alternative finds 0 links due to selector/LoadDocument mismatch |
| AC3 | Deep crawl (depth 3) | ❌ FAIL | Same `TaskLoops` issue as AC2 |
| AC4 | Seed file crawl (depth 0) | ✅ PASS | 2 pages found (both seed URLs), after fixing `CrawlToolExecutor` to pass `urls` parameter |

**Three code bugs were identified and fixed during testing:**

1. **`Browser4AutoConfiguration` missing from auto-config imports** — Added to `browser4-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports`
2. **`CrawlToolExecutor.submit()` ignoring `urls` parameter** — Added `paramStringList(args, "urls", ...)` call in `browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/CrawlToolExecutor.kt`
3. **`crawlDepthN` using `PulsarSettings.withSequentialBrowsers()` without graceful fallback** — Added try/catch in `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt` (partial fix — the deeper `session.submit()` path also requires `TaskLoops`)

### Execution Context

**Key Commands:**

- **Major steps:** Read SKILL.md and crawl.md documentation; verified mock site structure; ran AC1 (succeeded); attempted AC2 (failed — version mismatch between CLI 4.12.0 and installed backend v4.11.15); debugged and fixed backend startup (local bundle vs installed bundle); identified and fixed three code bugs; ran AC4 (succeeded after fix); attempted AC2/AC3 (failed — TaskLoops bean unavailable at the session.submit level)
- **Workarounds:** Manually started backend from local runtime bundle; copied rebuilt JARs into bundle lib directory; used `--depth 1` as alternative to `--depth 2`
- **Key decisions:** Switched from installed v4.11.15 to locally-built v4.12.0-SNAPSHOT backend; added `Browser4AutoConfiguration` to auto-config imports

---

## Issues Found (10 issues)

### Issue 1: Dev mode auto-start uses stale installed backend, not locally-built

**Severity:** Critical
**Category:** Reliability

#### Reproduction

Run `./b4w.ps1 status` from the repo root when a Browser4 backend was previously installed globally.

#### Expected Behavior

The CLI should auto-start the locally-built JAR from the repository. CLAUDE.md states: "Dev mode: the CLI daemon auto-starts the locally-built backend JAR from the repository."

#### Actual Behavior

The CLI connects to the globally installed v4.11.15 backend (installed June 2026) instead of the locally-built v4.12.0-SNAPSHOT. The status command shows "Version mismatch: CLI is 4.12.0 but installed backend is v4.11.15."

#### Root Cause Analysis

The CLI's backend discovery prioritizes the installed runtime over the local build. The local bundle at `browser4-apps/browser4-bundle/target/runtime-bundle/` is only used when the installed backend is not running. The first `status` call showed the installed v4.11.15 was active.

#### Code Pointer

``cli/browser4-cli/src/daemon.rs` — `resolve_base_url()` and `ensure_server_running()` functions`

#### AI Suggested Improvement

- When running in dev mode (repo root detected), always prefer the locally-built runtime bundle over any globally installed version
- The CLAUDE.md statement "auto-starts the locally-built backend JAR" should be accurate — if it can't, the status should clearly warn that the wrong backend is active
- Add a `--dev` flag to explicitly force local backend usage

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Clear discrepancy between documented behavior (CLAUDE.md says "auto-starts locally-built JAR") and actual behavior (finds installed backend first). The `resolve_base_url()` priority order needs inversion for dev mode, and the `--dev` flag is a sensible escape hatch.

---

### Issue 2: Crawl depth > 1 fails with missing TaskLoops bean

**Severity:** Critical
**Category:** Reliability

#### Reproduction

Run `crawl <url> --depth 2 -ol "a.product"` with the locally-built backend.

#### Expected Behavior

Crawl should traverse multiple depth levels following links.

#### Actual Behavior

`No qualifying bean of type 'ai.platon.pulsar.loop.TaskLoops' available` — the crawl task fails immediately with an internal server error. The CLI waits 600s for a task that was already failed.

#### Root Cause Analysis

`Browser4AutoConfiguration` (which creates the `TaskLoops` bean) is not registered in the auto-configuration imports file (`AutoConfiguration.imports`). Without this bean, `PulsarSettings.withSequentialBrowsers()` and `session.submit()` for deep crawls cannot create sequential browser agents. The `Browser4AutoConfiguration` class exists and has `@AutoConfiguration` but is never loaded because it's not listed in the Spring Boot auto-configuration imports.

#### Code Pointer

``browser4-boot/src/main/resources/META-INF/spring/org.springframework.boot.autoconfigure.AutoConfiguration.imports` — missing `Browser4AutoConfiguration` entry; `browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:461` and `:541` — both require `TaskLoops``

#### AI Suggested Improvement

- Add `ai.platon.browser4.boot.autoconfigure.Browser4AutoConfiguration` to the `AutoConfiguration.imports` file
- Add integration tests that verify the `TaskLoops` bean exists when the `bundle` profile is active
- Make `crawlDepthN` gracefully degrade when `TaskLoops` is unavailable (e.g., fall back to single-threaded crawling)

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Straightforward configuration bug — `Browser4AutoConfiguration` exists with `@AutoConfiguration` but isn't registered in `AutoConfiguration.imports`, causing a hard crash on any depth > 0 crawl. The fix is a one-line addition to the imports file; the suggested graceful-degradation fallback is lower priority but worth capturing as a follow-up.

---

### Issue 3: CrawlToolExecutor ignores `urls` parameter — seed file pages silently dropped

**Severity:** High
**Category:** Product

#### Reproduction

Run `crawl --seed-file urls.txt --depth 0 --refresh` with a seed file containing 2+ URLs.

#### Expected Behavior

All URLs from the seed file should be fetched (showing "URLs: 2" and "2 pages found").

#### Actual Behavior

CLI shows "URLs: 2" but only 1 page is found. The second URL is silently dropped.

#### Root Cause Analysis

`CrawlToolExecutor.callFunctionOn()` for the "submit" method extracts `url`, `depth`, and `args` from the MCP arguments but never extracts the `urls` list. The `CrawlRequest` is constructed with only `url`, `args`, and `depth` — the `urls` parameter is omitted. The CLI correctly sends the `urls` array in the MCP call, but the backend executor ignores it.

#### Code Pointer

``browser4-rest/src/main/kotlin/ai/platon/pulsar/agent/tool/CrawlToolExecutor.kt:66-71` — `"submit"` handler missing `urls` extraction`

#### AI Suggested Improvement

- Add `val urls = paramStringList(args, "urls", functionName, required = false)` and pass it to `CrawlRequest(urls = urls)`
- Add a `urls` argument to the `ToolSpec` for the submit method
- Add unit tests verifying seed file URLs are correctly forwarded

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Clear omission in `CrawlToolExecutor` — the `urls` parameter is never extracted or forwarded to `CrawlRequest`, despite the CLI correctly sending it. Silently dropping seed-file URLs is a data-loss class bug. Fix is well-scoped: add `paramStringList` extraction and wire it into the request object. Related to but distinct from Issue 2 (both block crawl functionality, but at different layers and with different root causes).

---

### Issue 4: Crawl tasks stuck "waiting to start" — misleading progress and slow failure

**Severity:** High
**Category:** UX / Reliability

#### Reproduction

Submit a crawl task that fails immediately at the backend (e.g., missing bean).

#### Expected Behavior

The CLI should detect the failure quickly and report the error.

#### Actual Behavior

The CLI shows "Still waiting for crawl to start..." for 600 seconds (the full timeout), polling every 16s, before reporting a timeout. The task had already failed at the backend within milliseconds. The CLI never checks the actual task status during this waiting period — it only polls `pagesFound`.

#### Root Cause Analysis

The CLI polling loop (`handle_crawl` in `main.rs`) waits for the crawl to "start" (first page found) but the task status is already ERROR internally. The polling only checks `pagesFound`, not the task status field for terminal error states.

#### Code Pointer

``cli/browser4-cli/src/main.rs` — the crawl polling loop in `handle_crawl()` function`

#### AI Suggested Improvement

- Check the task's `status` field during polling, not just `pagesFound` — exit early if status is ERROR or INTERNAL_SERVER_ERROR
- Reduce the initial polling interval from 16s to ~2s for the first few polls
- Display the actual error message from the backend to the user instead of just "timed out"

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] The crawl polling loop only checks `pagesFound` and never inspects the task `status` field, so terminal errors (like the Issue 2 bean failure) waste 600 seconds of user time. Should check status and surface backend errors. Related to Issue 8 (both touch the same polling loop in `handle_crawl`) but addresses a different defect: Issue 4 is about error-detection blindness, Issue 8 is about genuine cold-start latency. Fixes should be coordinated since they touch the same code.

---

### Issue 5: crawlDepth1 finds 0 out-links when CSS selector should match

**Severity:** High
**Category:** Reliability

#### Reproduction

Run `crawl http://localhost:18080/generated/crawl/index.html --depth 1 -ol "a.product" -olp "/product/"`

#### Expected Behavior

Should find the three `<a class="product">` elements on the hub page (Widget Alpha, Beta, Gamma).

#### Actual Behavior

"No out-links found on portal page. The page loaded but the CSS selector 'a.product' matched zero elements." The diagnostic suggests the selector is wrong, but the hub page clearly has `<a class="product" href="product/1.html">` elements.

#### Root Cause Analysis

The `extractOutLinks` function uses `session.loadDocument(portalUrl, normOptions)` which loads the page through the WebDriver/browser emulator path. The log shows "ProtoNotFound(1600)" for the hub page, suggesting the protocol handler can't properly render/parse the localhost page. The page HTML is fetched but the DOM may not be fully rendered for CSS selector matching.

#### Code Pointer

``browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:616-676` — `extractOutLinks` function`

#### AI Suggested Improvement

- For depth 1 crawls where the portal page might not render fully, offer a fallback that parses the raw HTML for anchor extraction
- Improve the diagnostic message to include the actual page content length and anchor count, so users can distinguish "page didn't load" from "wrong selector"
- The log already computes `allAnchors` count but doesn't surface it in the diagnostic — include it

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] 0 out-links when the HTML demonstrably contains matching elements suggests a DOM rendering gap in the WebDriver path for localhost pages. The `extractOutLinks` diagnostic should surface `allAnchors` count (already computed but not reported) to distinguish "page didn't render" from "wrong selector." The ProtoNotFound(1600) log hint deserves deeper investigation — may have the same root cause as the title-extraction gap in Issue 9.

---

### Issue 6: Backend build process fragile — cannot run locally-built JAR directly

**Severity:** High
**Category:** Installation & Setup

#### Reproduction

Try to run the locally-built backend after `mvn install`: `java -jar browser4-rest/target/browser4-rest-4.12.0-SNAPSHOT.jar`

#### Expected Behavior

The JAR should be executable (Spring Boot fat JAR).

#### Actual Behavior

"no main manifest attribute" — the JAR is a standard library JAR, not a Spring Boot executable. The `mvn spring-boot:run` command also fails ("No plugin found for prefix 'spring-boot'"). The only way to run the local backend is through the pre-built runtime bundle, which requires a separate build step (`mvn install -DallMainModules=true`).

#### Root Cause Analysis

The `browser4-rest` module doesn't include the Spring Boot Maven plugin to package as an executable fat JAR. The runtime bundle (`browser4-apps/browser4-bundle`) is the only executable packaging, and it's behind a Maven profile (`allMainModules`). The status command's suggestion ("run: cd browser4-rest && mvn spring-boot:run") is incorrect.

#### Code Pointer

``browser4-rest/pom.xml` — missing Spring Boot Maven plugin configuration`

#### AI Suggested Improvement

- Either add the Spring Boot Maven plugin to `browser4-rest` or update the status message to point to the correct build/run commands for the bundle
- The CLAUDE.md should document that the bundle needs `-DallMainModules=true` profile to build
- Provide a `./bin/run-backend.sh` or similar convenience script for local development

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] `browser4-rest` JAR isn't a Spring Boot fat JAR, making the status command's suggestion incorrect. Fix should update the status message to point to the bundle build (`mvn install -DallMainModules=true`) and ideally add a convenience launcher script. Adding the Spring Boot plugin is an option but may change the module's packaging semantics — the doc/clarify fix is lower risk. Related to Issue 1 (both affect the local-dev bootstrap experience) and Issue 10 (bundle artifact quality).

---

### Issue 7: PowerShell short-flag interception (`-d` consumed by PowerShell)

**Severity:** Medium
**Category:** CLI Experience

#### Reproduction

Run `./b4w.ps1 crawl "http://..." -d 2 -ol "a.product"`

#### Expected Behavior

`-d 2` should set crawl depth to 2.

#### Actual Behavior

PowerShell consumes `-d` (maps to `-Debug` common parameter), passing just `2` as a positional argument. The CLI reports: `error: too many arguments: expected 1, received 2`. The SKILL.md documents this issue for `-i` and `-v` but doesn't mention `-d`.

#### Root Cause Analysis

PowerShell's parameter binder intercepts short flags that match its common parameters. `-d` matches `-Debug`. The b4w.ps1 script uses `$RemainingArgs` but short flags are consumed before reaching the script's param block.

#### Code Pointer

``b4w.ps1` — the `param()` block and `$RemainingArgs` handling; SKILL.md line 359`

#### AI Suggested Improvement

- Document `-d` in the PowerShell warning alongside `-i` and `-v`
- Fix the `b4w.ps1` param block to use `[switch]` parameters for common PowerShell flags, or add explicit `-d` mapping
- Recommend using `--depth` (long form) in all examples instead of `-d` when running through PowerShell

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Well-understood PowerShell parameter-binding issue. `-d` matching `-Debug` is the same class of problem as the already-documented `-i`/`-v`. Fix is: (a) add `-d` to the SKILL.md warning, (b) prefer `--depth` in examples, (c) add explicit `[switch]$Debug` in the param block to stop PowerShell from consuming it silently.

---

### Issue 8: Slow crawl startup — 80-160s delay before work begins

**Severity:** Medium
**Category:** UX / Reliability

#### Reproduction

Submit any crawl task without `--background`.

#### Expected Behavior

Crawl should start within seconds for a simple localhost page.

#### Actual Behavior

"Still waiting for crawl to start..." appears for 80-160 seconds before crawling begins. Even for depth 0 with a single localhost URL, AC1 took ~96s and AC4 took ~144s to start.

#### Root Cause Analysis

The CoroutineDispatcher has a limited worker pool (`Dispatchers.IO.limitedParallelism(5)`), and the crawl queue seems to have startup contention. Or the `session.load()`/`loadDocument()` path has significant initialization overhead for the first page load of a session.

#### Code Pointer

``browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:116` — `crawlDispatcher` configuration`

#### AI Suggested Improvement

- Profile the startup delay to identify the bottleneck (session creation, browser launch, first page load)
- Display a "Starting crawl engine..." or similar message during the actual work, separate from "Waiting for crawl to start"
- Consider pre-warming the crawl worker pool when the backend starts

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [x] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT with improvements] 80-160s startup delay is severe, but the root cause is speculative (dispatcher contention vs. session init vs. first-page-load overhead). Needs profiling before committing to a fix. Accept the diagnostic work. The suggestion to display "Starting crawl engine…" during cold-start vs. "Waiting for crawl to start" is a quick UX win that doesn't require solving the root cause. Coordinate with Issue 4 since both modify the same polling code.

---

### Issue 9: Page titles not displayed in crawl output

**Severity:** Low
**Category:** Product

#### Reproduction

Any `crawl` command — the output shows `depth=0 | <url> |` with empty space after the pipe where the title should be.

#### Expected Behavior

The page title (e.g., "Crawl Test Hub", "Widget Alpha — $10.00") should be displayed.

#### Actual Behavior

Title field is always empty.

#### Root Cause Analysis

Unknown — the page titles are present in the HTML (`<title>Crawl Test Hub</title>`) but the `CrawlPageResult.title` is `null` in the crawl output. The `crawlDepth0` function calls `session.parse(page)` and uses `document.title`, but the title may not be extracted correctly from the parsed document. The backend log shows "ProtoNotFound(1600)" when fetching pages, suggesting the protocol adapter may not be parsing the page content properly.

#### Code Pointer

``browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/service/CrawlService.kt:333` — `val document = session.parse(page)``

#### AI Suggested Improvement

- Investigate why `document.title` is null for successfully fetched pages (the log shows "ProtoNotFound(1600)" which may indicate a protocol handler issue)
- If title extraction is unreliable, fall back to extracting `<title>` from raw HTML

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Page titles being always null degrades crawl output quality. May share a root cause with Issue 5 (ProtoNotFound(1600) suggesting protocol-handler issues with localhost pages). Title extraction from parsed DOM failing while raw HTML has `<title>` tags suggests a parsing gap. The fallback suggestion (regex-extract `<title>` from raw HTML) is a pragmatic interim fix while the deeper protocol issue is investigated.

---

### Issue 10: Binary permissions not set in local runtime bundle

**Severity:** Low
**Category:** Installation & Setup

#### Reproduction

Try to run `start.sh` or `bin/java` from a freshly-built runtime bundle.

#### Expected Behavior

Binaries should have execute permissions.

#### Actual Behavior

`Permission denied` errors. Requires manual `chmod +x` on `start.sh` and `runtime/bin/java`.

#### Root Cause Analysis

The Maven assembly or copy step in the bundle build doesn't preserve/apply execute permissions on shell scripts and the bundled Java runtime binary.

#### Code Pointer

``browser4-apps/browser4-bundle/pom.xml` or the assembly descriptor — the bundle packaging step`

#### AI Suggested Improvement

- Add `<fileMode>0755</fileMode>` to the assembly descriptor for `*.sh` files and `runtime/bin/*`
- Alternatively, make the CLI's auto-start path fix permissions before launching

#### Human Review

- [x] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**
[AI suggested: ACCEPT] Missing execute permissions on bundled binaries is a straightforward Maven assembly fix (`<fileMode>0755</fileMode>`). The CLI auto-start workaround (fix permissions before launching) is viable but the real fix belongs in the build. Low severity but trivially fixable — pair it with Issue 6 in a dev-setup polish pass.

---

## How to Reproduce

### Common Setup

1. Clone the repository and `cd` to the repo root.
2. The CLI is invoked via `./b4w.ps1` which auto-builds from source when needed.
3. The backend server starts automatically in dev mode.
4. All commands from repo root: `./b4w.ps1 <command>`

### Per-Issue Reproduction Steps

#### Issue 1: Dev mode auto-start uses stale installed backend, not locally-built

Run `./b4w.ps1 status` from the repo root when a Browser4 backend was previously installed globally.

#### Issue 2: Crawl depth > 1 fails with missing TaskLoops bean

Run `crawl <url> --depth 2 -ol "a.product"` with the locally-built backend.

#### Issue 3: CrawlToolExecutor ignores `urls` parameter — seed file pages silently dropped

Run `crawl --seed-file urls.txt --depth 0 --refresh` with a seed file containing 2+ URLs.

#### Issue 4: Crawl tasks stuck "waiting to start" — misleading progress and slow failure

Submit a crawl task that fails immediately at the backend (e.g., missing bean).

#### Issue 5: crawlDepth1 finds 0 out-links when CSS selector should match

Run `crawl http://localhost:18080/generated/crawl/index.html --depth 1 -ol "a.product" -olp "/product/"`

#### Issue 6: Backend build process fragile — cannot run locally-built JAR directly

Try to run the locally-built backend after `mvn install`: `java -jar browser4-rest/target/browser4-rest-4.12.0-SNAPSHOT.jar`

#### Issue 7: PowerShell short-flag interception (`-d` consumed by PowerShell)

Run `./b4w.ps1 crawl "http://..." -d 2 -ol "a.product"`

#### Issue 8: Slow crawl startup — 80-160s delay before work begins

Submit any crawl task without `--background`.

#### Issue 9: Page titles not displayed in crawl output

Any `crawl` command — the output shows `depth=0 | <url> |` with empty space after the pipe where the title should be.

#### Issue 10: Binary permissions not set in local runtime bundle

Try to run `start.sh` or `bin/java` from a freshly-built runtime bundle.

