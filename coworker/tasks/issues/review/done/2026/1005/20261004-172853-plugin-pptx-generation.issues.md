# Issues: plugin-pptx-generation

> **Source:** `20261004-172853-plugin-pptx-generation.full.md` | **Date:** 20261004-172853 | **Mode:** dev

## Scenario Background

### Task

**Task completed successfully on all three pages** — three valid PPTX decks were generated from live web pages and independently verified — but only after **two significant workarounds** (manual plugin-JAR installation and a backend proxy restart) that a first-time user could not easily discover. Results:

| Page | Reported filePath | slideCount | blockCount | imageCount | durationMs | File size | Verified |
|---|---|---|---|---|---|---|---|
| Solar System | `.test-sessions/…/pptx-output/Solar_System_-_Wikipedia_20261005_012555.pptx` | **48** (>5 ✓) | **214** (>20 ✓) | **53** (>0 ✓) | 14,561 | 1,795,398 B (>10 KB ✓) | zip OK · 48 slides · 36 embedded media |
| Moon | `.test-sessions/…/pptx-output/Moon_-_Wikipedia_20261005_012643.pptx` | 67 | 316 | 72 | 26,402 | 4,364,849 B | zip OK · 67 slides · 49 embedded media |
| httpbin.org/html | `.test-sessions/…/pptx-output/httpbin.org_html_20261005_012655.pptx` | 2 | 2 | 0 | 176 | 28,147 B | zip OK · 2 slides · 0 media |

All three files are distinct (no overwrite), are valid OOXML zips (`[Content_Types].xml` present, `testzip()` clean), and open as well-formed decks (title slide = page title + URL). Text encoding is intact (no U+FFFD; the em dash survived).

**Answers to the task's comparison questions**

- **Slides vs. complexity:** slide count tracks the number of extracted content blocks, not gut feeling about article length. The current Moon article yielded *more* blocks than Solar System (316 vs 214 → 67 vs 48 slides), so "shorter" did not hold. The httpbin page (genuinely 1 `<h1>` + 1 `<p>`) produced 2 slides (title + content).
- **Content mapping:** document order is walked once; H1–H6 headings drive section grouping and slide titles; paragraphs become body text; up to 2 images per content slide are embedded as native pictures; HTML tables become native `a:tbl` shapes (4 on Solar System, 2 on Moon); lists/code/blockquote blocks are rendered as styled text; long sections split into "(continued N)" slides at ≤6 blocks per slide.
- **Minimal page behavior:** still a valid PPTX, just 2 slides; the run is near-instant (176 ms) because there is no download phase. `imageCount` is 0, correctly.
- **Caveat on `imageCount`:** it counts image *blocks*, not images actually embedded. Solar System reported 53 while only 36 media parts exist in the file (52/52 downloads succeeded, 16 were silently dropped by per-slide caps/overflow). On a network where all image downloads fail, the call still "succeeds" reporting the block count — a silent failure mode.

### Execution Context

1. **Prep:** `./b4w.ps1 help`, `./b4w.ps1 help plugin`, read `skills/browser4-cli/SKILL.md` fully; located the plugin at `browser4-plugins/browser4-pptx/` (built shaded JAR `…-4.13.27-SNAPSHOT.jar`, 16.6 MB).
2. **Install:** `plugin list` showed 4 plugins, not pptx. `./b4w.ps1 plugin install browser4-plugins/browser4-pptx/target/browser4-pptx-4.13.27-SNAPSHOT.jar` → **HTTP 413** raw HTML. Root cause: Spring default multipart limits; the unshaded `original-` JAR (78 KB) can't be used because no POI is on the backend classpath. **Workaround:** copied the shaded JAR into the runtime bundle's `plugins/` directory, `./b4w.ps1 stop`, next command auto-restarted; `plugin list` then showed 5 plugins, pptx `loaded`.
3. **Invocation discovery:** general help documents the `plugin-<name> <method>` pa...

(truncated — see full.md for complete trace)

---

## Issues Found (7 issues)

### Issue 1: plugin install cannot upload the project's own shaded plugin JAR (HTTP 413)

**Severity:** High
**Category:** Product

#### Reproduction

./b4w.ps1 plugin install browser4-plugins/browser4-pptx/target/browser4-pptx-4.13.27-SNAPSHOT.jar

