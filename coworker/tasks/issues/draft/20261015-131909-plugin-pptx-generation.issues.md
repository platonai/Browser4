# Issues: plugin-pptx-generation

> **Source:** `20261015-131909-plugin-pptx-generation.issues.json` | **Date:** 20261015-131909 | **Mode:** dev

## Scenario Background

### Task

The plugin was not installed by default (`plugin list` showed only captcha, images, markdown, media, swarm), so I installed the repo-local JAR and restarted the backend. Three presentations were produced:

| Page | Slides | Blocks | Images embedded | durationMs | File size | Valid OOXML |
|---|---|---|---|---|---|---|
| `en.wikipedia.org/wiki/Solar_System` | **48** ✅ | **214** ✅ | **36** ✅ | **12 760** ✅ | 1 792 089 B ✅ | ✅ zip OK, 48 slides, 36 media entries |
| `en.wikipedia.org/wiki/Moon` | 67 | 312 | 47 | 17 491 | 4 359 821 B | ✅ zip OK, 67 slides, 47 media entries |
| `httpbin.org/html` | 2 | 2 | 0 | 305 | 28 147 B | ✅ zip OK, 2 slides, 0 media |

All acceptance criteria for the Solar System run were met. Files (in this run's scratch dir):

- `.test-sessions/20261015T0901431699046Z/pptx-solar/Solar_System_-_Wikipedia_20261015_211302.pptx`
- `.test-sessions/20261015T0901431699046Z/pptx-moon/Moon_-_Wikipedia_20261015_211457.pptx`
- `.test-sessions/20261015T0901431699046Z/pptx-httpbin2/httpbin.org_html_20261015_211521.pptx` (+ an identical-shape run in `pptx-httpbin/`)

**Critical caveat:** the Solar System run **only succeeded after I supplied a proxy**. The first two attempts returned nothing at all (details in Issue 1/2). The machine has a wininet proxy (`127.0.0.1:10808`, `ProxyEnable=1`) that Chrome uses but the plugin's OkHttp downloader does not; direct egress is blocked (curl to `thumb.wikimedia.org` fails after 54 s, to `en.wikipedia.org` after 21 s; via the proxy: HTTP 200 in 3.9 s). `config set pptx.download.proxy` is rejected, so the only lever is restarting the backend with `HTTPS_PROXY` set.

**Step 4 comparison (Moon vs Solar System):** the Moon article produced **more** slides (67 vs 48) despite being the "shorter" page — slide count tracks the *extracted block count* (312 vs 214, ≈1 slide per 4.6 blocks), not perceived article length. Moon's markup is denser (14 H2 sections, 164 `<p>`, 87 distinct `<img>`) than Solar System's (63 distinct `<img>`), and every heading at or above `sectionLevel+1` starts a new section. Output was a distinct file in a distinct directory; the filename embeds `<sanitized-title>_<timestamp>`.

**Step 6 summary — how `pptx.generate` handles complexity:**
- **Rich pages work but are lossy.** Title slide → sections grouped by the top heading level → up to 6 blocks per slide → "(continued N)" overflow slides. Images are embedded at up to 2/slide with alt-text captions.
- **Best-mapped content:** headings (drive sectioning), paragraphs (one text box each), lists (394 bulleted item runs in the Solar deck), tables (native `<a:tbl>`), images (36/47 embedded).
- **Worst-mapped:** (a) long pages — slides overflow and the *remaining blocks of the chunk are discarded silently*, so only **50 of 91 substantial paragraphs (55 %)** of the Solar System article made it into the deck, including its lead paragraph; (b) images beyond the 2-per-slide cap (17 and 25 dropped); (c) tables that arrive after a long paragraph in the same chunk (4 of 9 rendered); (d) pages whose text lives in table cells/infoboxes — the probe deliberately skips `<p>` inside `td/th/li/blockquote`; (e) minimal pages degrade gracefully (httpbin → 2 slides, still a valid file, but note its slide 1 shows only the URL because the page has no `<title>`).

### Execution Context

**Commands** (all via `./b4w.ps1`, session `-s pptx-eval`):
`help` · `plugin list` · `plugin info` · `plugin install <jar>` · `stop` · `open --headless <url>` · `page-info` · `goto <url>` · `eval` (incl. `--file probe.js`) · `tool call pptx_generate --json '{...}' --timeout N` · `config set/delete` · `--help-json`; plus `curl`/`unzip`/`python` for file and network verification.

**Major steps:** verified cwd → read SKILL.md + `help` → discovered nothing pptx-related installed → found `browser4-plugins/browser4-pptx/` (README, pre-built 4.14.1-SNAPSHOT JAR) → `plugin install` + `stop` + restart → confirmed `pptx_generate` in `GET /mcp/tools` (117 tools) → opened Solar System → **two failed generations (30 s then 600 s timeouts)** → diagnosed via backend log (`extracted 214 blocks` then `dur...

(truncated — see 20261015-131909-plugin-pptx-generation.issues.json for the complete execution trace)

---

## Issues Found (8 issues)

### Issue 1: pptx_generate has no deadline: un-downloadable images stall it past the server's async limit and nothing is returned

**Severity:** High
**Category:** Reliability

#### Reproduction

./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}'   # on a page whose images the JVM cannot reach
Then retry with --timeout 600.

#### Expected Behavior

Either the generation completes, or it fails within a bounded time with an actionable error (e.g. 'image downloads blocked; 52/52 failed') and, ideally, still writes a partial PPTX without images or returns the already-extracted block count.

#### Actual Behavior

Call 1 (default 30s): `Error: HTTP request timed out [tool=pptx_generate, endpoint=http://127.0.0.1:8282/mcp/call-tool, timeout=30s, ...]` after 31.9s. Call 2 (--timeout 600): identical error after 10m01s. No file, no partial result, no diagnostic was produced by either. Backend log: `pptx.generate: extracted 214 blocks` then silence, then `21:07:55 WARN GlobalExceptionHandler - Async request timed out`, then `21:08:21 ... MonoCoroutine was cancelled` / `tool.call failed ... durationMs=626499 outcome=CANCELLED`. The tool reports nothing about which stage it was in.

#### Root Cause Analysis

PptxGenerator.generate() step 1 calls PptxImageDownloader.downloadImages(), which launches one coroutine per distinct image URL, gates them with a Semaphore(3) and awaits them all — with no overall deadline and no cancellation tie-in to the HTTP request. Each blocked download burns the OkHttp connect timeout (30s) or read timeout (60s) set in PptxAutoConfiguration.pptxDownloadClient(). Solar System has 52 attempted images; 52/3 x 30s ≈ 520s, which exceeds the server's ~600s Spring async timeout, so the request is killed before any result exists. The CLI's own 30s default makes the first attempt fail even sooner, and its error message never mentions `--timeout` or how long the server kept working.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PptxImageDownloader.kt:downloadImages()`

#### AI Suggested Improvement

- Add a configurable overall deadline to the download phase (e.g. `pptx.download.total-timeout.seconds`, default 60) and stop after it, returning the images already fetched instead of hanging.
- Make the tool degrade instead of fail: if 0 of N images downloaded, still generate the PPTX without images and return `failedImages=N` plus a `warning` field — the text content is already extracted and useful.
- Log/return per-stage progress (`extracting / downloading i/N / rendering / writing`) so the caller can see where time went.
- Strengthen the CLI error for `tool call`: append the elapsed time and a hint such as `long-running plugin tool? retry with --timeout 600`. cli/browser4-cli/src/http.rs (DEFAULT_REQUEST_TIMEOUT_SECS = 30).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 2: Plugin image downloads ignore the OS/WinINET system proxy; the CLI cannot configure the plugin's proxy

**Severity:** High
**Category:** Product

#### Reproduction

On a machine with a system proxy (here `ProxyEnable=1`, `ProxyServer=127.0.0.1:10808`):
./b4w.ps1 plugin list                # start backend normally
./b4w.ps1 -s pptx-eval open --headless https://en.wikipedia.org/wiki/Solar_System
./b4w.ps1 -s pptx-eval tool call pptx_generate --timeout 550

#### Expected Behavior

Image downloads use the same egress path as the browser (the OS proxy), or the CLI can point the plugin at a proxy (`config set` / `--proxy`), so `pptx_generate` works on any machine where the browser can reach the page.

#### Actual Behavior

Every image download fails/times out, generation never completes, and no file is produced. `./b4w.ps1 config set pptx.download.proxy 127.0.0.1:10808` -> `Error: Unknown config key 'pptx.download.proxy'. Valid keys are: 'server', 'timeout', 'proxy', 'session', 'extension_id', 'agent.llm.maxRequestTokens', 'agent.token.budget.total'`. The CLI's own `--proxy`/`config set proxy` documents itself as 'HTTP proxy for downloads (runtime bundle, webminer)' and does not reach plugin downloads. Only restarting the backend with `HTTPS_PROXY=http://127.0.0.1:10808 ./b4w.ps1 ...` fixed it (backend log: `PPTX image download proxy enabled: 127.0.0.1:10808`), after which the same call finished in 12.8s.

#### Root Cause Analysis

PptxAutoConfiguration.resolveProxy() honours only the `pptx.download.proxy` property, then the process env vars HTTPS_PROXY/HTTP_PROXY; the OkHttp client it builds uses those explicitly instead of the JVM's DefaultProxySelector. The JVM does not read the Windows WinINET proxy (java.net.useSystemProxies=false in the shipped net.properties), so Thumbnail/Wikimedia requests bypass the only working route. The README documents this failure mode but the fix is not reachable from the CLI: server-side plugin properties are not settable through `config set` (only CLI defaults plus two agent keys), and CLI proxy settings are not propagated to the backend.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/config/PptxAutoConfiguration.kt:resolveProxy()`

#### AI Suggested Improvement

- Fall back to `java.net.ProxySelector.getDefault()` / System.getProperty("java.net.useSystemProxies") instead of env vars only, or start the backend JVM with `-Djava.net.useSystemProxies=true` in dev mode so OS proxy settings (WinINET/macOS) are honoured.
- Let the CLI set plugin properties: extend `config set` to route unknown-but-namespaced keys (e.g. `pptx.*`) to the backend's runtime overrides, or add `config server-set <key> <value>`.
- Propagate the CLI `--proxy` / `config set proxy` value to plugin image downloads as a default when `pptx.download.proxy` is unset.
- When 100% of downloads fail, surface it as an explicit error naming the likely cause ('no route to image hosts; set HTTPS_PROXY or pptx.download.proxy') instead of a generic timeout.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 3: Slide overflow silently discards remaining content blocks; only images are counted as skipped

**Severity:** High
**Category:** Product

#### Reproduction

Generate a deck from a text-heavy page and diff the deck against the page (Solar System example):
./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}'
# then compare the extracted blocks with the rendered slide text (see pptx-evidence.md)

#### Expected Behavior

Either every extracted block lands on a slide, or dropped blocks are reported (e.g. a `skippedBlocks` counter / warning) so the caller knows the deck is incomplete — and the splitter should prefer boundaries that fit the slide height.

#### Actual Behavior

Of the 214 extracted blocks, only ~50 of the page's 91 substantial paragraphs (55%) appear in the deck. The article's lead paragraph ("The Solar System is the gravitationally bound system of the Sun and the masses that orbit it...") is extracted by the probe but appears nowhere in the 1.79 MB deck. Reported statistics show only `skippedImages: 17` — no paragraph/table/heading loss is surfaced. Table blocks are affected too: 9 extracted, 4 rendered as `<a:tbl>`.

#### Root Cause Analysis

PptxGenerator.createContentSlide() renders blocks until `yOffset > slideHeight - MARGIN` and then `break`s, discarding the rest of the chunk (PptxGenerator.kt:300-307). The only accounting in that branch counts remaining *image* blocks as skipped; dropped text/table blocks are lost without a counter. findSplitPoint() (PptxGenerator.kt:566) chooses the chunk boundary by block type only, ignoring the height that addParagraphBlock()/estimateTextLines() will compute, so a chunk of 6 long paragraphs routinely overflows the 540pt slide.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PptxGenerator.kt:createContentSlide()`

#### AI Suggested Improvement

- Make findSplitPoint (or a pre-pass) height-aware: accumulate estimated block heights and close the chunk before the running total exceeds the usable slide height minus the title.
- When the overflow break does fire, carry the unrendered blocks into the next slide instead of dropping them (or count them in a new `skippedBlocks` field and log a warning).
- Add a regression test that asserts every extracted block appears on some slide, using the existing fixture pages.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 4: Untitled lead section yields meaningless slide titles like " (continued 1)" (leading space)

**Severity:** Medium
**Category:** UX

#### Reproduction

./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}' on https://en.wikipedia.org/wiki/Solar_System, then inspect slide 3 (or 3 of the Moon deck).

#### Expected Behavior

The pre-heading content should get a sensible title (e.g. the page title, "Introduction", or "Overview"), and continuation slides should read "Introduction (continued 1)".

#### Actual Behavior

Slides titled `" (continued 1)"` (a leading space followed by the continuation marker) appear in both Wikipedia decks — slide 3 of 48 (Solar) and slide 3 of 67 (Moon). 13/48 and 18/67 slides respectively are continuation slides; every one repeats the same pattern.

#### Root Cause Analysis

groupIntoSections() (PptxGenerator.kt:527-564) creates a Section with `currentSectionTitle ?: ""` for blocks that appear before the first heading. createSectionSlides() then builds the title as `"${section.title} (continued $continuationIndex)"`, so an empty section title renders as `" (continued 1)"`. createContentSlide() hides titles only when `isNotBlank()`, which is why the empty first slide shows no title at all while its continuations show the stray marker.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PptxGenerator.kt:groupIntoSections() / createSectionSlides()`

#### AI Suggested Improvement

- Title the leading section from the page title (or a localized "Introduction"/"Overview") instead of the empty string.
- Trim the continuation suffix when the base title is blank — never emit a title that starts with a space.
- Consider dropping the '(continued N)' suffix entirely and relying on the deck outline, or apply it only from N>=2 so the first continuation keeps the clean section name.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 5: downloadedImages reports embedded + skipped, contradicting its documented meaning

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}' on the Solar System page, then compare the result JSON with the backend log line 'PPTX image downloads: 51/52 succeeded, 1 failed'.

#### Expected Behavior

`downloadedImages` = number of images successfully downloaded (as documented in the plugin README), i.e. 51 here.

#### Actual Behavior

The result reports `downloadedImages: 53` while only 51 images downloaded (36 embedded + 17 skipped). For the Moon deck the two happen to coincide (72), hiding the bug.

#### Root Cause Analysis

PptxToolExecutor.callFunctionOn() computes `"downloadedImages" to (result.embeddedImageCount + result.skippedImageCount)` (PptxToolExecutor.kt:167) — a copy-paste of the log-line arithmetic — instead of threading the downloader's real success count through PptxGenerationOutput.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/tools/PptxToolExecutor.kt:callFunctionOn()`

#### AI Suggested Improvement

- Add a `downloadedImageCount` field to PptxGenerationOutput (PptxImageDownloader.DownloadResult already knows it: `total - failed`) and report that.
- Add a unit test asserting `downloadedImages == embedded + failed-to-embed + skipped-due-to-cap` is NOT the definition, i.e. pin downloadedImages to the downloader's own count.

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 6: help tool call points users at `plugin list` to find tool names, but plugin list shows no tool names

**Severity:** Low
**Category:** Documentation

#### Reproduction

./b4w.ps1 help "tool call"   # 'list available tools with `plugin list` or GET /mcp/tools'
./b4w.ps1 plugin list
./b4w.ps1 plugin info browser4-pptx

#### Expected Behavior

A documented way to discover that the pptx plugin exposes `pptx_generate`.

#### Actual Behavior

`plugin list` prints only 6 rows of 'JAR name / version / sdk / status / description' — the word pptx_generate appears nowhere. `plugin info browser4-pptx` prints path, size, status, manifest and auto-config class, again with no tool names. Only `GET /mcp/tools` (117 names) reveals `pptx_generate`; the user has to infer the name from the plugin README's `pptx.generate()` plus the snake_case rule.

#### Root Cause Analysis

The backend's plugin listing surfaces manifest metadata but not the MCP tool names each plugin's ToolMount registered; the CLI help text asserts otherwise. The tool registry (CustomToolRegistry / PluginManager, see log lines '+ Registered custom tool executor for domain: pptx') knows the mapping but does not expose it through `plugin list`/`plugin info`.

#### Code Pointer

`cli/browser4-cli/src/help.rs (tool-call command description); backend browser4-boot plugin listing endpoint`

#### AI Suggested Improvement

- Add a `Tools:` line to `plugin info <name>` (and optionally to `plugin list`) sourced from the registered custom tool executors.
- Or add `./b4w.ps1 tool list` that prints the MCP tool names (the CLI already fetches /mcp/tools for `tool call` validation).
- Fix the help sentence to match reality until then (point at GET /mcp/tools only).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 7: Installed plugins live in a build-output directory that the next runtime-bundle build wipes

**Severity:** Low
**Category:** Reliability

#### Reproduction

./b4w.ps1 plugin install browser4-plugins/browser4-pptx/target/browser4-pptx-4.14.1-SNAPSHOT.jar
./b4w.ps1 plugin info browser4-pptx   # Path: .../browser4-apps/browser4-bundle/target/runtime-bundle/_work/browser4-bundle-runtime-windows-x64/browser4-bundle-runtime-windows-x64/plugins/...
# then rebuild: powershell -ExecutionPolicy Bypass -File browser4-apps/browser4-bundle/build-runtime-bundle.ps1

#### Expected Behavior

A successful `plugin install` is durable, or the CLI warns that the location is a build artifact that a rebuild will reset.

#### Actual Behavior

The JAR is copied into the runtime bundle's `plugins/` directory, which `build-runtime-bundle.ps1` clears on every build (`Reset-CleanDirectory $pluginsDirectory 'plugins directory'`, line 909) before re-bundling the first-party plugins. After a rebuild the pptx plugin silently disappears (plugin list would show 5 again) with no message tying the loss to the rebuild. The install message only says 'Restart the application to activate'.

#### Root Cause Analysis

Plugin installation targets the assembled runtime bundle under browser4-apps/browser4-bundle/target/... rather than a user-level plugin directory (~/.browser4/...). Dev-mode rebuilds treat that tree as disposable output, so user-installed plugins are not part of any persistent store.

#### Code Pointer

`browser4-apps/browser4-bundle/build-runtime-bundle.ps1 (Reset-CleanDirectory $pluginsDirectory); plugin install handler in the backend's PluginService`

#### AI Suggested Improvement

- Install into a persistent, user-scoped plugin directory (e.g. <app-data>/plugins or ~/.browser4/plugins) that the runtime loads in addition to the bundle's own plugins/ dir.
- If the bundle directory must stay the target, have the build script preserve unknown JARs (or print a WARNING naming each plugin it removed together with the reinstall command).

#### Human Review

- [ ] **ACCEPT** — issue confirmed valid; suggested improvement is correct
- [ ] **ACCEPT with improvements** — issue valid but fix needs refinement (add details in Notes)
- [ ] **DEFER** — issue acknowledged but intentionally deferred (add rationale in Notes)
- [ ] **WONTFIX** — issue acknowledged but will not be fixed (add rationale in Notes)
- [ ] **REJECT** — issue invalid, not a problem, or already addressed
- [ ] **DUPLICATE** — issue duplicates another existing issue (reference in Notes)
- **Notes:**

---

### Issue 8: Page chrome is captured as slide content (Wikipedia status badges, sidebar text)

**Severity:** Low
**Category:** Product

#### Reproduction

./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}' on any Wikipedia article; inspect slides 2-3.