#### Expected Behavior

The JAR is validated and copied into the server's plugins directory, and the CLI reports the installed plugin (activated after restart).

#### Actual Behavior

Command fails with a raw HTML error page: 'HTTP request failed with status 413 Payload Too Large: ... Maximum upload size exceeded ... URI: http://localhost:18182/api/plugins/install'. Exit code 1. No plugin installed.

#### Root Cause Analysis

The browser4-pptx plugin bundles Apache POI in its shaded JAR (16.6 MB, required because the backend runtime bundle ships no POI), but the backend never configures spring.servlet.multipart limits, so Spring Boot's defaults (1 MB/file, 10 MB/request) apply. PluginController.installPlugin accepts a MultipartFile upload, and Tomcat rejects the body before the controller runs, returning its raw HTML error page. There is no non-HTTP install path in the CLI, and no size hint in any documentation.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/api/controller/PluginController.kt:installPlugin() (plus backend multipart configuration)`

#### AI Suggested Improvement

- Raise the multipart limits (e.g. spring.servlet.multipart.max-file-size=100MB, max-request-size=100MB) or stream the upload to disk without buffering.
- When the server is local (dev mode), have `plugin install` copy the JAR straight into the plugins directory instead of HTTP-uploading it.
- Return a structured, actionable error (naming the max size and stating the plugin was NOT installed) instead of the container's HTML page; the CLI should render it as a clean message.
- Document the size limitation in `help plugin install`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: 5-minute server async timeout kills long plugin tool calls with a raw 503 and no output

**Severity:** High
**Category:** Reliability

#### Reproduction

On a host where image hosts are slow/unreachable (e.g. direct egress blocked): ./b4w.ps1 -s pptx-eval goto https://en.wikipedia.org/wiki/Solar_System ; ./b4w.ps1 -s pptx-eval plugin-pptx generate --outputPath <dir> --timeout 600

#### Expected Behavior

The generation either completes, or fails with an error that explains the timeout, suggests remedies, and leaves no partial state ambiguity.

#### Actual Behavior

After exactly 300 s: 'HTTP request failed with status 503 Service Unavailable: {"status":503,"error":"Service Unavailable","path":"/mcp/call-tool"}'. No .pptx file is written; no partial result is reported. The CLI's --timeout 600 has no effect. Backend log: 'WARN DefaultHandlerExceptionResolver - Resolved [AsyncRequestTimeoutException]'.

#### Root Cause Analysis

WebMvcAsyncConfig hardcodes a 5-minute default async request timeout for the whole REST API (setDefaultTimeout(5.minutes.inWholeMilliseconds)). Kotlin suspend controller methods run as servlet async requests, so any MCP tool call taking >5 min is aborted server-side. pptx.generate is a single synchronous operation that downloads every image on the page first (no time budget, no image-count cap; here 62 images x up to 30 s connect timeout / 3 concurrent), so on adverse networks it cannot finish inside 5 minutes. There is no async submit-and-poll mode for plugin tools, and neither the CLI nor the 503 response mentions the timeout.

#### Code Pointer

`browser4-rest/src/main/kotlin/ai/platon/pulsar/rest/config/WebMvcAsyncConfig.kt:configureAsyncSupport() ; browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/tools/PptxToolExecutor.kt:callFunctionOn()`

#### AI Suggested Improvement

- Make the async timeout configurable and raise it (or per-endpoint) for plugin tool calls; expose the effective limit in `status`/help.
- Offer an async job mode for long-running tools (submit -> poll task id -> fetch result) so tool calls are not bound to one HTTP request lifetime.
- Bound the plugin's total image-download phase with a time/image budget so it degrades gracefully instead of exhausting the request budget, and report partial success.
- Convert AsyncRequestTimeoutException into a structured error ('tool call exceeded the 300 s server limit; retry with fewer images or a longer server timeout') and have the CLI print that hint instead of the raw JSON.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Image downloads bypass the system proxy and the documented download-timeout config is not wired

**Severity:** Medium
**Category:** Product

#### Reproduction

On a Windows host with a system proxy (ProxyEnable=1, ProxyServer=127.0.0.1:10808) and blocked direct egress: navigate to a Wikipedia page in headless Chrome (loads fine, Chrome uses the system proxy) then run `plugin-pptx generate`. All image downloads stall (each image URL fails at the TCP/connect stage), taking the call past the 5-minute async timeout. After restarting the backend with BROWSER4_SERVER_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808" the same command succeeds in ~15 s.

#### Expected Behavior

Image downloads use the same network route as the browser (system/JVM proxy), or the plugin exposes a documented proxy setting; failing downloads are visible in the result.

#### Actual Behavior

PptxImageDownloader builds requests on an OkHttpClient with no proxy support; the JVM does not read Windows system proxy settings, so downloads go direct and hang until connect timeout. No pptx.* proxy config exists, and the documented pptx.download.timeout.seconds is ignored (client hardcodes connect 30 s / read 60 s). When downloads fail the tool still returns success with imageCount > 0 (see next issue), so the failure is silent.

#### Root Cause Analysis

PptxAutoConfiguration.pptxDownloadClient() constructs OkHttpClient without proxy configuration and without reading the browser's proxy settings; PptxConfig.downloadTimeoutSeconds is never applied to the client (dead config). Combined with unbounded per-image retry latency this produces either the 5-minute 503 or image-less decks.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/config/PptxAutoConfiguration.kt:pptxDownloadClient() ; browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PptxImageDownloader.kt:downloadImage()`

#### AI Suggested Improvement

- Honor standard JVM proxy properties by default (document BROWSER4_SERVER_OPTS='-Dhttps.proxyHost=... -Dhttps.proxyPort=...' as the supported knob) or add a pptx.download.proxy setting.
- Reuse the browser's proxy configuration (PulsarSettings) when building the download client so Chrome-reachable images are also backend-reachable.
- Wire PptxConfig.downloadTimeoutSeconds into connect/read timeouts (and make the defaults lower than the server async budget).
- Emit a visible warning (and a separate failedImages count) when downloads fail or when zero images were embedded.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: imageCount reports image blocks, not embedded images; images are silently dropped

**Severity:** Medium
**Category:** Product

#### Reproduction

./b4w.ps1 -s pptx-eval plugin-pptx generate --outputPath <dir> on https://en.wikipedia.org/wiki/Solar_System ; then `python -c "import zipfile; z=zipfile.ZipFile('Solar_System_-_Wikipedia_20261005_012555.pptx'); print(len([n for n in z.namelist() if n.startswith('ppt/media/')]))"`

#### Expected Behavior

imageCount reflects images actually embedded (or separate embedded/failed/skipped counts), matching the README ('number of images embedded').

#### Actual Behavior

Tool reports imageCount=53 while the file contains 36 media files (Moon: reports 72, file has 49). The backend log says 'Downloaded 52/52 images'; per-slide cap (max 2) and slide-overflow breaks drop the rest without any warning. If all downloads fail, the tool still reports imageCount = block count, i.e. a fully image-less deck looks successful.

#### Root Cause Analysis

PptxToolExecutor computes imageCount = blocks.count { it.type == 'image' } (block count, not downloads/embeddings). PptxGenerator.createContentSlide silently skips images beyond config.maxImagesPerSlide and breaks out of the block loop when yOffset exceeds the slide height (dropping remaining blocks of that chunk) with no warning or continuation slide.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/tools/PptxToolExecutor.kt:callFunctionOn() ; browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PptxGenerator.kt:createContentSlide()`

#### AI Suggested Improvement

- Count embedded images from the produced file (like slideCount already is) or from successful placements, and add downloadedImages/skippedImages/failedImages fields.
- Emit warnings in the result when images are skipped due to max-images-per-slide or slide overflow.
- On overflow, start a continuation slide instead of discarding remaining blocks, or at least report droppedBlocks.
- Align the README's description of imageCount with the real semantics.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: outputPath argument is actually an output directory

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s pptx-eval plugin-pptx generate --outputPath "D:/…/pptx-output/demo.pptx" ; then list the directory: a DIRECTORY named 'demo.pptx' was created containing httpbin.org_html_<timestamp>.pptx.

#### Expected Behavior

Either the path names the file to create (demo.pptx), or the argument is documented/named as a directory (outputDir) and rejects file-looking paths.

#### Actual Behavior

Path.of(outputPath) is passed as the generator's outputDir; Files.createDirectories creates a directory named demo.pptx and an auto-generated filename is written inside it. The README example 'pptx.generate(outputPath: "/path/to/output")' and the parameter name both suggest a file path.

#### Root Cause Analysis

PptxToolExecutor resolves `dir = Path.of(outputPath)` and PptxGenerator.generate(outputDir=dir) always synthesizes <sanitized-title>_<timestamp>.pptx; the parameter name and README never say 'directory'.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/tools/PptxToolExecutor.kt:callFunctionOn()`