#### Expected Behavior

Slides contain article content; site chrome (protection badges, edit links, infobox boilerplate) is filtered out or at least confined to a small credit/source slide.

#### Actual Behavior

Slide 2 of the Solar System deck contains only the text 'Featured article' and 'Page semi-protected' (rendered from the page's status table) under an empty title; similar furniture appears in the Moon deck. Combined with the missing lead section this makes the opening slides of the deck look broken.

#### Root Cause Analysis

The extraction probe (PageContentExtractor.CONTENT_EXTRACTION_SCRIPT) skips only SCRIPT/STYLE/NAV/HEADER/FOOTER/... tags and a small exact-match boilerplate word list; it does not exclude elements inside the article's metadata band (short tables/badges above the lead) nor use a content-density/readability filter, so those nodes become leading 'paragraph'/'table' blocks with no heading, i.e. the untitled section.

#### Code Pointer

`browser4-plugins/browser4-pptx/src/main/kotlin/ai/platon/pulsar/pptx/service/PageContentExtractor.kt (CONTENT_EXTRACTION_SCRIPT, isVisible/shouldSkipText)`

#### AI Suggested Improvement

- Reuse the existing readability heuristic (`htmlsnapshot readability`) or a density threshold to pick the main content root and walk only that subtree.
- Drop leading blocks that precede the first heading when they contain no long text (>80 chars) — badges and link chips rarely do.
- Make the skip list configurable (`pptx.detect.exclude-selectors`) so users can suppress site-specific chrome.

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

**Completion Status:** Successful with a significant workaround — all three pages produced valid, non-empty PPTX files and every numeric acceptance criterion for the Solar System run was met (slideCount 48 > 5, blockCount 214 > 20, imageCount 36 > 0, durationMs 12760), but the first two attempts returned nothing at all and only succeeded after restarting the backend with HTTPS_PROXY set.

**Success Rate:** 85% — every step was completed; the deductions are for two failed generation attempts, ~20 minutes lost to diagnosis, and the confirmed content loss (55% paragraph coverage) that the tool does not report.

**Issues Found:** 8

**Major Blockers:** No proxy configured for plugin image downloads. The JVM's OkHttp downloader ignores the Windows wininet proxy, so every image download from Wikimedia timed out; generation ran 626s before the server cancelled it and the CLI returned a bare 'HTTP request timed out'. The CLI offers no way to set the plugin's proxy (config set rejects pptx.download.proxy), and --proxy/config set proxy do not reach plugin downloads, so the only fix was restarting the dev backend with HTTPS_PROXY set.

**Most Confusing Aspects:** (1) The identical, uninformative timeout error for both a 30s client timeout and a 600s server timeout — with no indication that the server was still working or which stage was stuck. (2) No way to tell that image downloads were the problem, or that a proxy was needed: the tool returns only after the entire pipeline finishes, so there is no progress signal. (3) Discovering the tool name — plugin list and plugin info never mention pptx_generate, and help tool call wrongly points at plugin list. (4) The 'outputDir' argument takes a directory while the legacy alias outputPath implies a file, so it is easy to pass a filename and silently get an oddly-named directory. (5) The deck's statistics look healthy (48 slides, 214 blocks) while 45% of the article's paragraphs are missing from the file.

**Most Valuable Improvements:** 1. Bound the image-download phase with an overall deadline and always return a partial deck plus a diagnostic instead of hanging to the server's async limit. 2. Honour the OS system proxy (or propagate --proxy / allow config set on pptx.* keys) so image downloads work wherever the browser works. 3. Stop silently discarding blocks on slide overflow — make the chunk splitter height-aware and report skippedBlocks. 4. Surface per-stage progress or the failed stage in the error, and mention --timeout 300+ for long-running plugin tools in the timeout error itself. 5. Make plugin tool names discoverable from the CLI (plugin info / tool list).

**Usability Rating:** 5/10

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

#### Issue 1: pptx_generate has no deadline: un-downloadable images stall it past the server's async limit and nothing is returned

./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}'   # on a page whose images the JVM cannot reach
Then retry with --timeout 600.