#### AI Suggested Improvement

- Rename the tool argument to outputDir everywhere and update the README/tool description.
- Or accept a *.pptx path as the exact target filename and only treat other paths as directories.
- Fail fast with a clear message if the target exists as a file, instead of creating odd nested structures.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: Plugin tool commands are undiscoverable in the CLI help system

**Severity:** Medium
**Category:** Discoverability

#### Reproduction

./b4w.ps1 help plugin-pptx  ->  'Unknown command: plugin-pptx'; ./b4w.ps1 plugin-pptx --help -> same; ./b4w.ps1 --help-json -> no plugin-<name> entries at all; ./b4w.ps1 plugin -> 'Error: Unsupported command form: plugin. Use browser4-cli plugin <subcommand> instead.' although the help text lists `plugin` as the way to 'list all available plugin tool domains'.

#### Expected Behavior

A first-time user can discover installed plugin tools, their methods, and arguments from help output (e.g. help plugin-pptx, plugin tools, or --help-json entries).

#### Actual Behavior

Only the generic 'Plugin tools' prose section in the full help reveals the plugin-<name> <method> pattern; per-plugin help does not exist (help falls back to the generic page with 'Unknown command'), --help-json omits plugin domains entirely, `plugin list` shows no tool names, and bare `plugin` contradicts the help text by erroring. Arguments (outputPath) were only discoverable by invoking the tool and reading the error text, which luckily prints the tool spec.

#### Root Cause Analysis

The CLI's help registry (help.rs, main.rs unknown-command handling) is static: dynamically registered backend tool domains are not part of the command metadata, and the help dispatcher rejects plugin-<name> before any plugin-prefix handling. Bare `plugin` is routed to the subcommand parser, which requires a known subcommand, contradicting the help text.

#### Code Pointer

`cli/browser4-cli/src/help.rs:430 (Unknown command handling) ; cli/browser4-cli/src/main.rs:21642 (Unsupported command form) — plus the plugin-domain command registration path`

#### AI Suggested Improvement

- Register plugin tool commands in the help registry so `help plugin-pptx`, `help plugin-pptx generate`, and `plugin-pptx --help` print the method list and argument specs.
- Add a `plugin tools [<domain>]` command (or make bare `plugin` list domains as documented) that enumerates installed domains and their methods/args.
- Include plugin domains and tool specs in --help-json for machine consumers.
- Resolve the help-text/behavior contradiction for bare `plugin`.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Plugin manifest version is stale relative to the built artifact

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 plugin list  ->  'browser4-pptx-4.13.27-SNAPSHOT.jar  v4.13.14-SNAPSHOT  loaded'

#### Expected Behavior

The version shown for an installed plugin matches the artifact version (4.13.27-SNAPSHOT).

#### Actual Behavior

JAR file name is 4.13.27-SNAPSHOT but the manifest reports v4.13.14-SNAPSHOT, which is confusing when diagnosing which build is loaded.

#### Root Cause Analysis

META-INF/browser4-plugin.json carries a hardcoded version not filtered from the Maven project version at build time (the README example shows the same stale 4.13.14-SNAPSHOT value).

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/resources/META-INF/browser4-plugin.json (and the module's pom.xml resource filtering)`

#### AI Suggested Improvement

- Filter ${project.version} into the manifest at build time, or fall back to the JAR filename version when displaying plugin info.
- Add a build test asserting manifest version == project version.

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

**Completion Status:** Successful — all three PPTX decks were generated from the requested live pages and independently verified (valid OOXML, slide/media counts, distinct files). Two workarounds were required: manual plugin-JAR installation (the CLI install endpoint 413s on the plugin's 16.6 MB shaded JAR) and restarting the backend with JVM proxy properties (image downloads ignore the system proxy).

**Success Rate:** 80% — every generation/verification step passed, but installation and default-network operation failed and had to be bypassed.

**Issues Found:** 7

**Major Blockers:** 1) `plugin install` cannot upload browser4-pptx's shaded JAR (HTTP 413; Spring multipart defaults) — worked around by copying the JAR into the runtime bundle's plugins/ directory. 2) Image downloads bypass the machine's system proxy; with direct egress blocked, pptx.generate ran into the backend's hard 5-minute async timeout (HTTP 503, no file) — worked around with BROWSER4_SERVER_OPTS JVM proxy flags.