#### Issue 2: Plugin image downloads ignore the OS/WinINET system proxy; the CLI cannot configure the plugin's proxy

On a machine with a system proxy (here `ProxyEnable=1`, `ProxyServer=127.0.0.1:10808`):
./b4w.ps1 plugin list                # start backend normally
./b4w.ps1 -s pptx-eval open --headless https://en.wikipedia.org/wiki/Solar_System
./b4w.ps1 -s pptx-eval tool call pptx_generate --timeout 550

#### Issue 3: Slide overflow silently discards remaining content blocks; only images are counted as skipped

Generate a deck from a text-heavy page and diff the deck against the page (Solar System example):
./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}'
# then compare the extracted blocks with the rendered slide text (see pptx-evidence.md)

#### Issue 4: Untitled lead section yields meaningless slide titles like " (continued 1)" (leading space)

./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}' on https://en.wikipedia.org/wiki/Solar_System, then inspect slide 3 (or 3 of the Moon deck).

#### Issue 5: downloadedImages reports embedded + skipped, contradicting its documented meaning

./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}' on the Solar System page, then compare the result JSON with the backend log line 'PPTX image downloads: 51/52 succeeded, 1 failed'.

#### Issue 6: help tool call points users at `plugin list` to find tool names, but plugin list shows no tool names

./b4w.ps1 help "tool call"   # 'list available tools with `plugin list` or GET /mcp/tools'
./b4w.ps1 plugin list
./b4w.ps1 plugin info browser4-pptx

#### Issue 7: Installed plugins live in a build-output directory that the next runtime-bundle build wipes

./b4w.ps1 plugin install browser4-plugins/browser4-pptx/target/browser4-pptx-4.14.1-SNAPSHOT.jar
./b4w.ps1 plugin info browser4-pptx   # Path: .../browser4-apps/browser4-bundle/target/runtime-bundle/_work/browser4-bundle-runtime-windows-x64/browser4-bundle-runtime-windows-x64/plugins/...
# then rebuild: powershell -ExecutionPolicy Bypass -File browser4-apps/browser4-bundle/build-runtime-bundle.ps1

#### Issue 8: Page chrome is captured as slide content (Wikipedia status badges, sidebar text)

./b4w.ps1 -s pptx-eval tool call pptx_generate --json '{"outputDir": "<dir>"}' on any Wikipedia article; inspect slides 2-3.