**Most Confusing Aspects:** How to invoke plugin tools: no per-plugin help (`help plugin-pptx` -> Unknown command), --help-json omits plugin domains, bare `plugin` errors although the help says it lists domains; the tool argument `outputPath` actually means a directory (passing demo.pptx creates a directory); the raw 503 JSON gives no hint that a timeout occurred or that --timeout is irrelevant because the server capped it; imageCount is a block count, so download failures and dropped images are invisible.

**Most Valuable Improvements:** Fix plugin upload limits (or copy locally for a local server) and return friendly errors; make plugin tools discoverable (help plugin-<name>, plugin tools, --help-json); honor the system/JVM proxy in the image downloader and wire the documented pptx.download.timeout.seconds; report actually embedded/skipped/failed image counts and warn on drops; make the 5-minute async timeout configurable or add an async submit-and-poll mode for long tools; rename outputPath to outputDir or accept a file path.

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

#### Issue 1: plugin install cannot upload the project's own shaded plugin JAR (HTTP 413)

./b4w.ps1 plugin install browser4-plugins/browser4-pptx/target/browser4-pptx-4.13.27-SNAPSHOT.jar

#### Issue 2: 5-minute server async timeout kills long plugin tool calls with a raw 503 and no output

On a host where image hosts are slow/unreachable (e.g. direct egress blocked): ./b4w.ps1 -s pptx-eval goto https://en.wikipedia.org/wiki/Solar_System ; ./b4w.ps1 -s pptx-eval plugin-pptx generate --outputPath <dir> --timeout 600

#### Issue 3: Image downloads bypass the system proxy and the documented download-timeout config is not wired

On a Windows host with a system proxy (ProxyEnable=1, ProxyServer=127.0.0.1:10808) and blocked direct egress: navigate to a Wikipedia page in headless Chrome (loads fine, Chrome uses the system proxy) then run `plugin-pptx generate`. All image downloads stall (each image URL fails at the TCP/connect stage), taking the call past the 5-minute async timeout. After restarting the backend with BROWSER4_SERVER_OPTS="-Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=10808 -Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=10808" the same command succeeds in ~15 s.

#### Issue 4: imageCount reports image blocks, not embedded images; images are silently dropped

./b4w.ps1 -s pptx-eval plugin-pptx generate --outputPath <dir> on https://en.wikipedia.org/wiki/Solar_System ; then `python -c "import zipfile; z=zipfile.ZipFile('Solar_System_-_Wikipedia_20261005_012555.pptx'); print(len([n for n in z.namelist() if n.startswith('ppt/media/')]))"`

#### Issue 5: outputPath argument is actually an output directory

./b4w.ps1 -s pptx-eval plugin-pptx generate --outputPath "D:/…/pptx-output/demo.pptx" ; then list the directory: a DIRECTORY named 'demo.pptx' was created containing httpbin.org_html_<timestamp>.pptx.

#### Issue 6: Plugin tool commands are undiscoverable in the CLI help system

./b4w.ps1 help plugin-pptx  ->  'Unknown command: plugin-pptx'; ./b4w.ps1 plugin-pptx --help -> same; ./b4w.ps1 --help-json -> no plugin-<name> entries at all; ./b4w.ps1 plugin -> 'Error: Unsupported command form: plugin. Use browser4-cli plugin <subcommand> instead.' although the help text lists `plugin` as the way to 'list all available plugin tool domains'.

#### Issue 7: Plugin manifest version is stale relative to the built artifact

./b4w.ps1 plugin list  ->  'browser4-pptx-4.13.27-SNAPSHOT.jar  v4.13.14-SNAPSHOT  loaded'

